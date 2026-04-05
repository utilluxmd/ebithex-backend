package com.ebithex.shared.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Écrit un événement domaine dans l'outbox dans la même transaction que l'appelant.
 * Jamais de @Transactional ici — on participe à la transaction du service appelant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void write(String aggregateType, java.util.UUID aggregateId,
                      String eventType, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(json)
                    .build();
            outboxEventRepository.save(event);
            log.debug("Outbox: queued {} for aggregate={}", eventType, aggregateId);
        } catch (Exception e) {
            // Propager — on NE PEUT PAS écrire en outbox : annuler la transaction
            throw new IllegalStateException("Outbox write failed for " + eventType, e);
        }
    }
}
