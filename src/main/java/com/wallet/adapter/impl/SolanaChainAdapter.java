package com.wallet.adapter.impl;

import com.wallet.adapter.AbstractChainAdapter;
import com.wallet.crypto.AesGcmEncryptor;
import com.wallet.dto.WithdrawRequest;
import com.wallet.entity.Transaction;
import com.wallet.entity.Wallet;
import com.wallet.enums.ChainType;
import com.wallet.enums.WalletType;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.rpc.Cluster;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Base64;

/**
 * Solana 链适配器
 *
 * Solana 特点：
 *   - ed25519 密钥对（不是 secp256k1）
 *   - 地址 base58 编码（PublicKey 32 字节 → base58）
 *   - 私钥 64 字节：前 32 是 seed，后 32 是公钥（Solana 标准 Account 格式）
 *   - 单位 lamport：1 SOL = 10^9 lamports
 *   - 无 nonce（Solana 用 recentBlockhash + 交易序列号代替）
 *   - safeConfirmations 默认 1（Solana 出块 ~400ms）
 *
 * solanaj 内置 HD 支持：Account.fromMnemonic / fromBip39Mnemonic / fromBip44Mnemonic
 * 交易所场景不用 HD，直接 Account() 随机生成
 */
@Component
public class SolanaChainAdapter extends AbstractChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(SolanaChainAdapter.class);

    /** lamport → SOL */
    private static final BigDecimal LAMPORT_PER_SOL = BigDecimal.valueOf(1_000_000_000L);

    private final AesGcmEncryptor encryptor;
    private final RpcClient rpcClient;
    private final String rpcUrl;

    public SolanaChainAdapter(AesGcmEncryptor encryptor,
                              WalletRepository walletRepository,
                              TransactionRepository transactionRepository,
                              @Value("${wallet.chain.solana-rpc-url:https://api.devnet.solana.com}") String rpcUrl) {
        super(walletRepository, transactionRepository);
        this.encryptor = encryptor;
        this.rpcUrl = rpcUrl;
        this.rpcClient = new RpcClient(rpcUrl); // solanaj 1.20.4 直接传 String 构造
        log.info("SolanaChainAdapter initialized, rpc={}", rpcUrl);
    }

    // ──────────────────────── ChainAdapter 通用方法 ────────────────────────

    @Override
    public ChainType getChainType() {
        return ChainType.SOLANA;
    }

    @Override
    public BigDecimal getBalance(String address) {
        try {
            PublicKey pub = new PublicKey(address);
            long lamports = rpcClient.getApi().getBalance(pub);
            return new BigDecimal(lamports).divide(LAMPORT_PER_SOL, 9, RoundingMode.HALF_UP);
        } catch (RpcException e) {
            throw new RuntimeException("Failed to get Solana balance for " + address + ": " + e.getMessage(), e);
        }
    }

    @Override
    public long getBlockNumber() {
        try {
            return rpcClient.getApi().getBlockHeight();
        } catch (RpcException e) {
            throw new RuntimeException("Failed to get Solana block height: " + e.getMessage(), e);
        }
    }

    @Override
    public String broadcastTransaction(String rawTx, String fromAddress) {
        try {
            // solanaj RpcApi.sendRawTransaction 接收 base64 编码的序列化交易
            return rpcClient.getApi().sendRawTransaction(rawTx, null);
        } catch (RpcException e) {
            throw new RuntimeException("Failed to broadcast Solana tx from " + fromAddress + ": " + e.getMessage(), e);
        }
    }

    @Override
    public int getConfirmations(String txHash) {
        try {
            // 通过 getTransaction 查询 slot 信息
            org.p2p.solanaj.rpc.types.ConfirmedTransaction tx = rpcClient.getApi().getTransaction(txHash);
            if (tx != null && tx.getMeta() != null) {
                // meta.err != null 表示 tx 失败但已确认，meta.err == null 且 slot != 0 表示已确认
                return 1;
            }
            return 0;
        } catch (RpcException e) {
            // txHash 不存在就返回 0 （mempool 里还没打包）
            log.debug("[Solana] tx={} not yet confirmed or not found: {}", txHash, e.getMessage());
            return 0;
        }
    }

    @Override
    public int getSafeConfirmations() {
        // Solana 出块 ~400ms，1 个 slot 基本确认
        return 1;
    }

    // ──────────────────────── createWallet ────────────────────────

    @Override
    public Wallet createWallet(WalletType walletType) {
        // solanaj Account() 自动生成 ed25519 密钥对（64 字节：seed 32 + pubkey 32）
        Account account = new Account();
        PublicKey pubKey = account.getPublicKey();

        String address = pubKey.toBase58();                           // base58 编地址
        byte[] secretKeyBytes = account.getSecretKey();               // 64 字节私钥
        String publicKeyHex = bytesToHex(pubKey.toByteArray());       // 公钥 32 字节 → hex
        String privKeyHex = bytesToHex(secretKeyBytes);               // 私钥 64 字节 → hex
        String encrypted = encryptor.encrypt(privKeyHex);

        Wallet wallet = new Wallet();
        wallet.setChainType(ChainType.SOLANA);
        wallet.setAddress(address);
        wallet.setPublicKey(publicKeyHex);
        wallet.setEncryptedPrivateKey(encrypted);
        wallet.setWalletType(walletType);
        wallet.setSafeConfirmations(1);
        wallet.setNonce(0); // Solana 无 nonce

        Wallet saved = walletRepository.save(wallet);
        log.info("[Solana] Created wallet id={} address={} type={}", saved.getId(), address, walletType);
        return saved;
    }

    // ──────────────────────── 模板方法钩子（提现待补） ────────────────────────

    @Override
    protected String buildRawTransaction(Transaction tx, Wallet wallet, WithdrawRequest request) {
        // TODO: 构造 Solana Transaction + Message + AccountMeta 指令 → 签名 → base64 序列化
        throw new UnsupportedOperationException("Solana 提现 MVP 待实现");
    }

    // ──────────────────────── 工具 ────────────────────────

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
