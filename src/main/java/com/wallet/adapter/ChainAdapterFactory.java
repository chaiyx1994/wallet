package com.wallet.adapter;

import com.wallet.enums.ChainType;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class ChainAdapterFactory {

    private final List<ChainAdapter> adapters;
    private final Map<ChainType, ChainAdapter> adapterMap = new EnumMap<>(ChainType.class);

    public ChainAdapterFactory(List<ChainAdapter> adapters) {
        this.adapters = adapters;
    }

    @PostConstruct
    public void init() {
        for (ChainAdapter adapter : adapters) {
            adapterMap.put(adapter.getChainType(), adapter);
        }
    }

    public ChainAdapter getAdapter(ChainType chainType) {
        ChainAdapter adapter = adapterMap.get(chainType);
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter for chain type: " + chainType);
        }
        return adapter;
    }
}
