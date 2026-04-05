package com.ebithex.payment.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ebithex.shared.event.PaymentStatusChangedEvent;
import com.ebithex.shared.event.PayoutStatusChangedEvent;
import com.ebithex.shared.outbox.OutboxEvent;
import com.ebithex.shared.outbox.OutboxEventRepository;
import com.ebithex.shared.outbox.OutboxStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Outbox Poller — lit les événements PENDING toutes les secondes
 * et les publie via ApplicationEventPublisher.
 *
 * Garantit qu'aucun événement n'est perdu même si le JVM crashe
 * entre le commit de la transaction métier et la publication de l'événement.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPoller {

    private final OutboxEventRepository outboxEventRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 1_000) // toutes les secondes
    @Transactional
    public void poll() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingBatch();
        if (pending.isEmpty()) return;

        log.debug("OutboxPoller: {} événements à dispatcher", pending.size());

        for (OutboxEvent outboxEvent : pending) {
            try {
                Object domainEvent = deserialize(outboxEvent);
                if (domainEvent != null) {
                    eventPublisher.publishEvent(domainEvent);
                    outboxEvent.setStatus(OutboxStatus.DISPATCHED);
                    outboxEvent.setDispatchedAt(Instant.now());
                } else {
                    log.warn("OutboxPoller: type d'événement inconnu — {}", outboxEvent.getEventType());
                    outboxEvent.setStatus(OutboxStatus.FAILED);
                }
            } catch (Exception e) {
                log.error("OutboxPoller: erreur dispatch {} — {}", outboxEvent.getId(), e.getMessage());
                outboxEvent.setStatus(OutboxStatus.FAILED);
            }
            outboxEventRepository.save(outboxEvent);
        }
    }

    private Object deserialize(OutboxEvent event) throws Exception {
        return switch (event.getEventType()) {
            case "PaymentStatusChangedEvent" ->
                objectMapper.readValue(event.getPayload(), PaymentStatusChangedEvent.class);
            case "PayoutStatusChangedEvent" ->
                objectMapper.readValue(event.getPayload(), PayoutStatusChangedEvent.class);
            default -> null;
        };
    }
}
