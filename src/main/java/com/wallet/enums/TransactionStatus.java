package com.wallet.enums;

/**
 * 提现交易状态流转：
 * CREATED → PENDING → BROADCASTED → CONFIRMED
 *                                    ↘ FAILED
 *                                    ↘ REVERTED
 */
public enum TransactionStatus {
    CREATED,     // 本地记录已创建，尚未广播
    PENDING,     // 已签名并标记待广播
    BROADCASTED, // 已广播到链上，等待打包
    CONFIRMED,   // 已收到足够区块确认
    FAILED,      // 广播或确认失败
    REVERTED     // 链上执行失败（如 EVM revert）
}
