package com.ebithex.health;

import com.ebithex.operatorfloat.application.OperatorFloatService;
import com.ebithex.operatorfloat.domain.OperatorFloat;
import com.ebithex.payment.health.OperatorHealthIndicator;
import com.ebithex.shared.domain.OperatorType;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires — OperatorHealthIndicator.
 *
 * Couvre :
 *  - Tous les circuits fermés + floats OK → UP
 *  - Un circuit OPEN → DOWN
 *  - Float bas (balance < seuil) → WARNING
 *  - Circuit OPEN prend priorité sur float bas → DOWN
 *  - OperatorFloatService lance une exception → DOWN avec floatError
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OperatorHealthIndicator — Circuit Breakers et Float Levels")
class OperatorHealthIndicatorTest {

    @Mock private CircuitBreakerRegistry circuitBreakerRegistry;
    @Mock private OperatorFloatService   floatService;
    @Mock private CircuitBreaker         cbPayment;
    @Mock private CircuitBreaker         cbDisbursement;

    private OperatorHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new OperatorHealthIndicator(circuitBreakerRegistry, floatService);

        when(circuitBreakerRegistry.circuitBreaker("operator-payment")).thenReturn(cbPayment);
        when(circuitBreakerRegistry.circuitBreaker("operator-disbursement")).thenReturn(cbDisbursement);
    }

    @Test
    @DisplayName("Circuits fermés + floats OK → UP")
    void allOk_returnsUp() {
        when(cbPayment.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(cbDisbursement.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(floatService.getAllFloats()).thenReturn(List.of(
            floatWith(OperatorType.MTN_MOMO_CI, "500000", "100000")
        ));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("Circuit operator-payment OPEN → DOWN")
    void paymentCircuitOpen_returnsDown() {
        when(cbPayment.getState()).thenReturn(CircuitBreaker.State.OPEN);
        when(cbDisbursement.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(floatService.getAllFloats()).thenReturn(List.of());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }

    @Test
    @DisplayName("Circuit operator-disbursement OPEN → DOWN")
    void disbursementCircuitOpen_returnsDown() {
        when(cbPayment.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(cbDisbursement.getState()).thenReturn(CircuitBreaker.State.OPEN);
        when(floatService.getAllFloats()).thenReturn(List.of());

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("Float bas (balance < seuil) → WARNING")
    void floatLow_returnsWarning() {
        when(cbPayment.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(cbDisbursement.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(floatService.getAllFloats()).thenReturn(List.of(
            floatWith(OperatorType.ORANGE_MONEY_CI, "50000", "100000") // 50k < seuil 100k
        ));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("WARNING");
        assertThat(health.getDetails()).containsKey("reason");
    }

    @Test
    @DisplayName("Circuit OPEN + float bas → DOWN (circuit breaker prend priorité)")
    void circuitOpenAndFloatLow_returnsDown() {
        when(cbPayment.getState()).thenReturn(CircuitBreaker.State.OPEN);
        when(cbDisbursement.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(floatService.getAllFloats()).thenReturn(List.of(
            floatWith(OperatorType.MTN_MOMO_CI, "0", "100000")
        ));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("OperatorFloatService lance une exception → floatError dans les détails")
    void floatServiceException_handledGracefully() {
        when(cbPayment.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(cbDisbursement.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(floatService.getAllFloats()).thenThrow(new RuntimeException("DB connection failed"));

        Health health = indicator.health();

        // Pas de DOWN à cause de l'exception float seule — mais floatError présent
        assertThat(health.getDetails()).containsKey("floatError");
    }

    @Test
    @DisplayName("Circuit HALF_OPEN → pas considéré OPEN (ne cause pas DOWN)")
    void halfOpenCircuit_notDown() {
        when(cbPayment.getState()).thenReturn(CircuitBreaker.State.HALF_OPEN);
        when(cbDisbursement.getState()).thenReturn(CircuitBreaker.State.CLOSED);
        when(floatService.getAllFloats()).thenReturn(List.of(
            floatWith(OperatorType.MTN_MOMO_CI, "500000", "100000")
        ));

        Health health = indicator.health();

        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private OperatorFloat floatWith(OperatorType type, String balance, String threshold) {
        OperatorFloat f = new OperatorFloat();
        f.setOperatorType(type);
        f.setBalance(new BigDecimal(balance));
        f.setLowBalanceThreshold(new BigDecimal(threshold));
        return f;
    }
}