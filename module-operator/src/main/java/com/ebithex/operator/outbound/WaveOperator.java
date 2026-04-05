package com.ebithex.operator.outbound;

import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;
import java.util.Set;

/**
 * Adaptateur Wave Checkout API — Côte d'Ivoire + Sénégal
 *
 * Wave expose une API unifiée pour CI et SN (même endpoint, même clé API).
 * Le pays est déterminé automatiquement par le numéro de téléphone du client.
 * Cet adapter est donc enregistré pour WAVE_CI et WAVE_SN via getSupportedTypes().
 *
 * Documentation : https://docs.wave.com/business/checkout
 * Indicatifs : +225 (CI), +221 (SN) | Monnaie : XOF
 */
@Component
@Slf4j
public class WaveOperator implements MobileMoneyOperator {

    private final WebClient webClient;
    private final String apiKey;
    private final String successUrl;
    private final String errorUrl;

    public WaveOperator(
            WebClient.Builder webClientBuilder,
            @Value("${ebithex.operators.wave.base-url:https://api.wave.com/v1}") String baseUrl,
            @Value("${ebithex.operators.wave.api-key:}") String apiKey,
            @Value("${ebithex.operators.wave.success-url:}") String successUrl,
            @Value("${ebithex.operators.wave.error-url:}") String errorUrl) {
        this.webClient  = webClientBuilder.baseUrl(baseUrl).build();
        this.apiKey     = apiKey;
        this.successUrl = successUrl;
        this.errorUrl   = errorUrl;
    }

    @Override
    public OperatorType getOperatorType() {
        return OperatorType.WAVE_SN;  // type principal — marché Sénégal
    }

    /**
     * Wave couvre SN et CI avec la même API — un seul adapter pour les deux.
     */
    @Override
    public Set<OperatorType> getSupportedTypes() {
        return Set.of(OperatorType.WAVE_SN, OperatorType.WAVE_CI);
    }

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public OperatorInitResponse initiatePayment(OperatorPaymentRequest request) {
        Map<String, Object> body = Map.of(
            "currency",         request.currency().name(),
            "amount",           request.amount().toPlainString(),
            "success_url",      successUrl,
            "error_url",        errorUrl,
            "client_reference", request.ebithexReference()
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                .uri("/checkout/sessions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(Map.class).block();

            if (response == null) return OperatorInitResponse.failed("Wave: réponse vide");

            String sessionId = (String) response.get("id");
            String launchUrl = (String) response.get("wave_launch_url");

            if (sessionId == null || launchUrl == null) {
                return OperatorInitResponse.failed("Wave: réponse incomplète");
            }

            log.info("Wave checkout session créée — ebithexRef={} sessionId={}", request.ebithexReference(), sessionId);
            return OperatorInitResponse.redirect(sessionId, launchUrl,
                "Redirigez le client vers Wave pour confirmer le paiement");

        } catch (WebClientResponseException e) {
            log.error("Wave erreur {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            return OperatorInitResponse.failed("Wave a refusé la demande: " + e.getStatusCode());
        }
    }

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public TransactionStatus checkStatus(String operatorReference) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                .uri("/checkout/sessions/{id}", operatorReference)
                .header("Authorization", "Bearer " + apiKey)
                .retrieve().bodyToMono(Map.class).block();

            String paymentStatus  = response != null ? (String) response.get("payment_status") : null;
            String checkoutStatus = response != null ? (String) response.get("checkout_status") : null;

            if ("succeeded".equalsIgnoreCase(paymentStatus))  return TransactionStatus.SUCCESS;
            if ("cancelled".equalsIgnoreCase(paymentStatus))  return TransactionStatus.FAILED;
            if ("expired".equalsIgnoreCase(checkoutStatus))   return TransactionStatus.EXPIRED;
            return TransactionStatus.PROCESSING;

        } catch (WebClientResponseException e) {
            log.warn("Wave checkStatus erreur {} pour {}", e.getStatusCode(), operatorReference);
            return TransactionStatus.PROCESSING;
        }
    }
}
