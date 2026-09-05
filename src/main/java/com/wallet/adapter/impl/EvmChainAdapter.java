package com.wallet.adapter.impl;

import com.wallet.adapter.ChainAdapter;
import com.wallet.crypto.AesGcmEncryptor;
import com.wallet.dto.WithdrawRequest;
import com.wallet.entity.Transaction;
import com.wallet.entity.Wallet;
import com.wallet.enums.ChainType;
import com.wallet.enums.TransactionStatus;
import com.wallet.enums.WalletType;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthGetBalance;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * EVM 链适配器（生产版，默认激活）
 * 策略模式实现：createWallet + executeWithdrawal
 */
@Component
public class EvmChainAdapter implements ChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(EvmChainAdapter.class);

    private final String rpcUrl;
    private final AesGcmEncryptor encryptor;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    private Web3j web3j;

    public EvmChainAdapter(@Value("${wallet.chain.evm-rpc-url}") String rpcUrl,
                           AesGcmEncryptor encryptor,
                           WalletRepository walletRepository,
                           TransactionRepository transactionRepository) {
        this.rpcUrl = rpcUrl;
        this.encryptor = encryptor;
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @PostConstruct
    public void init() {
        this.web3j = Web3j.build(new HttpService(rpcUrl));
        log.info("EvmChainAdapter initialized with rpc={}", rpcUrl);
    }

    // ──────────────────────── ChainAdapter 通用方法 ────────────────────────

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }

    @Override
    public BigDecimal getBalance(String address) {
        try {
            EthGetBalance response = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send();
            if (response.hasError()) throw new RuntimeException(response.getError().getMessage());
            return new BigDecimal(response.getBalance())
                    .divide(BigDecimal.TEN.pow(18), 18, RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get EVM balance for " + address, e);
        }
    }

    @Override
    public long getNonce(String address) {
        try {
            EthGetTransactionCount response = web3j.ethGetTransactionCount(
                    address, DefaultBlockParameterName.LATEST).send();
            if (response.hasError()) throw new RuntimeException(response.getError().getMessage());
            return response.getTransactionCount().longValue();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get EVM nonce for " + address, e);
        }
    }

    @Override
    public long getBlockNumber() {
        try {
            EthBlockNumber response = web3j.ethBlockNumber().send();
            if (response.hasError()) throw new RuntimeException(response.getError().getMessage());
            return response.getBlockNumber().longValue();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get EVM block number", e);
        }
    }

    @Override
    public String broadcastTransaction(String rawTx, String fromAddress) {
        try {
            EthSendTransaction response = web3j.ethSendRawTransaction(rawTx).send();
            if (response.hasError()) {
                throw new RuntimeException(response.getError().getMessage());
            }
            return response.getTransactionHash();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to broadcast EVM tx from " + fromAddress, e);
        }
    }

    @Override
    public int getConfirmations(String txHash) {
        try {
            EthGetTransactionReceipt response = web3j.ethGetTransactionReceipt(txHash).send();
            if (response.hasError()) return -1;
            Optional<TransactionReceipt> receipt = response.getTransactionReceipt();
            if (!receipt.isPresent()) return 0;
            long currentBlock = getBlockNumber();
            long txBlock = receipt.get().getBlockNumber().longValue();
            return (int) (currentBlock - txBlock);
        } catch (Exception e) {
            log.warn("Failed to get confirmations hash={} err={}", txHash, e.getMessage());
            return -1;
        }
    }

    @Override
    public int getSafeConfirmations() {
        return 6;
    }

    // ──────────────────────── 策略1：createWallet ────────────────────────

    /**
     * 生成 EVM 密钥对 → 派生 0x 地址 → 加密私钥 → 保存 DB
     */
    @Override
    public Wallet createWallet(WalletType walletType) {
        ECKeyPair ecKeyPair;
        try {
            ecKeyPair = Keys.createEcKeyPair();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create EVM key pair", e);
        }
        BigInteger privateKey = ecKeyPair.getPrivateKey();
        String address = "0x" + Keys.getAddress(privateKey);
        String pubKey = Numeric.toHexStringNoPrefix(ecKeyPair.getPublicKey().toByteArray());
        String encrypted = encryptor.encrypt(privateKey.toString(16));

        Wallet wallet = new Wallet();
        wallet.setChainType(ChainType.EVM);
        wallet.setAddress(address);
        wallet.setPublicKey(pubKey);
        wallet.setEncryptedPrivateKey(encrypted);
        wallet.setWalletType(walletType);
        wallet.setSafeConfirmations(6);

        Wallet saved = walletRepository.save(wallet);
        log.info("[EVM] Created wallet id={} address={} type={}", saved.getId(), address, walletType);
        return saved;
    }

    // ──────────────────────── 策略2：executeWithdrawal ────────────────────────

    /**
     * EVM 提现全流程：
     *   1. 查 nonce → max(链上, DB已分配) + 1
     *   2. 解密私钥 → Credentials
     *   3. 构造 RawTransaction → 签名
     *   4. 广播
     *   5. 写回 DB：wallet.nonce, tx.txHash, tx.gasFee, tx.status
     *
     * 调用前 Service 层已经做了：钱包存在校验、地址格式校验、链上余额校验、进程内加锁
     */
    @Override
    public String executeWithdrawal(Transaction tx, Wallet wallet, WithdrawRequest request) {
        // 1. nonce 分配
        long onChainNonce = getNonce(wallet.getAddress());
        int dbAllocatedNonce = wallet.getNonce();
        long nextNonce = Math.max(onChainNonce, dbAllocatedNonce) + 1;

        // 2. 解密私钥 → 签名凭证
        String decrypted = encryptor.decrypt(wallet.getEncryptedPrivateKey());
        BigInteger privateKey = new BigInteger(decrypted, 16);
        ECKeyPair ecKeyPair = ECKeyPair.create(privateKey);
        Credentials credentials = Credentials.create(ecKeyPair);

        // 3. 构造交易参数
        BigInteger value = request.getAmount()
                .multiply(BigDecimal.TEN.pow(18))  // ETH → wei
                .toBigInteger();
        BigInteger gasLimit = request.getGasLimit() != null
                ? request.getGasLimit().toBigInteger()
                : BigInteger.valueOf(21_000L);
        BigInteger gasPrice = request.getMaxFeePerGas() != null
                ? request.getMaxFeePerGas().toBigInteger()
                : BigInteger.valueOf(20_000_000_000L);  // 20 Gwei

        // 4. 构造 + 签名
        RawTransaction rawTx = RawTransaction.createEtherTransaction(
                BigInteger.valueOf(nextNonce), gasPrice, gasLimit,
                request.getToAddress(), value);
        byte[] signedMessage = TransactionEncoder.signMessage(rawTx, credentials);
        String rawTxHex = Numeric.toHexString(signedMessage);

        // 5. 广播前先写入 nonce 和 PENDING 状态（RPC 可能耗时较长）
        wallet.setNonce((int) nextNonce);
        walletRepository.save(wallet);

        tx.setNonce((int) nextNonce);
        tx.setStatus(TransactionStatus.PENDING);
        transactionRepository.save(tx);

        // 6. 广播
        String hash = broadcastTransaction(rawTxHex, wallet.getAddress());

        // 7. 广播成功：更新 txHash + gasFee + BROADCASTED
        tx.setTxHash(hash);
        tx.setStatus(TransactionStatus.BROADCASTED);
        tx.setGasFee(new BigDecimal(gasPrice.multiply(gasLimit))
                .divide(BigDecimal.TEN.pow(18), 18, RoundingMode.HALF_UP));
        transactionRepository.save(tx);

        log.info("[EVM] Withdrawal broadcasted wallet={} hash={} nonce={} amount={}",
                wallet.getAddress(), hash, nextNonce, request.getAmount());
        return hash;
    }
}
