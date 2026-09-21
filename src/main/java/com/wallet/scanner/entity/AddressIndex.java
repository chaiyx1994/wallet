package com.wallet.scanner.entity;

import com.wallet.enums.ChainType;

import javax.persistence.*;
import java.time.LocalDateTime;

/**
 * 充值地址索引 —— 把交易所维护的所有地址（用户充值/热/归集/冷）登记到这里
 * Scanner 发现转账后，用收款地址匹配这个表就能知道是哪个用户/哪类地址
 */
@Entity
@Table(name = "t_address_index", indexes = {
        @Index(name = "idx_ai_chain_addr", columnList = "chainType, address", unique = true),
        @Index(name = "idx_ai_user", columnList = "userId")
})
public class AddressIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ChainType chainType;

    @Column(nullable = false, length = 128)
    private String address;

    /** 关联的用户 ID（null 表示非用户地址，如热钱包/归集/冷钱包） */
    private String userId;

    /**
     * 地址类型：USER_DEPOSIT / HOT_WALLET / COLLECTION_WALLET / COLD_WALLET
     * 用 String 存储，值空间小，避免引入额外枚举
     */
    @Column(nullable = false, length = 32)
    private String addressType;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ChainType getChainType() { return chainType; }
    public void setChainType(ChainType chainType) { this.chainType = chainType; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAddressType() { return addressType; }
    public void setAddressType(String addressType) { this.addressType = addressType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
