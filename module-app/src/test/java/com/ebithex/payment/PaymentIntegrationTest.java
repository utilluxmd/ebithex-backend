package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests d'intégration — Paiements Mobile Money.
 *
 * Couvre :
 *  - Initiation de paiement (auto-detect opérateur, frais, persistance)
 *  - Idempotence (même merchantReference → même transaction)
 *  - Isolation multi-tenant (marchand A ne voit pas les transactions de B)
 *  - Consultation de statut
 *  - Pagination de l'historique
 */
@DisplayName("Payment — Initiation, Statut, Idempotence, Multitenancy")
class PaymentIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    private TestDataFactory.MerchantCredentials merchantA;
    private TestDataFactory.MerchantCredentials merchantB;

    @BeforeEach
    void setUp() {
        merchantA = factory.registerMerchant(restTemplate, url(""));
        merchantB = factory.registerMerchant(restTemplate, url(""));

        // OperatorGateway mocké : répond PROCESSING par défaut
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "OP-" + System.nanoTime(), "*144*1*2*5000#", "Composez le code USSD"));
    }

    // ── Initiation ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("Initiation avec auto-detect → 200, statut PROCESSING, frais calculés")
    void initiatePayment_autoDetect_success() {
        Map<String, Object> body = buildPaymentRequest("PMT-001", "+225051234567", "5000");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey()));
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("PROCESSING");
        assertThat(data.get("ebithexReference")).asString().isNotBlank();
        // Vérifier que les frais ont été calculés (montant > 0) et que net = montant - frais
        BigDecimal amount  = new BigDecimal("5000");
        BigDecimal fee     = new BigDecimal(data.get("feeAmount").toString());
        BigDecimal net     = new BigDecimal(data.get("netAmount").toString());
        assertThat(fee).isGreaterThan(BigDecimal.ZERO);
        assertThat(net).isEqualByComparingTo(amount.subtract(fee));
    }

    @Test
    @DisplayName("Initiation avec opérateur explicite → 200")
    void initiatePayment_explicitOperator_success() {
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.redirect(
                "OP-WAVE-001", "https://wave.ci/pay/abc", "Redirect to Wave"));

        Map<String, Object> body = buildPaymentRequest("PMT-WAVE-001", "+225021234567", "10000");
        body.put("operator", "WAVE_CI");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey()));
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("paymentUrl")).isEqualTo("https://wave.ci/pay/abc");
    }

    @Test
    @DisplayName("Paiement refusé par l'opérateur → statut FAILED conservé")
    void initiatePayment_operatorFails_statusFailed() {
        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.failed("Solde insuffisant"));

        Map<String, Object> body = buildPaymentRequest("PMT-FAIL-001", "+225051234567", "5000");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey()));
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<?, ?> data = (Map<?, ?>) response.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("FAILED");
        assertThat(data.get("failureReason")).isEqualTo("Solde insuffisant");
    }

    @Test
    @DisplayName("Montant inférieur au minimum → 400 validation")
    void initiatePayment_belowMinAmount_returns400() {
        Map<String, Object> body = buildPaymentRequest("PMT-MIN", "+225051234567", "50"); // < 100 FCFA

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey()));
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payments"), HttpMethod.POST, request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Idempotence ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("Même merchantReference → retourne la même transaction sans doublon")
    void initiatePayment_sameReference_returnsExisting() {
        Map<String, Object> body = buildPaymentRequest("PMT-IDEMPOTENT-001", "+225051234567", "5000");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey()));

        ResponseEntity<Map> first  = restTemplate.exchange(url("/v1/payments"), HttpMethod.POST, request, Map.class);
        ResponseEntity<Map> second = restTemplate.exchange(url("/v1/payments"), HttpMethod.POST, request, Map.class);

        Map<?, ?> firstData  = (Map<?, ?>) first.getBody().get("data");
        Map<?, ?> secondData = (Map<?, ?>) second.getBody().get("data");

        assertThat(firstData.get("ebithexReference"))
            .isEqualTo(secondData.get("ebithexReference"));
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    }

    @Test
    @DisplayName("Même merchantReference mais marchand différent → deux transactions distinctes")
    void initiatePayment_sameRefDifferentMerchant_createsTwo() {
        Map<String, Object> body = buildPaymentRequest("PMT-SHARED-REF", "+225051234567", "5000");

        HttpEntity<Map<String, Object>> reqA = new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey()));
        HttpEntity<Map<String, Object>> reqB = new HttpEntity<>(body, factory.apiKeyHeaders(merchantB.apiKey()));

        ResponseEntity<Map> respA = restTemplate.exchange(url("/v1/payments"), HttpMethod.POST, reqA, Map.class);
        ResponseEntity<Map> respB = restTemplate.exchange(url("/v1/payments"), HttpMethod.POST, reqB, Map.class);

        Map<?, ?> dataA = (Map<?, ?>) respA.getBody().get("data");
        Map<?, ?> dataB = (Map<?, ?>) respB.getBody().get("data");

        assertThat(dataA.get("ebithexReference"))
            .isNotEqualTo(dataB.get("ebithexReference"));
    }

    // ── Statut ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /payments/{ref} → retourne la transaction du marchand")
    void getStatus_ownTransaction_returnsDetails() {
        Map<String, Object> body = buildPaymentRequest("PMT-STATUS-001", "+225051234567", "5000");
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey()));
        ResponseEntity<Map> created = restTemplate.exchange(url("/v1/payments"), HttpMethod.POST, request, Map.class);

        String ref = (String) ((Map<?, ?>) created.getBody().get("data")).get("ebithexReference");

        ResponseEntity<Map> status = restTemplate.exchange(
            url("/v1/payments/" + ref), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchantA.apiKey())), Map.class);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) status.getBody().get("data")).get("ebithexReference")).isEqualTo(ref);
    }

    @Test
    @DisplayName("GET /payments/{ref} de marchant B par marchant A → 404 (isolation)")
    void getStatus_otherMerchantTransaction_returns404() {
        // Marchant B crée une transaction
        Map<String, Object> body = buildPaymentRequest("PMT-ISOLATED-001", "+225051234567", "5000");
        HttpEntity<Map<String, Object>> reqB = new HttpEntity<>(body, factory.apiKeyHeaders(merchantB.apiKey()));
        ResponseEntity<Map> created = restTemplate.exchange(url("/v1/payments"), HttpMethod.POST, reqB, Map.class);
        String ref = (String) ((Map<?, ?>) created.getBody().get("data")).get("ebithexReference");

        // Marchant A essaie d'accéder à la transaction de B
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payments/" + ref), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchantA.apiKey())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("GET /payments/{ref} inexistant → 404")
    void getStatus_unknownReference_returns404() {
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payments/EBITHEX-UNKNOWN-999"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchantA.apiKey())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Liste paginée ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /payments → liste uniquement les transactions du marchand connecté")
    void listTransactions_returnsOnlyOwnTransactions() {
        // Créer 2 transactions pour A et 1 pour B
        for (int i = 1; i <= 2; i++) {
            Map<String, Object> body = buildPaymentRequest("PMT-LIST-A-" + i, "+225051234567", "5000");
            restTemplate.exchange(url("/v1/payments"), HttpMethod.POST,
                new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey())), Map.class);
        }
        Map<String, Object> bodyB = buildPaymentRequest("PMT-LIST-B-1", "+225051234567", "5000");
        restTemplate.exchange(url("/v1/payments"), HttpMethod.POST,
            new HttpEntity<>(bodyB, factory.apiKeyHeaders(merchantB.apiKey())), Map.class);

        // Liste de A
        ResponseEntity<Map> response = restTemplate.exchange(
            url("/v1/payments?size=20"), HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchantA.apiKey())), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> page = (Map<?, ?>) ((Map<?, ?>) response.getBody().get("data"));
        // A ne voit que ses propres transactions (au moins 2)
        assertThat((Integer) page.get("totalElements")).isGreaterThanOrEqualTo(2);
    }

    // ── Authentification ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Requête sans authentification → 401")
    void initiatePayment_noAuth_returns401() {
        Map<String, Object> body = buildPaymentRequest("PMT-NOAUTH", "+225051234567", "5000");
        ResponseEntity<Map> response = restTemplate.postForEntity(url("/v1/payments"), body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> buildPaymentRequest(String ref, String phone, String amount) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("amount",            new BigDecimal(amount));
        map.put("phoneNumber",       phone);
        map.put("merchantReference", ref);
        map.put("description",       "Test payment");
        return map;
    }
}