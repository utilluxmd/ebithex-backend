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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Adaptateur M-Pesa — Kenya (STK Push + B2C Disbursement)
 *
 * API : Safaricom Daraja API (sandbox.safaricom.co.ke)
 * Indicatif : +254 | Monnaie : KES
 *
 * Flux collection : STK Push (Lipa Na M-Pesa Online)
 *  → POST /mpesa/stkpush/v1/processrequest
 *  → Prompt automatique affiché sur le téléphone du client
 *
 * Flux disbursement : B2C (Business to Customer)
 *  → POST /mpesa/b2c/v3/paymentrequest
 */
@Component
@Slf4j
public class MPesaKeOperator implements MobileMoneyOperator {

    private static final String TOKEN_KEY          = "mpesa-ke-token";
    private static final DateTimeFormatter TIMESTAMP_FMT =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final WebClient webClient;
    private final OperatorTokenCache tokenCache;
    private final String consumerKey;
    private final String consumerSecret;
    private final String businessShortCode;
    private final String passkey;
    private final String callbackUrl;
    private final String b2cInitiatorName;
    private final String b2cSecurityCredential;
    private final String b2cResultUrl;
    private final String b2cQueueTimeoutUrl;

    public MPesaKeOperator(
            WebClient.Builder webClientBuilder,
            OperatorTokenCache tokenCache,
            @Value("${ebithex.operators.mpesa-ke.base-url:https://sandbox.safaricom.co.ke}") String baseUrl,
            @Value("${ebithex.operators.mpesa-ke.consumer-key:}") String consumerKey,
            @Value("${ebithex.operators.mpesa-ke.consumer-secret:}") String consumerSecret,
            @Value("${ebithex.operators.mpesa-ke.business-short-code:174379}") String businessShortCode,
            @Value("${ebithex.operators.mpesa-ke.passkey:}") String passkey,
            @Value("${ebithex.operators.mpesa-ke.callback-url:}") String callbackUrl,
            @Value("${ebithex.operators.mpesa-ke.b2c-initiator-name:}") String b2cInitiatorName,
            @Value("${ebithex.operators.mpesa-ke.b2c-security-credential:}") String b2cSecurityCredential,
            @Value("${ebithex.operators.mpesa-ke.b2c-result-url:}") String b2cResultUrl,
            @Value("${ebithex.operators.mpesa-ke.b2c-queue-timeout-url:}") String b2cQueueTimeoutUrl) {
        this.webClient              = webClientBuilder.baseUrl(baseUrl).build();
        this.tokenCache             = tokenCache;
        this.consumerKey            = consumerKey;
        this.consumerSecret         = consumerSecret;
        this.businessShortCode      = businessShortCode;
        this.passkey                = passkey;
        this.callbackUrl            = callbackUrl;
        this.b2cInitiatorName       = b2cInitiatorName;
        this.b2cSecurityCredential  = b2cSecurityCredential;
        this.b2cResultUrl           = b2cResultUrl;
        this.b2cQueueTimeoutUrl     = b2cQueueTimeoutUrl;
    }

    @Override
    public OperatorType getOperatorType() {
        return OperatorType.MPESA_KE;
    }

    // ─── Collection (STK Push) ────────────────────────────────────────────────

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public OperatorInitResponse initiatePayment(OperatorPaymentRequest request) {
        String token     = getOrRefreshToken();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String password  = Base64.getEncoder().encodeToString(
            (businessShortCode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));

        // M-Pesa expects integer amount (no decimals for KES)
        String amount = request.amount().toBigInteger().toString();
        // Normalize phone: 254XXXXXXXXX (no +)
        String phone  = normalizeKenyanPhone(request.phoneNumber());

        Map<String, Object> body = new HashMap<>();
        body.put("BusinessShortCode", businessShortCode);
        body.put("Password",          password);
        body.put("Timestamp",         timestamp);
        body.put("TransactionType",   "CustomerPayBillOnline");
        body.put("Amount",            amount);
        body.put("PartyA",            phone);
        body.put("PartyB",            businessShortCode);
        body.put("PhoneNumber",       phone);
        body.put("CallBackURL",       callbackUrl);
        body.put("AccountReference",  request.ebithexReference().substring(0, Math.min(12, request.ebithexReference().length())));
        body.put("TransactionDesc",   request.description() != null ? request.description() : "Ebithex Payment");

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                .uri("/mpesa/stkpush/v1/processrequest")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(Map.class).block();

            String checkoutRequestId = response != null
                ? (String) response.get("CheckoutRequestID") : UUID.randomUUID().toString();

            log.info("M-Pesa KE STK Push sent — ebithexRef={} checkoutId={}",
                request.ebithexReference(), checkoutRequestId);
            return OperatorInitResponse.processing(checkoutRequestId, null,
                "STK Push sent to " + phone + " — please enter your M-Pesa PIN");

        } catch (WebClientResponseException e) {
            log.error("M-Pesa KE STK Push error {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(TOKEN_KEY);
            return OperatorInitResponse.failed("M-Pesa KE rejected payment: " + e.getStatusCode());
        }
    }

