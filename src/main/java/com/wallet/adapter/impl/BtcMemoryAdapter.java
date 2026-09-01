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
public class BtcMemoryAdapter implements ChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(BtcMemoryAdapter.class);

    private final AtomicLong blockNumber = new AtomicLong(3000L);

    @Override
    public ChainType getChainType() {
        return ChainType.BTC;
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
        log.info("[BTC-memory] broadcastTransaction from={}", fromAddress);
        return "mock-txid-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    @Override
    public int getSafeConfirmations() {
        return 6;
    }
}
