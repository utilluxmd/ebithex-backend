package com.ebithex.payment.application;

import com.ebithex.payment.domain.Transaction;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.payout.domain.Payout;
import com.ebithex.payout.infrastructure.PayoutRepository;
import com.ebithex.shared.crypto.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de rotation des clés AES-256-GCM.
 *
 * <p>Re-chiffre en batch les enregistrements dont le ciphertext n'est pas encore
 * à la version de clé active. Opération idempotente : peut être relancée sans risque
 * si elle est interrompue.
 *
 * <p>Déclenchement via {@link com.ebithex.payment.api.KeyRotationController}
 * ({@code POST /internal/admin/key-rotation}).
 *
 * <h3>Procédure de rotation</h3>
 * <ol>
 *   <li>Générer une nouvelle clé : {@code openssl rand -base64 32}</li>
 *   <li>Ajouter dans la config :
 *       {@code ebithex.security.encryption.versions[N]=<base64>}
 *       et {@code ebithex.security.encryption.active-version=N}</li>
 *   <li>Redémarrer l'application</li>
 *   <li>Appeler {@code POST /internal/admin/key-rotation} (peut prendre plusieurs minutes)</li>
 *   <li>Vérifier le résultat (reRotatedTx == 0 et reRotatedPayout == 0)</li>
 *   <li>Retirer les anciennes versions de clé de la configuration et redémarrer</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KeyRotationService {

    private static final int BATCH_SIZE = 200;

    private final EncryptionService      encryptionService;
    private final TransactionRepository  transactionRepository;
    private final PayoutRepository       payoutRepository;

    /**
     * Lance la rotation complète : Transactions puis Payouts.
     *
     * @return résultat avec compteurs de re-chiffrements
     */
    @Transactional
    public KeyRotationResult rotateAll() {
        int activeVersion = encryptionService.getActiveVersion();
        // Le préfixe SQL LIKE pour la version active (ex: "v2:%")
        String versionPrefix = "v" + activeVersion + ":%";

        log.info("Rotation des clés AES: version active={}, recherche ciphertexts NOT LIKE '{}'",
            activeVersion, versionPrefix);

        int reEncryptedTx     = rotateTransactions(versionPrefix);
        int reEncryptedPayout = rotatePayouts(versionPrefix);

        log.info("Rotation terminée: transactions={} payouts={}", reEncryptedTx, reEncryptedPayout);
        return new KeyRotationResult(activeVersion, reEncryptedTx, reEncryptedPayout);
    }

    // ── Transactions ──────────────────────────────────────────────────────────

    private int rotateTransactions(String versionPrefix) {
        int total = 0;
        Page<Transaction> page;
        do {
            page = transactionRepository.findNeedingReEncryption(
                versionPrefix, PageRequest.of(0, BATCH_SIZE));

            for (Transaction tx : page.getContent()) {
                try {
                    String plain = encryptionService.decrypt(tx.getPhoneNumber());
                    tx.setPhoneNumber(encryptionService.encrypt(plain));
                    // phoneNumberIndex reste inchangé (HMAC toujours clé v1)
                    transactionRepository.save(tx);
                    total++;
                } catch (Exception e) {
                    log.error("Impossible de re-chiffrer Transaction {}: {}", tx.getId(), e.getMessage());
                }
            }

            if (total > 0 && total % 1000 == 0) {
                log.info("Rotation transactions: {} enregistrements traités…", total);
            }
        } while (page.hasNext());

        log.info("Rotation transactions terminée: {} re-chiffrés", total);
        return total;
    }

    // ── Payouts ───────────────────────────────────────────────────────────────

    private int rotatePayouts(String versionPrefix) {
        int total = 0;
        Page<Payout> page;
        do {
            page = payoutRepository.findNeedingReEncryption(
                versionPrefix, PageRequest.of(0, BATCH_SIZE));

            for (Payout po : page.getContent()) {
                try {
                    String plain = encryptionService.decrypt(po.getPhoneNumber());
                    po.setPhoneNumber(encryptionService.encrypt(plain));
                    payoutRepository.save(po);
                    total++;
                } catch (Exception e) {
                    log.error("Impossible de re-chiffrer Payout {}: {}", po.getId(), e.getMessage());
                }
            }

            if (total > 0 && total % 1000 == 0) {
                log.info("Rotation payouts: {} enregistrements traités…", total);
            }
        } while (page.hasNext());

        log.info("Rotation payouts terminée: {} re-chiffrés", total);
        return total;
    }

    // ── DTO résultat ──────────────────────────────────────────────────────────

    /**
     * Résultat d'une opération de rotation de clés.
     *
     * @param activeKeyVersion  Version de clé utilisée pour le re-chiffrement
     * @param reEncryptedTx     Nombre de transactions re-chiffrées
     * @param reEncryptedPayout Nombre de payouts re-chiffrés
     */
    public record KeyRotationResult(
        int activeKeyVersion,
        int reEncryptedTx,
        int reEncryptedPayout
    ) {}
}