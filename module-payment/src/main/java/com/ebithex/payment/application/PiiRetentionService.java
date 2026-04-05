package com.ebithex.payment.application;

import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.payout.domain.Payout;
import com.ebithex.payout.infrastructure.PayoutRepository;
import com.ebithex.shared.crypto.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Service transactionnel chargé de la pseudonymisation des données PII
 * (numéros de téléphone) sur les enregistrements antérieurs au seuil de rétention.
 *
 * <p>Ce service est distinct de {@link PiiRetentionJob} pour éviter le problème
 * de self-invocation Spring AOP : {@link PiiRetentionJob#run()} est un job planifié
 * non-transactionnel qui appelle ce service via injection → le proxy Spring
 * intercepte correctement les annotations {@code @Transactional}.
 *
 * <p><b>Pagination :</b> Au lieu d'utiliser une pagination par offset (O(N²) sur
 * de grandes tables), le service redemande toujours la "page 0" des enregistrements
 * non purgés. Après chaque batch, les lignes traitées ont {@code pii_purged_at != NULL}
 * et n'apparaissent plus dans la requête suivante — comportement équivalent à une
 * pagination par curseur.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PiiRetentionService {

    private final TransactionRepository transactionRepository;
    private final PayoutRepository      payoutRepository;
    private final EncryptionService     encryptionService;

    @Value("${ebithex.pii.batch-size:200}")
    private int batchSize;

    /** Placeholder chiffré substitué au numéro purgé. */
    private static final String PURGED_PLACEHOLDER = "PURGED";

    /**
     * Pseudonymise les transactions dont la date de création est antérieure à {@code cutoff}
     * et dont le champ {@code pii_purged_at} n'est pas encore renseigné.
     *
     * <p>Le schéma cible (prod ou sandbox) doit être défini par l'appelant via
     * {@link com.ebithex.shared.sandbox.SandboxContextHolder} AVANT d'appeler cette méthode,
     * car le contexte de transaction doit être ouvert APRÈS le setContext.
     *
     * <p>Chaque appel de méthode traite un batch de {@code batchSize} enregistrements dans
     * sa propre transaction. La boucle est gérée par l'appelant ({@link PiiRetentionJob}).
     *
     * @return nombre d'enregistrements purgés lors de cet appel
     */
    @Transactional
    public int purgeTransactionBatch(LocalDateTime cutoff) {
        List<Transaction> batch = transactionRepository
            .findPurgeCandidates(cutoff, PageRequest.of(0, batchSize))
            .getContent();

        if (batch.isEmpty()) return 0;

        for (Transaction tx : batch) {
            try {
                tx.setPhoneNumber(encryptionService.encrypt(PURGED_PLACEHOLDER));
                tx.setPhoneNumberIndex(null);
                tx.setPiiPurgedAt(LocalDateTime.now());
            } catch (Exception e) {
                log.error("Erreur de purge PII transaction {}: {}", tx.getId(), e.getMessage());
            }
        }

        transactionRepository.saveAll(batch);
        return batch.size();
    }

    /**
     * Pseudonymise les payouts dont la date de création est antérieure à {@code cutoff}.
     * Même convention que {@link #purgeTransactionBatch}.
     *
     * @return nombre d'enregistrements purgés lors de cet appel
     */
    @Transactional
    public int purgePayoutBatch(LocalDateTime cutoff) {
        List<Payout> batch = payoutRepository
            .findPurgeCandidates(cutoff, PageRequest.of(0, batchSize))
            .getContent();

        if (batch.isEmpty()) return 0;

        for (Payout po : batch) {
            try {
                po.setPhoneNumber(encryptionService.encrypt(PURGED_PLACEHOLDER));
                po.setPhoneNumberIndex(null);
                po.setPiiPurgedAt(LocalDateTime.now());
            } catch (Exception e) {
                log.error("Erreur de purge PII payout {}: {}", po.getId(), e.getMessage());
            }
        }

        payoutRepository.saveAll(batch);
        return batch.size();
    }
}
