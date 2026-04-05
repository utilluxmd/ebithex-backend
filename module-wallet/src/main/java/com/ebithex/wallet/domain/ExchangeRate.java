package com.ebithex.wallet.domain;

import com.ebithex.shared.domain.Currency;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Taux de change entre deux devises, mis en cache en base de données.
 *
 * rate : 1 unité de fromCurrency = rate unités de toCurrency
 * Exemple : fromCurrency=USD, toCurrency=XOF, rate=605.00
 *           → 1 USD = 605 XOF
 */
@Entity
@Table(name = "exchange_rates",
       uniqueConstraints = @UniqueConstraint(
           name = "uq_exchange_rate",
           columnNames = {"from_currency", "to_currency"}))
@Getter @Setter
@Builder @NoArgsConstructor @AllArgsConstructor
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_currency", nullable = false, length = 5)
    private Currency fromCurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_currency", nullable = false, length = 5)
    private Currency toCurrency;

    @Column(nullable = false, precision = 20, scale = 8)
    private BigDecimal rate;

    @Column(nullable = false, length = 50)
    private String source;

    @CreationTimestamp
    @Column(name = "fetched_at", nullable = false)
    private LocalDateTime fetchedAt;
}