package com.ebithex.shared;

import com.ebithex.shared.domain.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.ebithex.shared.domain.TransactionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests unitaires — Machine à états TransactionStatus.
 *
 * Transitions valides (selon la logique métier Ebithex) :
 *   PENDING     → PROCESSING, EXPIRED, CANCELLED
 *   PROCESSING  → SUCCESS, FAILED, EXPIRED
 *   SUCCESS     → REFUNDED
 *   FAILED      → (terminal)
 *   EXPIRED     → (terminal)
 *   REFUNDED    → (terminal)
 *   CANCELLED   → (terminal)
 */
@DisplayName("TransactionStatus — Machine à états")
class TransactionStatusTest {

    // ── Transitions valides ──────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} → {1} est valide")
    @CsvSource({
        "PENDING,    PROCESSING",
        "PENDING,    EXPIRED",
        "PENDING,    CANCELLED",
        "PROCESSING, SUCCESS",
        "PROCESSING, FAILED",
        "PROCESSING, EXPIRED",
        "SUCCESS,    REFUNDED",
    })
    @DisplayName("Les transitions valides sont autorisées")
    void validTransitions(TransactionStatus from, TransactionStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    // ── Transitions invalides ────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} → {1} est invalide")
    @CsvSource({
        "PENDING,    SUCCESS",    // doit passer par PROCESSING
        "PENDING,    FAILED",     // doit passer par PROCESSING
        "PENDING,    REFUNDED",   // ne peut pas être remboursé sans succès
        "SUCCESS,    PENDING",    // pas de retour arrière
        "SUCCESS,    FAILED",     // pas de retour arrière
        "FAILED,     SUCCESS",    // état terminal
        "FAILED,     PENDING",    // état terminal
        "EXPIRED,    PROCESSING", // état terminal
        "EXPIRED,    SUCCESS",    // état terminal
        "REFUNDED,   SUCCESS",    // état terminal
        "REFUNDED,   PENDING",    // état terminal
        "CANCELLED,  PROCESSING", // état terminal
        "CANCELLED,  SUCCESS",    // état terminal
    })
    @DisplayName("Les transitions invalides sont refusées")
    void invalidTransitions(TransactionStatus from, TransactionStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    // ── États terminaux ──────────────────────────────────────────────────────

    @ParameterizedTest(name = "{0} est terminal ou a des transitions limitées")
    @CsvSource({ "SUCCESS", "PARTIALLY_REFUNDED", "FAILED", "EXPIRED", "REFUNDED", "CANCELLED" })
    @DisplayName("Les états terminaux ne permettent aucune transition (sauf SUCCESS et PARTIALLY_REFUNDED)")
    void terminalStates_noForwardTransitions(TransactionStatus status) {
        long forwardTransitions = java.util.Arrays.stream(TransactionStatus.values())
            .filter(target -> target != status && status.canTransitionTo(target))
            .count();

        // SUCCESS → REFUNDED, PARTIALLY_REFUNDED (2 transitions autorisées)
        if (status == SUCCESS) {
            assertThat(forwardTransitions).isEqualTo(2);
        // PARTIALLY_REFUNDED → REFUNDED (1 transition autorisée)
        } else if (status == PARTIALLY_REFUNDED) {
            assertThat(forwardTransitions).isEqualTo(1);
        } else {
            assertThat(forwardTransitions).isZero();
        }
    }

    // ── Auto-transitions ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Aucun statut ne peut se transitionner vers lui-même")
    void noSelfTransitions() {
        for (TransactionStatus status : TransactionStatus.values()) {
            assertThat(status.canTransitionTo(status))
                .as("Self-transition de %s vers %s doit être interdite", status, status)
                .isFalse();
        }
    }

    // ── Énumération complète ─────────────────────────────────────────────────

    @Test
    @DisplayName("Tous les 8 statuts sont présents dans l'énumération")
    void enumContainsAllStatuses() {
        assertThat(TransactionStatus.values()).containsExactlyInAnyOrder(
            PENDING, PROCESSING, SUCCESS, FAILED, EXPIRED, REFUNDED, PARTIALLY_REFUNDED, CANCELLED
        );
    }
}