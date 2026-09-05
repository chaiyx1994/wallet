package com.wallet.service;

import com.wallet.adapter.ChainAdapter;
import com.wallet.adapter.ChainAdapterFactory;
import com.wallet.dto.WithdrawRequest;
import com.wallet.entity.Transaction;
import com.wallet.entity.Wallet;
import com.wallet.enums.TransactionStatus;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 提现服务（策略模式路由层）
 * 链特有的 nonce/UTXO/签名/广播全部下沉到 ChainAdapter.executeWithdrawal
 * 这里只做通用编排：参数校验 → 加锁 → 余额校验 → 调用 adapter → 失败兜底
 */
@Service
public class WithdrawalService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalService.class);

    /** 进程内地址锁（多进程需 DB/Redis 行锁补充） */
    private final ConcurrentHashMap<String, ReentrantLock> addressLocks = new ConcurrentHashMap<>();

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final ChainAdapterFactory adapterFactory;

    public WithdrawalService(WalletRepository walletRepository,
                             TransactionRepository transactionRepository,
                             ChainAdapterFactory adapterFactory) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.adapterFactory = adapterFactory;
    }

    /**
     * 提交提现请求
     *
     * @return 已广播的交易实体（status=BROADCASTED）
     */
    public Transaction submitWithdrawal(WithdrawRequest request) {
        // ── 1. 通用参数校验 ──
        Wallet wallet = walletRepository.findById(request.getWalletId())
                .orElseThrow(() -> new IllegalArgumentException("钱包不存在: " + request.getWalletId()));
        if (wallet.getChainType() != com.wallet.enums.ChainType.EVM) {
            throw new IllegalArgumentException("当前只支持 EVM 链提现，该钱包是: " + wallet.getChainType());
        }
        validateTargetAddress(request.getToAddress(), wallet.getChainType());
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("提现金额必须大于 0");
        }

        ChainAdapter adapter = adapterFactory.getAdapter(wallet.getChainType());

        // ── 2. 进程内地址锁 ──
        ReentrantLock lock = addressLocks.computeIfAbsent(wallet.getAddress(), k -> new ReentrantLock());
        lock.lock();
        try {
            // ── 3. 链上余额校验（通用，所有链都有余额查询） ──
            BigDecimal onChainBalance;
            try {
                onChainBalance = adapter.getBalance(wallet.getAddress());
            } catch (Exception e) {
                log.warn("链上余额查询失败 wallet={} err={}, 跳过链上校验", wallet.getAddress(), e.getMessage());
                onChainBalance = BigDecimal.valueOf(Long.MAX_VALUE);  // 降级放行，依赖后续广播失败兜底
            }
            if (onChainBalance.compareTo(request.getAmount()) < 0) {
                log.warn("余额不足 wallet={} onChain={} need={}", wallet.getAddress(), onChainBalance, request.getAmount());
                throw new IllegalStateException("链上余额不足: 可用 " + onChainBalance + ", 需 " + request.getAmount());
            }

            // ── 4. 创建 Transaction 实体（基础字段，策略方法会补充 nonce/txHash/gasFee） ──
            Transaction tx = new Transaction();
            tx.setWallet(wallet);
            tx.setChainType(wallet.getChainType());
            tx.setFromAddress(wallet.getAddress());
            tx.setToAddress(request.getToAddress());
            tx.setAmount(request.getAmount());

            try {
                // ── 5. 策略方法：链特有流程（nonce → 签名 → 构造 → 广播 → 写回 DB） ──
                adapter.executeWithdrawal(tx, wallet, request);
                return transactionRepository.findById(tx.getId())
                        .orElseThrow(() -> new IllegalStateException("交易持久化异常"));
            } catch (Exception e) {
                // ── 6. 广播失败兜底：标记 FAILED + failReason ──
                log.error("提现广播失败 wallet={} err={}", wallet.getAddress(), e.getMessage());
                if (tx.getId() != null) {
                    tx.setStatus(TransactionStatus.FAILED);
                    tx.setFailReason(e.getMessage());
                    transactionRepository.save(tx);
                }
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 目标地址格式校验（简单正则，生产建议加更严格校验）
     */
    private void validateTargetAddress(String address, com.wallet.enums.ChainType chainType) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("目标地址不能为空");
        }
        switch (chainType) {
            case EVM:
                if (!address.matches("^0x[a-fA-F0-9]{40}$")) {
                    throw new IllegalArgumentException("EVM 地址格式错误，必须是 0x 开头的 42 位十六进制");
                }
                break;
            // Solana/BTC 暂不在通用层做格式校验，交给 adapter 或链上自然失败
            default:
                break;
        }
    }
}
