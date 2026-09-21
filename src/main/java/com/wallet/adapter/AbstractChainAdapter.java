package com.wallet.adapter;

import com.wallet.dto.WithdrawRequest;
import com.wallet.entity.Transaction;
import com.wallet.entity.Wallet;
import com.wallet.enums.TransactionStatus;
import com.wallet.repository.TransactionRepository;
import com.wallet.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * 链适配器抽象基类（模板方法模式）
 *
 * 提现全流程骨架固定为：
 *   checkBalance    → 通用（带降级）
 *   preBroadcast    → hook（更新 nonce / PENDING 状态等）
 *   buildRawTransaction → abstract（链特有：nonce/UTXO/签名/构造）
 *   broadcastTransaction → 通用（接口已有）
 *   postBroadcast   → hook（更新 txHash/gasFee/BROADCASTED 状态等）
 *
 * 子类只需实现 buildRawTransaction + 按需 override preBroadcast/postBroadcast。
 */
public abstract class AbstractChainAdapter implements ChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(AbstractChainAdapter.class);

    protected final WalletRepository walletRepository;
    protected final TransactionRepository transactionRepository;

    protected AbstractChainAdapter(WalletRepository walletRepository,
                                   TransactionRepository transactionRepository) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    // ──────────────────────── 模板方法 ────────────────────────

    /**
     * 提现模板方法（final，禁止子类覆盖）
     * 固定骨架：余额校验 → preBroadcast → buildRawTransaction → broadcast → postBroadcast
     */
    @Override
    public final String executeWithdrawal(Transaction tx, Wallet wallet, WithdrawRequest request) {
        // 1. 链上余额校验（通用，带降级）
        checkBalance(wallet.getAddress(), request.getAmount());

        // 2. 抽象钩子：构建链特有交易（nonce/UTXO/解密/签名/构造）
        //    子类可以直接修改 tx 对象设置 nonce、gasFee 等链特有字段
        String rawTx = buildRawTransaction(tx, wallet, request);

        // 3. hook：广播前保存状态（nonce 写 wallet、tx 置 PENDING 等）
        preBroadcast(tx, wallet);

        // 4. 广播
        String hash = broadcastTransaction(rawTx, wallet.getAddress());

        // 5. hook：广播后更新（gasFee 计算、txHash + BROADCASTED 状态）
        postBroadcast(tx, wallet, request, hash);

        // 6. 通用兜底：保证 txHash 和 BROADCASTED 状态一定被写入
        tx.setTxHash(hash);
        if (tx.getStatus() == null || tx.getStatus() != TransactionStatus.BROADCASTED) {
            tx.setStatus(TransactionStatus.BROADCASTED);
        }
        transactionRepository.save(tx);

        log.info("[{}] Withdrawal broadcasted wallet={} hash={} amount={}",
                getChainType(), wallet.getAddress(), hash, request.getAmount());
        return hash;
    }

    /**
     * 链上余额校验 + 降级
     * 查询失败时降级放行（依赖后续广播失败兜底），避免因链上查询偶发波动阻塞提现
     */
    protected void checkBalance(String address, BigDecimal need) {
        BigDecimal onChainBalance;
        try {
            onChainBalance = getBalance(address);
        } catch (Exception e) {
            log.warn("链上余额查询失败 address={} err={}, 降级放行", address, e.getMessage());
            onChainBalance = BigDecimal.valueOf(Long.MAX_VALUE);
        }
        if (onChainBalance.compareTo(need) < 0) {
            log.warn("余额不足 address={} onChain={} need={}", address, onChainBalance, need);
            throw new IllegalStateException("链上余额不足: 可用 " + onChainBalance + ", 需 " + need);
        }
    }

    // ──────────────────────── 抽象钩子 ────────────────────────

    /**
     * 构建并签名链特有交易
     * 子类负责：nonce/UTXO 准备、解密私钥、构造原始交易、签名
     * 可以直接修改 tx 对象（设置 nonce、gasFee 等）
     *
     * @return 原始交易的字节数组 hex 字符串，用于广播
     */
    protected abstract String buildRawTransaction(Transaction tx, Wallet wallet, WithdrawRequest request);

    // ──────────────────────── 可选 hook（默认空实现） ────────────────────────

    /**
     * 广播前的状态保存（默认空，子类按需覆盖）
     * 典型用途：更新 wallet.nonce、保存 tx 为 PENDING
     */
    protected void preBroadcast(Transaction tx, Wallet wallet) {
        // 默认什么都不做
    }

    /**
     * 广播后的额外更新（默认空，子类按需覆盖）
     * 典型用途：gasFee 计算、链特有字段回填
     *
     * 注意：txHash 和 BROADCASTED 状态由模板方法统一设置，子类无需重复设置
     */
    protected void postBroadcast(Transaction tx, Wallet wallet, WithdrawRequest request, String hash) {
        // 默认什么都不做
    }
}
