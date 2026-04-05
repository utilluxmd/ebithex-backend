package com.ebithex.payment.api;

import com.ebithex.payment.application.FeeService;
import com.ebithex.payment.domain.FeeRule;
import com.ebithex.payment.dto.FeeRuleRequest;
import com.ebithex.payment.dto.FeeRuleResponse;
import com.ebithex.payment.infrastructure.FeeRuleRepository;
import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * CRUD des règles tarifaires.
 *
 * Accès :
 *   GET /internal/config/fee-rules      → ADMIN, SUPER_ADMIN
 *   POST/PUT/DELETE                     → SUPER_ADMIN uniquement
 */
@RestController
@RequestMapping("/internal/config/fee-rules")
@RequiredArgsConstructor
@Tag(name = "Back-office — Frais")
@SecurityRequirement(name = "BearerAuth")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class FeeRuleController {

    private final FeeRuleRepository feeRuleRepository;

    @GetMapping
    @Operation(summary = "Lister toutes les règles tarifaires")
    public ResponseEntity<ApiResponse<List<FeeRuleResponse>>> list() {
        List<FeeRuleResponse> rules = feeRuleRepository
            .findAllByOrderByPriorityDescCreatedAtAsc()
            .stream()
            .map(FeeRuleResponse::from)
            .toList();
        return ResponseEntity.ok(ApiResponse.ok(rules));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Détail d'une règle tarifaire")
    public ResponseEntity<ApiResponse<FeeRuleResponse>> get(@PathVariable UUID id) {
        FeeRule rule = feeRuleRepository.findById(id)
            .orElseThrow(() -> new EbithexException(ErrorCode.FEE_RULE_NOT_FOUND, "Règle introuvable: " + id));
        return ResponseEntity.ok(ApiResponse.ok(FeeRuleResponse.from(rule)));
    }

    @PostMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Créer une règle tarifaire")
    public ResponseEntity<ApiResponse<FeeRuleResponse>> create(@Valid @RequestBody FeeRuleRequest req) {
        FeeRule rule = buildFromRequest(null, req);
        rule = feeRuleRepository.save(rule);
        return ResponseEntity.ok(ApiResponse.ok("Règle créée", FeeRuleResponse.from(rule)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Modifier une règle tarifaire")
    public ResponseEntity<ApiResponse<FeeRuleResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody FeeRuleRequest req) {
        FeeRule rule = feeRuleRepository.findById(id)
            .orElseThrow(() -> new EbithexException(ErrorCode.FEE_RULE_NOT_FOUND, "Règle introuvable: " + id));
        FeeRule updated = buildFromRequest(rule, req);
        updated = feeRuleRepository.save(updated);
        return ResponseEntity.ok(ApiResponse.ok("Règle mise à jour", FeeRuleResponse.from(updated)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(summary = "Désactiver une règle tarifaire (soft delete)")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        FeeRule rule = feeRuleRepository.findById(id)
            .orElseThrow(() -> new EbithexException(ErrorCode.FEE_RULE_NOT_FOUND, "Règle introuvable: " + id));
        rule.setActive(false);
        feeRuleRepository.save(rule);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private FeeRule buildFromRequest(FeeRule existing, FeeRuleRequest req) {
        FeeRule rule = existing != null ? existing : new FeeRule();
        rule.setName(req.name());
        rule.setDescription(req.description());
        rule.setMerchantId(req.merchantId());
        rule.setOperator(req.operator());
        rule.setCountry(req.country());
        rule.setFeeType(req.feeType());
        rule.setPercentageRate(req.percentageRate());
        rule.setFlatAmount(req.flatAmount());
        rule.setMinFee(req.minFee());
        rule.setMaxFee(req.maxFee());
        rule.setMinAmount(req.minAmount());
        rule.setMaxAmount(req.maxAmount());
        if (req.priority() != null)   rule.setPriority(req.priority());
        if (req.active() != null)     rule.setActive(req.active());
        if (req.validFrom() != null)  rule.setValidFrom(req.validFrom());
        rule.setValidUntil(req.validUntil());
        if (existing == null) rule.setCreatedAt(LocalDateTime.now());
        return rule;
    }
}