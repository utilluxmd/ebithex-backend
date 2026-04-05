package com.ebithex.payment.infrastructure;

import com.ebithex.payment.domain.OperatorStatement;
import com.ebithex.payment.domain.OperatorStatementStatus;
import com.ebithex.shared.domain.OperatorType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperatorStatementRepository extends JpaRepository<OperatorStatement, UUID> {

    /** Relevés en attente de réconciliation (job planifié). */
    List<OperatorStatement> findByStatus(OperatorStatementStatus status);

    /** Vérifie l'unicité opérateur + date avant import. */
    Optional<OperatorStatement> findByOperatorAndStatementDate(OperatorType operator,
                                                               java.time.LocalDate date);

    @Query("SELECT s FROM OperatorStatement s ORDER BY s.statementDate DESC")
    Page<OperatorStatement> findAllPaged(Pageable pageable);

    @Query("SELECT s FROM OperatorStatement s WHERE s.operator = :operator ORDER BY s.statementDate DESC")
    Page<OperatorStatement> findByOperatorPaged(@Param("operator") OperatorType operator, Pageable pageable);
}