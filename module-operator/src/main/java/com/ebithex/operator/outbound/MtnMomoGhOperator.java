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
 * Adaptateur MTN Mobile Money — Ghana (Collection + Disbursement)
 *
 * API : MTN MoMo Developer Platform (sandbox.momodeveloper.mtn.com)
 * Indicatif : +233 | Monnaie : GHS
 */
@Component
@Slf4j
public class MtnMomoGhOperator implements MobileMoneyOperator {

    private static final String COLLECTION_TOKEN_KEY   = "mtn-momo-gh-collection-token";
    private static final String DISBURSEMENT_TOKEN_KEY = "mtn-momo-gh-disbursement-token";

    private final WebClient webClient;
    private final OperatorTokenCache tokenCache;
    private final String subscriptionKey;
    private final String apiUser;
    private final String apiKey;
    private final String environment;
    private final String callbackUrl;

    @Value("${ebithex.operators.mtn-gh.disbursement-api-user:${ebithex.operators.mtn-gh.api-user:}}")
    private String disbursementApiUser;

    @Value("${ebithex.operators.mtn-gh.disbursement-api-key:${ebithex.operators.mtn-gh.api-key:}}")
    private String disbursementApiKey;

    @Value("${ebithex.operators.mtn-gh.disbursement-subscription-key:${ebithex.operators.mtn-gh.subscription-key:}}")
    private String disbursementSubscriptionKey;

    @Value("${ebithex.operators.mtn-gh.disbursement-callback-url:}")
    private String disbursementCallbackUrl;

