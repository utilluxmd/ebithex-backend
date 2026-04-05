package com.ebithex.payment.application;

import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.payout.domain.Payout;
import com.ebithex.payout.infrastructure.PayoutRepository;
import com.ebithex.shared.crypto.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires pour PiiRetentionService.
 *
 * Vérifie que :
 * - La pagination utilise toujours page=0 (pas d'offset croissant)
 * - Les champs purgés sont correctement mis à jour
 * - La boucle s'arrête quand la page est vide
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PiiRetentionService — Purge PII par batches")
class PiiRetentionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private PayoutRepository      payoutRepository;
    @Mock private EncryptionService     encryptionService;

    private PiiRetentionService service;

    private static final LocalDateTime CUTOFF = LocalDateTime.now().minusYears(5);

    @BeforeEach
    void setUp() {
        service = new PiiRetentionService(transactionRepository, payoutRepository, encryptionService);
        ReflectionTestUtils.setField(service, "batchSize", 2);
    }

    // ── purgeTransactionBatch ─────────────────────────────────────────────────

    @Test
    @DisplayName("purgeTransactionBatch — page 0 toujours demandée (pas d'offset croissant)")
    void purgeTransactionBatch_alwaysRequestsPage0() {
        Transaction tx = buildTransaction();
        when(transactionRepository.findPurgeCandidates(any(), any()))
            .thenReturn(new PageImpl<>(List.of(tx)));
        when(encryptionService.encrypt(anyString())).thenReturn("v1:encrypted-purged");

        service.purgeTransactionBatch(CUTOFF);

        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(transactionRepository).findPurgeCandidates(eq(CUTOFF), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageCaptor.getValue().getPageSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("purgeTransactionBatch — phone_number, index et pii_purged_at mis à jour")
    void purgeTransactionBatch_updatesAllPiiFields() {
        Transaction tx = buildTransaction();
        when(transactionRepository.findPurgeCandidates(any(), any()))
            .thenReturn(new PageImpl<>(List.of(tx)));
        when(encryptionService.encrypt("PURGED")).thenReturn("v1:purged-ciphertext");

        service.purgeTransactionBatch(CUTOFF);

        assertThat(tx.getPhoneNumber()).isEqualTo("v1:purged-ciphertext");
        assertThat(tx.getPhoneNumberIndex()).isNull();
        assertThat(tx.getPiiPurgedAt()).isNotNull();
        verify(transactionRepository).saveAll(List.of(tx));
    }

    @Test
    @DisplayName("purgeTransactionBatch — page vide → retourne 0")
    void purgeTransactionBatch_emptyPage_returns0() {
        when(transactionRepository.findPurgeCandidates(any(), any()))
            .thenReturn(new PageImpl<>(List.of()));

        int result = service.purgeTransactionBatch(CUTOFF);

        assertThat(result).isZero();
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("purgeTransactionBatch — exception sur un enregistrement → les autres sont quand même purgés")
    void purgeTransactionBatch_exceptionOnOne_continuesOthers() {
        Transaction tx1 = buildTransaction();
        Transaction tx2 = buildTransaction();
        when(transactionRepository.findPurgeCandidates(any(), any()))
            .thenReturn(new PageImpl<>(List.of(tx1, tx2)));
        // Première transaction lève une exception, deuxième OK
        when(encryptionService.encrypt("PURGED"))
            .thenThrow(new RuntimeException("Encryption failed"))
            .thenReturn("v1:purged-ok");

        service.purgeTransactionBatch(CUTOFF);

        // tx1 non purgée (exception), tx2 purgée
        assertThat(tx1.getPiiPurgedAt()).isNull();
        assertThat(tx2.getPiiPurgedAt()).isNotNull();
        // saveAll est quand même appelé avec les deux (tx1 non modifiée, tx2 modifiée)
        verify(transactionRepository).saveAll(List.of(tx1, tx2));
    }

    // ── purgePayoutBatch ──────────────────────────────────────────────────────

    @Test
    @DisplayName("purgePayoutBatch — page 0 toujours demandée")
    void purgePayoutBatch_alwaysRequestsPage0() {
        Payout po = buildPayout();
        when(payoutRepository.findPurgeCandidates(any(), any()))
            .thenReturn(new PageImpl<>(List.of(po)));
        when(encryptionService.encrypt(anyString())).thenReturn("v1:encrypted-purged");

        service.purgePayoutBatch(CUTOFF);

        ArgumentCaptor<PageRequest> pageCaptor = ArgumentCaptor.forClass(PageRequest.class);
        verify(payoutRepository).findPurgeCandidates(eq(CUTOFF), pageCaptor.capture());
        assertThat(pageCaptor.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("purgePayoutBatch — champs PII purgés correctement")
    void purgePayoutBatch_updatesAllPiiFields() {
        Payout po = buildPayout();
        when(payoutRepository.findPurgeCandidates(any(), any()))
            .thenReturn(new PageImpl<>(List.of(po)));
        when(encryptionService.encrypt("PURGED")).thenReturn("v1:purged-ciphertext");

        service.purgePayoutBatch(CUTOFF);

        assertThat(po.getPhoneNumber()).isEqualTo("v1:purged-ciphertext");
        assertThat(po.getPhoneNumberIndex()).isNull();
        assertThat(po.getPiiPurgedAt()).isNotNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Transaction buildTransaction() {
        Transaction tx = new Transaction();
        ReflectionTestUtils.setField(tx, "id", UUID.randomUUID());
        tx.setPhoneNumber("v1:encrypted-phone");
        tx.setPhoneNumberIndex("hmac-index");
        return tx;
    }

    private Payout buildPayout() {
        Payout po = new Payout();
        ReflectionTestUtils.setField(po, "id", UUID.randomUUID());
        po.setPhoneNumber("v1:encrypted-phone");
        po.setPhoneNumberIndex("hmac-index");
        return po;
    }
}
