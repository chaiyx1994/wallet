package com.wallet.adapter.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.adapter.AbstractChainAdapter;
import com.wallet.crypto.AesGcmEncryptor;
import com.wallet.dto.WithdrawRequest;
import com.wallet.entity.Transaction;
import com.wallet.entity.Wallet;
import com.wallet.enums.ChainType;
import com.wallet.enums.WalletType;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import org.bitcoinj.base.Address;
import org.bitcoinj.base.Coin;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.TransactionOutPoint;
import org.bitcoinj.crypto.ECKey;
import org.bitcoinj.params.MainNetParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * BTC 链适配器（模板方法实现）
 *
 * UTXO 模型要点：
 *   - 没有 nonce，每次提现从钱包地址的 UTXO 里选输入
 *   - 输入总金额 = 目标金额 + 手续费 + 找零（如果需要）
 *   - 用 P2WPKH 输出（Pay-to-Witness-Public-Key-Hash，现代地址格式）
 *   - 签名用 bitcoinj ECKey，直接调 Transaction.addSignedInput
 *
 * RPC 后端：blockstream.info 公开 REST API（无需注册，免费）
 *   - 主网: https://blockstream.info/api
 *   - 测试网: https://blockstream.info/testnet/api
 *
 * 单位换算：BTC ↔ satoshi (1 BTC = 10^8 satoshi)
 */
