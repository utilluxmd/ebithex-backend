package com.ebithex.payment.application;

import com.ebithex.shared.sandbox.SandboxContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Job de rétention des données PII (données personnelles).
 *
 * Pseudonymise les numéros de téléphone (données PII) des transactions et payouts
 * antérieurs à la durée de rétention réglementaire, sur les deux schémas (prod ET sandbox).
 *
 * Mécanisme :
 *   - phone_number chiffré → remplacé par chiffrement de "PURGED"
 *   - phone_number_index   → effacé (null)
 *   - pii_purged_at        → horodaté
 *
 * Le numéro original est rendu irrecoverable ; les autres données
 * (montant, statut, références) sont conservées à des fins de comptabilité/audit.
 *
 * <p><b>Architecture transactionnelle :</b> La logique de purge est déléguée à
 * {@link PiiRetentionService} (bean Spring distinct). Cela garantit que les
 * annotations {@code @Transactional} sont interceptées par le proxy AOP Spring —
 * un appel interne ({@code this.method()}) aurait contourné le proxy silencieusement.
 *
 * <p><b>Pagination :</b> Chaque batch demande toujours la "page 0" des lignes non
 * purgées (filtre {@code pii_purged_at IS NULL}). Les lignes traitées disparaissent
 * de la requête suivante → pas d'offset croissant, pas de dégradation O(N²).
 *
 * Configuration :
 *   ebithex.pii.retention-years  (défaut : 5)
 *   ebithex.pii.batch-size       (défaut : 200)
 *   ebithex.pii.cron             (défaut : 03:00 UTC chaque jour)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PiiRetentionJob {

    private final PiiRetentionService piiRetentionService;

    @Value("${ebithex.pii.retention-years:5}")
    private int retentionYears;

    /**
     * Point d'entrée du job planifié.
     * Exécute la purge sur le schéma production puis sur le schéma sandbox.
     */
    @Scheduled(cron = "${ebithex.pii.cron:0 0 3 * * *}")
    public void run() {
        log.info("=== Début du job de purge PII (rétention={} ans) ===", retentionYears);
        LocalDateTime cutoff = LocalDateTime.now().minusYears(retentionYears);

        // ── Schéma production ──────────────────────────────────────────────
        int prodTx, prodPo;
        SandboxContextHolder.set(false);
        try {
            prodTx = purgeTransactions(cutoff);
            prodPo = purgePayouts(cutoff);
        } finally {
            SandboxContextHolder.clear();
        }

        // ── Schéma sandbox ────────────────────────────────────────────────
        int sandboxTx, sandboxPo;
        SandboxContextHolder.set(true);
        try {
            sandboxTx = purgeTransactions(cutoff);
            sandboxPo = purgePayouts(cutoff);
        } finally {
            SandboxContextHolder.clear();
        }

        log.info("=== Fin du job de purge PII : prod({} tx, {} po) sandbox({} tx, {} po) ===",
            prodTx, prodPo, sandboxTx, sandboxPo);
    }

    /**
     * Purge toutes les transactions PII antérieures à {@code cutoff} par batches successifs.
     *
     * <p>Le schéma cible doit être défini par l'appelant via {@link SandboxContextHolder}
     * AVANT d'appeler cette méthode. La délégation à {@link PiiRetentionService} garantit
     * que chaque batch s'exécute dans sa propre transaction {@code @Transactional}.
     *
     * @return nombre total d'enregistrements purgés
     */
    public int purgeTransactions(LocalDateTime cutoff) {
        int total = 0;
        int batchCount;
        do {
            batchCount = piiRetentionService.purgeTransactionBatch(cutoff);
            total += batchCount;
        } while (batchCount > 0);

        if (total > 0) log.info("PII purgé: {} transactions antérieures au {}", total, cutoff.toLocalDate());
        return total;
    }

    /**
     * Purge tous les payouts PII antérieurs à {@code cutoff} par batches successifs.
     * Même convention que {@link #purgeTransactions}.
     *
     * @return nombre total d'enregistrements purgés
     */
    public int purgePayouts(LocalDateTime cutoff) {
        int total = 0;
        int batchCount;
        do {
            batchCount = piiRetentionService.purgePayoutBatch(cutoff);
            total += batchCount;
        } while (batchCount > 0);

        if (total > 0) log.info("PII purgé: {} payouts antérieurs au {}", total, cutoff.toLocalDate());
        return total;
    }
}
