package com.wallet.scanner.entity;

import com.wallet.enums.ChainType;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值事件 —— 扫描器识别到的所有资金入账记录
 * 状态流转：PENDING → CONFIRMED / REORGED
 * Scanner 本身不做余额计算和账务决策，只负责"发现并标记确认"
 */
@Entity
@Table(name = "t_deposit_event", indexes = {
        @Index(name = "idx_de_chain_tx", columnList = "chainType, txHash", unique = true),
        @Index(name = "idx_de_chain_status", columnList = "chainType, status"),
        @Index(name = "idx_de_chain_block", columnList = "chainType, blockNumber"),
        @Index(name = "idx_de_user", columnList = "userId")
})
public class DepositEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChainType chainType;

    /** 交易哈希 */
    @Column(nullable = false, length = 128)
    private String txHash;

    /** 所在区块高度 */
    @Column(nullable = false)
    private Long blockNumber;

    /** 所在区块 hash（reorg 时用于回滚） */
    @Column(nullable = false, length = 128)
    private String blockHash;

    /** 发送方地址 */
    @Column(nullable = false, length = 128)
    private String fromAddress;

    /** 收款方地址（交易所维护的地址） */
    @Column(nullable = false, length = 128)
    private String toAddress;

    /** 关联用户 ID（由 AddressMatcher 匹配填入，null 表示热/归集/冷钱包） */
    private String userId;

    /** 资产类型：原生币（ETH/SOL/BTC）或 ERC20/721 symbol */
    @Column(nullable = false, length = 32)
    private String asset;

    /** 金额 */
    @Column(nullable = false, precision = 36, scale = 18)
    private BigDecimal amount;

    /** 代币合约地址（null 表示原生币） */
    @Column(length = 128)
    private String tokenContract;

    /** ERC721/1155 的 tokenId（ERC20/原生币为 null） */
    @Column(length = 64)
    private String tokenId;

    /** 事件来源：NATIVE / ERC20_TRANSFER / ERC721_TRANSFER / INTERNAL */
    @Column(nullable = false, length = 32)
    private String source;

    /** 当前确认数 */
    @Column(nullable = false)
    private Integer confirmations;

    /** 期望安全确认阈值 */
    @Column(nullable = false)
    private Integer safeConfirmations;

    /** 状态：PENDING（待确认）/ CONFIRMED（安全）/ REORGED（被重组撤销） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DepositStatus status;

    /** 区块时间戳 */
    @Column(nullable = false)
    private LocalDateTime blockTime;

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

    public enum DepositStatus {
        PENDING, CONFIRMED, REORGED
    }

    // ──────────── getters/setters ────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ChainType getChainType() { return chainType; }
    public void setChainType(ChainType chainType) { this.chainType = chainType; }
    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }
    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }
    public String getBlockHash() { return blockHash; }
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAsset() { return asset; }
    public void setAsset(String asset) { this.asset = asset; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getTokenContract() { return tokenContract; }
    public void setTokenContract(String tokenContract) { this.tokenContract = tokenContract; }
    public String getTokenId() { return tokenId; }
    public void setTokenId(String tokenId) { this.tokenId = tokenId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public Integer getConfirmations() { return confirmations; }
    public void setConfirmations(Integer confirmations) { this.confirmations = confirmations; }
    public Integer getSafeConfirmations() { return safeConfirmations; }
    public void setSafeConfirmations(Integer safeConfirmations) { this.safeConfirmations = safeConfirmations; }
    public DepositStatus getStatus() { return status; }
    public void setStatus(DepositStatus status) { this.status = status; }
    public LocalDateTime getBlockTime() { return blockTime; }
    public void setBlockTime(LocalDateTime blockTime) { this.blockTime = blockTime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
