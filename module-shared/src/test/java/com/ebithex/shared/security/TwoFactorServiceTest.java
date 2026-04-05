package com.ebithex.shared.security;

import com.ebithex.shared.event.TwoFactorOtpEvent;
import com.ebithex.shared.exception.EbithexException;
import com.ebithex.shared.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests unitaires pour TwoFactorService — OTP 2FA avec protection bruteforce.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TwoFactorService — OTP 2FA + protection bruteforce")
class TwoFactorServiceTest {

    @Mock private StringRedisTemplate      redis;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ValueOperations<String, String> valueOps;

    private TwoFactorService service;

    private static final String EMAIL      = "admin@ebithex.io";
    private static final String TEMP_TOKEN = "550e8400-e29b-41d4-a716-446655440000";
    private static final String VALID_OTP  = "123456";

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new TwoFactorService(redis, eventPublisher);
    }

    // ── initiateOtp ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("initiateOtp() — stocke le code et le token dans Redis, publie l'événement")
    void initiateOtp_storesInRedisAndPublishesEvent() {
        String tempToken = service.initiateOtp(EMAIL);

        assertThat(tempToken).isNotBlank();

        // Vérifie que le code OTP est stocké sous "otp:code:{email}"
        verify(valueOps).set(eq("otp:code:" + EMAIL), anyString(), eq(Duration.ofMinutes(5)));
        // Vérifie que le tempToken est stocké sous "otp:token:{token}"
        verify(valueOps).set(eq("otp:token:" + tempToken), eq(EMAIL), eq(Duration.ofMinutes(5)));

        // Vérifie que l'événement email est publié
        ArgumentCaptor<TwoFactorOtpEvent> captor = ArgumentCaptor.forClass(TwoFactorOtpEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().email()).isEqualTo(EMAIL);
    }

    @Test
    @DisplayName("initiateOtp() — le code OTP est un nombre à 6 chiffres")
    void initiateOtp_otpIsSixDigits() {
        // Capture le code stocké dans Redis
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        service.initiateOtp(EMAIL);

        verify(valueOps).set(eq("otp:code:" + EMAIL), otpCaptor.capture(), any(Duration.class));
        String otp = otpCaptor.getValue();

        assertThat(otp).hasSize(6).matches("\\d{6}");
    }

    // ── verifyOtp — cas nominal ───────────────────────────────────────────────

    @Test
    @DisplayName("verifyOtp() — code correct, première tentative → retourne l'email")
    void verifyOtp_validCode_returnsEmail() {
        when(valueOps.get("otp:token:" + TEMP_TOKEN)).thenReturn(EMAIL);
        when(valueOps.get("otp:attempts:" + TEMP_TOKEN)).thenReturn(null); // 0 tentatives
        when(valueOps.get("otp:code:" + EMAIL)).thenReturn(VALID_OTP);

        String result = service.verifyOtp(TEMP_TOKEN, VALID_OTP);

        assertThat(result).isEqualTo(EMAIL);

        // Toutes les clés Redis doivent être supprimées après succès
        verify(redis).delete("otp:code:" + EMAIL);
        verify(redis).delete("otp:token:" + TEMP_TOKEN);
        verify(redis).delete("otp:attempts:" + TEMP_TOKEN);
    }

    // ── verifyOtp — token expiré ──────────────────────────────────────────────

    @Test
    @DisplayName("verifyOtp() — tempToken inconnu (expiré) → OTP_EXPIRED")
    void verifyOtp_expiredToken_throwsOtpExpired() {
        when(valueOps.get("otp:token:" + TEMP_TOKEN)).thenReturn(null);

        assertThatThrownBy(() -> service.verifyOtp(TEMP_TOKEN, VALID_OTP))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.OTP_EXPIRED));
    }

    // ── verifyOtp — code incorrect ────────────────────────────────────────────

    @Test
    @DisplayName("verifyOtp() — code incorrect → OTP_INVALID (compteur incrémenté)")
    void verifyOtp_wrongCode_throwsOtpInvalid() {
        when(valueOps.get("otp:token:" + TEMP_TOKEN)).thenReturn(EMAIL);
        when(valueOps.get("otp:attempts:" + TEMP_TOKEN)).thenReturn("0");
        when(valueOps.get("otp:code:" + EMAIL)).thenReturn("999999");

        assertThatThrownBy(() -> service.verifyOtp(TEMP_TOKEN, "111111"))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.OTP_INVALID));

        // Le compteur doit être incrémenté
        verify(valueOps).increment("otp:attempts:" + TEMP_TOKEN);
    }

    // ── verifyOtp — protection bruteforce ─────────────────────────────────────

    @Test
    @DisplayName("verifyOtp() — 5 tentatives atteintes → LOGIN_ATTEMPTS_EXCEEDED + token invalidé")
    void verifyOtp_maxAttemptsReached_throwsLocked() {
        when(valueOps.get("otp:token:" + TEMP_TOKEN)).thenReturn(EMAIL);
        when(valueOps.get("otp:attempts:" + TEMP_TOKEN)).thenReturn("5"); // déjà 5 tentatives

        assertThatThrownBy(() -> service.verifyOtp(TEMP_TOKEN, "111111"))
            .isInstanceOf(EbithexException.class)
            .satisfies(ex -> assertThat(((EbithexException) ex).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_ATTEMPTS_EXCEEDED));

        // Le tempToken doit être invalidé pour forcer un nouveau login
        verify(redis).delete("otp:token:" + TEMP_TOKEN);
        verify(redis).delete("otp:attempts:" + TEMP_TOKEN);
        // Le code OTP ne doit pas avoir été vérifié
        verify(valueOps, never()).get("otp:code:" + EMAIL);
    }

    @Test
    @DisplayName("verifyOtp() — 4 tentatives puis code correct → succès (pas de blocage)")
    void verifyOtp_fourFailsThenSuccess_allowed() {
        when(valueOps.get("otp:token:" + TEMP_TOKEN)).thenReturn(EMAIL);
        when(valueOps.get("otp:attempts:" + TEMP_TOKEN)).thenReturn("4"); // 4 tentatives, pas encore bloqué
        when(valueOps.get("otp:code:" + EMAIL)).thenReturn(VALID_OTP);

        String result = service.verifyOtp(TEMP_TOKEN, VALID_OTP);

        assertThat(result).isEqualTo(EMAIL);
    }
}
