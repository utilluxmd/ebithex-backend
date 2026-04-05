package com.ebithex.notification.application;

import com.ebithex.merchant.infrastructure.MerchantRepository;
import com.ebithex.shared.event.ApiKeyAgingReminderEvent;
import com.ebithex.shared.event.ApiKeyForcedRotationEvent;
import com.ebithex.shared.event.FloatLowBalanceEvent;
import com.ebithex.shared.event.KycStatusChangedEvent;
import com.ebithex.shared.event.PaymentStatusChangedEvent;
import com.ebithex.shared.event.PayoutStatusChangedEvent;
import com.ebithex.shared.event.TwoFactorOtpEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Service de notifications sortantes (email) vers les marchands.
 *
 * Écoute les événements domaine et envoie des emails asynchrones.
 * Si l'envoi échoue (SMTP down), l'erreur est loggée mais n'affecte
 * pas la transaction principale (découplage complet via événements).
 *
 * Configuration requise (application.properties) :
 *   spring.mail.host, spring.mail.port, spring.mail.username,
 *   spring.mail.password, ebithex.notification.from-email
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationService {

    private final JavaMailSender       mailSender;
    private final MerchantRepository   merchantRepository;

    @Value("${ebithex.notification.from-email:noreply@ebithex.com}")
    private String fromEmail;

    @Value("${ebithex.notification.enabled:true}")
    private boolean notificationsEnabled;

    @Value("${ebithex.notification.finance-alert-email:finance@ebithex.com}")
    private String financeAlertEmail;

    // ── Paiements ─────────────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("batchExecutor")
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {
        if (!notificationsEnabled) return;
        merchantRepository.findById(event.merchantId()).ifPresent(merchant -> {
            switch (event.newStatus()) {
                case SUCCESS -> sendEmail(
                    merchant.getEmail(),
                    "Paiement reçu — " + event.ebithexReference(),
                    buildPaymentSuccessBody(event)
                );
                case FAILED -> sendEmail(
                    merchant.getEmail(),
                    "Paiement échoué — " + event.ebithexReference(),
                    buildPaymentFailedBody(event)
                );
                case EXPIRED -> sendEmail(
                    merchant.getEmail(),
                    "Paiement expiré — " + event.ebithexReference(),
                    buildPaymentExpiredBody(event)
                );
                case REFUNDED -> sendEmail(
                    merchant.getEmail(),
                    "Remboursement effectué — " + event.ebithexReference(),
                    buildPaymentRefundedBody(event)
                );
                default -> { /* Pas de notification pour les autres statuts */ }
            }
        });
    }

    // ── Authentification 2FA ──────────────────────────────────────────────────

    @org.springframework.context.event.EventListener
    @Async
    public void onTwoFactorOtp(TwoFactorOtpEvent event) {
        sendEmail(
            event.email(),
            "Code de vérification Ebithex Back-Office",
            String.format("""
                Bonjour,

                Votre code de vérification pour la connexion back-office Ebithex est :

                        %s

                Ce code est valable 5 minutes.
                Si vous n'avez pas tenté de vous connecter, ignorez ce message et
                signalez immédiatement l'incident à security@ebithex.io.

                L'équipe Ebithex
                """,
                event.otp()
            )
        );
    }

    // ── KYC ───────────────────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onKycStatusChanged(KycStatusChangedEvent event) {
        if (!notificationsEnabled) return;
        if ("APPROVED".equals(event.newStatus())) {
            sendEmail(
                event.merchantEmail(),
                "Votre KYC a été approuvé",
                String.format("""
                    Bonjour %s,

                    Nous avons le plaisir de vous informer que votre dossier KYC a été approuvé.

                    Votre compte Ebithex est maintenant entièrement activé. Vous pouvez
                    désormais accéder à tous les services : paiements, décaissements, webhooks.

                    Si vous avez des questions, contactez-nous à support@ebithex.io.

                    L'équipe Ebithex
                    """,
                    event.businessName()
                )
            );
        } else if ("REJECTED".equals(event.newStatus())) {
            sendEmail(
                event.merchantEmail(),
                "Votre KYC nécessite des corrections",
                String.format("""
                    Bonjour %s,

                    Nous avons examiné votre dossier KYC et nous ne sommes pas en mesure
                    de l'approuver pour le moment.

                    Raison : %s

                    Veuillez téléverser les documents corrigés sur votre espace marchand
                    et soumettre à nouveau votre dossier.

                    Si vous avez des questions, contactez-nous à support@ebithex.io.

                    L'équipe Ebithex
                    """,
                    event.businessName(),
                    event.rejectionReason() != null ? event.rejectionReason() : "Non précisée"
                )
            );
        }
    }

    // ── Alertes finance internes ──────────────────────────────────────────────

    @org.springframework.context.event.EventListener
    @Async
    public void onFloatLowBalance(FloatLowBalanceEvent event) {
        sendEmail(
            financeAlertEmail,
            "⚠️ Float bas — " + event.operator().name(),
            String.format("""
                Alerte trésorerie Ebithex

                L'opérateur %s a un float en dessous du seuil d'alerte.

                Solde actuel : %s XOF
                Seuil d'alerte : %s XOF

                Action requise : approvisionner le float via
                PUT /internal/finance/float/%s

                L'équipe Ebithex (alerte automatique)
                """,
                event.operator().name(),
                event.currentBalance().toPlainString(),
                event.threshold().toPlainString(),
                event.operator().name()
            )
        );
    }

    // ── Clés API ──────────────────────────────────────────────────────────────

    @org.springframework.context.event.EventListener
    @Async
    public void onApiKeyAgingReminder(ApiKeyAgingReminderEvent event) {
        if (!notificationsEnabled) return;
        String body = String.format("""
            Bonjour %s,

            Votre clé API "%s" (%s) est active depuis %d jours.

            Pour des raisons de sécurité, nous vous recommandons de faire tourner
            vos clés API régulièrement (tous les %d jours).

            Pour faire tourner cette clé :
              POST /v1/auth/api-keys/%s/rotate

            La nouvelle clé sera retournée une seule fois. L'ancienne clé restera
            valide pendant 24 heures pour vous permettre une transition sans interruption.

            Si cette clé n'est plus utilisée, révoquez-la :
              DELETE /v1/auth/api-keys/%s

            L'équipe Ebithex
            """,
            event.businessName(), event.keyLabel(), event.keyHint(),
            event.keyAgeDays(), event.alertThresholdDays(),
            event.keyId(), event.keyId()
        );
        sendEmail(event.merchantEmail(),
            "Rappel : rotation de clé API recommandée — " + event.keyHint(), body);
    }

    @org.springframework.context.event.EventListener
    @Async
    public void onApiKeyForcedRotation(ApiKeyForcedRotationEvent event) {
        if (!notificationsEnabled) return;
        String body = String.format("""
            Bonjour %s,

            Votre clé API "%s" (%s) a été désactivée automatiquement.

            La politique de sécurité de votre compte exige une rotation tous les %d jours.
            Cette clé a dépassé ce seuil et ne peut plus être utilisée.

            Pour créer une nouvelle clé :
              POST /v1/auth/api-keys

            Si vous pensez qu'il s'agit d'une erreur, contactez support@ebithex.io.

            L'équipe Ebithex
            """,
            event.businessName(), event.keyLabel(), event.keyHint(),
            event.rotationRequiredDays()
        );
        sendEmail(event.merchantEmail(),
            "URGENT : clé API désactivée — rotation obligatoire dépassée", body);
    }

    // ── Décaissements ─────────────────────────────────────────────────────────

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("batchExecutor")
    public void onPayoutStatusChanged(PayoutStatusChangedEvent event) {
        if (!notificationsEnabled) return;
        merchantRepository.findById(event.merchantId()).ifPresent(merchant -> {
            switch (event.newStatus()) {
                case SUCCESS -> sendEmail(
                    merchant.getEmail(),
                    "Décaissement effectué — " + event.ebithexReference(),
                    buildPayoutSuccessBody(event)
                );
                case FAILED -> sendEmail(
                    merchant.getEmail(),
                    "Décaissement échoué — " + event.ebithexReference(),
                    buildPayoutFailedBody(event)
                );
                default -> { /* Pas de notification pour les autres statuts */ }
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendEmail(String to, String subject, String body) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject("[Ebithex] " + subject);
            message.setText(body);
            mailSender.send(message);
            log.debug("Email envoyé à {} — {}", to, subject);
        } catch (MailException e) {
            log.warn("Échec envoi email à {} — {}: {}", to, subject, e.getMessage());
        }
    }

    private String buildPaymentSuccessBody(PaymentStatusChangedEvent e) {
        return String.format("""
            Bonjour,

            Un paiement a été reçu avec succès sur votre compte Ebithex.

            Référence Ebithex    : %s
            Votre référence      : %s
            Montant              : %s %s
            Frais                : %s %s
            Montant net          : %s %s

            Votre solde wallet a été crédité.

            L'équipe Ebithex
            """,
            e.ebithexReference(), e.merchantReference(),
            e.amount(), e.currency(),
            e.feeAmount(), e.currency(),
            e.netAmount(), e.currency()
        );
    }

    private String buildPaymentFailedBody(PaymentStatusChangedEvent e) {
        return String.format("""
            Bonjour,

            Un paiement a échoué sur votre compte Ebithex.

            Référence Ebithex    : %s
            Votre référence      : %s
            Montant              : %s %s

            Aucun montant n'a été débité. Le client peut réessayer.

            L'équipe Ebithex
            """,
            e.ebithexReference(), e.merchantReference(),
            e.amount(), e.currency()
        );
    }

    private String buildPaymentExpiredBody(PaymentStatusChangedEvent e) {
        return String.format("""
            Bonjour,

            Un paiement a expiré sans confirmation du client.

            Référence Ebithex    : %s
            Votre référence      : %s
            Montant              : %s %s

            Vous pouvez initier un nouveau paiement si nécessaire.

            L'équipe Ebithex
            """,
            e.ebithexReference(), e.merchantReference(),
            e.amount(), e.currency()
        );
    }

    private String buildPaymentRefundedBody(PaymentStatusChangedEvent e) {
        return String.format("""
            Bonjour,

            Un remboursement a été initié pour la transaction suivante.

            Référence Ebithex    : %s
            Votre référence      : %s
            Montant remboursé    : %s %s

            Le montant a été débité de votre wallet Ebithex.

            L'équipe Ebithex
            """,
            e.ebithexReference(), e.merchantReference(),
            e.amount(), e.currency()
        );
    }

    private String buildPayoutSuccessBody(PayoutStatusChangedEvent e) {
        return String.format("""
            Bonjour,

            Un décaissement a été effectué avec succès.

            Référence Ebithex    : %s
            Votre référence      : %s
            Montant envoyé       : %s %s
            Frais                : %s %s

            L'équipe Ebithex
            """,
            e.ebithexReference(), e.merchantReference(),
            e.netAmount(), e.currency(),
            e.feeAmount(), e.currency()
        );
    }

    private String buildPayoutFailedBody(PayoutStatusChangedEvent e) {
        return String.format("""
            Bonjour,

            Un décaissement a échoué. Les fonds ont été recrédités sur votre wallet.

            Référence Ebithex    : %s
            Votre référence      : %s
            Montant              : %s %s

            L'équipe Ebithex
            """,
            e.ebithexReference(), e.merchantReference(),
            e.amount(), e.currency()
        );
    }
}