    /**
     * Query STK Push transaction status.
     * Uses /mpesa/stkpushquery/v1/query endpoint.
     */
    @Override
    public TransactionStatus checkStatus(String operatorReference) {
        String token     = getOrRefreshToken();
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String password  = Base64.getEncoder().encodeToString(
            (businessShortCode + passkey + timestamp).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> body = Map.of(
            "BusinessShortCode", businessShortCode,
            "Password",          password,
            "Timestamp",         timestamp,
            "CheckoutRequestID", operatorReference
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                .uri("/mpesa/stkpushquery/v1/query")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(Map.class).block();

            return mapMpesaStatus(response);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(TOKEN_KEY);
            return TransactionStatus.PROCESSING;
        }
    }

    // ─── Disbursement (B2C) ───────────────────────────────────────────────────

    @Override
    @Retryable(retryFor = WebClientResponseException.InternalServerError.class,
               maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public OperatorInitResponse initiateDisbursement(OperatorDisbursementRequest request) {
        String token  = getOrRefreshToken();
        String phone  = normalizeKenyanPhone(request.phoneNumber());
        String amount = request.amount().toBigInteger().toString();

        Map<String, Object> body = Map.of(
            "InitiatorName",      b2cInitiatorName,
            "SecurityCredential", b2cSecurityCredential,
            "CommandID",          "BusinessPayment",
            "Amount",             amount,
            "PartyA",             businessShortCode,
            "PartyB",             phone,
            "Remarks",            request.description() != null ? request.description() : "Ebithex Payout",
            "QueueTimeOutURL",    b2cQueueTimeoutUrl,
            "ResultURL",          b2cResultUrl,
            "Occasion",           request.ebithexReference()
        );

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = webClient.post()
                .uri("/mpesa/b2c/v3/paymentrequest")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve().bodyToMono(Map.class).block();

            String conversationId = response != null
                ? (String) response.get("ConversationID") : UUID.randomUUID().toString();

            log.info("M-Pesa KE B2C initiated — ebithexRef={} conversationId={}",
                request.ebithexReference(), conversationId);
            return OperatorInitResponse.processing(conversationId, null,
                "B2C disbursement initiated — beneficiary will receive M-Pesa notification");

        } catch (WebClientResponseException e) {
            log.error("M-Pesa KE B2C error {} — {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) tokenCache.invalidate(TOKEN_KEY);
            return OperatorInitResponse.failed("M-Pesa KE rejected disbursement: " + e.getStatusCode());
        }
    }

    // ─── OAuth2 Token ─────────────────────────────────────────────────────────

    private String getOrRefreshToken() {
        String cached = tokenCache.get(TOKEN_KEY);
        if (cached != null) return cached;

        String credentials = Base64.getEncoder()
            .encodeToString((consumerKey + ":" + consumerSecret).getBytes(StandardCharsets.UTF_8));

        @SuppressWarnings("unchecked")
        Map<String, Object> resp = webClient.get()
            .uri("/oauth/v1/generate?grant_type=client_credentials")
            .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
            .retrieve().bodyToMono(Map.class).block();

        if (resp == null) throw new IllegalStateException("M-Pesa KE: empty token response");

        String token     = (String) resp.get("access_token");
        Number expiresIn = (Number)  resp.get("expires_in");
        tokenCache.put(TOKEN_KEY, token, expiresIn != null ? expiresIn.longValue() : 3600);
        return token;
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String normalizeKenyanPhone(String phone) {
        // Ensure format: 254XXXXXXXXX (12 digits total)
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.startsWith("0")) {
            digits = "254" + digits.substring(1);
        } else if (!digits.startsWith("254")) {
            digits = "254" + digits;
        }
        return digits;
    }

    private TransactionStatus mapMpesaStatus(Map<String, Object> response) {
        if (response == null) return TransactionStatus.PROCESSING;
        Object resultCode = response.get("ResultCode");
        if (resultCode == null) {
            // Check ResponseCode from STK query
            Object responseCode = response.get("ResponseCode");
            if ("0".equals(String.valueOf(responseCode))) return TransactionStatus.PROCESSING;
            return TransactionStatus.PROCESSING;
        }
        return switch (String.valueOf(resultCode)) {
            case "0"    -> TransactionStatus.SUCCESS;
            case "1032" -> TransactionStatus.FAILED;  // Cancelled by user
            case "1037" -> TransactionStatus.FAILED;  // Timeout
            default     -> TransactionStatus.PROCESSING;
        };
    }
}