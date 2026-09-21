package com.wallet.scanner.repository;

import com.wallet.enums.ChainType;
import com.wallet.scanner.entity.DepositEvent;
import com.wallet.scanner.entity.DepositEvent.DepositStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DepositEventRepository extends JpaRepository<DepositEvent, Long> {

    Optional<DepositEvent> findByChainTypeAndTxHash(ChainType chainType, String txHash);

    /** 查某链上所有 PENDING 的充值事件（ConfirmationManager 轮询用） */
    List<DepositEvent> findByChainTypeAndStatusOrderByBlockNumberAsc(
            ChainType chainType, DepositStatus status);

    /** 删除某链某高度及之后的所有充值事件（reorg 回滚用） */
    @Modifying
    @Query("DELETE FROM DepositEvent de WHERE de.chainType = :chain AND de.blockNumber >= :fromBlock")
    int deleteFromBlock(@Param("chain") ChainType chain, @Param("fromBlock") Long fromBlock);
}
