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

import java.util.Map;
import java.util.UUID;

/**
 * Adaptateur Orange Money Web Pay — Cameroun (Collection)
 *
 * API : Orange Money Web Pay CM
 * Indicatif : +237 | Monnaie : XAF
 *
 * Flux : OAuth2 token → initiate payment → redirect URL → callback
 * Disbursement : non supporté (Orange CM ne propose pas de B2C direct via API)
 */
@Component
@Slf4j
public class OrangeMoneyCmOperator implements MobileMoneyOperator {

    private static final String TOKEN_KEY = "orange-money-cm-token";

    private final WebClient webClient;
    private final OperatorTokenCache tokenCache;
    private final String clientId;
    private final String clientSecret;
    private final String merchantKey;
    private final String notifUrl;
    private final String returnUrl;
    private final String cancelUrl;

    public OrangeMoneyCmOperator(
            WebClient.Builder webClientBuilder,
            OperatorTokenCache tokenCache,
            @Value("${ebithex.operators.orange-cm.base-url:https://api.orange.com/orange-money-webpay/cm/v1}") String baseUrl,
            @Value("${ebithex.operators.orange-cm.client-id:}") String clientId,
            @Value("${ebithex.operators.orange-cm.client-secret:}") String clientSecret,
            @Value("${ebithex.operators.orange-cm.merchant-key:}") String merchantKey,
            @Value("${ebithex.operators.orange-cm.notif-url:}") String notifUrl,
            @Value("${ebithex.operators.orange-cm.return-url:}") String returnUrl,
            @Value("${ebithex.operators.orange-cm.cancel-url:}") String cancelUrl) {
        this.webClient    = webClientBuilder.baseUrl(baseUrl).build();
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
        return OperatorType.ORANGE_MONEY_CM;
    }

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public OperatorInitResponse initiatePayment(OperatorPaymentRequest request) {
        String token = getOrRefreshToken();

        Map<String, Object> body = Map.of(
            "merchant_key",  merchantKey,
            "currency",      "XAF",
            "order_id",      request.ebithexReference(),
            "amount",        request.amount().intValue(),
            "return_url",    returnUrl,
            "cancel_url",    cancelUrl,
            "notif_url",     notifUrl,
            "lang",          "fr",
            "reference",     request.ebithexReference()
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                .uri("/webpayment")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(Map.class).block();

            if (response == null) return OperatorInitResponse.failed("Orange CM: empty response");

            String payToken   = (String) response.get("pay_token");
            String paymentUrl = (String) response.get("payment_url");
            String notifToken = (String) response.get("notif_token");

            log.info("Orange CM WebPay initiated — ebithexRef={} payToken={}", request.ebithexReference(), payToken);
            return OperatorInitResponse.redirect(notifToken != null ? notifToken : UUID.randomUUID().toString(),
                paymentUrl,
                "Redirect to Orange Money CM payment page");

        } catch (WebClientResponseException e) {
            log.error("Orange CM error {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(TOKEN_KEY);
            return OperatorInitResponse.failed("Orange CM rejected payment: " + e.getStatusCode());
        }
    }

    @Override
    public TransactionStatus checkStatus(String operatorReference) {
        String token = getOrRefreshToken();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                .uri("/webpayment/{ref}", operatorReference)
                .header("Authorization", "Bearer " + token)
                .retrieve().bodyToMono(Map.class).block();

            if (response == null) return TransactionStatus.PROCESSING;
            String status = (String) response.get("status");
            return mapOrangeStatus(status);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(TOKEN_KEY);
            return TransactionStatus.PROCESSING;
        }
    }

    private String getOrRefreshToken() {
        String cached = tokenCache.get(TOKEN_KEY);
        if (cached != null) return cached;

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type",    "client_credentials");
        form.add("client_id",     clientId);
        form.add("client_secret", clientSecret);

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = webClient.post()
            .uri("https://api.orange.com/oauth/v3/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .retrieve().bodyToMono(Map.class).block();

        if (resp == null) throw new IllegalStateException("Orange CM: empty token response");

        String token     = (String) resp.get("access_token");
        Number expiresIn = (Number)  resp.get("expires_in");
        tokenCache.put(TOKEN_KEY, token, expiresIn != null ? expiresIn.longValue() : 3600);
        return token;
    }

    private TransactionStatus mapOrangeStatus(String status) {
        if (status == null) return TransactionStatus.PROCESSING;
        return switch (status.toUpperCase()) {
            case "SUCCESS", "SUCCESSFULL" -> TransactionStatus.SUCCESS;
            case "FAILED", "CANCELLED"    -> TransactionStatus.FAILED;
            case "EXPIRED"                -> TransactionStatus.EXPIRED;
            default                       -> TransactionStatus.PROCESSING;
        };
    }
}