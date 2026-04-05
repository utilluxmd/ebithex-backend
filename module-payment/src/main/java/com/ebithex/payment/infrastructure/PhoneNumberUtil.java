package com.ebithex.payment.infrastructure;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.Phonenumber;
import com.ebithex.shared.domain.OperatorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalisation et détection d'opérateur des numéros de téléphone UEMOA.
 *
 * Utilise Google libphonenumber pour :
 * - Valider le format international (E.164)
 * - Convertir les formats locaux en E.164
 * - Vérifier la validité réelle du numéro par pays
 *
 * La détection retourne un OperatorType GRANULAIRE par pays (ex: ORANGE_MONEY_CI,
 * pas ORANGE_MONEY générique) pour permettre le routage vers le bon adapter.
 */
@Component
@Slf4j
public class PhoneNumberUtil {

    private static final com.google.i18n.phonenumbers.PhoneNumberUtil PHONE_UTIL =
            com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance();

    /**
     * Mapping préfixe E.164 → OperatorType granulaire par pays.
     *
     * Sources :
     *  - ARCEP CI, ARTP SN, ATRPT BJ, ARCEP BF, CRT CM
     *  - Formats E.164 : +[indicatif][préfixe opérateur][numéro local]
     *
     * Ordre : du plus spécifique (préfixe long) au plus générique.
     * LinkedHashMap préserve l'ordre d'insertion pour l'évaluation séquentielle.
     */
    private static final Map<Pattern, OperatorType> PREFIX_MAP = new LinkedHashMap<>();

    static {
        // ── Côte d'Ivoire (+225) — 10 chiffres après indicatif ───────────────
        PREFIX_MAP.put(Pattern.compile("^\\+2250[5789]\\d{7}$"), OperatorType.MTN_MOMO_CI);
        PREFIX_MAP.put(Pattern.compile("^\\+2250[47]\\d{7}$"),   OperatorType.ORANGE_MONEY_CI);
        PREFIX_MAP.put(Pattern.compile("^\\+2250[16]\\d{7}$"),   OperatorType.MOOV_MONEY_CI);
        PREFIX_MAP.put(Pattern.compile("^\\+2250[23]\\d{7}$"),   OperatorType.WAVE_CI);

        // ── Sénégal (+221) — 9 chiffres après indicatif ──────────────────────
        PREFIX_MAP.put(Pattern.compile("^\\+2217[78]\\d{7}$"),   OperatorType.ORANGE_MONEY_SN);
        PREFIX_MAP.put(Pattern.compile("^\\+2213[78]\\d{7}$"),   OperatorType.WAVE_SN);
        PREFIX_MAP.put(Pattern.compile("^\\+22176\\d{7}$"),       OperatorType.FREE_MONEY_SN);

        // ── Bénin (+229) — 8 chiffres après indicatif ────────────────────────
        PREFIX_MAP.put(Pattern.compile("^\\+2299[67]\\d{6}$"),   OperatorType.MTN_MOMO_BJ);
        PREFIX_MAP.put(Pattern.compile("^\\+2299[45]\\d{6}$"),   OperatorType.MOOV_MONEY_BJ);

        // ── Burkina Faso (+226) — 8 chiffres après indicatif ─────────────────
        PREFIX_MAP.put(Pattern.compile("^\\+2266[57]\\d{6}$"),   OperatorType.ORANGE_MONEY_BF);
        PREFIX_MAP.put(Pattern.compile("^\\+2266[09]\\d{6}$"),   OperatorType.MOOV_MONEY_BF);

        // ── Mali (+223) — 8 chiffres après indicatif ─────────────────────────
        PREFIX_MAP.put(Pattern.compile("^\\+2236[56789]\\d{6}$"), OperatorType.ORANGE_MONEY_ML);

        // ── Togo (+228) — 8 chiffres après indicatif ─────────────────────────
        PREFIX_MAP.put(Pattern.compile("^\\+2289[012]\\d{6}$"),  OperatorType.TMONEY_TG);
        PREFIX_MAP.put(Pattern.compile("^\\+2289[789]\\d{6}$"),  OperatorType.FLOOZ_TG);

        // ── Cameroun (+237) — 9 chiffres après indicatif (XAF) ───────────────
        PREFIX_MAP.put(Pattern.compile("^\\+2376[5789]\\d{7}$"), OperatorType.MTN_MOMO_CM);
        PREFIX_MAP.put(Pattern.compile("^\\+2376[23]\\d{7}$"),   OperatorType.ORANGE_MONEY_CM);
    }

    /**
     * Normalise un numéro vers le format E.164 international.
     *
     * @param raw           numéro brut (ex: 771234567, +221771234567)
     * @param defaultRegion code pays ISO-3166 si pas de préfixe + (ex: "SN", "CI")
     */
    public String normalizeToE164(String raw, String defaultRegion) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.replaceAll("[\\s\\-().]", "");
        try {
            Phonenumber.PhoneNumber parsed = PHONE_UTIL.parse(cleaned,
                    defaultRegion != null ? defaultRegion.toUpperCase() : "CI");
            return PHONE_UTIL.format(parsed, com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException e) {
            log.debug("Impossible de normaliser '{}': {}", raw, e.getErrorType());
            return null;
        }
    }

    /** Normalise avec région SN par défaut (marché principal). */
    public String normalizePhone(String phone) {
        String e164 = normalizeToE164(phone, "SN");
        return e164 != null ? e164 : (phone != null ? phone.replaceAll("[\\s\\-]", "") : null);
    }

    public boolean isValid(String phone) {
        if (phone == null || phone.isBlank()) return false;
        try {
            Phonenumber.PhoneNumber parsed = PHONE_UTIL.parse(phone, null);
            return PHONE_UTIL.isValidNumber(parsed);
        } catch (NumberParseException e) {
            return false;
        }
    }

    /**
     * Détecte automatiquement l'opérateur Mobile Money par préfixe E.164.
     * Retourne un OperatorType granulaire (ex: ORANGE_MONEY_CI, pas ORANGE_MONEY).
     */
    public OperatorType detectOperator(String phoneNumber) {
        if (phoneNumber == null) return null;
        String normalized = normalizePhone(phoneNumber);
        if (normalized == null) return null;
        return PREFIX_MAP.entrySet().stream()
                .filter(e -> e.getKey().matcher(normalized).matches())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }
}