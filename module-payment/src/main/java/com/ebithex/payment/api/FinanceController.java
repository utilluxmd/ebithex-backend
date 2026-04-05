package com.ebithex.payment.api;

import com.ebithex.operatorfloat.application.OperatorFloatService;
import com.ebithex.operatorfloat.domain.OperatorFloat;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.domain.OperatorType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Endpoints finance back-office — gestion du float opérateur.
 *
 * Accès : FINANCE, ADMIN, SUPER_ADMIN
 */
@RestController("paymentFinanceController")
@RequestMapping("/internal/finance")
@RequiredArgsConstructor
@Tag(name = "Back-office — Finance / Float opérateur")
public class FinanceController {

    private final OperatorFloatService floatService;

    @GetMapping("/float")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Lister les floats de tous les opérateurs")
    public ResponseEntity<ApiResponse<List<OperatorFloat>>> getAllFloats() {
        return ResponseEntity.ok(ApiResponse.ok(floatService.getAllFloats()));
    }

    @GetMapping("/float/alerts")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Opérateurs dont le float est en dessous du seuil d'alerte")
    public ResponseEntity<ApiResponse<List<OperatorFloat>>> getFloatAlerts() {
        return ResponseEntity.ok(ApiResponse.ok(floatService.getFloatsBelowThreshold()));
    }

    @PutMapping("/float/{operator}")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPER_ADMIN')")
    @Operation(summary = "Ajuster le solde float d'un opérateur (après approvisionnement ou réconciliation)")
    public ResponseEntity<ApiResponse<OperatorFloat>> adjustFloat(
            @PathVariable OperatorType operator,
            @RequestBody FloatAdjustRequest request) {
        OperatorFloat updated = floatService.adjustFloat(
            operator, request.balance(), request.lowBalanceThreshold());
        return ResponseEntity.ok(ApiResponse.ok(updated));
    }

    public record FloatAdjustRequest(
        @NotNull @DecimalMin("0") BigDecimal balance,
        BigDecimal lowBalanceThreshold
    ) {}
}