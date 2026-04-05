package com.ebithex.operator.outbound;

import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptateur MTN Mobile Money — Bénin (Collection)
 *
 * API : MTN MoMo Developer Platform (même structure que MTN CI)
 * Indicatif : +229 | Monnaie : XOF
 */
@Component
@Slf4j
public class MtnMomoBjOperator implements MobileMoneyOperator {

    private static final String TOKEN_KEY = "mtn-momo-bj-token";

    private final WebClient webClient;
    private final OperatorTokenCache tokenCache;
    private final String subscriptionKey;
    private final String apiUser;
    private final String apiKey;
    private final String environment;
    private final String callbackUrl;

    public MtnMomoBjOperator(
            WebClient.Builder webClientBuilder,
            OperatorTokenCache tokenCache,
            @Value("${ebithex.operators.mtn-bj.base-url:https://sandbox.momodeveloper.mtn.com}") String baseUrl,
            @Value("${ebithex.operators.mtn-bj.subscription-key:}") String subscriptionKey,
            @Value("${ebithex.operators.mtn-bj.api-user:}") String apiUser,
            @Value("${ebithex.operators.mtn-bj.api-key:}") String apiKey,
            @Value("${ebithex.operators.mtn-bj.environment:sandbox}") String environment,
            @Value("${ebithex.operators.mtn-bj.callback-url:}") String callbackUrl) {
        this.webClient       = webClientBuilder.baseUrl(baseUrl).build();
        this.tokenCache      = tokenCache;
        this.subscriptionKey = subscriptionKey;
        this.apiUser         = apiUser;
        this.apiKey          = apiKey;
        this.environment     = environment;
        this.callbackUrl     = callbackUrl;
    }

    @Override
    public OperatorType getOperatorType() {
        return OperatorType.MTN_MOMO_BJ;
    }

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public OperatorInitResponse initiatePayment(OperatorPaymentRequest request) {
        String referenceId = UUID.randomUUID().toString();
        String token       = getOrRefreshToken();
        String msisdn      = request.phoneNumber().replaceFirst("^\\+", "");

        Map<String, Object> body = Map.of(
            "amount",      request.amount().toPlainString(),
            "currency",    request.currency().name(),
            "externalId",  request.ebithexReference(),
            "payer",       Map.of("partyIdType", "MSISDN", "partyId", msisdn),
            "payerMessage", request.description() != null ? request.description() : "Paiement Ebithex",
            "payeeNote",   request.ebithexReference()
        );

        try {
            webClient.post()
                .uri("/collection/v1_0/requesttopay")
                .header("Authorization",             "Bearer " + token)
                .header("X-Reference-Id",            referenceId)
                .header("X-Target-Environment",      environment)
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .header("X-Callback-Url",            callbackUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().toBodilessEntity().block();

            log.info("MTN BJ requesttopay accepté — ebithexRef={} mtnRef={}", request.ebithexReference(), referenceId);
            return OperatorInitResponse.processing(referenceId, null,
                "Validez le paiement sur votre téléphone MTN MoMo Bénin");

        } catch (WebClientResponseException e) {
            log.error("MTN BJ collection erreur {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(TOKEN_KEY);
            return OperatorInitResponse.failed("MTN BJ a refusé le paiement: " + e.getStatusCode());
        }
    }

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public TransactionStatus checkStatus(String operatorReference) {
        String token = getOrRefreshToken();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                .uri("/collection/v1_0/requesttopay/{ref}", operatorReference)
                .header("Authorization",             "Bearer " + token)
                .header("X-Target-Environment",      environment)
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .retrieve().bodyToMono(Map.class).block();

            String status = response != null ? (String) response.get("status") : null;
            return switch (status != null ? status.toUpperCase() : "") {
                case "SUCCESSFUL" -> TransactionStatus.SUCCESS;
                case "FAILED"     -> TransactionStatus.FAILED;
                default           -> TransactionStatus.PROCESSING;
            };
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(TOKEN_KEY);
            return TransactionStatus.PROCESSING;
        }
    }

    private String getOrRefreshToken() {
        String cached = tokenCache.get(TOKEN_KEY);
        if (cached != null) return cached;

        String credentials = Base64.getEncoder()
            .encodeToString((apiUser + ":" + apiKey).getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = webClient.post()
            .uri("/collection/token/")
            .header(HttpHeaders.AUTHORIZATION,   "Basic " + credentials)
            .header("Ocp-Apim-Subscription-Key", subscriptionKey)
            .contentType(MediaType.APPLICATION_JSON)
            .retrieve().bodyToMono(Map.class).block();

        if (resp == null) throw new IllegalStateException("MTN BJ: réponse token vide");

        String token     = (String) resp.get("access_token");
        Number expiresIn = (Number)  resp.get("expires_in");
        tokenCache.put(TOKEN_KEY, token, expiresIn != null ? expiresIn.longValue() : 3600);
        return token;
    }
}
