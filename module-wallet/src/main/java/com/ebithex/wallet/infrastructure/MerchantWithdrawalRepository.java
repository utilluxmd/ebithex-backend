package com.ebithex.wallet.infrastructure;

import com.ebithex.wallet.domain.MerchantWithdrawal;
import com.ebithex.wallet.domain.WithdrawalStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantWithdrawalRepository extends JpaRepository<MerchantWithdrawal, UUID> {

    Page<MerchantWithdrawal> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<MerchantWithdrawal> findByStatusOrderByCreatedAtDesc(WithdrawalStatus status, Pageable pageable);

    Page<MerchantWithdrawal> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Optional<MerchantWithdrawal> findByReference(String reference);
}