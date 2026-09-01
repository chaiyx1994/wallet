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
public class EvmMemoryAdapter implements ChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(EvmMemoryAdapter.class);

    private final AtomicLong blockNumber = new AtomicLong(1000L);

    @Override
    public ChainType getChainType() {
        return ChainType.EVM;
    }

    @Override
    public BigDecimal getBalance(String address) {
        log.debug("[EVM-memory] getBalance for address={}", address);
        return BigDecimal.ZERO;
    }

    @Override
    public long getNonce(String address) {
        log.debug("[EVM-memory] getNonce for address={}", address);
        return 0L;
    }

    @Override
    public long getBlockNumber() {
        return blockNumber.incrementAndGet();
    }

    @Override
    public String broadcastTransaction(String rawTx, String fromAddress) {
        log.info("[EVM-memory] broadcastTransaction from={} rawTxLength={}", fromAddress, rawTx == null ? 0 : rawTx.length());
        String fakeHash = "0x" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 64);
        return fakeHash;
    }

    @Override
    public int getSafeConfirmations() {
        return 6;
    }
}
