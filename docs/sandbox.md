# Ebithex — Documentation Mode Sandbox

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture — Isolation par schéma PostgreSQL](#2-architecture--isolation-par-schéma-postgresql)
3. [Mécanisme de routage de datasource](#3-mécanisme-de-routage-de-datasource)
4. [Tables partagées et tables isolées](#4-tables-partagées-et-tables-isolées)
5. [Activation du mode sandbox](#5-activation-du-mode-sandbox)
6. [Comportement en mode sandbox](#6-comportement-en-mode-sandbox)
7. [Clés API](#7-clés-api)
8. [Référence API REST — Back-office](#8-référence-api-rest--back-office)
9. [Schéma de base de données](#9-schéma-de-base-de-données)
10. [Référence de configuration](#10-référence-de-configuration)
11. [Migrations Flyway](#11-migrations-flyway)
12. [Guide d'intégration — Tests](#12-guide-dintégration--tests)
13. [Opérations DBA et requêtes d'inspection](#13-opérations-dba-et-requêtes-dinspection)
14. [Modèle de sécurité](#14-modèle-de-sécurité)
15. [Codes d'erreur](#15-codes-derreur)
16. [Dépannage](#16-dépannage)

---

## 1. Vue d'ensemble

Le mode sandbox Ebithex permet à un marchand de tester son intégration sans déclencher d'appels vers les opérateurs mobiles réels (MTN MoMo, Orange Money, M-Pesa, Wave…). Les paiements en mode sandbox sont traités instantanément avec un statut `SUCCESS` simulé.

L'isolation est garantie **au niveau de la base de données** via deux schémas PostgreSQL dans la même instance :

- **`public`** — schéma de production ; toutes les données réelles
- **`sandbox`** — schéma de test ; toutes les données simulées

Cette approche élimine le risque historique du drapeau applicatif `test_mode` sur `Transaction` : un filtre `WHERE test_mode = false` oublié dans n'importe quelle requête pouvait polluer les rapports financiers, la réconciliation ou le settlement. Avec l'isolation par schéma, une requête sandbox ne peut structurellement pas lire ou écrire dans les tables de production.

Caractéristiques du système :
- **Isolation structurelle** : deux schémas PostgreSQL, deux pools de connexions HikariCP distincts
- **Transparent** : le code applicatif n'utilise jamais de préfixe `sandbox.` — le routage se fait via `search_path`
- **Automatique** : détection basée sur le préfixe de la clé API (`ap_test_`) ou le flag `testMode` du marchand
- **Wallet pré-financé** : tout nouveau wallet sandbox est crédité de 1 000 000 XOF automatiquement
- **AML ignoré** : les règles de détection AML et de screening sanctions ne s'appliquent pas aux transactions sandbox
- **Données non remontrées** : transactions sandbox absentes des rapports réglementaires BCEAO, du settlement et de la réconciliation

---

## 2. Architecture — Isolation par schéma PostgreSQL

```
┌──────────────────────────────────────────────────────────────────────┐
│                     PostgreSQL (même instance)                       │
│                                                                      │
│  ┌───────────────────────────┐  ┌───────────────────────────────┐   │
│  │      schéma : public      │  │      schéma : sandbox         │   │
│  │         (PROD)            │  │         (TEST)                │   │
│  │                           │  │                               │   │
│  │  transactions             │  │  transactions                 │   │
│  │  payouts                  │  │  payouts                      │   │
│  │  wallets                  │  │  wallets                      │   │
│  │  wallet_transactions      │  │  wallet_transactions          │   │
│  │  withdrawal_requests      │  │  withdrawal_requests          │   │
│  │  bulk_payouts             │  │  bulk_payouts                 │   │
│  │  bulk_payout_items        │  │  bulk_payout_items            │   │
│  │  webhook_deliveries       │  │  webhook_deliveries           │   │
│  │                           │  │                               │   │
│  │  ── tables partagées ──   │  │  (accès via search_path)      │   │
│  │  merchants                │  │                               │   │
│  │  staff_users              │  │                               │   │
│  │  fee_rules                │  │                               │   │
│  │  kyc_documents            │  │                               │   │
│  │  webhook_endpoints        │  │                               │   │
│  │  aml_alerts               │  │                               │   │
│  │  sanctions_entries        │  │                               │   │
│  │  operator_statements      │  │                               │   │
│  │  settlement_batches       │  │                               │   │
│  │  disputes                 │  │                               │   │
│  │  audit_logs               │  │                               │   │
│  └───────────────────────────┘  └───────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
         ▲                                    ▲
         │                                    │
┌────────┴────────────┐             ┌─────────┴────────────┐
│   Pool HikariCP     │             │   Pool HikariCP      │
│       prod          │             │      sandbox         │
│                     │             │                      │
│ search_path=public  │             │ search_path=          │
│                     │             │  sandbox, public     │
└─────────────────────┘             └──────────────────────┘
         ▲                                    ▲
         └──────────────┬─────────────────────┘
                        │
              ┌─────────┴──────────┐
              │  SchemaRouting     │
              │  DataSource        │
              │                    │
              │  isSandbox()       │
              │    → "sandbox"     │
              │  !isSandbox()      │
              │    → "prod"        │
              └─────────┬──────────┘
                        │
              ┌─────────┴──────────┐
              │ SandboxContext     │
              │ Holder             │
              │ (ThreadLocal)      │
              │                    │
              │  set(true/false)   │
              │  isSandbox()       │
              │  clear()           │
              └─────────┬──────────┘
                        │
              ┌─────────┴──────────┐
              │  ApiKeyAuthFilter  │
              │                    │
              │  ap_test_ → true   │
              │  ap_live_ → false  │
              │  JWT staff → false │
              └────────────────────┘
```

---

## 3. Mécanisme de routage de datasource

### 3.1 SandboxContextHolder

`SandboxContextHolder` est un porteur ThreadLocal qui transporte le flag sandbox pour la durée d'une requête HTTP :

```java
// module-shared/src/main/java/io/ebithex/shared/sandbox/SandboxContextHolder.java

public class SandboxContextHolder {
    private static final ThreadLocal<Boolean> CTX = new ThreadLocal<>();

    public static void set(boolean sandbox) { CTX.set(sandbox); }
    public static boolean isSandbox()       { return Boolean.TRUE.equals(CTX.get()); }
    public static void clear()              { CTX.remove(); }
}
```

**Règle impérative** : tout code qui appelle `SandboxContextHolder.set(...)` DOIT appeler `SandboxContextHolder.clear()` dans un bloc `finally`. Sans quoi, un thread worker réutilisé (pool de threads HTTP) hériterait du contexte sandbox de la requête précédente.

### 3.2 Initialisation par ApiKeyAuthFilter

```
Requête entrante
   │
   ├── Clé API présente ?
   │      │
   │      ├── ap_test_* OU merchant.testMode = true
   │      │      → SandboxContextHolder.set(true)
   │      │
   │      └── ap_live_* ET merchant.testMode = false
   │             → SandboxContextHolder.set(false)
   │
   ├── Token JWT (back-office / marchand dashboard) ?
   │      → SandboxContextHolder.set(false)   [toujours prod]
   │
   └── finally : SandboxContextHolder.clear()
```

### 3.3 SchemaRoutingDataSource

```java
// module-app/src/main/java/io/ebithex/config/SchemaRoutingDataSource.java

public class SchemaRoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() {
        return SandboxContextHolder.isSandbox() ? "sandbox" : "prod";
    }
}
```

Spring appelle `determineCurrentLookupKey()` à chaque `getConnection()`. En fonction de la clé retournée, la connexion est extraite du pool HikariCP correspondant.

### 3.4 Cycle de vie d'une connexion

```
Thread HTTP (requête "ap_test_...")
  │
  ├── ApiKeyAuthFilter.doFilterInternal()
  │     └── SandboxContextHolder.set(true)
  │
  ├── PaymentController.initiatePayment()
  │     └── PaymentService.initiatePayment()
  │           └── transactionRepository.save(tx)
  │                 └── SchemaRoutingDataSource.getConnection()
  │                       → clé = "sandbox"
  │                       → pool sandbox : connexion avec search_path=sandbox,public
  │                       → INSERT INTO transactions ... → sandbox.transactions ✓
  │
  └── finally : SandboxContextHolder.clear()
```

---

## 4. Tables partagées et tables isolées

### 4.1 Tables transactionnelles (dupliquées dans sandbox)

Ces tables ont une copie identique dans les deux schémas. Chaque requête lit et écrit dans le schéma sélectionné par le pool de connexions, de façon transparente :

| Table                  | `public` (prod) | `sandbox` (test) |
|------------------------|-----------------|------------------|
| `transactions`         | ✅              | ✅               |
| `payouts`              | ✅              | ✅               |
| `wallets`              | ✅              | ✅               |
| `wallet_transactions`  | ✅              | ✅               |
| `withdrawal_requests`  | ✅              | ✅               |
| `bulk_payouts`         | ✅              | ✅               |
| `bulk_payout_items`    | ✅              | ✅               |
| `webhook_deliveries`   | ✅              | ✅               |

### 4.2 Tables partagées (schéma public uniquement)

Ces tables n'existent que dans `public`. Le pool sandbox les lit via le fallback `search_path=sandbox,public` — PostgreSQL tente `sandbox.<table>` en premier, puis `public.<table>`. Les tables partagées étant absentes du schéma sandbox, elles sont toujours lues depuis `public` :

| Table                  | Rôle                                              |
|------------------------|---------------------------------------------------|
| `merchants`            | Configuration marchand — `testMode`, clés, KYC   |
| `staff_users`          | Comptes back-office                               |
| `fee_rules`            | Grille tarifaire commune prod et sandbox          |
| `kyc_documents`        | Documents KYC — liés au marchand                  |
| `webhook_endpoints`    | Endpoints de notification — communs aux deux modes|
| `aml_alerts`           | Alertes AML — prod uniquement (sandbox exempt)    |
| `sanctions_entries`    | Liste des sanctions — prod uniquement             |
| `operator_statements`  | Relevés opérateurs — prod uniquement              |
| `settlement_batches`   | Batches de règlement — prod uniquement            |
| `disputes`             | Litiges marchands — prod uniquement               |
| `audit_logs`           | Piste d'audit système — prod uniquement           |

> **Pourquoi les frais (fee_rules) sont partagés ?** Les frais sandbox reflètent la grille tarifaire réelle. Le marchand peut ainsi estimer ses coûts de production lors de l'intégration.

---

## 5. Activation du mode sandbox

### 5.1 Mécanisme de détection

Un marchand est considéré en mode sandbox si l'une des deux conditions suivantes est vraie :

| Condition                              | Description                                                      |
|----------------------------------------|------------------------------------------------------------------|
| Clé API commence par `ap_test_`        | Détection automatique sur le préfixe de la clé                  |
| `merchants.test_mode = true`           | Flag activé manuellement par un `SUPER_ADMIN`                    |

Les deux conditions sont évaluées par `ApiKeyAuthFilter` à chaque requête. La combinaison est un **OU logique** : un marchand en `testMode = true` est sandbox même s'il utilise une clé `ap_live_`.

### 5.2 Cas limites

```
ap_test_ + testMode=false  → SANDBOX  (préfixe prioritaire)
ap_live_ + testMode=true   → SANDBOX  (flag marchand prioritaire)
ap_live_ + testMode=false  → PROD
JWT back-office            → PROD     (quel que soit le marchand)
```

### 5.3 Activation via le back-office

Un `SUPER_ADMIN` peut basculer un marchand en mode sandbox via l'API interne. Tous les paiements suivants du marchand, y compris avec ses clés `ap_live_`, seront traités en mode sandbox :

```bash
curl -X PUT \
  -H "Authorization: Bearer $SUPER_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"testMode": true}' \
  https://api.ebithex.io/api/internal/merchants/{merchantId}/test-mode
```

> **Attention** : activer `testMode` sur un marchand en production interrompt ses paiements réels. Réserver aux phases de débug actif ou d'intégration initiale.

---

## 6. Comportement en mode sandbox

### 6.1 Paiements (transactions)

```
Paiement sandbox
   │
   ├── Validation (montant, marchant actif, KYC) → identique à prod
   ├── Plafonds journaliers/mensuels             → IGNORÉS
   ├── AML screening (vélocité, structuring)     → IGNORÉ
   ├── Screening sanctions                       → IGNORÉ
   ├── Appel opérateur réel                      → IGNORÉ
   │
   └── Résultat simulé :
         status           = SUCCESS
         operatorReference = "TEST-{UUID}"
         Écriture dans    : sandbox.transactions
```

### 6.2 Wallets

Lors du premier accès à un wallet sandbox (création automatique via `getOrCreate`), le solde initial est crédité de **1 000 000 XOF** (ou équivalent dans la devise du marchand) :

```
Prod :    nouveau wallet → availableBalance = 0
Sandbox : nouveau wallet → availableBalance = 1 000 000 XOF
```

Cela permet aux marchands en phase d'intégration de tester des paiements et des décaissements sans avoir à pré-alimenter leur wallet.

### 6.3 Webhooks

Les webhooks sandbox sont envoyés normalement depuis `webhook_deliveries` (table partagée, schéma `public`). L'URL de notification configurée sur l'endpoint reçoit les événements sandbox — le payload JSON inclut le statut `SUCCESS` simulé.

> **Note** : les webhooks sandbox utilisent le même mécanisme de retry que la production. Si l'endpoint du marchand est inaccessible, les tentatives de livraison échouent et sont tracées dans `webhook_deliveries`.

### 6.4 Settlement et réconciliation

Les transactions sandbox n'apparaissent jamais dans les cycles de règlement ni dans la réconciliation opérateur :

- `SettlementService.findSuccessForSettlement()` lit `public.transactions` (pool prod)
- `ReconciliationService` lit `public.transactions` (pool prod)
- `RegulatoryReportingService` lit `public.transactions` (schéma forcé par JdbcTemplate prod)

### 6.5 Rapports réglementaires BCEAO

Les rapports mensuel, CTR et SAR ne contiennent que des données prod. Les transactions sandbox sont structurellement absentes du schéma `public` et n'apparaissent donc pas dans les rapports.

### 6.6 Purge PII

`PiiRetentionJob` appelle `SandboxContextHolder.set(false)` en début d'exécution. Il opère sur le schéma `public` uniquement. Les données sandbox ne sont pas purgées par le job de production.

---

## 7. Clés API

### 7.1 Convention de nommage

| Préfixe    | Mode           | Usage                                    |
|------------|----------------|------------------------------------------|
| `ap_live_` | Production     | Paiements réels, settlement, rapports    |
| `ap_test_` | Sandbox        | Intégration, tests, démos                |

Les clés sont générées par Ebithex et ne peuvent pas être créées manuellement par le marchand.

### 7.2 Unicité et hashage

Les clés API sont hashées en SHA-256 avant stockage dans la table `api_keys` (colonne `key_hash`). La clé en clair n'est **jamais persistée**. La détection du préfixe `ap_test_` se fait sur la clé **avant hashage**, au niveau du filtre d'authentification :

```java
// ApiKeyAuthFilter.java (simplifié)
// La recherche utilise ApiKeyRepository.findByHash(hash, now)
// qui cherche key_hash OU previous_hash (période de grâce post-rotation)
boolean isSandbox = key.getType() == ApiKeyType.TEST || merchant.isTestMode();
SandboxContextHolder.set(isSandbox);
```

### 7.3 Rotation de clé

La rotation d'une clé API n'affecte pas les données existantes dans `sandbox.transactions`. Les transactions déjà créées restent accessibles via les APIs back-office avec les rôles appropriés.

---

## 8. Référence API REST — Back-office

### Authentification

```http
Authorization: Bearer <jwt-back-office>
```

---

### 8.1 Activer / désactiver le mode sandbox d'un marchand

```http
PUT /internal/merchants/{merchantId}/test-mode
Authorization: Bearer <jwt-super-admin>
Content-Type: application/json

{
  "testMode": true
}
```

**Corps de la requête**

| Champ      | Type    | Obligatoire | Description                              |
|------------|---------|-------------|------------------------------------------|
| `testMode` | boolean | ✅           | `true` = activer sandbox, `false` = désactiver |

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "merchantId": "a1b2c3d4-...",
    "testMode": true,
    "updatedAt": "2026-03-20T14:30:00Z"
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur          | Condition                               |
|--------|------------------------|-----------------------------------------|
| 403    | `FORBIDDEN`            | Rôle insuffisant (ADMIN ne peut pas)    |
| 404    | `MERCHANT_NOT_FOUND`   | Marchand introuvable                    |

**Rôle requis** : `SUPER_ADMIN` uniquement — le rôle `ADMIN` est refusé (403).

---

### 8.2 Consulter les transactions sandbox d'un marchand

```http
GET /internal/transactions?sandbox=true&merchantId={id}
Authorization: Bearer <jwt-admin>
```

> Les endpoints de listing de transactions back-office supportent le filtre `sandbox=true` qui force le contexte sandbox pour la durée de la requête.

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "tx-uuid-...",
        "ebithexReference": "APY-TEST-20260320-001",
        "merchantReference": "SANDBOX-a1b2c3",
        "amount": 5000.00,
        "currency": "XOF",
        "status": "SUCCESS",
        "operator": "MTN_MOMO_CI",
        "operatorReference": "TEST-8f3a2b1c-...",
        "phoneNumber": "+2250501xxxxx",
        "createdAt": "2026-03-20T14:30:00Z"
      }
    ],
    "totalElements": 1,
    "page": 0,
    "size": 20
  }
}
```

---

## 9. Schéma de base de données

### 9.1 Structure des tables sandbox

Les tables du schéma `sandbox` sont des copies structurelles des tables `public` correspondantes. Elles sont créées dans `V1__init.sql` via `LIKE … INCLUDING ALL` (PostgreSQL 14+) :

```sql
-- Exemple : sandbox.transactions suit exactement la structure de public.transactions
-- Colonnes, types, valeurs par défaut, contraintes CHECK, index et commentaires sont identiques.
-- Les contraintes FK cross-schéma ne sont PAS copiées (elles référenceraient public.*).
```

**Colonnes clés de `sandbox.transactions`** :

| Colonne              | Type          | Description                                                 |
|----------------------|---------------|-------------------------------------------------------------|
| `id`                 | UUID          | PK, généré automatiquement                                  |
| `ebithex_reference`  | VARCHAR(100)  | Référence unique Ebithex (`APY-TEST-...`)                   |
| `merchant_reference` | VARCHAR(255)  | Référence fournie par le marchand                           |
| `merchant_id`        | UUID          | Référence le marchand dans `public.merchants`               |
| `amount`             | DECIMAL(19,4) | Montant simulé                                              |
| `currency`           | VARCHAR(10)   | Devise                                                      |
| `status`             | VARCHAR(20)   | Toujours `SUCCESS` pour les paiements sandbox               |
| `operator`           | VARCHAR(50)   | Opérateur simulé                                            |
| `operator_reference` | VARCHAR(255)  | Référence simulée — toujours préfixée `TEST-`               |
| `fee_amount`         | DECIMAL(19,4) | Frais calculés selon la grille réelle (`fee_rules`)         |
| `phone_number`       | VARCHAR(20)   | Numéro de téléphone test                                    |
| `created_at`         | TIMESTAMPTZ   | Horodatage de création                                      |

### 9.2 Convention pour les migrations futures

Toute migration Flyway qui modifie une table transactionnelle dans `public` DOIT inclure une section miroir pour le schéma `sandbox` :

```sql
-- V2__add_payment_method_column.sql  (exemple de future migration)

-- Production
ALTER TABLE public.transactions ADD COLUMN payment_method VARCHAR(50);

-- sandbox (même modification obligatoire)
ALTER TABLE sandbox.transactions ADD COLUMN payment_method VARCHAR(50);
```

> **Règle** : si `ALTER TABLE public.<table>` apparaît dans une migration, `ALTER TABLE sandbox.<table>` doit suivre. Sans quoi les deux schémas divergent et les requêtes sandbox échoueront si la colonne est référencée par le code.

**Migration V2 — Verrouillage optimiste (déjà appliquée) :**

```sql
-- V2__add_optimistic_locking.sql

-- Production
ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE public.payouts      ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Sandbox (miroir obligatoire)
ALTER TABLE sandbox.transactions ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sandbox.payouts      ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
```

Cette migration ajoute le champ `version` utilisé par l'annotation JPA `@Version` pour le verrouillage optimiste. Elle prévient les mises à jour silencieuses en cas de concurrence (ex : callback opérateur et job d'expiration modifiant le statut d'une même transaction en parallèle). Une `OptimisticLockException` est levée si deux threads lisent la même version et que le second tente un `flush`.

---

## 10. Référence de configuration

### 10.1 Propriétés du pool de connexions

| Propriété                                              | Défaut | Description                                      |
|--------------------------------------------------------|--------|--------------------------------------------------|
| `spring.datasource.hikari.maximum-pool-size`           | 10     | Taille max du pool de connexions **prod**        |
| `spring.datasource.hikari.minimum-idle`                | 2      | Connexions idle min du pool **prod**             |
| `ebithex.datasource.sandbox.hikari.maximum-pool-size`  | 5      | Taille max du pool de connexions **sandbox**     |
| `ebithex.datasource.sandbox.hikari.minimum-idle`       | 1      | Connexions idle min du pool **sandbox**          |

### 10.2 Configuration par environnement

```properties
# application-local.properties
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=1
ebithex.datasource.sandbox.hikari.maximum-pool-size=3
ebithex.datasource.sandbox.hikari.minimum-idle=1

# application-dev.properties
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=2
ebithex.datasource.sandbox.hikari.maximum-pool-size=5
ebithex.datasource.sandbox.hikari.minimum-idle=1

# application-prod.properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.minimum-idle=5
ebithex.datasource.sandbox.hikari.maximum-pool-size=5
ebithex.datasource.sandbox.hikari.minimum-idle=1
```

> **Dimensionnement sandbox** : le trafic sandbox représente une fraction du trafic prod. Un pool de 5 connexions est suffisant en production. Augmenter uniquement si des marchands en phase d'intégration intense génèrent des queues de connexion.

### 10.3 Datasource Config — vue d'ensemble

```java
// DataSourceConfig.java (schéma simplifié)

@Bean @Primary
DataSource dataSource(DataSourceProperties props, Environment env) {
    HikariDataSource prodDs    = buildPool(props, "SET search_path TO public");
    HikariDataSource sandboxDs = buildPool(props, "SET search_path TO sandbox, public");

    SchemaRoutingDataSource routing = new SchemaRoutingDataSource();
    routing.setTargetDataSources(Map.of("prod", prodDs, "sandbox", sandboxDs));
    routing.setDefaultTargetDataSource(prodDs);
    routing.afterPropertiesSet();
    return routing;
}
```

Le `connectionInitSql` `SET search_path TO sandbox, public` garantit que :
1. Les requêtes sans préfixe de schéma (ex. `SELECT … FROM transactions`) lisent `sandbox.transactions`
2. Les tables absentes du schéma sandbox (ex. `merchants`) sont lues depuis `public` en fallback

---

## 11. Migrations Flyway

### 11.1 Script d'initialisation consolidé

Toutes les migrations ont été fusionnées en un seul script `V1__init.sql`. Il représente l'état final du schéma et inclut :

- La création du schéma `sandbox`
- Toutes les tables dans `public` (colonnes `version` JPA, contraintes CHECK sur les statuts enum)
- Les tables miroirs dans `sandbox` via `LIKE public.<table> INCLUDING ALL` (PostgreSQL 14+)

```sql
-- Extrait de V1__init.sql
CREATE SCHEMA IF NOT EXISTS sandbox;

CREATE TABLE sandbox.transactions        (LIKE public.transactions        INCLUDING ALL);
CREATE TABLE sandbox.payouts             (LIKE public.payouts             INCLUDING ALL);
CREATE TABLE sandbox.wallets             (LIKE public.wallets             INCLUDING ALL);
CREATE TABLE sandbox.wallet_transactions (LIKE public.wallet_transactions INCLUDING ALL);
CREATE TABLE sandbox.withdrawal_requests (LIKE public.withdrawal_requests INCLUDING ALL);
CREATE TABLE sandbox.bulk_payouts        (LIKE public.bulk_payouts        INCLUDING ALL);
CREATE TABLE sandbox.bulk_payout_items   (LIKE public.bulk_payout_items   INCLUDING ALL);
CREATE TABLE sandbox.webhook_deliveries  (LIKE public.webhook_deliveries  INCLUDING ALL);
```

`INCLUDING ALL` copie les colonnes, les valeurs par défaut (`DEFAULT`), les contraintes `CHECK`, les index et les commentaires. Les tables sandbox sont ainsi strictement identiques aux tables `public` à la création.

> **Migrations futures** : ajouter un nouveau script `V2__<description>.sql`. `INCLUDING ALL` s'applique uniquement à la **création** de la table — il ne propage pas automatiquement les `ALTER TABLE` ultérieurs. Toute migration ajoutant une colonne ou une contrainte à une table transactionnelle de `public` DOIT inclure la même modification sur la table correspondante dans `sandbox`.

### 11.2 Flyway et la datasource de routage

Flyway s'initialise au démarrage de l'application, avant tout traitement de requête HTTP. Le `ThreadLocal` `SandboxContextHolder` est vide (`null`) au démarrage — `isSandbox()` retourne `false`. Flyway utilise donc le pool `prod`, opère sur le schéma `public`. **Aucune configuration Flyway spécifique n'est requise.**

---

## 12. Guide d'intégration — Tests

### 12.1 Prérequis dans AbstractIntegrationTest

```java
@BeforeEach
void configureMockMvc() {
    // Garantit le contexte prod par défaut pour tous les tests
    SandboxContextHolder.set(false);
    // ... flush Redis, configure RestTemplate, etc.
}
```

Sans cette ligne, un test qui force le contexte sandbox et se termine sans `clear()` polluerait les tests suivants dans le même thread.

### 12.2 Écrire une transaction sandbox dans un test

```java
// Forcer le contexte sandbox pour écrire dans sandbox.transactions
SandboxContextHolder.set(true);
try {
    transactionRepository.save(buildTestTransaction());
} finally {
    SandboxContextHolder.clear();
}

// Vérifier que la transaction est invisible depuis le contexte prod
SandboxContextHolder.set(false);
assertThat(transactionRepository.findAll()).isEmpty();
```

### 12.3 Tester la double-isolation (test recommandé)

```java
@Test
void sandboxTransaction_invisible_in_prod_schema() {
    // 1. Créer en sandbox
    SandboxContextHolder.set(true);
    Transaction sandboxTx = transactionRepository.save(buildTestTransaction());
    SandboxContextHolder.clear();

    // 2. Vérifier que prod ne voit rien
    SandboxContextHolder.set(false);
    assertThat(transactionRepository.findById(sandboxTx.getId())).isEmpty();
    SandboxContextHolder.clear();

    // 3. Vérifier que sandbox la retrouve
    SandboxContextHolder.set(true);
    assertThat(transactionRepository.findById(sandboxTx.getId())).isPresent();
    SandboxContextHolder.clear();
}
```

### 12.4 Tester que PiiRetentionJob n'affecte pas sandbox

```java
@Test
void sandboxTransaction_notPurgedByProdJob() {
    // Créer une tx sandbox avec pii_purged_at null
    SandboxContextHolder.set(true);
    Transaction sandboxTx = transactionRepository.save(buildExpiredTransaction());
    UUID sandboxId = sandboxTx.getId();
    SandboxContextHolder.clear();

    // Lancer le job prod
    piiRetentionJob.purgeTransactions();

    // Vérifier que la tx sandbox est intacte
    SandboxContextHolder.set(true);
    Transaction after = transactionRepository.findById(sandboxId).orElseThrow();
    assertThat(after.getPiiPurgedAt()).isNull();
    SandboxContextHolder.clear();
}
```

### 12.5 Tester avec une clé API ap_test_

Via `RestTemplate` (tests d'intégration HTTP complets) :

```java
// Créer le marchand sans clé (la migration V18 supprime apiKeyHash de Merchant)
Merchant merchant = Merchant.builder()
    .businessName("Sandbox Test")
    .email(factory.uniqueEmail())
    .hashedSecret(passwordEncoder.encode("Test@1234!"))
    .country("CI").active(true).kycVerified(true)
    .kycStatus(KycStatus.APPROVED).testMode(false)
    .build();
merchantRepository.save(merchant);

// Créer les clés via ApiKeyService — keys[1] est la clé TEST (ap_test_)
String[] keys = apiKeyService.createInitialKeys(merchant.getId());
String rawTestKey = keys[1];   // ap_test_<43 chars>

ResponseEntity<Map> resp = restTemplate.exchange(
    url("/v1/payments"), HttpMethod.POST,
    new HttpEntity<>(paymentBody, factory.apiKeyHeaders(rawTestKey)),
    Map.class);

assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
assertThat(((Map<?,?>) resp.getBody().get("data")).get("operatorReference")
    .toString()).startsWith("TEST-");
```

---

## 13. Opérations DBA et requêtes d'inspection

### 13.1 Vérifier le volume de données sandbox

```sql
-- Comparer volumes prod vs sandbox
SELECT
    'public'  AS schema,
    COUNT(*)  AS tx_count,
    SUM(amount) AS total_amount
FROM public.transactions
WHERE status = 'SUCCESS'

UNION ALL

SELECT
    'sandbox',
    COUNT(*),
    SUM(amount)
FROM sandbox.transactions
WHERE status = 'SUCCESS';
```

### 13.2 Lister les marchands en mode sandbox actif

```sql
SELECT
    id,
    business_name,
    email,
    country,
    test_mode,
    created_at
FROM public.merchants
WHERE test_mode = true
ORDER BY created_at DESC;
```

### 13.3 Vérifier qu'une transaction est bien dans le bon schéma

```sql
-- Une transaction sandbox ne doit PAS apparaître dans public
SELECT COUNT(*) FROM public.transactions  WHERE ebithex_reference = 'APY-TEST-20260320-001';
-- → 0

-- Elle doit apparaître dans sandbox
SELECT COUNT(*) FROM sandbox.transactions WHERE ebithex_reference = 'APY-TEST-20260320-001';
-- → 1
```

### 13.4 Nettoyer les données sandbox (maintenance)

```sql
-- Purger les données sandbox de plus de 30 jours (maintenance périodique)
DELETE FROM sandbox.transactions
WHERE created_at < NOW() - INTERVAL '30 days';

DELETE FROM sandbox.wallets
WHERE created_at < NOW() - INTERVAL '30 days';

-- etc. pour chaque table transactionnelle sandbox
```

> **Note** : la purge sandbox est une opération de maintenance manuelle. Elle n'est pas couverte par `PiiRetentionJob` (qui opère exclusivement sur `public`).

### 13.5 Vérifier la structure des deux schémas

```sql
-- Lister les tables dans chaque schéma
SELECT table_name
FROM information_schema.tables
WHERE table_schema IN ('public', 'sandbox')
ORDER BY table_schema, table_name;

-- Vérifier que test_mode n'existe pas dans sandbox.transactions
SELECT column_name
FROM information_schema.columns
WHERE table_schema = 'sandbox'
  AND table_name = 'transactions'
ORDER BY ordinal_position;
```

---

## 14. Modèle de sécurité

### 14.1 Contrôle d'accès

| Opération                                      | Rôle(s) requis                 |
|------------------------------------------------|--------------------------------|
| Activer/désactiver sandbox d'un marchand       | `SUPER_ADMIN`                  |
| Consulter les transactions sandbox (back-office)| `ADMIN`, `SUPER_ADMIN`        |
| Paiement en mode sandbox (API marchand)        | Clé API valide (`ap_test_`)   |

### 14.2 Isolation des données

- Un marchand sandbox **ne peut pas lire** les données d'un autre marchand, sandbox ou prod — l'isolation par marchand reste garantie via `merchant_id` dans toutes les requêtes.
- Les APIs marchands (endpoint `/v1/payments`) ne retournent jamais d'informations sur le schéma utilisé.
- Le contexte sandbox est **opaque** pour le marchand : il voit un paiement `SUCCESS`, sans indication que la transaction est simulée (sauf le préfixe `TEST-` dans `operatorReference`).

### 14.3 Audit

Les opérations de modification du flag `testMode` (activation/désactivation via le back-office) sont tracées dans `public.audit_logs` avec l'action `MERCHANT_TEST_MODE_UPDATED`.

```sql
SELECT *
FROM public.audit_logs
WHERE action = 'MERCHANT_TEST_MODE_UPDATED'
  AND entity_id = '<merchant-uuid>'
ORDER BY created_at DESC;
```

---

## 15. Codes d'erreur

| Code d'erreur              | HTTP | Description                                                         |
|----------------------------|------|---------------------------------------------------------------------|
| `MERCHANT_NOT_FOUND`       | 404  | Marchand introuvable lors de la modification du flag `testMode`     |
| `FORBIDDEN`                | 403  | Tentative d'activation sandbox par un rôle `ADMIN` (SUPER_ADMIN requis) |
| `INVALID_API_KEY`          | 401  | Clé API invalide ou révoquée                                        |
| `MERCHANT_INACTIVE`        | 403  | Marchand désactivé — paiements sandbox refusés comme prod           |
| `KYC_NOT_VERIFIED`         | 403  | KYC non complété — paiements sandbox refusés                        |

> **Note** : les vérifications KYC et d'activité du marchand s'appliquent identiquement en sandbox et en prod. Seuls les plafonds et l'AML sont ignorés en mode sandbox.

---

## 16. Dépannage

### 16.1 Une transaction sandbox apparaît dans public.transactions

**Symptôme** : une transaction créée avec une clé `ap_test_` se retrouve dans `public.transactions`.

**Diagnostic** :

```sql
-- Vérifier où la transaction est réellement stockée
SELECT 'public'  AS schema, id, merchant_id FROM public.transactions  WHERE merchant_reference = 'SANDBOX-xxx';
SELECT 'sandbox' AS schema, id, merchant_id FROM sandbox.transactions WHERE merchant_reference = 'SANDBOX-xxx';
```

**Cause probable** : `SandboxContextHolder` n'a pas été défini (`set(true)`) avant l'appel — la connexion a été prise dans le pool prod. Vérifier `ApiKeyAuthFilter` : le `set(true)` est-il exécuté avant le `chain.doFilter()` ?

### 16.2 Le pool sandbox n'a pas les bons search_path

**Symptôme** : les requêtes sandbox échouent avec `relation "transactions" does not exist`.

**Diagnostic** :

```sql
-- Depuis une connexion du pool sandbox (vérifier search_path)
SHOW search_path;
-- Doit afficher : sandbox, public
```

**Cause probable** : `connectionInitSql` mal configuré dans `DataSourceConfig`. Vérifier que le pool sandbox utilise `SET search_path TO sandbox, public`.

### 16.3 Flyway échoue avec "column test_mode of relation transactions does not exist"

**Symptôme** : la migration V17 échoue si `public.transactions` n'a plus la colonne `test_mode`.

**Cause** : ordre des migrations incorrect. V16 doit être exécutée avant V17. Vérifier l'état Flyway :

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

### 16.4 SandboxContextHolder retourne true dans un job planifié

**Symptôme** : un job comme `PiiRetentionJob` ou `SettlementService` opère sur le schéma sandbox au lieu de prod.

**Cause** : un thread worker HTTP a été réutilisé sans que `SandboxContextHolder.clear()` soit appelé dans le bloc `finally`.

**Vérification** : s'assurer que tous les filtres d'authentification ont le pattern :

```java
try {
    SandboxContextHolder.set(...);
    chain.doFilter(request, response);
} finally {
    SandboxContextHolder.clear();   // obligatoire
}
```

**Protection complémentaire** : les jobs planifiés appellent `SandboxContextHolder.set(false)` en début d'exécution comme défense en profondeur :

```java
@Scheduled(cron = "...")
public void purgeTransactions() {
    SandboxContextHolder.set(false);  // défense en profondeur
    try {
        // ... purge prod uniquement
    } finally {
        SandboxContextHolder.clear();
    }
}
```

### 16.5 Wallet sandbox créé avec solde zéro

**Symptôme** : un nouveau wallet sandbox est créé avec `availableBalance = 0` au lieu de 1 000 000 XOF.

**Cause probable** : `SandboxContextHolder.isSandbox()` retourne `false` au moment de la création du wallet — la connexion a été prise dans le pool prod.

**Diagnostic** : ajouter un log dans `WalletService.buildNewWallet()` pour tracer la valeur de `SandboxContextHolder.isSandbox()` au moment de la création.

---

*Dernière mise à jour : 21 mars 2026*
