# Ebithex — Flows et Cas d'Usage par Rôle

> Documentation complète de tous les flux utilisateurs et cas d'usage du backend Ebithex.
> Date : 2026-04-04

---

## Sommaire

1. [Acteurs et rôles](#1-acteurs-et-rôles)
2. [Authentification et modèle de sécurité](#2-authentification-et-modèle-de-sécurité)
3. [Flux marchands](#3-flux-marchands)
   - 3.1 Inscription et activation du compte
   - 3.2 Gestion des clés API
   - 3.3 Soumission du KYC
   - 3.4 Initiation d'un paiement
   - 3.5 Remboursement et annulation
   - 3.6 Décaissement (payout unitaire)
   - 3.7 Décaissement en masse (bulk payout)
   - 3.8 Gestion du wallet
   - 3.9 Demande de retrait
   - 3.10 Transfert B2B inter-marchands
   - 3.11 Gestion des webhooks
   - 3.12 Gestion des litiges
   - 3.13 Conformité RGPD
4. [Flux back-office](#4-flux-back-office)
   - 4.1 Authentification staff (2FA obligatoire)
   - 4.2 Gestion des utilisateurs staff (SUPER_ADMIN)
   - 4.3 Gestion des marchands
   - 4.4 Revue KYC
   - 4.5 Finance — vue des wallets et résumé
   - 4.6 Approbation des retraits
   - 4.7 Gestion du float opérateur
   - 4.8 Réconciliation et relevés opérateurs
   - 4.9 Cycles de règlement (settlement)
   - 4.10 File des webhooks en échec (Dead Letter Queue)
   - 4.11 Opérations support client
   - 4.12 Revue des litiges
   - 4.13 Conformité AML
   - 4.14 Gestion des sanctions
   - 4.15 Configuration des règles tarifaires
   - 4.16 Journaux d'audit
   - 4.17 Rapports réglementaires (BCEAO / UEMOA)
   - 4.18 Purge PII (rétention des données)
   - 4.19 Rotation des clés AES
5. [Flux événementiels](#5-flux-événementiels)
6. [Callbacks opérateurs](#6-callbacks-opérateurs)
7. [Considérations transverses](#7-considérations-transverses)
8. [Matrice rôles × endpoints](#8-matrice-rôles--endpoints)

---

## 1. Acteurs et rôles

### Rôles marchands

| Rôle | Description | Mode d'authentification |
|------|-------------|------------------------|
| `MERCHANT` | Marchand inscrit, KYC non encore vérifié | Clé API (`X-API-Key`) ou JWT Bearer |
| `MERCHANT_KYC_VERIFIED` | Marchand avec KYC approuvé — accès complet | Clé API ou JWT Bearer |
| `AGENT` | Agent tiers — accès paiements et décaissements | Clé API ou JWT Bearer |

### Rôles staff back-office

| Rôle | Description | Périmètre |
|------|-------------|-----------|
| `SUPPORT` | Support client — lecture seule, gestion des litiges | Global |
| `FINANCE` | Équipe finance — wallets, retraits, float, règlements | Global |
| `RECONCILIATION` | Import relevés opérateurs, réconciliation, exports | Global |
| `COMPLIANCE` | Alertes AML, gestion des sanctions | Global |
| `COUNTRY_ADMIN` | Admin limité à un pays — marchands + revue KYC | Pays unique (ISO-3166-1) |
| `ADMIN` | Admin global — quasi toutes les opérations back-office | Global |
| `SUPER_ADMIN` | Super-administrateur — staff CRUD, règles tarifaires, rotation clés | Global |

---

## 2. Authentification et modèle de sécurité

### 2.1 Authentification marchande

#### Mode A — Clé API (sans état)

```
Client → POST /v1/payments   { X-API-Key: ap_live_... }
               │
               ▼
         ApiKeyAuthFilter
           ├─ Hachage SHA-256 de la valeur du header
           ├─ Recherche dans api_keys par key_hash
           │   OU previous_hash (période de grâce post-rotation)
           ├─ Vérification expiration, restriction IP, compte actif
           ├─ Clé de test (ap_test_) OU merchant.testMode=true
           │   → SandboxContextHolder=true → schéma sandbox
           ├─ Construction du principal avec apiKeyId + scopes
           └─ Vérification de scope par @PreAuthorize au niveau contrôleur
               │
               ▼
         Contrôleur traite la requête
```

#### Mode B — JWT (sessions)

```
POST /v1/auth/login  { email, password }
  → Vérification BCrypt (force 12)
  → Retourne { accessToken (15 min), refreshToken (7 jours) }

POST /v1/auth/refresh  { refreshToken }
  → Rotation : ancien refresh révoqué, nouvelle paire retournée

POST /v1/auth/logout  { bearerToken, refreshToken }
  → Les deux tokens ajoutés à la liste de blocage
```

### 2.2 Authentification staff (2FA obligatoire)

```
Étape 1 : Mot de passe
  POST /internal/auth/login  { email, password }
  → Vérification BCrypt
  → OTP généré (6 chiffres, TTL 5 min), envoyé par email/SMS
  → Retourne { tempToken (JWT court, signé) }

Étape 2 : OTP
  POST /internal/auth/login/verify-otp  { tempToken, code }
  → Vérification du code et de son expiration
  → Retourne { accessToken (8h) } — JWT complet avec claim de rôle

Déconnexion :
  POST /internal/auth/logout
  → Token ajouté à la liste de blocage
```

### 2.3 Rotation des clés API (période de grâce 24h)

```
POST /v1/auth/api-keys/{keyId}/rotate
  → Génération d'une nouvelle clé API (retournée en clair UNE SEULE FOIS)
  → Nouveau record ApiKey créé (active=true)
  → Hash de l'ancienne clé stocké comme previous_hash sur le nouveau record
  → previous_expires_at = maintenant + grace-period (défaut 24h)
  → Ancien record ApiKey désactivé (active=false)

Pendant la période de grâce (24h) :
  → Les requêtes avec l'ancienne clé sont acceptées via previous_hash
  → Après expiration : ancienne clé définitivement rejetée
```

---

## 3. Flux marchands

### 3.1 Inscription et activation du compte

**Acteur :** Anonyme / nouveau marchand
**Endpoint :** `POST /v1/auth/register`
**Auth :** Aucune

```
Corps de la requête :
  { businessName, email, password, country }

Validations :
  → Unicité de l'email (EMAIL_ALREADY_EXISTS si doublon)
  → Code pays ISO requis (COUNTRY_REQUIRED si absent)

Traitement :
  → Hachage du mot de passe (BCrypt force 12)
  → Création du Merchant (active=true, kycVerified=false, testMode=false)
  → Génération de deux clés initiales via ApiKeyService.createInitialKeys() :
      • ap_live_<43 chars>  (LIVE, FULL_ACCESS) → hash SHA-256 dans api_keys
      • ap_test_<43 chars>  (TEST, FULL_ACCESS) → hash SHA-256 dans api_keys
      Les clés en clair ne sont jamais persistées.
  → Création du Wallet par défaut (XOF)

Réponse : { liveApiKey (clair, unique !), testApiKey (clair, unique !),
            accessToken, refreshToken, merchantId }

Post-inscription :
  → Rôle = MERCHANT
  → Peut initier des paiements (dans les limites sans KYC)
  → Ne peut pas faire de payouts, retraits, bulk payouts
    (nécessite MERCHANT_KYC_VERIFIED)
```

**Profil marchand :**
```
GET /v1/merchants/me
  → Retourne le profil, statut KYC, limites actives, flag testMode
```

### 3.2 Gestion des clés API

**Acteur :** `MERCHANT`, `MERCHANT_KYC_VERIFIED`
**Auth :** JWT Bearer ou clé API avec scope `FULL_ACCESS`

| Action | Endpoint | Remarques |
|--------|----------|-----------|
| Lister les clés | `GET /v1/auth/api-keys` | Toutes les clés actives et inactives |
| Créer une clé scopée | `POST /v1/auth/api-keys` | `rawKey` retourné une seule fois |
| Tourner une clé | `POST /v1/auth/api-keys/{keyId}/rotate` | Grâce 24h sur l'ancienne clé |
| Révoquer une clé | `DELETE /v1/auth/api-keys/{keyId}` | Immédiat, sans période de grâce |
| Modifier les scopes | `PUT /v1/auth/api-keys/{keyId}/scopes` | Modification immédiate |
| Modifier les IPs autorisées | `PUT /v1/auth/api-keys/{keyId}/allowed-ips` | `null` = aucune restriction IP |
| Modifier l'expiration | `PUT /v1/auth/api-keys/{keyId}/expires-at` | `null` = pas d'expiration |

**Rotation :** utiliser `POST /v1/auth/api-keys/{keyId}/rotate` pour cibler une clé précise.

**Scopes disponibles :**

| Scope | Opérations couvertes |
|-------|----------------------|
| `FULL_ACCESS` | Toutes les opérations (y compris créer/supprimer des webhooks) |
| `PAYMENTS_WRITE` | `POST /v1/payments` |
| `PAYMENTS_READ` | `GET /v1/payments`, `GET /v1/payments/{ref}`, `GET /v1/payments/phone-check` |
| `PAYOUTS_WRITE` | `POST /v1/payouts`, `POST /v1/payouts/bulk` |
| `PAYOUTS_READ` | `GET /v1/payouts`, `GET /v1/payouts/{ref}` |
| `WEBHOOKS_READ` | `GET /v1/webhooks`, `GET /v1/webhooks/{id}/deliveries` |
| `PROFILE_READ` | `GET /v1/merchants/me` |

> **Scopes et webhooks :** `POST /v1/webhooks`, `DELETE /v1/webhooks/{id}` et `POST /v1/webhooks/{id}/test` requièrent `FULL_ACCESS`. Une clé `PAYMENTS_WRITE` uniquement ne peut pas créer de webhooks (403).
>
> Une clé sans `FULL_ACCESS` sera refusée (403) sur tout endpoint non couvert par ses scopes explicites.

### 3.3 Soumission du KYC

**Acteur :** `MERCHANT`, `MERCHANT_KYC_VERIFIED`
**Objectif :** Obtenir la vérification KYC pour débloquer toutes les fonctionnalités

```
Étape 1 : Téléverser des documents
  POST /v1/merchants/kyc/documents
  Corps : multipart { type: IDENTITY | BUSINESS_LICENSE | BANK_STATEMENT, file }
  → Envoi vers S3 / stockage local
  → Création du KycDocument (statut = UPLOADED)

Étape 2 : Lister les documents téléversés
  GET /v1/merchants/kyc/documents
  → Liste avec statuts

Étape 3 : Télécharger un document (propre)
  GET /v1/merchants/kyc/documents/{documentId}/url
  → URL présignée S3 (courte durée)

Étape 4 : Supprimer avant revue
  DELETE /v1/merchants/kyc/documents/{documentId}
  → Autorisé uniquement si statut = UPLOADED
  → HTTP 204 No Content (aucun corps de réponse)

Étape 5 : Déclencher la revue KYC
  POST /v1/merchants/kyc
  → Marchand passe à l'état KYC_SUBMITTED
  → Notifie la file de revue back-office

Étape 6 : Attendre l'approbation (cf. section 4.4)

Résultat :
  APPROUVÉ → rôle = MERCHANT_KYC_VERIFIED accordé
  REJETÉ   → documents rejetés, le marchand peut soumettre à nouveau
```

### 3.4 Initiation d'un paiement

**Acteur :** `MERCHANT`, `MERCHANT_KYC_VERIFIED`, `AGENT`
**Endpoint :** `POST /v1/payments`

```
Corps de la requête :
  {
    merchantReference: "ORDER-12345",     // clé d'idempotence (unique par marchand)
    phoneNumber:       "+22507123456",
    amount:            5000,
    currency:          "XOF",
    description:       "Paiement commande",
    operator:          "MTN_MOMO_CI"      // optionnel — détecté auto par préfixe
  }

Étapes de traitement :
  1. Contrôle d'idempotence
     → Recherche par merchantReference + merchantId
     → Existant & même montant → retourne le statut existant (pas de doublon)
     → Existant & montant différent → erreur CONFLICT

  2. Vérification des limites marchandes
     → Somme des SUCCESS aujourd'hui + nouveau montant > dailyLimit → LIMIT_EXCEEDED
     → Somme des SUCCESS ce mois + nouveau montant > monthlyLimit → LIMIT_EXCEEDED

  3. Détection de l'opérateur (si non fourni)
     → Préfixe du numéro → OperatorType (MTN_MOMO_CI, ORANGE_CI, …)

  4. Pré-screening AML
     → Screening sanctions (nom bénéficiaire si fourni) : fuzzy Jaro-Winkler + pays à haut risque
        · score ≥ 0.95 → CRITICAL SANCTIONS_HIT → rejet immédiat
        · score ∈ [0.80, 0.95[ → HIGH SANCTIONS_NEAR_MISS → alerte, paiement poursuivi
     → Seuil montant élevé (≥ 5M XOF → alerte AML créée)

  5. Calcul des frais
     → FeeService.calculate(merchantId, operator, country, amount)
     → Retourne feeAmount, netAmount = amount − feeAmount

  6. Chiffrement PII
     → phoneNumber → AES-256-GCM → blob chiffré
     → HMAC-SHA256 du numéro en clair → phoneNumberIndex (recherchable)

  7. Persistance de la Transaction (statut = PENDING)

  8. Appel opérateur (MobileMoneyOperator.initiatePayment)
     → Ex. : POST /collection/v1_0/requesttopay (MTN MoMo CI)
     → Circuit breaker Resilience4j appliqué
     → Retourne operatorReference (UUID)
     → Rejet opérateur → statut = FAILED

  9. Mise à jour Transaction.operatorReference

Réponse (nouveau paiement) :
  HTTP 201 Created
  Location: /api/v1/payments/AP-XXXXXXXX
  {
    reference:          "AP-XXXXXXXX",
    status:             "PENDING" | "PROCESSING" | "FAILED",
    operatorReference:  "...",
    ussdCode:           "*126*12345678#",
    instructions:       "Validez le paiement sur votre téléphone MTN MoMo CI"
  }

Réponse (replay idempotent — même merchantReference) :
  HTTP 200 OK
  Idempotent-Replayed: true
  { … même payload … }

Mode sandbox :
  → Clé API de test utilisée → SandboxContextHolder=true → schéma sandbox
  → Transaction écrite dans sandbox.transactions (jamais dans public.transactions)
  → Appel opérateur simulé (aucun argent réel déplacé)
  → Job de purge PII opère sur public uniquement → données sandbox intactes
```

**Consulter le statut :**
```
GET /v1/payments/{reference}
  → Retourne le statut courant (mis à jour par callback ou polling)
```

**Lister les transactions :**
```
GET /v1/payments?page=0&size=20&status=SUCCESS&from=2026-01-01&to=2026-03-20
  → Paginé, limité au marchand authentifié
```

**Vérification d'existence d'un numéro (sans exposition PII) :**
```
GET /v1/payments/phone-check?phoneNumber=+22507123456
  → Utilise l'index HMAC (pas de déchiffrement)
  → Retourne { exists: true | false }
```

### 3.5 Remboursement et annulation

**Acteur :** `MERCHANT`, `MERCHANT_KYC_VERIFIED`

#### Remboursement total

```
POST /v1/payments/{reference}/refund
  Corps : {}  (pas de montant = remboursement total)

Validations :
  → Transaction appartient au marchand
  → Statut = SUCCESS ou PARTIALLY_REFUNDED
  → Impossible de rembourser au-delà du montant d'origine

Traitement :
  1. Calcul du débit wallet :
     walletDebitAmount = montantActuel × netAmount / amount
     (proportionnel au net, les frais sont remboursés proportionnellement)

  2. Appel operateur (si supportsReversal() = true)
     → Crédite le portefeuille mobile du client
     → Best-effort : échec du reversal n'annule pas le remboursement comptable

  3. Mise à jour Transaction :
     → refundedAmount += montantActuel
     → statut : SUCCESS → PARTIALLY_REFUNDED → REFUNDED (si tout remboursé)
     → operatorRefundReference enregistré

  4. Débit wallet marchand (walletDebitAmount)

  5. Publication PaymentStatusChangedEvent → webhooks

Réponse : { reference, status: "REFUNDED", refundedAmount }
```

#### Remboursement partiel

```
POST /v1/payments/{reference}/refund
  Corps : { amount: 2500 }   // montant partiel dans la devise d'origine

Même flux mais :
  → montantActuel = 2500
  → walletDebitAmount calculé proportionnellement
  → statut = PARTIALLY_REFUNDED si solde restant > 0
```

#### Annulation (PENDING uniquement)

```
POST /v1/payments/{reference}/cancel
  → Autorisé uniquement si statut = PENDING (non encore envoyé à l'opérateur)
  → statut → CANCELLED
  → Aucun impact wallet (aucun crédit n'a eu lieu)
```

**Machine à états des transactions :**

```
         PENDING
        /        \
  PROCESSING   CANCELLED
      │
    SUCCESS ──► PARTIALLY_REFUNDED ──► REFUNDED
      │
    FAILED
    EXPIRED
```

### 3.6 Décaissement (payout unitaire)

**Acteur :** `MERCHANT_KYC_VERIFIED`, `AGENT`
**Endpoint :** `POST /v1/payouts`

> **Idempotence** : deux mécanismes sont supportés :
> - Header `Idempotency-Key: <valeur-unique>` (standard REST) — renvoyer la même clé retourne la réponse d'origine sans re-traitement.
> - Champ `merchantReference` dans le corps — comportement identique.
> Si `Idempotency-Key` est fourni mais que `merchantReference` est absent du corps, la clé est utilisée comme `merchantReference`.

```
Corps de la requête :
  {
    merchantReference: "PAYOUT-001",      // optionnel si Idempotency-Key fourni
    phoneNumber:       "+22507654321",
    amount:            10000,
    currency:          "XOF",
    operator:          "MTN_MOMO_CI",
    beneficiaryName:   "Jean Dupont",
    description:       "Paiement fournisseur"
  }

Traitement :
  1. Contrôle d'idempotence (merchantReference + merchantId)

  2. Screening sanctions sur le nom du bénéficiaire (fuzzy Jaro-Winkler, seuil blocage 0.95)

  3. Calcul des frais → netAmount = amount − feeAmount

  4. Vérification float opérateur :
     → OperatorFloat.availableBalance >= amount
     → Sinon : INSUFFICIENT_FLOAT

  5. Débit wallet (PENDING) :
     → availableBalance -= amount ; pendingBalance += amount
     → WalletTransaction(DEBIT_PAYOUT) enregistrée

  6. Chiffrement PII ; persistance Payout (statut = PROCESSING)

  7. Appel opérateur (MobileMoneyOperator.initiateDisbursement)
     → Ex. : POST /disbursement/v1_0/transfer (MTN MoMo CI)
     → Retourne operatorReference

  8. Sur callback/polling SUCCESS :
     → walletService.confirmPayout → pendingBalance -= amount
     → statut → SUCCESS
     → Publication PayoutStatusChangedEvent

  9. Sur FAILED :
     → walletService.refundPayout → pendingBalance -= amount ; availableBalance += amount
     → statut → FAILED

Réponse (nouveau payout) :
  HTTP 201 Created
  Location: /api/v1/payouts/PO-XXXXXXXX
  { reference, status, operatorReference, message }

Réponse (replay idempotent — même merchantReference ou même Idempotency-Key) :
  HTTP 200 OK
  Idempotent-Replayed: true
  { … même payload … }
```

**Consulter un décaissement :** `GET /v1/payouts/{reference}`

**Lister les décaissements :** `GET /v1/payouts?page=0&size=20`

### 3.7 Décaissement en masse (bulk payout)

**Acteur :** `MERCHANT_KYC_VERIFIED`

```
POST /v1/payouts/bulk
  Corps :
  {
    merchantBatchReference: "BATCH-2026-03",
    label:                  "Salaires Mars 2026",
    items: [
      { merchantReference, phoneNumber, amount, currency, operator, beneficiaryName },
      … (100 éléments max)
    ]
  }

Traitement synchrone :
  1. Validation du lot (max 100 items, merchantReference uniques en interne)
  2. Validation de chaque item (opérateur supporté, montant > 0, devise valide)
  3. Persistance BulkPayout (statut = PENDING) + tous les BulkPayoutItem
  4. Retour immédiat avec la référence du lot

Traitement asynchrone (post-commit) :
  5. Pour chaque item (séquentiel) :
     → Exécution du flux de payout unitaire complet
     → Mise à jour du statut de l'item (SUCCESS / FAILED)
     → Mise à jour des compteurs du lot (processedItems, successItems, failedItems)
  6. Statut final du lot :
     → Tout SUCCESS    → COMPLETED
     → Tout FAILED     → COMPLETED (avec failedItems = total)
     → Mixte           → PARTIAL_FAILURE

Suivi du lot :
  GET /v1/payouts/bulk/{batchReference}
  GET /v1/payouts/bulk/{batchReference}/items
  GET /v1/payouts/bulk                          (liste de tous les lots)
```

### 3.8 Gestion du wallet

**Acteur :** `MERCHANT`, `MERCHANT_KYC_VERIFIED`
**Auth :** JWT Bearer ou clé API

| Endpoint | Rôle requis | Description |
|----------|-------------|-------------|
| `GET /v1/wallet` | `MERCHANT` ou `MERCHANT_KYC_VERIFIED` | Tous les soldes (toutes devises) |
| `GET /v1/wallet/balance` | `MERCHANT` ou `MERCHANT_KYC_VERIFIED` | Solde dans une devise spécifique |
| `GET /v1/wallet/transactions` | `MERCHANT` ou `MERCHANT_KYC_VERIFIED` | Historique immuable des mouvements |
| `POST /v1/wallet/withdrawals` | **`MERCHANT_KYC_VERIFIED` uniquement** | Demande de retrait (KYC obligatoire) |
| `GET /v1/wallet/withdrawals` | `MERCHANT` ou `MERCHANT_KYC_VERIFIED` | Historique des demandes de retrait |

```
GET /v1/wallet
  → Tous les soldes (toutes devises)
  → { currency, availableBalance, pendingBalance }

GET /v1/wallet/balance?currency=XOF
  → Wallet unique (créé automatiquement si premier accès)

GET /v1/wallet/transactions?page=0&size=20
  → Historique immuable de tous les mouvements de wallet
  → Trié par createdAt DESC
```

**Types de mouvements wallet :**

| Type | Déclencheur | Sens |
|------|-------------|------|
| `CREDIT_PAYMENT` | Transaction → SUCCESS | + netAmount |
| `DEBIT_PAYOUT` | Payout initié | − amount (vers pending) |
| `CONFIRM_PAYOUT` | Payout SUCCESS | Libère le pending |
| `REFUND_PAYOUT` | Payout FAILED | Restaure l'available depuis pending |
| `DEBIT_REFUND` | Paiement remboursé | − walletDebitAmount |
| `DEBIT_WITHDRAWAL` | Retrait approuvé | − amount |
| `DEBIT_TRANSFER` | Transfert B2B envoyé | − amount |
| `CREDIT_TRANSFER` | Transfert B2B reçu | + amount |

### 3.9 Demande de retrait

**Acteur :** `MERCHANT_KYC_VERIFIED` (demande) → `FINANCE` / `ADMIN` / `SUPER_ADMIN` (approbation)

```
Étape 1 : Le marchand demande le retrait
  POST /v1/wallet/withdrawals
  Corps : { amount: 50000, currency: "XOF", description: "Retrait mensuel" }

  Validations :
    → wallet.availableBalance >= amount
    → KYC vérifié obligatoire

  Traitement :
    → Création MerchantWithdrawal (statut = PENDING)
    → Le wallet N'EST PAS encore débité (débité à l'approbation)

Étape 2 : Finance consulte les demandes
  GET /internal/finance/withdrawals?status=PENDING&currency=XOF

Étape 3 : Finance approuve
  PUT /internal/finance/withdrawals/{id}/approve
  → Débit wallet : availableBalance −= amount
  → statut → APPROVED
  → Notification marchand (webhook + email)

Étape 4 : Finance rejette
  PUT /internal/finance/withdrawals/{id}/reject
  Corps : { reason: "Coordonnées bancaires invalides" }
  → Aucun impact wallet
  → statut → REJECTED
  → Notification marchand

Étape 5 : Marquer comme exécuté (virement envoyé)
  PUT /internal/finance/withdrawals/{id}/execute
  → statut → EXECUTED ; executedAt défini

Consulter ses retraits :
  GET /v1/wallet/withdrawals
```

### 3.10 Transfert B2B inter-marchands

**Acteur :** `MERCHANT`, `MERCHANT_KYC_VERIFIED`

```
POST /v1/transfers
  Corps :
  {
    receiverMerchantId: "UUID du destinataire",
    amount:             25000,
    currency:           "XOF",
    description:        "Facture prestataire"
  }

Traitement (atomique) :
  1. Vérification sender.availableBalance >= amount
  2. Génération de la référence Ebithex (TRF-XXXX)
  3. walletService.transfer(sender, receiver, amount, currency, ref, description)
     → Débit émetteur : availableBalance −= amount (DEBIT_TRANSFER)
     → Crédit destinataire : availableBalance += amount (CREDIT_TRANSFER)
     → Les deux mouvements dans la même transaction DB (REQUIRES_NEW)

Réponse : { reference, status: "COMPLETED", senderBalance, receiverBalance }
```

### 3.11 Gestion des webhooks

**Acteur :** `MERCHANT`, `MERCHANT_KYC_VERIFIED`
**Scope requis par opération :** voir tableau ci-dessous

| Action | Endpoint | Scope API key requis | Code HTTP |
|--------|----------|----------------------|-----------|
| Enregistrer | `POST /v1/webhooks` | `FULL_ACCESS` | **201 Created** |
| Lister | `GET /v1/webhooks` | `WEBHOOKS_READ` ou `FULL_ACCESS` | 200 |
| Désactiver | `DELETE /v1/webhooks/{id}` | `FULL_ACCESS` | 200 |
| Historique livraisons | `GET /v1/webhooks/{id}/deliveries` | `WEBHOOKS_READ` ou `FULL_ACCESS` | 200 |
| Envoyer un test | `POST /v1/webhooks/{id}/test` | `FULL_ACCESS` | 200 |

```
Enregistrer un endpoint :
  POST /v1/webhooks
  Corps :
  {
    url:    "https://monserveur.com/hooks/ebithex",
    events: ["payment.success", "payment.failed", "payout.success", "payout.failed"]
  }

  Traitement :
    → Validation anti-SSRF : blocage des IPs privées / loopback
    → Génération d'un secret de signature 256 bits (retourné UNE SEULE FOIS)
    → Création WebhookEndpoint (active=true)

  Réponse : 201 Created
    Location: /api/v1/webhooks/{id}
    { id, url, events, signingSecret, active, createdAt }

Lister les endpoints :
  GET /v1/webhooks

Désactiver un endpoint :
  DELETE /v1/webhooks/{id}
  → Passe active=false (suppression douce)

Historique des livraisons :
  GET /v1/webhooks/{id}/deliveries?page=0&size=20  (max size=100)
  → Tentatives, codes HTTP, erreurs, horodatages

Événement de test :
  POST /v1/webhooks/{id}/test
  → Envoie un événement synthétique webhook.test
  → Retourne { delivered: true, statusCode: 200 }
```

**Vérification de signature (côté serveur du marchand) :**
```
Header : X-Ebithex-Signature: HMAC-SHA256(corps, signingSecret)
→ Le serveur marchand vérifie le header pour garantir l'authenticité
```

**Événements supportés :**

| Événement | Déclencheur |
|-----------|-------------|
| `payment.success` | Transaction → SUCCESS |
| `payment.failed` | Transaction → FAILED |
| `payment.refunded` | Transaction → REFUNDED / PARTIALLY_REFUNDED |
| `payout.success` | Payout → SUCCESS |
| `payout.failed` | Payout → FAILED |
| `withdrawal.approved` | Retrait → APPROVED |
| `withdrawal.rejected` | Retrait → REJECTED |
| `kyc.approved` | KYC → APPROUVÉ |
| `kyc.rejected` | KYC → REJETÉ |
| `dispute.resolved` | Litige → RESOLVED |
| `webhook.test` | Déclenchement manuel de test |

### 3.12 Gestion des litiges

**Acteur :** `MERCHANT_KYC_VERIFIED`

```
Ouvrir un litige :
  POST /v1/disputes
  Corps :
  {
    transactionReference: "AP-XXXXXXXX",
    reason:    "Transaction non reçue par le client",
    evidence:  "Le client a appelé pour confirmer..."
  }
  → statut = OPEN

Consulter les litiges :
  GET /v1/disputes
  GET /v1/disputes/{id}

Annuler (seulement si OPEN) :
  DELETE /v1/disputes/{id}
  → Transition : OPEN → CANCELLED

Cycle de vie :
  OPEN → UNDER_REVIEW (back-office prend en charge) → RESOLVED
  OPEN → CANCELLED (marchand annule)
```

### 3.13 Conformité RGPD

**Acteur :** `MERCHANT`, `MERCHANT_KYC_VERIFIED`
**Auth :** JWT Bearer ou clé API

#### Droit d'accès — Art. 15

```
GET /v1/merchants/gdpr/export
  → Retourne toutes les données personnelles associées au compte connecté :
    • Section "account" : merchantId, businessName, email, country, kycStatus, active,
      createdAt, updatedAt
    • Section "gdpr" : exportedAt, retentionYears, dataController, contact DPO
  → L'export est tracé dans les journaux d'audit (GDPR_EXPORT_REQUESTED)
  → Les numéros de téléphone sont retournés chiffrés (AES-256-GCM) tels que stockés

Réponse 200 :
  {
    "account": {
      "merchantId":   "uuid",
      "businessName": "Bakery Abidjan",
      "email":        "merchant@example.com",
      "country":      "CI",
      "kycStatus":    "APPROVED",
      "active":       true,
      "createdAt":    "2026-01-15T10:30:00",
      "updatedAt":    "2026-03-01T08:00:00"
    },
    "gdpr": {
      "exportedAt":     "2026-04-04T14:00:00",
      "retentionYears": "5",
      "dataController": "Ebithex SAS",
      "contact":        "dpo@ebithex.io"
    }
  }
```

#### Droit à l'effacement — Art. 17

```
DELETE /v1/merchants/gdpr/data
  → Anonymise les données personnelles identifiables du compte :
    • email        → deleted+{merchantId}@ebithex.invalid
    • businessName → [SUPPRIMÉ]
    • webhookUrl   → null
    • active       → false (compte désactivé, connexion impossible)
  → Irréversible
  → Traçabilité : GDPR_ANONYMIZATION_REQUESTED + GDPR_ANONYMIZATION_COMPLETED dans l'audit

IMPORTANT — Données conservées conformément à la réglementation BCEAO :
  → Les transactions, wallets, payouts et audit logs sont conservés 10 ans
    (BCEAO instruction 008-05-2015 — obligations de conservation des données financières)
  → Aucune suppression totale du compte possible tant que des données financières existent

Réponse 200 :
  { "message": "Données personnelles anonymisées. Votre compte a été désactivé." }

Après anonymisation :
  → Connexion avec l'ancienne adresse email impossible (email n'existe plus)
  → Les clés API du compte restent en base mais le compte inactif rejette les requêtes
```

---

## 4. Flux back-office

### 4.1 Authentification staff (2FA obligatoire)

Tous les utilisateurs staff doivent compléter une authentification en deux étapes :

```
Étape 1 — Mot de passe :
  POST /internal/auth/login  { email, password }
  → Succès : OTP envoyé par email/SMS, tempToken retourné

Étape 2 — OTP :
  POST /internal/auth/login/verify-otp  { tempToken, code }
  → Succès : JWT complet retourné (8h, claim de rôle inclus)

Déconnexion :
  POST /internal/auth/logout
  → JWT ajouté à la liste de blocage (révocation immédiate)
```

### 4.2 Gestion des utilisateurs staff (SUPER_ADMIN uniquement)

```
Lister le staff :
  GET /internal/staff-users         [ADMIN, SUPER_ADMIN]
  GET /internal/staff-users/{id}    [ADMIN, SUPER_ADMIN]

Créer un compte staff :            [SUPER_ADMIN uniquement]
  POST /internal/staff-users
  Corps :
  {
    email:   "agent@ebithex.com",
    role:    "SUPPORT",
    country: "CI"          // obligatoire uniquement pour le rôle COUNTRY_ADMIN
  }
  → Mot de passe temporaire généré et retourné une seule fois
  → Compte active=true, 2FA imposée à la prochaine connexion

Modifier un compte staff :         [SUPER_ADMIN uniquement]
  PUT /internal/staff-users/{id}
  Corps : { role, country, active, twoFactorEnabled }
  → Impossible de désactiver son propre compte (CANNOT_DEACTIVATE_SELF)

Désactiver un compte staff :       [SUPER_ADMIN uniquement]
  DELETE /internal/staff-users/{id}
  → active=false (suppression douce)
  → Tous les tokens immédiatement révoqués

Réinitialiser le mot de passe :    [SUPER_ADMIN uniquement]
  POST /internal/staff-users/{id}/reset-password
  → Nouveau mot de passe temporaire (retourné en clair, une seule fois)
  → Ancien mot de passe invalidé
```

### 4.3 Gestion des marchands

**Acteurs :** `COUNTRY_ADMIN` (pays propre), `ADMIN`, `SUPER_ADMIN`

```
Lister les marchands :
  GET /internal/merchants?country=CI&page=0&size=20
  → COUNTRY_ADMIN : filtré sur son pays uniquement
  → ADMIN / SUPER_ADMIN : tous pays

Détail d'un marchand :
  GET /internal/merchants/{id}

Activer / Désactiver :
  PUT /internal/merchants/{id}/activate
  PUT /internal/merchants/{id}/deactivate
  → Flag active mis à jour
  → Marchand désactivé : clés API rejetées immédiatement

Approuver / Rejeter le KYC :
  PUT /internal/merchants/{id}/kyc/approve
  → kycVerified=true ; rôle MERCHANT_KYC_VERIFIED accordé
  → Notification marchand (email + webhook kyc.approved)

  PUT /internal/merchants/{id}/kyc/reject  { reason }
  → kycVerified=false ; le marchand peut soumettre à nouveau
  → Notification marchand (email + webhook kyc.rejected)

Activer / Désactiver le mode test :
  PUT /internal/merchants/{id}/test-mode  { enabled: true }   [SUPER_ADMIN uniquement]

Définir les limites de paiement :
  PUT /internal/merchants/{id}/limits
  Corps : { dailyLimit: 1000000, monthlyLimit: 10000000 }     [ADMIN, SUPER_ADMIN]

Lister les clés API d'un marchand :
  GET /internal/merchants/{id}/api-keys                      [ADMIN, SUPER_ADMIN]
  → Toutes les clés actives et inactives du marchand

Révoquer toutes les clés API (urgence) :
  POST /internal/merchants/{id}/api-key/revoke               [ADMIN, SUPER_ADMIN]
  → Toutes les clés désactivées immédiatement, sans délai de grâce
  → Action tracée dans audit_logs (API_KEYS_REVOKED)

Définir la politique de rotation forcée :
  PUT /internal/merchants/{id}/api-keys/{keyId}/rotation-policy [SUPER_ADMIN]
  Corps : { rotationRequiredDays: 90 }
  → Le job ApiKeyAgingJob désactivera automatiquement la clé après ce délai
```

### 4.4 Revue KYC

**Acteurs :** `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`, `SUPPORT` (lecture seule)

```
File de revue (documents en attente) :
  GET /internal/merchants/kyc/documents/pending
  → Documents avec statut = UNDER_REVIEW, triés par uploadedAt

Voir les documents d'un marchand :
  GET /internal/merchants/{merchantId}/kyc/documents

Télécharger un document :
  GET /internal/merchants/kyc/documents/{documentId}/url
  → URL présignée S3 (courte durée)

Statuer sur un document :             [COUNTRY_ADMIN, ADMIN, SUPER_ADMIN]
  PUT /internal/merchants/kyc/documents/{documentId}/review
  Corps :
  {
    status:          "APPROVED" | "REJECTED",
    rejectionReason: "Document illisible"   // obligatoire si REJECTED
  }
  → reviewedAt, reviewedBy définis
  → Si tous les docs APPROVED → KYC marchand validé automatiquement
  → Si un doc REJECTED → marchand notifié
```

### 4.5 Finance — vue des wallets et résumé

**Acteurs :** `FINANCE`, `ADMIN`, `SUPER_ADMIN`

```
Tous les wallets :
  GET /internal/finance/wallets?page=0&size=20
  GET /internal/finance/wallets/{merchantId}
  → Soldes en temps réel (availableBalance, pendingBalance, currency)

Résumé financier global :
  GET /internal/finance/summary
  → Total des soldes par devise, retraits en attente, payouts du jour
```

### 4.6 Approbation des retraits

**Acteurs :** `FINANCE`, `ADMIN`, `SUPER_ADMIN`

```
Lister les retraits en attente :
  GET /internal/finance/withdrawals?status=PENDING&currency=XOF&merchantId=UUID

Approuver :
  PUT /internal/finance/withdrawals/{id}/approve
  → Wallet immédiatement débité
  → statut → APPROVED
  → Marchand notifié (webhook + email)

Rejeter :
  PUT /internal/finance/withdrawals/{id}/reject
  Corps : { reason: "…" }
  → Aucun impact wallet
  → statut → REJECTED
  → Marchand notifié

Marquer comme exécuté (virement envoyé physiquement) :
  PUT /internal/finance/withdrawals/{id}/execute
  → statut → EXECUTED ; executedAt défini
```

### 4.7 Gestion du float opérateur

**Acteurs :** `FINANCE`, `ADMIN`, `SUPER_ADMIN`

```
Voir les niveaux de float :
  GET /internal/finance/float
  → OperatorFloat par opérateur : availableBalance, lowBalanceThreshold, lastCheckedAt

Voir les alertes (float bas) :
  GET /internal/finance/float/alerts
  → Opérateurs où availableBalance < lowBalanceThreshold

Mettre à jour le float (après rechargement manuel) :
  PUT /internal/finance/float/{operator}
  Corps : { availableBalance: 5000000, lowBalanceThreshold: 500000 }
  → Mise à jour manuelle ; seuil configurable par opérateur
```

### 4.8 Réconciliation et relevés opérateurs

**Acteurs :** `RECONCILIATION`, `FINANCE`, `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`

#### Import et réconciliation d'un relevé opérateur

```
Étape 1 : Importer le CSV opérateur
  POST /internal/reconciliation/statements/import
  Corps : multipart { operator: "MTN_MOMO_CI", date: "2026-03-19", file: relevé.csv }

  Format CSV attendu :
    operator_reference, amount, currency, status, transaction_date

  Traitement :
    → Idempotence : (operator, date) unique → STATEMENT_ALREADY_EXISTS si doublon
    → Parsing CSV → validation en-têtes → INVALID_CSV si malformé
    → Persistance OperatorStatement (statut = PENDING) + toutes les lignes
    → Retourne statementId

Étape 2 : Réconcilier
  POST /internal/reconciliation/statements/{id}/reconcile

  Traitement :
    → Pour chaque ligne OperatorStatementLine :
        → Recherche Transaction par operatorReference
        → Non trouvée   → MISSING_IN_EBITHEX
        → Trouvée, montant concordant (écart ≤ 0,01)
                        → MATCHED
        → Trouvée, écart montant > 0,01
                        → AMOUNT_MISMATCH
        → Trouvée, statut incompatible
                        → STATUS_MISMATCH
    → Mise à jour OperatorStatement :
        matchedLines, discrepancyLines, totalLines
        statut : RECONCILED (0 anomalie) ou DISCREPANCY_FOUND
```

**Cycle automatique (job planifié à 02:30 UTC) :**
```
reconcileAllPending()
  → Traite tous les relevés en statut PENDING
  → Même logique que l'appel manuel POST /…/reconcile
```

**Autres endpoints de réconciliation :**
```
Lister les relevés :
  GET /internal/reconciliation/statements?operator=MTN_MOMO_CI&page=0&size=20

Détail d'un relevé :
  GET /internal/reconciliation/statements/{id}

Anomalies d'un relevé :
  GET /internal/reconciliation/statements/{id}/discrepancies?page=0&size=20
  → Uniquement les lignes avec discrepancyType ≠ MATCHED

Vérifier le solde opérateur :
  GET /internal/reconciliation/operators/{operator}/balance

Rapports transactions / payouts :
  GET /internal/reconciliation/transactions?from=…&to=…&operator=…&status=…
  GET /internal/reconciliation/payouts?from=…
  GET /internal/reconciliation/summary?from=…&to=…

Exports CSV :                          [RECONCILIATION, ADMIN, SUPER_ADMIN]
  GET /internal/reconciliation/export/transactions
  GET /internal/reconciliation/export/payouts
```

**Types d'anomalies :**

| Type | Signification | Action recommandée |
|------|--------------|-------------------|
| `MATCHED` | Concordance parfaite | Aucune |
| `MISSING_IN_EBITHEX` | Transaction chez l'opérateur, absente chez Ebithex | Vérifier callbacks tardifs ; contacter l'opérateur si absent après 48h |
| `AMOUNT_MISMATCH` | Écart de montant (> 0,01) | Vérifier frais opérateur ; ouvrir un litige si ponctuel |
| `STATUS_MISMATCH` | Opérateur=SUCCESS mais Ebithex=FAILED (ou inverse) | Synchroniser via support ; alerter l'engineering si récurrent |

### 4.9 Cycles de règlement (settlement)

**Acteurs :** `FINANCE`, `ADMIN`, `SUPER_ADMIN`

```
Lancer un cycle de règlement :        [ADMIN, SUPER_ADMIN]
  POST /internal/settlement/run
  Corps : { from: "2026-03-01T00:00:00", to: "2026-03-19T23:59:59" }
  → Agrégation des payouts SUCCESS par opérateur sur la période
  → Création d'un SettlementBatch par opérateur (statut = PENDING)
  → Création d'une SettlementEntry par marchand
  → Retourne le nombre de lots créés

Lister les lots :
  GET /internal/settlement?operator=MTN_MOMO_CI&status=PENDING

Détail d'un lot :
  GET /internal/settlement/{id}
  GET /internal/settlement/{id}/entries

Marquer comme réglé :                 [FINANCE, ADMIN, SUPER_ADMIN]
  POST /internal/settlement/{id}/settle
  → statut → SETTLED ; settledAt défini
```

### 4.10 File des webhooks en échec (Dead Letter Queue)

**Acteurs :** `SUPPORT`, `ADMIN`, `SUPER_ADMIN`

```
Voir les livraisons en échec (≥ 5 tentatives sans succès) :
  GET /internal/webhooks/dead-letters
  → Liste des WebhookDelivery avec attemptCount ≥ 5 et non livrés

Relancer manuellement :               [ADMIN, SUPER_ADMIN]
  POST /internal/webhooks/dead-letters/{id}/retry
  → Remet en file ; tentative exécutée immédiatement via @Async
```

**Politique de retry automatique :**

| Tentative | Délai avant nouvelle tentative |
|-----------|-------------------------------|
| 1 (initiale) | Immédiat |
| 2 | +30 secondes |
| 3 | +2 minutes |
| 4 | +10 minutes |
| 5 | +1 heure |
| ≥ 6 | Dead Letter — retry manuel requis |

### 4.11 Opérations support client

**Acteurs :** `SUPPORT`, `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`

```
Consulter n'importe quelle transaction :
  GET /internal/support/transactions/{reference}
  → Détail complet, y compris numéro de téléphone déchiffré (accès privilégié)

Consulter n'importe quel payout :
  GET /internal/support/payouts/{reference}

Historique des transactions d'un marchand :
  GET /internal/support/merchants/{merchantId}/transactions?page=0&size=20

Historique des payouts d'un marchand :
  GET /internal/support/merchants/{merchantId}/payouts?page=0&size=20
```

### 4.12 Revue des litiges

**Acteurs :** `SUPPORT`, `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`

```
Lister tous les litiges :
  GET /internal/disputes?status=OPEN&merchantId=UUID&from=2026-01-01&to=2026-03-20

Détail d'un litige :
  GET /internal/disputes/{id}

Prendre en charge (OPEN → UNDER_REVIEW) :
  PUT /internal/disputes/{id}/review
  → Statut mis à jour, email du relecteur enregistré

Résoudre le litige :
  PUT /internal/disputes/{id}/resolve
  Corps : { resolution: "COMPENSATED", note: "Remboursement de 5 000 XOF effectué" }
  → statut → RESOLVED ; resolvedAt défini
  → Marchand notifié via webhook (dispute.resolved)
```

### 4.13 Conformité AML

**Acteurs :** `COMPLIANCE`, `ADMIN`, `SUPER_ADMIN`

```
Screening AML automatique (déclenché à chaque paiement, hors sandbox) :
  → Sanctions (prioritaire) :
       · pays à haut risque → CRITICAL (blocage immédiat)
       · nom fuzzy Jaro-Winkler ≥ 0.95 → CRITICAL SANCTIONS_HIT (blocage)
       · score ∈ [0.80, 0.95[ → HIGH SANCTIONS_NEAR_MISS (near-miss, poursuite)
  → Vélocité : fréquence de transactions anormale (horaire/quotidienne/hebdomadaire)
  → Structuring : transactions multiples juste sous le seuil HIGH_AMOUNT
  → Montant élevé : transaction individuelle ≥ seuil configuré (défaut 5 M XOF)
  → Si déclenchée : AmlAlert créée (statut = OPEN)
  → Transactions en mode sandbox : ignorées

Consulter les alertes :
  GET /internal/aml/alerts?status=NEW&type=HIGH_AMOUNT

Détail d'une alerte :
  GET /internal/aml/alerts/{id}

Statuer sur une alerte :
  PUT /internal/aml/alerts/{id}
  Corps :
  {
    status:         "CLEARED" | "REPORTED",
    resolutionNote: "Transaction commerciale légitime vérifiée"
  }
  → CLEARED  : alerte clôturée, transaction débloquée
  → REPORTED : signalée pour rapport réglementaire (SAR généré, transmis CENTIF)
```

### 4.14 Gestion des sanctions

**Acteurs :** `COMPLIANCE`, `ADMIN`, `SUPER_ADMIN`

```
Consulter les entrées actives (toutes listes) :
  GET /internal/sanctions/entries

Vérification manuelle d'un nom ou d'un pays :
  POST /internal/sanctions/check
  Corps : { "name": "John Doe", "countryCode": "IR" }
  → Retourne {
      hit: true|false,
      requiresBlock: true|false,   ← score ≥ 0.95 = blocage ; [0.80, 0.95[ = near-miss
      score: 0.0–1.0,              ← score Jaro-Winkler (0.0 si pays uniquement)
      matchedList: "OFAC_SDN",     ← liste d'origine de la correspondance
      matchedEntity: "...",        ← nom officiel matché
      reason: "Pays à haut risque : IR"
    }

Purger une liste (avant rechargement manuel) :
  DELETE /internal/sanctions/entries/{listName}
  → listName : OFAC_SDN | UN_CONSOLIDATED | EU_CONSOLIDATED | ECOWAS_LOCAL | CUSTOM

───────────────────────────────────────────────────────────
Synchronisation automatique (hebdomadaire, dimanche 05:00 UTC)
  → SanctionsListSyncJob télécharge et recharge OFAC_SDN,
    UN_CONSOLIDATED, EU_CONSOLIDATED depuis leurs sources officielles

Déclencher une synchronisation manuellement :
  POST /internal/sanctions/sync
  → Synchronise les 3 listes automatiques
  → Retourne un log par liste { listName, status, entriesImported, durationMs }

  POST /internal/sanctions/sync/{listName}
  → Synchronise uniquement la liste spécifiée (OFAC_SDN, UN_CONSOLIDATED ou EU_CONSOLIDATED)

Consulter le statut des synchronisations :
  GET /internal/sanctions/sync/status
  → Dernier log de synchronisation par liste

  GET /internal/sanctions/sync/history?limit=20
  → 20 derniers logs toutes listes confondues

───────────────────────────────────────────────────────────
Import manuel (ECOWAS_LOCAL, CUSTOM — et tout autre liste) :
  POST /internal/sanctions/import/{listName}
  Corps : { "content": "<CSV_CONTENT>" }

  Format CSV (sans entête) :
    entityName,aliases,countryCode,entityType
    "KONAN AMARA","ALIAS A|ALIAS B",CI,INDIVIDUAL
    "SOCIÉTÉ FANTÔME",,NG,ENTITY
  (aliases séparés par |, commentaires commençant par # ignorés)
```

### 4.15 Configuration des règles tarifaires

**Acteurs :** `ADMIN`, `SUPER_ADMIN`

```
Lister les règles :
  GET /internal/config/fee-rules

Détail d'une règle :
  GET /internal/config/fee-rules/{id}

Créer une règle :                      [SUPER_ADMIN uniquement]
  POST /internal/config/fee-rules
  Corps :
  {
    operator:   "MTN_MOMO_CI",   // null = s'applique à tous
    country:    "CI",            // null = s'applique à tous
    merchantId: null,            // null = défaut, UUID = spécifique au marchand
    minAmount:  1,
    maxAmount:  999999,
    feeRate:    0.015,           // 1,5 %
    flatFee:    0,               // frais fixe additionnel
    priority:   10               // plus élevé = évalué en premier
  }

Modifier :                             [SUPER_ADMIN uniquement]
  PUT /internal/config/fee-rules/{id}

Supprimer :                            [SUPER_ADMIN uniquement]
  DELETE /internal/config/fee-rules/{id}

Logique de calcul :
  → Règles évaluées par priorité décroissante
  → Première règle concordante (operator, country, merchantId, plage montant) appliquée
  → frais = amount × feeRate + flatFee
  → netAmount = amount − frais
```

### 4.16 Journaux d'audit

**Acteurs :** `ADMIN`, `SUPER_ADMIN`

```
Tous les journaux :
  GET /internal/audit-logs?page=0&size=20&action=LOGIN&from=2026-03-01

Journal d'un utilisateur staff :
  GET /internal/audit-logs/staff-users/{staffUserId}

Journal d'une entité :
  GET /internal/audit-logs/{entityType}/{entityId}
  → entityType : MERCHANT, TRANSACTION, PAYOUT, STAFF_USER, WITHDRAWAL, …
  → Toutes les actions sur l'entité avec horodatage et acteur
```

### 4.17 Rapports réglementaires (BCEAO / UEMOA)

**Acteurs :** `FINANCE`, `ADMIN`, `SUPER_ADMIN`

```
Rapport mensuel des transactions :
  GET /internal/regulatory/reports/transactions?year=2026&month=3
  → Agrégé par opérateur / devise / statut ; format compatible BCEAO

Rapport mensuel des payouts :
  GET /internal/regulatory/reports/payouts?year=2026&month=3

Rapport CTR (Currency Transaction Report) :
  GET /internal/regulatory/reports/ctr?from=2026-03-01&to=2026-03-19&threshold=5000000
  → Toutes les transactions ≥ seuil (défaut 5 M XOF) ; requis par l'UEMOA

Rapport SAR (Suspicious Activity Report) :
  GET /internal/regulatory/reports/aml-sar?from=2026-03-01&to=2026-03-19
  → Alertes AML avec statut = REPORTED ; soumises au CENTIF

Résumé réglementaire mensuel :
  GET /internal/regulatory/reports/summary?year=2026&month=3
  → Vue consolidée pour le dépôt réglementaire mensuel
```

### 4.18 Purge PII (rétention des données)

**Acteur :** Job planifié (cron) — pas d'endpoint HTTP

```
Déclenchement : @Scheduled — quotidien en période de faible trafic
Seuil : maintenant − 5 ans

PiiRetentionJob.purgeTransactions(cutoff) :
  → SandboxContextHolder.set(false) → pool prod (search_path TO public)
  → Sélection en lot :
      public.transactions WHERE created_at < cutoff
                           AND pii_purged_at IS NULL
  → Pour chaque enregistrement :
      phoneNumber      ← AES-256-GCM.chiffrer("PURGED")
      phoneNumberIndex ← null
      piiPurgedAt      ← maintenant()
  → Idempotent : le filtre pii_purged_at IS NULL évite la double purge
  → sandbox.transactions invisible (schéma différent)

PiiRetentionJob.purgePayouts(cutoff) :
  → Même logique sur public.payouts

Exceptions :
  → Données sandbox : jamais purgées (schéma séparé, hors portée du pool prod)
  → Déjà purgé (pii_purged_at IS NOT NULL) : ignoré
```

### 4.19 Rotation des clés AES

**Acteur :** `SUPER_ADMIN`
**Endpoint :** `POST /internal/admin/key-rotation`

```
Traitement :
  1. Génération d'une nouvelle clé maître AES-256-GCM
  2. Récupération de tous les enregistrements avec phoneNumber chiffré
     (transactions + payouts + éléments de bulk payout)
  3. Pour chaque enregistrement :
     → Déchiffrement avec la clé courante
     → Rechiffrement avec la nouvelle clé
     → Persistance du nouveau texte chiffré
  4. Activation de la nouvelle clé
  5. Retourne { reEncryptedCount, durationMs }

Remarques :
  → À exécuter pendant une fenêtre de faible trafic
  → Idempotent par enregistrement
  → L'index HMAC reste inchangé (clé HMAC tournée séparément)
```

---

## 5. Flux événementiels

### 5.1 Paiement SUCCESS → Crédit wallet

```
PaymentService.markSuccess(txId)
  → Transaction.statut = SUCCESS
  → Publication PaymentStatusChangedEvent (via ApplicationEventPublisher)
  → Transaction DB validée (commit)

WalletEventListener.onPaymentSuccess
  [@TransactionalEventListener(AFTER_COMMIT), @Async]
  → walletService.creditPayment(merchantId, netAmount, ebithexRef, currency)
  → Wallet.availableBalance += netAmount
  → WalletTransaction(CREDIT_PAYMENT) persistée

WebhookEventListener.onPaymentStatusChanged
  [@TransactionalEventListener(AFTER_COMMIT), @Async]
  → WebhookService livre l'événement payment.success à tous les endpoints actifs
  → Livraison persistée (avec retry exponentiel si échec)
```

### 5.2 Payout SUCCESS → Confirmation wallet

```
PayoutService.markSuccess(payoutId)
  → Payout.statut = SUCCESS
  → Publication PayoutStatusChangedEvent

WalletEventListener.onPayoutSuccess [AFTER_COMMIT, @Async]
  → walletService.confirmPayout → pendingBalance −= amount
  → WalletTransaction(CONFIRM_PAYOUT)

WebhookEventListener [AFTER_COMMIT, @Async]
  → Événement payout.success livré au marchand
```

### 5.3 Livraison webhook et retry

```
Livraison initiale :
  → HTTP POST vers l'URL de l'endpoint (timeout : 10 s)
  → Corps : { event, data, timestamp, version }
  → Headers : X-Ebithex-Signature, X-Ebithex-Event, X-Request-ID

Succès : HTTP 2xx
  → WebhookDelivery.deliveredAt défini ; attemptCount = 1

Échec : non-2xx ou timeout
  → lastError enregistré
  → nextRetryAt = maintenant + backoff exponentiel
  → Après 5 échecs → Dead Letter Queue (retry manuel)
```

### 5.4 Polling du statut opérateur

```
Job planifié (polling des transactions PROCESSING anciennes) :
  Pour chaque transaction PROCESSING > N minutes :
    → MobileMoneyOperator.checkStatus(operatorReference)
    → Mapping statut opérateur → TransactionStatus :
        "SUCCESSFUL" | "COMPLETED" → SUCCESS
        "FAILED"                   → FAILED
        autres                     → PROCESSING (attendre prochain cycle)
    → Sur changement : mise à jour Transaction, publication événement

Pour les payouts :
    → MobileMoneyOperator.checkDisbursementStatus(operatorReference)
    → Même logique de mapping
```

---

## 6. Callbacks opérateurs

```
POST /v1/callbacks/{operator}
  → Sécurisé par signature HMAC (spécifique à l'opérateur, pas JWT/clé API)
  → Parsing du payload de callback
  → Mise à jour du statut Transaction ou Payout
  → Publication de l'événement de changement de statut
     → Crédit / remboursement wallet
     → Livraison webhook marchand

Exemples d'endpoints :
  POST /v1/callbacks/mtn-momo-ci/collection    (paiements MTN CI)
  POST /v1/callbacks/mtn-momo-ci/disbursement  (payouts MTN CI)

Note : L'URL de callback est enregistrée auprès de l'opérateur lors de
       l'initiation du paiement (header X-Callback-Url).
```

---

## 7. Considérations transverses

### 7.1 Chiffrement PII

```
Stockage :
  phoneNumber      → AES-256-GCM (blob opaque, non lisible)
  phoneNumberIndex → HMAC-SHA256 du numéro en clair (déterministe, pour recherche)

Recherche par numéro :
  → Calcul du HMAC de la requête → correspondance sur phoneNumberIndex
  → Aucun déchiffrement nécessaire pour la vérification d'existence

Déchiffrement (support / back-office) :
  → EncryptionService.decrypt() — accès journalisé dans l'audit log
```

### 7.2 Idempotence

| Entité | Clé d'idempotence | Portée |
|--------|-------------------|--------|
| Transaction | `merchantReference` | Par marchand |
| Payout | `merchantReference` | Par marchand |
| BulkPayout | `merchantBatchReference` | Par marchand |
| WalletTransaction | `ebithexReference` | Global |
| OperatorStatement | `(operator, statementDate)` | Global |

### 7.3 Limitation du débit (rate limiting)

```
RateLimitFilter (par principal) :
  → Clés API : N req/min configurable (défaut : 100)
  → Tokens JWT : N req/min configurable
  → Headers de réponse :
      X-RateLimit-Limit     : N
      X-RateLimit-Remaining : R
      X-RateLimit-Reset     : epoch
  → HTTP 429 si dépassé, avec header Retry-After
```

### 7.4 Limites de paiement par marchand

```
dailyPaymentLimit   : somme des montants SUCCESS aujourd'hui
monthlyPaymentLimit : somme des montants SUCCESS ce mois

À l'initiation d'un paiement :
  → Somme today + nouveau montant > dailyLimit   → LIMIT_EXCEEDED
  → Somme month + nouveau montant > monthlyLimit → LIMIT_EXCEEDED
```

### 7.5 Protection anti-SSRF (webhooks)

```
WebhookUrlValidator :
  → Résolution du nom d'hôte de l'URL
  → Blocage si l'IP résolue est :
      127.0.0.0/8     (loopback)
      10.0.0.0/8      (RFC-1918 privé)
      172.16.0.0/12   (RFC-1918 privé)
      192.168.0.0/16  (RFC-1918 privé)
      169.254.0.0/16  (lien local)
      ::1             (loopback IPv6)
  → Hôte non résolu : avertissement loggé, enregistrement autorisé
    (échouera proprement à la livraison)
```

### 7.6 Circuit breakers (Resilience4j)

```
operator-payment     (collection + reversal)
  → Seuil taux d'échec : 50 %
  → Durée état ouvert : 30 s

operator-disbursement (décaissements)
  → Seuil taux d'échec : 50 %
  → Durée état ouvert : 60 s
```

### 7.7 Mode test

```
Activé par :
  → Utilisation d'une clé API de type TEST (préfixe ap_test_ — stockée dans api_keys)
  → Activation du flag testMode sur le compte marchand (back-office SUPER_ADMIN)

Effets :
  → SandboxContextHolder.set(true) → pool sandbox (search_path TO sandbox, public)
  → Toutes les écritures transactionnelles vont dans le schéma sandbox
  → Appels opérateurs simulés (aucun argent réel)
  → Job de purge PII opère sur public uniquement → données sandbox intactes
  → Screening AML ignoré
  → Compteurs de limites daily/monthly non incrémentés
```

---

## 8. Matrice rôles × endpoints

### API marchande (`/v1/`)

| Endpoint | `MERCHANT` | `MERCHANT_KYC_VERIFIED` | `AGENT` |
|----------|:---:|:---:|:---:|
| `POST /v1/auth/register` | public | public | public |
| `POST /v1/auth/login` | public | public | public |
| `POST /v1/auth/logout` | ✓ | ✓ | ✓ |
| `POST /v1/auth/refresh` | public | public | public |
| `GET /v1/auth/api-keys` | ✓ | ✓ | — |
| `POST /v1/auth/api-keys` | ✓ | ✓ | — |
| `POST /v1/auth/api-keys/{keyId}/rotate` | ✓ | ✓ | — |
| `DELETE /v1/auth/api-keys/{keyId}` | ✓ | ✓ | — |
| `PUT /v1/auth/api-keys/{keyId}/scopes` | ✓ | ✓ | — |
| `PUT /v1/auth/api-keys/{keyId}/allowed-ips` | ✓ | ✓ | — |
| `PUT /v1/auth/api-keys/{keyId}/expires-at` | ✓ | ✓ | — |
| `GET /v1/merchants/me` | ✓ | ✓ | — |
| `POST /v1/merchants/kyc` | ✓ | ✓ | — |
| `POST /v1/merchants/kyc/documents` | ✓ | ✓ | — |
| `GET /v1/merchants/kyc/documents` | ✓ | ✓ | — |
| `GET /v1/merchants/kyc/documents/{id}/url` | ✓ | ✓ | — |
| `DELETE /v1/merchants/kyc/documents/{id}` | ✓ | ✓ | — |
| `POST /v1/payments` | ✓ | ✓ | ✓ |
| `GET /v1/payments/{ref}` | ✓ | ✓ | ✓ |
| `POST /v1/payments/{ref}/refund` | ✓ | ✓ | — |
| `POST /v1/payments/{ref}/cancel` | ✓ | ✓ | — |
| `GET /v1/payments` | ✓ | ✓ | ✓ |
| `GET /v1/payments/phone-check` | ✓ | ✓ | ✓ |
| `GET /v1/wallet` | ✓ | ✓ | — |
| `GET /v1/wallet/balance` | ✓ | ✓ | — |
| `GET /v1/wallet/transactions` | ✓ | ✓ | — |
| `POST /v1/wallet/withdrawals` | — | ✓ | — |
| `GET /v1/wallet/withdrawals` | ✓ | ✓ | — |
| `POST /v1/payouts` | — | ✓ | ✓ |
| `GET /v1/payouts/{ref}` | ✓ | ✓ | ✓ |
| `GET /v1/payouts` | ✓ | ✓ | ✓ |
| `POST /v1/payouts/bulk` | — | ✓ | — |
| `GET /v1/payouts/bulk/{ref}` | ✓ | ✓ | — |
| `GET /v1/payouts/bulk/{ref}/items` | ✓ | ✓ | — |
| `GET /v1/payouts/bulk` | ✓ | ✓ | — |
| `POST /v1/transfers` | ✓ | ✓ | — |
| `POST /v1/webhooks` | ✓ | ✓ | — |
| `GET /v1/webhooks` | ✓ | ✓ | — |
| `DELETE /v1/webhooks/{id}` | ✓ | ✓ | — |
| `GET /v1/webhooks/{id}/deliveries` | ✓ | ✓ | — |
| `POST /v1/webhooks/{id}/test` | ✓ | ✓ | — |
| `POST /v1/disputes` | — | ✓ | — |
| `GET /v1/disputes` | ✓ | ✓ | — |
| `GET /v1/disputes/{id}` | ✓ | ✓ | — |
| `DELETE /v1/disputes/{id}` | ✓ | ✓ | — |
| `GET /v1/merchants/gdpr/export` | ✓ | ✓ | — |
| `DELETE /v1/merchants/gdpr/data` | ✓ | ✓ | — |

### API back-office (`/internal/`)

| Endpoint | `SUPPORT` | `FINANCE` | `RECON.` | `COMPLIANCE` | `COUNTRY_ADMIN` | `ADMIN` | `SUPER_ADMIN` |
|----------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| `GET /internal/staff-users` | — | — | — | — | — | ✓ | ✓ |
| `GET /internal/staff-users/{id}` | — | — | — | — | — | ✓ | ✓ |
| `POST /internal/staff-users` | — | — | — | — | — | — | ✓ |
| `PUT /internal/staff-users/{id}` | — | — | — | — | — | — | ✓ |
| `DELETE /internal/staff-users/{id}` | — | — | — | — | — | — | ✓ |
| `POST /internal/staff-users/{id}/reset-password` | — | — | — | — | — | — | ✓ |
| `GET /internal/merchants` | — | — | — | — | ✓ (pays) | ✓ | ✓ |
| `GET /internal/merchants/{id}` | — | — | — | — | ✓ | ✓ | ✓ |
| `PUT /internal/merchants/{id}/kyc/approve` | — | — | — | — | ✓ | ✓ | ✓ |
| `PUT /internal/merchants/{id}/kyc/reject` | — | — | — | — | ✓ | ✓ | ✓ |
| `PUT /internal/merchants/{id}/activate` | — | — | — | — | ✓ | ✓ | ✓ |
| `PUT /internal/merchants/{id}/deactivate` | — | — | — | — | ✓ | ✓ | ✓ |
| `PUT /internal/merchants/{id}/test-mode` | — | — | — | — | — | — | ✓ |
| `PUT /internal/merchants/{id}/limits` | — | — | — | — | — | ✓ | ✓ |
| `GET /internal/merchants/{id}/api-keys` | — | — | — | — | — | ✓ | ✓ |
| `POST /internal/merchants/{id}/api-key/revoke` | — | — | — | — | — | ✓ | ✓ |
| `PUT /internal/merchants/{id}/api-keys/{keyId}/rotation-policy` | — | — | — | — | — | — | ✓ |
| `GET /internal/merchants/kyc/documents/pending` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `GET /internal/merchants/{id}/kyc/documents` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `GET /internal/merchants/kyc/documents/{id}/url` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `PUT /internal/merchants/kyc/documents/{id}/review` | — | — | — | — | ✓ | ✓ | ✓ |
| `GET /internal/finance/wallets` | — | ✓ | — | — | — | ✓ | ✓ |
| `GET /internal/finance/wallets/{merchantId}` | — | ✓ | — | — | — | ✓ | ✓ |
| `GET /internal/finance/summary` | — | ✓ | — | — | — | ✓ | ✓ |
| `GET /internal/finance/withdrawals` | — | ✓ | — | — | — | ✓ | ✓ |
| `PUT /internal/finance/withdrawals/{id}/approve` | — | ✓ | — | — | — | ✓ | ✓ |
| `PUT /internal/finance/withdrawals/{id}/reject` | — | ✓ | — | — | — | ✓ | ✓ |
| `PUT /internal/finance/withdrawals/{id}/execute` | — | ✓ | — | — | — | ✓ | ✓ |
| `GET /internal/finance/float` | — | ✓ | — | — | — | ✓ | ✓ |
| `GET /internal/finance/float/alerts` | — | ✓ | — | — | — | ✓ | ✓ |
| `PUT /internal/finance/float/{operator}` | — | ✓ | — | — | — | ✓ | ✓ |
| `POST /internal/reconciliation/statements/import` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `GET /internal/reconciliation/statements` | — | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `GET /internal/reconciliation/statements/{id}` | — | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `GET /internal/reconciliation/statements/{id}/discrepancies` | — | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `POST /internal/reconciliation/statements/{id}/reconcile` | — | — | ✓ | — | ✓ | ✓ | ✓ |
| `GET /internal/reconciliation/transactions` | — | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `GET /internal/reconciliation/payouts` | — | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `GET /internal/reconciliation/summary` | — | ✓ | ✓ | — | ✓ | ✓ | ✓ |
| `GET /internal/reconciliation/export/transactions` | — | — | ✓ | — | — | ✓ | ✓ |
| `GET /internal/reconciliation/export/payouts` | — | — | ✓ | — | — | ✓ | ✓ |
| `GET /internal/settlement` | — | ✓ | — | — | — | ✓ | ✓ |
| `GET /internal/settlement/{id}` | — | ✓ | — | — | — | ✓ | ✓ |
| `GET /internal/settlement/{id}/entries` | — | ✓ | — | — | — | ✓ | ✓ |
| `POST /internal/settlement/run` | — | — | — | — | — | ✓ | ✓ |
| `POST /internal/settlement/{id}/settle` | — | ✓ | — | — | — | ✓ | ✓ |
| `GET /internal/webhooks/dead-letters` | ✓ | — | — | — | — | ✓ | ✓ |
| `POST /internal/webhooks/dead-letters/{id}/retry` | — | — | — | — | — | ✓ | ✓ |
| `GET /internal/support/transactions/{ref}` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `GET /internal/support/payouts/{ref}` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `GET /internal/support/merchants/{id}/transactions` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `GET /internal/support/merchants/{id}/payouts` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `GET /internal/disputes` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `GET /internal/disputes/{id}` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `PUT /internal/disputes/{id}/review` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `PUT /internal/disputes/{id}/resolve` | ✓ | — | — | — | ✓ | ✓ | ✓ |
| `GET /internal/aml/alerts` | — | — | — | ✓ | — | ✓ | ✓ |
| `GET /internal/aml/alerts/{id}` | — | — | — | ✓ | — | ✓ | ✓ |
| `PUT /internal/aml/alerts/{id}` | — | — | — | ✓ | — | ✓ | ✓ |
| `GET /internal/sanctions/entries` | — | — | — | ✓ | — | ✓ | ✓ |
| `POST /internal/sanctions/check` | — | — | — | ✓ | — | ✓ | ✓ |
| `DELETE /internal/sanctions/entries/{listName}` | — | — | — | ✓ | — | ✓ | ✓ |
| `POST /internal/sanctions/sync` | — | — | — | ✓ | — | ✓ | ✓ |
| `POST /internal/sanctions/sync/{listName}` | — | — | — | ✓ | — | ✓ | ✓ |
| `GET /internal/sanctions/sync/status` | — | — | — | ✓ | — | ✓ | ✓ |
| `GET /internal/sanctions/sync/history` | — | — | — | ✓ | — | ✓ | ✓ |
| `POST /internal/sanctions/import/{listName}` | — | — | — | ✓ | — | ✓ | ✓ |
| `GET /internal/config/fee-rules` | — | — | — | — | — | ✓ | ✓ |
| `GET /internal/config/fee-rules/{id}` | — | — | — | — | — | ✓ | ✓ |
| `POST /internal/config/fee-rules` | — | — | — | — | — | — | ✓ |
| `PUT /internal/config/fee-rules/{id}` | — | — | — | — | — | — | ✓ |
| `DELETE /internal/config/fee-rules/{id}` | — | — | — | — | — | — | ✓ |
| `GET /internal/audit-logs` | — | — | — | — | — | ✓ | ✓ |
| `GET /internal/audit-logs/staff-users/{id}` | — | — | — | — | — | ✓ | ✓ |
| `GET /internal/audit-logs/{entityType}/{entityId}` | — | — | — | — | — | ✓ | ✓ |
| `GET /internal/regulatory/reports/*` | — | ✓ | — | — | — | ✓ | ✓ |
| `POST /internal/admin/key-rotation` | — | — | — | — | — | — | ✓ |