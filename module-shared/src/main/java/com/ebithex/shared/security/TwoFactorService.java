package com.ebithex.shared.security;

import com.ebithex.shared.event.TwoFactorOtpEvent;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.UUID;

/**
 * Gestion du second facteur (OTP email) pour les opérateurs back-office.
 *
 * Flux :
 *  1. Mot de passe validé → {@link #initiateOtp(String)} :
 *     - Génère un code à 6 chiffres via SecureRandom
 *     - Stocke le code dans Redis (TTL 5 min)
 *     - Retourne un tempToken (UUID random, stocké en Redis associé à l'email)
 *     - Publie {@link TwoFactorOtpEvent} → NotificationService envoie l'email
 *
 *  2. Utilisateur soumet le code → {@link #verifyOtp(String, String)} :
 *     - Vérifie que tempToken est valide (non expiré)
 *     - Vérifie le compteur de tentatives (max {@value #MAX_OTP_ATTEMPTS}) — protection bruteforce
 *     - Vérifie que le code OTP correspond
 *     - Invalide les clés Redis (code, token, compteur)
 *     - Retourne l'email associé (pour émettre le JWT final)
 *
 * <p><b>Protection bruteforce :</b> après {@value #MAX_OTP_ATTEMPTS} tentatives échouées,
 * le tempToken est invalidé et l'utilisateur doit recommencer le login depuis le début.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TwoFactorService {

    private static final Duration OTP_TTL          = Duration.ofMinutes(5);
    private static final String   OTP_PREFIX       = "otp:code:";
    private static final String   TOKEN_PREFIX     = "otp:token:";
    private static final String   ATTEMPTS_PREFIX  = "otp:attempts:";

    /** Nombre maximum de tentatives OTP avant invalidation du tempToken. */
    private static final int MAX_OTP_ATTEMPTS = 5;

    private final StringRedisTemplate      redis;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom             secureRandom = new SecureRandom();

    /**
     * Génère un OTP et un tempToken pour l'email donné.
     * Invalide tout OTP précédent pour cet email (un seul OTP actif à la fois).
     *
     * @return tempToken (à retourner au client pour la vérification)
     */
    public String initiateOtp(String email) {
        String otp       = String.format("%06d", secureRandom.nextInt(1_000_000));
        String tempToken = UUID.randomUUID().toString();

        // code  → redis["otp:code:{email}"]        = "123456"  (TTL 5 min)
        // token → redis["otp:token:{tempToken}"]   = email     (TTL 5 min)
        // Les clés de tentatives précédentes sont implicitement périmées (nouveau token)
        redis.opsForValue().set(OTP_PREFIX   + email,     otp,       OTP_TTL);
        redis.opsForValue().set(TOKEN_PREFIX + tempToken, email,     OTP_TTL);

        eventPublisher.publishEvent(new TwoFactorOtpEvent(email, otp));
        log.debug("OTP initié pour: {}", email);
        return tempToken;
    }

    /**
     * Vérifie le tempToken et le code OTP.
     *
     * <p>Protection bruteforce : le compteur de tentatives est incrémenté à chaque appel.
     * Après {@value #MAX_OTP_ATTEMPTS} échecs, le tempToken est invalidé et une exception
     * {@link ErrorCode#LOGIN_ATTEMPTS_EXCEEDED} est levée.
     *
     * @return email de l'opérateur si la vérification réussit
     * @throws EbithexException OTP_EXPIRED si le tempToken est inconnu ou expiré
     * @throws EbithexException LOGIN_ATTEMPTS_EXCEEDED si trop de tentatives échouées
     * @throws EbithexException OTP_INVALID si le code est incorrect
     */
    public String verifyOtp(String tempToken, String code) {
        // 1. Vérifier que le tempToken est encore valide
        String email = redis.opsForValue().get(TOKEN_PREFIX + tempToken);
        if (email == null) {
            throw new EbithexException(ErrorCode.OTP_EXPIRED,
                "Code expiré ou token invalide. Recommencez la connexion.");
        }

        // 2. Vérifier le compteur de tentatives (protection bruteforce)
        String attemptsKey = ATTEMPTS_PREFIX + tempToken;
        String attemptsStr = redis.opsForValue().get(attemptsKey);
        long attempts = attemptsStr != null ? Long.parseLong(attemptsStr) : 0;

        if (attempts >= MAX_OTP_ATTEMPTS) {
            // Invalider le tempToken pour forcer un nouveau login
            redis.delete(TOKEN_PREFIX  + tempToken);
            redis.delete(ATTEMPTS_PREFIX + tempToken);
            log.warn("OTP bruteforce détecté pour: {} — tempToken invalidé après {} tentatives",
                email, MAX_OTP_ATTEMPTS);
            throw new EbithexException(ErrorCode.LOGIN_ATTEMPTS_EXCEEDED,
                "Trop de tentatives incorrectes. Veuillez recommencer la connexion.");
        }

        // 3. Incrémenter le compteur (TTL aligné sur le TTL du token pour nettoyage automatique)
        redis.opsForValue().increment(attemptsKey);
        redis.expire(attemptsKey, OTP_TTL);

        // 4. Vérifier le code OTP en temps constant (protection timing attack)
        String expected = redis.opsForValue().get(OTP_PREFIX + email);
        boolean codeMatch = expected != null && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            (code != null ? code : "").getBytes(StandardCharsets.UTF_8));
        if (!codeMatch) {
            long newAttempts = attempts + 1;
            log.warn("OTP incorrect pour: {} ({}/{} tentatives)", email, newAttempts, MAX_OTP_ATTEMPTS);
            throw new EbithexException(ErrorCode.OTP_INVALID,
                "Code incorrect. " + (MAX_OTP_ATTEMPTS - newAttempts) + " tentative(s) restante(s).");
        }

        // 5. Succès — invalider toutes les clés associées
        redis.delete(OTP_PREFIX      + email);
        redis.delete(TOKEN_PREFIX    + tempToken);
        redis.delete(ATTEMPTS_PREFIX + tempToken);

        log.info("2FA vérifié avec succès pour: {}", email);
        return email;
    }
}
