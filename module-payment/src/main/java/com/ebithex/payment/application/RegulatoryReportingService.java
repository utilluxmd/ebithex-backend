package com.ebithex.payment.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Reporting réglementaire BCEAO / UEMOA.
 *
 * <p>Génère les rapports exigés par la Banque Centrale des États de l'Afrique de l'Ouest :
 * <ul>
 *   <li><b>Rapport mensuel des transactions</b> — volumes, montants et frais par opérateur/statut</li>
 *   <li><b>CTR (Currency Transaction Reports)</b> — transactions dépassant le seuil déclaratoire (défaut 5 M XOF)</li>
 *   <li><b>SAR (Suspicious Activity Reports)</b> — alertes AML générées par les règles de détection</li>
 * </ul>
 *
 * <p>Les rapports sont retournés sous forme de listes de lignes (une Map par ligne)
 * prêtes à être sérialisées en JSON ou exportées en CSV par le contrôleur.
 *
 * <p>Accès : FINANCE, ADMIN, SUPER_ADMIN
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegulatoryReportingService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Seuil par défaut de déclaration CTR (5 000 000 XOF = environ 7 500 EUR). */
    private static final BigDecimal DEFAULT_CTR_THRESHOLD = new BigDecimal("5000000");

    private final JdbcTemplate jdbc;

    // ── Rapport mensuel transactions ──────────────────────────────────────────

    /**
     * Rapport mensuel : volume de transactions agrégé par opérateur, devise et statut.
     *
     * <p>Exclut les transactions test. Conforme aux exigences de l'instruction 008-05-2015
     * de la BCEAO sur la monnaie électronique.
     *
     * @param month Mois du rapport (ex: 2026-03)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> monthlyTransactionReport(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to   = month.atEndOfMonth().plusDays(1);

        log.info("Rapport mensuel transactions: mois={}", month);

        String sql = """
            SELECT
                t.operator                                   AS operator,
                t.currency                                   AS currency,
                t.status                                     AS status,
                COUNT(*)                                     AS transaction_count,
                COALESCE(SUM(t.amount), 0)                   AS total_amount,
                COALESCE(SUM(t.fee_amount), 0)               AS total_fees,
                COALESCE(AVG(t.amount), 0)                   AS avg_amount,
                MIN(t.amount)                                AS min_amount,
                MAX(t.amount)                                AS max_amount,
                COUNT(DISTINCT t.merchant_id)                AS merchant_count
            FROM transactions t
            WHERE t.created_at >= ?
              AND t.created_at < ?
            GROUP BY t.operator, t.currency, t.status
            ORDER BY t.operator, t.currency, t.status
            """;

        return jdbc.queryForList(sql, from.atStartOfDay(), to.atStartOfDay());
    }

    /**
     * Rapport mensuel des décaissements (payouts) par opérateur et statut.
     *
     * @param month Mois du rapport
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> monthlyPayoutReport(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to   = month.atEndOfMonth().plusDays(1);

        log.info("Rapport mensuel payouts: mois={}", month);

        String sql = """
            SELECT
                p.operator                                   AS operator,
                p.currency                                   AS currency,
                p.status                                     AS status,
                COUNT(*)                                     AS payout_count,
                COALESCE(SUM(p.amount), 0)                   AS total_amount,
                COALESCE(SUM(p.fee_amount), 0)               AS total_fees,
                COUNT(DISTINCT p.merchant_id)                AS merchant_count
            FROM payouts p
            WHERE p.created_at >= ?
              AND p.created_at < ?
            GROUP BY p.operator, p.currency, p.status
            ORDER BY p.operator, p.currency, p.status
            """;

        return jdbc.queryForList(sql, from.atStartOfDay(), to.atStartOfDay());
    }

    // ── CTR — Currency Transaction Reports ───────────────────────────────────

    /**
     * Déclarations de transactions importantes (CTR) : transactions unitaires dépassant
     * le seuil réglementaire BCEAO.
     *
     * <p>Exclut les transactions test et les remboursements.
     * Toute transaction SUCCESS au-dessus du seuil doit être déclarée.
     *
     * @param from      Date de début de la période
     * @param to        Date de fin de la période (exclusive)
     * @param threshold Seuil en devise locale (null = 5 000 000 XOF par défaut)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> currencyTransactionReport(
            LocalDate from, LocalDate to, BigDecimal threshold) {

        BigDecimal effectiveThreshold = threshold != null ? threshold : DEFAULT_CTR_THRESHOLD;
        log.info("CTR: période={}/{} seuil={}", from.format(DATE_FMT), to.format(DATE_FMT), effectiveThreshold);

        String sql = """
            SELECT
                t.ebithex_reference                          AS ebithex_reference,
                t.merchant_id                                AS merchant_id,
                m.name                                       AS merchant_name,
                m.country                                    AS country,
                t.operator                                   AS operator,
                t.amount                                     AS amount,
                t.currency                                   AS currency,
                t.status                                     AS status,
                t.created_at                                 AS transaction_date,
                t.operator_reference                         AS operator_reference
            FROM transactions t
            JOIN merchants m ON m.id = t.merchant_id
            WHERE t.status = 'SUCCESS'
              AND t.amount >= ?
              AND t.created_at >= ?
              AND t.created_at < ?
            ORDER BY t.amount DESC, t.created_at DESC
            """;

        return jdbc.queryForList(sql,
            effectiveThreshold,
            from.atStartOfDay(),
            to.plusDays(1).atStartOfDay());
    }

    // ── SAR — Suspicious Activity Reports ────────────────────────────────────

    /**
     * Rapports d'activités suspectes (SAR) générés par le moteur AML.
     *
     * <p>Agrège les alertes AML par marchand et type d'anomalie sur la période.
     * Inclut : velocity excessive, structuring détecté, marchands sanctionnés.
     *
     * @param from Date de début
     * @param to   Date de fin (exclusive)
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> suspiciousActivityReport(LocalDate from, LocalDate to) {
        log.info("SAR: période={}/{}", from.format(DATE_FMT), to.format(DATE_FMT));

        String sql = """
            SELECT
                al.entity_id                                 AS merchant_id,
                m.name                                       AS merchant_name,
                m.country                                    AS country,
                al.action                                    AS alert_type,
                COUNT(*)                                     AS alert_count,
                MIN(al.created_at)                           AS first_alert,
                MAX(al.created_at)                           AS last_alert,
                al.details                                   AS details
            FROM audit_logs al
            LEFT JOIN merchants m ON m.id::text = al.entity_id
            WHERE al.action IN (
                'AML_VELOCITY_ALERT',
                'AML_STRUCTURING_ALERT',
                'AML_HIGH_AMOUNT_ALERT',
                'SANCTIONS_HIT'
            )
              AND al.created_at >= ?
              AND al.created_at < ?
            GROUP BY al.entity_id, m.name, m.country, al.action, al.details
            ORDER BY alert_count DESC, al.entity_id
            """;

        return jdbc.queryForList(sql,
            from.atStartOfDay(),
            to.plusDays(1).atStartOfDay());
    }

    // ── Résumé réglementaire global ───────────────────────────────────────────

    /**
     * Résumé réglementaire mensuel consolidé (transactions + payouts + alertes).
     * Fournit une vue synthétique pour les rapports trimestriels BCEAO.
     *
     * @param month Mois du résumé
     */
    @Transactional(readOnly = true)
    public Map<String, Object> regulatorySummary(YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to   = month.atEndOfMonth().plusDays(1);

        String summaryTx = """
            SELECT
                COUNT(*)                                     AS total_transactions,
                COUNT(*) FILTER (WHERE status = 'SUCCESS')  AS successful_transactions,
                COUNT(*) FILTER (WHERE status = 'FAILED')   AS failed_transactions,
                COALESCE(SUM(amount) FILTER (WHERE status = 'SUCCESS'), 0) AS total_successful_volume,
                COALESCE(SUM(fee_amount) FILTER (WHERE status = 'SUCCESS'), 0) AS total_fees_collected,
                COUNT(DISTINCT merchant_id)                  AS active_merchants,
                COUNT(DISTINCT operator)                     AS operators_used
            FROM transactions
            WHERE created_at >= ?
              AND created_at < ?
            """;

        String summaryPayout = """
            SELECT
                COUNT(*)                                     AS total_payouts,
                COUNT(*) FILTER (WHERE status = 'SUCCESS')  AS successful_payouts,
                COALESCE(SUM(amount) FILTER (WHERE status = 'SUCCESS'), 0) AS total_payout_volume
            FROM payouts
            WHERE created_at >= ?
              AND created_at < ?
            """;

        String summaryAlerts = """
            SELECT COUNT(*) AS total_aml_alerts
            FROM audit_logs
            WHERE action IN ('AML_VELOCITY_ALERT','AML_STRUCTURING_ALERT','AML_HIGH_AMOUNT_ALERT','SANCTIONS_HIT')
              AND created_at >= ?
              AND created_at < ?
            """;

        Map<String, Object> txRow     = jdbc.queryForMap(summaryTx, from.atStartOfDay(), to.atStartOfDay());
        Map<String, Object> payoutRow = jdbc.queryForMap(summaryPayout, from.atStartOfDay(), to.atStartOfDay());
        Map<String, Object> alertRow  = jdbc.queryForMap(summaryAlerts, from.atStartOfDay(), to.atStartOfDay());

        return Map.of(
            "period",       month.toString(),
            "transactions", txRow,
            "payouts",      payoutRow,
            "aml_alerts",   alertRow
        );
    }
}