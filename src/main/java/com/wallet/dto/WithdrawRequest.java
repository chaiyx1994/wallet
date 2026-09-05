package com.wallet.dto;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 提现请求
 * 只实现 ETH（EVM 原生币）提现，所以 chainType 固定 EVM
 */
public class WithdrawRequest {

    @NotNull(message = "walletId 不能为空")
    private Long walletId;

    @NotBlank(message = "toAddress 不能为空")
    private String toAddress;

    @NotNull(message = "amount 不能为空")
    @DecimalMin(value = "0", inclusive = false, message = "amount 必须大于 0")
    private BigDecimal amount;

    /**
     * gas 上限，可选。未传默认 21000。
     * 用户可能因特殊交易（合约交互）需要更高上限。
     */
    private BigDecimal gasLimit;

    /**
     * gas 单价（wei），可选。未传默认 20 Gwei。
     * 生产环境建议由系统根据链上拥堵情况动态设置。
     */
    private BigDecimal maxFeePerGas;

    public Long getWalletId() { return walletId; }
    public void setWalletId(Long walletId) { this.walletId = walletId; }
    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getGasLimit() { return gasLimit; }
    public void setGasLimit(BigDecimal gasLimit) { this.gasLimit = gasLimit; }
    public BigDecimal getMaxFeePerGas() { return maxFeePerGas; }
    public void setMaxFeePerGas(BigDecimal maxFeePerGas) { this.maxFeePerGas = maxFeePerGas; }
}
