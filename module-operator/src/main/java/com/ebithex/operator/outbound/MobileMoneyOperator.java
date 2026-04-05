package com.ebithex.operator.outbound;

import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;

import java.util.Set;

/**
 * Port (interface) — contrat que chaque adaptateur opérateur Mobile Money doit respecter.
 *
 * Méthodes synchrones : PaymentService est @Transactional (thread bloquant).
 * Les implémentations utilisent WebClient.block() en interne.
 *
 * Un adapter peut supporter plusieurs OperatorType (ex: Wave CI + Wave SN partagent
 * la même API). Dans ce cas, surcharger getSupportedTypes().
 */
public interface MobileMoneyOperator {

    /** Type principal de cet adapter (ex: WAVE_CI pour WaveOperator). */
    OperatorType getOperatorType();

    /**
     * Ensemble complet des OperatorType gérés par cet adapter.
     * Par défaut : singleton {getOperatorType()}.
     * Surcharger si l'adapter couvre plusieurs pays avec la même API.
     */
    default Set<OperatorType> getSupportedTypes() {
        return Set.of(getOperatorType());
    }

    /**
     * Initie un paiement (débit du portefeuille mobile du client).
     * @return OperatorInitResponse avec la référence opérateur et le statut initial
     */
    OperatorInitResponse initiatePayment(OperatorPaymentRequest request);

    /**
     * Interroge le statut actuel d'un paiement en cours.
     * Appelé lors du polling ou d'un GET /payments/{ref}.
     */
    TransactionStatus checkStatus(String operatorReference);

    /**
     * Initie un décaissement (payout) vers le portefeuille mobile d'un bénéficiaire.
     * Non supporté par tous les opérateurs — lève UnsupportedOperationException par défaut.
     */
    default OperatorInitResponse initiateDisbursement(OperatorDisbursementRequest request) {
        throw new UnsupportedOperationException(
            "Disbursement not supported by operator: " + getOperatorType());
    }

    /**
     * Interroge le statut d'un décaissement en cours.
     * Par défaut délègue à checkStatus (même API pour certains opérateurs).
     */
    default TransactionStatus checkDisbursementStatus(String operatorReference) {
        return checkStatus(operatorReference);
    }

    /**
     * Indique si cet opérateur supporte le remboursement programmatique.
     * Vérifier avant d'appeler {@link #reversePayment} pour éviter de trippler le circuit breaker.
     * Par défaut : false.
     */
    default boolean supportsReversal() {
        return false;
    }

    /**
     * Inverse (rembourse) un paiement côté opérateur — crédite le portefeuille mobile du client.
     *
     * Appeler uniquement si {@link #supportsReversal()} retourne {@code true}.
     *
     * @param operatorReference  Référence opérateur de la transaction originale
     * @param amount             Montant à rembourser (en général le montant brut de la transaction)
     * @param currency           Code devise (ex : "XOF")
     * @return OperatorRefundResult avec la référence de remboursement attribuée par l'opérateur
     */
    default OperatorRefundResult reversePayment(String operatorReference,
                                                java.math.BigDecimal amount,
                                                String currency) {
        throw new UnsupportedOperationException(
            "Reversal not supported by operator: " + getOperatorType());
    }

    /**
     * Vérifie le solde du compte float Ebithex chez cet opérateur.
     *
     * Permet de monitorer le float disponible pour les payouts avant de les déclencher.
     * Non supporté par tous les opérateurs — retourne {@link BalanceResult#unavailable} par défaut.
     */
    default BalanceResult checkBalance() {
        return BalanceResult.unavailable("Balance check not supported by " + getOperatorType());
    }

    // ─── Résultats ────────────────────────────────────────────────────────────

    /** Résultat d'un appel reversePayment vers l'opérateur. */
    record OperatorRefundResult(
            boolean success,
            String  operatorRefundReference,
            String  message
    ) {
        public static OperatorRefundResult success(String ref) {
            return new OperatorRefundResult(true, ref, "Remboursement confirmé par l'opérateur");
        }

        public static OperatorRefundResult failure(String message) {
            return new OperatorRefundResult(false, null, message);
        }
    }

    /**
     * Résultat d'une vérification de solde float opérateur.
     *
     * @param available true si le solde a pu être récupéré avec succès
     * @param balance   Solde disponible (null si non disponible)
     * @param currency  Code devise du solde (ex: "XOF")
     * @param message   Message d'erreur si non disponible, null sinon
     */
    record BalanceResult(
            boolean    available,
            java.math.BigDecimal balance,
            String     currency,
            String     message
    ) {
        public static BalanceResult success(java.math.BigDecimal balance, String currency) {
            return new BalanceResult(true, balance, currency, null);
        }

        public static BalanceResult unavailable(String message) {
            return new BalanceResult(false, null, null, message);
        }
    }

    // ─── Réponse unifiée retournée à PaymentService / PayoutService ──────────

    record OperatorInitResponse(
            String operatorReference,      // ID attribué par l'opérateur (pour callbacks)
            TransactionStatus initialStatus,
            String paymentUrl,             // URL de redirection (Orange, Wave)
            String ussdCode,               // Code USSD à composer (MTN)
            String message                 // Message informatif
    ) {
        /** Paiement accepté et en attente de confirmation du client. */
        public static OperatorInitResponse processing(String operatorRef, String ussdCode, String msg) {
            return new OperatorInitResponse(operatorRef, TransactionStatus.PROCESSING, null, ussdCode, msg);
        }

        /** Paiement redirigé vers URL (Orange, Wave). */
        public static OperatorInitResponse redirect(String operatorRef, String paymentUrl, String msg) {
            return new OperatorInitResponse(operatorRef, TransactionStatus.PROCESSING, paymentUrl, null, msg);
        }

        /** Paiement confirmé avec succès par l'opérateur (synchrone). */
        public static OperatorInitResponse success(String operatorRef, String ussdCode, String msg) {
            return new OperatorInitResponse(operatorRef, TransactionStatus.SUCCESS, null, ussdCode, msg);
        }

        /** Paiement refusé immédiatement par l'opérateur. */
        public static OperatorInitResponse failed(String msg) {
            return new OperatorInitResponse(null, TransactionStatus.FAILED, null, null, msg);
        }
    }
}
