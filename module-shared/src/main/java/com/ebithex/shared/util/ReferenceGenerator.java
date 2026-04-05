package com.ebithex.shared.util;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ReferenceGenerator {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final AtomicLong counter = new AtomicLong(1000);

    /**
     * Génère une référence unique Ebithex pour une collection.
     * Format: AP-20240315-X4K9M
     */
    public String generateEbithexRef() {
        String date = LocalDateTime.now().format(FMT);
        String unique = Long.toHexString(counter.incrementAndGet()).toUpperCase();
        return String.format("AP-%s-%s", date, unique);
    }

    /**
     * Génère une référence unique pour un décaissement (payout).
     * Format: PO-20240315-X4K9M
     */
    public String generatePayoutRef() {
        String date = LocalDateTime.now().format(FMT);
        String unique = Long.toHexString(counter.incrementAndGet()).toUpperCase();
        return String.format("PO-%s-%s", date, unique);
    }

    /**
     * Génère une référence unique pour un lot de décaissements (bulk payout).
     * Format: BP-20240315-X4K9M
     */
    public String generateBulkPayoutRef() {
        String date = LocalDateTime.now().format(FMT);
        String unique = Long.toHexString(counter.incrementAndGet()).toUpperCase();
        return String.format("BP-%s-%s", date, unique);
    }
}
