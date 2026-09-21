package com.wallet.scanner.reorg;

import com.wallet.enums.ChainType;
import com.wallet.scanner.repository.DepositEventRepository;
import com.wallet.scanner.repository.ScannedBlockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reorg 处理器 —— 检测区块链重组并回滚扫描结果
 *
 * 机制：每次扫描新区块前，先检查它的 parentHash 是否等于数据库中记录的上一个已扫区块的 blockHash
 * 不相等 → 发生了重组 → 找到分叉点 → 回滚所有在那之后的 ScannedBlock + DepositEvent
 */
@Component
public class ReorgHandler {

    private static final Logger log = LoggerFactory.getLogger(ReorgHandler.class);

    private final ScannedBlockRepository scannedBlockRepo;
    private final DepositEventRepository depositEventRepo;

    /** 重组缓冲区大小：允许保留的最大已扫区块数，用于回滚 */
    private static final int REORG_BUFFER = 64;

    public ReorgHandler(ScannedBlockRepository scannedBlockRepo,
                        DepositEventRepository depositEventRepo) {
        this.scannedBlockRepo = scannedBlockRepo;
        this.depositEventRepo = depositEventRepo;
    }

    /**
     * 检测即将扫描的新区块是否延续了已扫描的链
     *
     * @param chainType 链类型
     * @param newBlockNumber 即将扫描的区块号
     * @param newBlockParentHash 该新区块的 parentHash（从 RPC 拉到的最新区块）
     * @return 需要回滚到的分叉点区块号（回滚后从这个号重新扫）；-1 表示不需要回滚
     */
    public long detectReorg(ChainType chainType, long newBlockNumber, String newBlockParentHash) {
        // 查数据库中已扫描的最新区块
        var latest = scannedBlockRepo.findTopByChainTypeOrderByBlockNumberDesc(chainType);
        if (latest.isEmpty()) {
            // 还没扫过，直接扫新块
            return -1;
        }

        long lastScannedNum = latest.get().getBlockNumber();
        String lastScannedHash = latest.get().getBlockHash();

        // 正常情况：新区块是已扫区块的下一个且 parentHash 匹配
        if (newBlockNumber == lastScannedNum + 1
                && lastScannedHash.equalsIgnoreCase(newBlockParentHash)) {
            return -1;
        }

        // 情况 1：新区块号 == 已扫块号 + 1，但 hash 不匹配 → 上一个块被替换了
        // 情况 2：新区块号 > 已扫块号 + 1，但中间某个块 hash 不匹配
        // 情况 3：新区块号 <= 已扫块号（跳过了，可能节点落后）

        // 找到分叉点：沿已扫描区块往回找，直到找到 parentHash 匹配的位置
        long reorgFrom = findForkPoint(chainType, newBlockNumber, newBlockParentHash);
        if (reorgFrom < 0) {
            log.warn("[Reorg] chain={} 无法找到分叉点，从最早已扫块重新开始", chainType);
            reorgFrom = latest.get().getBlockNumber() - REORG_BUFFER;
        }

        log.warn("[Reorg] chain={} 检测到重组！newBlock={} parentHash={} 回滚到 block={}",
                chainType, newBlockNumber, newBlockParentHash, reorgFrom);
        return reorgFrom;
    }

    /**
     * 从分叉点开始回滚所有扫描数据
     */
    @Transactional
    public void rollback(ChainType chainType, long fromBlock) {
        int sbDeleted = scannedBlockRepo.deleteFromBlock(chainType, fromBlock);
        int deDeleted = depositEventRepo.deleteFromBlock(chainType, fromBlock);
        log.warn("[Reorg] chain={} 回滚完成 ScannedBlock={} DepositEvent={}",
                chainType, sbDeleted, deDeleted);
    }

    /** 往回找：从已扫描的最新块开始，向上回溯，直到找到一个块的 blockHash == 当前 parentHash */
    private long findForkPoint(ChainType chainType, long newBlockNumber, String parentHash) {
        // 获取已扫描的最近 REORG_BUFFER 个区块
        var recentBlocks = scannedBlockRepo
                .findByChainTypeAndBlockNumberGreaterThanEqualOrderByBlockNumberAsc(
                        chainType, Math.max(0, newBlockNumber - REORG_BUFFER));

        for (int i = recentBlocks.size() - 1; i >= 0; i--) {
            var sb = recentBlocks.get(i);
            if (sb.getBlockHash().equalsIgnoreCase(parentHash)) {
                // 这个块是安全的分叉点，从下一个块开始重新扫
                return sb.getBlockNumber() + 1;
            }
        }
        return -1; // 没找到分叉点
    }
}
