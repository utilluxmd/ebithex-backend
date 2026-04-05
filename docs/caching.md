# Ebithex — Caching Redis

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Caches configurés](#2-caches-configurés)
3. [Configuration technique](#3-configuration-technique)
4. [Stratégie d'invalidation](#4-stratégie-dinvalidation)
5. [Tests et environnements non-prod](#5-tests-et-environnements-non-prod)
6. [Surveillance](#6-surveillance)

---

## 1. Vue d'ensemble

Le cache Redis applicatif Ebithex réduit la charge sur PostgreSQL pour les données fréquemment lues mais rarement modifiées. Il s'appuie sur Spring Cache (`@Cacheable` / `@CacheEvict`) avec Redis comme backend.

**Namespace** : toutes les clés sont préfixées `ebithex:cache:` pour éviter les collisions avec les clés rate-limit (`rl:`) et session.

**Sérialisation** : JSON via `GenericJackson2JsonRedisSerializer` — lisible et portable entre nœuds (pas de sérialisation Java native dépendante du classpath).

---

## 2. Caches configurés

### `merchants` — Données marchands

| Paramètre | Valeur |
|-----------|--------|
| Clé | `merchantId` (UUID) |
| TTL | 5 minutes |
| Clé Redis complète | `ebithex:cache:merchants::{uuid}` |

**Méthodes cachées** : `MerchantService.findById(UUID merchantId)`

**Invalidation** (`@CacheEvict`) sur toutes les méthodes mutantes :
- `submitKyc`, `approveKyc`, `rejectKyc`
- `setActive`, `setTestMode`
- `setPaymentLimits`
- `anonymizeGdprData`

**Pourquoi 5 minutes ?** Les données marchands changent lors d'actions KYC/admin explicites, qui déclenchent systématiquement l'éviction. Le TTL de 5 min est un filet de sécurité contre des modifications directes en base.

---

### `fee-rules` — Règles tarifaires résolues

| Paramètre | Valeur |
|-----------|--------|
| Clé | `merchantId:operator:country` |
| TTL | 10 minutes |
| Clé Redis complète | `ebithex:cache:fee-rules::{uuid}:ORANGE_MONEY_CI:CI` |

**Méthode cachée** : `FeeService.resolveRule(UUID merchantId, OperatorType operator, String country)`

**Invalidation** (`@CacheEvict(allEntries = true)`) : `FeeService.evictFeeRuleCache()`

> Appeler `evictFeeRuleCache()` après toute création, modification ou suppression de `FeeRule` en base.

**Pourquoi cache séparé pour la résolution vs le calcul ?**
La résolution de règle (choix de la règle applicable) est indépendante du montant → cacheable.
Le calcul du `feeAmount` dépend du montant de la transaction → non caché (appelé à chaque transaction).

---

## 3. Configuration technique

```java
// CacheConfig.java (module-app)
@Bean
public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
    return builder -> builder
        .withCacheConfiguration("merchants",  baseConfig().entryTtl(Duration.ofMinutes(5)))
        .withCacheConfiguration("fee-rules",  baseConfig().entryTtl(Duration.ofMinutes(10)));
}
```

**Valeurs nulles** : les valeurs `null` **sont mises en cache** avec le TTL du cache concerné (comportement par défaut de Spring Data Redis). Cela évite les "cache miss storms" : si un acteur malveillant énumère des UUIDs inexistants, chaque UUID frappe la base de données une seule fois puis la réponse `null` est mise en cache. Sans ce comportement, chaque requête frapperait PostgreSQL.

> **Note** : les exceptions (`EbithexException`) ne sont jamais mises en cache — seul le `null` retourné par une méthode `@Cacheable` l'est. Une création ultérieure déclenchera un `@CacheEvict` qui invalide l'entrée.

---

## 4. Stratégie d'invalidation

| Cache | Déclencheur | Portée |
|-------|-------------|--------|
| `merchants` | `@CacheEvict(key = "#merchantId")` sur chaque mutation | Entrée spécifique |
| `fee-rules` | `@CacheEvict(allEntries = true)` | Toutes les entrées |

L'invalidation totale pour `fee-rules` est justifiée car une modification de règle tarifaire peut affecter plusieurs combinaisons (marchand + opérateur + pays). Avec un TTL court (10 min), le coût d'un cache miss suite à l'invalidation globale est négligeable.

---

## 5. Tests et environnements non-prod

En test (`application-test.properties`), si Redis est indisponible ou non configuré, Spring Boot dégrade gracieusement :

```properties
# Désactiver le cache en test si Redis n'est pas disponible
spring.cache.type=none
```

Avec `type=none`, `@Cacheable` et `@CacheEvict` deviennent des no-ops transparents — les tests appellent les méthodes réelles sans caching.

Les tests d'intégration (`AbstractIntegrationTest`) utilisent un conteneur Redis réel via Testcontainers, donc le cache est pleinement actif en tests d'intégration.

---

## 6. Surveillance

### Clés Redis à monitorer

```bash
# Lister toutes les clés du cache applicatif
redis-cli KEYS "ebithex:cache:*"

# TTL d'une clé marchand spécifique
redis-cli TTL "ebithex:cache:merchants::550e8400-e29b-41d4-a716-446655440000"

# Taille du cache fee-rules
redis-cli KEYS "ebithex:cache:fee-rules::*" | wc -l
```

### Métriques Spring Boot Actuator

```
# Taux de cache hit/miss (disponible si micrometer est configuré)
GET /actuator/metrics/cache.gets?tag=cache:merchants&tag=result:hit
GET /actuator/metrics/cache.gets?tag=cache:merchants&tag=result:miss
```

### Logs à surveiller

```
# Invalidation explicite du cache fee-rules
INFO  FeeService - Cache 'fee-rules' invalidé
```

---

*Dernière mise à jour : 5 avril 2026*
