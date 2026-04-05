package com.ebithex.webhook.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ebithex.shared.audit.AuditLogService;
import com.ebithex.shared.event.PaymentStatusChangedEvent;
import com.ebithex.shared.event.PayoutStatusChangedEvent;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.util.HmacUtil;
import com.ebithex.webhook.domain.WebhookDelivery;
import com.ebithex.webhook.domain.WebhookEndpoint;
import com.ebithex.webhook.domain.WebhookEvent;
import com.ebithex.webhook.dto.WebhookRequest;
import com.ebithex.webhook.dto.WebhookResponse;
import com.ebithex.webhook.infrastructure.WebhookDeliveryRepository;
import com.ebithex.webhook.infrastructure.WebhookEndpointRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.reactive.function.client.WebClient;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final WebhookEndpointRepository endpointRepo;
    private final WebhookDeliveryRepository deliveryRepo;
    private final HmacUtil hmacUtil;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    private final WebhookUrlValidator urlValidator;
    private final AuditLogService auditLogService;

    // -------------------------------------------------------------------------
    // Event listener — fires after the payment transaction commits
    // -------------------------------------------------------------------------

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("webhookExecutor")
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {
        String webhookEvent = switch (event.newStatus()) {
            case SUCCESS   -> WebhookEvent.PAYMENT_SUCCESS;
            case FAILED    -> WebhookEvent.PAYMENT_FAILED;
            case EXPIRED   -> WebhookEvent.PAYMENT_EXPIRED;
            case PENDING   -> WebhookEvent.PAYMENT_PENDING;
            case CANCELLED -> WebhookEvent.PAYMENT_CANCELLED;
            case REFUNDED           -> WebhookEvent.REFUND_COMPLETED;
            case PARTIALLY_REFUNDED -> WebhookEvent.REFUND_PARTIAL_COMPLETED;
            default        -> null;
        };
        if (webhookEvent == null) return;
        dispatchToMerchant(event.merchantId(), event.transactionId(),
            event.ebithexReference(), event.merchantReference(),
            event.newStatus().name(), event.previousStatus().name(), webhookEvent);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("webhookExecutor")
    public void onPayoutStatusChanged(PayoutStatusChangedEvent event) {
        String webhookEvent = switch (event.newStatus()) {
            case SUCCESS -> WebhookEvent.PAYOUT_SUCCESS;
            case FAILED  -> WebhookEvent.PAYOUT_FAILED;
            case EXPIRED -> WebhookEvent.PAYOUT_EXPIRED;
            default      -> null;
        };
        if (webhookEvent == null) return;
        dispatchToMerchant(event.merchantId(), event.payoutId(),
            event.ebithexReference(), event.merchantReference(),
            event.newStatus().name(), event.previousStatus().name(), webhookEvent);
    }

    // -------------------------------------------------------------------------
    // Public API — called by WebhookController
    // -------------------------------------------------------------------------

    @Transactional
    public WebhookResponse register(UUID merchantId, WebhookRequest request) {
        // Validation SSRF avant toute persistance
        urlValidator.validate(request.url());

        // Secret cryptographiquement fort (32 bytes = 256 bits)
        byte[] secretBytes = new byte[32];
        new java.security.SecureRandom().nextBytes(secretBytes);
        String secret = java.util.HexFormat.of().formatHex(secretBytes);

        WebhookEndpoint endpoint = WebhookEndpoint.builder()
                .merchantId(merchantId)
                .url(request.url())
                .signingSecret(secret)
                .events(request.events() != null ? new HashSet<>(request.events()) : new HashSet<>())
                .build();
        endpoint = endpointRepo.save(endpoint);
        return toResponse(endpoint);
    }

    /**
     * Historique paginé des livraisons d'un endpoint webhook.
     * Vérifie que l'endpoint appartient bien au marchand demandeur.
     *
     * @throws EbithexException WEBHOOK_NOT_FOUND si introuvable ou hors périmètre
     */
    @Transactional(readOnly = true)
    public Page<WebhookDelivery> listDeliveries(UUID endpointId, UUID merchantId, Pageable pageable) {
        WebhookEndpoint endpoint = endpointRepo.findById(endpointId)
            .orElseThrow(() -> new EbithexException(ErrorCode.WEBHOOK_NOT_FOUND,
                "Webhook introuvable: " + endpointId));
        if (!endpoint.getMerchantId().equals(merchantId)) {
            throw new EbithexException(ErrorCode.WEBHOOK_NOT_FOUND, "Webhook introuvable: " + endpointId);
        }
        return deliveryRepo.findByEndpointIdOrderByCreatedAtDesc(endpointId, pageable);
    }

    @Transactional(readOnly = true)
    public List<WebhookResponse> listForMerchant(UUID merchantId) {
        return endpointRepo.findByMerchantId(merchantId).stream()
                .map(this::toResponse)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Test delivery — valide la configuration webhook du marchand
    // -------------------------------------------------------------------------

    /**
     * Envoie un événement synthétique {@code webhook.test} vers l'URL configurée.
     *
     * Permet au marchand de vérifier en un appel que :
     *  - Son URL est accessible depuis Ebithex
     *  - Son serveur valide correctement la signature HMAC-SHA256
     *  - Son handler traite le JSON sans erreur
     *
     * La livraison test n'est PAS persistée dans webhook_deliveries (elle n'est pas
     * un événement de paiement réel). Le résultat HTTP est retourné directement.
     *
     * @throws EbithexException WEBHOOK_NOT_FOUND si introuvable ou hors périmètre
     * @throws EbithexException WEBHOOK_INACTIVE si l'endpoint est désactivé
     */
    public WebhookTestResult sendTestDelivery(UUID endpointId, UUID merchantId) {
        WebhookEndpoint endpoint = endpointRepo.findById(endpointId)
            .orElseThrow(() -> new EbithexException(ErrorCode.WEBHOOK_NOT_FOUND,
                "Webhook introuvable: " + endpointId));

        if (!endpoint.getMerchantId().equals(merchantId)) {
            throw new EbithexException(ErrorCode.WEBHOOK_NOT_FOUND, "Webhook introuvable: " + endpointId);
        }
        if (!endpoint.isActive()) {
            throw new EbithexException(ErrorCode.WEBHOOK_INACTIVE, "Cet endpoint webhook est désactivé");
        }

        Map<String, Object> payload = Map.of(
            "event",             WebhookEvent.WEBHOOK_TEST,
            "ebithexReference",  "AP-TEST-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(),
            "merchantReference", "TEST-" + Instant.now().toEpochMilli(),
            "status",            "SUCCESS",
            "previousStatus",    "PROCESSING",
            "timestamp",         Instant.now().toString(),
            "test",              true
        );
        String json = serialize(payload);
        String signature = hmacUtil.sign(endpoint.getSigningSecret(), json);

        try {
            Integer httpStatus = webClientBuilder.build()
                .post()
                .uri(endpoint.getUrl())
                .header("Content-Type", "application/json")
                .header("X-Ebithex-Signature", signature)
                .header("X-Ebithex-Event", WebhookEvent.WEBHOOK_TEST)
                .bodyValue(json)
                .retrieve()
                .toBodilessEntity()
                .map(r -> r.getStatusCode().value())
                .block();

            boolean success = httpStatus != null && httpStatus >= 200 && httpStatus < 300;
            log.info("Webhook test delivery: endpointId={} url={} httpStatus={}",
                endpointId, endpoint.getUrl(), httpStatus);
            return new WebhookTestResult(success, httpStatus != null ? httpStatus : 0,
                success ? "Webhook test reçu avec succès (HTTP " + httpStatus + ")"
                        : "L'URL a répondu HTTP " + httpStatus + " — vérifiez votre handler");

        } catch (Exception e) {
            log.warn("Webhook test delivery échoué: endpointId={} error={}", endpointId, e.getMessage());
            return new WebhookTestResult(false, 0,
                "Erreur de connexion vers " + endpoint.getUrl() + " : " + e.getMessage());
        }
    }

    /**
     * Résultat d'une livraison de test webhook.
     *
     * @param success    true si l'URL a répondu avec un code 2xx
     * @param httpStatus Code HTTP reçu (0 si erreur réseau)
     * @param message    Message lisible décrivant le résultat
     */
    public record WebhookTestResult(boolean success, int httpStatus, String message) {}

    // -------------------------------------------------------------------------
    // DLQ — Dead Letter Queue (back-office)
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<WebhookDelivery> listDeadLetters() {
        return deliveryRepo.findDeadLettered();
    }

    @Transactional
    public void retryDeadLetter(UUID deliveryId) {
        WebhookDelivery delivery = deliveryRepo.findById(deliveryId)
            .orElseThrow(() -> new IllegalArgumentException("Delivery introuvable: " + deliveryId));
        if (!delivery.isDeadLettered()) {
            throw new IllegalStateException("La livraison n'est pas en DLQ: " + deliveryId);
        }
        // Réinitialiser pour une nouvelle tentative
        delivery.setDeadLettered(false);
        delivery.setDeadLetteredAt(null);
        delivery.setAttemptCount(0);
        delivery.setNextRetryAt(null);
        delivery.setLastError(null);
        deliveryRepo.save(delivery);
        log.info("Webhook DLQ retry: deliveryId={}", deliveryId);
        auditLogService.record("WEBHOOK_DLQ_RETRY", "WebhookDelivery", deliveryId.toString(), null);
    }

    @Transactional
    public void disable(UUID endpointId, UUID merchantId) {
        endpointRepo.findById(endpointId).ifPresent(ep -> {
            if (!ep.getMerchantId().equals(merchantId)) {
                throw new SecurityException("Not authorized to modify this webhook");
            }
            ep.setActive(false);
            endpointRepo.save(ep);
        });
    }

    // -------------------------------------------------------------------------
    // Retry scheduler — runs every 5 minutes
    // -------------------------------------------------------------------------

    @Scheduled(fixedDelay = 300_000)
    @Transactional
    public void retryFailedDeliveries() {
        List<WebhookDelivery> pending = deliveryRepo.findPendingRetries(Instant.now());
        log.info("Webhook retry job: {} pending deliveries", pending.size());
        for (WebhookDelivery delivery : pending) {
            endpointRepo.findById(delivery.getEndpointId()).ifPresent(ep -> {
                sendDelivery(ep, delivery);
            });
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private void dispatchToMerchant(UUID merchantId, UUID transactionId,
                                     String ebithexRef, String merchantRef,
                                     String status, String previousStatus,
                                     String webhookEvent) {
        List<WebhookEndpoint> endpoints = endpointRepo.findActiveByMerchantIdWithEvents(merchantId);
        for (WebhookEndpoint endpoint : endpoints) {
            if (!endpoint.getEvents().isEmpty() && !endpoint.getEvents().contains(webhookEvent)) {
                continue;
            }
            dispatchAsync(endpoint, transactionId, ebithexRef, merchantRef,
                status, previousStatus, webhookEvent);
        }
    }

    @Async("webhookExecutor")
    protected void dispatchAsync(WebhookEndpoint endpoint, UUID transactionId,
                                  String ebithexRef, String merchantRef,
                                  String status, String previousStatus,
                                  String webhookEvent) {
        Map<String, Object> payload = Map.of(
            "event",             webhookEvent,
            "ebithexReference",  ebithexRef,
            "merchantReference", merchantRef,
            "status",            status,
            "previousStatus",    previousStatus,
            "timestamp",         Instant.now().toString()
        );
        String json = serialize(payload);

        WebhookDelivery delivery = WebhookDelivery.builder()
                .endpointId(endpoint.getId())
                .transactionId(transactionId)
                .event(webhookEvent)
                .payload(json)
                .build();
        delivery = deliveryRepo.save(delivery);

        sendDelivery(endpoint, delivery);
    }

    /**
     * Envoie une livraison webhook vers l'URL du marchand.
     * Protégé par un {@code @CircuitBreaker} : si 60 % des 10 dernières tentatives
     * échouent (timeout, réseau, 5xx), le circuit s'ouvre 30 s pour protéger le
     * pool de threads du {@code webhookExecutor}.
     *
     * <p>Le fallback {@link #sendDeliveryFallback} est appelé lorsque le circuit est ouvert
     * ou que la méthode lève une exception non gérée en interne.
     *
     * <p>Métriques Micrometer : {@code webhook.delivery} histogram (latence p50/p95/p99)
     * et compteur de succès/échec disponibles sur {@code /actuator/prometheus}.
     */
    @CircuitBreaker(name = "webhook-delivery", fallbackMethod = "sendDeliveryFallback")
    @Timed(value = "webhook.delivery",
           description = "Latence des livraisons HTTP webhook vers les URLs marchandes",
           histogram = true,
           percentiles = {0.5, 0.95, 0.99})
    void sendDelivery(WebhookEndpoint endpoint, WebhookDelivery delivery) {
        String signature = hmacUtil.sign(endpoint.getSigningSecret(), delivery.getPayload());
        try {
            Integer status = webClientBuilder.build()
                    .post()
                    .uri(endpoint.getUrl())
                    .header("Content-Type", "application/json")
                    .header("X-Ebithex-Signature", signature)
                    .header("X-Ebithex-Event", delivery.getEvent())
                    .bodyValue(delivery.getPayload())
                    .retrieve()
                    .toBodilessEntity()
                    .map(r -> r.getStatusCode().value())
                    .block();

            delivery.setHttpStatus(status);
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            if (status != null && status >= 200 && status < 300) {
                delivery.setDelivered(true);
                delivery.setDeliveredAt(Instant.now());
                log.info("Webhook delivered to {} status={}", endpoint.getUrl(), status);
            } else {
                scheduleRetry(delivery);
            }
        } catch (Exception ex) {
            log.warn("Webhook delivery failed to {}: {}", endpoint.getUrl(), ex.getMessage());
            delivery.setLastError(ex.getMessage());
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            scheduleRetry(delivery);
        }
        deliveryRepo.save(delivery);
    }

    /**
     * Fallback déclenché lorsque le circuit {@code webhook-delivery} est ouvert.
     * On planifie un retry différé sans tenter l'appel HTTP.
     */
    void sendDeliveryFallback(WebhookEndpoint endpoint, WebhookDelivery delivery, Throwable cause) {
        log.warn("Circuit ouvert pour webhook-delivery — livraison différée: " +
                 "endpointId={} event={} cause={}", delivery.getEndpointId(), delivery.getEvent(),
                 cause.getMessage());
        delivery.setLastError("Circuit ouvert : " + cause.getMessage());
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        scheduleRetry(delivery);
        deliveryRepo.save(delivery);
    }

    private void scheduleRetry(WebhookDelivery delivery) {
        int attempts = delivery.getAttemptCount();
        if (attempts < 5) {
            // Exponential back-off: 1m, 5m, 30m, 2h, 8h
            long[] delaysMinutes = {1, 5, 30, 120, 480};
            delivery.setNextRetryAt(Instant.now().plus(delaysMinutes[Math.min(attempts, 4)], ChronoUnit.MINUTES));
        } else {
            // Toutes les tentatives épuisées — déplacer en DLQ
            delivery.setDeadLettered(true);
            delivery.setDeadLetteredAt(Instant.now());
            delivery.setNextRetryAt(null);
            log.warn("Webhook DLQ: endpointId={} event={} après {} tentatives",
                delivery.getEndpointId(), delivery.getEvent(), attempts);
        }
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private WebhookResponse toResponse(WebhookEndpoint ep) {
        return new WebhookResponse(ep.getId(), ep.getUrl(), ep.getEvents(), ep.isActive(), ep.getCreatedAt(), ep.getSigningSecret());
    }
}
