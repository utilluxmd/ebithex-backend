package com.ebithex.payout.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ebithex.shared.domain.BulkPaymentStatus;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data @Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BulkPayoutResponse {

    private UUID batchId;
    private String ebithexBatchReference;
    private String merchantBatchReference;
    private String label;
    private BulkPaymentStatus status;

    private int totalItems;
    private int processedItems;
    private int successItems;
    private int failedItems;

    private LocalDateTime createdAt;

    /** Présent uniquement sur GET /v1/payouts/bulk/{ref}/items. */
    private List<ItemSummary> items;

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ItemSummary {
        private int itemIndex;
        private String merchantReference;
        private String phoneNumber;
        private String beneficiaryName;
        private BigDecimal amount;
        private String currency;
        private OperatorType operator;
        private TransactionStatus status;
        private String ebithexReference;   // référence PO- du Payout créé
        private String failureReason;
    }
}
