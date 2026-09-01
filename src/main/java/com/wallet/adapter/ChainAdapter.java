package com.wallet.adapter;

import com.wallet.enums.ChainType;

import java.math.BigDecimal;

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
}
