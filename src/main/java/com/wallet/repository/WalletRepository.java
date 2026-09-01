package com.wallet.repository;

import com.wallet.entity.Wallet;
import com.wallet.enums.ChainType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByChainTypeAndAddress(ChainType chainType, String address);

    List<Wallet> findByChainType(ChainType chainType);

    boolean existsByChainTypeAndAddress(ChainType chainType, String address);
}
