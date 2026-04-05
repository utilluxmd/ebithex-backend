package com.ebithex.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Configuration du cache Redis applicatif.
 *
 * <p>Caches configurés :
 * <ul>
 *   <li><b>merchants</b> — données marchands par ID. TTL 5 min.
 *       Invalide automatiquement lors des modifications (KYC, activation, profil).</li>
 *   <li><b>fee-rules</b> — règles tarifaires résolues par (merchant, operator, country).
 *       TTL 10 min. Les règles changent rarement.</li>
 * </ul>
 *
 * <p>Sérialisation JSON (GenericJackson2JsonRedisSerializer) pour lisibilité et
 * compatibilité entre instances (pas de sérialisation Java native qui dépend du classpath).
 *
 * <p>Si Redis est indisponible, Spring Boot dégrade gracieusement vers NoOpCacheManager
 * via la configuration {@code spring.cache.type=none} dans les propriétés de test.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** TTL du cache marchands : 5 minutes. Court pour refléter rapidement les changements. */
    private static final Duration MERCHANTS_TTL  = Duration.ofMinutes(5);

    /** TTL du cache règles tarifaires : 10 minutes. Les règles changent rarement. */
    private static final Duration FEE_RULES_TTL  = Duration.ofMinutes(10);

    /** Configuration de base partagée par tous les caches. */
    private RedisCacheConfiguration baseConfig() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
            mapper.getPolymorphicTypeValidator(),
            ObjectMapper.DefaultTyping.NON_FINAL);

        return RedisCacheConfiguration.defaultCacheConfig()
            // Sérialisation JSON pour lisibilité et portabilité inter-nœuds
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                new GenericJackson2JsonRedisSerializer(mapper)))
            // Les valeurs null SONT mises en cache (comportement par défaut de Spring Data Redis).
            // Cela évite les "cache miss storms" : un attaquant énumérant des UUIDs invalides
            // ne provoque qu'un accès DB par clé inexistante (puis la réponse null est cachée
            // avec le TTL du cache). Sans ce comportement, chaque requête frapperait la DB.
            // Note : les exceptions (EbithexException) ne sont jamais cachées — seuls les null
            // retournés par les méthodes @Cacheable le sont.
            .prefixCacheNameWith("ebithex:cache:");
    }

    /**
     * Personnalise les TTL par cache.
     * Complète la configuration par défaut de Spring Boot Redis Cache.
     */
    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> builder
            .withCacheConfiguration("merchants",
                baseConfig().entryTtl(MERCHANTS_TTL))
            .withCacheConfiguration("fee-rules",
                baseConfig().entryTtl(FEE_RULES_TTL));
    }
}
