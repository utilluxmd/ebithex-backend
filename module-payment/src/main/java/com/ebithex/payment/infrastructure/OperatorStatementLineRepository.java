package com.ebithex.payment.infrastructure;

import com.ebithex.payment.domain.DiscrepancyType;
import com.ebithex.payment.domain.OperatorStatementLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperatorStatementLineRepository extends JpaRepository<OperatorStatementLine, UUID> {

    List<OperatorStatementLine> findByStatementId(UUID statementId);

    Optional<OperatorStatementLine> findByStatementIdAndOperatorReference(UUID statementId,
                                                                           String operatorReference);

    @Query("""
        SELECT l FROM OperatorStatementLine l
        WHERE l.statementId = :statementId
          AND l.discrepancyType IS NOT NULL
          AND l.discrepancyType != com.ebithex.payment.domain.DiscrepancyType.MATCHED
        """)
    Page<OperatorStatementLine> findDiscrepanciesByStatementId(@Param("statementId") UUID statementId,
                                                               Pageable pageable);

    @Transactional
    @Modifying
    void deleteByStatementId(UUID statementId);
}