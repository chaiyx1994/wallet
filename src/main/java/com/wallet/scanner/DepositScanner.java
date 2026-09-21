package com.wallet.scanner;

import com.wallet.enums.ChainType;
import com.wallet.scanner.confirm.ConfirmationManager;
import com.wallet.scanner.entity.DepositEvent;
import com.wallet.scanner.entity.DepositEvent.DepositStatus;
import com.wallet.scanner.entity.ScannedBlock;
import com.wallet.scanner.entity.ScannerState;
import com.wallet.scanner.fetcher.BlockFetcher;
import com.wallet.scanner.fetcher.BlockFetcher.FetchedBlock;
import com.wallet.scanner.matcher.AddressMatcher;
import com.wallet.scanner.matcher.AddressMatcher.MatchedTransfer;
import com.wallet.scanner.parser.TransactionParser;
import com.wallet.scanner.parser.TransactionParser.ParsedTransfer;
import com.wallet.scanner.reorg.ReorgHandler;
import com.wallet.scanner.repository.DepositEventRepository;
import com.wallet.scanner.repository.ScannedBlockRepository;
import com.wallet.scanner.repository.ScannerStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.protocol.Web3j;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 充值扫描器 —— 主调度循环
 *
 * 每轮 tick（默认 10s）：
 *   1. 取链头最新高度
 *   2. 初始化/读取 ScannerState，决定从哪个块开始扫
 *   3. ReorgHandler 检测 + 回滚
 *   4. 逐块（每块独立事务）：BlockFetcher → TransactionParser → AddressMatcher → 落库
 *   5. 更新 ScannerState + 确认数更新
 */
@Component
@ConditionalOnProperty(name = "wallet.scanner.enabled", havingValue = "true", matchIfMissing = true)
public class DepositScanner {

    private static final Logger log = LoggerFactory.getLogger(DepositScanner.class);

    private final BlockFetcher blockFetcher;
    private final TransactionParser parser;
    private final AddressMatcher matcher;
    private final ReorgHandler reorgHandler;
    private final ConfirmationManager confirmManager;

    private final ScannerStateRepository stateRepo;
    private final ScannedBlockRepository scannedBlockRepo;
    private final DepositEventRepository depositRepo;

    /** 用 TransactionTemplate 做编程式事务，避免 tick 级长事务 */
    private final TransactionTemplate txTemplate;

    private final int safeConfirmations;
    private final int startFromBlock;

    public DepositScanner(Web3j web3j,
                          ScannerStateRepository stateRepo,
                          ScannedBlockRepository scannedBlockRepo,
                          DepositEventRepository depositRepo,
                          AddressMatcher matcher,
                          ReorgHandler reorgHandler,
                          ConfirmationManager confirmManager,
                          PlatformTransactionManager txManager,
                          @Value("${wallet.scanner.safe-confirmations:12}") int safeConfirmations,
                          @Value("${wallet.scanner.start-from-block:-1}") int startFromBlock) {
        this.blockFetcher = new BlockFetcher(web3j);
        this.parser = new TransactionParser();
        this.stateRepo = stateRepo;
        this.scannedBlockRepo = scannedBlockRepo;
        this.depositRepo = depositRepo;
        this.matcher = matcher;
        this.reorgHandler = reorgHandler;
        this.confirmManager = confirmManager;
        this.txTemplate = new TransactionTemplate(txManager);
        this.safeConfirmations = safeConfirmations;
        this.startFromBlock = startFromBlock;
    }

    @PostConstruct
    public void init() {
        log.info("[Scanner] initialized, safeConfirmations={}, startFromBlock={}", safeConfirmations, startFromBlock);
    }

    /** 主循环 —— 定时触发 */
    @Scheduled(fixedDelayString = "${wallet.scanner.tick-interval-ms:10000}")
    public void tick() {
        try {
            tickInner();
        } catch (Exception e) {
            log.error("[Scanner] tick 异常，跳过本轮", e);
        }
    }

