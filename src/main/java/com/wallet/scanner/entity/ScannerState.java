package com.wallet.scanner.entity;

import com.wallet.enums.ChainType;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 扫描器状态 —— 记录每条链已扫描到哪里（断点续扫）
 * 全表只有 1 条记录 per chain
 */
@Entity
@Table(name = "t_scanner_state")
public class ScannerState {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChainType chainType;

    /** 已扫描的最新区块高度 */
    @Column(nullable = false)
    private Long lastBlockNumber;

    /** 已扫描的最新区块 hash，用于 reorg 检测 */
    @Column(nullable = false, length = 128)
    private String lastBlockHash;

    /** 初始启动时从哪个高度开始扫（0 表示从链头实时扫） */
    private Long startFromBlock;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public ChainType getChainType() { return chainType; }
    public void setChainType(ChainType chainType) { this.chainType = chainType; }
    public Long getLastBlockNumber() { return lastBlockNumber; }
    public void setLastBlockNumber(Long lastBlockNumber) { this.lastBlockNumber = lastBlockNumber; }
    public String getLastBlockHash() { return lastBlockHash; }
    public void setLastBlockHash(String lastBlockHash) { this.lastBlockHash = lastBlockHash; }
    public Long getStartFromBlock() { return startFromBlock; }
    public void setStartFromBlock(Long startFromBlock) { this.startFromBlock = startFromBlock; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
