package com.ebithex.webhook.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "webhook_endpoints")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WebhookEndpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Stored as UUID — no @ManyToOne across module boundaries. */
    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 500)
    private String url;

    @Column(name = "signing_secret", nullable = false, length = 100)
    private String signingSecret;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
        name = "webhook_endpoint_events",
        joinColumns = @JoinColumn(name = "webhook_endpoint_id")
    )
    @Column(name = "events")
    @Builder.Default
    private Set<String> events = new HashSet<>();

    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
