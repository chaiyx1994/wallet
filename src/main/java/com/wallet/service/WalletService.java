package com.wallet.service;

import com.wallet.crypto.AesGcmEncryptor;
import com.wallet.dto.CreateWalletRequest;
import com.wallet.entity.Wallet;
import com.wallet.enums.ChainType;
import com.wallet.enums.WalletType;
import com.wallet.repository.WalletRepository;
import org.bitcoinj.base.BitcoinNetwork;
import org.bitcoinj.base.ScriptType;
import org.bitcoinj.crypto.ECKey;
import org.p2p.solanaj.core.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.math.BigInteger;

@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final AesGcmEncryptor encryptor;
    private final BitcoinNetwork btcNetwork;

    public WalletService(WalletRepository walletRepository,
                         AesGcmEncryptor encryptor,
                         @Value("${wallet.chain.bitcoin-network:test}") String btcNetworkName) {
        this.walletRepository = walletRepository;
        this.encryptor = encryptor;
        this.btcNetwork = resolveBtcNetwork(btcNetworkName);
    }

    private static BitcoinNetwork resolveBtcNetwork(String name) {
        String n = name.toLowerCase().trim();
        return switch (n) {
            case "mainnet", "main", "prod", "bitcoin" -> BitcoinNetwork.MAINNET;
            case "signet", "testnet3" -> BitcoinNetwork.SIGNET;
            case "regtest" -> BitcoinNetwork.REGTEST;
            default -> BitcoinNetwork.TESTNET;
        };
    }

    @Transactional
    public Wallet createWallet(CreateWalletRequest request) {
        ChainType chainType = request.getChainType();
        WalletType walletType = WalletType.valueOf(request.getWalletType());

        switch (chainType) {
            case EVM:
                return createEvmWallet(walletType);
            case SOLANA:
                return createSolanaWallet(walletType);
            case BTC:
                return createBtcWallet(walletType);
            default:
                throw new IllegalArgumentException("Unsupported chain: " + chainType);
        }
    }

    private Wallet createEvmWallet(WalletType walletType) {
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
        log.info("Created EVM wallet id={} address={} type={}", saved.getId(), address, walletType);
        return saved;
    }

    private Wallet createSolanaWallet(WalletType walletType) {
        Account account = new Account();
        String address = account.getPublicKeyBase58();
        String encrypted = encryptor.encrypt(account.getPrivateKeyBase58());

        Wallet wallet = new Wallet();
        wallet.setChainType(ChainType.SOLANA);
        wallet.setAddress(address);
        wallet.setPublicKey(address);
        wallet.setEncryptedPrivateKey(encrypted);
        wallet.setWalletType(walletType);
        wallet.setSafeConfirmations(32);

        Wallet saved = walletRepository.save(wallet);
        log.info("Created SOL wallet id={} address={} type={}", saved.getId(), address, walletType);
        return saved;
    }

    private Wallet createBtcWallet(WalletType walletType) {
        ECKey key = new ECKey();
        String privateKeyHex = Numeric.toHexStringNoPrefix(key.getPrivKey().toByteArray());
        String encrypted = encryptor.encrypt(privateKeyHex);
        String pubKeyHex = Numeric.toHexStringNoPrefix(key.getPubKey());

        Wallet wallet = new Wallet();
        wallet.setChainType(ChainType.BTC);
        wallet.setAddress(key.toAddress(ScriptType.P2PKH, btcNetwork).toString());
        wallet.setPublicKey(pubKeyHex);
        wallet.setEncryptedPrivateKey(encrypted);
        wallet.setWalletType(walletType);
        wallet.setSafeConfirmations(6);

        Wallet saved = walletRepository.save(wallet);
        log.info("Created BTC wallet id={} address={} type={}", saved.getId(), wallet.getAddress(), walletType);
        return saved;
    }

    public String decryptPrivateKey(Wallet wallet) {
        return encryptor.decrypt(wallet.getEncryptedPrivateKey());
    }

    public Wallet findById(Long id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + id));
    }
}
