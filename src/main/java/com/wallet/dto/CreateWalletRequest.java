package com.wallet.dto;

import com.wallet.enums.ChainType;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

public class CreateWalletRequest {

    @NotNull(message = "chainType is required")
    private ChainType chainType;

    @NotBlank(message = "walletType is required")
    private String walletType;

    public ChainType getChainType() { return chainType; }
    public void setChainType(ChainType chainType) { this.chainType = chainType; }
    public String getWalletType() { return walletType; }
    public void setWalletType(String walletType) { this.walletType = walletType; }
}
