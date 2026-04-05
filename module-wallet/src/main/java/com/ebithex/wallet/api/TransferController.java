package com.ebithex.wallet.api;

import com.ebithex.shared.api.ApiResponse;
import com.ebithex.shared.security.EbithexPrincipal;
import com.ebithex.shared.util.ReferenceGenerator;
import com.ebithex.wallet.application.WalletService;
import com.ebithex.wallet.dto.B2bTransferRequest;
import com.ebithex.wallet.dto.B2bTransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Virements B2B inter-marchands Ebithex.
 *
 * <p>Permet à un marchand de transférer des fonds depuis son wallet vers le wallet
 * d'un autre marchand enregistré sur la plateforme Ebithex, dans la même devise.
 *
 * <p>Cas d'usage :
 * <ul>
 *   <li>Règlement de factures entre partenaires commerciaux</li>
 *   <li>Transfert de liquidités entre entités d'un même groupe</li>
 *   <li>Commission reversée à un apporteur d'affaires</li>
 * </ul>
 *
 * <p>Le transfert est atomique et idempotent via {@code merchantReference}.
 */
@RestController
@RequestMapping("/v1/transfers")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transferts B2B", description = "Virements inter-marchands Ebithex (wallet-to-wallet)")
@SecurityRequirement(name = "ApiKey")
public class TransferController {

    private final WalletService      walletService;
    private final ReferenceGenerator referenceGenerator;

    /**
     * Initie un virement B2B du wallet du marchand authentifié vers un autre marchand.
     *
     * <p>Envoyer le même {@code merchantReference} retourne le résultat sans re-débiter (idempotent).
     *
     * @return Résultat du transfert avec la référence Ebithex générée
     */
    @PostMapping
    @Operation(
        summary = "Initier un virement B2B",
        description = "Transfère des fonds du wallet du marchand authentifié vers le wallet d'un autre marchand Ebithex. " +
                      "Idempotent via merchantReference : un second appel avec la même référence est ignoré silencieusement. " +
                      "Le transfert est atomique — l'expéditeur est débité si et seulement si le destinataire est crédité."
    )
    public ResponseEntity<ApiResponse<B2bTransferResponse>> transfer(
            @Valid @RequestBody B2bTransferRequest request,
            @AuthenticationPrincipal EbithexPrincipal principal) {

        String ebithexRef = referenceGenerator.generateEbithexRef();

        walletService.transfer(
            principal.merchantId(),
            request.receiverMerchantId(),
            request.amount(),
            request.currency(),
            ebithexRef,
            request.description()
        );

        log.info("Transfert B2B: expéditeur={} destinataire={} montant={} {} ref={}",
            principal.merchantId(), request.receiverMerchantId(),
            request.amount(), request.currency(), ebithexRef);

        B2bTransferResponse response = new B2bTransferResponse(
            ebithexRef,
            principal.merchantId(),
            request.receiverMerchantId(),
            request.amount(),
            request.currency().name(),
            request.description(),
            Instant.now()
        );
        return ResponseEntity.ok(ApiResponse.ok("Virement effectué", response));
    }
}