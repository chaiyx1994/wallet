package com.wallet.scanner.confirm;

import com.wallet.enums.ChainType;
import com.wallet.scanner.emitter.DepositEventPublisher;
import com.wallet.scanner.entity.DepositEvent;
import com.wallet.scanner.entity.DepositEvent.DepositStatus;
import com.wallet.scanner.repository.DepositEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 确认管理器 —— 扫描 PENDING 充值事件，根据最新区块高度更新确认数
 *
 * 职责：
 *  - 不做任何 RPC 调用，只根据 Scanner 内部维护的最新区块高度计算
 *  - confirmations = latestBlock - deposit.blockNumber + 1
 *  - 达 safeConfirmations → 改为 CONFIRMED + 发 publishConfirmed 事件
 */
@Component
public class ConfirmationManager {

    private static final Logger log = LoggerFactory.getLogger(ConfirmationManager.class);

    private final DepositEventRepository depositEventRepo;
    private final DepositEventPublisher publisher;

    public ConfirmationManager(DepositEventRepository depositEventRepo,
                              DepositEventPublisher publisher) {
        this.depositEventRepo = depositEventRepo;
        this.publisher = publisher;
    }

    /**
     * 更新某链上所有 PENDING 充值事件的确认数
     */
    public void updateConfirmations(ChainType chainType, long latestBlockNumber) {
        List<DepositEvent> pendings = depositEventRepo.findByChainTypeAndStatusOrderByBlockNumberAsc(
                chainType, DepositStatus.PENDING);

        if (pendings.isEmpty()) return;

        int confirmedCount = 0;
        for (DepositEvent de : pendings) {
            int confirmations = (int) (latestBlockNumber - de.getBlockNumber() + 1);

            if (confirmations >= de.getSafeConfirmations()) {
                de.setStatus(DepositStatus.CONFIRMED);
                de.setConfirmations(confirmations);
                depositEventRepo.save(de);
                // 关键变：CONFIRMED 必须发事件，否则下游永远收不到入账通知
                publisher.publishConfirmed(de);
                confirmedCount++;
            } else if (confirmations > 0) {
                de.setConfirmations(confirmations);
                depositEventRepo.save(de);
            }
            // confirmations < 0 说明发生了 reorg，ReorgHandler 会回滚掉
        }

        if (confirmedCount > 0) {
            log.info("[Confirm] chain={} 有 {} 笔充值 PENDING → CONFIRMED", chainType, confirmedCount);
        }
    }
}
