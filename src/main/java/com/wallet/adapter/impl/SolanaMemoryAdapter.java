package com.wallet.adapter.impl;

import com.wallet.adapter.ChainAdapter;
import com.wallet.enums.ChainType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile("!prod")
public class SolanaMemoryAdapter implements ChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(SolanaMemoryAdapter.class);

    private final AtomicLong blockNumber = new AtomicLong(2000L);

    @Override
    public ChainType getChainType() {
        return ChainType.SOLANA;
    }

    @Override
    public BigDecimal getBalance(String address) {
        return BigDecimal.ZERO;
    }

    @Override
    public long getNonce(String address) {
        return 0L;
    }

    @Override
    public long getBlockNumber() {
        return blockNumber.incrementAndGet();
    }

    @Override
    public String broadcastTransaction(String rawTx, String fromAddress) {
        log.info("[SOL-memory] broadcastTransaction from={}", fromAddress);
        return java.util.Base64.getEncoder().encodeToString(
                java.util.UUID.randomUUID().toString().getBytes());
    }

    @Override
    public int getSafeConfirmations() {
        return 32;
    }
}
