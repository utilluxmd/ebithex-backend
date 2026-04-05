# Ebithex — Documentation Gestion des Litiges (Disputes)

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture](#2-architecture)
3. [Cycle de vie d'un litige](#3-cycle-de-vie-dun-litige)
4. [Motifs de litige](#4-motifs-de-litige)
5. [Référence API REST — Marchands](#5-référence-api-rest--marchands)
6. [Référence API REST — Back-office](#6-référence-api-rest--back-office)
7. [Schéma de base de données](#7-schéma-de-base-de-données)
8. [Référence de configuration](#8-référence-de-configuration)
9. [Modèle de sécurité](#9-modèle-de-sécurité)
10. [Codes d'erreur](#10-codes-derreur)

---

## 1. Vue d'ensemble

Le module de gestion des litiges d'Ebithex permet aux marchands de contester des transactions de paiement et à l'équipe Support de traiter ces contestations de manière structurée.

Un litige (dispute) correspond à une réclamation formelle d'un marchand indiquant qu'une transaction posée problème : montant incorrect, paiement non reçu, transaction non autorisée, ou doublon. Le système gère l'intégralité du workflow, de l'ouverture à la résolution.

Fonctionnalités principales :
- Ouverture de litige sur une référence de transaction Ebithex
- Prévention des doublons (un seul litige actif par transaction)
- Isolation stricte des données (un marchand ne voit que ses propres litiges)
- Workflow de révision back-office avec traçabilité complète
- Annulation par le marchand (statut OPEN uniquement)

> **Prérequis** : le marchand doit avoir le rôle `MERCHANT_KYC_VERIFIED` pour accéder aux endpoints de litige. Les marchands non-KYC ne peuvent pas initier de litiges.

---

## 2. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        module-payment                            │
│                                                                  │
│  ┌─────────────────────┐   ┌──────────────────────────────────┐  │
│  │  DisputeController  │   │    DisputeAdminController        │  │
│  │  POST /v1/disputes  │   │  GET  /internal/disputes         │  │
│  │  GET  /v1/disputes  │   │  GET  /internal/disputes/{id}    │  │
│  │  GET  /v1/disputes/ │   │  PUT  /internal/disputes/{id}/   │  │
│  │       {id}          │   │       review                     │  │
│  │  DELETE /v1/disputes│   │  PUT  /internal/disputes/{id}/   │  │
│  │        /{id}        │   │       resolve                    │  │
│  └──────────┬──────────┘   └──────────────┬───────────────────┘  │
│             │                             │                       │
│             └──────────────┬──────────────┘                       │
│                            ▼                                      │
│               ┌────────────────────────┐                         │
│               │     DisputeService     │                         │
│               │  openDispute()         │                         │
│               │  listForMerchant()     │                         │
│               │  getForMerchant()      │  ← isolation marchand   │
│               │  cancelByMerchant()    │                         │
│               │  startReview()         │                         │
│               │  resolve()             │                         │
│               │  listForBackOffice()   │                         │
│               └──────────┬─────────────┘                         │
│                          │                                        │
│               ┌──────────┴─────────────┐                         │
│               │                        │                         │
│               ▼                        ▼                         │
│  ┌─────────────────────┐   ┌────────────────────────┐           │
│  │  DisputeRepository  │   │  TransactionRepository │           │
│  │  (table: disputes)  │   │  (vérif. propriété tx) │           │
│  └─────────────────────┘   └────────────────────────┘           │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. Cycle de vie d'un litige

### 3.1 Diagramme d'état complet

```
                  POST /v1/disputes
                         │
                         ▼
                  ┌─────────────┐
                  │    OPEN     │  (litige ouvert, en attente de traitement)
                  └──────┬──────┘
                         │
       ┌─────────────────┼──────────────────────┐
       │                 │                      │
       ▼                 ▼                      ▼
┌───────────┐   PUT …/{id}/review    DELETE /v1/disputes/{id}
│ CANCELLED │   (back-office)               │
│(marchand) │          │                   ▼
└───────────┘          ▼             ┌───────────┐
                ┌──────────────┐     │ CANCELLED │
                │ UNDER_REVIEW │     │(marchand) │
                └──────┬───────┘     └───────────┘
                       │
              PUT …/{id}/resolve
                       │
           ┌───────────┴──────────────┐
           ▼                          ▼
  ┌──────────────────┐      ┌──────────────────────┐
  │ RESOLVED_MERCHANT│      │ RESOLVED_CUSTOMER    │
  │  (en faveur du   │      │  (en faveur du       │
  │   marchand)      │      │   client final)      │
  └──────────────────┘      └──────────────────────┘
```

### 3.2 Description des statuts

| Statut               | Description                                                                    |
|----------------------|--------------------------------------------------------------------------------|
| `OPEN`               | Litige ouvert par le marchand, en attente de prise en charge par le support    |
| `UNDER_REVIEW`       | Pris en charge par un agent support — en cours d'investigation                 |
| `RESOLVED_MERCHANT`  | Résolu en faveur du marchand (ex. remboursement accordé, erreur confirmée)     |
| `RESOLVED_CUSTOMER`  | Résolu en faveur du client final (ex. réclamation infondée)                    |
| `CANCELLED`          | Annulé par le marchand avant traitement (uniquement depuis `OPEN`)             |

### 3.3 Règles de transition

| De          | Vers                                       | Qui           | Endpoint                              |
|-------------|--------------------------------------------|---------------|---------------------------------------|
| `OPEN`      | `UNDER_REVIEW`                             | Support       | `PUT /internal/disputes/{id}/review`  |
| `OPEN`      | `CANCELLED`                                | Marchand      | `DELETE /v1/disputes/{id}`            |
| `UNDER_REVIEW` | `RESOLVED_MERCHANT`, `RESOLVED_CUSTOMER` | Support/Admin | `PUT /internal/disputes/{id}/resolve` |

Il est **impossible** d'annuler un litige en statut `UNDER_REVIEW` ou au-delà. Toute tentative retourne l'erreur `DISPUTE_CANNOT_CANCEL`.

---

## 4. Motifs de litige

| Valeur enum      | Description                                                   |
|------------------|---------------------------------------------------------------|
| `UNAUTHORIZED`   | Transaction non autorisée par le marchand ou le client final  |
| `DUPLICATE`      | Transaction dupliquée — même opération facturée deux fois     |
| `NOT_RECEIVED`   | Paiement non reçu côté opérateur malgré le statut SUCCESS     |
| `WRONG_AMOUNT`   | Montant débité différent du montant attendu                   |
| `OTHER`          | Tout autre motif — préciser dans le champ `description`       |

---

## 5. Référence API REST — Marchands

### Authentification

Tous les endpoints marchands nécessitent l'un des headers suivants :
- `X-API-Key: ap_live_<clé>`
- `Authorization: Bearer <jwt>`

**Rôle requis** : `MERCHANT_KYC_VERIFIED`

---

### 5.1 Ouvrir un litige

```http
POST /v1/disputes
Content-Type: application/json
X-API-Key: ap_live_...

{
  "ebithexReference": "TX-CI-20260318-001234",
  "reason": "WRONG_AMOUNT",
  "description": "Le client a été débité de 10 000 XOF alors que la transaction était de 5 000 XOF"
}
```

**Corps de la requête**

| Champ              | Type            | Obligatoire | Description                                          |
|--------------------|-----------------|-------------|------------------------------------------------------|
| `ebithexReference` | chaîne          | ✅           | Référence Ebithex de la transaction contestée        |
| `reason`           | `DisputeReason` | ✅           | Motif du litige (voir §4)                            |
| `description`      | chaîne          | ✅           | Description détaillée du problème                    |

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "id": "7a3f1c2d-8e4b-4a5f-9d6c-1b2e3f4a5b6c",
    "ebithexReference": "TX-CI-20260318-001234",
    "merchantId": "550e8400-e29b-41d4-a716-446655440000",
    "reason": "WRONG_AMOUNT",
    "description": "Le client a été débité de 10 000 XOF alors que la transaction était de 5 000 XOF",
    "amount": 10000.00,
    "currency": "XOF",
    "status": "OPEN",
    "openedAt": "2026-03-18T14:30:00Z",
    "resolvedAt": null,
    "resolvedBy": null,
    "resolutionNotes": null
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur               | Condition                                                    |
|--------|-----------------------------|--------------------------------------------------------------|
| 400    | `DISPUTE_TRANSACTION_NOT_FOUND` | Référence de transaction inconnue ou n'appartenant pas au marchand |
| 409    | `DISPUTE_ALREADY_EXISTS`    | Un litige actif existe déjà pour cette référence de transaction |

---

### 5.2 Lister ses litiges

```http
GET /v1/disputes
X-API-Key: ap_live_...
```

Retourne tous les litiges du marchand authentifié, triés par date d'ouverture décroissante.

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": [
    {
      "id": "7a3f1c2d-...",
      "ebithexReference": "TX-CI-20260318-001234",
      "reason": "WRONG_AMOUNT",
      "status": "OPEN",
      "amount": 10000.00,
      "currency": "XOF",
      "openedAt": "2026-03-18T14:30:00Z",
      ...
    }
  ]
}
```

---

### 5.3 Obtenir le détail d'un litige

```http
GET /v1/disputes/{disputeId}
X-API-Key: ap_live_...
```

**Réponses d'erreur**

| Statut | Code d'erreur        | Condition                                                         |
|--------|----------------------|-------------------------------------------------------------------|
| 404    | `DISPUTE_NOT_FOUND`  | Litige inexistant ou appartenant à un autre marchand              |

---

### 5.4 Annuler un litige

```http
DELETE /v1/disputes/{disputeId}
X-API-Key: ap_live_...
```

Annule un litige en statut `OPEN`. L'annulation est **définitive**.

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "id": "7a3f1c2d-...",
    "status": "CANCELLED",
    ...
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur          | Condition                                                      |
|--------|------------------------|----------------------------------------------------------------|
| 400    | `DISPUTE_CANNOT_CANCEL`| Le litige n'est plus en statut OPEN (déjà en révision ou résolu) |
| 404    | `DISPUTE_NOT_FOUND`    | Litige inexistant ou appartenant à un autre marchand           |

---

## 6. Référence API REST — Back-office

### Authentification

```http
Authorization: Bearer <jwt-back-office>
```

---

### 6.1 Lister tous les litiges

```http
GET /internal/disputes
Authorization: Bearer <jwt-support>
```

**Paramètres de requête (tous optionnels)**

| Paramètre    | Type             | Description                                     |
|--------------|------------------|-------------------------------------------------|
| `status`     | `DisputeStatus`  | Filtrer par statut                              |
| `merchantId` | UUID             | Filtrer pour un marchand spécifique             |
| `from`       | ISO 8601 datetime| Date de début (openedAt ≥ from)                 |
| `to`         | ISO 8601 datetime| Date de fin (openedAt ≤ to)                     |
| `page`       | int              | Numéro de page (défaut : 0)                     |
| `size`       | int              | Taille de page (défaut : 20, max : 100)         |

**Exemple de requête**

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "https://api.ebithex.io/api/internal/disputes?status=OPEN&page=0&size=50"
```

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "content": [...],
    "totalElements": 12,
    "totalPages": 1,
    "page": 0,
    "size": 50
  }
}
```

---

### 6.2 Obtenir le détail d'un litige (admin)

```http
GET /internal/disputes/{disputeId}
Authorization: Bearer <jwt-support>
```

Retourne le litige quel que soit son marchand propriétaire.

---

### 6.3 Prendre en charge un litige (OPEN → UNDER_REVIEW)

```http
PUT /internal/disputes/{disputeId}/review
Authorization: Bearer <jwt-support>
```

Pas de corps de requête. L'agent support est identifié via son JWT (`email()`).

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "id": "7a3f1c2d-...",
    "status": "UNDER_REVIEW",
    ...
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur          | Condition                                        |
|--------|------------------------|--------------------------------------------------|
| 400    | `DISPUTE_INVALID_STATE`| Le litige n'est pas en statut OPEN               |
| 404    | `DISPUTE_NOT_FOUND`    | Litige introuvable                               |

---

### 6.4 Résoudre un litige

```http
PUT /internal/disputes/{disputeId}/resolve
Authorization: Bearer <jwt-support>
Content-Type: application/json

{
  "status": "RESOLVED_MERCHANT",
  "resolutionNotes": "Erreur opérateur confirmée — remboursement initié le 2026-03-18"
}
```

**Corps de la requête**

| Champ             | Type             | Obligatoire | Description                                               |
|-------------------|------------------|-------------|-----------------------------------------------------------|
| `status`          | `DisputeStatus`  | ✅           | `RESOLVED_MERCHANT` ou `RESOLVED_CUSTOMER`               |
| `resolutionNotes` | chaîne           | ✅           | Résumé de la décision et des actions entreprises          |

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "id": "7a3f1c2d-...",
    "status": "RESOLVED_MERCHANT",
    "resolutionNotes": "Erreur opérateur confirmée — remboursement initié le 2026-03-18",
    "resolvedAt": "2026-03-18T16:45:00Z",
    "resolvedBy": "support@ebithex.io"
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur          | Condition                                          |
|--------|------------------------|----------------------------------------------------|
| 400    | `DISPUTE_INVALID_STATE`| Le litige n'est pas en statut OPEN ni UNDER_REVIEW |
| 400    | `DISPUTE_INVALID_STATUS` | Statut cible non valide pour la résolution        |
| 404    | `DISPUTE_NOT_FOUND`    | Litige introuvable                                 |

---

## 7. Schéma de base de données

### Table : `disputes`

| Colonne            | Type          | Contraintes                   | Description                                               |
|--------------------|---------------|-------------------------------|-----------------------------------------------------------|
| `id`               | UUID          | PK, DEFAULT gen_random_uuid() | Identifiant du litige                                     |
| `ebithex_reference`| VARCHAR(100)  | NOT NULL                      | Référence Ebithex de la transaction contestée             |
| `merchant_id`      | UUID          | NOT NULL                      | Marchand ayant ouvert le litige                           |
| `transaction_id`   | UUID          |                               | Identifiant interne de la transaction (peut être NULL)    |
| `reason`           | VARCHAR(50)   | NOT NULL                      | Valeur de l'enum `DisputeReason`                          |
| `description`      | TEXT          | NOT NULL                      | Description libre du problème                             |
| `amount`           | DECIMAL(19,4) |                               | Montant de la transaction contestée                       |
| `currency`         | VARCHAR(10)   |                               | Devise (ex. `XOF`, `KES`)                                |
| `status`           | VARCHAR(30)   | NOT NULL, DEFAULT 'OPEN'      | Valeur de l'enum `DisputeStatus`                         |
| `opened_at`        | TIMESTAMPTZ   | NOT NULL, DEFAULT NOW()       | Horodatage d'ouverture (automatique via @CreationTimestamp)|
| `resolved_at`      | TIMESTAMPTZ   |                               | Horodatage de résolution                                  |
| `resolved_by`      | VARCHAR(255)  |                               | Email de l'agent ayant résolu le litige                   |
| `resolution_notes` | TEXT          |                               | Notes de résolution                                       |
| `evidence_urls`    | TEXT          |                               | URLs de pièces justificatives (format JSON array)         |

**Index**

| Nom de l'index               | Colonnes                        | Utilité                                           |
|------------------------------|---------------------------------|---------------------------------------------------|
| `idx_dispute_merchant`       | `merchant_id`                   | Lister les litiges d'un marchand                  |
| `idx_dispute_status`         | `status`                        | Filtrer par statut pour le back-office            |
| `idx_dispute_ref`            | `ebithex_reference`             | Retrouver un litige par référence de transaction  |
| `idx_dispute_opened_at`      | `opened_at`                     | Trier et filtrer par date                         |

**Contrainte d'unicité**

```sql
UNIQUE (ebithex_reference, merchant_id)
```

Empêche l'ouverture de plusieurs litiges actifs pour la même transaction du même marchand.

---

## 8. Référence de configuration

Le module Disputes ne nécessite pas de configuration spécifique. Il hérite des propriétés de sécurité générales d'Ebithex.

Pour les futures extensions (délai d'ouverture maximum, escalade automatique) :

| Propriété                                    | Défaut  | Description                                                 |
|----------------------------------------------|---------|-------------------------------------------------------------|
| `ebithex.disputes.max-open-days`             | _(n/a)_ | Nombre de jours max après la transaction pour ouvrir un litige *(à venir)* |
| `ebithex.disputes.auto-escalate-after-days`  | _(n/a)_ | Escalade automatique si non traité après N jours *(à venir)* |

---

## 9. Modèle de sécurité

### 9.1 Contrôle d'accès

| Endpoint                                     | Rôle(s) requis                                             |
|----------------------------------------------|------------------------------------------------------------|
| `POST /v1/disputes`                          | `MERCHANT_KYC_VERIFIED`                                    |
| `GET /v1/disputes`                           | `MERCHANT_KYC_VERIFIED`                                    |
| `GET /v1/disputes/{id}`                      | `MERCHANT_KYC_VERIFIED`                                    |
| `DELETE /v1/disputes/{id}`                   | `MERCHANT_KYC_VERIFIED`                                    |
| `GET /internal/disputes`                     | `SUPPORT`, `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`         |
| `GET /internal/disputes/{id}`                | `SUPPORT`, `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`         |
| `PUT /internal/disputes/{id}/review`         | `SUPPORT`, `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`         |
| `PUT /internal/disputes/{id}/resolve`        | `SUPPORT`, `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`         |

### 9.2 Isolation des données marchands

Les endpoints marchands appliquent une isolation stricte :

- `getForMerchant(disputeId, merchantId)` : vérifie que le litige appartient bien au marchand authentifié. Si le litige existe mais appartient à un autre marchand, l'erreur retournée est `DISPUTE_NOT_FOUND` (pas `FORBIDDEN`) pour ne pas divulguer l'existence du litige.
- `cancelByMerchant(disputeId, merchantId)` : même vérification avant toute modification.

### 9.3 Piste d'audit

| Action                          | Champ tracé                              |
|---------------------------------|------------------------------------------|
| Prise en charge par le support  | Implicite via le changement de statut    |
| Résolution                      | `resolvedBy` (email JWT), `resolvedAt`   |
| Annulation                      | Statut `CANCELLED` avec horodatage       |

---

## 10. Codes d'erreur

| Code d'erreur                     | HTTP | Description                                                              |
|-----------------------------------|------|--------------------------------------------------------------------------|
| `DISPUTE_TRANSACTION_NOT_FOUND`   | 404  | Référence de transaction inconnue ou n'appartenant pas au marchand       |
| `DISPUTE_ALREADY_EXISTS`          | 409  | Un litige actif existe déjà pour cette référence de transaction          |
| `DISPUTE_NOT_FOUND`               | 404  | Litige inexistant ou appartenant à un autre marchand                     |
| `DISPUTE_CANNOT_CANCEL`           | 400  | Annulation impossible — le litige est en statut UNDER_REVIEW ou terminal |
| `DISPUTE_INVALID_STATE`           | 400  | Transition de statut incompatible avec l'état actuel du litige           |
| `DISPUTE_INVALID_STATUS`          | 400  | Statut cible non valide pour l'opération demandée                        |

---

*Dernière mise à jour : 18 mars 2026*
