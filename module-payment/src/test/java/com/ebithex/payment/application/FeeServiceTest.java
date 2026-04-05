package com.ebithex.payment.application;

import com.ebithex.payment.domain.FeeRule;
import com.ebithex.payment.infrastructure.FeeRuleRepository;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests unitaires pour FeeService — calcul dynamique des frais.
 * Aucun contexte Spring — le repository est mocké.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeeService — Calcul dynamique des frais")
class FeeServiceTest {

    @Mock
    private FeeRuleRepository feeRuleRepository;

    private FeeService feeService;

    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        feeService = new FeeService(feeRuleRepository);
    }

    // ── PERCENTAGE ────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0}% de {1} XOF = {2} XOF de frais")
    @CsvSource({
        "2.0,  10000, 200.00,  9800.00",
        "1.5,  10000, 150.00,  9850.00",
        "0.5,  50000, 250.00, 49750.00",
        "10.0,  1000, 100.00,   900.00",
    })
    @DisplayName("PERCENTAGE : frais = montant × taux / 100")
    void computeFee_percentage(BigDecimal rate, BigDecimal amount,
                                BigDecimal expectedFee, BigDecimal expectedNet) {
        FeeRule rule = percentageRule(rate);
        when(feeRuleRepository.findCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(rule));

        FeeService.FeeCalculation result = feeService.calculate(
            merchantId, OperatorType.MTN_MOMO_CI, "CI", amount);

        assertThat(result.feeAmount()).isEqualByComparingTo(expectedFee);
        assertThat(result.netAmount()).isEqualByComparingTo(expectedNet);
    }

    // ── FLAT ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("FLAT : frais fixe indépendant du montant")
    void computeFee_flat_fixedRegardlessOfAmount() {
        FeeRule rule = flatRule(new BigDecimal("500.00"));
        when(feeRuleRepository.findCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(rule));

        FeeService.FeeCalculation r1 = feeService.calculate(merchantId, OperatorType.MTN_MOMO_CI, "CI", new BigDecimal("10000"));
        FeeService.FeeCalculation r2 = feeService.calculate(merchantId, OperatorType.MTN_MOMO_CI, "CI", new BigDecimal("500000"));

        assertThat(r1.feeAmount()).isEqualByComparingTo("500.00");
        assertThat(r2.feeAmount()).isEqualByComparingTo("500.00");
    }

    // ── MIXED ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MIXED : frais = pourcentage + montant fixe")
    void computeFee_mixed_combinedFee() {
        // 1% + 100 XOF fixe sur 10 000 XOF → 100 + 100 = 200 XOF
        FeeRule rule = mixedRule(new BigDecimal("1.0"), new BigDecimal("100.00"));
        when(feeRuleRepository.findCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(rule));

        FeeService.FeeCalculation result = feeService.calculate(
            merchantId, OperatorType.MTN_MOMO_CI, "CI", new BigDecimal("10000"));

        assertThat(result.feeAmount()).isEqualByComparingTo("200.00");
        assertThat(result.netAmount()).isEqualByComparingTo("9800.00");
    }

    // ── maxFee cap ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("maxFee : frais plafonné même si le taux calculé dépasse le plafond")
    void computeFee_maxFeeCap_applied() {
        // 10% de 10 000 = 1000, mais maxFee = 300
        FeeRule rule = percentageRule(new BigDecimal("10.0"));
        rule.setMaxFee(new BigDecimal("300.00"));
        when(feeRuleRepository.findCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(rule));

        FeeService.FeeCalculation result = feeService.calculate(
            merchantId, OperatorType.MTN_MOMO_CI, "CI", new BigDecimal("10000"));

        assertThat(result.feeAmount()).isEqualByComparingTo("300.00");
        assertThat(result.netAmount()).isEqualByComparingTo("9700.00");
    }

    // ── minFee floor ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("minFee : frais plancher appliqué si le taux calculé est insuffisant")
    void computeFee_minFeeFloor_applied() {
        // 1% de 1 000 = 10, mais minFee = 50
        FeeRule rule = percentageRule(new BigDecimal("1.0"));
        rule.setMinFee(new BigDecimal("50.00"));
        when(feeRuleRepository.findCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(rule));

        FeeService.FeeCalculation result = feeService.calculate(
            merchantId, OperatorType.MTN_MOMO_CI, "CI", new BigDecimal("1000"));

        assertThat(result.feeAmount()).isEqualByComparingTo("50.00");
    }

    // ── Frais ne dépassent jamais le montant ──────────────────────────────────

    @Test
    @DisplayName("Les frais sont plafonnés au montant de la transaction")
    void computeFee_feeNeverExceedsAmount() {
        // FLAT 9999 sur un paiement de 100 XOF → frais = 100 (pas 9999)
        FeeRule rule = flatRule(new BigDecimal("9999.00"));
        when(feeRuleRepository.findCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(rule));

        FeeService.FeeCalculation result = feeService.calculate(
            merchantId, OperatorType.MTN_MOMO_CI, "CI", new BigDecimal("100"));

        assertThat(result.feeAmount()).isEqualByComparingTo("100.00");
        assertThat(result.netAmount()).isEqualByComparingTo("0.00");
    }

    // ── Aucune règle trouvée ──────────────────────────────────────────────────

    @Test
    @DisplayName("Lève FEE_RULE_NOT_FOUND si aucune règle ne correspond")
    void calculate_noMatchingRule_throwsException() {
        when(feeRuleRepository.findCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of());

        assertThatThrownBy(() ->
            feeService.calculate(merchantId, OperatorType.MTN_MOMO_CI, "CI", new BigDecimal("10000"))
        )
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode()).isEqualTo(ErrorCode.FEE_RULE_NOT_FOUND));
    }

    // ── Résolution de priorité ────────────────────────────────────────────────

    @Test
    @DisplayName("Règle marchand+opérateur prime sur règle opérateur seul")
    void resolve_merchantOperatorRuleBeatsOperatorRule() {
        // Niveau 3 : règle opérateur (5%)
        FeeRule operatorRule = percentageRule(new BigDecimal("5.0"));
        operatorRule.setName("operator-rule");

        // Niveau 1 : règle marchand+opérateur (1%)
        FeeRule merchantRule = percentageRule(new BigDecimal("1.0"));
        merchantRule.setName("merchant-rule");
        merchantRule.setMerchantId(merchantId);
        merchantRule.setOperator(OperatorType.MTN_MOMO_CI);

        when(feeRuleRepository.findCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(operatorRule, merchantRule));

        FeeService.FeeCalculation result = feeService.calculate(
            merchantId, OperatorType.MTN_MOMO_CI, "CI", new BigDecimal("10000"));

        // Doit appliquer 1% (règle marchand), pas 5% (règle opérateur)
        assertThat(result.feeAmount()).isEqualByComparingTo("100.00");
        assertThat(result.ruleName()).isEqualTo("merchant-rule");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FeeRule percentageRule(BigDecimal rate) {
        FeeRule rule = new FeeRule();
        rule.setName("test-percentage-rule");
        rule.setFeeType(FeeRule.FeeType.PERCENTAGE);
        rule.setPercentageRate(rate);
        rule.setActive(true);
        rule.setPriority(10);
        return rule;
    }

    private FeeRule flatRule(BigDecimal amount) {
        FeeRule rule = new FeeRule();
        rule.setName("test-flat-rule");
        rule.setFeeType(FeeRule.FeeType.FLAT);
        rule.setFlatAmount(amount);
        rule.setActive(true);
        rule.setPriority(10);
        return rule;
    }

    private FeeRule mixedRule(BigDecimal rate, BigDecimal flat) {
        FeeRule rule = new FeeRule();
        rule.setName("test-mixed-rule");
        rule.setFeeType(FeeRule.FeeType.MIXED);
        rule.setPercentageRate(rate);
        rule.setFlatAmount(flat);
        rule.setActive(true);
        rule.setPriority(10);
        return rule;
    }
}