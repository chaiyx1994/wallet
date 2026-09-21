package com.wallet.scanner.emitter;

import com.wallet.enums.ChainType;
import com.wallet.scanner.entity.DepositEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 事件输出器 —— Phase 1 用 Spring ApplicationEvent 解耦
 * 下游可以注册 @EventListener 监听，决定是入账/冻结/审核
 *
 * Phase 6 可替换为 Kafka/RabbitMQ，只需改这个类的实现，Scanner 主循环无感知
 */
@Component
public class DepositEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DepositEventPublisher.class);

    private final ApplicationEventPublisher appEventPublisher;

    public DepositEventPublisher(ApplicationEventPublisher appEventPublisher) {
        this.appEventPublisher = appEventPublisher;
    }

    /**
     * 发布一笔充值事件（刚扫描到，PENDING 状态）
     */
    public void publishPending(DepositEvent event) {
        DepositEventDto dto = buildDto(event);
        appEventPublisher.publishEvent(new DepositApplicationEvent(this, dto));
        log.info("[Emit] PENDING {}", dto);
    }

    /**
     * 发布一笔充值事件（确认数达标，CONFIRMED 状态）
     */
    public void publishConfirmed(DepositEvent event) {
        DepositEventDto dto = buildDto(event);
        appEventPublisher.publishEvent(new DepositApplicationEvent(this, dto));
        log.info("[Emit] CONFIRMED {}", dto);
    }

    private DepositEventDto buildDto(DepositEvent event) {
        return new DepositEventDto()
                .setChain(event.getChainType().name().toLowerCase())
                .setTxHash(event.getTxHash())
                .setBlockNumber(event.getBlockNumber())
                .setFrom(event.getFromAddress())
                .setTo(event.getToAddress())
                .setUserId(event.getUserId())
                .setAsset(event.getAsset())
                .setAmount(event.getAmount())
                .setTokenContract(event.getTokenContract())
                .setTokenId(event.getTokenId())
                .setSource(event.getSource())
                .setConfirmations(event.getConfirmations())
                .setSafeConfirmations(event.getSafeConfirmations())
                .setStatus(event.getStatus())
                .setBlockTime(event.getBlockTime());
    }

    /** Spring ApplicationEvent 包装，让下游可以 @EventListener 监听 */
    public static class DepositApplicationEvent extends org.springframework.context.ApplicationEvent {
        private final DepositEventDto dto;
        public DepositApplicationEvent(Object source, DepositEventDto dto) {
            super(source);
            this.dto = dto;
        }
        public DepositEventDto getDto() { return dto; }
    }

    // 监听示例（下游账务系统实现这个 bean 即可）：
    // @Component
    // class DepositListener {
    //     @EventListener
    //     void onDeposit(DepositEventPublisher.DepositApplicationEvent event) {
    //         DepositEventDto dto = event.getDto();
    //         if (dto.getStatus() == DepositStatus.CONFIRMED) {
    //             // 入账逻辑
    //         }
    //     }
    // }
}
