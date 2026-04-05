package com.ebithex;

import com.ebithex.operator.outbound.MobileMoneyOperator;
import com.ebithex.operator.outbound.OperatorRegistry;
import com.ebithex.payment.application.OperatorGateway;
import com.ebithex.shared.sandbox.SandboxContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

/**
 * Classe de base pour tous les tests d'intégration Ebithex.
 *
 * Infrastructure fournie :
 *  - PostgreSQL 16 (Testcontainers) avec migrations Flyway
 *  - Redis 7   (Testcontainers) pour la blacklist JWT et le rate limiting
 *  - OperatorGateway mocké  → aucun appel réel aux APIs Mobile Money
 *  - JavaMailSender mocké   → aucun email envoyé
 *  - MockMvc disponible pour les endpoints back-office (injection principal admin)
 *  - TestRestTemplate disponible pour les appels HTTP réels (APIs marchands)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = EbithexApplication.class)
@ActiveProfiles("test")
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    // ── Testcontainers — singleton partagé sur toute la suite (même JVM) ──────
    // Pattern singleton : démarrage unique via static initializer, jamais arrêté
    // entre classes de test → évite le problème de port changeant avec le cache
    // de contexte Spring.

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine");

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
        new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host",     redis::getHost);
        registry.add("spring.data.redis.port",     () -> redis.getMappedPort(6379));
    }

    // ── Mocks — aucun appel réel aux opérateurs ou à la messagerie ──────────

    @MockBean
    protected OperatorGateway operatorGateway;

    @MockBean
    protected OperatorRegistry operatorRegistry;

    /**
     * Stub MobileMoneyOperator returned by operatorRegistry.get(any()).
     * Default: supportsReversal() = false (safe — no reversal attempted unless test overrides).
     * Override in subclass @BeforeEach: when(stubOperator.supportsReversal()).thenReturn(true)
     */
    protected MobileMoneyOperator stubOperator;

    @MockBean
    protected JavaMailSender mailSender;

    // ── HTTP helpers ─────────────────────────────────────────────────────────

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void configureMockMvc() {
        // Guarantee prod schema by default — individual tests can override via SandboxContextHolder.set(true)
        SandboxContextHolder.set(false);

        // All operator types are considered supported in tests — no real adapters are loaded.
        // PaymentService checks operatorRegistry.supports() before calling the (mocked) OperatorGateway.
        stubOperator = mock(MobileMoneyOperator.class);
        when(operatorRegistry.supports(any())).thenReturn(true);
        // Default: supportsReversal() = false (Mockito boolean default).
        // Prevents NPE in PaymentService.refundPayment() — tests that need reversal must override.
        when(operatorRegistry.get(any())).thenReturn(stubOperator);

        // Purge toutes les tables transactionnelles pour l'isolation entre tests.
        // CASCADE gère les dépendances FK sans avoir à spécifier l'ordre.
        // RESTART IDENTITY omis : les colonnes PK utilisent GenerationType.UUID,
        // les séquences ne s'appliquent pas.
        jdbcTemplate.execute("""
            TRUNCATE TABLE
                audit_logs,
                aml_alerts,
                outbox_events,
                webhook_deliveries,
                webhook_endpoints,
                bulk_payout_items,
                bulk_payouts,
                wallet_transactions,
                withdrawal_requests,
                wallets,
                transactions,
                payouts,
                kyc_documents,
                api_keys,
                merchants
            CASCADE
            """);

        // Restore global fee rules wiped by TRUNCATE merchants CASCADE.
        // fee_rules.merchant_id has an FK to merchants, so CASCADE truncates the whole table.
        // Global rules (merchant_id = NULL) must be re-inserted to avoid FEE_RULE_NOT_FOUND (400).
        jdbcTemplate.execute("""
            INSERT INTO fee_rules (name, description, operator, fee_type, percentage_rate, priority) VALUES
                ('MTN MoMo CI — Taux par défaut',
                 'Taux appliqué à tous les paiements MTN MoMo — Côte d''Ivoire',
                 'MTN_MOMO_CI',    'PERCENTAGE', 1.50, 0),
                ('Orange Money CI — Taux par défaut',
                 'Taux appliqué à tous les paiements Orange Money — Côte d''Ivoire',
                 'ORANGE_MONEY_CI', 'PERCENTAGE', 1.75, 0),
                ('Wave CI — Taux par défaut',
                 'Taux appliqué à tous les paiements Wave',
                 'WAVE_CI',         'PERCENTAGE', 1.00, 0),
                ('Taux global par défaut',
                 'Taux de repli si aucune règle spécifique ne correspond',
                 NULL,              'PERCENTAGE', 1.50, -10)
            """);

        // Vider Redis entre chaque test : évite l'accumulation des compteurs de rate limiting
        // et des tokens blacklistés issus d'autres classes de test.
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        mockMvc = MockMvcBuilders.webAppContextSetup(wac)
            .apply(springSecurity())
            .defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/").with(request -> { request.setContextPath("/api"); return request; }))
            .alwaysDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
            .build();
    }

    /**
     * Construit l'URL complète pour un chemin relatif (inclut le context-path /api).
     */
    protected String url(String path) {
        return "http://localhost:" + port + "/api" + path;
    }
}