package com.wallet.scanner.sync;

import com.wallet.entity.Wallet;
import com.wallet.enums.ChainType;
import com.wallet.enums.WalletType;
import com.wallet.repository.WalletRepository;
import com.wallet.scanner.entity.AddressIndex;
import com.wallet.scanner.repository.AddressIndexRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 地址索引同步服务 —— 启动时把 Wallet 表的地址同步到 AddressIndex
 * 让 Scanner 能匹配到"转入某个用户钱包地址"就是充值
 *
 * TODO: 真实生产应该有独立的地址管理服务（注册/注销/批量导入）
 *       这里先用启动同步做一个最简桥接
 */
@Component
public class AddressIndexSyncService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AddressIndexSyncService.class);

    private final WalletRepository walletRepo;
    private final AddressIndexRepository addressIndexRepo;

    public AddressIndexSyncService(WalletRepository walletRepo,
                                   AddressIndexRepository addressIndexRepo) {
        this.walletRepo = walletRepo;
        this.addressIndexRepo = addressIndexRepo;
    }

    @Override
    public void run(String... args) {
        // 目前只同步 EVM，其他链接上来再加
        List<Wallet> evmWallets = walletRepo.findByChainType(ChainType.EVM);
        int synced = 0;
        for (Wallet w : evmWallets) {
            if (addressIndexRepo.existsByChainTypeAndAddress(ChainType.EVM, w.getAddress())) {
                continue; // 已存在
            }
            AddressIndex idx = new AddressIndex();
            idx.setChainType(w.getChainType());
            idx.setAddress(w.getAddress());
            idx.setUserId("wallet_" + w.getId()); // 暂时用 wallet ID 当 userId
            // HOT 通常是用户充值地址，COLD 是离线冷钱包
            idx.setAddressType(w.getWalletType() == WalletType.HOT ? "USER_DEPOSIT" : "COLD_WALLET");
            addressIndexRepo.save(idx);
            synced++;
        }
        log.info("[AddressIndex] 启动同步完成，新增 {} 条 (total wallets={})", synced, evmWallets.size());
    }
}
