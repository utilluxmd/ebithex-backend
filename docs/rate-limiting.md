# Ebithex — Rate Limiting

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture](#2-architecture)
3. [Plans tarifaires](#3-plans-tarifaires)
4. [Comportement fail-safe](#4-comportement-fail-safe)
5. [Headers HTTP de réponse](#5-headers-http-de-réponse)
6. [Configuration](#6-configuration)
7. [Surveillance et métriques](#7-surveillance-et-métriques)

---

## 1. Vue d'ensemble

Le rate limiting Ebithex protège l'infrastructure contre les abus (rafales, DDoS applicatifs, scraping) tout en garantissant un service équitable entre marchands.

**Algorithme** : fixed-window avec deux fenêtres indépendantes (minute et heure) via un script Lua atomique sur Redis.

**Comportement en cas de panne Redis** : fail-safe — le rate limiting reste actif via un fallback en mémoire locale (par nœud), avec une précision réduite pendant la panne.

---

## 2. Architecture

```
HTTP Request
    │
    ▼
RateLimitFilter (OncePerRequestFilter)
    │
    ├── Identifie l'identifiant : merchantId (si authentifié) ou IP (si anonyme)
    ├── Sélectionne le plan : PREMIUM / STANDARD / ANONYMOUS
    │
    ▼
RateLimitService.check(identifier, plan)
    │
    ├── [Redis disponible] → checkRedis()
    │       Script Lua INCR atomique + EXPIRE
    │       Clés : rl:{id}:m:{epoch/60}  (fenêtre minute)
    │              rl:{id}:h:{epoch/3600} (fenêtre heure)
    │
    └── [Redis down] → checkLocal() (LocalRateLimitFallback)
            ConcurrentHashMap<String, AtomicLong>
            Nettoyage des fenêtres expirées toutes les 2 minutes (@Scheduled)
```

### Flux de décision

```
Si mCount > minuteLimit  → 429 Too Many Requests
Sinon si hCount > hourLimit → 429 Too Many Requests
Sinon → 2xx (requête autorisée)
```

---

## 3. Plans tarifaires

| Plan | req/min | req/heure | Attribution |
|------|---------|-----------|-------------|
| `ANONYMOUS` | 10 | 300 | Requêtes sans authentification (par IP) |
| `STANDARD` | 60 | 3 000 | Marchands actifs (KYC non vérifié) |
| `PREMIUM` | 300 | 10 000 | Marchands KYC vérifié |

L'attribution du plan se fait automatiquement dans `RateLimitFilter` en fonction du rôle/statut extrait du JWT.

---

## 4. Comportement fail-safe

### Pourquoi fail-safe et non fail-open ?

Un système **fail-open** désactive totalement le rate limiting quand Redis est indisponible, ce qui crée une fenêtre d'exploitation prévisible. Un attaquant peut délibérément saturer Redis pour contourner les limites.

Le système Ebithex adopte une approche **fail-safe** :

- Redis est la source de vérité (précision distribuée, multi-nœuds)
- En cas d'indisponibilité Redis, `LocalRateLimitFallback` prend le relais
- Les compteurs locaux sont **par nœud** (pas distribués) — légèrement moins précis qu'en temps normal, mais le rate limiting reste **effectif**
- Dès que Redis revient, les requêtes reprennent le chemin Redis automatiquement (aucune configuration manuelle)

### Cycle de vie des compteurs locaux

```
Redis indisponible
    → Exception capturée dans RateLimitService.check()
    → localFallback.increment(minuteKey)  // ConcurrentHashMap + AtomicLong
    → localFallback.increment(hourKey)
    → buildResult() — même logique de décision qu'avec Redis

Nettoyage mémoire (éviction)
    → @Scheduled(fixedDelay = 120_000)  // toutes les 2 minutes
    → Supprime les clés dont la fenêtre temporelle est expirée
    → Prévient les fuites mémoire en cas de panne Redis prolongée
```

### Précision en mode fallback

| Scénario | Précision |
|----------|-----------|
| 1 nœud | Identique à Redis (comptage exact) |
| N nœuds, Redis up | Identique à Redis (atomique, partagé) |
| N nœuds, Redis down | Chaque nœud compte séparément : un client peut envoyer jusqu'à `N × limite` requêtes totales (1 par nœud) |

Ce comportement est acceptable car il s'agit d'un mode dégradé temporaire, pas du fonctionnement normal.

---

## 5. Headers HTTP de réponse

Chaque réponse inclut des headers standard `RateLimit-*` (draft IETF) :

```
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 45
X-RateLimit-Reset: 1712345120
```

En cas de dépassement (429) :

```
HTTP/1.1 429 Too Many Requests
Retry-After: 23
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1712345120
```

---

## 6. Configuration

Le rate limiting est activé par défaut. Aucune configuration n'est requise pour le fonctionnement standard.

```properties
# Paramétrage des plans (valeurs par défaut)
# Ces valeurs sont définies dans l'enum RateLimitPlan — modifier l'enum pour changer les limites.
# Pas de properties dédiées pour les limites elles-mêmes.

# Fallback local — nettoyage des fenêtres expirées
# (configurable via @Scheduled dans LocalRateLimitFallback)
# Par défaut : toutes les 2 minutes (fixedDelay = 120_000 ms)
```

### Chemins exclus du rate limiting

Les endpoints suivants sont exclus (`RateLimitFilter.shouldSkip()`) :

- `/actuator/**` (health checks, métriques)
- `/v3/api-docs/**` (Swagger/OpenAPI)

---

## 7. Surveillance et métriques

### Logs à surveiller

```
# Redis indisponible (passage en fallback)
WARN  RateLimitService - Redis indisponible — rate limiting via fallback local pour 'ip:1.2.3.4': ...

# Requête bloquée (429)
WARN  RateLimitFilter  - Rate limit dépassé: ip:1.2.3.4 plan=ANONYMOUS limit=10 reset=1712345120
```

### Alertes recommandées

| Métrique | Seuil | Action |
|----------|-------|--------|
| Volume de 429/min > 100 | Possible attaque | Investiguer l'IP source |
| Logs "Redis indisponible" répétés | Redis down | Vérifier la connexion Redis |
| `LocalRateLimitFallback.size()` > 10 000 | Fuite mémoire potentielle | Vérifier l'éviction |

---

*Dernière mise à jour : 5 avril 2026*
