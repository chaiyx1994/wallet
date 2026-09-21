package com.wallet.scanner.fetcher;

import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterNumber;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * EVM 区块拉取器 —— 顺序版（Phase 1 最简实现）
 *
 * 职责：从 RPC 拉取区块数据，封装成 FetchedBlock 传递给 Parser
 * 不做任何业务判断（不检查是否有充值、不处理 reorg、不匹配地址）
 */
public class BlockFetcher {

    private final Web3j web3j;

    public BlockFetcher(Web3j web3j) {
        this.web3j = web3j;
    }

    /** 获取链当前最新区块高度 */
    public long getLatestBlockNumber() {
        try {
            return web3j.ethBlockNumber().send().getBlockNumber().longValue();
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch latest block number", e);
        }
    }

    /**
     * 拉取指定区块 —— 包含区块头信息 + 所有交易 + 所有收据
     */
    public FetchedBlock fetchBlock(long blockNumber) {
        try {
            // 1. 拉区块头 + 交易列表
            EthBlock ethBlock = web3j.ethGetBlockByNumber(
                    new DefaultBlockParameterNumber(blockNumber), true).send();
            if (ethBlock.hasError()) {
                throw new RuntimeException("RPC error: " + ethBlock.getError().getMessage());
            }
            EthBlock.Block block = ethBlock.getBlock();
            if (block == null) {
                throw new RuntimeException("Block not found at height " + blockNumber);
            }

            // 2. 拉每笔交易的收据 —— 顺序拉，Phase 4 再做 batch
            List<TransactionReceipt> receipts = new ArrayList<>();
            List<String> txHashes = new ArrayList<>();
            for (EthBlock.TransactionResult<EthBlock.TransactionObject> tx : block.getTransactions()) {
                String hash = tx.get().getHash();
                txHashes.add(hash);
                EthGetTransactionReceipt receiptResp = web3j.ethGetTransactionReceipt(hash).send();
                if (receiptResp.hasError()) continue;
                receiptResp.getTransactionReceipt().ifPresent(receipts::add);
            }

            // 3. 组装
            FetchedBlock fb = new FetchedBlock();
            fb.blockNumber = block.getNumber().longValue();
            fb.blockHash = block.getHash();
            fb.parentHash = block.getParentHash();
            fb.timestamp = block.getTimestamp().longValue();
            fb.txHashes = txHashes;
            fb.receipts = receipts;
            fb.rawBlock = block;
            return fb;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch block " + blockNumber, e);
        }
    }

    /**
     * 一次拉取连续 N 个区块（Phase 1 用简单循环，Phase 4 做 batch）
     */
    public List<FetchedBlock> fetchBlocks(long from, long to) {
        List<FetchedBlock> result = new ArrayList<>();
        for (long n = from; n <= to; n++) {
            result.add(fetchBlock(n));
        }
        return result;
    }

    /** 封装：一次 fetch 的完整区块数据 */
    public static class FetchedBlock {
        public long blockNumber;
        public String blockHash;
        public String parentHash;
        public long timestamp;           // 秒
        public List<String> txHashes = Collections.emptyList();
        public List<TransactionReceipt> receipts = Collections.emptyList();
        public EthBlock.Block rawBlock;  // 保留原始对象，Parser 可按需取用
    }
}
