package com.wallet.scanner.repository;

import com.wallet.enums.ChainType;
import com.wallet.scanner.entity.ScannedBlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScannedBlockRepository extends JpaRepository<ScannedBlock, Long> {

    Optional<ScannedBlock> findByChainTypeAndBlockNumber(ChainType chainType, Long blockNumber);

    /** 获取某链已扫描的最新区块 */
    Optional<ScannedBlock> findTopByChainTypeOrderByBlockNumberDesc(ChainType chainType);

    /** 获取某链某区间的已扫描区块，按区块号升序 */
    List<ScannedBlock> findByChainTypeAndBlockNumberGreaterThanEqualOrderByBlockNumberAsc(
            ChainType chainType, Long blockNumber);

    /** 删除某链某高度及之后的所有区块（reorg 回滚用） */
    @Modifying
    @Query("DELETE FROM ScannedBlock sb WHERE sb.chainType = :chain AND sb.blockNumber >= :fromBlock")
    int deleteFromBlock(@Param("chain") ChainType chain, @Param("fromBlock") Long fromBlock);
}
