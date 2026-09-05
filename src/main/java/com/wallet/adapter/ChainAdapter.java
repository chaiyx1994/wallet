package com.wallet.adapter;

import com.wallet.dto.WithdrawRequest;
import com.wallet.entity.Transaction;
import com.wallet.entity.Wallet;
import com.wallet.enums.ChainType;
import com.wallet.enums.WalletType;

import java.math.BigDecimal;

/**
 * 多链适配器接口（策略模式核心抽象）
 * 每个链（EVM / Solana / BTC）提供自己的实现
 * 两个核心策略方法：createWallet + executeWithdrawal
 */
public interface ChainAdapter {

    ChainType getChainType();

    BigDecimal getBalance(String address);

    default long getNonce(String address) {
        return 0L;
    }

    long getBlockNumber();

    String broadcastTransaction(String rawTx, String fromAddress);

    default int getConfirmations(String txHash) {
        return -1;
    }

    int getSafeConfirmations();


    Wallet createWallet(WalletType walletType);


    String executeWithdrawal(Transaction tx, Wallet wallet, WithdrawRequest request);
}
