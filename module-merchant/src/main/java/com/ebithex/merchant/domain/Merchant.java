package com.ebithex.merchant.domain;

import com.ebithex.shared.domain.Currency;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
// Clés API gérées dans l'entité ApiKey (table api_keys) depuis V18.


@Entity
@Table(name = "merchants")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String businessName;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String hashedSecret;

    private String webhookUrl;
    private String country;

    @Enumerated(EnumType.STRING)
    private Currency defaultCurrency = Currency.XOF;

    @Column(precision = 5, scale = 2)
    private BigDecimal customFeeRate;

    /** Si true, les transactions sont simulées — aucun appel opérateur réel n'est effectué. */
    private boolean testMode = false;

    /** Plafond journalier en XOF (NULL = pas de limite). */
    @Column(precision = 15, scale = 2)
    private BigDecimal dailyPaymentLimit;

    /** Plafond mensuel en XOF (NULL = pas de limite). */
    @Column(precision = 15, scale = 2)
    private BigDecimal monthlyPaymentLimit;

    private boolean active = true;
    private boolean kycVerified = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private KycStatus kycStatus = KycStatus.NONE;

    private String kycRejectionReason;
    private LocalDateTime kycSubmittedAt;
    private LocalDateTime kycReviewedAt;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}
