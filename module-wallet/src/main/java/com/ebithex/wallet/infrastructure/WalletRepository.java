package com.ebithex.wallet.infrastructure;

import com.ebithex.shared.domain.Currency;
import com.ebithex.wallet.domain.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    /** Tous les wallets d'un marchand (toutes devises). */
    List<Wallet> findByMerchantId(UUID merchantId);

    /** Wallet d'un marchand dans une devise spécifique. */
    Optional<Wallet> findByMerchantIdAndCurrency(UUID merchantId, Currency currency);

    /**
     * SELECT FOR UPDATE sur un wallet spécifique (merchant + devise).
     * Sérialise les mises à jour de balance pour éviter les lectures fantômes
     * en cas d'événements concurrents.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.merchantId = :merchantId AND w.currency = :currency")
    Optional<Wallet> findByMerchantIdAndCurrencyForUpdate(
        @Param("merchantId") UUID merchantId,
        @Param("currency") Currency currency);
}