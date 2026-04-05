package com.ebithex.operatorfloat.infrastructure;

import com.ebithex.operatorfloat.domain.OperatorFloat;
import com.ebithex.shared.domain.OperatorType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.util.List;

public interface OperatorFloatRepository extends JpaRepository<OperatorFloat, OperatorType> {

    @Query("SELECT f FROM OperatorFloat f WHERE f.balance < f.lowBalanceThreshold")
    List<OperatorFloat> findBelowThreshold();
}