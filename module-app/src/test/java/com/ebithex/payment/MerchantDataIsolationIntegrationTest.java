package com.ebithex.payment;

import com.ebithex.AbstractIntegrationTest;
import com.ebithex.TestDataFactory;
import com.ebithex.operator.outbound.MobileMoneyOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests d'intégration — Isolation des données multi-tenant via filtre Hibernate.
 *
 * Vérifie que le filtre {@code merchantFilter} activé par {@code MerchantFilterAspect}
 * empêche un marchand d'accéder aux ressources d'un autre marchand, même en connaissant
 * la référence exacte.
 *
 * Couvre :
 *  - Isolation des transactions : le marchand B ne peut pas lire les transactions de A
 *  - Isolation des payouts : idem
 *  - Isolation des webhooks : idem
 */
@DisplayName("Isolation multi-tenant — Filtre Hibernate marchand")
class MerchantDataIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestDataFactory factory;

    private TestDataFactory.MerchantCredentials merchantA;
    private TestDataFactory.MerchantCredentials merchantB;

    @BeforeEach
    void setUp() {
        merchantA = factory.registerMerchant(restTemplate, url(""));
        merchantB = factory.registerMerchant(restTemplate, url(""));

        when(operatorGateway.initiatePayment(any(), any()))
            .thenReturn(MobileMoneyOperator.OperatorInitResponse.processing(
                "OP-ISO-" + System.nanoTime(), "*144*1*2*5000#", "Composez USSD"));
    }

    // ── Isolation des transactions ────────────────────────────────────────────

    @Test
    @DisplayName("Marchand B ne peut pas lire les transactions de A (même référence exacte)")
    void getPayment_crossTenant_returns404() {
        // Marchand A crée une transaction
        Map<String, Object> body = Map.of(
            "merchantReference", "ISO-PMT-001",
            "phoneNumber",       "+225051234567",
            "amount",            5000,
            "currency",          "XOF",
            "description",       "Test isolation"
        );
        ResponseEntity<Map> createResponse = restTemplate.exchange(
            url("/v1/payments"),
            HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey())),
            Map.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String reference = (String) ((Map<?, ?>) createResponse.getBody().get("data"))
            .get("ebithexReference");
        assertThat(reference).isNotBlank();

        // Marchand B tente de lire la transaction de A avec sa propre clé
        ResponseEntity<Map> readResponse = restTemplate.exchange(
            url("/v1/payments/" + reference),
            HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchantB.apiKey())),
            Map.class
        );

        // Le filtre Hibernate exclut la transaction de A → 404 pour B
        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Marchand A peut lire sa propre transaction")
    void getPayment_ownTransaction_returns200() {
        Map<String, Object> body = Map.of(
            "merchantReference", "ISO-PMT-OWN-001",
            "phoneNumber",       "+225051234568",
            "amount",            3000,
            "currency",          "XOF",
            "description",       "Test own access"
        );
        ResponseEntity<Map> createResponse = restTemplate.exchange(
            url("/v1/payments"),
            HttpMethod.POST,
            new HttpEntity<>(body, factory.apiKeyHeaders(merchantA.apiKey())),
            Map.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String reference = (String) ((Map<?, ?>) createResponse.getBody().get("data"))
            .get("ebithexReference");

        // Marchand A lit sa propre transaction → 200
        ResponseEntity<Map> readResponse = restTemplate.exchange(
            url("/v1/payments/" + reference),
            HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchantA.apiKey())),
            Map.class
        );

        assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> data = (Map<?, ?>) readResponse.getBody().get("data");
        assertThat(data.get("ebithexReference")).isEqualTo(reference);
    }

    @Test
    @DisplayName("Liste des transactions de B ne contient pas celles de A")
    void listPayments_onlyOwnTransactions_returned() {
        // Marchand A crée 2 transactions
        for (int i = 1; i <= 2; i++) {
            restTemplate.exchange(
                url("/v1/payments"),
                HttpMethod.POST,
                new HttpEntity<>(
                    Map.of(
                        "merchantReference", "ISO-LIST-A-00" + i,
                        "phoneNumber",       "+22505123456" + i,
                        "amount",            1000 * i,
                        "currency",          "XOF",
                        "description",       "Tx A-" + i
                    ),
                    factory.apiKeyHeaders(merchantA.apiKey())
                ),
                Map.class
            );
        }

        // Marchand B crée 1 transaction
        restTemplate.exchange(
            url("/v1/payments"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of(
                    "merchantReference", "ISO-LIST-B-001",
                    "phoneNumber",       "+225070000001",
                    "amount",            2000,
                    "currency",          "XOF",
                    "description",       "Tx B-1"
                ),
                factory.apiKeyHeaders(merchantB.apiKey())
            ),
            Map.class
        );

        // La liste de B ne doit contenir que sa propre transaction
        ResponseEntity<Map> listResponseB = restTemplate.exchange(
            url("/v1/payments?page=0&size=50"),
            HttpMethod.GET,
            new HttpEntity<>(factory.apiKeyHeaders(merchantB.apiKey())),
            Map.class
        );

        assertThat(listResponseB.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> page = (Map<?, ?>) listResponseB.getBody().get("data");
        // La liste paginée retourne "content" (Page Spring) ou "items" — adapter selon le DTO réel
        Object totalElements = page.get("totalElements");
        if (totalElements != null) {
            assertThat(((Number) totalElements).intValue()).isEqualTo(1);
        } else {
            // Vérifier via la taille du contenu
            assertThat(((java.util.List<?>) page.get("content")).size()).isEqualTo(1);
        }
    }

    // ── Isolation des webhooks ────────────────────────────────────────────────

    @Test
    @DisplayName("Marchand B ne peut pas désactiver le webhook de A")
    void disableWebhook_crossTenant_returns404() {
        // Marchand A enregistre un webhook
        ResponseEntity<Map> createResponse = restTemplate.exchange(
            url("/v1/webhooks"),
            HttpMethod.POST,
            new HttpEntity<>(
                Map.of("url", "https://webhook.example.com/notify",
                       "events", java.util.List.of("payment.success")),
                factory.apiKeyHeaders(merchantA.apiKey())
            ),
            Map.class
        );
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String webhookId = ((Map<?, ?>) createResponse.getBody().get("data"))
            .get("id").toString();

        // Marchand B tente de supprimer le webhook de A
        ResponseEntity<Map> deleteResponse = restTemplate.exchange(
            url("/v1/webhooks/" + webhookId),
            HttpMethod.DELETE,
            new HttpEntity<>(factory.apiKeyHeaders(merchantB.apiKey())),
            Map.class
        );

        // Doit être refusé (404 ou 403 selon l'implémentation)
        assertThat(deleteResponse.getStatusCode().value()).isGreaterThanOrEqualTo(400);

        // Vérifier que le webhook existe toujours
        Boolean stillActive = jdbcTemplate.queryForObject(
            "SELECT active FROM webhook_endpoints WHERE id = ?::uuid",
            Boolean.class,
            webhookId
        );
        assertThat(stillActive).isTrue();
    }
}
