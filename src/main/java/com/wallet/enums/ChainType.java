package com.wallet.enums;

public enum ChainType {
    EVM("evm"),
    SOLANA("solana"),
    BTC("btc");

    private final String code;

    ChainType(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
