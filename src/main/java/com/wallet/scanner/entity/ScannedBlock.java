package com.wallet.scanner.entity;

import com.wallet.enums.ChainType;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 已扫描区块哈希链 —— reorg 检测核心
 * 每扫一个区块就存一条，保留最近 N 个区块的 hash + parentHash 链
 *
 * 检测 reorg：新区块的 parentHash != 表里上一个已扫区块的 blockHash → 分叉了
 * 回滚：删除从分叉点之后的所有 ScannedBlock + DepositEvent，重新扫描
 */
@Entity
@Table(name = "t_scanned_block", indexes = {
        @Index(name = "idx_sb_chain_block", columnList = "chainType, blockNumber", unique = true),
        @Index(name = "idx_sb_chain_hash", columnList = "chainType, blockHash"),
        @Index(name = "idx_sb_chain_parent", columnList = "chainType, parentHash")
})
public class ScannedBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChainType chainType;

    @Column(nullable = false)
    private Long blockNumber;

    @Column(nullable = false, length = 128)
    private String blockHash;

    @Column(nullable = false, length = 128)
    private String parentHash;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ChainType getChainType() { return chainType; }
    public void setChainType(ChainType chainType) { this.chainType = chainType; }
    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }
    public String getBlockHash() { return blockHash; }
    public void setBlockHash(String blockHash) { this.blockHash = blockHash; }
    public String getParentHash() { return parentHash; }
    public void setParentHash(String parentHash) { this.parentHash = parentHash; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
