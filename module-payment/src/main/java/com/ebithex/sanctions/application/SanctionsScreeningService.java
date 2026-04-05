package com.ebithex.sanctions.application;

import com.ebithex.payment.domain.Transaction;
import com.ebithex.sanctions.domain.SanctionsEntry;
import com.ebithex.sanctions.infrastructure.SanctionsRepository;
import com.ebithex.shared.domain.OperatorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service de screening contre les listes de sanctions réglementaires.
 *
 * <h2>Deux niveaux de contrôle</h2>
 * <ol>
 *   <li><b>Pays à haut risque</b> — extrait depuis l'OperatorType (ex. MTN_MOMO_NG → NG).
 *       Un pays à haut risque bloque toujours la transaction (CRITICAL).</li>
 *   <li><b>Correspondance de nom</b> — Jaro-Winkler comparé à toutes les entrées actives
 *       de {@code sanctions_entries} (nom principal + aliases).</li>
 * </ol>
 *
 * <h2>Trois résultats possibles pour la correspondance de nom</h2>
 * <ul>
 *   <li>score ≥ {@code blockThreshold} (défaut 0.92)  → {@code requiresBlock=true}  → CRITICAL → blocage</li>
 *   <li>score ≥ {@code reviewThreshold} (défaut 0.80) → {@code requiresBlock=false} → HIGH → révision</li>
 *   <li>score < {@code reviewThreshold}               → pas de hit</li>
 * </ul>
 *
 * <p>Intégré dans {@link com.ebithex.aml.application.AmlScreeningService} en priorité absolue,
 * avant les règles de vélocité.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SanctionsScreeningService {

    private final SanctionsRepository sanctionsRepository;

    @Value("${ebithex.sanctions.enabled:true}")
    private boolean enabled;

    @Value("${ebithex.sanctions.high-risk-countries:IR,KP,SY,CU,SD,BY,RU,MM,AF,YE,SO,SS,LY,ZW,IQ,VE}")
    private String highRiskCountriesConfig;

    /**
     * Seuil de blocage : score Jaro-Winkler ≥ ce seuil → CRITICAL → transaction bloquée.
     * Défaut 0.95 = correspondance quasi-certaine (ex. "OSAMA BIN LADEN" vs "USAMA BIN LADEN" → ~0.956).
     */
    @Value("${ebithex.sanctions.match-threshold-block:0.95}")
    private double blockThreshold;

    /**
     * Seuil de révision : score ≥ ce seuil (mais < blockThreshold) → HIGH → révision manuelle.
     * Défaut 0.80 = probable homonyme ou translittération (ex. "KADHAFI" vs "GADDAFI").
     */
    @Value("${ebithex.sanctions.match-threshold-review:0.80}")
    private double reviewThreshold;

    // ── Résultat d'un contrôle ────────────────────────────────────────────────

    /**
     * Résultat du screening de sanctions pour une transaction ou un nom.
     *
     * @param hit           {@code true} si un match (blocage ou révision) a été trouvé
     * @param requiresBlock {@code true} → score ≥ blockThreshold → bloquer la transaction
     *                      {@code false} + hit → score ∈ [reviewThreshold, blockThreshold[ → révision
     * @param score         Score Jaro-Winkler du meilleur match (0.0 si pas de hit)
     * @param matchedList   Identifiant de la liste source (ex. "OFAC_SDN")
     * @param matchedEntity Nom officiel de l'entité dans la liste
     * @param reason        Description lisible du hit
     */
    public record SanctionsCheckResult(
        boolean hit,
        boolean requiresBlock,
        double  score,
        String  matchedList,
        String  matchedEntity,
        String  reason
    ) {
        public static SanctionsCheckResult noHit() {
            return new SanctionsCheckResult(false, false, 0.0, null, null, null);
        }
    }

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Contrôle complet d'une transaction :
     * <ol>
     *   <li>Pays à haut risque (depuis l'OperatorType) — toujours CRITICAL si hit</li>
     *   <li>Nom du client contre la DB de sanctions (fuzzy Jaro-Winkler)</li>
     * </ol>
     */
    public SanctionsCheckResult checkTransaction(Transaction tx) {
        if (!enabled) return SanctionsCheckResult.noHit();

        // 1. Contrôle pays à haut risque
        String country = extractCountryFromOperator(tx.getOperator());
        if (country != null && isHighRiskCountry(country)) {
            log.warn("Sanctions — pays à haut risque : operator={} country={} tx={}",
                tx.getOperator(), country, tx.getEbithexReference());
            return new SanctionsCheckResult(true, true, 1.0,
                "HIGH_RISK_COUNTRY", country, "Pays à haut risque : " + country);
        }

        // 2. Contrôle par nom du client (fuzzy matching)
        if (tx.getCustomerName() != null && !tx.getCustomerName().isBlank()) {
            SanctionsCheckResult nameResult = checkName(tx.getCustomerName());
            if (nameResult.hit()) {
                log.warn("Sanctions — correspondance nom (score={}) : name={} list={} tx={}",
                    String.format("%.3f", nameResult.score()),
                    tx.getCustomerName(), nameResult.matchedList(), tx.getEbithexReference());
                return nameResult;
            }
        }

        return SanctionsCheckResult.noHit();
    }

    /**
     * Vérifie si un nom correspond à une entrée de sanctions (fuzzy Jaro-Winkler).
     * Retourne un {@link SanctionsCheckResult} complet avec score et catégorie.
     *
     * <p>Utilisé directement par l'API back-office pour les vérifications manuelles.
     */
    public SanctionsCheckResult checkName(String name) {
        if (name == null || name.isBlank()) return SanctionsCheckResult.noHit();

        List<SanctionsEntry> entries = sanctionsRepository.findByIsActiveTrue();
        SanctionsCheckResult best = SanctionsCheckResult.noHit();

        for (SanctionsEntry entry : entries) {
            // Construire la liste des termes à comparer (nom + aliases)
            List<String> terms = buildTerms(entry);
            double score = FuzzyNameMatcher.bestScore(name, terms);

            if (score >= reviewThreshold && score > best.score()) {
                boolean requiresBlock = score >= blockThreshold;
                String category = requiresBlock ? "BLOCAGE" : "RÉVISION";
                String reason = String.format(
                    "%s — score=%.3f — liste %s : \"%s\"",
                    category, score, entry.getListName(), entry.getEntityName());
                best = new SanctionsCheckResult(true, requiresBlock, score,
                    entry.getListName(), entry.getEntityName(), reason);
                if (score == 1.0) break; // identique — inutile de continuer
            }
        }

        return best;
    }

    /**
     * Raccourci booléen : {@code true} si le nom produit un hit (blocage OU révision).
     * Utilisé par l'API back-office {@code POST /internal/sanctions/check}.
     */
    public boolean isNameSanctioned(String name) {
        return checkName(name).hit();
    }

    /**
     * Vérifie si un code pays ISO 3166-1 alpha-2 est dans la liste des pays à haut risque.
     */
    public boolean isHighRiskCountry(String isoCountryCode) {
        if (isoCountryCode == null || isoCountryCode.isBlank()) return false;
        Set<String> highRiskSet = Arrays.stream(highRiskCountriesConfig.split(","))
            .map(String::trim)
            .collect(Collectors.toSet());
        return highRiskSet.contains(isoCountryCode.toUpperCase());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<String> buildTerms(SanctionsEntry entry) {
        List<String> terms = new ArrayList<>();
        terms.add(entry.getEntityName());
        if (entry.getAliases() != null && !entry.getAliases().isBlank()) {
            for (String alias : entry.getAliases().split(",")) {
                String trimmed = alias.trim();
                if (!trimmed.isEmpty()) terms.add(trimmed);
            }
        }
        return terms;
    }

    /**
     * Extrait le code pays ISO depuis l'OperatorType.
     * Exemples : MTN_MOMO_NG → "NG", MPESA_KE → "KE"
     */
    String extractCountryFromOperator(OperatorType operator) {
        if (operator == null) return null;
        String[] parts = operator.name().split("_");
        if (parts.length >= 2) {
            String last = parts[parts.length - 1];
            if (last.length() == 2 && last.matches("[A-Z]{2}")) return last;
        }
        return null;
    }
}
