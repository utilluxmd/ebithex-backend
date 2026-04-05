package com.ebithex.aml.dto;

import com.ebithex.aml.domain.AmlAlert;
import com.ebithex.aml.domain.AmlSeverity;
import com.ebithex.aml.domain.AmlStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Enregistrement SAR (Suspicious Activity Report) / CCF (Currency Transaction Form).
 *
 * <p>Structure conforme aux exigences de déclaration BCEAO/UEMOA pour les
 * Déclarations de Soupçon (DS) et les Déclarations d'Opération Suspecte (DOS).
 *
 * <p>Chaque enregistrement correspond à une alerte AML de sévérité HIGH ou CRITICAL
 * non encore déclarée aux autorités (statut OPEN ou UNDER_REVIEW).
 */
public record SarExportRecord(
    /** Identifiant unique de l'alerte AML dans Ebithex. */
    UUID          alertId,
    /** Identifiant du marchand à l'origine de l'activité suspecte. */
    UUID          merchantId,
    /** Identifiant de la transaction déclenchante (peut être null pour règles agrégées). */
    UUID          transactionId,
    /** Code de la règle AML déclenchée (ex : VELOCITY_DAILY, STRUCTURING). */
    String        ruleCode,
    /** Sévérité de l'alerte : HIGH ou CRITICAL. */
    AmlSeverity   severity,
    /** Statut actuel de l'alerte (OPEN ou UNDER_REVIEW). */
    AmlStatus     status,
    /** Montant de la transaction ou du volume agrégé. */
    BigDecimal    amount,
    /** Code devise ISO-4217. */
    String        currency,
    /** Description détaillée du motif de déclenchement. */
    String        details,
    /** Date et heure de création de l'alerte. */
    LocalDateTime createdAt
) {

    private static final DateTimeFormatter CSV_DT_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Convertit une entité {@link AmlAlert} en enregistrement SAR. */
    public static SarExportRecord from(AmlAlert alert) {
        return new SarExportRecord(
            alert.getId(),
            alert.getMerchantId(),
            alert.getTransactionId(),
            alert.getRuleCode(),
            alert.getSeverity(),
            alert.getStatus(),
            alert.getAmount(),
            alert.getCurrency(),
            alert.getDetails(),
            alert.getCreatedAt()
        );
    }

    /**
     * Sérialise l'enregistrement en ligne CSV (RFC 4180).
     * Les valeurs contenant des virgules ou guillemets sont protégées.
     */
    public String toCsvRow() {
        return String.join(",",
            str(alertId),
            str(merchantId),
            str(transactionId),
            csv(ruleCode),
            str(severity),
            str(status),
            amount != null ? amount.toPlainString() : "",
            csv(currency),
            csv(details),
            createdAt != null ? createdAt.format(CSV_DT_FORMAT) : ""
        );
    }

    /** En-tête CSV. */
    public static String csvHeader() {
        return "alert_id,merchant_id,transaction_id,rule_code,severity,status," +
               "amount,currency,details,created_at";
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    /** Entoure de guillemets et échappe les guillemets internes (RFC 4180). */
    private static String csv(String s) {
        if (s == null) return "";
        String escaped = s.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
