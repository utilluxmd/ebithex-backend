# Ebithex — Documentation AML (Anti-Money Laundering)

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture](#2-architecture)
3. [Règles de détection](#3-règles-de-détection)
4. [Cycle de vie d'une alerte](#4-cycle-de-vie-dune-alerte)
5. [Intégration avec le flux de paiement](#5-intégration-avec-le-flux-de-paiement)
6. [Référence API REST — Alertes AML](#6-référence-api-rest--alertes-aml)
7. [Screening de sanctions](#7-screening-de-sanctions)
8. [Export SAR/CCF — Déclaration aux autorités](#8-export-sarccf--déclaration-aux-autorités)
9. [Schéma de base de données](#9-schéma-de-base-de-données)
10. [Référence de configuration](#10-référence-de-configuration)
11. [Modèle de sécurité](#11-modèle-de-sécurité)
12. [Codes d'erreur](#12-codes-derreur)
13. [Étendre les règles AML](#13-étendre-les-règles-aml)

---

## 1. Vue d'ensemble

Le module AML d'Ebithex implémente un système de détection de transactions suspectes conforme aux exigences LCB-FT (Lutte contre le Blanchiment de Capitaux et le Financement du Terrorisme) applicables aux marchés africains réglementés.

Le système applique six vérifications automatiques à chaque transaction de paiement réel (hors mode sandbox) :

- **Screening de sanctions** *(prioritaire)* : correspondance floue Jaro-Winkler contre les listes OFAC/ONU/UE/ECOWAS + contrôle des pays à haut risque
- **Vélocité horaire** : volume de transactions trop élevé sur une heure glissante
- **Vélocité quotidienne** : volume de transactions trop élevé sur 24 heures glissantes
- **Vélocité hebdomadaire** : volume de transactions trop élevé sur 7 jours glissants
- **Montant élevé** : transaction unitaire dépassant le seuil configuré
- **Fractionnement (Structuring)** : tentatives répétées de transactions juste sous le seuil de déclaration obligatoire

Chaque violation génère une `AmlAlert` consultable et révisionnable par l'équipe Conformité via l'API back-office. Une transaction déclenchant une alerte de sévérité `CRITICAL` est bloquée immédiatement.

> **Note** : les requêtes sandbox (`SandboxContextHolder.isSandbox() = true`) sont **exclues** du screening AML. Cela garantit que les environnements de développement et de test ne génèrent pas de faux positifs.

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        module-payment                            │
│                                                                  │
│  POST /v1/payments                                               │
│         │                                                        │
│         ▼                                                        │
│  ┌───────────────────┐                                           │
│  │   PaymentService  │                                           │
│  │  initiatePayment()│                                           │
│  └────────┬──────────┘                                           │
│           │  1. Créer & persister Transaction                    │
│           │  2. screen(transaction)  ◄─── dans la même @Transactional │
│           │  3. Appeler l'opérateur mobile                       │
│           │                                                      │
│           ▼                                                      │
│  ┌───────────────────────────────────┐                           │
│  │       AmlScreeningService         │                           │
│  │  @Transactional(MANDATORY)        │                           │
│  │                                   │                           │
│  │  screen(tx):                      │                           │
│  │  ├── if SandboxCtx → return       │                           │
│  │  ├── SANCTIONS (pays + nom fuzzy) │                           │
│  │  │     ├── score ≥ 0.95 ────────► CRITICAL → throw blocked  │
│  │  │     └── score ∈ [0.80,0.95[ ► HIGH → near-miss (continue)│
│  │  ├── VELOCITY_HOURLY              │                           │
│  │  ├── VELOCITY_DAILY               │                           │
│  │  ├── VELOCITY_WEEKLY              │                           │
│  │  ├── HIGH_AMOUNT                  │                           │
│  │  └── STRUCTURING                  │                           │
│  │       │                           │                           │
│  │       ├── severity < CRITICAL ──► AmlAlert (OPEN)            │
│  │       └── severity = CRITICAL ──► throw AmlBlockedException  │
│  └───────────────────────────────────┘                           │
│                    │                                             │
│                    ▼                                             │
│  ┌───────────────────────────────────┐                           │
│  │      AmlAlertRepository           │                           │
│  │  (table : aml_alerts)             │                           │
│  └───────────────────────────────────┘                           │
│                                                                  │
│  ┌───────────────────────────────────┐                           │
│  │        AmlController              │                           │
│  │  GET  /internal/aml/alerts        │                           │
│  │  GET  /internal/aml/alerts/{id}   │                           │
│  │  PUT  /internal/aml/alerts/{id}   │                           │
│  └───────────────────────────────────┘                           │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. Règles de détection

### 3.1 Tableau de synthèse

| Code règle        | Condition de déclenchement                                            | Sévérité  |
|-------------------|-----------------------------------------------------------------------|-----------|
| `VELOCITY_HOURLY` | Nombre de transactions (hors test) > seuil sur 1 heure glissante     | MEDIUM    |
| `VELOCITY_DAILY`  | Nombre de transactions (hors test) > seuil sur 24 heures glissantes  | HIGH      |
| `VELOCITY_WEEKLY` | Nombre de transactions (hors test) > seuil sur 7 jours glissants     | HIGH      |
| `HIGH_AMOUNT`     | Montant de la transaction > seuil de montant élevé                   | HIGH      |
| `STRUCTURING`     | ≥ 3 transactions entre 80 % et 100 % du seuil HIGH_AMOUNT sur 24 h  | HIGH      |

### 3.2 Vélocité horaire (`VELOCITY_HOURLY`)

```
Fenêtre : [ now() - 1h, now() ]
Comptage : nombre de transactions non-test du marchand dans la fenêtre

Si count > ebithex.aml.velocity.max-tx-per-hour (défaut : 20)
  → Créer AmlAlert(ruleCode=VELOCITY_HOURLY, severity=MEDIUM)
```

La règle s'applique **après** la persistance de la transaction courante ; le comptage l'inclut donc.

### 3.3 Vélocité quotidienne (`VELOCITY_DAILY`)

```
Fenêtre : [ now() - 24h, now() ]
Seuil   : ebithex.aml.velocity.max-tx-per-day (défaut : 100)
Sévérité : HIGH
```

### 3.4 Vélocité hebdomadaire (`VELOCITY_WEEKLY`)

```
Fenêtre : [ now() - 7j, now() ]
Seuil   : ebithex.aml.velocity.max-tx-per-week (défaut : 500)
Sévérité : HIGH
```

### 3.5 Montant élevé (`HIGH_AMOUNT`)

```
Condition : tx.amount > ebithex.aml.high-amount-threshold (défaut : 5 000 000)
Sévérité  : HIGH
```

Le seuil est exprimé dans la **devise naturelle de la transaction** (pas de conversion). Configurer un seuil différent par marché si nécessaire (fonctionnalité à venir).

### 3.6 Fractionnement (`STRUCTURING`)

Le fractionnement consiste à diviser délibérément un montant important en plusieurs transactions plus petites pour éviter le seuil de déclaration réglementaire.

```
Fenêtre : [ now() - 24h, now() ]
Zone    : [0.80 × threshold, threshold[  (80 % à 100 % du seuil HIGH_AMOUNT)
Condition : count ≥ 3 transactions dans cette zone
Sévérité  : HIGH
```

### 3.7 Escalade vers CRITICAL

Deux sources génèrent une alerte CRITICAL :

| Source | Condition | Code règle |
|---|---|---|
| **Pays à haut risque** | Code ISO du pays dans la liste `ebithex.sanctions.high-risk-countries` | `SANCTIONS_HIT` |
| **Correspondance de nom** | Score Jaro-Winkler ≥ `blockThreshold` (défaut 0.95) | `SANCTIONS_HIT` |

Une alerte CRITICAL provoque le lancement d'une `EbithexException(AML_BLOCKED)` qui interrompt immédiatement le flux de paiement (HTTP 422). La transaction n'est **pas** persistée.

---

## 4. Cycle de vie d'une alerte

### 4.1 Diagramme d'état

```
                         Screening automatique
                                 │
                                 ▼
                         ┌──────────────┐
                         │     OPEN     │  (alerte créée, non traitée)
                         └──────┬───────┘
                                │
               ┌────────────────┴──────────────────┐
               │                                   │
               ▼                                   ▼
   PUT /internal/aml/alerts/{id}      (examen en cours)
     { status: CLEARED }            { status: UNDER_REVIEW }
               │                                   │
               ▼                          ┌────────┴────────┐
        ┌──────────┐                      ▼                 ▼
        │ CLEARED  │               ┌──────────┐      ┌──────────┐
        │ (faux +) │               │ CLEARED  │      │ REPORTED │
        └──────────┘               └──────────┘      └──────────┘
                                  (faux positif)   (transmis aux autorités)
```

### 4.2 Description des statuts

| Statut         | Description                                                              |
|----------------|--------------------------------------------------------------------------|
| `OPEN`         | Alerte créée automatiquement, en attente de traitement par la conformité |
| `UNDER_REVIEW` | Prise en charge par un agent de conformité                               |
| `CLEARED`      | Clôturée comme faux positif — aucune action réglementaire requise        |
| `REPORTED`     | Transaction transmise aux autorités réglementaires compétentes           |

### 4.3 Champs enrichis lors de la révision

| Champ              | Description                                         |
|--------------------|-----------------------------------------------------|
| `reviewedAt`       | Horodatage de la révision (automatiquement défini)  |
| `reviewedBy`       | Email de l'agent de conformité (depuis le JWT)      |
| `resolutionNote`   | Justification textuelle libre                       |

---

## 5. Intégration avec le flux de paiement

`AmlScreeningService.screen()` est annoté `@Transactional(propagation = Propagation.MANDATORY)` : il **doit** être appelé dans une transaction active. `PaymentService.initiatePayment()` étant déjà `@Transactional`, les alertes AML sont persistées dans la **même transaction** que la transaction de paiement. En cas de rollback du paiement, les alertes sont également annulées.

```
PaymentService.initiatePayment()  [@Transactional]
  │
  ├── 1. Valider la requête
  ├── 2. Calculer les frais
  ├── 3. transactionRepository.save(tx)      ← transaction persistée
  ├── 4. amlScreeningService.screen(tx)      ← screening dans la même tx
  │         └── si CRITICAL → throw AmlBlockedException
  │              └── rollback de toute la transaction
  └── 5. operatorGateway.initiatePayment()   ← appel opérateur
```

Le marchand reçoit une erreur `AML_BLOCKED` (HTTP 422) si une règle CRITICAL est déclenchée. La transaction n'est **pas** persistée.

---

## 6. Référence API REST — Alertes AML

### Authentification

Tous les endpoints AML sont des endpoints **back-office internes**. Ils nécessitent un JWT avec un rôle autorisé :

```http
Authorization: Bearer <jwt-back-office>
```

---

### 6.1 Lister les alertes AML

```http
GET /internal/aml/alerts
Authorization: Bearer <jwt-compliance>
```

**Paramètres de requête (tous optionnels)**

| Paramètre    | Type              | Description                                            |
|--------------|-------------------|--------------------------------------------------------|
| `status`     | `AmlStatus`       | Filtrer par statut (`OPEN`, `CLEARED`, `REPORTED`, …)  |
| `merchantId` | UUID              | Filtrer pour un marchand spécifique                    |
| `from`       | ISO 8601 datetime | Date de début (createdAt ≥ from)                       |
| `to`         | ISO 8601 datetime | Date de fin (createdAt ≤ to)                           |
| `page`       | int               | Numéro de page (défaut : 0)                            |
| `size`       | int               | Taille de page (défaut : 20, max : 100)                |

**Exemple de requête**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "https://api.ebithex.io/api/internal/aml/alerts?status=OPEN&page=0&size=20"
```

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        "merchantId": "550e8400-e29b-41d4-a716-446655440000",
        "transactionId": "a4c2e1b0-...",
        "ruleCode": "VELOCITY_DAILY",
        "severity": "HIGH",
        "status": "OPEN",
        "details": "Vélocité quotidienne dépassée : 105 transactions sur les dernières 24h (seuil : 100)",
        "amount": 25000.00,
        "currency": "XOF",
        "createdAt": "2026-03-18T09:15:32Z",
        "reviewedAt": null,
        "reviewedBy": null,
        "resolutionNote": null
      }
    ],
    "totalElements": 1,
    "totalPages": 1,
    "page": 0,
    "size": 20
  }
}
```

---

### 6.2 Obtenir une alerte par identifiant

```http
GET /internal/aml/alerts/{alertId}
Authorization: Bearer <jwt-compliance>
```

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "merchantId": "550e8400-...",
    "ruleCode": "HIGH_AMOUNT",
    "severity": "HIGH",
    "status": "OPEN",
    "details": "Montant de la transaction (6 500 000 XOF) dépasse le seuil (5 000 000 XOF)",
    "amount": 6500000.00,
    "currency": "XOF",
    "createdAt": "2026-03-18T08:00:00Z",
    "reviewedAt": null,
    "reviewedBy": null,
    "resolutionNote": null
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur       | Condition              |
|--------|---------------------|------------------------|
| 404    | `AML_ALERT_NOT_FOUND` | Identifiant inconnu  |

---

### 6.3 Réviser une alerte (CLEARED ou REPORTED)

```http
PUT /internal/aml/alerts/{alertId}
Authorization: Bearer <jwt-compliance>
Content-Type: application/json

{
  "status": "CLEARED",
  "resolutionNote": "Marchand vérifié — pic de vélocité dû à une promotion commerciale autorisée"
}
```

**Corps de la requête**

| Champ            | Type        | Obligatoire | Description                                              |
|------------------|-------------|-------------|----------------------------------------------------------|
| `status`         | `AmlStatus` | ✅           | `CLEARED` ou `REPORTED` uniquement                      |
| `resolutionNote` | chaîne      | ✅           | Justification de la décision (archivée pour l'audit)    |

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-...",
    "status": "CLEARED",
    "resolutionNote": "Marchand vérifié — pic de vélocité dû à une promotion commerciale autorisée",
    "reviewedAt": "2026-03-18T10:30:00Z",
    "reviewedBy": "compliance@ebithex.io"
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur         | Condition                                          |
|--------|-----------------------|----------------------------------------------------|
| 400    | `AML_INVALID_STATUS`  | Statut cible non autorisé (pas CLEARED ni REPORTED)|
| 404    | `AML_ALERT_NOT_FOUND` | Alerte introuvable                                 |

---

---

## 7. Screening de sanctions

### 7.1 Vue d'ensemble

Le screening de sanctions est la **première vérification** exécutée par `AmlScreeningService` pour chaque paiement (avant les règles de vélocité).

Deux contrôles indépendants sont réalisés :

| Contrôle | Source | Méthode | Action |
|---|---|---|---|
| **Pays à haut risque** | Liste ISO configurable | Comparaison exacte | CRITICAL → blocage |
| **Correspondance de nom** | Table `sanctions_entries` | Fuzzy Jaro-Winkler | Dépend du score (voir §7.2) |

### 7.2 Algorithme de matching — Jaro-Winkler

Le matching n'est **plus basé sur `contains()`** mais sur la similarité **Jaro-Winkler**, l'algorithme de référence dans les systèmes KYC/AML (World-Check, LexisNexis, Dow Jones Risk).

**Avantages vs `contains()` :**
- Tolère les translittérations : `KADHAFI` ↔ `GADDAFI` (score ≈ 0.947 — near-miss)
- Tolère les variantes orthographiques : `OSAMA` ↔ `USAMA` (score ≈ 0.956 — blocage)
- Réduit les faux positifs : `Ali` ne matche plus n'importe qui avec "ali" dans le nom

**Normalisation préalable** :
1. Conversion en majuscules
2. Suppression des diacritiques via décomposition NFD (`Kâdhâfî` → `KADHAFI`)
3. Suppression de la ponctuation
4. Collapse des espaces multiples

**Trois zones de résultat** :

| Score | Zone | RuleCode | Sévérité | Action |
|---|---|---|---|---|
| ≥ `blockThreshold` (défaut **0.95**) | Certitude | `SANCTIONS_HIT` | CRITICAL | Transaction **bloquée** |
| ≥ `reviewThreshold` (défaut **0.80**) | Near-miss | `SANCTIONS_NEAR_MISS` | HIGH | Transaction **poursuivie**, alerte créée |
| < `reviewThreshold` | Pas de match | — | — | Rien |

Exemples concrets :

| Nom soumis | Nom en liste | Score | Zone |
|---|---|---|---|
| `USAMA BIN LADEN` | `OSAMA BIN LADEN` | ~0.956 | BLOCAGE |
| `MUAMMAR GADDAFI` | `MUAMMAR KADHAFI` | ~0.947 | NEAR-MISS |
| `KOUASSI JEAN` | `OSAMA BIN LADEN` | ~0.61 | Pas de match |

### 7.3 Sources des listes

| Liste | Nom interne | Source officielle | Fréquence de sync |
|---|---|---|---|
| OFAC SDN | `OFAC_SDN` | `treasury.gov/ofac/downloads/sdn.xml` | Hebdomadaire (auto) |
| ONU Consolidée | `UN_CONSOLIDATED` | `scsanctions.un.org/resources/xml/en/consolidated.xml` | Hebdomadaire (auto) |
| UE Consolidée | `EU_CONSOLIDATED` | `webgate.ec.europa.eu/fsd/fsf/public/files/xmlFullSanctionsList_1_1/content` | Hebdomadaire (auto) |
| ECOWAS Local | `ECOWAS_LOCAL` | BCEAO / manuelle | Import CSV manuel |
| Personnalisée | `CUSTOM` | Interne | Import CSV manuel |

### 7.4 Synchronisation automatique (`SanctionsListSyncJob`)

Un job Spring Scheduling déclenche la synchronisation chaque **dimanche à 05:00 UTC** :

```
SanctionsListSyncJob.runWeeklySync()
  ├── syncService.syncAll()
  │     ├── downloadContent(ofacUrl)    → parseOfac()   → persistEntries("OFAC_SDN")
  │     ├── downloadContent(unUrl)      → parseUn()     → persistEntries("UN_CONSOLIDATED")
  │     └── downloadContent(euUrl)      → parseEu()     → persistEntries("EU_CONSOLIDATED")
  └── Log résumé : N/3 listes OK, M entrées importées
```

Chaque synchronisation :
1. Télécharge le fichier XML source (timeout configurable)
2. Supprime **intégralement** les anciennes entrées de la liste (`DELETE WHERE list_name = ?`)
3. Insère les nouvelles entrées par batches de 500 (configurable)
4. Enregistre un log dans `sanctions_sync_log` (`SUCCESS | FAILED | PARTIAL`)

### 7.5 Format CSV pour les listes manuelles

Format attendu pour `POST /internal/sanctions/import/{listName}` :

```csv
# Commentaire (ligne ignorée)
entityName,aliases,countryCode,entityType
"KONAN AMARA","AMARA KONAN|K. AMARA",CI,INDIVIDUAL
"SOCIÉTÉ FANTÔME",,NG,ENTITY
```

| Champ | Obligatoire | Format |
|---|---|---|
| `entityName` | ✅ | Texte libre, guillemets optionnels |
| `aliases` | ✗ | Valeurs séparées par `\|`, guillemets optionnels |
| `countryCode` | ✗ | Code ISO 3166-1 alpha-2 (ex. `CI`, `NG`) |
| `entityType` | ✗ | `INDIVIDUAL \| ENTITY \| VESSEL \| AIRCRAFT` |

### 7.6 API back-office des sanctions

| Méthode | Endpoint | Description |
|---|---|---|
| `GET` | `/internal/sanctions/entries` | Lister les entrées actives (toutes listes) |
| `POST` | `/internal/sanctions/check` | Vérifier un nom ou un pays |
| `DELETE` | `/internal/sanctions/entries/{listName}` | Purger une liste |
| `POST` | `/internal/sanctions/sync` | Synchroniser les 3 listes automatiques |
| `POST` | `/internal/sanctions/sync/{listName}` | Synchroniser une liste spécifique |
| `GET` | `/internal/sanctions/sync/status` | Dernier log par liste |
| `GET` | `/internal/sanctions/sync/history` | Historique récent (param `?limit=20`) |
| `POST` | `/internal/sanctions/import/{listName}` | Importer depuis un CSV |

**Rôles requis** : `COMPLIANCE`, `ADMIN`, `SUPER_ADMIN`

### 7.7 Exemple : vérification manuelle

```bash
curl -X POST https://api.ebithex.io/api/internal/sanctions/check \
  -H "Authorization: Bearer $BACKOFFICE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "Konan Amara", "countryCode": "IR"}'
```

Réponse (pays à haut risque) :
```json
{
  "success": true,
  "data": {
    "hit": true,
    "requiresBlock": true,
    "score": 0.0,
    "matchedList": "",
    "matchedEntity": "",
    "reason": "Pays à haut risque : IR"
  }
}
```

Réponse (correspondance de nom near-miss) :
```json
{
  "success": true,
  "data": {
    "hit": true,
    "requiresBlock": false,
    "score": 0.947,
    "matchedList": "OFAC_SDN",
    "matchedEntity": "MUAMMAR KADHAFI",
    "reason": "Correspondance floue : score=0.947 (near-miss)"
  }
}
```

### 7.8 Statut de synchronisation

```bash
curl https://api.ebithex.io/api/internal/sanctions/sync/status \
  -H "Authorization: Bearer $BACKOFFICE_TOKEN"
```

Réponse :
```json
{
  "success": true,
  "data": {
    "OFAC_SDN": {
      "listName": "OFAC_SDN",
      "syncedAt": "2026-03-23T05:00:12",
      "status": "SUCCESS",
      "entriesImported": 8943,
      "durationMs": 47230
    },
    "UN_CONSOLIDATED": {
      "listName": "UN_CONSOLIDATED",
      "syncedAt": "2026-03-23T05:01:04",
      "status": "SUCCESS",
      "entriesImported": 632
    }
  }
}
```

---

## 8. Schéma de base de données

### Table : `aml_alerts`



| Colonne           | Type          | Contraintes                   | Description                                               |
|-------------------|---------------|-------------------------------|-----------------------------------------------------------|
| `id`              | UUID          | PK, DEFAULT gen_random_uuid() | Identifiant de l'alerte                                   |
| `merchant_id`     | UUID          | NOT NULL                      | Marchand concerné                                         |
| `transaction_id`  | UUID          |                               | Transaction ayant déclenché l'alerte (NULL si manuelle)   |
| `rule_code`       | VARCHAR(50)   | NOT NULL                      | Code de la règle déclenchée (ex. `VELOCITY_DAILY`)        |
| `severity`        | VARCHAR(20)   | NOT NULL                      | `LOW` \| `MEDIUM` \| `HIGH` \| `CRITICAL`                 |
| `status`          | VARCHAR(20)   | NOT NULL, DEFAULT 'OPEN'      | `OPEN` \| `UNDER_REVIEW` \| `CLEARED` \| `REPORTED`      |
| `details`         | TEXT          | NOT NULL                      | Description textuelle de la violation détectée            |
| `amount`          | DECIMAL(19,4) |                               | Montant de la transaction concernée                       |
| `currency`        | VARCHAR(10)   |                               | Devise (ex. `XOF`, `KES`, `NGN`)                         |
| `created_at`      | TIMESTAMPTZ   | NOT NULL, DEFAULT NOW()       | Horodatage de création automatique                        |
| `reviewed_at`     | TIMESTAMPTZ   |                               | Horodatage de la décision de révision                     |
| `reviewed_by`     | VARCHAR(255)  |                               | Email de l'agent de conformité                            |
| `resolution_note` | TEXT          |                               | Justification de la décision                              |

**Index**

| Nom de l'index             | Colonnes                        | Utilité                                           |
|----------------------------|---------------------------------|---------------------------------------------------|
| `idx_aml_merchant_status`  | `merchant_id, status`           | Filtrer les alertes ouvertes d'un marchand        |
| `idx_aml_created_at`       | `created_at`                    | Requêtes par fenêtre temporelle                   |
| `idx_aml_rule_code`        | `rule_code`                     | Statistiques par type de règle                    |

---

### Table : `sanctions_sync_log`

| Colonne            | Type         | Description                                                     |
|--------------------|--------------|-----------------------------------------------------------------|
| `id`               | UUID         | PK                                                              |
| `list_name`        | VARCHAR(30)  | `OFAC_SDN \| UN_CONSOLIDATED \| EU_CONSOLIDATED \| ECOWAS_LOCAL \| CUSTOM` |
| `synced_at`        | TIMESTAMP    | Horodatage de la synchronisation                                |
| `status`           | VARCHAR(20)  | `SUCCESS \| FAILED \| PARTIAL`                                  |
| `entries_imported` | INT          | Nombre d'entrées importées (0 si échec)                         |
| `error_message`    | TEXT         | Message d'erreur (null si succès)                               |
| `duration_ms`      | BIGINT       | Durée de la synchronisation en ms                               |

---

## 9. Référence de configuration

| Propriété                                    | Défaut      | Description                                                |
|----------------------------------------------|-------------|------------------------------------------------------------|
| `ebithex.aml.velocity.max-tx-per-hour`       | `20`        | Seuil de vélocité horaire (nombre de transactions)         |
| `ebithex.aml.velocity.max-tx-per-day`        | `100`       | Seuil de vélocité quotidienne                              |
| `ebithex.aml.velocity.max-tx-per-week`       | `500`       | Seuil de vélocité hebdomadaire                             |
| `ebithex.aml.high-amount-threshold`          | `5000000`   | Seuil de montant élevé (dans la devise de la transaction)  |

### Exemple de configuration production (Côte d'Ivoire / XOF)

```properties
# Seuils conformes à la réglementation BCEAO
ebithex.aml.velocity.max-tx-per-hour=10
ebithex.aml.velocity.max-tx-per-day=50
ebithex.aml.velocity.max-tx-per-week=200
ebithex.aml.high-amount-threshold=3000000
```

### Exemple de configuration production (Kenya / KES)

```properties
# Seuils conformes à la réglementation CBK
ebithex.aml.velocity.max-tx-per-hour=30
ebithex.aml.velocity.max-tx-per-day=150
ebithex.aml.velocity.max-tx-per-week=800
ebithex.aml.high-amount-threshold=500000
```

---

### Configuration du fuzzy matching

| Propriété | Défaut | Description |
|---|---|---|
| `ebithex.sanctions.match-threshold-block` | `0.95` | Score Jaro-Winkler minimum pour bloquer (CRITICAL) |
| `ebithex.sanctions.match-threshold-review` | `0.80` | Score minimum pour créer une alerte de révision (HIGH) |

**Calibrage recommandé** :
- Défaut (`block=0.95`, `review=0.80`) : équilibre faux positifs / faux négatifs — validé sur les paires connues OFAC (USAMA/OSAMA → 0.956 ≥ 0.95 = blocage ; GADDAFI/KADHAFI → 0.947 ∈ [0.80, 0.95[ = near-miss)
- Si trop de blocages légitimes : augmenter `block` vers 0.97
- Si sous-détection : abaisser `block` vers 0.92 et `review` vers 0.75
- Après audit BCEAO/FATF : ajuster selon les retours de l'équipe Conformité

---

### Configuration de la synchronisation des sanctions

| Propriété | Défaut | Description |
|---|---|---|
| `ebithex.sanctions.sync.enabled` | `true` | Active/désactive le job hebdomadaire |
| `ebithex.sanctions.sync.cron` | `0 0 5 * * SUN` | Cron de synchronisation (dimanche 05:00 UTC) |
| `ebithex.sanctions.sync.timeout-seconds` | `60` | Timeout HTTP pour le téléchargement |
| `ebithex.sanctions.sync.batch-size` | `500` | Taille de batch pour l'insertion en base |
| `ebithex.sanctions.sync.ofac-url` | URL treasury.gov | URL du fichier OFAC SDN |
| `ebithex.sanctions.sync.un-url` | URL scsanctions.un.org | URL de la liste ONU |
| `ebithex.sanctions.sync.eu-url` | URL webgate.ec.europa.eu | URL de la liste UE |

---

## 10. Modèle de sécurité

### 9.1 Contrôle d'accès

| Endpoint                              | Rôle(s) requis                          |
|---------------------------------------|-----------------------------------------|
| `GET /internal/aml/alerts`            | `COMPLIANCE`, `ADMIN`, `SUPER_ADMIN`    |
| `GET /internal/aml/alerts/{id}`       | `COMPLIANCE`, `ADMIN`, `SUPER_ADMIN`    |
| `PUT /internal/aml/alerts/{id}`       | `COMPLIANCE`, `ADMIN`, `SUPER_ADMIN`    |

Le rôle `COMPLIANCE` est un rôle back-office dédié aux équipes de conformité réglementaire. Il donne accès exclusivement aux endpoints AML — pas aux opérations administratives générales.

### 9.2 Piste d'audit

Chaque décision de révision est traçable :
- `reviewedBy` contient l'email de l'agent issu du JWT (jamais saisissable manuellement)
- `reviewedAt` est défini par le serveur au moment de la révision
- `resolutionNote` est obligatoire pour toute décision de révision

### 9.3 Isolation des données

Les alertes AML sont des données internes. Elles ne sont **jamais exposées** aux marchands via les API publiques. Un marchand ne peut pas savoir si une de ses transactions a déclenché une alerte AML.

---

## 11. Codes d'erreur

| Code d'erreur         | HTTP | Description                                                        |
|-----------------------|------|--------------------------------------------------------------------|
| `AML_BLOCKED`         | 422  | Transaction bloquée par une règle AML de sévérité CRITICAL        |
| `AML_ALERT_NOT_FOUND` | 404  | Alerte AML introuvable pour l'identifiant fourni                   |
| `AML_INVALID_STATUS`  | 400  | Statut cible non autorisé pour la révision (doit être CLEARED ou REPORTED) |

---

## 12. Étendre les règles AML

Pour ajouter une nouvelle règle de détection :

1. **Ajouter le code de règle** dans `AmlScreeningService` — créer une méthode privée `checkMaRegle(Transaction tx)` qui retourne un `Optional<AmlAlert>`.

2. **Appeler la méthode** depuis `screen()` :

```java
checkMaRegle(tx).ifPresent(alerts::add);
```

3. **Configurer le seuil** via une propriété `@Value` :

```java
@Value("${ebithex.aml.ma-regle.seuil:valeur_defaut}")
private int seuilMaRegle;
```

4. **Ajouter la propriété** dans `application.properties` :

```properties
ebithex.aml.ma-regle.seuil=42
```

5. **Écrire un test d'intégration** dans `AmlIntegrationTest` couvrant les cas : déclenchement, non-déclenchement, et sévérité attendue.

> **Important** : toujours documenter la base réglementaire justifiant l'ajout d'une règle (article de loi, circulaire, directive BCEAO/CBK/CBN, etc.) dans le commentaire Javadoc de la méthode.

---

## 8. Export SAR/CCF — Déclaration aux autorités

### Vue d'ensemble

L'export SAR (Suspicious Activity Report) / CCF (Cellule de Conformité et Filtrage) permet à l'équipe Conformité de générer un fichier CSV des alertes HIGH et CRITICAL à transmettre aux autorités financières (CENTIF, BCEAO/UEMOA) dans le cadre des obligations déclaratives LCB-FT.

### Périmètre de l'export

- **Sévérités incluses** : HIGH et CRITICAL uniquement
- **Statuts inclus** : OPEN et UNDER_REVIEW (les alertes CLEARED/REPORTED sont exclues pour éviter les doublons)
- **Fenêtre temporelle** : configurable (`from` / `to`), défaut = 30 derniers jours

### Endpoints

| Méthode | Endpoint | Rôles | Description |
|---------|----------|-------|-------------|
| `GET` | `/internal/aml/alerts/export/sar` | `COMPLIANCE` | Télécharge le CSV |
| `GET` | `/internal/aml/alerts/export/sar/preview` | `COMPLIANCE`, `SUPER_ADMIN` | Aperçu JSON (compte d'alertes) |
| `POST` | `/internal/aml/alerts/export/sar/mark-reported` | `COMPLIANCE` | Marque les alertes comme REPORTED |

### Flux de déclaration recommandé

```
1. GET /preview         → vérifier le nombre d'alertes à déclarer
2. GET /export/sar      → télécharger le CSV (attachment)
3. Transmettre le CSV aux autorités compétentes
4. POST /mark-reported  → marquer comme REPORTED (irréversible)
```

> **Attention** : l'opération `mark-reported` est **irréversible**. Elle doit être exécutée uniquement après confirmation de la transmission officielle aux autorités.

### Format CSV

Le fichier suit le standard RFC 4180 (séparateur virgule, guillemets doubles pour l'échappement) et est encodé en UTF-8 avec BOM pour compatibilité Excel.

Colonnes exportées :

| Colonne | Description |
|---------|-------------|
| `alert_id` | UUID de l'alerte |
| `merchant_id` | UUID du marchand concerné |
| `transaction_id` | UUID de la transaction déclenchante |
| `rule_code` | Règle AML déclenchée (ex: `HIGH_AMOUNT`) |
| `severity` | HIGH ou CRITICAL |
| `status` | OPEN ou UNDER_REVIEW |
| `amount` | Montant de la transaction |
| `currency` | Devise (ex: XOF) |
| `details` | Description du déclenchement |
| `created_at` | Date/heure de création de l'alerte (ISO 8601) |

### Exemple d'aperçu JSON (preview)

```json
{
  "totalAlerts": 12,
  "criticalAlerts": 3,
  "highAlerts": 9,
  "from": "2026-03-01T00:00:00",
  "to": "2026-03-31T23:59:59"
}
```

### Exemple de requête curl

```bash
# Aperçu
curl -H "Authorization: Bearer $TOKEN" \
  "https://api.ebithex.com/internal/aml/alerts/export/sar/preview?from=2026-03-01T00:00:00&to=2026-03-31T23:59:59"

# Export CSV
curl -H "Authorization: Bearer $TOKEN" \
  "https://api.ebithex.com/internal/aml/alerts/export/sar?from=2026-03-01T00:00:00" \
  -o sar_export.csv

# Marquer comme déclaré
curl -X POST -H "Authorization: Bearer $TOKEN" \
  "https://api.ebithex.com/internal/aml/alerts/export/sar/mark-reported?from=2026-03-01T00:00:00"
```

---

*Dernière mise à jour : 5 avril 2026*