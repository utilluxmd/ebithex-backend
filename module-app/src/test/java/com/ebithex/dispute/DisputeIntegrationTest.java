package com.ebithex.dispute;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.dispute.application.DisputeService;
import com.ebithex.dispute.domain.Dispute;
import com.ebithex.dispute.domain.DisputeReason;
import com.ebithex.dispute.domain.DisputeStatus;
import com.ebithex.dispute.dto.DisputeRequest;
import com.ebithex.dispute.dto.DisputeResolutionRequest;
import com.ebithex.dispute.infrastructure.DisputeRepository;
import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.shared.domain.Currency;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests d'intégration — Litiges (Disputes).
 *
 * Couvre :
 *  - Ouverture d'un litige via le service
 *  - Workflow back-office : review → résolution
 *  - Isolation : un marchand ne voit pas les litiges d'un autre
 *  - Cas d'erreur : doublon, statut invalide
 */
@DisplayName("Disputes — Workflow Litige")
class DisputeIntegrationTest extends AbstractIntegrationTest {

    @Autowired private TestDataFactory      factory;
    @Autowired private DisputeService       disputeService;
    @Autowired private DisputeRepository    disputeRepository;
    @Autowired private TransactionRepository transactionRepository;

    private TestDataFactory.MerchantCredentials merchant;

    @BeforeEach
    void setUp() {
        merchant = factory.registerKycVerifiedMerchant();
    }

    @Test
    @DisplayName("Ouvrir un litige via le service — statut OPEN")
    void openDispute_viaService_statusOpen() {
        // Créer une transaction SUCCESS en base
        Transaction tx = seedSuccessTransaction("TX-DISP-001");

        DisputeRequest req = new DisputeRequest(tx.getEbithexReference(), DisputeReason.WRONG_AMOUNT,
            "Montant incorrect");
        Dispute dispute = disputeService.openDispute(req, merchant.merchantId());

        assertThat(dispute.getStatus()).isEqualTo(DisputeStatus.OPEN);
        assertThat(dispute.getReason()).isEqualTo(DisputeReason.WRONG_AMOUNT);
        assertThat(dispute.getMerchantId()).isEqualTo(merchant.merchantId());
    }

    @Test
    @DisplayName("Doublon de litige → erreur DISPUTE_ALREADY_EXISTS")
    void openDispute_duplicate_throwsError() {
        Transaction tx = seedSuccessTransaction("TX-DISP-DUP");

        disputeService.openDispute(
            new DisputeRequest(tx.getEbithexReference(), DisputeReason.UNAUTHORIZED, "Premier"),
            merchant.merchantId());

        assertThatThrownBy(() -> disputeService.openDispute(
                new DisputeRequest(tx.getEbithexReference(), DisputeReason.DUPLICATE, "Doublon"),
                merchant.merchantId()))
            .isInstanceOf(EbithexException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DISPUTE_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("Workflow complet: OPEN → UNDER_REVIEW → RESOLVED_MERCHANT")
    void disputeWorkflow_fullCycle() {
        Dispute dispute = saveDispute("TX-WF-001", DisputeStatus.OPEN);

        // OPEN → UNDER_REVIEW
        Dispute underReview = disputeService.startReview(dispute.getId(), "support@ebithex.io");
        assertThat(underReview.getStatus()).isEqualTo(DisputeStatus.UNDER_REVIEW);

        // UNDER_REVIEW → RESOLVED_MERCHANT
        Dispute resolved = disputeService.resolve(dispute.getId(),
            new DisputeResolutionRequest(DisputeStatus.RESOLVED_MERCHANT, "Preuve fournie"),
            "support@ebithex.io");
        assertThat(resolved.getStatus()).isEqualTo(DisputeStatus.RESOLVED_MERCHANT);
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolvedBy()).isEqualTo("support@ebithex.io");
    }

    @Test
    @DisplayName("Annulation d'un litige OPEN par le marchand")
    void cancelDispute_success() {
        Dispute dispute = saveDispute("TX-CANCEL-001", DisputeStatus.OPEN);

        Dispute cancelled = disputeService.cancelByMerchant(dispute.getId(), merchant.merchantId());
        assertThat(cancelled.getStatus()).isEqualTo(DisputeStatus.CANCELLED);
    }

    @Test
    @DisplayName("Annulation d'un litige UNDER_REVIEW → erreur DISPUTE_CANNOT_CANCEL")
    void cancelDispute_underReview_throwsError() {
        Dispute dispute = saveDispute("TX-CANCEL-002", DisputeStatus.UNDER_REVIEW);
        final UUID id = dispute.getId();

        assertThatThrownBy(() -> disputeService.cancelByMerchant(id, merchant.merchantId()))
            .isInstanceOf(EbithexException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DISPUTE_CANNOT_CANCEL);
    }

    @Test
    @DisplayName("Isolation marchand — un marchand ne voit pas le litige d'un autre")
    void getForMerchant_isolation() {
        Dispute dispute = saveDispute("TX-ISO-001", DisputeStatus.OPEN);
        UUID otherMerchantId = factory.registerKycVerifiedMerchant().merchantId();

        assertThatThrownBy(() -> disputeService.getForMerchant(dispute.getId(), otherMerchantId))
            .isInstanceOf(EbithexException.class)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DISPUTE_NOT_FOUND);
    }

    @Test
    @DisplayName("API back-office /internal/disputes — accessible par SUPPORT")
    void backOffice_listDisputes_supportRole() throws Exception {
        mockMvc.perform(get("/api/internal/disputes")
                .with(SecurityMockMvcRequestPostProcessors.user("support@ebithex.io")
                    .roles("SUPPORT")))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("API /v1/disputes — MERCHANT non authentifié reçoit 401")
    void disputeEndpoint_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/disputes"))
            .andExpect(status().isUnauthorized());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Transaction seedSuccessTransaction(String ebithexRef) {
        Transaction tx = Transaction.builder()
            .ebithexReference(ebithexRef + "-" + UUID.randomUUID().toString().substring(0, 4))
            .merchantReference("MR-" + UUID.randomUUID())
            .merchantId(merchant.merchantId())
            .amount(BigDecimal.valueOf(5000))
            .currency(Currency.XOF)
            .phoneNumber("enc-phone")
            .operator(OperatorType.MTN_MOMO_CI)
            .status(TransactionStatus.SUCCESS)
            .build();
        return transactionRepository.save(tx);
    }

    private Dispute saveDispute(String refPrefix, DisputeStatus status) {
        return disputeRepository.save(Dispute.builder()
            .ebithexReference(refPrefix + "-" + UUID.randomUUID().toString().substring(0, 4))
            .merchantId(merchant.merchantId())
            .reason(DisputeReason.NOT_RECEIVED)
            .description("Test dispute")
            .amount(BigDecimal.valueOf(10000))
            .currency("XOF")
            .status(status)
            .build());
    }
}