package com.ebithex.payment.application;

import com.ebithex.payment.domain.FeeRule;
import com.ebithex.payment.infrastructure.FeeRuleRepository;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service de calcul des frais de transaction.
 *
 * Remplace les taux hardcodés dans application.properties par un système
 * dynamique basé sur la table fee_rules.
 *
 * Algorithme de résolution (priorité décroissante) :
 *  1. Règle avec merchant_id = :merchantId ET operator = :operator  (la plus spécifique)
 *  2. Règle avec merchant_id = :merchantId ET operator IS NULL
 *  3. Règle avec merchant_id IS NULL ET operator = :operator
 *  4. Règle avec country = :country (pas de marchand ni opérateur)
 *  5. Règle globale (merchant_id IS NULL, operator IS NULL, country IS NULL)
 *
 * Pour chaque niveau, la règle avec le priority le plus élevé gagne.
 *
 * <p><b>Cache :</b> la résolution de règle est mise en cache sous la clé
 * {@code merchantId:operator:country} (TTL 10 min) car les règles tarifaires
 * changent rarement. Le calcul du montant de frais (qui dépend du montant de la
 * transaction) est intentionnellement exclu du cache.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FeeService {

    private final FeeRuleRepository feeRuleRepository;

    public record FeeCalculation(
        BigDecimal feeAmount,
        BigDecimal netAmount,
        UUID       ruleId,
        String     ruleName
    ) {}

    /**
     * Calcule les frais pour un paiement.
     *
     * @param merchantId  UUID du marchand
     * @param operator    Opérateur Mobile Money détecté
     * @param country     Pays du marchand
     * @param amount      Montant brut de la transaction
     * @return FeeCalculation avec feeAmount, netAmount et la règle appliquée
     * @throws EbithexException FEE_RULE_NOT_FOUND si aucune règle ne correspond
     */
    @Transactional(readOnly = true)
    public FeeCalculation calculate(UUID merchantId, OperatorType operator,
                                    String country, BigDecimal amount) {

        // La résolution de la règle est cachée (indépendante du montant).
        // Le calcul du feeAmount dépend du montant → non caché.
        FeeRule rule = resolveRule(merchantId, operator, country);

        if (rule == null) {
            log.error("Aucune règle tarifaire trouvée: merchant={} operator={} country={} amount={}",
                merchantId, operator, country, amount);
            throw new EbithexException(ErrorCode.FEE_RULE_NOT_FOUND,
                "Aucune règle tarifaire configurée pour cet opérateur/pays");
        }

        BigDecimal feeAmount = computeFee(rule, amount);
        BigDecimal netAmount = amount.subtract(feeAmount);

        log.debug("Frais calculés: règle='{}' amount={} fee={} net={} rule_id={}",
            rule.getName(), amount, feeAmount, netAmount, rule.getId());

        return new FeeCalculation(feeAmount, netAmount, rule.getId(), rule.getName());
    }

    /**
     * Résout la règle tarifaire applicable pour la combinaison (marchand, opérateur, pays).
     *
     * <p>Mise en cache sous la clé {@code merchantId:operator:country} (TTL 10 min via CacheConfig).
     * L'invalidation est déclenchée par {@link #evictFeeRuleCache()} lors de toute modification
     * des règles tarifaires.
     *
     * @return la FeeRule la plus spécifique, ou {@code null} si aucune règle ne correspond
     */
    @Cacheable(cacheNames = "fee-rules",
               key = "#merchantId + ':' + #operator.name() + ':' + (#country ?: '')")
    @Transactional(readOnly = true)
    public FeeRule resolveRule(UUID merchantId, OperatorType operator, String country) {
        List<FeeRule> candidates = feeRuleRepository.findCandidates(
            merchantId, operator, country, null, LocalDateTime.now());
        return resolve(candidates, merchantId, operator, country);
    }

    /**
     * Invalide l'intégralité du cache des règles tarifaires.
     * À appeler après toute création, modification ou suppression d'une FeeRule.
     */
    @CacheEvict(cacheNames = "fee-rules", allEntries = true)
    public void evictFeeRuleCache() {
        log.info("Cache 'fee-rules' invalidé");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Sélectionne la règle la plus spécifique parmi les candidats.
     * Les candidats sont déjà ordonnés par priority DESC.
     * On choisit selon le niveau de spécificité, puis par priorité.
     */
    private FeeRule resolve(List<FeeRule> candidates, UUID merchantId,
                            OperatorType operator, String country) {
        // Niveau 1 : merchant + operator
        for (FeeRule r : candidates) {
            if (merchantId.equals(r.getMerchantId()) && operator == r.getOperator()) return r;
        }
        // Niveau 2 : merchant seulement (pas d'opérateur spécifié)
        for (FeeRule r : candidates) {
            if (merchantId.equals(r.getMerchantId()) && r.getOperator() == null) return r;
        }
        // Niveau 3 : operator seulement
        for (FeeRule r : candidates) {
            if (r.getMerchantId() == null && operator == r.getOperator()) return r;
        }
        // Niveau 4 : pays
        if (country != null) {
            for (FeeRule r : candidates) {
                if (r.getMerchantId() == null && r.getOperator() == null
                        && country.equals(r.getCountry())) return r;
            }
        }
        // Niveau 5 : global (fallback)
        for (FeeRule r : candidates) {
            if (r.getMerchantId() == null && r.getOperator() == null && r.getCountry() == null) return r;
        }
        return null;
    }

    private BigDecimal computeFee(FeeRule rule, BigDecimal amount) {
        BigDecimal fee = BigDecimal.ZERO;

        switch (rule.getFeeType()) {
            case PERCENTAGE -> {
                if (rule.getPercentageRate() != null) {
                    fee = amount.multiply(rule.getPercentageRate())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                }
            }
            case FLAT -> {
                if (rule.getFlatAmount() != null) {
                    fee = rule.getFlatAmount().setScale(2, RoundingMode.HALF_UP);
                }
            }
            case MIXED -> {
                if (rule.getPercentageRate() != null) {
                    fee = amount.multiply(rule.getPercentageRate())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                }
                if (rule.getFlatAmount() != null) {
                    fee = fee.add(rule.getFlatAmount());
                }
            }
        }

        // Appliquer plancher et plafond
        if (rule.getMinFee() != null && fee.compareTo(rule.getMinFee()) < 0) {
            fee = rule.getMinFee();
        }
        if (rule.getMaxFee() != null && fee.compareTo(rule.getMaxFee()) > 0) {
            fee = rule.getMaxFee();
        }

        // Les frais ne peuvent pas dépasser le montant de la transaction
        if (fee.compareTo(amount) > 0) {
            fee = amount;
        }

        return fee.setScale(2, RoundingMode.HALF_UP);
    }
}
