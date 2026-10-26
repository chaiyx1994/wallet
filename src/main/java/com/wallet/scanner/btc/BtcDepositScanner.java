package com.wallet.scanner.btc;

import com.wallet.enums.ChainType;
import com.wallet.scanner.confirm.ConfirmationManager;
import com.wallet.scanner.entity.DepositEvent;
import com.wallet.scanner.entity.DepositEvent.DepositStatus;
import com.wallet.scanner.entity.ScannedBlock;
import com.wallet.scanner.entity.ScannerState;
import com.wallet.scanner.fetcher.BlockFetcher.FetchedBlock;
import com.wallet.scanner.matcher.AddressMatcher;
import com.wallet.scanner.matcher.AddressMatcher.MatchedTransfer;
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

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * BTC 充值扫描器 —— 主调度循环
 *
 * 和 EVM DepositScanner 的区别只有：
 *   1. 拉区块用 BtcBlockFetcher（blockstream REST）而不是 BlockFetcher（web3j）
 *   2. ChainType.BTC 而不是 ChainType.EVM
 *   3. safeConfirmations 默认 3（BTC ~30min，EVM 12 ~2.5min）
 *   4. tick 间隔默认 30s（BTC 出块慢 10min，没必要 10s 扫一次）
 *
 * 其余全部复用：ScannerState/ScannedBlock/DepositEvent/ReorgHandler/ConfirmationManager/AddressMatcher
 *   因为它们只依赖 ChainType 枚举，跟链实现无关
 */
@Component
@ConditionalOnProperty(name = "wallet.scanner.btc.enabled", havingValue = "true", matchIfMissing = false)
public class BtcDepositScanner {

    private static final Logger log = LoggerFactory.getLogger(BtcDepositScanner.class);

    private static final ChainType CHAIN = ChainType.BTC;

    private final BtcBlockFetcher btcFetcher;
    private final AddressMatcher matcher;
    private final ReorgHandler reorgHandler;
    private final ConfirmationManager confirmManager;
    private final ScannerStateRepository stateRepo;
    private final ScannedBlockRepository scannedBlockRepo;
    private final DepositEventRepository depositRepo;
    private final TransactionTemplate txTemplate;
    private final int safeConfirmations;
    private final int startFromBlock;

    public BtcDepositScanner(BtcBlockFetcher btcFetcher,
                             ScannerStateRepository stateRepo,
                             ScannedBlockRepository scannedBlockRepo,
                             DepositEventRepository depositRepo,
                             AddressMatcher matcher,
                             ReorgHandler reorgHandler,
                             ConfirmationManager confirmManager,
                             PlatformTransactionManager txManager,
                             @Value("${wallet.scanner.btc.safe-confirmations:3}") int safeConfirmations,
                             @Value("${wallet.scanner.btc.start-from-block:-1}") int startFromBlock) {
        this.btcFetcher = btcFetcher;
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
        log.info("[BTC Scanner] initialized, safeConfirmations={}, startFromBlock={}", safeConfirmations, startFromBlock);
    }

    /** 主循环 —— BTC 出块慢（~10min/块），tick 间隔 30s 足够 */
    @Scheduled(fixedDelayString = "${wallet.scanner.btc.tick-interval-ms:30000}")
    public void tick() {
        try {
            tickInner();
        } catch (Exception e) {
            log.error("[BTC Scanner] tick 异常，跳过本轮", e);
        }
    }

    private void tickInner() {
        long latestBlock = btcFetcher.getLatestBlockNumber();

        // ── 1. 决定扫描起点 ──
        ScannerState state = stateRepo.findByChainType(CHAIN).orElse(null);
        long scanFrom;
        if (state == null) {
            if (startFromBlock > 0) {
                scanFrom = startFromBlock;
            } else {
                scanFrom = latestBlock;
            }
            state = new ScannerState();
            state.setChainType(CHAIN);
            state.setLastBlockNumber(scanFrom - 1);
            state.setStartFromBlock((long) startFromBlock);
            stateRepo.save(state);
            log.info("[BTC Scanner] 首次启动，从 block={} 开始 (latest={})", scanFrom, latestBlock);
        } else {
            scanFrom = state.getLastBlockNumber() + 1;
        }

        // ── 2. 没有新块 → 直接返回 ──
        if (scanFrom > latestBlock) return;

        // ── 3. Reorg 检测 ──
        if (scanFrom > 0) {
            BtcBlockFetcher.FetchedBlock probe = btcFetcher.fetchAndParseBlock(scanFrom);
            long reorgFrom = reorgHandler.detectReorg(CHAIN, scanFrom, probe.parentHash);
            if (reorgFrom >= 0) {
                reorgHandler.rollback(CHAIN, reorgFrom);
                scanFrom = reorgFrom;
            }
        }

        // ── 4. 逐块扫描（每块独立事务） ──
        long lastScanned = state.getLastBlockNumber();
        for (long n = scanFrom; n <= latestBlock; n++) {
            try {
                BtcBlockFetcher.FetchedBlock fb = btcFetcher.fetchAndParseBlock(n);
                txTemplate.executeWithoutResult(status -> processOneBlock(fb));
                lastScanned = n;
            } catch (Exception e) {
                log.error("[BTC Scanner] block={} 拉取/处理失败，中断本轮，断点停在 {}", n, lastScanned, e);
                break;
            }
        }

        // ── 5. 更新 ScannerState ──
        final long finalLastScanned = lastScanned;
        txTemplate.executeWithoutResult(status -> {
            ScannerState s = stateRepo.findByChainType(CHAIN).orElseThrow();
            s.setLastBlockNumber(finalLastScanned);
            stateRepo.save(s);
        });

        // ── 6. 确认数更新 ──
        confirmManager.updateConfirmations(CHAIN, latestBlock);

        log.debug("[BTC Scanner] tick done, up to block={} (latest={})", lastScanned, latestBlock);
    }

    /** 单区块处理 —— 独立事务 */
    private void processOneBlock(BtcBlockFetcher.FetchedBlock fb) {
        if (!fb.transfers.isEmpty()) {
            List<MatchedTransfer> matched = matcher.match(fb.transfers, CHAIN);
            for (MatchedTransfer mt : matched) saveDepositEvent(mt, fb);
        }
        saveScannedBlock(fb);
    }

    private void saveScannedBlock(BtcBlockFetcher.FetchedBlock fb) {
        ScannedBlock sb = new ScannedBlock();
        sb.setChainType(CHAIN);
        sb.setBlockNumber(fb.blockNumber);
        sb.setBlockHash(fb.blockHash);
        sb.setParentHash(fb.parentHash);
        scannedBlockRepo.save(sb);
    }

    private void saveDepositEvent(MatchedTransfer mt, BtcBlockFetcher.FetchedBlock fb) {
        ParsedTransfer t = mt.transfer;
        if (depositRepo.findByChainTypeAndTxHash(CHAIN, t.txHash).isPresent()) return;

        DepositEvent de = new DepositEvent();
        de.setChainType(CHAIN);
        de.setTxHash(t.txHash);
        de.setBlockNumber(t.blockNumber);
        de.setBlockHash(t.blockHash);
        de.setFromAddress(t.from);
        de.setToAddress(t.to);
        de.setUserId(mt.userId);
        de.setAsset(t.asset);
        de.setAmount(t.amount);
        de.setSource(t.source);
        de.setConfirmations(1);
        de.setSafeConfirmations(safeConfirmations);
        de.setStatus(DepositStatus.PENDING);
        de.setBlockTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(fb.timestamp), ZoneOffset.UTC));
        depositRepo.save(de);
    }
}
