package com.ebithex.settlement.infrastructure;

import com.ebithex.settlement.domain.SettlementEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SettlementEntryRepository extends JpaRepository<SettlementEntry, UUID> {

    List<SettlementEntry> findByBatchId(UUID batchId);

    @Query("SELECT COUNT(e) FROM SettlementEntry e WHERE e.transactionId = :txId")
    long countByTransactionId(@Param("txId") UUID transactionId);
}
