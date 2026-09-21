package com.wallet.scanner.parser;

import com.wallet.scanner.fetcher.BlockFetcher.FetchedBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.core.methods.response.EthBlock;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交易解析器 —— Phase 1 最简版，只解析 ETH 原生转账
 *
 * 规则：
 *  - 遍历区块每笔交易，value > 0 且 to 非空（非合约创建）→ 原生转账
 *  - 不解析 receipt logs（ERC20/721 Transfer 事件留 Phase 3）
 *  - 不做内部交易（合约调用链里的 ETH 转账，需 debug_traceTransaction，Phase 5）
 */
public class TransactionParser {

    private static final Logger log = LoggerFactory.getLogger(TransactionParser.class);

    public List<ParsedTransfer> parse(FetchedBlock block) {
        List<ParsedTransfer> transfers = new ArrayList<>();
        if (block.rawBlock == null) return transfers;

        // 先把 receipt 按 txHash 建索引，后面取 from 地址方便
        Map<String, String> fromMap = new HashMap<>();
        block.receipts.forEach(r -> fromMap.put(r.getTransactionHash(), r.getFrom()));

        for (EthBlock.TransactionResult<EthBlock.TransactionObject> txRes : block.rawBlock.getTransactions()) {
            EthBlock.TransactionObject tx = txRes.get();
            BigInteger value = tx.getValue();

            // 跳过：value=0（合约调用、转账 token 等）、合约创建（to=null）
            if (value == null || value.signum() <= 0) continue;
            if (tx.getTo() == null || tx.getTo().isBlank()) continue;

            ParsedTransfer transfer = new ParsedTransfer();
            transfer.txHash = tx.getHash();
            transfer.blockNumber = block.blockNumber;
            transfer.blockHash = block.blockHash;
            transfer.blockTimestamp = block.timestamp;
            transfer.from = fromMap.getOrDefault(tx.getHash(), tx.getFrom());
            transfer.to = tx.getTo();
            // wei → ETH
            transfer.amount = new BigDecimal(value)
                    .divide(BigDecimal.TEN.pow(18), 18, RoundingMode.HALF_UP);
            transfer.asset = "ETH";
            transfer.tokenContract = null;
            transfer.source = "NATIVE";
            transfers.add(transfer);
        }

        if (!transfers.isEmpty()) {
            log.debug("[Parser] block={} parsed {} native ETH transfers", block.blockNumber, transfers.size());
        }
        return transfers;
    }

    /** 一笔解析出的资金转移（待 AddressMatcher 匹配收款方是否为交易所地址） */
    public static class ParsedTransfer {
        public String txHash;
        public long blockNumber;
        public String blockHash;
        public long blockTimestamp;
        public String from;
        public String to;            // 收款地址
        public BigDecimal amount;    // 人类可读单位（ETH = wei / 10^18）
        public String asset;         // ETH / USDT / ...
        public String tokenContract; // null = 原生币
        public String source;        // NATIVE / ERC20_TRANSFER / ...
        public String tokenId;       // ERC721/1155 专用
    }
}
