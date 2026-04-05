package com.ebithex.payment.application;

import com.ebithex.payment.domain.*;
import com.ebithex.payment.dto.StatementImportResult;
import com.ebithex.payment.infrastructure.OperatorStatementLineRepository;
import com.ebithex.payment.infrastructure.OperatorStatementRepository;
import com.ebithex.payment.infrastructure.TransactionRepository;
import com.ebithex.shared.domain.OperatorType;
import com.ebithex.shared.domain.TransactionStatus;
import com.ebithex.shared.exception.ErrorCode;
import com.ebithex.shared.exception.EbithexException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Réconciliation automatisée entre les relevés journaliers des opérateurs
 * et les transactions enregistrées dans Ebithex.
 *
 * Format CSV attendu (une ligne par transaction) :
 *   operator_reference,amount,currency,status,transaction_date
 *
 * Exemple :
 *   MTN-CI-123456,5000.00,XOF,SUCCESS,2026-03-17T10:00:00
 *
 * Anomalies détectées :
 *   MISSING_IN_EBITHEX  — l'opérateur liste une transaction absente chez nous
 *   AMOUNT_MISMATCH     — montants différents
 *   STATUS_MISMATCH     — statuts différents (ex. opérateur SUCCESS, nous FAILED)
 *   MATCHED             — concordance parfaite
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class OperatorReconciliationService {

    private final OperatorStatementRepository     statementRepository;
    private final OperatorStatementLineRepository lineRepository;
    private final TransactionRepository           transactionRepository;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Importe un relevé CSV fourni par l'opérateur.
     * Crée l'entête {@link OperatorStatement} et parse les lignes CSV.
     * La réconciliation réelle est lancée ensuite via {@link #reconcile(UUID)}.
     *
     * @throws EbithexException STATEMENT_ALREADY_EXISTS si un relevé existe déjà pour ce couple opérateur/date
     * @throws EbithexException INVALID_CSV si le CSV est malformé
     */
    @Transactional
    public OperatorStatement importStatement(OperatorType operator, LocalDate statementDate,
                                             Reader csvReader, UUID importedBy) throws IOException {

        // Unicité opérateur + date
        statementRepository.findByOperatorAndStatementDate(operator, statementDate).ifPresent(s -> {
            throw new EbithexException(ErrorCode.STATEMENT_ALREADY_EXISTS,
                "Un relevé existe déjà pour " + operator + " / " + statementDate + " (id=" + s.getId() + ")");
        });

        OperatorStatement statement = OperatorStatement.builder()
            .operator(operator)
            .statementDate(statementDate)
            .status(OperatorStatementStatus.PENDING)
            .importedBy(importedBy)
            .build();

        statement = statementRepository.save(statement);

        List<OperatorStatementLine> lines = parseCsv(csvReader, statement.getId());
        lineRepository.saveAll(lines);

        statement.setTotalLines(lines.size());
        statement = statementRepository.save(statement);

        log.info("Relevé importé: operator={} date={} lignes={} id={}",
            operator, statementDate, lines.size(), statement.getId());

        return statement;
    }

    // ── Réconciliation ────────────────────────────────────────────────────────

    /**
     * Lance la réconciliation d'un relevé donné.
     * Compare chaque ligne du relevé avec les transactions Ebithex
     * identifiées par {@code operatorReference}.
     *
     * @return résultat avec compteurs matched/discrepancy
     */
    @Transactional
    public StatementImportResult reconcile(UUID statementId) {
        OperatorStatement statement = statementRepository.findById(statementId)
            .orElseThrow(() -> new EbithexException(ErrorCode.STATEMENT_NOT_FOUND,
                "Relevé introuvable: " + statementId));

        statement.setStatus(OperatorStatementStatus.PROCESSING);
        statementRepository.save(statement);

        List<OperatorStatementLine> lines = lineRepository.findByStatementId(statementId);
        int matched = 0;
        int discrepancy = 0;

        for (OperatorStatementLine line : lines) {
            DiscrepancyType dtype = reconcileLine(line);
            line.setDiscrepancyType(dtype);
            if (dtype == DiscrepancyType.MATCHED) {
                matched++;
            } else {
                discrepancy++;
            }
        }

        lineRepository.saveAll(lines);

        // ── Détection MISSING_IN_OPERATOR ─────────────────────────────────────
        // Transactions Ebithex SUCCESS/PARTIALLY_REFUNDED/REFUNDED pour cet opérateur
        // à la date du relevé, mais absentes du CSV opérateur.
        Set<String> knownOperatorRefs = lines.stream()
            .map(OperatorStatementLine::getOperatorReference)
            .collect(Collectors.toSet());

        LocalDateTime dayStart = statement.getStatementDate().atStartOfDay();
        LocalDateTime dayEnd   = dayStart.plusDays(1);

        List<Transaction> ebithexTxs = transactionRepository.findByOperatorAndDateAndStatusIn(
            statement.getOperator(), dayStart, dayEnd,
            List.of(TransactionStatus.SUCCESS,
                    TransactionStatus.PARTIALLY_REFUNDED,
                    TransactionStatus.REFUNDED));

        List<OperatorStatementLine> missingLines = new ArrayList<>();
        for (Transaction tx : ebithexTxs) {
            if (!knownOperatorRefs.contains(tx.getOperatorReference())) {
                missingLines.add(OperatorStatementLine.builder()
                    .statementId(statementId)
                    .operatorReference(tx.getOperatorReference())
                    .ebithexReference(tx.getEbithexReference())
                    .amount(tx.getAmount())
                    .currency(tx.getCurrency().name())
                    .operatorStatus("MISSING")
                    .discrepancyType(DiscrepancyType.MISSING_IN_OPERATOR)
                    .discrepancyNote("Transaction " + tx.getEbithexReference()
                        + " (status=" + tx.getStatus() + ") absente du relevé opérateur")
                    .build());
                discrepancy++;
            }
        }
        if (!missingLines.isEmpty()) {
            lineRepository.saveAll(missingLines);
            log.warn("MISSING_IN_OPERATOR: {} transaction(s) Ebithex absentes du relevé {} / {}",
                missingLines.size(), statement.getOperator(), statement.getStatementDate());
        }

        int totalLinesReconciled = lines.size() + missingLines.size();
        statement.setMatchedLines(matched);
        statement.setDiscrepancyLines(discrepancy);
        statement.setReconciledAt(LocalDateTime.now());
        statement.setStatus(discrepancy == 0
            ? OperatorStatementStatus.RECONCILED
            : OperatorStatementStatus.DISCREPANCY_FOUND);
        statementRepository.save(statement);

        log.info("Réconciliation terminée: statementId={} matched={} discrepancy={} missingInOperator={}",
            statementId, matched, discrepancy, missingLines.size());

        return new StatementImportResult(statementId, totalLinesReconciled, matched, discrepancy,
            statement.getStatus().name());
    }

    /**
     * Réconcilie automatiquement tous les relevés en statut PENDING.
     * Appelé par {@link OperatorReconciliationJob}.
     */
    @Transactional
    public void reconcileAllPending() {
        List<OperatorStatement> pending = statementRepository.findByStatus(OperatorStatementStatus.PENDING);
        if (pending.isEmpty()) {
            log.info("Aucun relevé en attente de réconciliation");
            return;
        }
        log.info("Réconciliation automatique de {} relevé(s) PENDING", pending.size());
        for (OperatorStatement s : pending) {
            try {
                reconcile(s.getId());
            } catch (Exception e) {
                log.error("Erreur lors de la réconciliation du relevé {}: {}", s.getId(), e.getMessage(), e);
            }
        }
    }

    // ── Consultation ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<OperatorStatement> listStatements(OperatorType operator, Pageable pageable) {
        if (operator != null) {
            return statementRepository.findByOperatorPaged(operator, pageable);
        }
        return statementRepository.findAllPaged(pageable);
    }

    @Transactional(readOnly = true)
    public OperatorStatement getStatement(UUID statementId) {
        return statementRepository.findById(statementId)
            .orElseThrow(() -> new EbithexException(ErrorCode.STATEMENT_NOT_FOUND,
                "Relevé introuvable: " + statementId));
    }

    @Transactional(readOnly = true)
    public Page<OperatorStatementLine> getDiscrepancies(UUID statementId, Pageable pageable) {
        if (!statementRepository.existsById(statementId)) {
            throw new EbithexException(ErrorCode.STATEMENT_NOT_FOUND, "Relevé introuvable: " + statementId);
        }
        return lineRepository.findDiscrepanciesByStatementId(statementId, pageable);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private DiscrepancyType reconcileLine(OperatorStatementLine line) {
        Optional<Transaction> txOpt = transactionRepository
            .findByOperatorReference(line.getOperatorReference());

        if (txOpt.isEmpty()) {
            line.setDiscrepancyNote("Référence opérateur absente de la base Ebithex");
            return DiscrepancyType.MISSING_IN_EBITHEX;
        }

        Transaction tx = txOpt.get();
        line.setEbithexReference(tx.getEbithexReference());

        // Vérification du montant (tolérance d'1 centime pour les arrondis opérateurs)
        BigDecimal diff = tx.getAmount().subtract(line.getAmount()).abs();
        if (diff.compareTo(new BigDecimal("0.01")) > 0) {
            line.setDiscrepancyNote(
                "Montant Ebithex=" + tx.getAmount() + " vs opérateur=" + line.getAmount());
            return DiscrepancyType.AMOUNT_MISMATCH;
        }

        // Vérification du statut
        boolean operatorSuccess = "SUCCESS".equalsIgnoreCase(line.getOperatorStatus())
            || "COMPLETED".equalsIgnoreCase(line.getOperatorStatus());
        boolean ebithexSuccess = tx.getStatus() == TransactionStatus.SUCCESS
            || tx.getStatus() == TransactionStatus.REFUNDED
            || tx.getStatus() == TransactionStatus.PARTIALLY_REFUNDED;

        if (operatorSuccess != ebithexSuccess) {
            line.setDiscrepancyNote("Statut Ebithex=" + tx.getStatus()
                + " vs opérateur=" + line.getOperatorStatus());
            return DiscrepancyType.STATUS_MISMATCH;
        }

        return DiscrepancyType.MATCHED;
    }

    /**
     * Parse le CSV de l'opérateur.
     * Format attendu (avec en-tête) :
     *   operator_reference,amount,currency,status,transaction_date
     */
    private List<OperatorStatementLine> parseCsv(Reader csvReader, UUID statementId) throws IOException {
        List<OperatorStatementLine> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(csvReader)) {
            String header = reader.readLine(); // skip header
            if (header == null) return lines;

            String rawLine;
            int lineNum = 1;
            while ((rawLine = reader.readLine()) != null) {
                lineNum++;
                rawLine = rawLine.trim();
                if (rawLine.isEmpty()) continue;

                String[] parts = rawLine.split(",", -1);
                if (parts.length < 4) {
                    throw new EbithexException(ErrorCode.INVALID_CSV,
                        "Ligne " + lineNum + " malformée (attendu ≥ 4 colonnes) : " + rawLine);
                }

                try {
                    OperatorStatementLine line = OperatorStatementLine.builder()
                        .statementId(statementId)
                        .operatorReference(parts[0].trim())
                        .amount(new BigDecimal(parts[1].trim()))
                        .currency(parts[2].trim())
                        .operatorStatus(parts[3].trim())
                        .operatorDate(parts.length > 4 && !parts[4].trim().isEmpty()
                            ? LocalDateTime.parse(parts[4].trim(), DT_FMT) : null)
                        .build();
                    lines.add(line);
                } catch (Exception e) {
                    throw new EbithexException(ErrorCode.INVALID_CSV,
                        "Erreur de parsing ligne " + lineNum + ": " + e.getMessage());
                }
            }
        }
        return lines;
    }
}