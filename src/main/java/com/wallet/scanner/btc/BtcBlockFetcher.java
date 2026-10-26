package com.wallet.scanner.btc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.scanner.parser.TransactionParser.ParsedTransfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * BTC 区块拉取 + 解析（blockstream.info REST API + Jackson 反序列化）
 *
 * 和 EVM BlockFetcher 不同：BTC 区块只返回 txids，完整交易体要逐笔拉
 *   1. GET /block/{height} → BlockResponse { txids, block_hash, previous_block_hash, timestamp }
 *   2. GET /tx/{txid}      → TxResponse     { vin, vout: [{ value, scriptpubkey_address }] }
 *
 * 注：ObjectMapper 是 Spring Boot starter-web 默认带的，无需额外依赖
 */
@Component
public class BtcBlockFetcher {

    private static final Logger log = LoggerFactory.getLogger(BtcBlockFetcher.class);

    private static final BigDecimal SATOSHI_PER_BTC = BigDecimal.valueOf(100_000_000L);

    private final String blockstreamUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public BtcBlockFetcher(
            @Value("${wallet.chain.btc-blockstream-url:https://blockstream.info/api}") String blockstreamUrl,
            ObjectMapper objectMapper) {
        this.blockstreamUrl = blockstreamUrl.endsWith("/") ? blockstreamUrl.substring(0, blockstreamUrl.length() - 1) : blockstreamUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        // 不认识的字段直接忽略，接口改版不炸
        this.mapper = objectMapper.copy();
        this.mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 获取链头最新高度 */
    public long getLatestBlockNumber() {
        try {
            String resp = httpGet(blockstreamUrl + "/blocks/tip/height");
            return Long.parseLong(resp.trim());
        } catch (Exception e) {
            throw new RuntimeException("Failed to get BTC latest block", e);
        }
    }

    /**
     * 拉取指定区块并解析出所有 vout 转账
     * 返回 List<ParsedTransfer>，交给 AddressMatcher 匹配我们的交易所地址
     */
    public FetchedBlock fetchAndParseBlock(long blockHeight) {
        try {
            // 1. 拉区块头
            String blockJson = httpGet(blockstreamUrl + "/block/" + blockHeight);
            BlockResponse block = mapper.readValue(blockJson, BlockResponse.class);

            String parentHash = block.previous_block_hash;
            if (parentHash == null || parentHash.isBlank()) parentHash = "GENESIS";

            // 2. 拿到 txids，逐笔拉完整交易（简化：只扫 vout）
            List<ParsedTransfer> transfers = new ArrayList<>();
            for (String txid : block.txids) {
                try {
                    String txJson = httpGet(blockstreamUrl + "/tx/" + txid);
                    TxResponse tx = mapper.readValue(txJson, TxResponse.class);

                    // 2a. coinbase 交易（第一个 vin 有 coinbase 字段）跳过 —— 没法匹配充值来源
                    String fromAddr = null;
                    if (tx.vin != null && !tx.vin.isEmpty() && tx.vin.get(0).prevout != null) {
                        fromAddr = tx.vin.get(0).prevout.scriptpubkey_address;
                    }
                    if (fromAddr == null) continue;

                    // 2b. 遍历 vout，每个收款地址都是一笔可能的充值
                    for (Vout vout : tx.vout) {
                        BigDecimal amount = new BigDecimal(vout.value)
                                .divide(SATOSHI_PER_BTC, 8, RoundingMode.HALF_UP);

                        ParsedTransfer pt = new ParsedTransfer();
                        pt.txHash = txid;
                        pt.blockNumber = blockHeight;
                        pt.blockHash = block.block_hash;
                        pt.blockTimestamp = block.timestamp;
                        pt.from = fromAddr;
                        pt.to = vout.scriptpubkey_address;
                        pt.amount = amount;
                        pt.asset = "BTC";
                        pt.tokenContract = null;
                        pt.source = "NATIVE";
                        pt.tokenId = null;
                        transfers.add(pt);
                    }
                } catch (Exception e) {
                    log.warn("[BTC] tx={} 拉取/解析失败，跳过: {}", txid, e.getMessage());
                }
            }

            FetchedBlock fb = new FetchedBlock();
            fb.blockNumber = blockHeight;
            fb.blockHash = block.block_hash;
            fb.parentHash = parentHash;
            fb.timestamp = block.timestamp;
            fb.transfers = transfers;
            return fb;

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch/parse BTC block " + blockHeight, e);
        }
    }

    /** BTC 区块封装 —— 类比 EVM BlockFetcher.FetchedBlock，但字段更精简 */
    public static class FetchedBlock {
        public long blockNumber;
        public String blockHash;
        public String parentHash;
        public long timestamp;   // 秒
        public List<ParsedTransfer> transfers = new ArrayList<>();
    }

    // ──────────────────────── Jackson DTO（只映射我们关心的字段） ────────────────────────

    /** blockstream GET /block/{height} 响应 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BlockResponse {
        public String block_hash;
        public String previous_block_hash;
        public long timestamp;
        public List<String> txids = new ArrayList<>();
    }

    /** blockstream GET /tx/{txid} 响应 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TxResponse {
        public List<Vin> vin = new ArrayList<>();
        public List<Vout> vout = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Vin {
        public String coinbase;           // 存在即 coinbase 交易
        public Prevout prevout;           // 普通交易才有
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Prevout {
        public String scriptpubkey_address; // 付款地址
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Vout {
        public long value;                  // satoshi
        public String scriptpubkey_address; // 收款地址
    }

    // ──────────────────────── HTTP 工具 ────────────────────────

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url + ": " + resp.body());
        }
        return resp.body();
    }
}
