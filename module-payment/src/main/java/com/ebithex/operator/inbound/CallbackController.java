package com.ebithex.operator.inbound;

import com.ebithex.payment.application.PaymentService;
import com.ebithex.payout.application.PayoutService;
import com.ebithex.shared.domain.TransactionStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Anti-Corruption Layer : reçoit les callbacks bruts des opérateurs Mobile Money.
 *
 * Chaque opérateur-pays a son propre endpoint pour isoler les HMAC secrets.
 * La logique de traitement est identique pour tous : verify → deduplicate → delegate.
 *
 * Sécurité en 3 couches :
 *  1. Vérification HMAC-SHA256 de la signature opérateur
 *  2. Déduplication (operator_processed_callbacks) — idempotent par design
 *  3. Traduction en langage domaine avant délégation à PaymentService/PayoutService
 */
@RestController
@RequestMapping("/v1/callbacks")
@Slf4j
@RequiredArgsConstructor
public class CallbackController {

    private final PaymentService paymentService;
    private final PayoutService payoutService;
    private final CallbackAuthService callbackAuthService;

    // ── MTN CI ────────────────────────────────────────────────────────────────
    @Value("${ebithex.operators.mtn-ci.callback-secret:}")
    private String mtnCiSecret;

    // ── MTN BJ ────────────────────────────────────────────────────────────────
    @Value("${ebithex.operators.mtn-bj.callback-secret:}")
    private String mtnBjSecret;

    // ── Orange CI ─────────────────────────────────────────────────────────────
    @Value("${ebithex.operators.orange-ci.callback-secret:}")
    private String orangeCiSecret;

    // ── Orange SN ─────────────────────────────────────────────────────────────
    @Value("${ebithex.operators.orange-sn.callback-secret:}")
    private String orangeSnSecret;

    // ── Wave (CI + SN, même secret) ───────────────────────────────────────────
    @Value("${ebithex.operators.wave.callback-secret:}")
    private String waveSecret;

    // ── MTN CI Disbursement ───────────────────────────────────────────────────
    @Value("${ebithex.operators.mtn-ci.callback-secret:}")
    private String mtnCiDisbursementSecret;

    // ─── MTN CI — Collection ─────────────────────────────────────────────────

    @PostMapping("/mtn-ci")
    public ResponseEntity<Void> mtnCiCallback(
            HttpServletRequest request,
            @RequestHeader(value = "X-Callback-Auth", required = false) String signature)
            throws IOException {

        String rawBody = readBody(request);
        log.info("MTN CI callback — {} bytes", rawBody.length());

        if (!callbackAuthService.verifySignature("mtn-ci", rawBody, signature, mtnCiSecret)) {
            log.warn("MTN CI callback rejeté — signature invalide");
            return ResponseEntity.status(401).build();
        }

        Map<String, Object> body = parseJson(rawBody);
        String referenceId = (String) body.get("referenceId");
        String status      = (String) body.get("status");
        if (referenceId == null || status == null) return ResponseEntity.badRequest().build();

        if (!callbackAuthService.markAsProcessed("mtn-ci", referenceId)) return ResponseEntity.ok().build();

        TransactionStatus mapped = mapMtnStatus(status);
        if (mapped != null) paymentService.processCallback(referenceId, mapped);
        return ResponseEntity.ok().build();
    }

    // ─── MTN BJ — Collection ─────────────────────────────────────────────────

    @PostMapping("/mtn-bj")
    public ResponseEntity<Void> mtnBjCallback(
            HttpServletRequest request,
            @RequestHeader(value = "X-Callback-Auth", required = false) String signature)
            throws IOException {

        String rawBody = readBody(request);
        log.info("MTN BJ callback — {} bytes", rawBody.length());

        if (!callbackAuthService.verifySignature("mtn-bj", rawBody, signature, mtnBjSecret)) {
            log.warn("MTN BJ callback rejeté — signature invalide");
            return ResponseEntity.status(401).build();
        }

        Map<String, Object> body = parseJson(rawBody);
        String referenceId = (String) body.get("referenceId");
        String status      = (String) body.get("status");
        if (referenceId == null || status == null) return ResponseEntity.badRequest().build();

        if (!callbackAuthService.markAsProcessed("mtn-bj", referenceId)) return ResponseEntity.ok().build();

        TransactionStatus mapped = mapMtnStatus(status);
        if (mapped != null) paymentService.processCallback(referenceId, mapped);
        return ResponseEntity.ok().build();
    }

    // ─── MTN CI — Disbursement ────────────────────────────────────────────────

