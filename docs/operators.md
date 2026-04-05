# Ebithex — Documentation Opérateurs Mobile Money

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture des adaptateurs](#2-architecture-des-adaptateurs)
3. [Remboursement via opérateur (Reversal)](#3-remboursement-via-opérateur-reversal)
4. [Réconciliation automatisée](#4-réconciliation-automatisée)
5. [Format du relevé CSV](#5-format-du-relevé-csv)
6. [Types d'anomalies détectées](#6-types-danomalies-détectées)
7. [Référence API REST](#7-référence-api-rest)
8. [Schéma de base de données](#8-schéma-de-base-de-données)
9. [Référence de configuration](#9-référence-de-configuration)
10. [Matrice de support opérateur](#10-matrice-de-support-opérateur)
11. [Codes d'erreur](#11-codes-derreur)
12. [Runbook — Anomalies de réconciliation](#12-runbook--anomalies-de-réconciliation)

---

## 1. Vue d'ensemble

Ebithex s'interfaçe avec les opérateurs Mobile Money via le pattern **Port/Adapter** : chaque opérateur est un bean Spring qui implémente l'interface `MobileMoneyOperator`. Tous les appels sortants transitent par l'`OperatorGateway`, qui applique les **circuit breakers Resilience4j**.

Deux processus automatisés opèrent sur ces données après le traitement initial :

| Processus | Déclenchement | Rôle |
|-----------|--------------|------|
| **Reversal** | À la demande (refund marchand) | Rembourser le client sur son portefeuille mobile |
| **Réconciliation** | Chaque nuit à 02:30 UTC | Comparer les relevés opérateurs avec nos transactions |

---

## 2. Architecture des adaptateurs

```
                    PaymentService / PayoutService
                              │
                              ▼
                       OperatorGateway          ← Spring-managed, circuit breakers
                              │
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
        OperatorRegistry  (dispatch par OperatorType)
              │
    ┌─────────┴──────────────────────────────────────────┐
    │         │             │             │              │
    ▼         ▼             ▼             ▼              ▼
MtnMomo   OrangeMoney   WaveOperator  MPesaKe  OrangeMoneySn
CiOperator CiOperator                           ...
    │
    ▼
WebClient.block()  →  API Opérateur (REST/SOAP)
```

Chaque adapter implémente `MobileMoneyOperator` :

```java
public interface MobileMoneyOperator {
    OperatorType     getOperatorType();
    OperatorInitResponse initiatePayment(OperatorPaymentRequest request);
    TransactionStatus    checkStatus(String operatorReference);

    // Optionnel — surcharger si supporté
    default boolean          supportsReversal()                        { return false; }
    default OperatorRefundResult reversePayment(String ref, BigDecimal amount, String currency) {
        throw new UnsupportedOperationException(...);
    }
    default OperatorInitResponse initiateDisbursement(...)            { throw UOE; }
    default TransactionStatus    checkDisbursementStatus(String ref)  { return checkStatus(ref); }
}
```

**Circuit breakers configurés :**
- `operator-payment` — collection, reversal
- `operator-disbursement` — décaissements

---

## 3. Remboursement via opérateur (Reversal)

### 3.1 Principe

Lorsqu'un marchand demande un remboursement (`POST /v1/payments/{reference}/refund`), Ebithex exécute **deux actions dans cet ordre** :

```
1. Reversal opérateur (si supporté)
   └─ Crédite le portefeuille mobile du client

2. Débit wallet marchand
   └─ Déduit le montant net du solde Ebithex du marchand

3. Statut transaction → REFUNDED
4. Événement PaymentStatusChangedEvent(REFUNDED) → webhook + email
```

### 3.2 Comportement selon le support opérateur

```
supportsReversal() == false (défaut)
  └─ Step 1 ignoré → remboursement comptable uniquement
  └─ Log INFO : "Reversal non supporté par XXX — remboursement comptable uniquement"
  └─ Le back-office doit effectuer le remboursement manuellement côté opérateur

supportsReversal() == true
  └─ Step 1 : appel operatorGateway.reversePayment()
      ├─ Succès → operatorRefundReference sauvegardé en base
      └─ Échec (best-effort) → log WARN, opération comptable continue quand même
```

> **Pourquoi best-effort ?**
> Le wallet du marchand est déjà débité. Un échec du reversal opérateur ne doit pas laisser la transaction dans un état incohérent. Le back-office est notifié pour intervenir manuellement.

### 3.3 Implémentation d'un reversal pour un nouvel opérateur

```java
@Component
public class MtnMomoCiOperator implements MobileMoneyOperator {

    @Override
    public boolean supportsReversal() { return true; }

    @Override
    public OperatorRefundResult reversePayment(String operatorReference,
                                               BigDecimal amount,
                                               String currency) {
        // Appel à l'API MTN MoMo Reversals endpoint
        var response = webClient.post()
            .uri("/v1_0/payment/{ref}/refund", operatorReference)
            .bodyValue(Map.of("amount", amount, "currency", currency))
            .retrieve()
            .bodyToMono(MtnRefundResponse.class)
            .block();

        return response.isSuccess()
            ? OperatorRefundResult.success(response.getReferenceId())
            : OperatorRefundResult.failure(response.getReason());
    }
}
```

### 3.4 Données persistées

| Champ | Table | Description |
|-------|-------|-------------|
| `operator_refund_reference` | `transactions` | Référence opérateur du remboursement (null si non supporté ou échoué) |

### 3.5 Flux complet (diagramme de séquence)

```
Marchand          Ebithex API          OperatorGateway      Opérateur
   │                   │                      │                  │
   │ POST /refund       │                      │                  │
   │──────────────────►│                      │                  │
   │                   │ supportsReversal()?  │                  │
   │                   │─────────────────────►│                  │
   │                   │                      │ true / false     │
   │                   │◄─────────────────────│                  │
   │                   │                      │                  │
   │              [si true]                   │                  │
   │                   │ reversePayment()      │                  │
   │                   │─────────────────────►│ POST /refund     │
   │                   │                      │─────────────────►│
   │                   │                      │◄─────────────────│
   │                   │◄─────────────────────│                  │
   │                   │                      │                  │
   │                   │ debitRefund(wallet)  │                  │
   │                   │ status = REFUNDED     │                  │
   │                   │ publish REFUNDED event│                  │
   │◄──────────────────│                      │                  │
```

---

## 4. Réconciliation automatisée

### 4.1 Principe

La réconciliation compare les **relevés journaliers** fournis par les opérateurs avec les transactions enregistrées dans Ebithex. Elle détecte trois catégories d'anomalies :

- Transactions présentes chez l'opérateur mais absentes chez Ebithex
- Écarts de montant entre les deux systèmes
- Divergence de statut (ex. opérateur SUCCESS mais Ebithex FAILED)

### 4.2 Cycle de traitement

```
Opérateur                Back-office Ebithex                   Job planifié
    │                          │                                     │
    │  Envoi relevé J-1        │                                     │
    │  (CSV, SFTP, email)      │                                     │
    │─────────────────────────►│                                     │
    │                          │ POST /internal/reconciliation/      │
    │                          │      statements/import              │
    │                          │ → OperatorStatement (PENDING)       │
    │                          │ → OperatorStatementLine × N         │
    │                          │                                     │
    │                          │           02:30 UTC                 │
    │                          │◄────────────────────────────────────│
    │                          │  reconcileAllPending()              │
    │                          │  → Pour chaque ligne CSV :          │
    │                          │    findByOperatorReference()        │
    │                          │    compare montant + statut         │
    │                          │    → DiscrepancyType                │
    │                          │  → Statement : RECONCILED           │
    │                          │              ou DISCREPANCY_FOUND   │
```

### 4.3 Moteur de réconciliation

Pour chaque ligne du relevé opérateur :

```
operatorReference
      │
      ▼
transactionRepository.findByOperatorReference(ref)
      │
      ├─ Not found ──────────────► MISSING_IN_EBITHEX
      │
      ├─ Found, |amount_diff| > 0.01 ─► AMOUNT_MISMATCH
      │                                 discrepancyNote: "Ebithex=5000 vs opérateur=5100"
      │
      ├─ Found, statuts incompatibles ─► STATUS_MISMATCH
      │   opérateur=SUCCESS mais Ebithex=FAILED (ou inversement)
      │   Les statuts SUCCESS et REFUNDED sont considérés équivalents côté Ebithex
      │
      └─ Concordance parfaite ──────► MATCHED
```

> **Tolérance montant :** 1 centime (0.01 dans la devise) pour absorber les écarts d'arrondi des opérateurs.

> **Cas MISSING\_IN\_OPERATOR :** Ebithex ne génère pas automatiquement ce type car il faudrait comparer l'intégralité de nos transactions avec chaque relevé, ce qui est coûteux. Ce contrôle s'effectue via l'export CSV (`GET /internal/reconciliation/export/transactions`) comparé manuellement avec le relevé opérateur.

### 4.4 États d'un relevé

```
        PENDING
           │
    Job / Manuel
           │
           ▼
       PROCESSING
           │
    ┌──────┴──────┐
    │             │
    ▼             ▼
RECONCILED  DISCREPANCY_FOUND
(0 anomalie) (≥1 anomalie)
```

---

## 5. Format du relevé CSV

### 5.1 Structure

```csv
operator_reference,amount,currency,status,transaction_date
MTN-CI-20260317-001,5000.00,XOF,SUCCESS,2026-03-17T10:00:00
MTN-CI-20260317-002,15000.00,XOF,FAILED,2026-03-17T10:45:00
MTN-CI-20260317-003,2500.00,XOF,SUCCESS,2026-03-17T11:30:00
```

| Colonne | Type | Obligatoire | Description |
|---------|------|-------------|-------------|
| `operator_reference` | String | ✓ | Référence interne de l'opérateur |
| `amount` | Decimal (`.` comme séparateur) | ✓ | Montant brut |
| `currency` | String ISO 4217 | ✓ | Ex : `XOF`, `GHS`, `NGN`, `KES` |
| `status` | String | ✓ | `SUCCESS`, `COMPLETED`, `FAILED`, `REVERSED`, etc. |
| `transaction_date` | ISO 8601 | — | Ex : `2026-03-17T10:00:00` |

### 5.2 Règles de parsing

- **Encodage :** UTF-8
- **Délimiteur :** virgule (`,`)
- **En-tête :** obligatoire, ignorée lors du parsing
- **Lignes vides :** ignorées
- **Valeurs manquantes :** `transaction_date` seule peut être vide
- **Erreur de format :** lève `INVALID_CSV` — l'import entier est annulé

### 5.3 Statuts opérateurs reconnus comme SUCCESS

| Valeur dans le CSV | Interprétation Ebithex |
|--------------------|------------------------|
| `SUCCESS` | Succès |
| `COMPLETED` | Succès (alias courant) |
| Toute autre valeur | Échec |

---

## 6. Types d'anomalies détectées

| Type | Signification | Action recommandée |
|------|--------------|-------------------|
| `MATCHED` | Concordance parfaite | Aucune |
| `MISSING_IN_EBITHEX` | L'opérateur liste une transaction absente chez nous | Vérifier si la transaction est arrivée en callback en retard ; contacter l'opérateur si absent après 48h |
| `AMOUNT_MISMATCH` | Montants différents (écart > 0.01) | Corriger manuellement via `POST /internal/disputes` ou demander un avoir à l'opérateur |
| `STATUS_MISMATCH` | Opérateur=SUCCESS mais Ebithex=FAILED (ou inverse) | Synchroniser le statut via `POST /internal/support/sync/{ref}` si l'opérateur a raison ; signaler l'anomalie si Ebithex a raison |

---

## 7. Référence API REST

Tous les endpoints nécessitent un rôle `RECONCILIATION`, `FINANCE`, `ADMIN` ou `SUPER_ADMIN`.

### 7.1 Import d'un relevé

```http
POST /api/internal/reconciliation/statements/import
Content-Type: multipart/form-data

operator=MTN_MOMO_CI
statementDate=2026-03-17
file=<CSV file>
```

**Réponse 200 :**
```json
{
  "success": true,
  "message": "Relevé importé et réconcilié",
  "data": {
    "statementId": "a1b2c3d4-...",
    "totalLines": 245,
    "matchedLines": 242,
    "discrepancyLines": 3,
    "status": "DISCREPANCY_FOUND"
  }
}
```

**Erreurs :**
- `400 STATEMENT_ALREADY_EXISTS` — un relevé existe déjà pour ce couple opérateur/date
- `400 INVALID_CSV` — format de fichier invalide (message d'erreur inclut le numéro de ligne)

---

### 7.2 Lister les relevés

```http
GET /api/internal/reconciliation/statements?operator=MTN_MOMO_CI&page=0&size=20
```

**Réponse 200 :**
```json
{
  "data": {
    "content": [
      {
        "id": "a1b2c3d4-...",
        "operator": "MTN_MOMO_CI",
        "statementDate": "2026-03-17",
        "status": "DISCREPANCY_FOUND",
        "totalLines": 245,
        "matchedLines": 242,
        "discrepancyLines": 3,
        "importedAt": "2026-03-18T07:12:00",
        "reconciledAt": "2026-03-18T02:31:45"
      }
    ],
    "totalElements": 1
  }
}
```

---

### 7.3 Anomalies d'un relevé

```http
GET /api/internal/reconciliation/statements/{id}/discrepancies?page=0&size=50
```

**Réponse 200 :**
```json
{
  "data": {
    "content": [
      {
        "operatorReference": "MTN-CI-20260317-047",
        "ebithexReference": "AP-20260317-ABC123",
        "amount": 5100.00,
        "currency": "XOF",
        "operatorStatus": "SUCCESS",
        "discrepancyType": "AMOUNT_MISMATCH",
        "discrepancyNote": "Montant Ebithex=5000.00 vs opérateur=5100.00"
      },
      {
        "operatorReference": "MTN-CI-20260317-183",
        "ebithexReference": null,
        "amount": 3000.00,
        "currency": "XOF",
        "operatorStatus": "SUCCESS",
        "discrepancyType": "MISSING_IN_EBITHEX",
        "discrepancyNote": "Référence opérateur absente de la base Ebithex"
      }
    ]
  }
}
```

---

### 7.4 Relancer une réconciliation (manuel)

```http
POST /api/internal/reconciliation/statements/{id}/reconcile
```

Utile après une correction de données ou si le job automatique a échoué.

---

### 7.5 Résumé financier (transactions + payouts)

```http
GET /api/internal/reconciliation/summary?from=2026-03-01T00:00:00&to=2026-03-17T23:59:59
```

```json
{
  "data": {
    "transactions": [
      { "status": "SUCCESS",    "count": 12450, "totalAmount": 186750000.00, "totalFees": 1494000.00 },
      { "status": "FAILED",     "count": 342,   "totalAmount": 5130000.00,   "totalFees": 0 },
      { "status": "REFUNDED",   "count": 18,    "totalAmount": 270000.00,    "totalFees": 2160.00 }
    ],
    "payouts": [
      { "status": "SUCCESS",    "count": 3210,  "totalAmount": 48150000.00,  "totalFees": 240750.00 }
    ]
  }
}
```

---

## 8. Schéma de base de données

### `operator_statements`

```sql
CREATE TABLE operator_statements (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    operator          VARCHAR(50)  NOT NULL,           -- OperatorType enum
    statement_date    DATE         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    total_lines       INT          NOT NULL DEFAULT 0,
    matched_lines     INT          NOT NULL DEFAULT 0,
    discrepancy_lines INT          NOT NULL DEFAULT 0,
    imported_by       UUID,                            -- UUID du StaffUser ayant importé le relevé (FK → staff_users.id)
    imported_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    reconciled_at     TIMESTAMP,
    notes             TEXT,
    UNIQUE (operator, statement_date)                  -- Un seul relevé par opérateur/jour
);
```

### `operator_statement_lines`

```sql
CREATE TABLE operator_statement_lines (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    statement_id        UUID         NOT NULL REFERENCES operator_statements(id) ON DELETE CASCADE,
    operator_reference  VARCHAR(255) NOT NULL,         -- Référence de l'opérateur
    ebithex_reference   VARCHAR(255),                  -- Référence Ebithex trouvée (null si MISSING)
    amount              NUMERIC(15,2) NOT NULL,
    currency            VARCHAR(10)  NOT NULL,
    operator_status     VARCHAR(50)  NOT NULL,
    operator_date       TIMESTAMP,
    discrepancy_type    VARCHAR(30),                   -- NULL avant réconciliation
    discrepancy_note    TEXT,
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);
```

### Colonne ajoutée sur `transactions`

```sql
ALTER TABLE transactions
    ADD COLUMN operator_refund_reference VARCHAR(255);
-- Référence attribuée par l'opérateur lors du reversal
```

### Index

```sql
-- Recherche d'anomalies
CREATE INDEX idx_stmt_lines_operator_ref ON operator_statement_lines(operator_reference);
CREATE INDEX idx_stmt_lines_statement_id ON operator_statement_lines(statement_id);
CREATE INDEX idx_stmt_lines_discrepancy  ON operator_statement_lines(statement_id, discrepancy_type)
    WHERE discrepancy_type IS NOT NULL AND discrepancy_type != 'MATCHED';
CREATE INDEX idx_op_stmt_operator_date   ON operator_statements(operator, statement_date);
CREATE INDEX idx_op_stmt_status          ON operator_statements(status);
```

---

## 9. Référence de configuration

```properties
# Cron du job de réconciliation automatique (défaut : 02:30 UTC)
ebithex.reconciliation.cron=0 30 2 * * *
```

**Circuit breakers (partagés avec les paiements) :**

```properties
# Protège les appels operator-payment (initiatePayment + reversePayment)
resilience4j.circuitbreaker.instances.operator-payment.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.operator-payment.wait-duration-in-open-state=30s

# Protège les décaissements
resilience4j.circuitbreaker.instances.operator-disbursement.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.operator-disbursement.wait-duration-in-open-state=60s
```

---

## 10. Matrice de support opérateur

| Opérateur | Pays | Collection | Disbursement | Reversal |
|-----------|------|:---------:|:------------:|:--------:|
| MTN MoMo | CI | ✓ | ✓ | à implémenter |
| MTN MoMo | BJ | ✓ | ✓ | à implémenter |
| MTN MoMo | CM | ✓ | ✓ | à implémenter |
| MTN MoMo | NG | ✓ | ✓ | à implémenter |
| MTN MoMo | GH | ✓ | ✓ | à implémenter |
| MTN MoMo | ZA | ✓ | ✓ | à implémenter |
| Orange Money | CI | ✓ | ✓ | à implémenter |
| Orange Money | SN | ✓ | ✓ | à implémenter |
| Orange Money | CM | ✓ | ✓ | à implémenter |
| Wave | CI + SN | ✓ | — | — (API non disponible) |
| M-Pesa | KE | ✓ | ✓ | à implémenter |

> Pour activer le reversal d'un opérateur : surcharger `supportsReversal()` et `reversePayment()` dans son adapter, puis créer un test d'intégration `OperatorReversalTest`.

---

## 11. Codes d'erreur

| Code | HTTP | Contexte | Description |
|------|------|---------|-------------|
| `STATEMENT_ALREADY_EXISTS` | 400 | Import | Un relevé existe déjà pour ce couple opérateur/date |
| `INVALID_CSV` | 400 | Import | Format CSV invalide — message d'erreur inclut le numéro de ligne |
| `STATEMENT_NOT_FOUND` | 404 | Consultation | Relevé introuvable avec cet ID |
| `REFUND_NOT_ALLOWED` | 400 | Reversal | Transaction non en statut SUCCESS |
| `INSUFFICIENT_BALANCE` | 400 | Reversal | Solde marchand insuffisant pour débiter le remboursement |
| `TRANSACTION_NOT_FOUND` | 404 | Reversal | Transaction introuvable ou hors périmètre du marchand |

---

## 12. Runbook — Anomalies de réconciliation

### Cas 1 : `MISSING_IN_EBITHEX`

L'opérateur a traité une transaction que nous n'avons pas enregistrée.

```
1. Vérifier les logs du jour J pour operatorReference=XXX
2. Chercher un callback entrant (OutboxEvent de type PaymentCallback)
3. Si le callback est arrivé après 00:00 → la transaction est dans J+1
   → Pas d'action requise
4. Si aucune trace → contacter l'opérateur pour obtenir les détails
5. Si la transaction est réelle et l'argent encaissé → créer manuellement
   via l'outil back-office de régularisation
```

### Cas 2 : `AMOUNT_MISMATCH`

```
1. Vérifier le taux de change si la devise a été convertie
2. Vérifier les frais déduits côté opérateur (certains opérateurs déduisent
   leurs frais avant de reporter le montant)
3. Si écart systématique → ajuster la configuration des frais (FeeRule)
4. Si écart ponctuel → ouvrir un litige opérateur via le portail partenaire
```

### Cas 3 : `STATUS_MISMATCH` (opérateur=SUCCESS, Ebithex=FAILED)

```
1. Vérifier si un callback SUCCESS est arrivé après expiration de la transaction
2. Vérifier si le circuit breaker était ouvert au moment du checkStatus
3. Si la transaction est bien SUCCESS côté opérateur :
   → Synchroniser via POST /api/internal/support/sync/{ref}
   → Le wallet marchand sera crédité automatiquement
4. Alerter l'équipe engineering si ce cas est récurrent pour un opérateur
```

### Cas 4 : Job de réconciliation silencieux (aucun relevé traité)

```
1. Vérifier les logs à 02:30 UTC : "=== Début du job de réconciliation ==="
2. Si absent → vérifier que @EnableScheduling est actif dans ApplicationConfig
3. Vérifier qu'il n'y a pas de relevés PENDING non importés
4. En cas d'urgence → POST /api/internal/reconciliation/statements/{id}/reconcile
```