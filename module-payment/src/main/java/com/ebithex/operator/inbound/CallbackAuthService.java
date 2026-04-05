package com.ebithex.operator.inbound;

import com.ebithex.shared.util.HmacUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vérifie l'authenticité et l'unicité de chaque callback opérateur.
 *
 * Deux protections complémentaires :
 *  1. Vérification HMAC-SHA256 de la signature opérateur (si fournie)
 *  2. Déduplication via la table operator_processed_callbacks (INSERT unique)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallbackAuthService {

    private final HmacUtil hmacUtil;
    private final OperatorProcessedCallbackRepository processedCallbackRepository;

    /**
     * Vérifie la signature HMAC du callback entrant.
     *
     * @param operatorName  ex: "mtn-momo"
     * @param rawBody       body HTTP brut (avant désérialisation)
     * @param signature     valeur du header de signature (ex: X-Callback-Auth)
     * @param secret        secret partagé avec l'opérateur (depuis config)
     * @return true si la signature est valide ou si l'opérateur ne fournit pas de signature
     */
    public boolean verifySignature(String operatorName, String rawBody,
                                    String signature, String secret) {
        if (signature == null || signature.isBlank()) {
            // Certains opérateurs sandbox ne signent pas — logger en warning
            log.warn("Callback {} reçu sans signature HMAC — accepté mais à surveiller", operatorName);
            return true;
        }
        if (secret == null || secret.isBlank()) {
            log.error("Secret HMAC non configuré pour l'opérateur: {}", operatorName);
            return false;
        }
        String expected = hmacUtil.sign(secret, rawBody);
        boolean valid = expected.equals(signature);
        if (!valid) {
            log.warn("Signature HMAC invalide pour callback {} — attendu={} reçu={}",
                    operatorName, expected.substring(0, 8) + "...", signature.substring(0, Math.min(8, signature.length())) + "...");
        }
        return valid;
    }

    /**
     * Marque le callback comme traité (idempotence).
     *
     * @return true si c'est un nouveau callback à traiter
     *         false si déjà traité (doublon — retourner 200 sans retraiter)
     */
    @Transactional
    public boolean markAsProcessed(String operatorName, String operatorReference) {
        if (processedCallbackRepository.existsByOperatorAndOperatorReference(
                operatorName, operatorReference)) {
            log.info("Callback dupliqué ignoré — operator={} ref={}", operatorName, operatorReference);
            return false;
        }
        try {
            processedCallbackRepository.save(
                OperatorProcessedCallback.builder()
                    .operator(operatorName)
                    .operatorReference(operatorReference)
                    .build()
            );
            return true;
        } catch (DataIntegrityViolationException e) {
            // Race condition — un autre thread a inséré en parallèle
            log.info("Callback dupliqué (race condition) ignoré — operator={} ref={}", operatorName, operatorReference);
            return false;
        }
    }
}