    @PostMapping("/mtn-ci-disbursement")
    public ResponseEntity<Void> mtnCiDisbursementCallback(
            HttpServletRequest request,
            @RequestHeader(value = "X-Callback-Auth", required = false) String signature)
            throws IOException {

        String rawBody = readBody(request);
        log.info("MTN CI disbursement callback — {} bytes", rawBody.length());

        if (!callbackAuthService.verifySignature("mtn-ci-disbursement", rawBody, signature, mtnCiDisbursementSecret)) {
            log.warn("MTN CI disbursement callback rejeté — signature invalide");
            return ResponseEntity.status(401).build();
        }

        Map<String, Object> body = parseJson(rawBody);
        String referenceId = (String) body.get("referenceId");
        String status      = (String) body.get("status");
        if (referenceId == null || status == null) return ResponseEntity.badRequest().build();

        if (!callbackAuthService.markAsProcessed("mtn-ci-disbursement", referenceId)) return ResponseEntity.ok().build();

        TransactionStatus mapped = mapMtnStatus(status);
        if (mapped != null) payoutService.processCallback(referenceId, mapped);
        return ResponseEntity.ok().build();
    }

    // ─── Orange CI ────────────────────────────────────────────────────────────

    @PostMapping("/orange-ci")
    public ResponseEntity<Void> orangeCiCallback(
            HttpServletRequest request,
            @RequestHeader(value = "X-Orange-Signature", required = false) String signature)
            throws IOException {

        String rawBody = readBody(request);
        log.info("Orange CI callback — {} bytes", rawBody.length());

        if (!callbackAuthService.verifySignature("orange-ci", rawBody, signature, orangeCiSecret)) {
            log.warn("Orange CI callback rejeté — signature invalide");
            return ResponseEntity.status(401).build();
        }

        Map<String, Object> body = parseJson(rawBody);
        String payToken = (String) body.get("pay_token");
        String status   = (String) body.get("status");
        if (payToken == null) return ResponseEntity.badRequest().build();

        if (!callbackAuthService.markAsProcessed("orange-ci", payToken)) return ResponseEntity.ok().build();

        TransactionStatus mapped = mapOrangeStatus(status);
        if (mapped != null) paymentService.processCallback(payToken, mapped);
        return ResponseEntity.ok().build();
    }

    // ─── Orange SN ────────────────────────────────────────────────────────────

    @PostMapping("/orange-sn")
    public ResponseEntity<Void> orangeSnCallback(
            HttpServletRequest request,
            @RequestHeader(value = "X-Orange-Signature", required = false) String signature)
            throws IOException {

        String rawBody = readBody(request);
        log.info("Orange SN callback — {} bytes", rawBody.length());

        if (!callbackAuthService.verifySignature("orange-sn", rawBody, signature, orangeSnSecret)) {
            log.warn("Orange SN callback rejeté — signature invalide");
            return ResponseEntity.status(401).build();
        }

        Map<String, Object> body = parseJson(rawBody);
        String payToken = (String) body.get("pay_token");
        String status   = (String) body.get("status");
        if (payToken == null) return ResponseEntity.badRequest().build();

        if (!callbackAuthService.markAsProcessed("orange-sn", payToken)) return ResponseEntity.ok().build();

        TransactionStatus mapped = mapOrangeStatus(status);
        if (mapped != null) paymentService.processCallback(payToken, mapped);
        return ResponseEntity.ok().build();
    }

    // ─── Wave (CI + SN) ───────────────────────────────────────────────────────

    @PostMapping("/wave")
    public ResponseEntity<Void> waveCallback(
            HttpServletRequest request,
            @RequestHeader(value = "X-Wave-Signature", required = false) String signature)
            throws IOException {

        String rawBody = readBody(request);
        log.info("Wave callback — {} bytes", rawBody.length());

        if (!callbackAuthService.verifySignature("wave", rawBody, signature, waveSecret)) {
            log.warn("Wave callback rejeté — signature invalide");
            return ResponseEntity.status(401).build();
        }

        Map<String, Object> body = parseJson(rawBody);
        String clientReference = (String) body.get("client_reference");
        String paymentStatus   = (String) body.get("payment_status");
        if (clientReference == null) return ResponseEntity.badRequest().build();

        if (!callbackAuthService.markAsProcessed("wave", clientReference)) return ResponseEntity.ok().build();

        TransactionStatus mapped = mapWaveStatus(paymentStatus);
        if (mapped != null) paymentService.processCallback(clientReference, mapped);
        return ResponseEntity.ok().build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String readBody(HttpServletRequest request) throws IOException {
        return StreamUtils.copyToString(request.getInputStream(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("Callback payload non-JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private TransactionStatus mapMtnStatus(String s) {
        if (s == null) return null;
        return switch (s.toUpperCase()) {
            case "SUCCESSFUL" -> TransactionStatus.SUCCESS;
            case "FAILED"     -> TransactionStatus.FAILED;
            default           -> null;
        };
    }

    private TransactionStatus mapOrangeStatus(String s) {
        if (s == null) return null;
        return switch (s.toUpperCase()) {
            case "SUCCESS"             -> TransactionStatus.SUCCESS;
            case "FAILED", "CANCELLED" -> TransactionStatus.FAILED;
            case "EXPIRED"             -> TransactionStatus.EXPIRED;
            default                    -> null;
        };
    }

    private TransactionStatus mapWaveStatus(String s) {
        if (s == null) return null;
        return switch (s.toLowerCase()) {
            case "succeeded" -> TransactionStatus.SUCCESS;
            case "cancelled" -> TransactionStatus.FAILED;
            default          -> null;
        };
    }
}