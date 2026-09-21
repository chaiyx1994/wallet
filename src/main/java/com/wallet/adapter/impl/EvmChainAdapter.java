package com.wallet.adapter.impl;

import com.wallet.adapter.AbstractChainAdapter;
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
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * EVM 链适配器（模板方法实现）
 * 继承 AbstractChainAdapter，只需实现 buildRawTransaction + override hook
 *
 * Web3j 统一由 ScannerConfig 注册 Bean，EvmChainAdapter 和 DepositScanner 共用同一实例
 */
@Component
public class EvmChainAdapter extends AbstractChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(EvmChainAdapter.class);

    private final Web3j web3j;
    private final AesGcmEncryptor encryptor;

    public EvmChainAdapter(Web3j web3j,
                           AesGcmEncryptor encryptor,
                           WalletRepository walletRepository,
                           TransactionRepository transactionRepository) {
        super(walletRepository, transactionRepository);
        this.web3j = web3j;
        this.encryptor = encryptor;
        log.info("EvmChainAdapter initialized (Web3j injected)");
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

    // ──────────────────────── createWallet ────────────────────────

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

    // ──────────────────────── 模板方法钩子实现 ────────────────────────

    @Override
    protected String buildRawTransaction(Transaction tx, Wallet wallet, WithdrawRequest request) {
        // 1. nonce：取链上和 DB 已分配的较大值 + 1
        long onChainNonce = getNonce(wallet.getAddress());
        long nextNonce = Math.max(onChainNonce, wallet.getNonce()) + 1;
        tx.setNonce((int) nextNonce);

        // 2. 解密私钥 → Credentials
        String decrypted = encryptor.decrypt(wallet.getEncryptedPrivateKey());
        BigInteger privateKey = new BigInteger(decrypted, 16);
        ECKeyPair ecKeyPair = ECKeyPair.create(privateKey);
        Credentials credentials = Credentials.create(ecKeyPair);

        // 3. 构造交易参数（ETH → wei）
        BigInteger value = request.getAmount()
                .multiply(BigDecimal.TEN.pow(18))
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

        // 5. 顺手算好 gasFee 塞到 tx（广播不影响 gas 费，提前算安全）
        tx.setGasFee(new BigDecimal(gasPrice.multiply(gasLimit))
                .divide(BigDecimal.TEN.pow(18), 18, RoundingMode.HALF_UP));

        return Numeric.toHexString(signedMessage);
    }

    /** 广播前：更新 wallet nonce + tx PENDING（RPC 可能耗时较长） */
    @Override
    protected void preBroadcast(Transaction tx, Wallet wallet) {
        wallet.setNonce(tx.getNonce());
        walletRepository.save(wallet);

        tx.setStatus(TransactionStatus.PENDING);
        transactionRepository.save(tx);
    }
}
