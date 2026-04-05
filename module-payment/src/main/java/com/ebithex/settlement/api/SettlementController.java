package com.ebithex.settlement.api;

import com.ebithex.settlement.application.SettlementService;
import com.ebithex.settlement.domain.SettlementBatch;
import com.ebithex.settlement.domain.SettlementBatchStatus;
import com.ebithex.settlement.domain.SettlementEntry;
import com.ebithex.settlement.dto.SettlementBatchResponse;
import com.ebithex.shared.domain.OperatorType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Back-office API pour le cycle de règlement.
 *
 * GET  /internal/settlement              — liste des batches avec filtres
 * GET  /internal/settlement/{id}         — détail d'un batch
 * GET  /internal/settlement/{id}/entries — lignes du batch
 * POST /internal/settlement/run          — déclenche manuellement un cycle
 * POST /internal/settlement/{id}/settle  — confirme le règlement bancaire
 */
@RestController
@RequestMapping("/internal/settlement")
@RequiredArgsConstructor
@Tag(name = "Back-office — Settlement")
public class SettlementController {

    private final SettlementService settlementService;

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPER_ADMIN')")
    public Page<SettlementBatchResponse> listBatches(
            @RequestParam(required = false) OperatorType operator,
            @RequestParam(required = false) SettlementBatchStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @PageableDefault(size = 25, sort = "periodStart", direction = Sort.Direction.DESC) Pageable pageable) {

        LocalDateTime effectiveFrom = from != null ? from : LocalDateTime.now().minusDays(30);
        LocalDateTime effectiveTo   = to   != null ? to   : LocalDateTime.now();

        return settlementService
            .listBatches(operator, status, effectiveFrom, effectiveTo, pageable)
            .map(SettlementBatchResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPER_ADMIN')")
    public SettlementBatchResponse getBatch(@PathVariable UUID id) {
        return SettlementBatchResponse.from(settlementService.getById(id));
    }

    @GetMapping("/{id}/entries")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPER_ADMIN')")
    public List<SettlementEntry> getEntries(@PathVariable UUID id) {
        return settlementService.getEntries(id);
    }

    @PostMapping("/run")
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public int triggerManually(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return settlementService.runSettlementCycle(from, to);
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasAnyRole('FINANCE','ADMIN','SUPER_ADMIN')")
    public SettlementBatchResponse confirmSettlement(@PathVariable UUID id) {
        SettlementBatch batch = settlementService.markSettled(id);
        return SettlementBatchResponse.from(batch);
    }
}