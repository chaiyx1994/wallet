package com.wallet.entity;

import com.wallet.enums.ChainType;
import com.wallet.enums.WalletType;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "t_wallet", indexes = {
        @Index(name = "idx_wallet_address", columnList = "chainType,address", unique = true)
})
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChainType chainType;

    @Column(nullable = false, length = 128)
    private String address;

    @Column(nullable = false, length = 1024)
    private String encryptedPrivateKey;

    @Column(length = 256)
    private String publicKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WalletType walletType;

    @Column(nullable = false, precision = 36, scale = 18)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(nullable = false)
    private int nonce = 0;

    @Column(nullable = false)
    private int safeConfirmations;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ChainType getChainType() { return chainType; }
    public void setChainType(ChainType chainType) { this.chainType = chainType; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getEncryptedPrivateKey() { return encryptedPrivateKey; }
    public void setEncryptedPrivateKey(String encryptedPrivateKey) { this.encryptedPrivateKey = encryptedPrivateKey; }
    public String getPublicKey() { return publicKey; }
    public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    public WalletType getWalletType() { return walletType; }
    public void setWalletType(WalletType walletType) { this.walletType = walletType; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public int getNonce() { return nonce; }
    public void setNonce(int nonce) { this.nonce = nonce; }
    public int getSafeConfirmations() { return safeConfirmations; }
    public void setSafeConfirmations(int safeConfirmations) { this.safeConfirmations = safeConfirmations; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
