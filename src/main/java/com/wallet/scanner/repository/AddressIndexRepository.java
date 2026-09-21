package com.wallet.scanner.repository;

import com.wallet.enums.ChainType;
import com.wallet.scanner.entity.AddressIndex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AddressIndexRepository extends JpaRepository<AddressIndex, Long> {

    Optional<AddressIndex> findByChainTypeAndAddress(ChainType chainType, String address);

    boolean existsByChainTypeAndAddress(ChainType chainType, String address);
}
