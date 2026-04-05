package com.ebithex.sanctions.application;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Moteur de correspondance floue (fuzzy) pour le screening de noms sanctionnés.
 *
 * <h2>Algorithme : Jaro-Winkler</h2>
 * Jaro-Winkler est l'algorithme de référence dans les systèmes KYC/AML (World-Check,
 * LexisNexis, Dow Jones). Il est plus adapté que Levenshtein pour les noms propres :
 * il tolère les transpositions de lettres et valorise un préfixe commun.
 *
 * <p>Retourne un score de 0.0 (aucune similarité) à 1.0 (chaînes identiques).
 *
 * <h2>Normalisation préalable</h2>
 * Avant toute comparaison, les chaînes sont :
 * <ol>
 *   <li>Converties en majuscules</li>
 *   <li>Décomposées NFD pour isoler les diacritiques des caractères de base</li>
 *   <li>Débarrassées des diacritiques (accents, cédilles, tilde…)</li>
 *   <li>Débarrassées de la ponctuation</li>
 *   <li>Normalisées en espaces simples</li>
 * </ol>
 *
 * <p>Exemples après normalisation :
 * <ul>
 *   <li>{@code "Kadhâfi"} → {@code "KADHAFI"}</li>
 *   <li>{@code "Ōsama"} → {@code "OSAMA"}</li>
 *   <li>{@code "al-Qaida"} → {@code "AL QAIDA"}</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   // "OSAMA BIN LADEN" vs "USAMA BIN LADEN" → score ≈ 0.97
 *   double score = FuzzyNameMatcher.bestScore(
 *       "USAMA BIN LADEN",
 *       List.of("OSAMA BIN LADEN", "OBL"));
 * </pre>
 */
public final class FuzzyNameMatcher {

    private static final Pattern DIACRITICS  = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Z0-9 ]");
    private static final Pattern MULTI_SPACE  = Pattern.compile("\\s+");

    private FuzzyNameMatcher() {}

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Retourne le meilleur score Jaro-Winkler entre {@code candidate} et chacun
     * des {@code terms} (nom officiel + aliases d'une entrée de sanctions).
     *
     * @param candidate Nom à vérifier (ex : customerName de la transaction)
     * @param terms     Termes à comparer (nom officiel + liste d'aliases)
     * @return Score entre 0.0 et 1.0 (0.0 si aucun terme valide)
     */
    public static double bestScore(String candidate, List<String> terms) {
        if (candidate == null || terms == null || terms.isEmpty()) return 0.0;
        String nc = normalize(candidate);
        if (nc.isEmpty()) return 0.0;

        double best = 0.0;
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            double score = jaroWinkler(nc, normalize(term));
            if (score > best) {
                best = score;
                if (best == 1.0) break; // correspondance exacte — inutile de continuer
            }
        }
        return best;
    }

    /**
     * Normalise une chaîne pour la comparaison fuzzy.
     * Visible en dehors du package pour les tests d'intégration.
     */
    public static String normalize(String s) {
        if (s == null) return "";
        String upper      = s.toUpperCase();
        String decomposed = Normalizer.normalize(upper, Normalizer.Form.NFD);
        String stripped   = DIACRITICS.matcher(decomposed).replaceAll("");
        String alphanum   = NON_ALPHANUM.matcher(stripped).replaceAll(" ");
        return MULTI_SPACE.matcher(alphanum).replaceAll(" ").trim();
    }

    // ── Jaro-Winkler ──────────────────────────────────────────────────────────

    /**
     * Calcule la similarité Jaro-Winkler entre deux chaînes normalisées.
     * Complexité O(n·m).
     */
    static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        double jaro = jaro(s1, s2);
        if (jaro == 0.0) return 0.0;

        // Bonus Winkler : préfixe commun (max 4 caractères, coefficient 0.1)
        int maxPrefix = Math.min(4, Math.min(s1.length(), s2.length()));
        int prefix = 0;
        for (int i = 0; i < maxPrefix; i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }

        return jaro + prefix * 0.1 * (1.0 - jaro);
    }

    private static double jaro(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();

        // Fenêtre de matching : ⌊max(len1, len2) / 2⌋ - 1
        int window = Math.max(len1, len2) / 2 - 1;
        if (window < 0) window = 0;

        boolean[] m1 = new boolean[len1];
        boolean[] m2 = new boolean[len2];
        int matches = 0;

        for (int i = 0; i < len1; i++) {
            int lo = Math.max(0, i - window);
            int hi = Math.min(i + window + 1, len2);
            for (int j = lo; j < hi; j++) {
                if (m2[j] || s1.charAt(i) != s2.charAt(j)) continue;
                m1[i] = true;
                m2[j] = true;
                matches++;
                break;
            }
        }

        if (matches == 0) return 0.0;

        // Transpositions : nombre de caractères correspondants hors ordre, divisé par 2
        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!m1[i]) continue;
            while (!m2[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }

        return ((double) matches / len1
            + (double) matches / len2
            + (double) (matches - transpositions / 2) / matches) / 3.0;
    }
}
