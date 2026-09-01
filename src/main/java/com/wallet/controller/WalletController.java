package com.wallet.controller;

import com.wallet.adapter.ChainAdapter;
import com.wallet.adapter.ChainAdapterFactory;
import com.wallet.dto.ApiResponse;
import com.wallet.dto.CreateWalletRequest;
import com.wallet.entity.Wallet;
import com.wallet.enums.ChainType;
import com.wallet.repository.WalletRepository;
import com.wallet.service.WalletService;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/wallets")
public class WalletController {

    private final WalletService walletService;
    private final WalletRepository walletRepository;
    private final ChainAdapterFactory adapterFactory;

    public WalletController(WalletService walletService,
                            WalletRepository walletRepository,
                            ChainAdapterFactory adapterFactory) {
        this.walletService = walletService;
        this.walletRepository = walletRepository;
        this.adapterFactory = adapterFactory;
    }

    @PostMapping
    public ApiResponse<Wallet> create(@Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.createWallet(request);
        return ApiResponse.ok(wallet);
    }

    @GetMapping("/{id}")
    public ApiResponse<Wallet> get(@PathVariable Long id) {
        Wallet wallet = walletService.findById(id);
        return ApiResponse.ok(wallet);
    }

    @GetMapping
    public ApiResponse<List<Wallet>> list(@RequestParam(required = false) ChainType chainType) {
        List<Wallet> wallets;
        if (chainType != null) {
            wallets = walletRepository.findByChainType(chainType);
        } else {
            wallets = walletRepository.findAll();
        }
        return ApiResponse.ok(wallets);
    }

    @GetMapping("/{id}/balance")
    public ApiResponse<BigDecimal> getBalance(@PathVariable Long id) {
        Wallet wallet = walletService.findById(id);
        ChainAdapter adapter = adapterFactory.getAdapter(wallet.getChainType());
        BigDecimal balance = adapter.getBalance(wallet.getAddress());
        return ApiResponse.ok(balance);
    }
}
