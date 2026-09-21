package com.wallet.scanner.repository;

import com.wallet.enums.ChainType;
import com.wallet.scanner.entity.ScannerState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ScannerStateRepository extends JpaRepository<ScannerState, ChainType> {
    Optional<ScannerState> findByChainType(ChainType chainType);
}
