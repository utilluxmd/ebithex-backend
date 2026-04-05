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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Adaptateur Orange Money Web Pay — Sénégal
 *
 * API : https://developer.orange.com/apis/orange-money-webpay-sn-v1
 * Indicatif : +221 | Monnaie : XOF | Endpoint : /orange-money-webpay/sn/v1
 *
 * Credentials séparés d'Orange CI (merchant key différent, compte Orange SN).
 */
@Component
@Slf4j
public class OrangeMoneySnOperator implements MobileMoneyOperator {

    private static final String TOKEN_CACHE_KEY = "orange-money-sn-token";
    private static final String OAUTH_URL       = "https://api.orange.com";

    private final WebClient webPayClient;
    private final WebClient oauthClient;
    private final OperatorTokenCache tokenCache;
    private final String clientId;
    private final String clientSecret;
    private final String merchantKey;
    private final String notifUrl;
    private final String returnUrl;
    private final String cancelUrl;

    public OrangeMoneySnOperator(
            WebClient.Builder webClientBuilder,
            OperatorTokenCache tokenCache,
            @Value("${ebithex.operators.orange-sn.base-url:https://api.orange.com/orange-money-webpay/sn/v1}") String baseUrl,
            @Value("${ebithex.operators.orange-sn.client-id:}") String clientId,
            @Value("${ebithex.operators.orange-sn.client-secret:}") String clientSecret,
            @Value("${ebithex.operators.orange-sn.merchant-key:}") String merchantKey,
            @Value("${ebithex.operators.orange-sn.notif-url:}") String notifUrl,
            @Value("${ebithex.operators.orange-sn.return-url:}") String returnUrl,
            @Value("${ebithex.operators.orange-sn.cancel-url:}") String cancelUrl) {
        this.webPayClient = webClientBuilder.baseUrl(baseUrl).build();
        this.oauthClient  = webClientBuilder.baseUrl(OAUTH_URL).build();
        this.tokenCache   = tokenCache;
        this.clientId     = clientId;
        this.clientSecret = clientSecret;
        this.merchantKey  = merchantKey;
        this.notifUrl     = notifUrl;
        this.returnUrl    = returnUrl;
        this.cancelUrl    = cancelUrl;
    }

    @Override
    public OperatorType getOperatorType() {
        return OperatorType.ORANGE_MONEY_SN;
    }

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public OperatorInitResponse initiatePayment(OperatorPaymentRequest request) {
        String token = getOrRefreshToken();

        Map<String, Object> body = Map.of(
            "merchant_key", merchantKey,
            "currency",     "OUV",
            "order_id",     request.ebithexReference(),
            "amount",       request.amount().toPlainString(),
            "return_url",   returnUrl,
            "cancel_url",   cancelUrl,
            "notif_url",    notifUrl,
            "lang",         "fr",
            "reference",    request.ebithexReference()
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webPayClient.post()
                .uri("/webpayment")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(Map.class).block();

            if (response == null) return OperatorInitResponse.failed("Orange SN: réponse vide");

            String payToken   = (String) response.get("pay_token");
            String paymentUrl = (String) response.get("payment_url");

            if (payToken == null || paymentUrl == null) {
                return OperatorInitResponse.failed("Orange SN: réponse incomplète");
            }
            if ("FAILED".equalsIgnoreCase((String) response.get("status"))) {
                return OperatorInitResponse.failed((String) response.getOrDefault("message", "Refusé par Orange SN"));
            }

            log.info("Orange SN webpayment créé — ebithexRef={} payToken={}", request.ebithexReference(), payToken);
            return OperatorInitResponse.redirect(payToken, paymentUrl,
                "Redirigez le client vers l'interface Orange Money Sénégal");

        } catch (WebClientResponseException e) {
            log.error("Orange SN erreur {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(TOKEN_CACHE_KEY);
            return OperatorInitResponse.failed("Orange SN a refusé la demande: " + e.getStatusCode());
        }
    }

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public TransactionStatus checkStatus(String operatorReference) {
        String token = getOrRefreshToken();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webPayClient.get()
                .uri("/paymentstatus/{payToken}", operatorReference)
                .header("Authorization", "Bearer " + token)
                .retrieve().bodyToMono(Map.class).block();

            String status = response != null ? (String) response.get("status") : null;
            if (status == null) return TransactionStatus.PROCESSING;
            return switch (status.toUpperCase()) {
                case "SUCCESS"              -> TransactionStatus.SUCCESS;
                case "FAILED", "CANCELLED" -> TransactionStatus.FAILED;
                case "EXPIRED"              -> TransactionStatus.EXPIRED;
                default                     -> TransactionStatus.PROCESSING;
            };
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(TOKEN_CACHE_KEY);
            return TransactionStatus.PROCESSING;
        }
    }

    private String getOrRefreshToken() {
        String cached = tokenCache.get(TOKEN_CACHE_KEY);
        if (cached != null) return cached;

        String credentials = Base64.getEncoder()
            .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = oauthClient.post()
            .uri("/oauth/v3/token")
            .header("Authorization", "Basic " + credentials)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .retrieve().bodyToMono(Map.class).block();

        if (resp == null) throw new IllegalStateException("Orange SN: réponse token vide");

        String token     = (String) resp.get("access_token");
        Number expiresIn = (Number)  resp.get("expires_in");
        tokenCache.put(TOKEN_CACHE_KEY, token, expiresIn != null ? expiresIn.longValue() : 3600);
        return token;
    }
}