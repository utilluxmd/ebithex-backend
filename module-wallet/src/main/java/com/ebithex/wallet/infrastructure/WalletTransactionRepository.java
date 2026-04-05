package com.ebithex.wallet.infrastructure;

import com.ebithex.wallet.domain.WalletTransaction;
import com.ebithex.wallet.domain.WalletTransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    Page<WalletTransaction> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    boolean existsByEbithexReferenceAndType(String ebithexReference, WalletTransactionType type);

    Optional<WalletTransaction> findByEbithexReferenceAndType(String ebithexReference, WalletTransactionType type);
}