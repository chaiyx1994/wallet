package com.wallet.controller;

import com.wallet.dto.ApiResponse;
import com.wallet.dto.WithdrawRequest;
import com.wallet.entity.Transaction;
import com.wallet.enums.TransactionStatus;
import com.wallet.repository.TransactionRepository;
import com.wallet.service.WithdrawalService;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

/**
 * 提现相关接口（只支持 ETH 链）
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final WithdrawalService withdrawalService;
    private final TransactionRepository transactionRepository;

    public TransactionController(WithdrawalService withdrawalService,
                                 TransactionRepository transactionRepository) {
        this.withdrawalService = withdrawalService;
        this.transactionRepository = transactionRepository;
    }

    /**
     * 提交提现请求
     * 生产环境需要先调用 POST /api/wallets 建钱包，拿到 walletId 后再提现
     */
    @PostMapping("/withdraw")
    public ApiResponse<Transaction> withdraw(@Valid @RequestBody WithdrawRequest request) {
        Transaction tx = withdrawalService.submitWithdrawal(request);
        return ApiResponse.ok(tx);
    }

    /**
     * 查询交易详情
     */
    @GetMapping("/{id}")
    public ApiResponse<Transaction> get(@PathVariable Long id) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("交易不存在: " + id));
        return ApiResponse.ok(tx);
    }

    /**
     * 按钱包查询交易列表
     */
    @GetMapping
    public ApiResponse<List<Transaction>> list(@RequestParam Long walletId) {
        return ApiResponse.ok(transactionRepository.findByWalletIdOrderByCreatedAtDesc(walletId));
    }
}
