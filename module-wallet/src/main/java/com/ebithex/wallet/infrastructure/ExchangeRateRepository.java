package com.ebithex.wallet.infrastructure;

import com.ebithex.shared.domain.Currency;
import com.ebithex.wallet.domain.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    Optional<ExchangeRate> findByFromCurrencyAndToCurrency(Currency from, Currency to);

    @Modifying
    @Query(value = "INSERT INTO exchange_rates (id, from_currency, to_currency, rate, source, fetched_at) VALUES (gen_random_uuid(), :from, :to, :rate, :source, NOW()) ON CONFLICT (from_currency, to_currency) DO UPDATE SET rate = :rate, source = :source, fetched_at = NOW()", nativeQuery = true)
    void upsert(
        @Param("from") String from,
        @Param("to") String to,
        @Param("rate") BigDecimal rate,
        @Param("source") String source);
}