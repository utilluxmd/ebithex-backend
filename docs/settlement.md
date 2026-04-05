# Ebithex — Documentation Cycle de Règlement (Settlement)

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture](#2-architecture)
3. [Concept de batch de règlement](#3-concept-de-batch-de-règlement)
4. [Cycle de règlement automatique](#4-cycle-de-règlement-automatique)
5. [Idempotence et gestion des doublons](#5-idempotence-et-gestion-des-doublons)
6. [Référence API REST — Back-office](#6-référence-api-rest--back-office)
7. [Schéma de base de données](#7-schéma-de-base-de-données)
8. [Référence de configuration](#8-référence-de-configuration)
9. [Modèle de sécurité](#9-modèle-de-sécurité)
10. [Codes d'erreur](#10-codes-derreur)
11. [Opérations manuelles et récupération d'incident](#11-opérations-manuelles-et-récupération-dincident)

---

## 1. Vue d'ensemble

Le cycle de règlement (settlement) est le processus par lequel Ebithex calcule et consolide les montants dus aux opérateurs mobiles (MTN MoMo, Orange Money, M-Pesa, etc.) pour les transactions de paiement traitées avec succès sur une période donnée.

Le cycle produit des **batches de règlement** : une agrégation par opérateur et par devise de toutes les transactions `SUCCESS` d'une période, avec les montants bruts collectés, les frais Ebithex retenus, et le montant net à verser à l'opérateur.

Caractéristiques du système :
- **Automatique** : exécution quotidienne à 01h00 UTC pour la journée précédente (J-1)
- **Idempotent** : une double exécution sur la même période ne crée pas de doublons
- **Multi-opérateur** : un batch par opérateur et par devise
- **Confirmable manuellement** : un agent Finance marque le batch comme `SETTLED` après virement bancaire
- **Déclenchable à la demande** : API pour exécuter un cycle hors planification

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        module-payment                            │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              DailySettlementJob                          │   │
│  │  @Scheduled(cron="${ebithex.settlement.cron:0 0 1 * * *}")│   │
│  │  Exécution quotidienne : J-1 00:00:00 → 23:59:59        │   │
│  └───────────────────────┬──────────────────────────────────┘   │
│                          │                                       │
│  ┌───────────────────────▼────────────────────────────────────┐ │
│  │                  SettlementService                         │ │
│  │                                                            │ │
│  │  runSettlementCycle(periodStart, periodEnd):               │ │
│  │  ┌─────────────────────────────────────────────────────┐  │ │
│  │  │ Pour chaque OperatorType:                           │  │ │
│  │  │   1. findSuccessForSettlement(op, from, to)         │  │ │
│  │  │   2. Grouper par devise                             │  │ │
│  │  │   3. Calculer gross / fee / net                     │  │ │
│  │  │   4. Idempotence → findByBatchReference(ref)        │  │ │
│  │  │   5. Créer SettlementBatch + SettlementEntry(s)     │  │ │
│  │  └─────────────────────────────────────────────────────┘  │ │
│  │                                                            │ │
│  │  markSettled(batchId):                                     │ │
│  │    PENDING / PROCESSING → SETTLED + settledAt = now()     │ │
│  └───────────────────────────────────────────────────────────┘ │
│                    │                    │                        │
│                    ▼                    ▼                        │
│  ┌──────────────────────┐  ┌───────────────────────┐           │
│  │ SettlementBatchRepo  │  │ SettlementEntryRepo   │           │
│  │ (settlement_batches) │  │ (settlement_entries)  │           │
│  └──────────────────────┘  └───────────────────────┘           │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │              SettlementController                        │   │
│  │  GET  /internal/settlement                               │   │
│  │  GET  /internal/settlement/{id}                          │   │
│  │  GET  /internal/settlement/{id}/entries                  │   │
│  │  POST /internal/settlement/run                           │   │
│  │  POST /internal/settlement/{id}/settle                   │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. Concept de batch de règlement

### 3.1 Définition

Un **batch de règlement** (`SettlementBatch`) représente l'ensemble des transactions traitées avec succès pour un opérateur donné, dans une devise donnée, sur une période donnée.

```
SettlementBatch
├── operator       : MPESA_KE
├── currency       : KES
├── periodStart    : 2026-03-17T00:00:00
├── periodEnd      : 2026-03-17T23:59:59
├── transactionCount : 847
├── grossAmount    : 4 235 000.00 KES   (somme des montants bruts)
├── feeAmount      :    84 700.00 KES   (somme des frais Ebithex, ~2 %)
├── netAmount      : 4 150 300.00 KES   (à reverser à l'opérateur)
└── status         : PENDING
```

### 3.2 Calcul des montants

```
grossAmount = Σ transaction.amount        (toutes les tx SUCCESS de la période)
feeAmount   = Σ transaction.feeAmount     (frais retenus par Ebithex)
netAmount   = grossAmount - feeAmount     (montant dû à l'opérateur)
```

### 3.3 Référence de batch

La référence de batch est un identifiant unique calculé de façon déterministe :

```
batchReference = "SET-{OPERATOR}-{CURRENCY}-{yyyyMMdd-HHmm}"

Exemple :
  SET-MPESAKE-KES-20260317-0000
  SET-MTNMOMOCI-XOF-20260317-0000
  SET-ORANGEMONEYCI-XOF-20260317-0000
```

Cette référence est soumise à une contrainte `UNIQUE` en base de données et sert de clé d'idempotence.

### 3.4 Lignes de règlement (Settlement Entries)

Chaque batch contient autant de `SettlementEntry` que de transactions incluses. Chaque ligne lie le batch à une transaction individuelle, permettant un audit complet :

```
SettlementEntry
├── batchId       : UUID du batch parent
├── transactionId : UUID de la transaction
├── entryType     : COLLECTION (paiement collecté) | DISBURSEMENT (décaissement)
├── amount        : montant de la transaction
├── feeAmount     : frais retenus
├── operator      : opérateur
└── currency      : devise
```

---

## 4. Cycle de règlement automatique

### 4.1 Planification

```
┌─────────────────────────────────────────────────────────────────┐
│  Chaque jour à 01:00 UTC :                                      │
│                                                                 │
│  periodStart = LocalDate.now().minusDays(1).atStartOfDay()      │
│             = 2026-03-17T00:00:00                               │
│                                                                 │
│  periodEnd   = periodStart.withHour(23).withMinute(59)          │
│             = 2026-03-17T23:59:59                               │
│                                                                 │
│  runSettlementCycle(periodStart, periodEnd)                     │
└─────────────────────────────────────────────────────────────────┘
```

L'expression cron est configurable via `ebithex.settlement.cron` (voir §8).

### 4.2 Déroulement d'un cycle

```
DailySettlementJob.runDailySettlement()
  │
  └── SettlementService.runSettlementCycle(J-1 00:00, J-1 23:59)
        │
        ├── Pour MTN_MOMO_CI :
        │     transactions = findSuccessForSettlement(MTN_MOMO_CI, from, to)
        │     → grouper par devise (ex. XOF)
        │     → ref = "SET-MTNMOMOCI-XOF-20260317-0000"
        │     → findByBatchReference(ref) → absent → créer batch + entries
        │
        ├── Pour ORANGE_MONEY_CI :
        │     → [même processus]
        │
        ├── Pour MPESA_KE :
        │     → [même processus]
        │
        └── [... pour chaque opérateur actif ...]

  Résultat : N batches créés (un par (opérateur, devise) ayant des tx SUCCESS)
```

### 4.3 Cycle de vie d'un batch

```
        runSettlementCycle()
               │
               ▼
         ┌───────────┐
         │  PENDING  │  (batch calculé, en attente de virement)
         └─────┬─────┘
               │
     ┌─────────┴──────────┐
     │                    │
     ▼                    ▼
┌──────────────┐    POST /internal/settlement/{id}/settle
│  PROCESSING  │    (confirmation de virement en cours)
└──────┬───────┘          │
       │                  ▼
       └──────────► ┌───────────┐
                    │  SETTLED  │  (virement confirmé, règlement terminé)
                    └───────────┘

  (statut FAILED réservé aux incidents techniques)
```

---

## 5. Idempotence et gestion des doublons

### 5.1 Mécanisme

L'idempotence est garantie à deux niveaux :

1. **Vérification applicative** : avant de créer un batch, `SettlementService` vérifie si `findByBatchReference(ref).isPresent()`. Si oui, le batch est ignoré.

2. **Contrainte de base de données** : la colonne `batch_reference` porte une contrainte `UNIQUE`. En cas de race condition entre deux exécutions concurrentes, la base de données rejette le doublon avec une violation de contrainte.

### 5.2 Pourquoi la référence et non la période

La clé d'idempotence est la **batchReference** (ex. `SET-MPESAKE-KES-20260317-0000`) et non le couple `(operator, currency, periodStart)`. En effet, `periodStart` est un `LocalDateTime` incluant les millisecondes, qui peut varier légèrement entre deux exécutions d'un test ou d'un job. La batchReference est calculée avec une précision à la minute (`yyyyMMdd-HHmm`), ce qui la rend stable sur un même cycle.

### 5.3 Comportement en cas de double exécution

```
Premier appel runSettlementCycle(from, to) :
  → 3 batches créés (MTN_MOMO_CI/XOF, MPESA_KE/KES, ORANGE_MONEY_CI/XOF)

Deuxième appel runSettlementCycle(from, to) [même fenêtre] :
  → 0 batch créé (tous ignorés — batchReference déjà présente)
  → return 0
```

---

## 6. Référence API REST — Back-office

### Authentification

Tous les endpoints settlement sont des endpoints **back-office internes**.

```http
Authorization: Bearer <jwt-back-office>
```

---

### 6.1 Lister les batches de règlement

```http
GET /internal/settlement
Authorization: Bearer <jwt-finance>
```

**Paramètres de requête (tous optionnels)**

| Paramètre  | Type                  | Description                                         |
|------------|-----------------------|-----------------------------------------------------|
| `operator` | `OperatorType`        | Filtrer pour un opérateur spécifique                |
| `status`   | `SettlementBatchStatus` | Filtrer par statut (`PENDING`, `SETTLED`, …)      |
| `from`     | ISO 8601 datetime     | Date de début de période (periodStart ≥ from)       |
| `to`       | ISO 8601 datetime     | Date de fin de période (periodStart ≤ to)           |
| `page`     | int                   | Numéro de page (défaut : 0)                         |
| `size`     | int                   | Taille de page (défaut : 20, max : 100)             |

**Exemple de requête**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "https://api.ebithex.io/api/internal/settlement?status=PENDING&page=0&size=50"
```

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d",
        "batchReference": "SET-MPESAKE-KES-20260317-0000",
        "operator": "MPESA_KE",
        "currency": "KES",
        "periodStart": "2026-03-17T00:00:00",
        "periodEnd": "2026-03-17T23:59:59",
        "transactionCount": 847,
        "grossAmount": 4235000.00,
        "feeAmount": 84700.00,
        "netAmount": 4150300.00,
        "status": "PENDING",
        "settledAt": null,
        "createdAt": "2026-03-18T01:00:12Z"
      }
    ],
    "totalElements": 5,
    "totalPages": 1,
    "page": 0,
    "size": 50
  }
}
```

---

### 6.2 Obtenir le détail d'un batch

```http
GET /internal/settlement/{batchId}
Authorization: Bearer <jwt-finance>
```

**Réponse `200 OK`** : même structure qu'un élément du listing.

**Réponses d'erreur**

| Statut | Code d'erreur         | Condition           |
|--------|-----------------------|---------------------|
| 404    | `SETTLEMENT_NOT_FOUND`| Batch introuvable   |

---

### 6.3 Lister les lignes d'un batch

```http
GET /internal/settlement/{batchId}/entries
Authorization: Bearer <jwt-finance>
```

Retourne toutes les `SettlementEntry` du batch, soit une ligne par transaction incluse.

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": [
    {
      "id": "...",
      "batchId": "1a2b3c4d-...",
      "transactionId": "a1b2c3d4-...",
      "entryType": "COLLECTION",
      "amount": 5000.00,
      "feeAmount": 100.00,
      "operator": "MPESA_KE",
      "currency": "KES"
    },
    ...
  ]
}
```

---

### 6.4 Déclencher un cycle manuellement

```http
POST /internal/settlement/run
Authorization: Bearer <jwt-admin>
Content-Type: application/json

{
  "periodStart": "2026-03-15T00:00:00",
  "periodEnd": "2026-03-15T23:59:59"
}
```

Permet de relancer un cycle pour une période passée (reprise d'incident, période manquée).

**Corps de la requête**

| Champ         | Type              | Obligatoire | Description                     |
|---------------|-------------------|-------------|---------------------------------|
| `periodStart` | ISO 8601 datetime | ✅           | Début de la période à traiter   |
| `periodEnd`   | ISO 8601 datetime | ✅           | Fin de la période à traiter     |

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "batchesCreated": 4
  }
}
```

**Rôle requis** : `ADMIN`, `SUPER_ADMIN` (pas `FINANCE` seul)

---

### 6.5 Confirmer un règlement (PENDING → SETTLED)

```http
POST /internal/settlement/{batchId}/settle
Authorization: Bearer <jwt-finance>
```

À appeler après confirmation de réception du virement par l'opérateur.

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "id": "1a2b3c4d-...",
    "batchReference": "SET-MPESAKE-KES-20260317-0000",
    "status": "SETTLED",
    "settledAt": "2026-03-18T11:30:00Z"
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur               | Condition                                                      |
|--------|-----------------------------|----------------------------------------------------------------|
| 400    | `SETTLEMENT_INVALID_STATUS` | Le batch n'est pas en statut PENDING ou PROCESSING             |
| 404    | `SETTLEMENT_NOT_FOUND`      | Batch introuvable                                              |

---

## 7. Schéma de base de données

### Table : `settlement_batches`

| Colonne             | Type          | Contraintes                          | Description                                           |
|---------------------|---------------|--------------------------------------|-------------------------------------------------------|
| `id`                | UUID          | PK, DEFAULT gen_random_uuid()        | Identifiant du batch                                  |
| `batch_reference`   | VARCHAR(100)  | NOT NULL, UNIQUE                     | Référence calculée — clé d'idempotence                |
| `operator`          | VARCHAR(50)   | NOT NULL                             | Code opérateur (valeur de `OperatorType`)             |
| `currency`          | VARCHAR(10)   | NOT NULL                             | Devise du batch (ex. `XOF`, `KES`, `NGN`)            |
| `period_start`      | TIMESTAMP     | NOT NULL                             | Début de la période couverte                          |
| `period_end`        | TIMESTAMP     | NOT NULL                             | Fin de la période couverte                            |
| `transaction_count` | INTEGER       | NOT NULL, CHECK (≥ 0)               | Nombre de transactions incluses                       |
| `gross_amount`      | DECIMAL(19,4) | NOT NULL, CHECK (≥ 0)               | Somme des montants bruts collectés                    |
| `fee_amount`        | DECIMAL(19,4) | NOT NULL, CHECK (≥ 0)               | Somme des frais Ebithex retenus                       |
| `net_amount`        | DECIMAL(19,4) | NOT NULL                             | Montant net dû à l'opérateur (gross - fee)           |
| `status`            | VARCHAR(20)   | NOT NULL, DEFAULT 'PENDING'          | `PENDING` \| `PROCESSING` \| `SETTLED` \| `FAILED`   |
| `settled_at`        | TIMESTAMPTZ   |                                      | Horodatage de confirmation du règlement               |
| `created_at`        | TIMESTAMPTZ   | NOT NULL, DEFAULT NOW()              | Horodatage de création automatique du batch           |

**Index**

| Nom de l'index               | Colonnes                      | Utilité                                            |
|------------------------------|-------------------------------|----------------------------------------------------|
| `idx_settlement_operator`    | `operator, period_start`      | Retrouver les batches d'un opérateur par période   |
| `idx_settlement_status`      | `status`                      | Lister les batches en attente de règlement         |
| `idx_settlement_created`     | `created_at`                  | Tri et filtrage temporel                           |

---

### Table : `settlement_entries`

| Colonne          | Type          | Contraintes                   | Description                                           |
|------------------|---------------|-------------------------------|-------------------------------------------------------|
| `id`             | UUID          | PK, DEFAULT gen_random_uuid() | Identifiant de la ligne                               |
| `batch_id`       | UUID          | NOT NULL, FK → settlement_batches(id) | Batch parent                                 |
| `transaction_id` | UUID          | NOT NULL                      | Transaction incluse dans ce batch                     |
| `entry_type`     | VARCHAR(20)   | NOT NULL                      | `COLLECTION` \| `DISBURSEMENT`                        |
| `amount`         | DECIMAL(19,4) | NOT NULL                      | Montant de la transaction                             |
| `fee_amount`     | DECIMAL(19,4) | NOT NULL                      | Frais Ebithex retenus                                 |
| `operator`       | VARCHAR(50)   | NOT NULL                      | Opérateur de la transaction                           |
| `currency`       | VARCHAR(10)   | NOT NULL                      | Devise de la transaction                              |
| `created_at`     | TIMESTAMPTZ   | NOT NULL, DEFAULT NOW()       | Horodatage de création                                |

**Index**

| Nom de l'index              | Colonnes         | Utilité                                           |
|-----------------------------|------------------|---------------------------------------------------|
| `idx_settlement_entry_batch`| `batch_id`       | Lister toutes les lignes d'un batch               |
| `idx_settlement_entry_tx`   | `transaction_id` | Retrouver le batch d'une transaction              |

---

## 8. Référence de configuration

| Propriété                    | Défaut            | Description                                                  |
|------------------------------|-------------------|--------------------------------------------------------------|
| `ebithex.settlement.cron`    | `0 0 1 * * *`     | Expression cron du job quotidien (01:00 UTC chaque jour)     |

### Exemples de configuration

```properties
# Production : 01:00 UTC (recommandé)
ebithex.settlement.cron=0 0 1 * * *

# Staging : toutes les heures (pour les tests de non-régression)
ebithex.settlement.cron=0 0 * * * *

# Désactivé (exécution manuelle uniquement via l'API)
# Ne pas définir de cron — désactiver le scheduling Spring
spring.task.scheduling.enabled=false
```

> **Note** : l'heure 01:00 UTC est choisie pour s'exécuter après minuit UTC, en dehors du pic de trafic des marchés d'Afrique de l'Ouest (UTC+0) et d'Afrique de l'Est (UTC+3). Adapter selon les marchés actifs.

---

## 9. Modèle de sécurité

### 9.1 Contrôle d'accès

| Endpoint                                | Rôle(s) requis                     |
|-----------------------------------------|------------------------------------|
| `GET /internal/settlement`              | `FINANCE`, `ADMIN`, `SUPER_ADMIN`  |
| `GET /internal/settlement/{id}`         | `FINANCE`, `ADMIN`, `SUPER_ADMIN`  |
| `GET /internal/settlement/{id}/entries` | `FINANCE`, `ADMIN`, `SUPER_ADMIN`  |
| `POST /internal/settlement/run`         | `ADMIN`, `SUPER_ADMIN`             |
| `POST /internal/settlement/{id}/settle` | `FINANCE`, `ADMIN`, `SUPER_ADMIN`  |

Le déclenchement manuel d'un cycle (`/run`) est réservé aux administrateurs. La consultation et la confirmation (`/settle`) sont accessibles au rôle `FINANCE`.

### 9.2 Piste d'audit

Chaque batch de règlement constitue en lui-même une piste d'audit complète :
- `batchReference` : identifiant déterministe traçable dans les systèmes bancaires
- `createdAt` : horodatage de calcul du batch (automatique via `@CreationTimestamp`)
- `settledAt` : horodatage de confirmation de virement
- `SettlementEntry` : ligne par ligne, chaque transaction est tracée jusqu'au batch

### 9.3 Isolation

Les données de settlement sont **exclusivement internes** — aucun endpoint de settlement n'est exposé aux marchands. Un marchand ne peut pas connaître les montants consolidés reversés aux opérateurs.

---

## 10. Codes d'erreur

| Code d'erreur               | HTTP | Description                                                           |
|-----------------------------|------|-----------------------------------------------------------------------|
| `SETTLEMENT_NOT_FOUND`      | 404  | Batch de règlement introuvable pour l'identifiant fourni              |
| `SETTLEMENT_INVALID_STATUS` | 400  | Impossible de confirmer un batch qui n'est pas en statut PENDING ou PROCESSING |

---

## 11. Opérations manuelles et récupération d'incident

### 11.1 Période manquée (job non exécuté)

Si le job quotidien ne s'est pas exécuté (panne serveur, maintenance) :

```bash
# Relancer le règlement pour la journée manquée via l'API
curl -X POST \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"periodStart":"2026-03-16T00:00:00","periodEnd":"2026-03-16T23:59:59"}' \
  https://api.ebithex.io/api/internal/settlement/run
```

L'appel est idempotent : si des batches existent déjà pour cette période, ils sont ignorés et seuls les manquants sont créés.

### 11.2 Vérifier l'état d'un cycle de règlement

```bash
# Lister tous les batches PENDING du jour J-1
curl -H "Authorization: Bearer $FINANCE_TOKEN" \
  "https://api.ebithex.io/api/internal/settlement?status=PENDING&from=2026-03-17T00:00:00&to=2026-03-17T23:59:59"
```

### 11.3 Confirmer un règlement après virement bancaire

```bash
# Après réception de confirmation bancaire pour le batch MTN MoMo CI
BATCH_ID="1a2b3c4d-5e6f-7a8b-9c0d-1e2f3a4b5c6d"

curl -X POST \
  -H "Authorization: Bearer $FINANCE_TOKEN" \
  https://api.ebithex.io/api/internal/settlement/$BATCH_ID/settle
```

### 11.4 Réconciliation manuelle

Pour auditer les transactions incluses dans un batch :

```bash
# Lister les 100 premières lignes du batch
curl -H "Authorization: Bearer $FINANCE_TOKEN" \
  "https://api.ebithex.io/api/internal/settlement/$BATCH_ID/entries?page=0&size=100"
```

Les `transactionId` retournés peuvent être croisés avec les données opérateurs pour une réconciliation transaction par transaction.

### 11.5 Alerte Prometheus associée

L'alerte `EbithexSettlementBatchPending` se déclenche si des batches restent en statut `PENDING` plus de 48 heures après leur création. Cela signale soit une absence de confirmation Finance, soit un dysfonctionnement du job de règlement.

```yaml
# prometheus/alerting_rules.yml
- alert: EbithexSettlementBatchPending
  expr: ebithex_settlement_batches_pending > 0
  for: 48h
  labels:
    severity: warning
    team: finance
  annotations:
    summary: "Batch(es) de règlement en attente depuis plus de 48h"
```

---

*Dernière mise à jour : 18 mars 2026*
