package com.ebithex.aml.application;

import com.ebithex.aml.domain.AmlAlert;
import com.ebithex.aml.domain.AmlSeverity;
import com.ebithex.aml.domain.AmlStatus;
import com.ebithex.aml.infrastructure.AmlAlertRepository;
import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.sanctions.application.SanctionsScreeningService;
import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.sandbox.SandboxContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour AmlScreeningService — règles de détection AML.
 * Repositories et SanctionsScreeningService sont mockés.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AmlScreeningService — règles de détection anti-blanchiment")
class AmlScreeningServiceTest {

    @Mock private AmlAlertRepository        alertRepository;
    @Mock private TransactionRepository     transactionRepository;
    @Mock private SanctionsScreeningService sanctionsScreeningService;

    private AmlScreeningService service;

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AmlScreeningService(alertRepository, transactionRepository, sanctionsScreeningService);
        // Valeurs par défaut des @Value (identiques aux propriétés de prod)
        ReflectionTestUtils.setField(service, "maxTxPerHour",        20);
        ReflectionTestUtils.setField(service, "maxTxPerDay",         100);
        ReflectionTestUtils.setField(service, "maxTxPerWeek",        500);
        ReflectionTestUtils.setField(service, "highAmountThreshold", new BigDecimal("5000000"));

