package com.ebithex.operator.inbound;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OperatorProcessedCallbackRepository extends JpaRepository<OperatorProcessedCallback, UUID> {
    boolean existsByOperatorAndOperatorReference(String operator, String operatorReference);
}