    public MtnMomoGhOperator(
            WebClient.Builder webClientBuilder,
            OperatorTokenCache tokenCache,
            @Value("${ebithex.operators.mtn-gh.base-url:https://sandbox.momodeveloper.mtn.com}") String baseUrl,
            @Value("${ebithex.operators.mtn-gh.subscription-key:}") String subscriptionKey,
            @Value("${ebithex.operators.mtn-gh.api-user:}") String apiUser,
            @Value("${ebithex.operators.mtn-gh.api-key:}") String apiKey,
            @Value("${ebithex.operators.mtn-gh.environment:sandbox}") String environment,
            @Value("${ebithex.operators.mtn-gh.callback-url:}") String callbackUrl) {
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
        return OperatorType.MTN_MOMO_GH;
    }

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public OperatorInitResponse initiatePayment(OperatorPaymentRequest request) {
        String referenceId = UUID.randomUUID().toString();
        String token       = getOrRefreshCollectionToken();
        String msisdn      = request.phoneNumber().replaceFirst("^\\+", "");

        Map<String, Object> body = Map.of(
            "amount",       request.amount().toPlainString(),
            "currency",     request.currency().name(),
            "externalId",   request.ebithexReference(),
            "payer",        Map.of("partyIdType", "MSISDN", "partyId", msisdn),
            "payerMessage", request.description() != null ? request.description() : "Payment Ebithex",
            "payeeNote",    request.ebithexReference()
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

            log.info("MTN GH requesttopay accepted — ebithexRef={} mtnRef={}", request.ebithexReference(), referenceId);
            return OperatorInitResponse.processing(referenceId,
                "*170*" + msisdn.substring(Math.max(0, msisdn.length() - 9)) + "#",
                "Approve the payment on your MTN MoMo Ghana phone");

        } catch (WebClientResponseException e) {
            log.error("MTN GH collection error {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(COLLECTION_TOKEN_KEY);
            return OperatorInitResponse.failed("MTN GH rejected payment: " + e.getStatusCode());
        }
    }

    @Override
    public TransactionStatus checkStatus(String operatorReference) {
        String token = getOrRefreshCollectionToken();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                .uri("/collection/v1_0/requesttopay/{ref}", operatorReference)
                .header("Authorization",             "Bearer " + token)
                .header("X-Target-Environment",      environment)
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .retrieve().bodyToMono(Map.class).block();

            return mapMtnStatus(response != null ? (String) response.get("status") : null);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(COLLECTION_TOKEN_KEY);
            return TransactionStatus.PROCESSING;
        }
    }

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public OperatorInitResponse initiateDisbursement(OperatorDisbursementRequest request) {
        String referenceId = UUID.randomUUID().toString();
        String token       = getOrRefreshDisbursementToken();
        String msisdn      = request.phoneNumber().replaceFirst("^\\+", "");

        Map<String, Object> body = Map.of(
            "amount",       request.amount().toPlainString(),
            "currency",     request.currency().name(),
            "externalId",   request.ebithexReference(),
            "payee",        Map.of("partyIdType", "MSISDN", "partyId", msisdn),
            "payerMessage", request.description() != null ? request.description() : "Payout Ebithex",
            "payeeNote",    request.ebithexReference()
        );

        try {
            webClient.post()
                .uri("/disbursement/v1_0/transfer")
                .header("Authorization",             "Bearer " + token)
                .header("X-Reference-Id",            referenceId)
                .header("X-Target-Environment",      environment)
                .header("Ocp-Apim-Subscription-Key", disbursementSubscriptionKey)
                .header("X-Callback-Url",            disbursementCallbackUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().toBodilessEntity().block();

            log.info("MTN GH transfer accepted — ebithexRef={} mtnRef={}", request.ebithexReference(), referenceId);
            return OperatorInitResponse.processing(referenceId, null,
                "Disbursement in progress — beneficiary will receive MTN GH notification");

        } catch (WebClientResponseException e) {
            log.error("MTN GH disbursement error {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(DISBURSEMENT_TOKEN_KEY);
            return OperatorInitResponse.failed("MTN GH rejected disbursement: " + e.getStatusCode());
        }
    }

    @Override
    public TransactionStatus checkDisbursementStatus(String operatorReference) {
        String token = getOrRefreshDisbursementToken();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.get()
                .uri("/disbursement/v1_0/transfer/{ref}", operatorReference)
                .header("Authorization",             "Bearer " + token)
                .header("X-Target-Environment",      environment)
                .header("Ocp-Apim-Subscription-Key", disbursementSubscriptionKey)
                .retrieve().bodyToMono(Map.class).block();

            return mapMtnStatus(response != null ? (String) response.get("status") : null);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(DISBURSEMENT_TOKEN_KEY);
            return TransactionStatus.PROCESSING;
        }
    }

    private String getOrRefreshCollectionToken() {
        return getOrRefreshToken(COLLECTION_TOKEN_KEY, apiUser, apiKey, "/collection/token/");
    }

    private String getOrRefreshDisbursementToken() {
        return getOrRefreshToken(DISBURSEMENT_TOKEN_KEY, disbursementApiUser, disbursementApiKey, "/disbursement/token/");
    }

    private String getOrRefreshToken(String cacheKey, String user, String key, String tokenUri) {
        String cached = tokenCache.get(cacheKey);
        if (cached != null) return cached;

        String credentials = Base64.getEncoder()
            .encodeToString((user + ":" + key).getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = webClient.post()
            .uri(tokenUri)
            .header(HttpHeaders.AUTHORIZATION,   "Basic " + credentials)
            .header("Ocp-Apim-Subscription-Key", subscriptionKey)
            .contentType(MediaType.APPLICATION_JSON)
            .retrieve().bodyToMono(Map.class).block();

        if (resp == null) throw new IllegalStateException("MTN GH: empty token response for " + tokenUri);

        String token     = (String) resp.get("access_token");
        Number expiresIn = (Number)  resp.get("expires_in");
        tokenCache.put(cacheKey, token, expiresIn != null ? expiresIn.longValue() : 3600);
        return token;
    }

    private TransactionStatus mapMtnStatus(String status) {
        if (status == null) return TransactionStatus.PROCESSING;
        return switch (status.toUpperCase()) {
            case "SUCCESSFUL" -> TransactionStatus.SUCCESS;
            case "FAILED"     -> TransactionStatus.FAILED;
            default           -> TransactionStatus.PROCESSING;
        };
    }
}
