# Ebithex — Documentation Gestion des Clés API

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Types de clés et scopes](#2-types-de-clés-et-scopes)
3. [Clés à la création du compte](#3-clés-à-la-création-du-compte)
4. [Mécanisme d'authentification](#4-mécanisme-dauthentification)
5. [Stockage et hashage](#5-stockage-et-hashage)
6. [Rotation et grace period](#6-rotation-et-grace-period)
7. [Expiration optionnelle](#7-expiration-optionnelle)
8. [Restriction IP](#8-restriction-ip)
9. [Rotation forcée](#9-rotation-forcée)
10. [Alertes de vieillissement](#10-alertes-de-vieillissement)
11. [Référence API — Endpoints marchands](#11-référence-api--endpoints-marchands)
12. [Référence API — Endpoints back-office](#12-référence-api--endpoints-back-office)
13. [Schéma de base de données](#13-schéma-de-base-de-données)
14. [Référence de configuration](#14-référence-de-configuration)
15. [Modèle de sécurité](#15-modèle-de-sécurité)
16. [Codes d'erreur](#16-codes-derreur)
17. [Runbook — Incident sur clé compromise](#17-runbook--incident-sur-clé-compromise)

---

## 1. Vue d'ensemble

Chaque compte marchand Ebithex dispose de **deux clés initiales** (live + test, FULL_ACCESS) créées à l'inscription. Le marchand peut ensuite **créer autant de clés supplémentaires** qu'il le souhaite, avec des scopes restreints, une restriction IP, une date d'expiration ou une politique de rotation forcée.

| Type | Préfixe | Environnement | Comportement |
|------|---------|---------------|--------------|
| **Live** | `ap_live_` | Production | Paiements réels, AML actif, webhooks envoyés |
| **Test** | `ap_test_` | Sandbox | Opérateur simulé, AML ignoré, schéma `sandbox` |

Les clés sont des tokens opaques de 256 bits générés par `SecureRandom`. Seul leur **hash SHA-256** est stocké en base de données — la valeur brute n'est jamais persistée et ne peut être récupérée après la génération initiale.

**Six mécanismes de sécurité** couvrent le cycle de vie complet :

1. **Scopes** : chaque clé peut être limitée à un sous-ensemble d'opérations.
2. **Grace period** : lors d'une rotation, l'ancienne clé reste valide 24 h (configurable).
3. **Expiration** : date limite optionnelle après laquelle la clé est automatiquement rejetée.
4. **Restriction IP** : liste blanche d'IPs autorisées à utiliser la clé.
5. **Rotation forcée** : politique par clé fixant un nombre maximal de jours avant désactivation automatique.
6. **Révocation d'urgence** : invalidation immédiate de toutes les clés d'un marchand.

---

## 2. Types de clés et scopes

### 2.1 Scopes disponibles

| Scope | Opérations autorisées |
|-------|----------------------|
| `FULL_ACCESS` | Toutes les opérations (équivalent d'une clé sans restriction) |
| `PAYMENTS_WRITE` | Initier des paiements (`POST /v1/payments`) |
| `PAYMENTS_READ` | Lire l'état de transactions (`GET /v1/payments/*`) |
| `PAYOUTS_WRITE` | Initier des décaissements (`POST /v1/payouts`) |
| `PAYOUTS_READ` | Lire l'état des payouts (`GET /v1/payouts/*`) |
| `WEBHOOKS_READ` | Lire les configurations et livraisons de webhooks |
| `PROFILE_READ` | Lire le profil marchand (`GET /v1/merchants/me`) |

### 2.2 Règles d'application

- Une clé avec `FULL_ACCESS` peut accéder à tous les endpoints.
- Une clé sans `FULL_ACCESS` ne peut accéder qu'aux endpoints couverts par ses scopes explicites.
- Les requêtes JWT (dashboard marchand, back-office) **ne sont jamais filtrées par scope** — elles ont accès complet.
- `PAYMENTS_WRITE` seul ne suffit pas pour lire les transactions ; il faut aussi `PAYMENTS_READ`.

### 2.3 Exemple de clé scopée (intégration CI/CD)

```json
{
  "type": "LIVE",
  "label": "CI — smoke tests",
  "scopes": ["PAYMENTS_WRITE", "PAYMENTS_READ"],
  "allowedIps": ["10.0.2.15"],
  "expiresAt": "2027-01-01T00:00:00"
}
```

Cette clé ne peut initier et lire des paiements que depuis l'IP `10.0.2.15`, et expire automatiquement le 1er janvier 2027.

---

## 3. Clés à la création du compte

À la création du compte (`POST /v1/auth/register`), deux clés sont générées automatiquement :

| Clé | Type | Scopes | Usage recommandé |
|-----|------|--------|-----------------|
| `ap_live_xxx` | LIVE | FULL_ACCESS | Intégration production |
| `ap_test_xxx` | TEST | FULL_ACCESS | Développement et tests |

```json
{
  "success": true,
  "data": {
    "merchantId": "550e8400-e29b-41d4-a716-446655440000",
    "liveApiKey": "ap_live_K7mXpQ2vNsLdA8rFcE1hJwYbGiOuTtZ3nMqVkWy0P9s",
    "testApiKey": "ap_test_R4nBsL6fQmA2xPcD9hJwKtYvGiOuZzN1eWqVkXy8M7r",
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "message": "Compte créé. Complétez votre KYC pour activer tous les services."
  }
}
```

> **Les clés brutes ne sont jamais stockées.** Si elles sont perdues, elles ne peuvent pas être récupérées — il faut les régénérer.

---

## 4. Mécanisme d'authentification

### 4.1 Usage dans les requêtes HTTP

```http
POST /api/v1/payments
X-API-Key: ap_live_K7mXpQ2vNsLdA8rFcE1hJwYbGiOuTtZ3nMqVkWy0P9s
Content-Type: application/json

{ "amount": 10000, "currency": "XOF", ... }
```

La clé est transmise dans le header `X-API-Key`. Elle ne doit **jamais** être incluse dans l'URL (risque d'apparition dans les logs de proxy).

### 4.2 Flux de validation dans `ApiKeyAuthFilter`

```
1. Extraire la clé du header X-API-Key
2. SHA-256(clé) → hash
3. ApiKeyRepository.findByHash(hash, now)
   → recherche : key_hash = hash
               OR (previous_hash = hash AND previous_expires_at > now)
4. Si not found → 401 Unauthorized
5. Si found :
   a. Vérifier key.isActive()            → sinon 401
   b. Vérifier !key.isExpired()          → sinon 401
   c. Vérifier key.isIpAllowed(clientIp) → sinon 403
   d. Vérifier merchant.isActive()       → sinon 401 (ACCOUNT_DISABLED)
6. Construire EbithexPrincipal(apiKeyId=key.id, scopes=key.parsedScopes())
7. SandboxContextHolder.set(keyType==TEST || merchant.testMode)
8. apiKeyService.touchLastUsed(key.id)   → @Async, non bloquant
```

### 4.3 Vérification de scope sur les endpoints

Les endpoints sensibles portent l'annotation `@PreAuthorize` :

```java
@PreAuthorize("@scopeGuard.hasScope(principal, T(com.ebithex.shared.apikey.ApiKeyScope).PAYMENTS_WRITE)")
public ResponseEntity<?> initiatePayment(...) { ... }
```

`ScopeGuard.hasScope()` retourne `true` si :
- L'authentification est par JWT (`apiKeyId == null`), ou
- La clé contient `FULL_ACCESS`, ou
- La clé contient explicitement le scope requis.

### 4.4 Routage de schéma selon le type de clé

| Mécanisme | Schéma DB |
|-----------|-----------|
| Clé `ap_live_` | `public` (sauf `merchant.testMode = true` → `sandbox`) |
| Clé `ap_test_` | `sandbox` (forcé par le préfixe) |
| JWT Bearer | `public` toujours |

---

## 5. Stockage et hashage

| Donnée | Stockée | Forme |
|--------|---------|-------|
| Clé brute | Non | Jamais persistée en base |
| Hash de la clé | Oui | SHA-256 hex (64 caractères) |
| Hint (4 derniers caractères) | Oui | Pour affichage uniquement (`...xK3a`) |
| Clé brute en mémoire | Transitoire | Présente uniquement le temps de la requête de génération |

```
Format : ap_live_<43 caractères Base64url sans padding>
         ap_test_<43 caractères Base64url sans padding>

Exemple :
  ap_live_K7mXpQ2vNsLdA8rFcE1hJwYbGiOuTtZ3nMqVkWy0P9s
  SHA-256 → a3f1c2d4e5b6... (stocké en base)
  Hint    → "0P9s"           (affiché dans le dashboard)
```

L'entropie effective est de **256 bits** (32 octets `SecureRandom`), rendant toute attaque par force brute infaisable.

---

## 6. Rotation et grace period

### 6.1 Principe

Lors d'une rotation, un **nouveau record `ApiKey`** est créé. L'ancien record est désactivé (`active = false`) mais son hash est conservé comme `previousHash` sur le nouveau record, valide jusqu'à `previousExpiresAt`.

```
T=0  Rotation déclenchée
  ├── Nouveau record créé   : key_hash = sha256(nouvelle_clé)
  │                           previous_hash = sha256(ancienne_clé)
  │                           previous_expires_at = T + grace_period_hours
  └── Ancien record         : active = false

T=0..grace_period  Les deux clés sont acceptées
T=grace_period+    Seule la nouvelle clé est acceptée
```

### 6.2 Avantage du nouveau record vs mise à jour en place

- Le `createdAt` est réinitialisé à chaque rotation → l'horloge du vieillissement (alertes aging) repart à zéro.
- L'historique complet des rotations est conservé en base.
- Pas de course condition entre la rotation et les requêtes en cours.

### 6.3 Rotations successives

La grace period ne conserve que la **dernière** clé précédente. En cas de deux rotations rapides, la clé d'avant-dernière est perdue immédiatement.

```
T=0  clé A active
T=1  rotation → clé B active, clé A en grâce jusqu'à T=25
T=2  rotation → clé C active, clé B en grâce jusqu'à T=26
                               clé A perdue (non référencée par C)
```

---

## 7. Expiration optionnelle

Une clé peut avoir une date limite d'utilisation (`expiresAt`). Passée cette date, elle est automatiquement rejetée avec `401 Unauthorized`.

```
ApiKey {
  expiresAt = "2027-01-01T00:00:00"
}

Requête le 2027-01-02 → 401 (clé expirée)
```

L'expiration est vérifiée dans le filtre via `key.isExpired()` :

```java
public boolean isExpired() {
    return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
}
```

L'API retourne `401` sans distinguer "clé invalide" de "clé expirée" (principe de divulgation minimale).

---

## 8. Restriction IP

Une clé peut être liée à une liste blanche d'IPs autorisées. Toute requête provenant d'une IP non listée reçoit `403 Forbidden`.

```json
{
  "allowedIps": ["10.0.2.15", "203.0.113.42"]
}
```

Si `allowedIps` est null ou vide, aucune restriction n'est appliquée (toutes les IPs sont autorisées).

Le filtre extrait l'IP cliente en respectant l'ordre : `X-Forwarded-For` (premier segment) → `RemoteAddr`.

```java
public boolean isIpAllowed(String clientIp) {
    if (allowedIps == null || allowedIps.isBlank()) return true;
    Set<String> allowed = Arrays.stream(allowedIps.split(","))
        .map(String::trim).collect(Collectors.toSet());
    return allowed.contains(clientIp);
}
```

> La restriction IP est un complément au hash de la clé, pas un remplacement. Une clé compromise mais avec restriction IP nécessite que l'attaquant provienne aussi d'une IP autorisée.

---

## 9. Rotation forcée

Un administrateur (SUPER_ADMIN) peut imposer une politique de rotation sur une clé spécifique via `rotationRequiredDays`. Le job quotidien `ApiKeyAgingJob.enforceRotationPolicy()` désactive automatiquement les clés dépassant leur délai.

```
ApiKey {
  createdAt = "2026-01-01"
  rotationRequiredDays = 90
}

Le 2026-04-01 (90 jours plus tard) :
  → active = false
  → email d'alerte envoyé au marchand
```

### Comportement

1. Le job tourne quotidiennement à 08:05 UTC (`forced-rotation-cron`).
2. Les clés dont `createdAt + rotationRequiredDays < now` ET `active = true` sont désactivées.
3. Un email urgent est envoyé au marchand avec instructions de rotation.
4. La piste d'audit enregistre `API_KEY_FORCED_DEACTIVATION`.

---

## 10. Alertes de vieillissement

Le job `ApiKeyAgingJob.sendAgingReminders()` envoie un email de rappel de rotation au marchand lorsqu'une clé dépasse `aging-alert-days` jours sans avoir été tournée.

```
Conditions pour déclencher un rappel :
  1. key.createdAt < now - aging_alert_days   (clé suffisamment ancienne)
  2. key.agingReminderSentAt IS NULL
     OR key.agingReminderSentAt < now - 30 jours  (pas de rappel récent)
  3. key.active = true
```

- Le job tourne quotidiennement à 08:00 UTC (`aging-cron`).
- Le délai par défaut est 60 jours.
- Un seul rappel par période de 30 jours (évite le spam).
- `agingReminderSentAt` est mis à jour après envoi.

---

## 11. Référence API — Endpoints marchands

### Authentification requise

Tous les endpoints ci-dessous requièrent une authentification `MERCHANT` (JWT Bearer ou clé FULL_ACCESS).

---

### `GET /api/v1/auth/api-keys` — Lister les clés

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "a1b2c3d4-...",
        "type": "LIVE",
        "label": "Production",
        "prefix": "ap_live_",
        "hint": "...0P9s",
        "scopes": ["FULL_ACCESS"],
        "allowedIps": null,
        "expiresAt": null,
        "lastUsedAt": "2026-03-19T14:32:00",
        "active": true,
        "rotationRequiredDays": null,
        "createdAt": "2026-01-15T10:00:00"
      }
    ]
  }
}
```

---

### `POST /api/v1/auth/api-keys` — Créer une clé scopée

**Body**

```json
{
  "type": "LIVE",
  "label": "Intégration paiements",
  "scopes": ["PAYMENTS_WRITE", "PAYMENTS_READ"],
  "allowedIps": ["10.0.2.15"],
  "expiresAt": "2027-01-01T00:00:00"
}
```

| Champ | Type | Requis | Description |
|-------|------|--------|-------------|
| `type` | `LIVE` \| `TEST` | Oui | Type de clé |
| `label` | String | Non | Libellé descriptif |
| `scopes` | `Set<ApiKeyScope>` | Non | Si absent : `FULL_ACCESS` par défaut |
| `allowedIps` | `Set<String>` | Non | Si absent : toutes les IPs autorisées |
| `expiresAt` | ISO-8601 | Non | Si absent : pas d'expiration |

**Réponse `201 Created`**

```json
{
  "success": true,
  "data": {
    "id": "a1b2c3d4-...",
    "rawKey": "ap_live_NewK7mXpQ2vNsLdA8rFcE1hJwYbGiOuTtZ3nMqVkW",
    "type": "LIVE",
    "label": "Intégration paiements",
    "scopes": ["PAYMENTS_WRITE", "PAYMENTS_READ"],
    "allowedIps": ["10.0.2.15"],
    "expiresAt": "2027-01-01T00:00:00",
    "createdAt": "2026-03-20T08:00:00"
  }
}
```

> `rawKey` est retourné **une seule fois**. Le stocker immédiatement dans un gestionnaire de secrets.

---

### `POST /api/v1/auth/api-keys/{keyId}/rotate` — Rotation d'une clé spécifique

**Paramètres de chemin** : `keyId` (UUID)

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "rawKey": "ap_live_RotatedK7mXpQ2vNsLdA8rFcE1hJwYbGiOuTtZ3nMqV",
    "gracePeriodHours": 24
  }
}
```

L'ancienne clé reste valide pendant `gracePeriodHours` heures.

---

### `DELETE /api/v1/auth/api-keys/{keyId}` — Révoquer une clé

**Paramètres de chemin** : `keyId` (UUID)

La clé est immédiatement désactivée. Aucune grace period. La clé authentifiant la requête de révocation reste valide.

**Réponse `204 No Content`** *(aucun corps de réponse)*

---

### `PUT /api/v1/auth/api-keys/{keyId}/scopes` — Modifier les scopes

**Body**

```json
{
  "scopes": ["PAYOUTS_READ"]
}
```

La modification est **immédiate** : les requêtes suivantes avec cette clé n'auront accès qu'aux scopes mis à jour.

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": { "scopes": ["PAYOUTS_READ"] }
}
```

---

### `PUT /api/v1/auth/api-keys/{keyId}/allowed-ips` — Modifier la liste IP

**Body**

```json
{
  "allowedIps": ["10.0.2.15", "203.0.113.42"]
}
```

Passer `allowedIps: null` ou `[]` pour supprimer toute restriction IP.

---

### `PUT /api/v1/auth/api-keys/{keyId}/expires-at` — Modifier l'expiration

**Body**

```json
{
  "expiresAt": "2027-06-01T00:00:00"
}
```

Passer `expiresAt: null` pour supprimer l'expiration.

---

## 12. Référence API — Endpoints back-office

### `GET /api/internal/merchants/{merchantId}/api-keys` — Lister les clés d'un marchand

**Rôle requis** : `ADMIN` ou `SUPER_ADMIN`

Retourne toutes les clés (actives et inactives) du marchand. Utile pour l'audit et le support.

---

### `POST /api/internal/merchants/{merchantId}/api-key/revoke` — Révocation d'urgence

**Rôle requis** : `ADMIN` ou `SUPER_ADMIN`

Invalide **immédiatement** toutes les clés du marchand (actives et en grace period). Il n'y a pas de grace period sur la révocation d'urgence.

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": null
}
```

> La révocation est tracée dans `audit_logs` (`action = API_KEYS_REVOKED`). L'action est irréversible — le marchand doit créer de nouvelles clés via son dashboard.

---

### `PUT /api/internal/merchants/{merchantId}/api-keys/{keyId}/rotation-policy` — Politique de rotation forcée

**Rôle requis** : `SUPER_ADMIN`

Fixe le nombre de jours maximal avant désactivation automatique de la clé.

**Body**

```json
{
  "rotationRequiredDays": 90
}
```

Passer `rotationRequiredDays: null` pour supprimer la contrainte.

---

## 13. Schéma de base de données

### Table `api_keys`

| Colonne | Type | Contraintes | Description |
|---------|------|-------------|-------------|
| `id` | UUID | PK | Identifiant unique |
| `merchant_id` | UUID | FK merchants(id), CASCADE | Propriétaire de la clé |
| `key_hash` | VARCHAR(64) | NOT NULL, UNIQUE | SHA-256 hex de la clé courante |
| `key_hint` | VARCHAR(8) | NOT NULL | 4 derniers caractères pour affichage |
| `prefix` | VARCHAR(20) | NOT NULL | `ap_live_` ou `ap_test_` |
| `type` | VARCHAR(10) | CHECK IN ('LIVE','TEST') | Type de clé |
| `label` | VARCHAR(100) | | Libellé marchand |
| `scopes` | TEXT | NOT NULL, DEFAULT 'FULL_ACCESS' | Scopes comma-separated |
| `allowed_ips` | TEXT | | IPs autorisées comma-separated (NULL = toutes) |
| `expires_at` | TIMESTAMPTZ | | Date d'expiration (NULL = pas d'expiration) |
| `last_used_at` | TIMESTAMPTZ | | Dernière utilisation effective |
| `active` | BOOLEAN | NOT NULL, DEFAULT TRUE | Clé active ou désactivée |
| `previous_hash` | VARCHAR(64) | | Hash de l'ancienne clé (grace period) |
| `previous_expires_at` | TIMESTAMPTZ | | Fin de validité de l'ancienne clé |
| `aging_reminder_sent_at` | TIMESTAMPTZ | | Date du dernier rappel de rotation envoyé |
| `rotation_required_days` | INTEGER | | Délai max avant désactivation forcée (NULL = aucun) |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT NOW() | Date de création |

**Index**

| Nom | Colonnes | Utilité |
|-----|----------|---------|
| `idx_api_keys_merchant` | `merchant_id` | Listing des clés d'un marchand |
| `idx_api_keys_hash` | `key_hash` | Lookup par hash — O(1) |
| `idx_api_keys_active` | `active WHERE active = TRUE` | Filtrage rapide des clés actives |

### Requête de recherche avec grace period

```sql
SELECT k FROM ApiKey k
JOIN Merchant m ON k.merchantId = m.id
WHERE (k.keyHash = :hash
    OR (k.previousHash = :hash AND k.previousExpiresAt > :now))
  AND k.active = true
  AND m.active = true
```

### Migration Flyway

- Toutes les tables sont définies dans `V1__init.sql` (script d'initialisation consolidé). La table `api_keys` y est incluse avec ses contraintes et index.

---

## 14. Référence de configuration

| Propriété | Défaut | Description |
|-----------|--------|-------------|
| `ebithex.security.api-key.grace-period-hours` | `24` | Durée (h) de validité de l'ancienne clé après rotation |
| `ebithex.security.api-key.aging-alert-days` | `60` | Âge (jours) déclenchant un rappel de rotation |
| `ebithex.security.api-key.aging-cron` | `0 0 8 * * *` | Cron du job d'alertes vieillissement (08:00 UTC) |
| `ebithex.security.api-key.forced-rotation-cron` | `0 5 8 * * *` | Cron du job de rotation forcée (08:05 UTC) |
| `ebithex.datasource.sandbox.hikari.maximum-pool-size` | `5` | Pool sandbox (taille réduite vs prod) |
| `ebithex.datasource.sandbox.hikari.minimum-idle` | `1` | Connexions idle minimales sandbox |

---

## 15. Modèle de sécurité

### 15.1 Contrôle d'accès par endpoint

| Endpoint | Auth requise | Rôle requis |
|----------|-------------|-------------|
| `POST /v1/auth/register` | Non | Public |
| `GET /v1/auth/api-keys` | JWT ou clé | `MERCHANT` |
| `POST /v1/auth/api-keys` | JWT ou clé | `MERCHANT` |
| `POST /v1/auth/api-keys/{id}/rotate` | JWT ou clé | `MERCHANT` |
| `DELETE /v1/auth/api-keys/{id}` | JWT ou clé | `MERCHANT` |
| `PUT /v1/auth/api-keys/{id}/scopes` | JWT ou clé | `MERCHANT` |
| `GET /internal/merchants/{id}/api-keys` | JWT | `ADMIN`, `SUPER_ADMIN` |
| `POST /internal/merchants/{id}/api-key/revoke` | JWT | `ADMIN`, `SUPER_ADMIN` |
| `PUT /internal/merchants/{id}/api-keys/{id}/rotation-policy` | JWT | `SUPER_ADMIN` |

Un marchand ne peut agir **que sur ses propres clés** — le `merchantId` est extrait du principal authentifié, jamais d'un paramètre de requête.

### 15.2 Principe de divulgation minimale

- L'API ne distingue pas "clé invalide", "clé expirée" et "clé révoquée" → toutes retournent `401`.
- Seul "IP non autorisée" retourne `403` (distinct pour aider le diagnostic côté marchand).
- Les logs internes contiennent le hash tronqué de la clé, jamais la valeur brute.

### 15.3 Isolation sandbox

```
ap_test_xxx → ApiKeyRepository.findByHash(hash)
           → key.type = TEST → SandboxContextHolder.set(true)
           → SchemaRoutingDataSource → pool "sandbox"
           → search_path TO sandbox, public
           → Transaction écrite dans sandbox.transactions
           → AML ignoré, opérateur simulé
```

Il est architecturalement impossible d'utiliser une clé test pour déclencher un paiement réel.

### 15.4 Piste d'audit

| Événement | `audit_logs.action` |
|-----------|---------------------|
| Révocation d'urgence | `API_KEYS_REVOKED` |
| Désactivation forcée (rotation dépassée) | `API_KEY_FORCED_DEACTIVATION` |
| Rotation normale | loggé en INFO uniquement |

---

## 16. Codes d'erreur

| Code | HTTP | Déclencheur |
|------|------|-------------|
| `UNAUTHORIZED` | 401 | Clé absente, invalide, expirée ou révoquée |
| `ACCOUNT_DISABLED` | 401 | `merchant.active = false` |
| `FORBIDDEN` | 403 | IP non autorisée sur la clé, ou rôle insuffisant |
| `MERCHANT_NOT_FOUND` | 404 | Identifiant marchand inconnu |
| `API_KEY_NOT_FOUND` | 404 | Identifiant de clé inconnu ou n'appartenant pas au marchand |
| `SCOPE_DENIED` | 403 | Endpoint non couvert par les scopes de la clé |

---

## 17. Runbook — Incident sur clé compromise

### Scénario : une clé `ap_live_` a été exposée dans des logs

**Délai cible de résolution** : < 5 minutes

---

**Étape 1 — Identifier le marchand**

```bash
grep "ap_live_" /var/log/ebithex/app.log | head -20
# ou rechercher dans Datadog / Grafana Loki
```

---

**Étape 2 — Révoquer immédiatement (admin)**

```bash
curl -X POST https://api.ebithex.io/api/internal/merchants/{merchantId}/api-key/revoke \
  -H "Authorization: Bearer $ADMIN_JWT"
```

Réponse attendue : `{"success":true,"data":null}`

À ce stade, **aucune clé existante** (y compris la clé compromise et les clés en grace period) ne peut s'authentifier.

---

**Étape 3 — Vérifier la piste d'audit**

```sql
SELECT * FROM audit_logs
WHERE entity_type = 'Merchant'
  AND entity_id   = '{merchantId}'
  AND action      = 'API_KEYS_REVOKED'
ORDER BY created_at DESC
LIMIT 1;
```

---

**Étape 4 — Auditer les transactions suspectes**

```sql
-- Transactions depuis la date estimée de compromission
SELECT * FROM transactions
WHERE merchant_id = '{merchantId}'
  AND created_at BETWEEN '{compromise_date}' AND NOW()
ORDER BY created_at DESC;
```

---

**Étape 5 — Notifier le marchand**

Informer le marchand que ses clés ont été révoquées et qu'il doit en créer de nouvelles via son dashboard (authentification JWT non affectée).

---

**Étape 6 — Le marchand crée de nouvelles clés**

```bash
# 1. S'authentifier par JWT
curl -X POST https://api.ebithex.io/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"merchant@example.com","password":"..."}'

# 2. Créer une nouvelle clé live (scopes restreints recommandés)
curl -X POST https://api.ebithex.io/api/v1/auth/api-keys \
  -H "Authorization: Bearer $MERCHANT_JWT" \
  -H "Content-Type: application/json" \
  -d '{"type":"LIVE","label":"Post-incident","scopes":["PAYMENTS_WRITE","PAYMENTS_READ"]}'

# 3. Créer une nouvelle clé test
curl -X POST https://api.ebithex.io/api/v1/auth/api-keys \
  -H "Authorization: Bearer $MERCHANT_JWT" \
  -H "Content-Type: application/json" \
  -d '{"type":"TEST","label":"Post-incident test"}'
```

---

**Étape 7 — Post-incident**

- Identifier l'origine de la fuite (CI/CD, logs, dépôt Git, variable d'environnement non chiffrée).
- Recommander au marchand d'utiliser des clés scopées avec restriction IP pour les intégrations automatisées.
- Envisager de fixer une politique de rotation forcée (`rotationRequiredDays: 90`) sur les nouvelles clés.
- Documenter l'incident dans le système de ticketing interne.

---

*Dernière mise à jour : 20 mars 2026*
