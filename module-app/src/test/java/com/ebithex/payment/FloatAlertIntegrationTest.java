package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.operatorfloat.application.OperatorFloatService;
import com.ebithex.operatorfloat.infrastructure.OperatorFloatRepository;
import com.ebithex.operatorfloat.domain.OperatorFloat;
import com.ebithex.shared.domain.OperatorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests d'intégration — Alertes float bas.
 *
 * Couvre :
 *  - Débit float en dessous du seuil → email d'alerte envoyé
 *  - Débit float au-dessus du seuil → aucune alerte
 *  - Débit float à exactement zéro → alerte envoyée
 *  - Float insuffisant → exception INSUFFICIENT_OPERATOR_FLOAT, pas d'alerte
 */
@DisplayName("Float Alert — Email envoyé quand balance < seuil")
class FloatAlertIntegrationTest extends AbstractIntegrationTest {

    @Autowired private OperatorFloatService    floatService;
    @Autowired private OperatorFloatRepository floatRepository;

    @BeforeEach
    void setUp() {
        // Initialiser le float ORANGE_CI avec un solde connu
        OperatorFloat f = floatRepository.findById(OperatorType.ORANGE_MONEY_CI).orElseGet(() ->
            OperatorFloat.builder()
                .operatorType(OperatorType.ORANGE_MONEY_CI)
                .balance(BigDecimal.ZERO)
                .lowBalanceThreshold(new BigDecimal("100000"))
                .build());
        f.setBalance(new BigDecimal("200000"));
        f.setLowBalanceThreshold(new BigDecimal("100000"));
        floatRepository.save(f);

        reset(mailSender);
    }

    @Test
    @DisplayName("Débit sous le seuil → email d'alerte envoyé")
    void debit_belowThreshold_triggersAlert() {
        // Débiter 150 000 → solde passe à 50 000 < seuil 100 000
        floatService.debitFloat(OperatorType.ORANGE_MONEY_CI, new BigDecimal("150000"), "TEST-REF-001");

        // L'événement FloatLowBalanceEvent → NotificationService → mailSender.send()
        verify(mailSender, timeout(2000).atLeastOnce()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("Débit au-dessus du seuil → aucune alerte")
    void debit_aboveThreshold_noAlert() {
        // Débiter 50 000 → solde passe à 150 000 > seuil 100 000
        floatService.debitFloat(OperatorType.ORANGE_MONEY_CI, new BigDecimal("50000"), "TEST-REF-002");

        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("Débit exact qui vide le float → alerte envoyée")
    void debit_toZero_triggersAlert() {
        // Débiter 200 000 → solde passe à 0 < seuil 100 000
        floatService.debitFloat(OperatorType.ORANGE_MONEY_CI, new BigDecimal("200000"), "TEST-REF-003");

        verify(mailSender, timeout(2000).atLeastOnce()).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("Float insuffisant → exception, aucun email envoyé")
    void debit_insufficientFloat_throwsException_noAlert() {
        // Tenter de débiter plus que le solde disponible
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            floatService.debitFloat(OperatorType.ORANGE_MONEY_CI, new BigDecimal("999999"), "TEST-REF-004")
        ).hasMessageContaining("Float insuffisant");

        verify(mailSender, after(500).never()).send(any(SimpleMailMessage.class));
    }
}