package com.wallet.scanner.emitter;

import com.wallet.scanner.entity.DepositEvent.DepositStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 充值事件 DTO —— 输出给下游消费的标准化格式
 * Scanner 本身不直接写账务数据库，通过这个 DTO 解耦
 */
public class DepositEventDto {

    private String chain;          // ethereum
    private String txHash;
    private Long blockNumber;
    private String from;
    private String to;             // 交易所地址
    private String userId;         // null = 热/归集/冷钱包
    private String asset;          // ETH / USDT / ...
    private BigDecimal amount;
    private String tokenContract;  // null = 原生币
    private String tokenId;        // null = 非 NFT
    private String source;         // NATIVE / ERC20_TRANSFER / ...
    private Integer confirmations;
    private Integer safeConfirmations;
    private DepositStatus status;
    private LocalDateTime blockTime;

    // ──────────── builder style setters ────────────

    public String getChain() { return chain; }
    public DepositEventDto setChain(String chain) { this.chain = chain; return this; }
    public String getTxHash() { return txHash; }
    public DepositEventDto setTxHash(String txHash) { this.txHash = txHash; return this; }
    public Long getBlockNumber() { return blockNumber; }
    public DepositEventDto setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; return this; }
    public String getFrom() { return from; }
    public DepositEventDto setFrom(String from) { this.from = from; return this; }
    public String getTo() { return to; }
    public DepositEventDto setTo(String to) { this.to = to; return this; }
    public String getUserId() { return userId; }
    public DepositEventDto setUserId(String userId) { this.userId = userId; return this; }
    public String getAsset() { return asset; }
    public DepositEventDto setAsset(String asset) { this.asset = asset; return this; }
    public BigDecimal getAmount() { return amount; }
    public DepositEventDto setAmount(BigDecimal amount) { this.amount = amount; return this; }
    public String getTokenContract() { return tokenContract; }
    public DepositEventDto setTokenContract(String tokenContract) { this.tokenContract = tokenContract; return this; }
    public String getTokenId() { return tokenId; }
    public DepositEventDto setTokenId(String tokenId) { this.tokenId = tokenId; return this; }
    public String getSource() { return source; }
    public DepositEventDto setSource(String source) { this.source = source; return this; }
    public Integer getConfirmations() { return confirmations; }
    public DepositEventDto setConfirmations(Integer confirmations) { this.confirmations = confirmations; return this; }
    public Integer getSafeConfirmations() { return safeConfirmations; }
    public DepositEventDto setSafeConfirmations(Integer safeConfirmations) { this.safeConfirmations = safeConfirmations; return this; }
    public DepositStatus getStatus() { return status; }
    public DepositEventDto setStatus(DepositStatus status) { this.status = status; return this; }
    public LocalDateTime getBlockTime() { return blockTime; }
    public DepositEventDto setBlockTime(LocalDateTime blockTime) { this.blockTime = blockTime; return this; }

    @Override
    public String toString() {
        return "DepositEventDto{" +
                "chain='" + chain + '\'' +
                ", txHash='" + txHash + '\'' +
                ", blockNumber=" + blockNumber +
                ", userId='" + userId + '\'' +
                ", asset='" + asset + '\'' +
                ", amount=" + amount +
                ", source='" + source + '\'' +
                ", confirmations=" + confirmations + '/' + safeConfirmations +
                ", status=" + status +
                '}';
    }
}