        // Par défaut : pas de hit sanctions (lenient car non utilisé par les tests review_*)
        org.mockito.Mockito.lenient()
            .when(sanctionsScreeningService.checkTransaction(any()))
            .thenReturn(SanctionsScreeningService.SanctionsCheckResult.noHit());
    }

    @AfterEach
    void tearDown() {
        // Garantit que SandboxContextHolder est remis à zéro après chaque test
        SandboxContextHolder.clear();
    }

    // ── Sandbox bypass ────────────────────────────────────────────────────────

    @Test
    @DisplayName("screen() — mode sandbox → aucune alerte créée")
    void screen_sandboxMode_skipsAllRules() {
        SandboxContextHolder.set(true);
        Transaction tx = buildTx(new BigDecimal("10000000")); // > seuil

        service.screen(tx);

        verify(alertRepository, never()).save(any());
        verify(alertRepository, never()).saveAll(any());
    }

    // ── Sanctions ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("screen() — sanctions hit requiresBlock → AML_BLOCKED levé")
    void screen_sanctionsHitBlock_throwsAmlBlocked() {
        when(sanctionsScreeningService.checkTransaction(any()))
            .thenReturn(new SanctionsScreeningService.SanctionsCheckResult(
                true, true, 0.97, "UN", "John Doe", "Correspondance confirmée"));
        when(alertRepository.save(any(AmlAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        Transaction tx = buildTx(new BigDecimal("100000"));

        assertThatThrownBy(() -> service.screen(tx))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AML_BLOCKED));

        // L'alerte CRITICAL doit être sauvegardée avant le blocage
        ArgumentCaptor<AmlAlert> captor = ArgumentCaptor.forClass(AmlAlert.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(AmlSeverity.CRITICAL);
        assertThat(captor.getValue().getRuleCode()).isEqualTo("SANCTIONS_HIT");
    }

    @Test
    @DisplayName("screen() — sanctions near-miss (score entre seuils) → alerte HIGH, pas d'exception")
    void screen_sanctionsNearMiss_createsHighAlert_noException() {
        when(sanctionsScreeningService.checkTransaction(any()))
            .thenReturn(new SanctionsScreeningService.SanctionsCheckResult(
                true, false, 0.85, "EU", "Jane Doe", "Révision requise"));
        when(alertRepository.save(any(AmlAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        // Montant normal, aucune règle de vélocité dépassée
        stubVelocityUnderLimits();
        Transaction tx = buildTx(new BigDecimal("100000"));

        // Ne doit PAS lever d'exception
        service.screen(tx);

        ArgumentCaptor<AmlAlert> captor = ArgumentCaptor.forClass(AmlAlert.class);
        verify(alertRepository).save(captor.capture());
        assertThat(captor.getValue().getSeverity()).isEqualTo(AmlSeverity.HIGH);
        assertThat(captor.getValue().getRuleCode()).isEqualTo("SANCTIONS_NEAR_MISS");
    }

    // ── Règle 1 — Velocity horaire ────────────────────────────────────────────

    @Test
    @DisplayName("screen() — 21 transactions/heure (seuil=20) → alerte MEDIUM VELOCITY_HOURLY")
    void screen_velocityHourlyExceeded_createsMediumAlert() {
        when(transactionRepository.countByMerchantIdAndCreatedAtAfter(eq(MERCHANT_ID), any(LocalDateTime.class)))
            .thenReturn(21L)   // last hour  > 20
            .thenReturn(5L)    // last 24h   OK
            .thenReturn(10L);  // last week  OK
        when(alertRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.screen(buildTx(new BigDecimal("1000")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AmlAlert>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertRepository).saveAll(captor.capture());
        List<AmlAlert> alerts = captor.getValue();

        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getRuleCode()).isEqualTo("VELOCITY_HOURLY");
        assertThat(alerts.get(0).getSeverity()).isEqualTo(AmlSeverity.MEDIUM);
    }

    // ── Règle 2 — Velocity journalière ───────────────────────────────────────

    @Test
    @DisplayName("screen() — 101 transactions/24h (seuil=100) → alerte HIGH VELOCITY_DAILY")
    void screen_velocityDailyExceeded_createsHighAlert() {
        when(transactionRepository.countByMerchantIdAndCreatedAtAfter(eq(MERCHANT_ID), any(LocalDateTime.class)))
            .thenReturn(5L)    // last hour  OK
            .thenReturn(101L)  // last 24h   > 100
            .thenReturn(50L);  // last week  OK
        when(alertRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.screen(buildTx(new BigDecimal("1000")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AmlAlert>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
            .anyMatch(a -> a.getRuleCode().equals("VELOCITY_DAILY") && a.getSeverity() == AmlSeverity.HIGH);
    }

    // ── Règle 4 — Montant élevé ───────────────────────────────────────────────

    @Test
    @DisplayName("screen() — montant > 5 000 000 XOF → alerte HIGH HIGH_AMOUNT")
    void screen_highAmount_createsHighAlert() {
        stubVelocityUnderLimits();
        when(transactionRepository.countStructuringAttempts(any(), any(), any(), any())).thenReturn(0L);
        when(alertRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.screen(buildTx(new BigDecimal("5000001")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AmlAlert>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
            .anyMatch(a -> a.getRuleCode().equals("HIGH_AMOUNT") && a.getSeverity() == AmlSeverity.HIGH);
    }

    @Test
    @DisplayName("screen() — montant exactement égal au seuil → pas d'alerte HIGH_AMOUNT")
    void screen_amountEqualsThreshold_noHighAmountAlert() {
        stubVelocityUnderLimits();
        when(transactionRepository.countStructuringAttempts(any(), any(), any(), any())).thenReturn(0L);

        service.screen(buildTx(new BigDecimal("5000000"))); // == seuil → pas d'alerte

        verify(alertRepository, never()).saveAll(any());
    }

    // ── Règle 5 — Structuring ─────────────────────────────────────────────────

    @Test
    @DisplayName("screen() — 3 transactions entre 80%–100% du seuil en 24h → alerte HIGH STRUCTURING")
    void screen_structuringDetected_createsHighAlert() {
        stubVelocityUnderLimits();
        when(transactionRepository.countStructuringAttempts(any(), any(), any(), any())).thenReturn(3L);
        when(alertRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        service.screen(buildTx(new BigDecimal("1000")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AmlAlert>> captor = ArgumentCaptor.forClass(List.class);
        verify(alertRepository).saveAll(captor.capture());
        assertThat(captor.getValue())
            .anyMatch(a -> a.getRuleCode().equals("STRUCTURING") && a.getSeverity() == AmlSeverity.HIGH);
    }

    @Test
    @DisplayName("screen() — 2 transactions suspectes (< 3) → pas d'alerte STRUCTURING")
    void screen_structuringBelowThreshold_noAlert() {
        stubVelocityUnderLimits();
        when(transactionRepository.countStructuringAttempts(any(), any(), any(), any())).thenReturn(2L);

        service.screen(buildTx(new BigDecimal("1000")));

        verify(alertRepository, never()).saveAll(any());
    }

    // ── review() ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("review() — alerte introuvable → AML_ALERT_NOT_FOUND")
    void review_alertNotFound_throws() {
        UUID alertId = UUID.randomUUID();
        when(alertRepository.findById(alertId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.review(alertId, AmlStatus.CLEARED, "note", "user@e.com"))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AML_ALERT_NOT_FOUND));
    }

    @Test
    @DisplayName("review() — alerte déjà CLEARED → AML_ALREADY_RESOLVED")
    void review_alreadyCleared_throws() {
        UUID alertId = UUID.randomUUID();
        AmlAlert alert = AmlAlert.builder()
            .id(alertId).merchantId(MERCHANT_ID).ruleCode("VELOCITY_HOURLY")
            .severity(AmlSeverity.MEDIUM).status(AmlStatus.CLEARED)
            .build();
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert));

        assertThatThrownBy(() -> service.review(alertId, AmlStatus.REPORTED, "note", "user@e.com"))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AML_ALREADY_RESOLVED));
    }

    @Test
    @DisplayName("review() — alerte OPEN → status, note et reviewedBy mis à jour")
    void review_openAlert_updatesCorrectly() {
        UUID alertId = UUID.randomUUID();
        AmlAlert alert = AmlAlert.builder()
            .id(alertId).merchantId(MERCHANT_ID).ruleCode("HIGH_AMOUNT")
            .severity(AmlSeverity.HIGH).status(AmlStatus.OPEN)
            .build();
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert));
        when(alertRepository.save(any(AmlAlert.class))).thenAnswer(inv -> inv.getArgument(0));

        AmlAlert updated = service.review(alertId, AmlStatus.CLEARED, "Faux positif", "compliance@e.com");

        assertThat(updated.getStatus()).isEqualTo(AmlStatus.CLEARED);
        assertThat(updated.getResolutionNote()).isEqualTo("Faux positif");
        assertThat(updated.getReviewedBy()).isEqualTo("compliance@e.com");
        assertThat(updated.getReviewedAt()).isNotNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction buildTx(BigDecimal amount) {
        return Transaction.builder()
            .id(UUID.randomUUID())
            .ebithexReference("EBIT-TEST-001")
            .merchantReference("REF-001")
            .merchantId(MERCHANT_ID)
            .amount(amount)
            .currency(Currency.XOF)
            .build();
    }

    private void stubVelocityUnderLimits() {
        when(transactionRepository.countByMerchantIdAndCreatedAtAfter(eq(MERCHANT_ID), any(LocalDateTime.class)))
            .thenReturn(1L)   // last hour  OK
            .thenReturn(1L)   // last 24h   OK
            .thenReturn(1L);  // last week  OK
    }
}
