package com.wallet.service;

import com.wallet.adapter.ChainAdapterFactory;
import com.wallet.dto.CreateWalletRequest;
import com.wallet.entity.Wallet;
import com.wallet.enums.ChainType;
import com.wallet.enums.WalletType;
import com.wallet.repository.WalletRepository;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;

/**
 * 钱包服务（策略模式路由层）
 * 所有链特有逻辑全部下沉到 ChainAdapter 实现
 * 这里只做参数转换 + Factory 路由 + 通用查询
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final ChainAdapterFactory adapterFactory;

    public WalletService(WalletRepository walletRepository, ChainAdapterFactory adapterFactory) {
        this.walletRepository = walletRepository;
        this.adapterFactory = adapterFactory;
    }

    /**
     * 创建新钱包（策略模式，ChainAdapterFactory 按链路由）
     * 当前支持：EVM、BTC（Solana 待补 SolanaChainAdapter）
     */
    @Transactional
    public Wallet createWallet(CreateWalletRequest request) {
        ChainType chainType = request.getChainType();
        WalletType walletType = WalletType.valueOf(request.getWalletType());
        return adapterFactory.getAdapter(chainType).createWallet(walletType);
    }

    public Wallet findById(Long id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Wallet not found: " + id));
    }
}