@Component
public class BtcChainAdapter extends AbstractChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(BtcChainAdapter.class);

    /** satoshi → BTC */
    private static final BigDecimal SATOSHI_PER_BTC = BigDecimal.valueOf(100_000_000L);

    /** 默认费率（sat/vB）—— 2025 年常见 10-50，这里保守取 20 */
    private static final long DEFAULT_FEE_RATE_SAT_VB = 20;

    /** 一个输入 + 两个输出 ≈ 148 + 34*2 = 216 vB，取 220 保守 */
    private static final long BASE_TX_VB = 220;

    /** BTC dust 阈值 —— 低于这个的找零就丢掉 */
    private static final long DUST_SATOSHI = 546L;

    private final AesGcmEncryptor encryptor;
    private final String blockstreamUrl;
    private final NetworkParameters network;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public BtcChainAdapter(AesGcmEncryptor encryptor,
                           WalletRepository walletRepository,
                           TransactionRepository transactionRepository,
                           ObjectMapper objectMapper,
                           @Value("${wallet.chain.btc-blockstream-url:https://blockstream.info/api}") String blockstreamUrl,
                           @Value("${wallet.chain.btc-network:mainnet}") String networkType) {
        super(walletRepository, transactionRepository);
        this.encryptor = encryptor;
        this.blockstreamUrl = blockstreamUrl.endsWith("/") ? blockstreamUrl.substring(0, blockstreamUrl.length() - 1) : blockstreamUrl;
        this.network = "testnet".equalsIgnoreCase(networkType)
                ? org.bitcoinj.params.TestNet3Params.get()
                : MainNetParams.get();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.mapper = objectMapper.copy();
        this.mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        log.info("BtcChainAdapter initialized, network={}, rpc={}", networkType, blockstreamUrl);
    }

    // ──────────────────────── ChainAdapter 通用方法 ────────────────────────

    @Override
    public ChainType getChainType() {
        return ChainType.BTC;
    }

    @Override
    public BigDecimal getBalance(String address) {
        try {
            // blockstream: GET /address/{address} → chain_stats (satoshi)
            String resp = httpGet(blockstreamUrl + "/address/" + address);
            JsonNode chainStats = mapper.readTree(resp).get("chain_stats");
            long funded = chainStats.path("funded_txo_sum").asLong(0);
            long spent = chainStats.path("spent_txo_sum").asLong(0);
            long balanceSatoshi = Math.max(0, funded - spent);
            return new BigDecimal(balanceSatoshi).divide(SATOSHI_PER_BTC, 8, RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get BTC balance for " + address, e);
        }
    }

    @Override
    public long getBlockNumber() {
        try {
            // blockstream: GET /blocks/tip/height
            String resp = httpGet(blockstreamUrl + "/blocks/tip/height");
            return Long.parseLong(resp.trim());
        } catch (Exception e) {
            throw new RuntimeException("Failed to get BTC block number", e);
        }
    }

    @Override
    public String broadcastTransaction(String rawTxHex, String fromAddress) {
        try {
            // blockstream: POST /tx (body = raw hex)
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(blockstreamUrl + "/tx"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString(rawTxHex))
                    .timeout(Duration.ofSeconds(30))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new RuntimeException("Broadcast failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            // 成功时 body 返回 txid（一串 hex）
            return resp.body().trim();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to broadcast BTC tx from " + fromAddress, e);
        }
    }

    @Override
    public int getConfirmations(String txHash) {
        try {
            // blockstream: GET /tx/{txid} → status.confirmations（未确认时该字段不存在）
            String resp = httpGet(blockstreamUrl + "/tx/" + txHash);
            JsonNode status = mapper.readTree(resp).get("status");
            if (status == null || !status.has("block_height")) return 0; // mempool
            long latest = getBlockNumber();
            long blockHeight = status.path("block_height").asLong(0);
            return blockHeight == 0 ? 0 : (int) (latest - blockHeight);
        } catch (Exception e) {
            log.warn("Failed to get BTC confirmations hash={} err={}", txHash, e.getMessage());
            return -1;
        }
    }

    @Override
    public int getSafeConfirmations() {
        // BTC 出块 ~10min/块，保守设 3 个 ≈ 30min
        return 3;
    }

    // ──────────────────────── createWallet ────────────────────────

    @Override
    public Wallet createWallet(WalletType walletType) {
        ECKey ecKey = new ECKey(); // bitcoinj 随机生成 secp256k1 密钥对
        // 用 P2WPKH（Pay-to-Witness-Public-Key-Hash）格式，地址以 bc1q 开头
        Address addr = Address.fromKey(network, ecKey, ScriptType.P2WPKH);
        String address = addr.toString();
        String pubKeyHex = bytesToHex(ecKey.getPubKey());
        String privKeyHex = ecKey.getPrivateKeyAsHex();
        String encrypted = encryptor.encrypt(privKeyHex);

        Wallet wallet = new Wallet();
        wallet.setChainType(ChainType.BTC);
        wallet.setAddress(address);
        wallet.setPublicKey(pubKeyHex);
        wallet.setEncryptedPrivateKey(encrypted);
        wallet.setWalletType(walletType);
        wallet.setSafeConfirmations(3);
        // BTC 无 nonce，保持默认 0

        Wallet saved = walletRepository.save(wallet);
        log.info("[BTC] Created wallet id={} address={} type={}", saved.getId(), address, walletType);
        return saved;
    }

    // ──────────────────────── BTC 内部用的轻量 UTXO 记录 ────────────────────────
    // bitcoinj 的 UTXO 要求 Script 参数，blockstream 不返回 scriptPubKey
    // 我们自己存 txid/vout/value 就够签名用了
    private record SimpleUtxo(String txidHex, int vout, long valueSatoshi) {}

    // ──────────────────────── 模板方法钩子实现 ────────────────────────

    @Override
    protected String buildRawTransaction(Transaction tx, Wallet wallet, WithdrawRequest request) {
        // 1. 目标金额：BTC → satoshi
        long targetSatoshi = request.getAmount()
                .multiply(SATOSHI_PER_BTC)
                .longValueExact();

        // 2. 估算手续费（sat/vB × 估算 vB）
        long fee = estimateFeeSatoshi();

        // 3. 从 blockstream 拉 UTXO + 贪心选输入
        List<SimpleUtxo> allUtxos = fetchUtxos(wallet.getAddress());
        List<SimpleUtxo> selected = selectInputsGreedy(allUtxos, targetSatoshi + fee);
        if (selected.isEmpty()) {
            throw new IllegalStateException("BTC 余额不足：无可用 UTXO");
        }

        long inputTotalSatoshi = selected.stream()
                .mapToLong(SimpleUtxo::valueSatoshi)
                .sum();
        long change = inputTotalSatoshi - targetSatoshi - fee;

        // 4. 解密私钥 → ECKey
        String decryptedPrivKey = encryptor.decrypt(wallet.getEncryptedPrivateKey());
        BigInteger privKeyBI = new BigInteger(decryptedPrivKey, 16);
        ECKey ecKey = ECKey.fromPrivate(privKeyBI);

        // 5. 构造交易（bitcoinj 0.17.x 无 TransactionBuilder，直接 new Transaction + addSignedInput）
        org.bitcoinj.core.Transaction btcTx = new org.bitcoinj.core.Transaction(network);

        // 5a. 逐个添加输入并签名（P2WPKH witness 签名，Script 传 null 即可）
        for (SimpleUtxo u : selected) {
            // blockstream 返回的 txid 是字节序，bitcoinj Sha256Hash 需要反转后的字节序
            byte[] txidBytes = hexToBytes(u.txidHex());
            byte[] reversed = new byte[32];
            for (int i = 0; i < 32; i++) reversed[i] = txidBytes[31 - i];
            Sha256Hash hash = Sha256Hash.wrap(reversed);
            TransactionOutPoint outPoint = new TransactionOutPoint((long) u.vout(), hash);
            btcTx.addSignedInput(outPoint, null, Coin.valueOf(u.valueSatoshi()), ecKey);
        }

        // 5b. 添加目标输出（to 地址）
        Address toAddr = Address.fromString(network, request.getToAddress());
        btcTx.addOutput(Coin.valueOf(targetSatoshi), toAddr);

        // 5c. 添加找零输出（超过 dust 阈值才加，否则算小费给矿工）
        if (change > DUST_SATOSHI) {
            Address selfAddr = Address.fromKey(network, ecKey, ScriptType.P2WPKH);
            btcTx.addOutput(Coin.valueOf(change), selfAddr);
        } else if (change > 0) {
            log.debug("[BTC] 找零 {} sat 低于 dust 阈值，算小费", change);
        }

        // 6. 回填 tx 元数据
        tx.setGasFee(new BigDecimal(fee).divide(SATOSHI_PER_BTC, 8, RoundingMode.HALF_UP));
        tx.setNonce(-1); // BTC 无 nonce

        // 7. 序列化 → hex（BaseMessage.serialize() 返回 byte[]）
        byte[] raw = btcTx.serialize();
        return bytesToHex(raw);
    }

    // ──────────────────────── UTXO 拉取 & 选输入 ────────────────────────

    /**
     * 从 blockstream 拉地址的所有未花费 UTXO
     * blockstream 返回的 JSON：[{"txid":"abc...","vout":0,"value":1000,"status":{"spent":false,...}}, ...]
     */
    private List<SimpleUtxo> fetchUtxos(String address) {
        try {
            String resp = httpGet(blockstreamUrl + "/address/" + address + "/utxo");
            List<SimpleUtxo> utxos = new ArrayList<>();
            JsonNode arr = mapper.readTree(resp);
            for (JsonNode node : arr) {
                JsonNode status = node.get("status");
                if (status != null && status.path("spent").asBoolean(false)) continue;
                utxos.add(new SimpleUtxo(
                        node.get("txid").asText(),
                        node.path("vout").asInt(),
                        node.path("value").asLong()));
            }
            log.debug("[BTC] address={} 有 {} 个可用 UTXO", address, utxos.size());
            return utxos;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch BTC UTXO for " + address, e);
        }
    }

    /**
     * 贪心选 UTXO：从小到大依次累加，够 target 就停
     */
    private List<SimpleUtxo> selectInputsGreedy(List<SimpleUtxo> utxos, long targetSatoshi) {
        List<SimpleUtxo> sorted = utxos.stream()
                .sorted((a, b) -> Long.compare(a.valueSatoshi(), b.valueSatoshi()))
                .collect(Collectors.toList());
        List<SimpleUtxo> result = new ArrayList<>();
        long sum = 0;
        for (SimpleUtxo u : sorted) {
            result.add(u);
            sum += u.valueSatoshi();
            if (sum >= targetSatoshi) break;
        }
        if (sum < targetSatoshi) {
            log.warn("[BTC] 余额不足：可用 {} sat, 需要 {} sat", sum, targetSatoshi);
            return new ArrayList<>();
        }
        return result;
    }

    /** 简易手续费估算：固定 vB × 固定费率（生产应接 fee estimator） */
    private long estimateFeeSatoshi() {
        return BASE_TX_VB * DEFAULT_FEE_RATE_SAT_VB;
    }

    // ──────────────────────── HTTP & 编码工具 ────────────────────────

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url + ": " + resp.body());
        }
        return resp.body();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