    private void tickInner() {
        ChainType chain = ChainType.EVM;
        long latestBlock = blockFetcher.getLatestBlockNumber();

        // ── 1. 决定扫描起点 ──
        ScannerState state = stateRepo.findByChainType(chain).orElse(null);
        long scanFrom;
        if (state == null) {
            if (startFromBlock > 0) {
                scanFrom = startFromBlock;
            } else {
                scanFrom = latestBlock;
            }
            state = new ScannerState();
            state.setChainType(chain);
            state.setLastBlockNumber(scanFrom - 1);
            state.setStartFromBlock((long) startFromBlock);
            stateRepo.save(state);
            log.info("[Scanner] 首次启动，从 block={} 开始 (latest={})", scanFrom, latestBlock);
        } else {
            scanFrom = state.getLastBlockNumber() + 1;
        }

        // ── 2. 没有新块 → 直接返回（链头没动，确认数不可能变） ──
        if (scanFrom > latestBlock) {
            return;
        }

        // ── 3. Reorg 检测（区块链重组，即链上某个块被替换了） ──
        if (scanFrom > 0) {                                                    // 从 block 1 开始才有 parentHash 可比
            FetchedBlock probe = blockFetcher.fetchBlock(scanFrom);            // 拉取即将扫描的第一个区块，拿到它的 parentHash
            long reorgFrom = reorgHandler.detectReorg(chain, scanFrom, probe.parentHash); // 拿新区块的 parentHash 去和数据库里"上一个已扫区块的 blockHash"比对；不匹配=重组了，返回需要回滚到的高度（-1 表示没重组）
            if (reorgFrom >= 0) {                                              // 返回值 >= 0 说明检测到重组
                reorgHandler.rollback(chain, reorgFrom);                       // 回滚：删掉从分叉点开始的所有 ScannedBlock + DepositEvent，数据清干净
                scanFrom = reorgFrom;                                          // 扫描起点改为分叉点，重新扫被替换的那些块
            }                                                                  // 如果没重组（reorgFrom = -1），什么都不做，继续往下正常扫
        }                                                                      // 分支结束；scanFrom = 0 时跳过（只在 genesis block 的 parentHash 场景才会发生，ETH 基本碰不到）

        // ── 4. 逐块扫描（每块独立事务） ──
        long lastScanned = state.getLastBlockNumber();
        for (long n = scanFrom; n <= latestBlock; n++) {
            try {
                FetchedBlock fb = blockFetcher.fetchBlock(n);              // RPC 拉区块；异常则中断本批次扫描（避免跳过块导致后续 reorg 判断失真）
                txTemplate.executeWithoutResult(status -> processOneBlock(chain, fb)); // 单区块事务：解析+匹配+落 ScannedBlock
                lastScanned = n;                                            // 成功处理完才推进断点
            } catch (Exception e) {                                          // RPC 超时/块暂时不可用等
                log.error("[Scanner] block={} 拉取/处理失败，中断本轮扫描，断点停在 {}", n, lastScanned, e);
                break;                                                       // 关键修改：不是 skip 而是 break。跳过块会让 ScannedBlock 链断档，下轮 reorg 检测 parentHash 时链中间缺环，只能回滚。不如直接停在已知最后成功的位置，下次 tick 从断点续扫同一块
            }
        }

        // ── 5. 更新 ScannerState ──
        final long finalLastScanned = lastScanned;
        txTemplate.executeWithoutResult(status -> {
            ScannerState s = stateRepo.findByChainType(chain).orElseThrow();
            s.setLastBlockNumber(finalLastScanned);
            stateRepo.save(s);
        });

        // ── 6. 扫完新块后，统一更新 PENDING 充值的确认数 ──
        confirmManager.updateConfirmations(chain, latestBlock);

        log.debug("[Scanner] tick done, up to block={} (latest={})", lastScanned, latestBlock);
    }

    /**
     * 处理单个区块 —— 独立事务
     */
    private void processOneBlock(ChainType chain, FetchedBlock fb) {
        List<ParsedTransfer> transfers = parser.parse(fb);
        if (!transfers.isEmpty()) {
            List<MatchedTransfer> matched = matcher.match(transfers, chain);
            for (MatchedTransfer mt : matched) {
                saveDepositEvent(chain, mt, fb);
            }
        }
        // 无论是否有充值都存区块 hash（reorg 检测依赖）
        saveScannedBlock(chain, fb);
    }

    private void saveScannedBlock(ChainType chain, FetchedBlock fb) {
        ScannedBlock sb = new ScannedBlock();
        sb.setChainType(chain);
        sb.setBlockNumber(fb.blockNumber);
        sb.setBlockHash(fb.blockHash);
        sb.setParentHash(fb.parentHash);
        scannedBlockRepo.save(sb);
    }

    private void saveDepositEvent(ChainType chain, MatchedTransfer mt, FetchedBlock fb) {
        ParsedTransfer t = mt.transfer;
        if (depositRepo.findByChainTypeAndTxHash(chain, t.txHash).isPresent()) {
            return; // 幂等
        }

        DepositEvent de = new DepositEvent();
        de.setChainType(chain);
        de.setTxHash(t.txHash);
        de.setBlockNumber(t.blockNumber);
        de.setBlockHash(t.blockHash);
        de.setFromAddress(t.from);
        de.setToAddress(t.to);
        de.setUserId(mt.userId);
        de.setAsset(t.asset);
        de.setAmount(t.amount);
        de.setTokenContract(t.tokenContract);
        de.setTokenId(t.tokenId);
        de.setSource(t.source);
        de.setConfirmations(1);
        de.setSafeConfirmations(safeConfirmations);
        de.setStatus(DepositStatus.PENDING);
        de.setBlockTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(fb.timestamp), ZoneOffset.UTC));
        depositRepo.save(de);
    }
}
