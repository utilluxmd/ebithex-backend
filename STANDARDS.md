# Référentiel Technique — Agrégateur Mobile Money de Haute Qualité

> **Version :** 1.0
> **Périmètre :** Systèmes de paiement mobile en Afrique subsaharienne (UEMOA, CEMAC, Afrique de l'Est)
> **Public cible :** Architectes logiciels, développeurs backend, équipes sécurité, product managers techniques

---

## Table des matières

1. [Vue d'ensemble et définitions](#1-vue-densemble-et-définitions)
2. [Architecture système](#2-architecture-système)
   - [2.4 Monolithique modulaire — point de départ recommandé](#24-monolithique-modulaire--point-de-départ-recommandé)
3. [Modèle de données](#3-modèle-de-données)
4. [API Design](#4-api-design)
5. [Sécurité](#5-sécurité)
   - [5.1 Authentification](#51-authentification)
   - [5.2 Autorisation et RBAC](#52-autorisation-et-rbac)
     - [5.2.1 Définition des rôles](#521-définition-des-rôles)
     - [5.2.2 Matrice des permissions](#522-matrice-des-permissions)
     - [5.2.3 Configuration Spring Security](#523-configuration-spring-security)
     - [5.2.4 Autorisation au niveau des méthodes (@PreAuthorize)](#524-autorisation-au-niveau-des-méthodes-preauthorize)
     - [5.2.5 Vérification d'ownership — règle d'or](#525-vérification-downership--règle-dor)
     - [5.2.6 Principal unifié — objet d'authentification](#526-principal-unifié--objet-dauthentification)
     - [5.2.7 Hiérarchie des rôles Spring](#527-hiérarchie-des-rôles-spring)
     - [5.2.8 Logging des violations d'autorisation](#528-logging-des-violations-dautorisation)
   - [5.3 Vérification des callbacks opérateurs](#53-vérification-des-callbacks-opérateurs)
   - [5.4 Idempotence](#54-idempotence)
   - [5.5 Rate Limiting](#55-rate-limiting)
   - [5.6 Chiffrement des données sensibles](#56-chiffrement-des-données-sensibles)
   - [5.7 OWASP Top 10 — Checklist](#57-owasp-top-10--checklist)
   - [5.8 Headers de sécurité HTTP](#58-headers-de-sécurité-http)
   - [5.9 Validation des URLs webhook (SSRF protection)](#59-validation-des-urls-webhook-ssrf-protection)
6. [Intégrations opérateurs](#6-intégrations-opérateurs)
   - [6.4 Normalisation des numéros de téléphone](#64-normalisation-des-numéros-de-téléphone)
   - [6.5 Détection automatique d'opérateur](#65-détection-automatique-dopérateur)
7. [Gestion des transactions](#7-gestion-des-transactions)
8. [Système de webhooks](#8-système-de-webhooks)
   - [8.1 Clarification fondamentale — Callback, Webhook et Notification](#81-clarification-fondamentale--callback-webhook-et-notification)
9. [Résilience et haute disponibilité](#9-résilience-et-haute-disponibilité)
10. [Observabilité](#10-observabilité)
11. [Conformité et réglementation](#11-conformité-et-réglementation)
12. [Tests](#12-tests)
13. [DevOps et déploiement](#13-devops-et-déploiement)
14. [Performance et scalabilité](#14-performance-et-scalabilité)
15. [Gestion des erreurs](#15-gestion-des-erreurs)
16. [Checklist de mise en production](#16-checklist-de-mise-en-production)

---

## 1. Vue d'ensemble et définitions

### 1.1 Qu'est-ce qu'un agrégateur mobile money ?

Un agrégateur mobile money est une plateforme intermédiaire qui :

- **Unifie** l'accès à plusieurs opérateurs de mobile money (MTN MoMo, Orange Money, Wave, Moov, etc.) derrière une API unique
- **Abstrait** la complexité des protocoles propriétaires de chaque opérateur
- **Orchestre** les flux de paiement : collecte (cash-in), paiement marchand, décaissement (cash-out/payout)
- **Garantit** la traçabilité, la réconciliation et la conformité réglementaire des transactions

### 1.2 Acteurs du système

| Acteur | Rôle |
|--------|------|
| **Marchand (Merchant)** | Entreprise intégrant l'API pour accepter des paiements |
| **Client final (End User)** | Payeur utilisant son portefeuille mobile |
| **Opérateur (MNO)** | Fournisseur de mobile money (MTN, Orange, Wave...) |
| **Agrégateur** | La plateforme (Ebithex et équivalents) |
| **Régulateur** | BCEAO, ARTCI, ARCEP et banques centrales nationales |

### 1.3 Types d'opérations supportées

| Opération | Description | Priorité |
|-----------|-------------|----------|
| **Collection (Cash-in)** | Prélèvement depuis le portefeuille client | P0 — critique |
| **Payout (Cash-out)** | Envoi d'argent vers un portefeuille | P0 — critique |
| **Vérification de solde** | Vérifier la disponibilité du compte opérateur | P1 |
| **Remboursement (Refund)** | Annulation totale ou partielle d'une collection | P1 |
| **Transfert B2B** | Virement entre comptes marchands | P2 |
| **Vérification de numéro** | Valider l'existence d'un compte mobile | P2 |

---

## 2. Architecture système

### 2.1 Architecture recommandée (production)

```
┌─────────────────────────────────────────────────────────────────────┐
│                          ZONE PUBLIQUE                               │
│                                                                      │
│   Marchands ──── HTTPS ────► WAF / DDoS Protection                 │
│   Opérateurs ──── HTTPS ───►  (Cloudflare / AWS Shield)            │
└──────────────────────────────────────┬──────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────┐
│                          ZONE DMZ                                    │
│                                                                      │
│              API Gateway (Kong / AWS API GW)                        │
│              ├─ Rate Limiting                                        │
│              ├─ Auth (API Key validation)                            │
│              ├─ Request routing                                      │
│              └─ SSL Termination                                      │
└──────────────────────────────────────┬──────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────┐
│                        ZONE APPLICATIVE                              │
│                                                                      │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐    │
│  │  Payment API │   │ Webhook Svc  │   │   Reconciliation Svc │    │
│  │  (stateless) │   │  (workers)   │   │   (batch/scheduled)  │    │
│  └──────┬───────┘   └──────┬───────┘   └──────────────────────┘    │
│         │                  │                                        │
│  ┌──────▼───────────────────▼────────────────────────────────┐     │
│  │                    Message Broker                          │     │
│  │              (RabbitMQ / Kafka)                            │     │
│  │    Queues: payments.process, webhooks.dispatch,            │     │
│  │            callbacks.inbound, reconciliation.jobs          │     │
│  └────────────────────────────────────────────────────────────┘     │
└──────────────────────────────────────┬──────────────────────────────┘
                                       │
┌──────────────────────────────────────▼──────────────────────────────┐
│                         ZONE DATA                                    │
│                                                                      │
│  PostgreSQL (primary/replica)   Redis Cluster   Elasticsearch        │
│  ├─ Transactions                ├─ Idempotence  └─ Audit logs       │
│  ├─ Merchants                   ├─ Sessions                         │
│  ├─ Webhooks                    ├─ Rate limits                       │
│  └─ Audit logs                  └─ Operator tokens                  │
└──────────────────────────────────────────────────────────────────────┘
```

### 2.2 Principes architecturaux obligatoires

#### Séparation des responsabilités (SoC)

```
controller/     → Validation des entrées, routing HTTP, sérialisation
service/        → Logique métier, orchestration, règles de gestion
repository/     → Accès données uniquement, pas de logique métier
operator/       → Adaptateur par opérateur (pattern Adapter)
webhook/        → Dispatch asynchrone uniquement
scheduler/      → Jobs récurrents (expiration, retry, réconciliation)
security/       → Filtres d'authentification, autorisation
config/         → Configuration Spring, beans
util/           → Fonctions pures sans état
```

#### Règles de dépendance strictes

```
controller → service ✅
service → repository ✅
service → operator ✅
repository → model ✅
operator → external HTTP ✅

controller → repository ❌ (interdit)
service → controller ❌ (interdit)
model → service ❌ (interdit)
```

### 2.3 Découpage des microservices (évolution)

Pour une plateforme à fort volume, découper en services indépendants :

| Service | Responsabilité | Base de données |
|---------|---------------|-----------------|
| `auth-service` | Gestion marchands, JWT, API keys | PostgreSQL |
| `payment-service` | Initiation et suivi transactions | PostgreSQL |
| `operator-service` | Intégration MNO, adaptateurs | PostgreSQL (read) |
| `webhook-service` | Dispatch et retry webhooks | PostgreSQL |
| `reconciliation-service` | Réconciliation, reporting | PostgreSQL + S3 |
| `notification-service` | SMS, email, push notifications | — |
| `admin-service` | Back-office, KYC, support | PostgreSQL |

### 2.4 Monolithique modulaire — point de départ recommandé

#### Pourquoi commencer par un monolithique modulaire ?

Démarrer directement avec des microservices est une erreur fréquente en phase d'amorçage. Les microservices introduisent une complexité opérationnelle (déploiement distribué, réseau, cohérence des données, observabilité) qui ralentit le développement et augmente les coûts avant même d'avoir validé le produit. Le **monolithique modulaire** est le bon compromis : il permet de livrer vite, d'avoir une base solide, et de migrer vers les microservices proprement lorsque la charge ou les contraintes d'équipe le justifient.

```
Phase 1 (0 → ~100K transactions/mois)    Phase 2 (~100K → ~1M/mois)        Phase 3 (> 1M/mois)
─────────────────────────────────────    ─────────────────────────────      ──────────────────────
Monolithique modulaire                   Extraction progressive              Microservices complets
  + 1 base PostgreSQL                      + Workers séparés                  + Event Streaming
  + 1 Redis                                + Queue asynchrone                 + Base par service
  + 1 déploiement                          + 1 base PostgreSQL                + Kubernetes
  + modules bien cloisonnés                + Monolith allégé                  + Service mesh
```

#### 2.4.1 Structure du monolithique modulaire

Le principe fondamental est que chaque module est **autonome dans sa logique**, même si tout tourne dans le même processus JVM. Les modules ne se parlent qu'à travers des **interfaces publiques définies** — jamais en accédant directement aux classes internes d'un autre module.

```
src/main/java/io/ebithex/
│
├── EbithexApplication.java
│
├── modules/
│   │
│   ├── auth/                          ← MODULE : Authentification pure (tokens, sessions)
│   │   ├── api/
│   │   │   └── AuthController.java    (login, register, refresh, logout)
│   │   ├── application/
│   │   │   ├── AuthService.java       (login, vérification mot de passe)
│   │   │   └── TokenService.java      (JWT, refresh token, révocation)
│   │   ├── domain/
│   │   │   ├── RefreshToken.java      (entité JPA)
│   │   │   └── RefreshTokenRepository.java
│   │   ├── infrastructure/
│   │   │   ├── JwtServiceImpl.java    (RS256, claims, blacklist Redis)
│   │   │   └── ApiKeyFilter.java      (filtre Spring Security)
│   │   └── AuthService.java            ← SEULE interface publique : validateToken(), resolveApiKey()
│   │                                    Dépend de MerchantService pour charger le marchand
│   │
│   ├── merchant/                      ← MODULE : Cycle de vie des marchands
│   │   ├── api/
│   │   │   ├── MerchantController.java  (GET /me, PATCH /me)
│   │   │   └── ApiKeyController.java    (POST /api-key/regenerate)
│   │   ├── application/
│   │   │   ├── MerchantService.java   (inscription, profil, suspension)
│   │   │   ├── KycService.java        (soumission, validation, workflow KYC)
│   │   │   ├── ApiKeyService.java     (génération, rotation, révocation)
│   │   │   └── LimitService.java      (vérification des limites jour/mois/transaction)
│   │   ├── domain/
│   │   │   ├── Merchant.java          (entité JPA — possédée par CE module uniquement)
│   │   │   ├── MerchantRepository.java
│   │   │   ├── KycDocument.java
│   │   │   └── FeeConfig.java         (value object : taux custom du marchand)
│   │   ├── infrastructure/
│   │   │   └── MerchantEventListener.java  (écoute MerchantRegisteredEvent)
│   │   └── MerchantService.java        ← SEULE interface publique du module
│   │                                    Exposée à : auth, payment, webhook, reconciliation
│   │
│   ├── payment/                       ← MODULE : Transactions & paiements
│   │   ├── api/
│   │   │   └── PaymentController.java
│   │   ├── application/
│   │   │   ├── PaymentService.java
│   │   │   ├── FeeService.java
│   │   │   └── StateMachineService.java
│   │   ├── domain/
│   │   │   ├── Transaction.java
│   │   │   ├── TransactionRepository.java
│   │   │   ├── TransactionStatus.java
│   │   │   └── TransactionEvent.java
│   │   ├── infrastructure/
│   │   │   └── TransactionEventPublisher.java
│   │   └── PaymentService.java         ← SEULE interface publique du module
│   │
│   ├── operator/                      ← MODULE : Tout ce qui concerne les opérateurs MNO
│   │   │                                (sortant ET entrant — une seule relation, un seul module)
│   │   ├── outbound/                  ← Appels Ebithex → Opérateur
│   │   │   ├── api/                   (pas de controller — appels initiés par payment)
│   │   │   ├── application/
│   │   │   │   ├── OperatorRegistry.java
│   │   │   │   └── OperatorTokenCache.java
│   │   │   ├── domain/
│   │   │   │   └── MobileMoneyOperator.java  (interface : initiateCollection, checkStatus...)
│   │   │   └── infrastructure/
│   │   │       ├── MtnMomoAdapter.java
│   │   │       ├── OrangeMoneyAdapter.java
│   │   │       ├── WaveAdapter.java
│   │   │       └── MoovMoneyAdapter.java
│   │   ├── inbound/                   ← Appels Opérateur → Ebithex (callbacks)
│   │   │   ├── api/
│   │   │   │   └── OperatorCallbackController.java  (POST /v1/callbacks/{operator})
│   │   │   └── application/
│   │   │       ├── CallbackVerifier.java     (vérifie HMAC / IP whitelist de l'opérateur)
│   │   │       └── CallbackProcessor.java    (traduit le payload natif → événement domaine)
│   │   └── OperatorService.java        ← SEULE interface publique du module
│   │                                    Exposée à : payment (pour initier les transactions)
│   │
│   ├── webhook/                       ← MODULE : Notifications sortantes vers les marchands
│   │   ├── api/
│   │   │   └── WebhookController.java   (CRUD des endpoints webhook du marchand)
│   │   ├── application/
│   │   │   ├── WebhookDispatcher.java
│   │   │   └── WebhookRetryService.java
│   │   ├── domain/
│   │   │   ├── WebhookEndpoint.java
│   │   │   ├── WebhookDelivery.java
│   │   │   └── WebhookEndpointRepository.java
│   │   └── WebhookService.java         ← SEULE interface publique du module
│   │
│   ├── wallet/                        ← MODULE : Grand livre, soldes, settlements
│   │   ├── api/
│   │   │   ├── WalletController.java    (GET /me/balance, GET /me/ledger)
│   │   │   └── SettlementController.java (POST /settlements, GET /settlements/{id})
│   │   ├── application/
│   │   │   ├── LedgerService.java       (écriture des entrées double-entrée)
│   │   │   ├── BalanceService.java      (calcul et cache du solde marchand)
│   │   │   ├── SettlementService.java   (initiation et suivi des virements)
│   │   │   ├── FloatService.java        (suivi du float Ebithex chez chaque opérateur)
│   │   │   └── WalletEventListener.java (écoute PaymentSucceededEvent, PaymentRefundedEvent)
│   │   ├── domain/
│   │   │   ├── LedgerEntry.java         (entité JPA — ligne du grand livre, IMMUABLE)
│   │   │   ├── LedgerEntryRepository.java
│   │   │   ├── MerchantBalance.java     (vue agrégée du solde — calculée, pas stockée)
│   │   │   ├── Settlement.java          (entité JPA — demande de virement vers la banque)
│   │   │   ├── SettlementRepository.java
│   │   │   ├── FloatAccount.java        (entité JPA — solde Ebithex chez l'opérateur)
│   │   │   ├── FloatAccountRepository.java
│   │   │   └── EntryType.java           (enum : CREDIT | DEBIT)
│   │   └── WalletService.java           ← SEULE interface publique du module
│   │                                     Exposée à : reconciliation (pour les rapports financiers)
│   │
│   ├── reconciliation/                ← MODULE : Réconciliation & reporting
│   │   ├── application/
│   │   │   ├── ReconciliationService.java
│   │   │   └── ReportGeneratorService.java
│   │   ├── domain/
│   │   │   └── ReconciliationReport.java
│   │   └── ReconciliationService.java  ← SEULE interface publique du module
│   │
│   └── notification/                  ← MODULE : Notifications sortantes vers les clients finaux
│       │                                (SMS, email, push — distinct des webhooks marchands)
│       ├── application/
│       │   ├── SmsService.java        (confirmation de paiement par SMS au payeur)
│       │   ├── EmailService.java      (reçu de paiement par email)
│       │   └── NotificationEventListener.java  (écoute PaymentSucceededEvent)
│       ├── infrastructure/
│       │   ├── TwilioSmsAdapter.java  (ou Orange SMS API, etc.)
│       │   └── SendgridEmailAdapter.java
│       └── NotificationService.java    ← SEULE interface publique (optionnel — module leaf)
│
├── shared/                            ← CODE PARTAGÉ (sans logique métier)
│   ├── domain/
│   │   ├── Money.java                 (value object montant + devise)
│   │   ├── PhoneNumber.java           (value object numéro E.164)
│   │   └── AuditLog.java
│   ├── events/
│   │   ├── DomainEvent.java           (interface)
│   │   ├── PaymentInitiatedEvent.java
│   │   ├── PaymentSucceededEvent.java
│   │   └── PaymentFailedEvent.java
│   ├── exceptions/
│   │   ├── EbithexException.java
│   │   └── GlobalExceptionHandler.java
│   └── util/
│       ├── HmacUtil.java
│       └── ReferenceGenerator.java
│
├── config/
│   ├── SecurityConfig.java
│   ├── AsyncConfig.java
│   ├── CacheConfig.java
│   └── WebClientConfig.java
│
└── infrastructure/
    ├── persistence/                   ← Configuration JPA, Flyway
    └── messaging/                     ← ApplicationEventPublisher (Spring)
```

#### 2.4.2 Règles de cloisonnement des modules

Ces règles sont **non négociables**. C'est ce qui distingue un monolithique modulaire d'un "big ball of mud".

```
RÈGLE 1 — Accès inter-modules uniquement via les Services

  ✅ paymentModule → merchantService.findById(merchantId)
  ❌ paymentModule → MerchantRepository.findById(merchantId)
  ❌ paymentModule → Merchant.getHashedSecret()   (accès à un interne du module auth)

RÈGLE 2 — Pas de dépendances circulaires entre modules

  Graphe de dépendances autorisées (toutes les flèches vont dans un seul sens) :

  merchant     ◄── auth          (auth charge le marchand pour valider ses credentials)
  merchant     ◄── payment       (payment vérifie les limites et la config du marchand)
  merchant     ◄── webhook       (webhook résout les endpoints du marchand)
  merchant     ◄── wallet        (wallet crédite/débite par merchantId)
  operator     ◄── payment       (payment appelle operator/outbound pour initier)
  operator     ◄── wallet        (wallet rafraîchit le solde float via operator/outbound)
  payment      ──► wallet        (PaymentSucceededEvent → LedgerService crédite)
  payment      ──► webhook       (PaymentSucceededEvent → WebhookDispatcher notifie le marchand)
  payment      ──► notification  (PaymentSucceededEvent → SmsService notifie le client final)
  payment      ──► reconcile     (PaymentSucceededEvent → ReconciliationService enregistre)
  operator/inbound ──► payment   (CallbackProcessor publie OperatorCallbackReceivedEvent)
  wallet       ──► reconcile     (SettlementCompletedEvent → ReconciliationService enregistre)

  ❌ merchant      → payment      (merchant ne sait pas qu'il y a des transactions)
  ❌ merchant      → wallet       (merchant ne gère pas les soldes)
  ❌ webhook       → payment      (webhook ne modifie jamais une transaction)
  ❌ notification  → payment      (notification ne modifie jamais une transaction)
  ❌ wallet        → payment      (wallet ne crée pas de transactions)
  ❌ auth          → merchant     (auth ne gère pas le cycle de vie des marchands)
  ❌ operator/outbound → payment  (les adaptateurs ne connaissent pas les transactions Ebithex)

RÈGLE 3 — Communication par événements pour les side effects

  ✅ PaymentService publie PaymentSucceededEvent
     → WebhookModule écoute et déclenche la notification
     → ReconciliationModule écoute et met à jour ses compteurs
  ❌ PaymentService appelle directement webhookService.notify() et reconciliationService.record()

RÈGLE 4 — Chaque module possède ses entités JPA

  Le module payment   possède Transaction.java
  Le module merchant  possède Merchant.java  ← PAS le module auth
  Le module auth      possède RefreshToken.java uniquement
  Le module webhook   possède WebhookEndpoint.java et WebhookDelivery.java
  Aucun module n'a de @OneToMany vers l'entité d'un autre module
  → Les jointures inter-modules se font par ID (UUID), jamais par référence d'objet JPA
```

#### 2.4.3 Communication interne par événements Spring

```java
// shared/events/PaymentSucceededEvent.java
public record PaymentSucceededEvent(
    UUID transactionId,
    String ebithexReference,
    UUID merchantId,
    BigDecimal amount,
    String currency,
    String operator,
    Instant occurredAt
) implements DomainEvent {}

// payment/application/PaymentService.java
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public PaymentResponse processCallback(String operatorRef, TransactionStatus newStatus) {
        Transaction tx = transactionRepo.findByOperatorReference(operatorRef).orElseThrow();

        transitionStatus(tx, newStatus);

        // Publier l'événement : les autres modules réagiront de façon découplée
        if (newStatus == TransactionStatus.SUCCESS) {
            eventPublisher.publishEvent(new PaymentSucceededEvent(
                tx.getId(), tx.getEbithexReference(), tx.getMerchantId(),
                tx.getAmount(), tx.getCurrency(), tx.getOperator().name(),
                Instant.now()
            ));
        }
        return PaymentResponse.from(tx);
    }
}

// webhook/application/WebhookEventListener.java
@Component
@RequiredArgsConstructor
public class WebhookEventListener {

    private final WebhookDispatcher dispatcher;

    // @Async : s'exécute dans un thread séparé — ne bloque pas le paiement
    @Async
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        // Exécuté uniquement si la transaction de paiement est bien commitée
        dispatcher.dispatch(event.transactionId(), "payment.success");
    }
}

// reconciliation/application/ReconciliationEventListener.java
@Component
public class ReconciliationEventListener {

    @Async
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        reconciliationService.recordSuccessfulTransaction(event);
    }
}
```

**Pourquoi `@TransactionalEventListener(phase = AFTER_COMMIT)` ?**
L'événement n'est dispatché qu'après le commit de la transaction de paiement. Si le commit échoue, le webhook ne part pas. Cela garantit la cohérence sans avoir besoin d'un message broker externe.

#### 2.4.4 Services publiques — contrat inter-modules

```java
// ─────────────────────────────────────────────────────────────────────
// merchant/MerchantService.java
// Seule interface publique du module merchant.
// Importée par : auth, payment, webhook, reconciliation.
// ─────────────────────────────────────────────────────────────────────
@Component
public class MerchantService {

    private final MerchantService merchantService;
    private final LimitService limitService;

    /** Utilisé par auth pour charger le principal après validation du token/API key */
    public Optional<MerchantSummary> findById(UUID merchantId) {
        return merchantService.findById(merchantId).map(MerchantSummary::from);
    }

    /** Utilisé par auth pour valider la clé API */
    public Optional<MerchantSummary> findByApiKeyHash(String apiKeyHash) {
        return merchantService.findByApiKeyHash(apiKeyHash).map(MerchantSummary::from);
    }

    /** Utilisé par payment pour vérifier les limites avant d'initier une transaction */
    public void checkLimits(UUID merchantId, BigDecimal amount, String currency) {
        limitService.check(merchantId, amount, currency);  // lève LimitExceededException si dépassé
    }

    /** Utilisé par payment pour calculer les frais du marchand */
    public FeeConfig getFeeConfig(UUID merchantId) {
        return merchantService.getFeeConfig(merchantId);
    }

    /** Utilisé par webhook pour résoudre les endpoints du marchand */
    public boolean isActive(UUID merchantId) {
        return merchantService.isActive(merchantId);
    }
}

// merchant/domain/MerchantSummary.java
// DTO de sortie du module merchant — jamais l'entité JPA Merchant directement
public record MerchantSummary(
    UUID id,
    String businessName,
    String country,
    String defaultCurrency,
    boolean active,
    boolean kycVerified,
    BigDecimal customFeeRate
) {
    public static MerchantSummary from(Merchant merchant) { ... }
}

// ─────────────────────────────────────────────────────────────────────
// auth/AuthService.java
// Seule interface publique du module auth.
// Importée par : SecurityConfig (filtre Spring Security uniquement).
// ─────────────────────────────────────────────────────────────────────
@Component
public class AuthService {

    private final TokenService tokenService;
    private final MerchantService merchantService;  // auth dépend de merchant (sens unique)

    /** Valide un JWT et retourne le résumé du marchand correspondant */
    public Optional<MerchantSummary> resolveJwt(String bearerToken) {
        UUID merchantId = tokenService.validateAndExtract(bearerToken);
        return merchantService.findById(merchantId);
    }

    /** Valide une API Key et retourne le résumé du marchand correspondant */
    public Optional<MerchantSummary> resolveApiKey(String rawApiKey) {
        String hash = sha256(rawApiKey);
        return merchantService.findByApiKeyHash(hash);
    }
}
```

#### 2.4.5 Base de données : une seule instance, schémas séparés

Même si tout partage la même instance PostgreSQL, chaque module a son propre schéma SQL. Cela facilite la migration vers des bases séparées le jour venu.

```sql
-- Schémas PostgreSQL par module
CREATE SCHEMA auth;        -- merchants, api_keys, audit_logs auth
CREATE SCHEMA payment;     -- transactions, transaction_events, fee_rules
CREATE SCHEMA webhook;     -- webhook_endpoints, webhook_deliveries
CREATE SCHEMA operator;    -- operator_accounts, operator_health
CREATE SCHEMA reconcile;   -- reconciliation_reports

-- Exemple : les transactions n'ont pas de FK vers merchants
-- (pas de jointure inter-schéma dans les entités JPA)
-- Le merchant_id dans payment.transactions est juste un UUID, pas une FK

-- ✅ Référence par UUID (portable vers microservice)
ALTER TABLE payment.transactions
    ADD COLUMN merchant_id UUID NOT NULL;   -- pas de REFERENCES auth.merchants(id)

-- ❌ Foreign key inter-schéma (interdit — couplage fort)
-- ADD COLUMN merchant_id UUID REFERENCES auth.merchants(id);
```

```yaml
# application.yml — configuration Flyway par module
spring:
  flyway:
    locations:
      - classpath:db/migration/auth
      - classpath:db/migration/merchant
      - classpath:db/migration/payment
      - classpath:db/migration/wallet
      - classpath:db/migration/webhook
      - classpath:db/migration/operator
      - classpath:db/migration/reconcile

# Structure des migrations
src/main/resources/db/migration/
├── auth/
│   └── V1__create_merchants.sql
├── payment/
│   ├── V1__create_transactions.sql
│   └── V2__add_transaction_events.sql
├── webhook/
│   └── V1__create_webhooks.sql
├── operator/
│   └── V1__create_operator_accounts.sql
└── reconcile/
    └── V1__create_reconciliation_reports.sql
```

#### 2.4.6 Configuration des threads asynchrones

Dans le monolithique, les opérations asynchrones (webhooks, synchronisation opérateurs, réconciliation) partagent le même JVM mais doivent avoir des pools de threads **isolés** pour ne pas se bloquer mutuellement.

```java
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig implements AsyncConfigurer {

    // Pool dédié aux webhooks
    @Bean("webhookExecutor")
    public Executor webhookExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(5);
        exec.setMaxPoolSize(20);
        exec.setQueueCapacity(500);
        exec.setThreadNamePrefix("webhook-");
        exec.setRejectedExecutionHandler(new CallerRunsPolicy());  // Ne jamais perdre un webhook
        exec.initialize();
        return exec;
    }

    // Pool dédié à la synchronisation des statuts opérateurs
    @Bean("operatorSyncExecutor")
    public Executor operatorSyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(3);
        exec.setMaxPoolSize(10);
        exec.setQueueCapacity(200);
        exec.setThreadNamePrefix("op-sync-");
        exec.setRejectedExecutionHandler(new DiscardOldestPolicy());
        exec.initialize();
        return exec;
    }

    // Pool pour les jobs de réconciliation et reporting
    @Bean("batchExecutor")
    public Executor batchExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("batch-");
        exec.initialize();
        return exec;
    }

    // Pool par défaut (@Async sans qualificateur)
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(5);
        exec.setMaxPoolSize(15);
        exec.setQueueCapacity(100);
        exec.setThreadNamePrefix("async-");
        exec.initialize();
        return exec;
    }
}

// Usage dans les services
@Async("webhookExecutor")
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onPaymentSucceeded(PaymentSucceededEvent event) { ... }

@Async("operatorSyncExecutor")
public void syncTransactionStatus(UUID transactionId) { ... }

@Async("batchExecutor")
public void generateDailyReport(LocalDate date) { ... }
```

#### 2.4.7 Module Wallet — grand livre, soldes et settlements

##### Pourquoi un module wallet distinct ?

Le module `wallet` répond à une question que nul autre module ne couvre : **où est l'argent ?**

| Question | Module responsable |
|----------|--------------------|
| La transaction a-t-elle réussi ? | `payment` |
| Qui est le marchand ? | `merchant` |
| Combien d'argent le marchand a-t-il sur Ebithex ? | `wallet` ✅ |
| Combien Ebithex a-t-il chez MTN MoMo ? | `wallet` ✅ |
| Quand le marchand sera-t-il payé ? | `wallet` ✅ |

##### Principe du grand livre en double entrée

Toute entrée financière est enregistrée comme une paire de lignes : un débit et un crédit. C'est la règle comptable universelle — elle garantit qu'on ne peut pas créer ni perdre d'argent par bug.

```
Exemple : collection de 10 000 XOF par le marchand A (frais : 80 XOF)

  DÉBIT  | compte=FLOAT_MTN_CI       | 10 000 XOF | ref=AP-P-20240315-001
  CRÉDIT | compte=MERCHANT_A_BALANCE |  9 920 XOF | ref=AP-P-20240315-001
  CRÉDIT | compte=EBITHEX_REVENUE    |     80 XOF | ref=AP-P-20240315-001

Exemple : settlement de 9 920 XOF vers le compte bancaire du marchand A

  DÉBIT  | compte=MERCHANT_A_BALANCE |  9 920 XOF | ref=SETTLE-20240315-042
  CRÉDIT | compte=BANK_TRANSIT       |  9 920 XOF | ref=SETTLE-20240315-042
```

##### Schema SQL du module wallet

```sql
CREATE SCHEMA wallet;

-- Grand livre (append-only — jamais de UPDATE ni DELETE)
CREATE TABLE wallet.ledger_entries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id      VARCHAR(100) NOT NULL,  -- ex: MERCHANT:uuid, FLOAT:MTN_CI, REVENUE:EBITHEX
    entry_type      VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount          DECIMAL(15,2) NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3) NOT NULL DEFAULT 'XOF',
    reference_id    VARCHAR(100) NOT NULL,  -- ebithexReference ou settlementId
    reference_type  VARCHAR(30) NOT NULL,   -- COLLECTION | PAYOUT | SETTLEMENT | REFUND | FEE
    description     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
    -- PAS de updated_at : cette table est IMMUABLE
);

CREATE INDEX idx_ledger_account_id   ON wallet.ledger_entries(account_id);
CREATE INDEX idx_ledger_reference    ON wallet.ledger_entries(reference_id);
CREATE INDEX idx_ledger_created_at   ON wallet.ledger_entries(created_at DESC);
-- Requête de solde : SUM(CASE WHEN entry_type='CREDIT' THEN amount ELSE -amount END)

-- Settlements (virements vers les marchands)
CREATE TABLE wallet.settlements (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID NOT NULL,           -- référence par UUID, pas de FK inter-schéma
    amount          DECIMAL(15,2) NOT NULL CHECK (amount > 0),
    currency        VARCHAR(3) NOT NULL DEFAULT 'XOF',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- PENDING → PROCESSING → COMPLETED | FAILED

    bank_account_number VARCHAR(30),         -- IBAN ou numéro de compte local
    bank_name       VARCHAR(100),
    bank_code       VARCHAR(20),

    initiated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    processed_at    TIMESTAMPTZ,
    failure_reason  TEXT,
    external_ref    VARCHAR(100),            -- référence du virement bancaire
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Comptes float Ebithex chez les opérateurs
CREATE TABLE wallet.float_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator        VARCHAR(50) NOT NULL,
    country         VARCHAR(2) NOT NULL,
    currency        VARCHAR(3) NOT NULL DEFAULT 'XOF',
    current_balance DECIMAL(15,2),           -- mis à jour par le scheduler
    min_threshold   DECIMAL(15,2),           -- seuil d'alerte bas
    balance_updated_at TIMESTAMPTZ,
    UNIQUE(operator, country, currency)
);
```

##### Façade publique du module wallet

```java
// wallet/WalletService.java
@Component
public class WalletService {

    private final BalanceService balanceService;

    /** Solde disponible d'un marchand (utilisé par merchant pour GET /me/balance) */
    public Money getBalance(UUID merchantId, String currency) {
        return balanceService.computeBalance(merchantId, currency);
    }

    /** Vérifie que le marchand a assez de fonds pour un payout */
    public boolean hasSufficientFunds(UUID merchantId, Money amount) {
        Money balance = balanceService.computeBalance(merchantId, amount.getCurrency());
        return balance.isGreaterThanOrEqualTo(amount);
    }
}

// wallet/application/WalletEventListener.java
@Component
@RequiredArgsConstructor
public class WalletEventListener {

    private final LedgerService ledgerService;
    private final FloatService floatService;

    /** Créditer le marchand et débiter le float opérateur après une collection réussie */
    @Async("walletExecutor")
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onPaymentSucceeded(PaymentSucceededEvent event) {
        // DÉBIT  : float de l'opérateur (l'argent est physiquement là)
        ledgerService.debit(
            "FLOAT:" + event.operator() + ":" + event.country(),
            event.amount(), event.currency(), event.ebithexReference(), "COLLECTION"
        );
        // CRÉDIT : solde du marchand (net de frais)
        ledgerService.credit(
            "MERCHANT:" + event.merchantId(),
            event.netAmount(), event.currency(), event.ebithexReference(), "COLLECTION"
        );
        // CRÉDIT : revenus Ebithex (les frais)
        ledgerService.credit(
            "REVENUE:EBITHEX",
            event.feeAmount(), event.currency(), event.ebithexReference(), "FEE"
        );
    }

    /** Mettre à jour le float en cas de payout réussi */
    @Async("walletExecutor")
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onPayoutSucceeded(PayoutSucceededEvent event) {
        ledgerService.debit(
            "MERCHANT:" + event.merchantId(),
            event.amount(), event.currency(), event.ebithexReference(), "PAYOUT"
        );
        ledgerService.credit(
            "FLOAT:" + event.operator() + ":" + event.country(),
            event.amount(), event.currency(), event.ebithexReference(), "PAYOUT"
        );
    }

    /** En cas de remboursement, inverser les écritures */
    @Async("walletExecutor")
    @TransactionalEventListener(phase = AFTER_COMMIT)
    public void onPaymentRefunded(PaymentRefundedEvent event) {
        ledgerService.debit(
            "MERCHANT:" + event.merchantId(),
            event.refundedAmount(), event.currency(), event.refundReference(), "REFUND"
        );
        ledgerService.credit(
            "FLOAT:" + event.operator() + ":" + event.country(),
            event.refundedAmount(), event.currency(), event.refundReference(), "REFUND"
        );
    }
}
```

##### Calcul du solde (toujours recalculé depuis le grand livre)

```java
// Le solde n'est JAMAIS stocké directement — il est calculé à la demande
// depuis les entrées du grand livre, avec cache Redis (TTL court)

@Service
public class BalanceService {

    @Cacheable(value = "merchant-balance", key = "#merchantId + '-' + #currency")
    public Money computeBalance(UUID merchantId, String currency) {
        String account = "MERCHANT:" + merchantId;
        BigDecimal balance = ledgerEntryRepo.computeBalance(account, currency);
        return new Money(balance, currency);
    }
}
```

```sql
-- Requête de calcul du solde depuis le grand livre
SELECT
    SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS balance
FROM wallet.ledger_entries
WHERE account_id = :accountId
  AND currency   = :currency;
```

**Pourquoi recalculer plutôt que stocker le solde ?**
- Impossible d'avoir un solde incohérent avec l'historique (single source of truth)
- En cas de bug, on peut recalculer et corriger sans modifier le passé
- Auditabilité totale : chaque centime a une ligne dans le grand livre
- Le cache Redis couvre la performance (invalidé à chaque nouvelle écriture)

##### Pool de threads dédié au wallet

```java
// Dans AsyncConfig.java — ajouter le pool wallet
@Bean("walletExecutor")
public Executor walletExecutor() {
    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(3);
    exec.setMaxPoolSize(10);
    exec.setQueueCapacity(1000);  // File plus grande : les écritures ne doivent pas se perdre
    exec.setThreadNamePrefix("wallet-");
    exec.setRejectedExecutionHandler(new CallerRunsPolicy());  // Jamais perdre une écriture
    exec.initialize();
    return exec;
}
```

#### 2.4.8 Critères de décision : rester monolithique ou migrer

Évaluer ces indicateurs trimestriellement. La migration est justifiée quand **au moins 3 critères** sont atteints simultanément.

| Indicateur | Seuil de migration | Comment mesurer |
|------------|-------------------|-----------------|
| **Volume de transactions** | > 500 000 / mois | Dashboard Grafana |
| **Équipe de développement** | > 5 développeurs backend | Organisation |
| **Temps de build + test** | > 15 minutes | CI pipeline |
| **Déploiements par semaine** | > 5 par feature team | GitHub Actions |
| **Incidents de disponibilité** | Un module affecte les autres | Alertes PagerDuty |
| **Latence P95** | > 2s malgré les optimisations | Métriques Prometheus |
| **Opérateurs intégrés** | > 6 opérateurs actifs | Inventaire opérateurs |

#### 2.4.9 Roadmap de migration vers les microservices

Quand les seuils sont atteints, extraire les modules dans cet ordre précis, du moins risqué au plus complexe :

```
ÉTAPE 1 — Extraire les workers asynchrones (risque faible)
  Modules : webhook-service, reconciliation-service
  Pourquoi en premier : pas de chemin critique, déjà asynchrones
  Durée estimée : 2-3 semaines
  Pattern : les workers consomment depuis une queue (RabbitMQ / SQS)
            Le monolithique publie dans la queue au lieu d'appeler en mémoire

      Avant :  ApplicationEventPublisher (in-memory)
      Après :  RabbitTemplate.send("webhooks.dispatch", event)

ÉTAPE 2 — Extraire les adaptateurs opérateurs (risque faible-moyen)
  Module : operator-service
  Pourquoi : bien isolé derrière l'interface MobileMoneyOperator, pas de données propres
  Durée estimée : 3-4 semaines
  Pattern : le monolithique appelle l'operator-service via HTTP REST
            L'operator-service gère les tokens, retry, circuit breakers

ÉTAPE 3 — Extraire le wallet (risque moyen)
  Module : wallet-service
  Pourquoi : domaine financier distinct, écrit par événements (faible couplage synchrone)
  Durée estimée : 4-5 semaines
  Pattern : wallet-service écoute les événements payment via la queue
            Il expose une API REST pour les settlements et la consultation de solde
  Attention : la table ledger_entries doit migrer avec ses données historiques
              Utiliser une période de double-write (monolith + service) avant bascule

ÉTAPE 4 — Extraire le marchand et l'authentification (risque moyen)
  Modules : merchant-service + auth-service (souvent déployés ensemble)
  Pourquoi : auth dépend de merchant — les extraire séparément crée une complexité inutile
  Durée estimée : 4-6 semaines
  Pattern : API Gateway valide les tokens JWT avec la clé publique RS256 localement
            Les autres services cachent le profil marchand (TTL court, invalidation par event)

ÉTAPE 5 — Extraire le cœur paiement (risque élevé — en dernier)
  Module : payment-service
  Pourquoi : le plus critique, le plus de dépendances entrantes
  Durée estimée : 6-8 semaines + période de double-run obligatoire
  Pattern : canary deployment, traffic splitting progressif (1% → 5% → 25% → 100%)
            Rollback automatique si taux d'erreur > seuil configuré
```

**Règle d'or de la migration :** ne jamais migrer plusieurs modules simultanément. Une extraction à la fois, avec une période de stabilisation de 4 semaines entre chaque étape.

#### 2.4.10 Anti-patterns à éviter dans le monolithique modulaire

```
❌ Package by layer (organisation par type technique)
   src/
   ├── controllers/   ← mélange auth + payment + webhook
   ├── services/      ← mélange auth + payment + webhook
   └── repositories/  ← mélange auth + payment + webhook

   Problème : impossible d'extraire un module sans tout toucher

✅ Package by module (organisation par domaine métier)
   src/modules/
   ├── auth/          ← tout ce qui concerne l'authentification
   ├── payment/       ← tout ce qui concerne les transactions
   └── webhook/       ← tout ce qui concerne les notifications

---

❌ Shared database object (entité JPA partagée entre modules)
   // Module payment accède à Merchant.java défini dans le module merchant
   @ManyToOne
   @JoinColumn(name = "merchant_id")
   private Merchant merchant;  ← couplage fort, impossible à extraire

✅ Reference by ID
   @Column(name = "merchant_id")
   private UUID merchantId;    ← portable, extractible

---

❌ Appel direct entre services de modules différents
   // Dans PaymentService.java
   @Autowired
   private WebhookService webhookService;  ← couplage direct inter-modules

✅ Communication par événements
   eventPublisher.publishEvent(new PaymentSucceededEvent(...));

---

❌ Transaction @Transactional couvrant plusieurs modules
   @Transactional
   public void doSomething() {
       merchantService.updateBalance();   // module auth
       transactionService.markPaid();     // module payment
       webhookService.notify();           // module webhook
   }
   Problème : impossible de distribuer, rollback imprévisible

✅ Saga pattern ou événements compensatoires
   // Chaque module gère sa propre transaction
   // Les erreurs sont compensées par des événements de rollback
```

---

## 3. Modèle de données

### 3.1 Entités principales et règles de conception

#### Règles générales

- **UUID v4** comme clé primaire sur toutes les entités (jamais d'auto-increment exposé)
- **Soft delete** obligatoire : jamais de `DELETE` physique sur des données financières
- **Timestamps** `created_at` et `updated_at` sur toutes les tables
- **Audit trail** : toute modification d'une transaction doit être journalisée
- **Immutabilité** : une transaction créée ne peut être modifiée que via des transitions de statut définies
- **BigDecimal / DECIMAL(15,2)** pour tous les montants — jamais de FLOAT

#### Schema de référence complet

```sql
-- ============================================================
-- TABLE: merchants
-- ============================================================
CREATE TABLE merchants (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name       VARCHAR(255) NOT NULL,
    email               VARCHAR(255) NOT NULL UNIQUE,

    -- Authentification
    hashed_secret       VARCHAR(255) NOT NULL,          -- BCrypt 12 rounds
    api_key             VARCHAR(255) NOT NULL UNIQUE,   -- ap_live_xxx (hash en DB)
    api_key_hash        VARCHAR(255),                   -- SHA-256 du vrai api_key

    -- Configuration
    country             VARCHAR(2) NOT NULL,            -- ISO 3166-1 alpha-2
    default_currency    VARCHAR(3) NOT NULL DEFAULT 'XOF',
    webhook_url         VARCHAR(2048),
    custom_fee_rate     DECIMAL(5,4),                   -- ex: 0.0080 = 0.8%

    -- Limites (risk management)
    daily_limit         DECIMAL(15,2),
    monthly_limit       DECIMAL(15,2),
    per_transaction_max DECIMAL(15,2),
    per_transaction_min DECIMAL(15,2) DEFAULT 100.00,

    -- KYC & compliance
    kyc_verified        BOOLEAN NOT NULL DEFAULT FALSE,
    kyc_verified_at     TIMESTAMPTZ,
    kyc_documents       JSONB,                          -- refs S3 des documents

    -- États
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    suspended_at        TIMESTAMPTZ,
    suspension_reason   TEXT,
    deleted_at          TIMESTAMPTZ,                    -- soft delete

    -- Metadata
    metadata            JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_merchants_api_key_hash ON merchants(api_key_hash);
CREATE INDEX idx_merchants_email ON merchants(email);
CREATE INDEX idx_merchants_country ON merchants(country);
CREATE INDEX idx_merchants_active ON merchants(active) WHERE active = TRUE;

-- ============================================================
-- TABLE: transactions
-- ============================================================
CREATE TABLE transactions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Références
    ebithex_reference       VARCHAR(50) NOT NULL UNIQUE,  -- AP-YYYYMMDD-SEQ
    merchant_reference      VARCHAR(255) NOT NULL,        -- ref du marchand
    operator_reference      VARCHAR(255),                  -- ref de l'opérateur

    -- Relations
    merchant_id             UUID NOT NULL REFERENCES merchants(id),

    -- Montants (TOUS en DECIMAL — jamais FLOAT)
    amount                  DECIMAL(15,2) NOT NULL CHECK (amount > 0),
    fee_amount              DECIMAL(15,2) NOT NULL DEFAULT 0.00 CHECK (fee_amount >= 0),
    net_amount              DECIMAL(15,2) NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'XOF',
    fee_rate_applied        DECIMAL(5,4),                  -- taux appliqué (audit)
    fee_rule_id             UUID,                          -- règle tarifaire utilisée

    -- Paiement
    phone_number            VARCHAR(20) NOT NULL,          -- E.164 : +22507123456
    operator                VARCHAR(50) NOT NULL,
    type                    VARCHAR(20) NOT NULL DEFAULT 'COLLECTION',  -- COLLECTION | PAYOUT | REFUND

    -- Statut (machine d'états stricte)
    status                  VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    previous_status         VARCHAR(30),                   -- pour audit
    status_updated_at       TIMESTAMPTZ,
    failure_reason          TEXT,
    failure_code            VARCHAR(50),                   -- code d'erreur opérateur

    -- Client
    customer_name           VARCHAR(255),
    customer_email          VARCHAR(255),
    description             VARCHAR(512),

    -- Remboursement
    refunded_amount         DECIMAL(15,2),
    refunded_at             TIMESTAMPTZ,
    refund_reference        VARCHAR(255),
    original_transaction_id UUID REFERENCES transactions(id),  -- pour les remboursements

    -- Tentatives et retry
    attempt_count           INTEGER NOT NULL DEFAULT 0,
    last_attempt_at         TIMESTAMPTZ,

    -- Expiration
    expires_at              TIMESTAMPTZ NOT NULL,

    -- Metadata libre
    metadata                JSONB,
    ip_address              INET,                          -- IP du marchand
    user_agent              VARCHAR(512),

    -- Soft delete / archivage
    archived_at             TIMESTAMPTZ,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Contrainte d'unicité sur (merchant_id, merchant_reference)
CREATE UNIQUE INDEX idx_transactions_merchant_ref
    ON transactions(merchant_id, merchant_reference);

-- Index de performance
CREATE INDEX idx_transactions_merchant_id ON transactions(merchant_id);
CREATE INDEX idx_transactions_ebithex_ref ON transactions(ebithex_reference);
CREATE INDEX idx_transactions_status ON transactions(status);
CREATE INDEX idx_transactions_phone ON transactions(phone_number);
CREATE INDEX idx_transactions_operator ON transactions(operator);
CREATE INDEX idx_transactions_created_at ON transactions(created_at DESC);
CREATE INDEX idx_transactions_expires_at ON transactions(expires_at)
    WHERE status IN ('PENDING', 'PROCESSING');

-- Partial index pour réconciliation
CREATE INDEX idx_transactions_pending_reconcile
    ON transactions(created_at)
    WHERE status IN ('PROCESSING', 'PENDING') AND expires_at > NOW();

-- ============================================================
-- TABLE: transaction_events (audit trail immuable)
-- ============================================================
CREATE TABLE transaction_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    event_type      VARCHAR(50) NOT NULL,  -- STATUS_CHANGED, RETRY_ATTEMPTED, etc.
    from_status     VARCHAR(30),
    to_status       VARCHAR(30),
    actor           VARCHAR(100),          -- 'system', 'operator', 'merchant', 'admin'
    details         JSONB,                 -- contexte complet de l'événement
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tx_events_transaction_id ON transaction_events(transaction_id);
CREATE INDEX idx_tx_events_created_at ON transaction_events(created_at DESC);

-- ============================================================
-- TABLE: fee_rules (tarification configurable)
-- ============================================================
CREATE TABLE fee_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    operator        VARCHAR(50),           -- NULL = toutes opérateurs
    country         VARCHAR(2),            -- NULL = tous pays
    merchant_id     UUID REFERENCES merchants(id),  -- NULL = défaut global

    -- Structure tarifaire
    fee_type        VARCHAR(20) NOT NULL DEFAULT 'PERCENTAGE',  -- PERCENTAGE | FLAT | MIXED
    percentage_rate DECIMAL(5,4),          -- ex: 0.0080 = 0.8%
    flat_amount     DECIMAL(15,2),         -- montant fixe en plus
    min_fee         DECIMAL(15,2),
    max_fee         DECIMAL(15,2),

    -- Applicabilité
    min_amount      DECIMAL(15,2),
    max_amount      DECIMAL(15,2),
    currency        VARCHAR(3),

    -- Validité
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    valid_from      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_until     TIMESTAMPTZ,

    -- Priorité (plus haut = prioritaire)
    priority        INTEGER NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- TABLE: operator_accounts (soldes et credentials opérateurs)
-- ============================================================
CREATE TABLE operator_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator        VARCHAR(50) NOT NULL,
    country         VARCHAR(2) NOT NULL,
    environment     VARCHAR(10) NOT NULL DEFAULT 'SANDBOX',  -- SANDBOX | PRODUCTION

    -- État du compte
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    balance         DECIMAL(15,2),         -- solde en cache (rafraîchi périodiquement)
    balance_updated_at TIMESTAMPTZ,
    low_balance_threshold DECIMAL(15,2),

    -- Limites opérateur
    max_per_transaction DECIMAL(15,2),
    min_per_transaction DECIMAL(15,2),
    daily_limit     DECIMAL(15,2),

    -- Credentials (chiffrés en base — voir section sécurité)
    credentials     BYTEA,                 -- AES-256-GCM chiffré
    credentials_iv  BYTEA,                 -- vecteur d'initialisation

    UNIQUE(operator, country, environment),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- TABLE: reconciliation_reports
-- ============================================================
CREATE TABLE reconciliation_reports (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator            VARCHAR(50) NOT NULL,
    period_start        TIMESTAMPTZ NOT NULL,
    period_end          TIMESTAMPTZ NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',

    -- Totaux Ebithex
    total_transactions  INTEGER,
    total_amount        DECIMAL(15,2),
    total_fees          DECIMAL(15,2),

    -- Totaux opérateur (importés)
    operator_total_tx   INTEGER,
    operator_total_amt  DECIMAL(15,2),

    -- Écarts
    discrepancies       JSONB,             -- liste des écarts détectés
    discrepancy_count   INTEGER DEFAULT 0,
    discrepancy_amount  DECIMAL(15,2),

    resolved_at         TIMESTAMPTZ,
    report_url          VARCHAR(2048),     -- lien S3 vers le rapport CSV

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### 3.2 Machine d'états des transactions

Les transitions de statut doivent être **strictement contrôlées**. Toute transition invalide doit lever une exception.

```
                     ┌─────────────────────────────────────────┐
                     │                                         │
                     ▼                                         │
              ┌─────────────┐                                  │
   Création → │   PENDING   │ ─── Soumis à l'opérateur ──►  │
              └──────┬──────┘                                  │
                     │                                         │
         ┌───────────▼──────────┐                             │
         │      PROCESSING      │ ◄── Opérateur confirme      │
         └───┬──────────────┬───┘      réception              │
             │              │                                  │
    ┌────────▼──┐      ┌────▼────────┐                        │
    │  SUCCESS  │      │   FAILED    │ ──── Délai expiré ────►│
    └────────┬──┘      └─────────────┘                        │
             │                                                 │
    ┌────────▼──────┐         ┌──────────────┐               │
    │   REFUNDED    │         │   EXPIRED    │ ◄─────────────┘
    └───────────────┘         └──────────────┘

    ┌──────────────┐
    │  CANCELLED   │ ← Annulé avant traitement opérateur
    └──────────────┘
```

**Transitions autorisées :**

| From | To | Acteur |
|------|----|--------|
| PENDING | PROCESSING | système (après appel opérateur) |
| PENDING | FAILED | système (erreur opérateur) |
| PENDING | EXPIRED | scheduler (délai dépassé) |
| PENDING | CANCELLED | marchand (via API) |
| PROCESSING | SUCCESS | système (callback opérateur) |
| PROCESSING | FAILED | système (callback opérateur) |
| PROCESSING | EXPIRED | scheduler |
| SUCCESS | REFUNDED | système (après remboursement) |

**Toute autre transition est interdite et doit lever une `InvalidTransitionException`.**

### 3.3 Génération de références

```java
// Format: AP-{ENV}-{YYYYMMDD}-{SEQ_PADDED}
// Exemples:
//   Production : AP-P-20240315-0000001234
//   Sandbox    : AP-S-20240315-0000001234

// Utiliser une SEQUENCE PostgreSQL — jamais un compteur en mémoire
CREATE SEQUENCE transaction_reference_seq
    START WITH 1000000
    INCREMENT BY 1
    NO CYCLE;

// Java:
String ref = String.format("AP-%s-%s-%010d",
    environment.equals("PRODUCTION") ? "P" : "S",
    LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE),
    nextVal("transaction_reference_seq")
);
```

**Règles :**
- La séquence est gérée par PostgreSQL (thread-safe, pas de trou en cas de crash)
- La référence est **immuable** une fois créée
- Elle encode la date pour faciliter les requêtes de réconciliation

---

## 4. API Design

### 4.1 Conventions REST obligatoires

#### Structure des URLs

```
/api/v1/payments              ✅  (versionnée, pluriel, minuscule)
/api/v1/Payment               ❌  (casse incorrecte)
/api/payments                 ❌  (non versionnée)
/api/v1/initiatePayment       ❌  (verbe dans l'URL)
```

#### Versionnement

- Version dans le path : `/api/v1/`, `/api/v2/`
- Maintenir N et N-1 simultanément pendant 6 mois minimum
- Dépréciation signalée par header : `Deprecation: true` + `Sunset: Sat, 01 Jan 2026 00:00:00 GMT`

#### Endpoints standard

```
POST   /v1/payments                    Initier un paiement
GET    /v1/payments/{reference}        Statut d'une transaction
GET    /v1/payments                    Liste des transactions (paginée)
POST   /v1/payments/{reference}/refund Rembourser une transaction
POST   /v1/payouts                     Initier un décaissement
GET    /v1/payouts/{reference}         Statut d'un décaissement

POST   /v1/auth/register               Inscription marchand
POST   /v1/auth/login                  Authentification JWT
POST   /v1/auth/refresh                Rafraîchir le token
POST   /v1/auth/logout                 Invalider le token
POST   /v1/auth/api-key/regenerate     Rotation de clé API

GET    /v1/webhooks                    Lister les endpoints
POST   /v1/webhooks                    Créer un endpoint
GET    /v1/webhooks/{id}               Détail d'un endpoint
PATCH  /v1/webhooks/{id}              Modifier un endpoint
DELETE /v1/webhooks/{id}              Désactiver un endpoint
POST   /v1/webhooks/{id}/test         Envoyer un webhook de test

GET    /v1/merchants/me                Profil du marchand connecté
PATCH  /v1/merchants/me               Modifier le profil
GET    /v1/merchants/me/balance        Solde de la plateforme

GET    /v1/operators                   Opérateurs disponibles par pays
GET    /v1/operators/{operator}/status État de l'opérateur

POST   /v1/callbacks/{operator}        Callbacks des opérateurs (inbound)
```

### 4.2 Format de réponse unifié

#### Succès

```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "ebithexReference": "AP-P-20240315-0000001234",
    "status": "PROCESSING"
  },
  "meta": {
    "requestId": "req_01HX4K2M3N5P6Q7R8S9T",
    "timestamp": "2024-03-15T10:30:00.123Z",
    "version": "1.0"
  }
}
```

#### Erreur

```json
{
  "success": false,
  "error": {
    "code": "DUPLICATE_TRANSACTION",
    "message": "Une transaction avec cette référence marchand existe déjà.",
    "details": {
      "existingReference": "AP-P-20240315-0000001230",
      "merchantReference": "CMD-2024-001"
    }
  },
  "meta": {
    "requestId": "req_01HX4K2M3N5P6Q7R8S9T",
    "timestamp": "2024-03-15T10:30:00.123Z"
  }
}
```

#### Liste paginée

```json
{
  "success": true,
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "perPage": 20,
    "total": 1543,
    "totalPages": 78,
    "hasNext": true,
    "hasPrev": false,
    "nextCursor": "eyJpZCI6IjU1MGU4N...",
    "links": {
      "self": "/v1/payments?page=1&per_page=20",
      "next": "/v1/payments?page=2&per_page=20",
      "last": "/v1/payments?page=78&per_page=20"
    }
  },
  "meta": { ... }
}
```

### 4.3 Codes de statut HTTP

| Code | Usage |
|------|-------|
| 200 | Succès GET, PATCH |
| 201 | Ressource créée (POST) |
| 202 | Accepté mais traitement asynchrone (payout initié) |
| 400 | Données invalides, validation échouée |
| 401 | Non authentifié (pas de token ou token invalide) |
| 403 | Authentifié mais non autorisé (accès à la ressource d'un autre marchand) |
| 404 | Ressource non trouvée |
| 409 | Conflit (transaction dupliquée) |
| 422 | Entité non traitable (logique métier échouée — ex: opérateur non supporté) |
| 429 | Trop de requêtes (rate limiting) |
| 500 | Erreur interne serveur |
| 502 | Erreur du gateway opérateur |
| 503 | Service indisponible (maintenance) |
| 504 | Timeout du gateway opérateur |

### 4.4 Headers obligatoires

**Requête (marchand → API) :**

```
X-API-Key: ap_live_...              Auth par clé API
Authorization: Bearer eyJ...        Auth par JWT
X-Request-ID: req_01HX4K2M...      ID unique par requête (idempotence)
X-Idempotency-Key: UUID             Clé d'idempotence (POST uniquement)
Content-Type: application/json
Accept: application/json
```

**Réponse (API → marchand) :**

```
X-Request-ID: req_01HX4K2M...       Repris de la requête (ou généré)
X-RateLimit-Limit: 1000             Limite de requêtes par fenêtre
X-RateLimit-Remaining: 987          Requêtes restantes
X-RateLimit-Reset: 1710499200       Timestamp de réinitialisation
X-Ebithex-Version: 1.0.3            Version de l'API
Retry-After: 60                     (uniquement si 429 ou 503)
```

### 4.5 Pagination par curseur (recommandée)

La pagination par offset (`page=5&per_page=20`) est inefficace sur de grandes tables. Utiliser la pagination par curseur pour les transactions :

```
GET /v1/payments?limit=20&cursor=eyJpZCI6IjU1MGU4ND...&direction=next

cursor = base64_url( JSON({ "id": "UUID", "created_at": "timestamp" }) )
```

### 4.6 Filtres de liste

```
GET /v1/payments?status=SUCCESS&operator=MTN_MOMO&from=2024-01-01&to=2024-01-31
GET /v1/payments?phone=%2B22507123456&limit=50
GET /v1/payments?amount_min=1000&amount_max=50000&currency=XOF
```

---

## 5. Sécurité

### 5.1 Authentification

#### API Keys

```
Format:  ap_{env}_{random_bytes_hex}
Exemple: ap_live_3f4a8b2c1d9e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2
Env:     live | test | sandbox

Génération:
  - 32 bytes cryptographiquement aléatoires (SecureRandom)
  - Encodage hexadécimal (64 chars)
  - Préfixe indiquant l'environnement

Stockage:
  - Jamais stocker la clé en clair en base de données
  - Stocker le SHA-256 de la clé : apiKeyHash = sha256(apiKey)
  - Afficher la clé une seule fois lors de la création
  - Permettre la rotation (régénération) sans coupure de service

Lookup:
  - Hash la clé reçue → chercher par hash en DB
  - Index sur api_key_hash

Rotation sans coupure:
  - Supporter 2 clés actives simultanément pendant 24h
  - Invalider l'ancienne après confirmation par le marchand
```

#### JWT Tokens

```
Algorithme:   RS256 (RSA) ou ES256 (ECDSA) — JAMAIS HS256 en production multi-instance
Durée:        Access token = 15 minutes (pas 24h !)
Refresh:      Refresh token = 30 jours, rotation à chaque usage (RTR)
Claims requis:
  - sub: merchantId (UUID)
  - iss: https://api.ebithex.io
  - aud: ["api.ebithex.io"]
  - iat, exp, jti (JWT ID unique pour blacklist)
  - email, role

Validation:
  - Vérifier signature
  - Vérifier iss et aud
  - Vérifier exp
  - Vérifier jti non révoqué (Redis blacklist)
  - Vérifier que le marchand est toujours actif en base

Stockage côté client:
  - Access token : mémoire (pas localStorage)
  - Refresh token : HttpOnly cookie
```

### 5.2 Autorisation et RBAC

L'authentification prouve l'identité. L'autorisation détermine ce que cette identité a le droit de faire. Les deux sont indépendants et doivent être gérés séparément.

```
Requête entrante
     │
     ▼
┌─────────────────┐     échec     ┌──────────────────┐
│ AUTHENTIFICATION│ ────────────► │  401 Unauthorized │
│ Qui es-tu ?     │               └──────────────────┘
└────────┬────────┘
         │ succès (identité connue)
         ▼
┌─────────────────┐     échec     ┌──────────────────┐
│  AUTORISATION   │ ────────────► │  403 Forbidden    │
│ Que peux-tu     │               └──────────────────┘
│ faire ?  (RBAC) │
└────────┬────────┘
         │ rôle autorisé
         ▼
┌─────────────────┐     échec     ┌──────────────────┐
│   OWNERSHIP     │ ────────────► │  403 Forbidden    │
│ Cette ressource │               └──────────────────┘
│ t'appartient-   │
│ elle ?          │
└────────┬────────┘
         │ ressource ok
         ▼
     Traitement
```

#### 5.2.1 Définition des rôles

```java
// shared/security/Role.java
public enum Role {

    // ── Rôles marchands (authentification par API Key ou JWT) ──

    MERCHANT,
    // Marchand de base : peut initier des collections, voir ses transactions,
    // gérer ses webhooks. Limites de volume réduites.

    MERCHANT_KYC_VERIFIED,
    // Marchand ayant complété le KYC : peut initier des payouts et des remboursements,
    // limites de volume étendues selon les seuils BCEAO.

    AGENT,
    // Employé/caissier d'un marchand dans une agence spécifique.
    // Périmètre : merchantId + agencyId uniquement.
    // Peut initier des opérations financières mais ne peut pas gérer le compte marchand.
    // Authentification : JWT émis lors du login via le dashboard marchand.

    // ── Rôles internes (authentification par JWT back-office) ──

    SUPPORT,
    // Agent support client Tier 2 : lecture seule globale sur toutes les transactions
    // et tous les marchands, sans restriction de pays.
    // Ne peut pas déclencher d'opérations financières.
    //
    // Périmètre : plateforme entière (multi-pays).
    // Pour un support scopé à un seul pays, utiliser COUNTRY_ADMIN :
    // la hiérarchie COUNTRY_ADMIN > SUPPORT lui donne les mêmes droits de lecture
    // mais limités à son pays, sans nécessiter un rôle COUNTRY_SUPPORT distinct.

    FINANCE,
    // Équipe finance : accès aux rapports agrégés, aux settlements, au grand livre.
    // Ne peut pas gérer les marchands ni exporter des données brutes en masse.

    RECONCILIATION,
    // Équipe réconciliation opérateurs : accès bulk en lecture aux transactions brutes,
    // export CSV, lancement manuel des jobs de réconciliation, consultation des écarts.
    // Utilisé aussi par les service accounts des batchs nocturnes.
    // Ne peut pas gérer les settlements ni les marchands.

    COUNTRY_ADMIN,
    // Administrateur pays : gestion des marchands de son pays uniquement.
    // Applique les règles BCEAO nationales (seuils KYC, limites par pays).
    // Périmètre : country uniquement — ne peut pas accéder aux autres pays.

    ADMIN,
    // Administrateur global : gestion complète de tous les marchands (suspension,
    // activation, override des limites). Ne peut pas modifier les règles tarifaires.

    SUPER_ADMIN
    // Super administrateur : accès total incluant la configuration des frais,
    // les clés de chiffrement et les paramètres système.
}
```

#### 5.2.2 Matrice des permissions

| Action | MERCHANT | MERCHANT_KYC | AGENT | SUPPORT | FINANCE | RECONCILIATION | COUNTRY_ADMIN | ADMIN | SUPER_ADMIN |
|--------|:--------:|:------------:|:-----:|:-------:|:-------:|:--------------:|:-------------:|:-----:|:-----------:|
| Initier une collection | ✅ | ✅ | ✅ (son agence) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Initier un payout unitaire | ❌ | ✅ | ✅ (si marchand KYC) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Initier un bulk payout | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Demander un remboursement | ❌ | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ (son pays) | ✅ | ✅ |
| Voir ses transactions (agence) | ✅ | ✅ | ✅ (son agence) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Voir toutes les transactions (bulk) | ❌ | ❌ | ❌ | ✅ (lecture) | ✅ (lecture) | ✅ | ✅ (son pays) | ✅ | ✅ |
| Exporter transactions CSV | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ |
| Voir les écarts de réconciliation | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ✅ (son pays) | ✅ | ✅ |
| Lancer un job de réconciliation | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ✅ | ✅ |
| Gérer ses webhooks | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Voir les rapports financiers agrégés | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ (lecture) | ✅ (son pays) | ✅ | ✅ |
| Gérer les settlements | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Voir le profil d'un marchand | ✅ (sien) | ✅ (sien) | ❌ | ✅ (tous) | ❌ | ❌ | ✅ (son pays) | ✅ | ✅ |
| Suspendre / activer un marchand | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ (son pays) | ✅ | ✅ |
| Modifier les règles de frais | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Régénérer sa propre clé API | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| Valider le KYC d'un marchand | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ (son pays) | ✅ | ✅ |
| Gérer les agences d'un marchand | ✅ (sien) | ✅ (sien) | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |

#### 5.2.3 Configuration Spring Security

```java
// config/SecurityConfig.java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)  // active @PreAuthorize sur les méthodes
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth

                // Endpoints publics
                .requestMatchers("/v1/auth/register", "/v1/auth/login").permitAll()
                .requestMatchers("/v1/callbacks/**").permitAll()        // sécurisé par HMAC
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()

                // Endpoints marchands (API Key ou JWT)
                // AGENT inclus : ownership merchantId+agencyId vérifié dans le service
                .requestMatchers("/v1/payments/**").hasAnyRole("MERCHANT", "MERCHANT_KYC_VERIFIED", "AGENT")
                // Bulk payout : MERCHANT_KYC_VERIFIED uniquement (opération financière critique)
                // Déclaré AVANT /v1/payouts/** pour que Spring évalue la règle spécifique en premier
                .requestMatchers("/v1/payouts/bulk/**").hasRole("MERCHANT_KYC_VERIFIED")
                .requestMatchers("/v1/payouts/**").hasAnyRole("MERCHANT_KYC_VERIFIED", "AGENT")
                .requestMatchers("/v1/webhooks/**").hasAnyRole("MERCHANT", "MERCHANT_KYC_VERIFIED")

                // Endpoints back-office internes
                .requestMatchers("/internal/merchants/**").hasAnyRole("COUNTRY_ADMIN", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/internal/reconciliation/**").hasAnyRole("RECONCILIATION", "FINANCE", "COUNTRY_ADMIN", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/internal/reconciliation/export/**").hasAnyRole("RECONCILIATION", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/internal/reconciliation/jobs/**").hasAnyRole("RECONCILIATION", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/internal/finance/**").hasAnyRole("FINANCE", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/internal/support/**").hasAnyRole("SUPPORT", "COUNTRY_ADMIN", "ADMIN", "SUPER_ADMIN")
                .requestMatchers("/internal/config/**").hasRole("SUPER_ADMIN")

                // Tout le reste requiert authentification minimum
                .anyRequest().authenticated()
            )
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

#### 5.2.4 Autorisation au niveau des méthodes (@PreAuthorize)

```java
// payment/api/PaymentController.java
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    // Collection : tout marchand (KYC ou non)
    @PostMapping
    @PreAuthorize("hasAnyRole('MERCHANT', 'MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<ApiResponse<PaymentResponse>> initiatePayment(
            @Valid @RequestBody PaymentRequest req,
            @AuthenticationPrincipal MerchantPrincipal principal) {
        return ok(paymentService.initiatePayment(req, principal.getMerchant()));
    }

    // Payout : KYC obligatoire
    @PostMapping("/payouts")
    @PreAuthorize("hasRole('MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<ApiResponse<PaymentResponse>> initiatePayout(
            @Valid @RequestBody PayoutRequest req,
            @AuthenticationPrincipal MerchantPrincipal principal) {
        return ok(paymentService.initiatePayout(req, principal.getMerchant()));
    }

    // Remboursement : KYC obligatoire
    @PostMapping("/{reference}/refund")
    @PreAuthorize("hasRole('MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<ApiResponse<RefundResponse>> refund(
            @PathVariable String reference,
            @Valid @RequestBody RefundRequest req,
            @AuthenticationPrincipal MerchantPrincipal principal) {
        return ok(paymentService.refund(reference, req, principal.getMerchant()));
    }

    // Consultation : tout marchand — ownership vérifié dans le service
    @GetMapping("/{reference}")
    @PreAuthorize("hasAnyRole('MERCHANT', 'MERCHANT_KYC_VERIFIED')")
    public ResponseEntity<ApiResponse<TransactionStatusResponse>> getStatus(
            @PathVariable String reference,
            @AuthenticationPrincipal MerchantPrincipal principal) {
        return ok(paymentService.getStatus(reference, principal.getMerchant()));
    }
}
```

#### 5.2.5 Vérification d'ownership — règle d'or

L'ownership doit être vérifié **dans la couche service**, pas dans le contrôleur. C'est la protection contre les Insecure Direct Object References (IDOR), classé A01 OWASP.

**Deux niveaux d'ownership selon le rôle :**

| Rôle | Ownership requis |
|------|-----------------|
| `MERCHANT`, `MERCHANT_KYC_VERIFIED` | `tx.merchantId == principal.merchantId` |
| `AGENT` | `tx.merchantId == principal.merchantId` **ET** `tx.agencyId == principal.agencyId` |
| `SUPPORT`, `FINANCE`, `ADMIN`, `SUPER_ADMIN` | Aucun (accès global) |
| `COUNTRY_ADMIN` | `merchant.country == principal.country` |

```java
// payment/application/PaymentService.java
public TransactionStatusResponse getStatus(String ebithexReference, EbithexPrincipal principal) {

    Transaction tx = transactionRepo.findByEbithexReference(ebithexReference)
        .orElseThrow(() -> new ResourceNotFoundException("Transaction non trouvée"));

    // OWNERSHIP CHECK — niveau 1 : appartient-elle au bon marchand ?
    if (principal.hasMerchantScope()) {
        if (!tx.getMerchantId().equals(principal.getMerchantId())) {
            throw new AccessDeniedException("Accès refusé à cette transaction");
        }
        // OWNERSHIP CHECK — niveau 2 (AGENT) : appartient-elle à la bonne agence ?
        if (principal.hasAgencyScope()) {
            if (!tx.getAgencyId().equals(principal.getAgencyId())) {
                throw new AccessDeniedException("Accès refusé — transaction hors agence");
            }
        }
    }

    // COUNTRY CHECK — pour COUNTRY_ADMIN
    if (principal.hasCountryScope()) {
        Merchant merchant = merchantRepo.findById(tx.getMerchantId()).orElseThrow();
        if (!merchant.getCountry().equals(principal.getCountry())) {
            throw new AccessDeniedException("Accès refusé — marchand hors périmètre pays");
        }
    }

    return TransactionStatusResponse.from(tx);
}

// Note sur 403 vs 404 :
// Retourner 404 ("non trouvé") cache l'existence de la ressource à un attaquant.
// Retourner 403 ("interdit") confirme que la ressource existe mais est inaccessible.
// En fintech : utiliser 403 pour être transparent avec vos propres marchands légitimes
// qui font des erreurs de référence, et loguer l'incident pour détecter les tentatives d'IDOR.
```

```java
// Annotation custom pour exprimer l'intention clairement dans le code
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresOwnership {
    String resourceType();  // ex: "Transaction", "WebhookEndpoint"
}

// Usage :
@RequiresOwnership(resourceType = "Transaction")
public TransactionStatusResponse getStatus(String ref, Merchant merchant) { ... }
```

#### 5.2.6 Principal unifié — objet d'authentification

Le principal est unifié pour couvrir tous les rôles (marchands, agents, back-office) via un seul objet injecté par `@AuthenticationPrincipal`.

```java
// security/EbithexPrincipal.java
// Injecté par @AuthenticationPrincipal dans les contrôleurs
// Construit par ApiKeyAuthFilter ou JwtAuthFilter après authentification réussie

public record EbithexPrincipal(
    UUID id,            // merchantId pour MERCHANT/AGENT, userId pour les rôles internes
    String email,
    Set<Role> roles,
    boolean active,

    // Champs de scope — null si non applicable au rôle
    UUID merchantId,    // non null pour MERCHANT, MERCHANT_KYC_VERIFIED, AGENT
    UUID agencyId,      // non null pour AGENT uniquement
    String country      // non null pour COUNTRY_ADMIN uniquement
) implements UserDetails {

    /** Vrai si le principal est scopé à un marchand (MERCHANT, MERCHANT_KYC, AGENT) */
    public boolean hasMerchantScope() {
        return merchantId != null;
    }

    /** Vrai si le principal est scopé à une agence (AGENT uniquement) */
    public boolean hasAgencyScope() {
        return agencyId != null;
    }

    /** Vrai si le principal est scopé à un pays (COUNTRY_ADMIN uniquement) */
    public boolean hasCountryScope() {
        return country != null && roles.contains(Role.COUNTRY_ADMIN);
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
            .map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
            .toList();
    }

    @Override public String getPassword()               { return ""; }
    @Override public String getUsername()               { return email; }
    @Override public boolean isAccountNonExpired()      { return true; }
    @Override public boolean isAccountNonLocked()       { return active; }
    @Override public boolean isCredentialsNonExpired()  { return true; }
    @Override public boolean isEnabled()                { return active; }

    // ── Factories selon le contexte d'authentification ──

    public static EbithexPrincipal fromMerchant(Merchant merchant) {
        Set<Role> roles = new HashSet<>();
        roles.add(Role.MERCHANT);
        if (merchant.isKycVerified()) roles.add(Role.MERCHANT_KYC_VERIFIED);
        return new EbithexPrincipal(
            merchant.getId(), merchant.getEmail(), roles, merchant.isActive(),
            merchant.getId(), null, null   // merchantId=sien, pas d'agence, pas de pays
        );
    }

    public static EbithexPrincipal fromAgent(Agent agent) {
        return new EbithexPrincipal(
            agent.getId(), agent.getEmail(), Set.of(Role.AGENT), agent.isActive(),
            agent.getMerchantId(), agent.getAgencyId(), null   // double scope
        );
    }

    public static EbithexPrincipal fromBackOfficeUser(BackOfficeUser user) {
        String country = user.hasRole(Role.COUNTRY_ADMIN) ? user.getCountry() : null;
        return new EbithexPrincipal(
            user.getId(), user.getEmail(), user.getRoles(), user.isActive(),
            null, null, country   // pas de scope marchand ni agence
        );
    }
}
```

#### 5.2.7 Hiérarchie des rôles Spring

```java
// config/SecurityConfig.java — hiérarchie : SUPER_ADMIN hérite de tout
@Bean
public RoleHierarchy roleHierarchy() {
    RoleHierarchyImpl hierarchy = new RoleHierarchyImpl();
    hierarchy.setHierarchy("""
        ROLE_SUPER_ADMIN > ROLE_ADMIN
        ROLE_ADMIN > ROLE_COUNTRY_ADMIN
        ROLE_ADMIN > ROLE_FINANCE
        ROLE_ADMIN > ROLE_RECONCILIATION
        ROLE_ADMIN > ROLE_SUPPORT
        ROLE_COUNTRY_ADMIN > ROLE_SUPPORT
        ROLE_MERCHANT_KYC_VERIFIED > ROLE_MERCHANT
        ROLE_MERCHANT_KYC_VERIFIED > ROLE_AGENT
    """);
    return hierarchy;
}

// Avec cette hiérarchie :
// SUPER_ADMIN  →  ADMIN  →  COUNTRY_ADMIN  →  SUPPORT
//                       →  FINANCE
//                       →  RECONCILIATION
// MERCHANT_KYC_VERIFIED  →  MERCHANT
//                        →  AGENT
//
// Note : RECONCILIATION n'hérite pas de FINANCE et vice-versa —
// ce sont deux périmètres distincts (données brutes vs agrégats financiers).
// AGENT n'hérite PAS de MERCHANT — pas de gestion de webhooks ni clés API.
```

#### 5.2.8 Logging des violations d'autorisation

```java
// Toute tentative d'accès non autorisé doit être loggée et comptée
// pour détecter les scans IDOR et tentatives de fraude

@ExceptionHandler(AccessDeniedException.class)
public ResponseEntity<ApiErrorResponse> handleAccessDenied(
        AccessDeniedException ex, HttpServletRequest request,
        @AuthenticationPrincipal MerchantPrincipal principal) {

    // Logger avec le contexte complet — jamais supprimer silencieusement
    log.warn("Authorization denied [merchantId={}] [path={}] [method={}] [ip={}]",
        principal != null ? principal.merchantId() : "anonymous",
        request.getRequestURI(),
        request.getMethod(),
        extractIp(request)
    );

    // Incrémenter le compteur d'alertes (déclenche une alerte si > seuil)
    metricsService.increment("security.authorization.denied",
        "path", request.getRequestURI());

    // Réponse générique — ne pas révéler pourquoi l'accès est refusé
    return ResponseEntity.status(403)
        .body(ApiErrorResponse.of("FORBIDDEN", "Accès refusé"));
}
```

### 5.3 Vérification des callbacks opérateurs

C'est le point de sécurité **le plus critique** de tout l'agrégateur. Un callback non authentifié permet de frauder le système.

```java
// Chaque opérateur a sa méthode de vérification :

// MTN MoMo : vérifier X-Callback-Token (configuré lors de la création du user)
// Orange Money : vérifier la signature HMAC-SHA256
// Wave : vérifier le header Authorization Bearer avec leur public key

@PostMapping("/callbacks/{operator}")
public ResponseEntity<?> handleCallback(
    @PathVariable String operator,
    @RequestBody String rawBody,  // Lire le body RAW pour calculer le HMAC
    HttpServletRequest request
) {
    // 1. Vérifier l'IP source (whitelist des IPs opérateur)
    String sourceIp = extractIp(request);
    if (!operatorIpWhitelist.isAllowed(operator, sourceIp)) {
        log.warn("Callback from unauthorized IP: {}", sourceIp);
        return ResponseEntity.status(403).build();
    }

    // 2. Vérifier la signature HMAC
    String signature = request.getHeader("X-Signature");
    if (!hmacVerifier.verify(rawBody, getOperatorSecret(operator), signature)) {
        log.warn("Invalid callback signature for operator: {}", operator);
        return ResponseEntity.status(401).build();
    }

    // 3. Vérifier l'idempotence du callback (même référence déjà traitée ?)
    String operatorRef = extractReference(rawBody, operator);
    if (callbackIdempotenceService.alreadyProcessed(operatorRef)) {
        return ResponseEntity.ok().build();  // 200 OK pour stopper les retry
    }

    // 4. Traiter le callback
    callbackService.process(operator, rawBody);
    return ResponseEntity.ok().build();
}
```

### 5.4 Idempotence

L'idempotence doit être garantie à **chaque couche** du système. Avant de choisir un mécanisme, il faut comprendre pourquoi le choix technique varie selon la nature financière ou non de chaque couche.

#### 5.3.1 Redis vs Base de données — quelle différence ?

Redis est souvent présenté comme *la* solution d'idempotence. C'est une simplification dangereuse dans un contexte financier.

```
                    Redis SET NX          PostgreSQL UNIQUE
                    ─────────────         ─────────────────
Persistance         Non (volatile)        Oui (permanente)
Survie à un crash   Non (perte possible)  Oui (WAL, ACID)
TTL / expiration    Oui (risque replay)   Non (protection permanente)
Vitesse             ~0.5ms                ~5ms
Dépendance externe  Oui (point de défail) Non (déjà présente)
Garantie ACID       Non                   Oui
Usage recommandé    Confort UX (réponse   Données financières
                    rapide au client)     (transactions, ledger)
```

**Règle fondamentale :**
- Ce qui a un impact financier → **PostgreSQL UNIQUE constraint** (permanent, ACID, aucun TTL)
- Ce qui est de l'ordre du confort UX → **Redis SET NX** (rapide, acceptable si perdu)

Si Redis tombe, les opérations financières continuent d'être protégées par la base. Redis n'est qu'une optimisation de performance, jamais le seul filet de sécurité pour l'argent.

#### 5.3.2 Le vrai problème des événements Spring : l'Outbox Pattern

`@TransactionalEventListener(phase = AFTER_COMMIT)` résout 90% des cas mais pas le suivant :

```
Transaction DB commit ✅
App plante ICI      💥
Événement jamais publié ❌
→ wallet, webhook, notification ne reçoivent jamais la notification
→ le marchand n'est pas crédité, n'est pas notifié
```

La solution robuste est le **Transactional Outbox Pattern** : écrire l'événement dans la même transaction que la donnée métier. Un processus séparé le publie ensuite de façon garantie.

```sql
-- shared schema : table outbox (appartient au module qui publie l'événement)
CREATE TABLE payment.outbox_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type      VARCHAR(100) NOT NULL,   -- 'PaymentSucceededEvent'
    aggregate_id    UUID NOT NULL,           -- transactionId
    aggregate_type  VARCHAR(50) NOT NULL,    -- 'Transaction'
    payload         JSONB NOT NULL,          -- contenu complet de l'événement
    published       BOOLEAN NOT NULL DEFAULT FALSE,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_unpublished ON payment.outbox_events(created_at)
    WHERE published = FALSE;
```

```java
// PaymentService.java — écrire event et donnée dans la MÊME transaction
@Transactional
public void processCallback(String operatorRef, TransactionStatus newStatus) {

    Transaction tx = transactionRepo.findByOperatorReference(operatorRef).orElseThrow();
    transitionStatus(tx, newStatus);  // mise à jour de la transaction

    if (newStatus == TransactionStatus.SUCCESS) {
        // Écriture atomique avec la transaction : si le commit échoue, l'event n'existe pas
        outboxRepo.save(new OutboxEvent(
            "PaymentSucceededEvent",
            tx.getId(),
            "Transaction",
            buildPayload(tx)   // JSON complet
        ));
    }
    // Un seul commit → transaction ET outbox event sont écrits ensemble ou pas du tout
}

// OutboxPoller.java — processus séparé (scheduler toutes les secondes)
@Scheduled(fixedDelay = 1000)
@Transactional
public void pollAndPublish() {
    List<OutboxEvent> pending = outboxRepo.findUnpublished(Pageable.ofSize(50));

    for (OutboxEvent outbox : pending) {
        try {
            DomainEvent event = deserialize(outbox);
            applicationEventPublisher.publishEvent(event);  // ou queue message broker

            outbox.setPublished(true);
            outbox.setPublishedAt(Instant.now());
            outboxRepo.save(outbox);

        } catch (Exception e) {
            log.error("Failed to publish outbox event {}: {}", outbox.getId(), e.getMessage());
            // L'event reste published=false → sera retenté au prochain poll
        }
    }
}
```

Avec l'Outbox Pattern, la déduplication par `eventId` dans les listeners reste nécessaire (le poller peut publier deux fois en cas de crash entre publication et marquage `published=true`), mais la contrainte DB du grand livre constitue le filet final.

#### 5.3.3 Mécanismes par couche — recommandations définitives

```
Couche               Impact financier   Mécanisme recommandé               Fallback
──────────────────   ───────────────    ────────────────────────────────   ────────────────────
API (entrée)         Non (UX)           Redis SET NX (X-Idempotency-Key)   UNIQUE(merchant_id, merchant_reference) DB
Business logic       Oui                UNIQUE(merchant_id, merchant_ref)  —
Appel opérateur      Oui                UUID transaction = X-Reference-Id  Stateless (pas de stockage)
Callback entrant     Oui                UNIQUE(operator, operator_ref) DB  —
Événement domaine    Oui                Outbox Pattern (DB)                eventId dans listener
Grand livre          Oui (critique)     UNIQUE(account_id, ref, type) DB   DataIntegrityViolation catchée
Webhook marchand     Non                UNIQUE(endpoint_id, tx_id, event)  —
Notification client  Non (UX)           Redis SET NX TTL 1h                —
```

#### Couche 1 — API : Redis + contrainte DB en fallback

```java
public PaymentResponse initiatePayment(PaymentRequest req, Merchant merchant) {

    // Redis : réponse rapide si la clé optionnelle X-Idempotency-Key est fournie
    String idempotencyKey = req.getIdempotencyKey();
    if (idempotencyKey != null) {
        String cached = redis.get("idempotency:" + merchant.getId() + ":" + idempotencyKey);
        if (cached != null) {
            return deserialize(cached, PaymentResponse.class);
        }
    }

    // DB : protection permanente sur merchantReference (même si Redis est down)
    transactionRepo.findByMerchantIdAndMerchantReference(merchant.getId(), req.getMerchantReference())
        .ifPresent(tx -> { throw new DuplicateTransactionException(tx.getEbithexReference()); });

    PaymentResponse response = processPayment(req, merchant);

    // Stocker en Redis uniquement après succès (TTL 24h = confort, pas sécurité)
    if (idempotencyKey != null) {
        redis.setex("idempotency:" + merchant.getId() + ":" + idempotencyKey,
            86400, serialize(response));
    }

    return response;
}
```

#### Couche 2 — Appels opérateurs : UUID fixe (sans stockage)

```java
// Aucun stockage nécessaire : l'UUID de la transaction EST la clé d'idempotence
// L'opérateur gère lui-même la déduplication côté MTN/Orange via ce X-Reference-Id

public OperatorInitResponse initiateCollection(Transaction tx) {
    return webClient.post()
        .uri("/collection/v1_0/requesttopay")
        .header("X-Reference-Id", tx.getId().toString())  // toujours le même UUID
        .bodyValue(buildRequest(tx))
        .retrieve()
        .bodyToMono(OperatorInitResponse.class)
        .block();
}
```

#### Couche 3 — Callbacks opérateurs : contrainte UNIQUE en base (pas Redis)

```sql
-- Les callbacks sont des données financières → protection permanente en DB
-- Redis avec TTL 7 jours est insuffisant : un callback peut être rejoué
-- des mois plus tard dans certains systèmes opérateurs défaillants

CREATE TABLE operator.processed_callbacks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator        VARCHAR(50) NOT NULL,
    operator_ref    VARCHAR(255) NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(operator, operator_ref)   -- contrainte permanente
);
```

```java
@PostMapping("/callbacks/{operator}")
@Transactional
public ResponseEntity<?> handleCallback(@PathVariable String operator,
                                         @RequestBody String rawBody) {
    // ... vérification HMAC et IP ...

    String operatorRef = callbackParser.extractReference(rawBody, operator);

    try {
        // INSERT avec contrainte UNIQUE — atomique, pas de race condition
        processedCallbackRepo.save(new ProcessedCallback(operator, operatorRef));
    } catch (DataIntegrityViolationException e) {
        // Déjà traité → idempotent, retourner 200 pour stopper les retry
        log.info("Duplicate callback ignored: operator={} ref={}", operator, operatorRef);
        return ResponseEntity.ok().build();
    }

    callbackProcessor.process(operator, rawBody);
    return ResponseEntity.ok().build();
}
```

#### Couche 4 — Événements domaine : Outbox Pattern (voir 5.3.2)

```java
// Dans chaque listener : déduplication sur eventId comme filet secondaire
// (le poller peut publier deux fois en cas de crash entre publish et markPublished)

@Async("walletExecutor")
public void onPaymentSucceeded(PaymentSucceededEvent event) {

    // Filet secondaire uniquement — la vraie protection est dans la contrainte UNIQUE du ledger
    if (outboxRepo.isAlreadyConsumedBy("wallet", event.getEventId())) {
        return;
    }

    ledgerService.recordCollection(event);  // protégé par UNIQUE en DB
    outboxRepo.markConsumedBy("wallet", event.getEventId());
}
```

#### Couche 5 — Grand livre : contrainte UNIQUE PostgreSQL (protection absolue)

```sql
-- Filet final et non négociable pour l'intégrité financière
ALTER TABLE wallet.ledger_entries
    ADD CONSTRAINT uq_ledger_entry UNIQUE (account_id, reference_id, entry_type);
```

```java
public void credit(String accountId, BigDecimal amount, String currency,
                   String referenceId, String referenceType) {
    try {
        ledgerEntryRepo.save(new LedgerEntry(accountId, CREDIT, amount, currency,
                                              referenceId, referenceType));
    } catch (DataIntegrityViolationException e) {
        // Double écriture absorbée silencieusement — le premier commit a gagné
        log.info("Idempotent ledger write skipped: account={} ref={}", accountId, referenceId);
    }
}
```

#### Couche 6 — Webhooks marchands : contrainte UNIQUE en base

```java
// Création du WebhookDelivery avec contrainte UNIQUE — pas de Redis nécessaire
// Le DeliveryId est immuable et sert de clé d'idempotence pour les retries

public void dispatch(UUID transactionId, String eventType) {
    for (WebhookEndpoint endpoint : endpoints) {
        try {
            WebhookDelivery delivery = new WebhookDelivery(endpoint, transactionId, eventType);
            deliveryRepo.save(delivery);  // UNIQUE(endpoint_id, transaction_id, event_type)
            deliver(delivery);
        } catch (DataIntegrityViolationException e) {
            log.info("Webhook delivery already exists, skipping dispatch");
        }
    }
}
```

#### Couche 7 — Notifications clients : Redis acceptable

```java
// Les notifications SMS/email ne sont pas financières.
// Un doublon est gênant mais pas catastrophique.
// Redis TTL 1h est ici suffisant et plus simple qu'une contrainte DB.

@Async("notificationExecutor")
public void onPaymentSucceeded(PaymentSucceededEvent event) {
    Boolean isNew = redis.opsForValue()
        .setIfAbsent("notif:sms:" + event.transactionId(), "1", Duration.ofHours(1));

    if (Boolean.FALSE.equals(isNew)) return;

    smsService.sendPaymentConfirmation(event);
}
```

#### Récapitulatif — Matrice d'idempotence révisée

| Couche | Impact $ | Mécanisme principal | Redis utilisé ? | Survit à panne Redis ? |
|--------|----------|--------------------|-----------------|-----------------------|
| API entrée | Non | Redis `SET NX` | Oui (UX) | Oui (fallback DB UNIQUE) |
| Business logic | Oui | `UNIQUE(merchant_id, merchant_ref)` DB | Non | Oui ✅ |
| Appel opérateur | Oui | UUID fixe (stateless) | Non | Oui ✅ |
| Callback entrant | Oui | `UNIQUE(operator, operator_ref)` DB | Non | Oui ✅ |
| Événement domaine | Oui | Outbox Pattern (DB) | Non | Oui ✅ |
| Grand livre | Oui ⚠️ | `UNIQUE(account_id, ref, type)` DB | Non | Oui ✅ |
| Webhook marchand | Non | `UNIQUE(endpoint_id, tx_id, event)` DB | Non | Oui ✅ |
| Notification client | Non | Redis `SET NX` TTL 1h | Oui (acceptable) | Non (toléré) |

### 5.5 Rate Limiting

#### Plans par rôle marchand

| Plan | Rôle(s) | req/min | req/heure | Identificateur |
|------|---------|---------|-----------|----------------|
| **ANONYMOUS** | Non authentifié | 10 | 300 | IP (`X-Forwarded-For`) |
| **STANDARD** | `MERCHANT`, `AGENT` | 60 | 3 000 | `merchantId` |
| **PREMIUM** | `MERCHANT_KYC_VERIFIED` | 300 | 10 000 | `merchantId` |

#### Endpoints exclus du rate limiting

| Endpoint | Raison |
|----------|--------|
| `POST /v1/callbacks/**` | Callbacks opérateurs — flux critique, sécurisé par HMAC |
| `GET /actuator/**` | Sondes infra internes |
| `GET /v3/api-docs/**`, `/swagger-ui/**` | Documentation |

#### Algorithme : fixed-window Redis (deux fenêtres)

Deux compteurs indépendants par requête :
- **Fenêtre minute** → protection anti-burst
- **Fenêtre heure** → quota soutenu

Clé Redis : `rl:merchant:{id}:m:{epoch/60}` et `rl:merchant:{id}:h:{epoch/3600}`

Script Lua atomique (INCR + EXPIRE à la création) — pas de race condition.

**Fail-open** : si Redis est indisponible, la requête passe sans blocage (les paiements ne sont jamais bloqués par une panne Redis).

#### Headers de réponse

```
X-RateLimit-Limit: 60          Limite de la fenêtre minute active
X-RateLimit-Remaining: 43      Requêtes restantes (min des deux fenêtres)
X-RateLimit-Reset: 1710499200  Timestamp Unix de la prochaine réinitialisation
Retry-After: 37                Secondes avant de pouvoir réessayer (sur 429 uniquement)
```

#### Réponse 429

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Limite de requêtes dépassée. Réessayez dans 37s."
}
```

#### Implémentation

```
module-shared/src/main/java/io/ebithex/shared/security/
  ├── RateLimitPlan.java    — enum ANONYMOUS / STANDARD / PREMIUM avec limites
  ├── RateLimitService.java — script Lua Redis, retourne RateLimitResult
  └── RateLimitFilter.java  — OncePerRequestFilter, enregistré après JwtAuthFilter
```

Ordre dans la chaîne Spring Security :
```
ApiKeyAuthFilter → JwtAuthFilter → RateLimitFilter → Controllers
```

### 5.6 Chiffrement des données sensibles

```java
// Chiffrement AES-256-GCM des données PII et credentials opérateurs

@Service
public class EncryptionService {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    public EncryptedData encrypt(String plaintext, String keyId) {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        SecretKey key = keyManagementService.getKey(keyId);  // AWS KMS ou Vault

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
        cipher.updateAAD(keyId.getBytes());  // Additional authenticated data

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(UTF_8));

        return new EncryptedData(Base64.encode(ciphertext), Base64.encode(iv), keyId);
    }

    public String decrypt(EncryptedData data) {
        SecretKey key = keyManagementService.getKey(data.getKeyId());
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key,
            new GCMParameterSpec(GCM_TAG_LENGTH, Base64.decode(data.getIv())));
        cipher.updateAAD(data.getKeyId().getBytes());
        return new String(cipher.doFinal(Base64.decode(data.getCiphertext())), UTF_8);
    }
}

// Données à chiffrer obligatoirement :
// - phone_number des transactions
// - customer_email
// - credentials opérateurs (api_key, secret, subscription_key)
// - api_key_hash du marchand (stocker le hash + chiffrer l'original si affiché)
```

### 5.7 OWASP Top 10 — Checklist

| Vulnérabilité | Mitigation |
|---------------|------------|
| **A01 Broken Access Control** | Vérifier merchantId sur chaque ressource, ownership check systématique |
| **A02 Cryptographic Failures** | AES-256-GCM, RS256 JWT, HMAC-SHA256, TLS 1.3 minimum |
| **A03 Injection** | PreparedStatement (Spring Data JPA), jamais de concaténation SQL |
| **A04 Insecure Design** | Machine d'états stricte, principe du moindre privilège |
| **A05 Security Misconfiguration** | Headers de sécurité HTTP, CORS strict, actuator sécurisé |
| **A06 Vulnerable Components** | Dependabot / Renovate activé, scanning OWASP Dependency-Check |
| **A07 Auth Failures** | JWT courte durée, blacklist, lockout sur brute force |
| **A08 Software Integrity** | SBOM, checksums Docker, signature d'images |
| **A09 Logging Failures** | Audit log structuré, pas de données sensibles dans les logs |
| **A10 SSRF** | Whitelist stricte des URLs webhook, validation domaine |

### 5.8 Headers de sécurité HTTP

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.headers(headers -> headers
        .contentSecurityPolicy(csp ->
            csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
        .frameOptions(frame -> frame.deny())
        .xssProtection(xss -> xss.headerValue(XXssProtectionHeaderWriter.HeaderValue.ENABLED_MODE_BLOCK))
        .contentTypeOptions(Customizer.withDefaults())
        .httpStrictTransportSecurity(hsts -> hsts
            .maxAgeInSeconds(31536000)
            .includeSubDomains(true)
            .preload(true))
        .referrerPolicy(referrer ->
            referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
    );
    return http.build();
}
```

### 5.9 Validation des URLs webhook (SSRF protection)

```java
public void validateWebhookUrl(String url) {
    URI uri = URI.create(url);

    // HTTPS obligatoire en production
    if (!"https".equals(uri.getScheme())) {
        throw new ValidationException("L'URL webhook doit utiliser HTTPS");
    }

    // Interdire les IPs privées (SSRF)
    InetAddress address = InetAddress.getByName(uri.getHost());
    if (address.isLoopbackAddress()    // 127.x.x.x
     || address.isSiteLocalAddress()   // 10.x, 172.16-31.x, 192.168.x
     || address.isLinkLocalAddress()   // 169.254.x.x
     || address.isAnyLocalAddress()) {
        throw new ValidationException("URL webhook non autorisée");
    }

    // Whitelist de TLD ou domaines si nécessaire
    // Pas de IPs brutes sans reverse DNS
}
```

---

## 6. Intégrations opérateurs

### 6.1 Interface standardisée

```java
public interface MobileMoneyOperator {

    // Identifiant de l'opérateur
    OperatorType getType();

    // Pays supportés
    Set<String> getSupportedCountries();

    // Limites de l'opérateur
    OperatorLimits getLimits(String country, String currency);

    // === COLLECTION (Cash-in) ===

    /**
     * Initier un paiement (prélèvement depuis le client).
     * @throws OperatorException avec un code d'erreur normalisé
     */
    @Retryable(...)
    OperatorInitResponse initiateCollection(Transaction transaction);

    /**
     * Vérifier le statut d'une collection.
     */
    TransactionStatus checkCollectionStatus(String operatorReference);

    /**
     * Rembourser une collection réussie.
     */
    OperatorRefundResponse refundCollection(String operatorReference, BigDecimal amount);

    // === PAYOUT (Cash-out) ===

    /**
     * Envoyer de l'argent vers un portefeuille.
     */
    @Retryable(...)
    OperatorInitResponse initiatePayout(Transaction transaction);

    /**
     * Vérifier le statut d'un payout.
     */
    TransactionStatus checkPayoutStatus(String operatorReference);

    // === UTILITAIRES ===

    /**
     * Vérifier le solde du compte opérateur (pour les payouts).
     */
    BigDecimal getAccountBalance();

    /**
     * Vérifier qu'un numéro de téléphone est bien enregistré chez cet opérateur.
     */
    boolean verifyPhoneNumber(String phoneNumber);

    /**
     * Test de connectivité.
     */
    boolean isHealthy();
}
```

### 6.2 Normalisation des erreurs opérateurs

```java
public enum OperatorErrorCode {
    // Erreurs client (4xx équivalent)
    INSUFFICIENT_FUNDS("OP001", "Solde insuffisant"),
    ACCOUNT_NOT_FOUND("OP002", "Numéro non trouvé chez l'opérateur"),
    ACCOUNT_BLOCKED("OP003", "Compte bloqué"),
    LIMIT_EXCEEDED("OP004", "Limite dépassée"),
    INVALID_AMOUNT("OP005", "Montant invalide pour cet opérateur"),
    TRANSACTION_DECLINED("OP006", "Transaction refusée par le client"),
    EXPIRED_TRANSACTION("OP007", "Transaction expirée"),

    // Erreurs opérateur (5xx équivalent — retriables)
    OPERATOR_TIMEOUT("OP100", "Timeout de l'opérateur"),
    OPERATOR_UNAVAILABLE("OP101", "Opérateur temporairement indisponible"),
    OPERATOR_INTERNAL_ERROR("OP102", "Erreur interne opérateur"),
    AUTHENTICATION_FAILED("OP103", "Échec d'authentification opérateur"),

    // Erreurs inconnues
    UNKNOWN("OP999", "Erreur inconnue");

    // Mapper chaque code opérateur natif vers ce code normalisé
    public static OperatorErrorCode fromMtnCode(String mtnCode) { ... }
    public static OperatorErrorCode fromOrangeCode(String orangeCode) { ... }
}
```

### 6.3 Gestion des tokens opérateur avec cache

```java
// Les tokens opérateurs (OAuth2) doivent être cachés pour éviter de régénérer
// à chaque requête (limite de taux des APIs opérateur)

@Service
public class OperatorTokenCache {

    private final RedisTemplate<String, String> redis;

    public String getToken(String operator, String country) {
        String key = String.format("operator_token:%s:%s", operator, country);
        String cached = redis.opsForValue().get(key);

        if (cached != null) return cached;

        OperatorToken fresh = fetchFreshToken(operator, country);

        // Stocker avec TTL = expiration - 60s (marge de sécurité)
        long ttl = fresh.getExpiresIn() - 60;
        redis.opsForValue().set(key, fresh.getAccessToken(), Duration.ofSeconds(ttl));

        return fresh.getAccessToken();
    }

    public void invalidateToken(String operator, String country) {
        redis.delete(String.format("operator_token:%s:%s", operator, country));
    }
}
```

### 6.4 Normalisation des numéros de téléphone

#### Pourquoi c'est critique

Un numéro de téléphone peut arriver sous des dizaines de formats différents selon le marchand, le pays ou l'interface. Si Ebithex ne normalise pas avant de traiter, les conséquences sont directes :

- Un numéro `07123456` (format local ivoirien) est envoyé à MTN tel quel → rejet opérateur
- Un numéro `00225 07 12 34 56` (double zéro + espaces) est comparé à un pattern `^\+225` → pas de match → détection d'opérateur impossible → transaction échouée
- Un numéro `+221 77 123 45 67` est stocké avec espaces → la contrainte de recherche échoue

**Règle absolue : tout numéro est normalisé en E.164 à l'entrée du système, avant toute autre opération.**

#### Format E.164

```
Format E.164 :  +{indicatif pays}{numéro local sans zéro}
Longueur :      max 15 chiffres (hors le +)
Exemples :
  +22507123456   Côte d'Ivoire, numéro 07 12 34 56
  +221771234567  Sénégal, numéro 77 123 45 67
  +22961234567   Bénin, numéro 61 23 45 67
  +22670123456   Burkina Faso, numéro 70 12 34 56
```

#### Formats entrants à normaliser (UEMOA)

```
Format reçu                    Transformation                 Résultat E.164
─────────────────────────────  ─────────────────────────────  ──────────────────
07123456                       + indicatif pays (CI = 225)    +22507123456
0507123456                     suppr. 0 initial + indicatif   +22507123456  ← ⚠️ ambiguë sans pays
+22507123456                   rien (déjà E.164)              +22507123456
00225 07 12 34 56              00 → +, suppr. espaces         +22507123456
+225 07-12-34-56               suppr. espaces et tirets       +22507123456
(+225) 07 12 34 56             suppr. parenthèses et espaces  +22507123456
225507123456                   + ajout du +                   +225507123456  ← ⚠️ vérifier longueur
77 123 45 67                   + indicatif pays (SN = 221)    +221771234567
```

#### Recommandation : Google libphonenumber (pas de regex maison)

Les regex maison échouent sur des cas limites courants. La bibliothèque [libphonenumber](https://github.com/google/libphonenumber) de Google (utilisée par WhatsApp, Stripe, Twilio...) gère tous les formats de 240+ pays et est maintenue en temps réel.

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.googlecode.libphonenumber</groupId>
    <artifactId>libphonenumber</artifactId>
    <version>8.13.x</version>
</dependency>
```

#### Value Object PhoneNumber

```java
// shared/domain/PhoneNumber.java
// Immuable — créé une fois, toujours valide, toujours normalisé

public final class PhoneNumber {

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();

    private final String e164;          // +22507123456 — format de stockage
    private final String countryCode;   // CI, SN, ML, BF, BJ, TG, GN
    private final String nationalNumber; // 07123456 — pour affichage local

    // Constructeur privé : seule la factory method peut créer une instance
    private PhoneNumber(String e164, String countryCode, String nationalNumber) {
        this.e164 = e164;
        this.countryCode = countryCode;
        this.nationalNumber = nationalNumber;
    }

    /**
     * Point d'entrée unique pour créer un PhoneNumber.
     * @param rawInput  Le numéro brut reçu du marchand (n'importe quel format)
     * @param country   Le pays du marchand (ISO 3166-1 alpha-2 : CI, SN, ML...)
     *                  Utilisé comme région par défaut si le numéro est local (sans indicatif)
     * @throws InvalidPhoneNumberException si le numéro est invalide ou non supporté
     */
    public static PhoneNumber of(String rawInput, String country) {
        if (rawInput == null || rawInput.isBlank()) {
            throw new InvalidPhoneNumberException("Le numéro de téléphone est obligatoire");
        }

        // Nettoyer les caractères non numériques sauf + en tête
        String cleaned = rawInput.trim()
            .replaceAll("[\\s\\-\\.\\(\\)]", "")   // espaces, tirets, points, parenthèses
            .replaceAll("^00", "+");                // 00225... → +225...

        try {
            Phonenumber.PhoneNumber parsed = PHONE_UTIL.parse(cleaned, country);

            // Valider : numéro possible ET valide pour ce pays
            if (!PHONE_UTIL.isValidNumber(parsed)) {
                throw new InvalidPhoneNumberException(
                    "Numéro invalide pour le pays " + country + " : " + mask(cleaned));
            }

            String e164         = PHONE_UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164);
            String isoCountry   = PHONE_UTIL.getRegionCodeForNumber(parsed);
            String nationalNum  = PHONE_UTIL.format(parsed, PhoneNumberUtil.PhoneNumberFormat.NATIONAL);

            // Vérifier que le pays détecté fait partie des pays UEMOA supportés
            if (!SupportedCountries.UEMOA.contains(isoCountry)) {
                throw new InvalidPhoneNumberException(
                    "Pays non supporté : " + isoCountry + " (pays UEMOA attendu)");
            }

            return new PhoneNumber(e164, isoCountry, nationalNum);

        } catch (NumberParseException e) {
            throw new InvalidPhoneNumberException(
                "Format de numéro non reconnu : " + mask(cleaned));
        }
    }

    public String toE164()          { return e164; }           // +22507123456 (stockage DB)
    public String toNational()      { return nationalNumber; } // 07 12 34 56  (affichage)
    public String getCountry()      { return countryCode; }    // CI

    // Masquage pour les logs — ne jamais logger un numéro en clair
    public String toMasked()        { return mask(e164); }     // +225071****6

    @Override
    public String toString()        { return e164; }

    private static String mask(String phone) {
        if (phone.length() < 8) return "****";
        return phone.substring(0, Math.min(7, phone.length()))
            + "****"
            + phone.substring(phone.length() - 1);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PhoneNumber)) return false;
        return e164.equals(((PhoneNumber) o).e164);
    }

    @Override
    public int hashCode() { return e164.hashCode(); }
}
```

#### Pays UEMOA supportés

```java
// shared/domain/SupportedCountries.java
public final class SupportedCountries {

    public static final Set<String> UEMOA = Set.of(
        "CI",  // Côte d'Ivoire     +225
        "SN",  // Sénégal           +221
        "ML",  // Mali              +223
        "BF",  // Burkina Faso      +226
        "BJ",  // Bénin             +229
        "TG",  // Togo              +228
        "GN",  // Guinée Conakry    +224
        "GW"   // Guinée-Bissau     +245
    );

    // Pour référence — indicatifs pays
    public static final Map<String, String> DIAL_CODES = Map.of(
        "CI", "+225",
        "SN", "+221",
        "ML", "+223",
        "BF", "+226",
        "BJ", "+229",
        "TG", "+228",
        "GN", "+224",
        "GW", "+245"
    );
}
```

#### Cas d'usage dans le service de paiement

```java
// PaymentService.java — normalisation en première étape
public PaymentResponse initiatePayment(PaymentRequest req, Merchant merchant) {

    // Normaliser immédiatement — toute la suite du code travaille avec E.164
    PhoneNumber phone;
    try {
        phone = PhoneNumber.of(req.getPhoneNumber(), merchant.getCountry());
    } catch (InvalidPhoneNumberException e) {
        throw new ValidationException("VAL_003", e.getMessage());
    }

    // Désormais : phone.toE164() = "+22507123456" garanti
    OperatorType operator = resolveOperator(req, phone);
    limitService.check(merchant.getId(), req.getAmount(), req.getCurrency());
    FeeCalculation fees = feeService.calculate(req.getAmount(), req.getCurrency(), operator, merchant);

    Transaction tx = Transaction.builder()
        .phoneNumber(phone.toE164())          // stockage normalisé
        .operator(operator)
        // ...
        .build();
}
```

#### Règles de stockage et d'affichage

```
Stockage en base (phone_number) : toujours E.164 — +22507123456
Affichage dans l'API response   : E.164 également (standard international)
Affichage dans les webhooks     : E.164 avec masquage partiel — +225071****6
Logs applicatifs                : toujours masqué — jamais le numéro complet
Envoi aux opérateurs            : selon le format attendu par chaque opérateur
  MTN MoMo  → E.164 sans + : 22507123456
  Orange    → E.164 avec + : +22507123456
  Wave      → E.164 avec + : +22507123456
```

#### Tests de normalisation obligatoires

```java
@ParameterizedTest
@CsvSource({
    // Format local sans indicatif (pays = CI)
    "07123456,         CI, +22507123456",
    "0507123456,       CI, +22507123456",

    // Format avec indicatif complet
    "+22507123456,     CI, +22507123456",
    "00225507123456,   CI, +225507123456",  // 00 → +

    // Format avec espaces et tirets
    "+225 07 12 34 56, CI, +22507123456",
    "+225-07-12-34-56, CI, +22507123456",
    "(+225) 07123456,  CI, +22507123456",

    // Sénégal
    "771234567,        SN, +221771234567",
    "+221771234567,    SN, +221771234567",
    "00221771234567,   SN, +221771234567",

    // Burkina Faso
    "70123456,         BF, +22670123456",
})
void shouldNormalizePhoneNumberToE164(String input, String country, String expected) {
    PhoneNumber phone = PhoneNumber.of(input, country);
    assertThat(phone.toE164()).isEqualTo(expected);
}

@ParameterizedTest
@CsvSource({
    "invalid,          CI",   // texte invalide
    "123,              CI",   // trop court
    "+33612345678,     CI",   // numéro français — pays non UEMOA
    ",                 CI",   // null/vide
})
void shouldRejectInvalidPhoneNumbers(String input, String country) {
    assertThrows(InvalidPhoneNumberException.class,
        () -> PhoneNumber.of(input, country));
}
```

### 6.5 Détection automatique d'opérateur

```java
// Patterns E.164 par opérateur et pays — doit être externalisé en configuration
// pour permettre les mises à jour sans redeployer

@ConfigurationProperties(prefix = "operators.phone-patterns")
public class PhonePatternConfig {
    private Map<String, List<String>> patterns;  // operator -> liste de regex
}

@Service
public class OperatorDetectionService {

    public OperatorType detect(String phoneE164, String country) {
        // Normaliser en E.164 d'abord
        String normalized = normalizeToE164(phoneE164, country);

        for (Map.Entry<String, List<String>> entry : patterns.entrySet()) {
            for (String pattern : entry.getValue()) {
                if (normalized.matches(pattern)) {
                    return OperatorType.valueOf(entry.getKey());
                }
            }
        }

        // Si détection impossible, demander au marchand de préciser
        throw new OperatorDetectionException(
            "Impossible de détecter l'opérateur pour: " + mask(normalized));
    }

    // Masquer le numéro dans les logs : +22507123456 → +225071****6
    private String mask(String phone) {
        return phone.substring(0, 7) + "****" + phone.substring(phone.length() - 1);
    }
}
```

---

## 7. Gestion des transactions

### 7.1 Service de paiement complet

```java
@Service
@Transactional
public class PaymentService {

    public PaymentResponse initiatePayment(PaymentRequest req, Merchant merchant) {

        // === ÉTAPE 1 : Validations métier ===
        validatePaymentRequest(req, merchant);

        // === ÉTAPE 2 : Idempotence ===
        checkIdempotence(req.getMerchantReference(), merchant.getId());

        // === ÉTAPE 3 : Détection opérateur ===
        OperatorType operator = resolveOperator(req, merchant);

        // === ÉTAPE 4 : Vérification des limites ===
        limitChecker.check(merchant, operator, req.getAmount(), req.getCurrency());

        // === ÉTAPE 5 : Calcul des frais ===
        FeeCalculation fees = feeService.calculate(
            req.getAmount(), req.getCurrency(), operator, merchant);

        // === ÉTAPE 6 : Persister la transaction en PENDING ===
        Transaction tx = buildTransaction(req, merchant, operator, fees);
        tx = transactionRepo.save(tx);

        // Enregistrer l'événement
        eventService.record(tx, "INITIATED", null, null, "system", null);

        // === ÉTAPE 7 : Appeler l'opérateur ===
        OperatorInitResponse operatorResp;
        try {
            operatorResp = operatorRegistry.get(operator).initiateCollection(tx);
        } catch (OperatorException e) {
            // Transition vers FAILED avec la raison normalisée
            transitionStatus(tx, TransactionStatus.FAILED, e.getNormalizedCode(), e.getMessage());
            webhookService.notifyAsync(tx);
            throw new PaymentFailedException(e.getNormalizedCode(), e.getUserMessage());
        }

        // === ÉTAPE 8 : Mettre à jour avec la réponse opérateur ===
        transitionStatus(tx, operatorResp.getInitialStatus(), null, null);
        tx.setOperatorReference(operatorResp.getOperatorReference());
        transactionRepo.save(tx);

        // === ÉTAPE 9 : Notifier le marchand via webhook ===
        webhookService.notifyAsync(tx);

        return PaymentResponse.from(tx, operatorResp);
    }

    private void transitionStatus(Transaction tx, TransactionStatus newStatus,
                                   String failureCode, String failureReason) {
        // Valider la transition
        if (!StateMachine.isValidTransition(tx.getStatus(), newStatus)) {
            throw new InvalidTransitionException(tx.getStatus(), newStatus);
        }

        TransactionStatus previous = tx.getStatus();
        tx.setPreviousStatus(previous);
        tx.setStatus(newStatus);
        tx.setStatusUpdatedAt(Instant.now());
        if (failureCode != null) tx.setFailureCode(failureCode);
        if (failureReason != null) tx.setFailureReason(failureReason);

        transactionRepo.save(tx);

        // Enregistrer dans l'audit trail
        eventService.record(tx, "STATUS_CHANGED", previous, newStatus, "system", null);
    }

    private void validatePaymentRequest(PaymentRequest req, Merchant merchant) {
        // Vérifier que le montant est dans les limites du marchand
        if (merchant.getPerTransactionMax() != null &&
            req.getAmount().compareTo(merchant.getPerTransactionMax()) > 0) {
            throw new LimitExceededException("Montant supérieur à la limite par transaction");
        }
        if (req.getAmount().compareTo(merchant.getPerTransactionMin()) < 0) {
            throw new ValidationException("Montant inférieur au minimum autorisé");
        }

        // Vérifier la limite journalière
        BigDecimal todayVolume = transactionRepo.sumSuccessfulAmountToday(merchant.getId());
        if (merchant.getDailyLimit() != null &&
            todayVolume.add(req.getAmount()).compareTo(merchant.getDailyLimit()) > 0) {
            throw new LimitExceededException("Limite journalière atteinte");
        }
    }
}
```

### 7.2 Scheduler d'expiration et de réconciliation

```java
@Component
public class TransactionScheduler {

    // Expirer les transactions en attente depuis plus de 15 minutes
    @Scheduled(fixedDelay = 60_000)  // toutes les minutes
    @Transactional
    public void expireStaleTransactions() {
        int expired = transactionRepo.expireStale(Instant.now());
        if (expired > 0) {
            log.info("Expired {} stale transactions", expired);
            // Notifier les marchands via webhook
            transactionRepo.findRecentlyExpired().forEach(webhookService::notifyAsync);
        }
    }

    // Synchroniser le statut des transactions PROCESSING (polling opérateur)
    @Scheduled(fixedDelay = 30_000)  // toutes les 30 secondes
    public void syncProcessingTransactions() {
        List<Transaction> processing = transactionRepo
            .findByStatusAndLastAttemptAtBefore(
                TransactionStatus.PROCESSING,
                Instant.now().minusSeconds(30)
            );

        for (Transaction tx : processing) {
            try {
                TransactionStatus operatorStatus = operatorRegistry
                    .get(tx.getOperator())
                    .checkCollectionStatus(tx.getOperatorReference());

                if (operatorStatus != tx.getStatus()) {
                    paymentService.transitionStatus(tx, operatorStatus, null, null);
                    webhookService.notifyAsync(tx);
                }

                tx.setLastAttemptAt(Instant.now());
                tx.setAttemptCount(tx.getAttemptCount() + 1);
                transactionRepo.save(tx);

            } catch (Exception e) {
                log.warn("Failed to sync status for tx {}: {}", tx.getId(), e.getMessage());
            }
        }
    }

    // Réconciliation quotidienne
    @Scheduled(cron = "0 30 2 * * *")  // 02h30 chaque nuit
    public void dailyReconciliation() {
        reconciliationService.runDailyReconciliation(LocalDate.now().minusDays(1));
    }
}
```

### 7.3 Service de réconciliation

```java
@Service
public class ReconciliationService {

    public void runDailyReconciliation(LocalDate date) {
        for (OperatorType operator : OperatorType.values()) {
            try {
                // 1. Récupérer notre liste de transactions
                List<Transaction> ourTransactions = transactionRepo
                    .findByOperatorAndDate(operator, date);

                // 2. Récupérer la liste de l'opérateur
                List<OperatorTransaction> operatorTransactions = operatorRegistry
                    .get(operator)
                    .getTransactionHistory(date);

                // 3. Comparer
                ReconciliationResult result = compare(ourTransactions, operatorTransactions);

                // 4. Traiter les écarts
                result.getMismatches().forEach(this::handleMismatch);

                // 5. Générer le rapport
                ReconciliationReport report = saveReport(operator, date, result);

                // 6. Alerter si écarts significatifs
                if (result.hasSignificantDiscrepancies()) {
                    alertService.sendReconciliationAlert(report);
                }

            } catch (Exception e) {
                log.error("Reconciliation failed for operator {}: {}", operator, e.getMessage());
                alertService.sendReconciliationFailureAlert(operator, date, e);
            }
        }
    }

    private void handleMismatch(TransactionMismatch mismatch) {
        switch (mismatch.getType()) {
            case MISSING_IN_OPERATOR:
                // Transaction dans notre système mais pas chez l'opérateur
                // → Marquer pour investigation manuelle
                break;
            case MISSING_IN_OUR_SYSTEM:
                // Transaction chez l'opérateur mais pas dans notre système
                // → Créer une transaction fantôme et alerter
                break;
            case STATUS_MISMATCH:
                // Statut différent → mettre à jour avec la vérité opérateur
                paymentService.forceStatusUpdate(mismatch.getTransaction(),
                    mismatch.getOperatorStatus());
                break;
            case AMOUNT_MISMATCH:
                // Montant différent → alerte urgente (fraude possible)
                alertService.sendUrgentAlert("Amount mismatch", mismatch);
                break;
        }
    }
}
```

---

## 8. Système de webhooks

### 8.1 Clarification fondamentale — Callback, Webhook et Notification

Ces trois termes sont souvent confondus. Dans un agrégateur mobile money, ils désignent des flux **distincts, dans des directions opposées, entre des acteurs différents**.

```
┌─────────────┐                    ┌─────────────┐                    ┌─────────────┐
│  OPÉRATEUR  │                    │   EBITHEX   │                    │  MARCHAND   │
│ (MTN, Wave) │                    │ (agrégateur)│                    │  (client)   │
└──────┬──────┘                    └──────┬──────┘                    └──────┬──────┘
       │                                  │                                  │
       │  ① CALLBACK (inbound)            │                                  │
       │  "Le paiement X est confirmé"    │                                  │
       │ ────────────────────────────────►│                                  │
       │                                  │                                  │
       │                                  │  ② WEBHOOK (outbound marchand)   │
       │                                  │  "Votre transaction a réussi"    │
       │                                  │ ────────────────────────────────►│
       │                                  │                                  │
       │                           ┌──────┴──────┐
       │                           │ CLIENT FINAL│
       │                           │  (payeur)   │
       │                           └──────┬──────┘
       │                                  │
       │                                  │  ③ NOTIFICATION (outbound client)
       │                                  │  SMS: "Paiement de 5000 XOF reçu"
       │                                  │ ◄────────────────────────────────
       │                                  │   (Ebithex → Client final)
```

| Concept | Direction | Émetteur | Destinataire | Protocole | Module |
|---------|-----------|----------|--------------|-----------|--------|
| **Callback** | ← Inbound | Opérateur MNO | Ebithex | HTTP POST signé (HMAC) | `operator/inbound` |
| **Webhook** | → Outbound | Ebithex | Marchand | HTTP POST signé (HMAC) | `webhook` |
| **Notification** | → Outbound | Ebithex | Client final (payeur) | SMS / Email / Push | `notification` |

#### Callback (operator → Ebithex)

Le callback est la **confirmation asynchrone** que l'opérateur envoie à Ebithex après qu'un client a validé (ou refusé) son paiement sur son téléphone. Ebithex ne peut pas considérer un paiement comme réussi sans ce callback.

- **Sécurité :** Ebithex vérifie la signature HMAC et l'IP source avant tout traitement
- **Idempotence :** un même callback peut arriver plusieurs fois (retry opérateur) — il doit être traité une seule fois
- **Rôle du `CallbackProcessor` :** traduire le langage propriétaire de l'opérateur (`"status": "SUCCESSFUL"` chez MTN, `"statut": "SUCCESS"` chez Orange) en événement domaine unifié `OperatorCallbackReceivedEvent`
- **URL exemple :** `POST https://api.ebithex.io/v1/callbacks/mtn-momo`

#### Webhook (Ebithex → Marchand)

Le webhook est la **notification que le marchand a demandée** pour être informé en temps réel de l'évolution de ses transactions. Ebithex envoie un HTTP POST signé vers l'URL configurée par le marchand.

- **Déclencheur :** tout changement de statut d'une transaction (SUCCESS, FAILED, EXPIRED...)
- **Fiabilité :** retry avec backoff exponentiel jusqu'à 7 tentatives
- **Sécurité :** signature HMAC-SHA256 dans le header `X-Ebithex-Signature` pour que le marchand puisse vérifier l'authenticité
- **URL exemple :** `POST https://shop.marchand.com/webhooks/ebithex` (configurée par le marchand)

#### Notification (Ebithex → Client final)

La notification est un message **informatif envoyé au payeur** pour l'informer du résultat de son paiement. Elle est optionnelle mais fortement recommandée pour l'expérience utilisateur.

- **SMS :** "Votre paiement de 5 000 XOF à Shop XYZ a été effectué avec succès. Réf: AP-P-20240315-001"
- **Email :** reçu de paiement si le client a fourni son email
- **Push :** notification applicative si une app mobile est intégrée
- **Important :** ce module n'a aucun accès aux données financières — il reçoit uniquement les informations nécessaires à la notification via l'événement domaine

#### Règle d'or

> Un **callback** entrant déclenche une mise à jour de statut dans `payment`,
> qui publie un événement domaine,
> qui déclenche simultanément et indépendamment un **webhook** vers le marchand
> et une **notification** vers le client final.
> Ces trois opérations sont découplées — l'échec de l'une n'affecte pas les autres.

### 8.2 Architecture asynchrone avec message broker

```
Payment Service ──publish──► [Queue: webhooks.dispatch] ──consume──► Webhook Worker
                                                                           │
                                                                    Fetch endpoints
                                                                           │
                                                                    Sign payload
                                                                           │
                                                                    HTTP POST ──► Merchant
                                                                           │
                                                              ┌─── 2xx OK: mark delivered
                                                              └─── Error: schedule retry
                                                                    [Queue: webhooks.retry]
```

### 8.2 Format du payload webhook

```json
{
  "id": "evt_01HX4K2M3N5P6Q7R8S9T0U1V2",
  "event": "payment.success",
  "version": "1.0",
  "timestamp": "2024-03-15T10:30:00.123Z",
  "livemode": true,
  "data": {
    "object": "transaction",
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "ebithexReference": "AP-P-20240315-0000001234",
    "merchantReference": "CMD-2024-001",
    "status": "SUCCESS",
    "type": "COLLECTION",
    "amount": 5000,
    "feeAmount": 40,
    "netAmount": 4960,
    "currency": "XOF",
    "phoneNumber": "+22507XXXXXXX",
    "operator": "MTN_MOMO",
    "operatorReference": "mtn-ref-abc123",
    "customer": {
      "name": "John Doe",
      "email": "j***@example.com"
    },
    "metadata": {},
    "createdAt": "2024-03-15T10:29:45.000Z",
    "updatedAt": "2024-03-15T10:30:00.000Z"
  }
}
```

### 8.3 Signature et vérification

```java
// Signature du payload webhook
public String signPayload(String payload, String signingSecret) {
    long timestamp = Instant.now().getEpochSecond();
    String signedPayload = timestamp + "." + payload;

    byte[] secret = signingSecret.getBytes(UTF_8);
    byte[] data = signedPayload.getBytes(UTF_8);

    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret, "HmacSHA256"));
    byte[] hash = mac.doFinal(data);

    // Format : t=TIMESTAMP,v1=SIGNATURE (compatible avec Stripe)
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(hash);
}

// Vérification côté marchand (exemple en Java)
public boolean verify(String payload, String header, String signingSecret) {
    // header = "t=1710499200,v1=abc123..."
    long timestamp = Long.parseLong(extractValue(header, "t"));
    String signature = extractValue(header, "v1");

    // Rejeter les webhooks trop anciens (protection contre replay attacks)
    if (Math.abs(Instant.now().getEpochSecond() - timestamp) > 300) {  // 5 min
        return false;
    }

    String expected = computeSignature(timestamp + "." + payload, signingSecret);
    return MessageDigest.isEqual(
        HexFormat.of().parseHex(expected),
        HexFormat.of().parseHex(signature)
    );  // Constant-time comparison
}
```

### 8.4 Stratégie de retry avec backoff exponentiel

```
Tentative 1 : immédiat
Tentative 2 : +5 minutes
Tentative 3 : +30 minutes
Tentative 4 : +2 heures
Tentative 5 : +5 heures
Tentative 6 : +10 heures
Tentative 7 : +24 heures

Après 7 échecs : désactiver l'endpoint et alerter le marchand

Règle : si le marchand retourne un 410 Gone → désactiver immédiatement l'endpoint
Règle : les codes 5xx sont retriables, les codes 4xx sont des échecs définitifs (sauf 429)
Règle : timeout de la requête HTTP : 10 secondes maximum
```

### 8.5 Événements webhook supportés

```
payment.pending        Transaction en attente de confirmation client
payment.processing     Transaction soumise à l'opérateur
payment.success        Transaction réussie
payment.failed         Transaction échouée
payment.expired        Transaction expirée avant traitement
payment.cancelled      Transaction annulée par le marchand
payment.refunded       Remboursement effectué

payout.pending         Payout initié
payout.success         Payout réussi
payout.failed          Payout échoué

merchant.kyc.approved  KYC validé
merchant.kyc.rejected  KYC rejeté

operator.degraded      Opérateur en mode dégradé
operator.restored      Opérateur rétabli
```

---

## 9. Résilience et haute disponibilité

### 9.1 Circuit Breaker (Resilience4j)

```java
// Configuration par opérateur
@Bean
public CircuitBreakerConfig mtnCircuitBreakerConfig() {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(COUNT_BASED)
        .slidingWindowSize(20)                    // 20 derniers appels
        .failureRateThreshold(50f)                // 50% d'échecs → OPEN
        .slowCallRateThreshold(80f)               // 80% de lenteurs → OPEN
        .slowCallDurationThreshold(Duration.ofSeconds(5))
        .waitDurationInOpenState(Duration.ofSeconds(30))  // 30s avant HALF_OPEN
        .permittedNumberOfCallsInHalfOpenState(5)
        .recordExceptions(OperatorException.class, WebClientResponseException.class)
        .build();
}

// Usage
@CircuitBreaker(name = "mtn-momo", fallbackMethod = "operatorFallback")
@Retry(name = "mtn-momo")
@TimeLimiter(name = "mtn-momo")
public OperatorInitResponse initiateCollection(Transaction tx) { ... }

public OperatorInitResponse operatorFallback(Transaction tx, Exception ex) {
    // Circuit ouvert → retourner une erreur explicite, ne pas FAILED la transaction
    throw new OperatorUnavailableException(
        "L'opérateur MTN MoMo est temporairement indisponible. Veuillez réessayer.",
        OperatorErrorCode.OPERATOR_UNAVAILABLE
    );
}
```

### 9.2 Configuration de retry

```java
// Retry uniquement sur les erreurs retriables (5xx opérateur, timeout)
// Ne jamais retry sur les erreurs client (4xx) comme insufficient_funds

@Retryable(
    retryFor = { OperatorTimeoutException.class, OperatorUnavailableException.class },
    noRetryFor = { InsufficientFundsException.class, AccountNotFoundException.class },
    maxAttempts = 3,
    backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 10000)
)
public OperatorInitResponse initiateCollection(Transaction tx) { ... }
```

### 9.3 Bulkhead (isolation des ressources)

```java
// Pool de threads séparé par opérateur pour éviter qu'un opérateur lent
// n'affecte les autres

@Bulkhead(name = "mtn-momo", type = THREADPOOL)
public OperatorInitResponse callMtn(Transaction tx) { ... }

@Bulkhead(name = "orange-money", type = THREADPOOL)
public OperatorInitResponse callOrange(Transaction tx) { ... }
```

### 9.4 Fallback opérateur

```java
// Si l'opérateur primaire est indisponible, proposer une alternative
// (uniquement si le client a plusieurs opérateurs sur son numéro)

public PaymentResponse initiateWithFallback(PaymentRequest req, Merchant merchant) {
    OperatorType primary = resolveOperator(req);

    if (!circuitBreakerRegistry.circuitBreaker(primary.name()).getState().equals(CLOSED)) {
        // Circuit ouvert → chercher un opérateur alternatif
        Optional<OperatorType> fallback = operatorDetection.findAlternative(
            req.getPhoneNumber(), primary);

        if (fallback.isPresent()) {
            log.info("Falling back from {} to {} for tx {}",
                primary, fallback.get(), req.getMerchantReference());
            req.setOperator(fallback.get());
            return initiatePayment(req, merchant);
        }

        throw new OperatorUnavailableException("Aucun opérateur disponible pour ce numéro");
    }

    return initiatePayment(req, merchant);
}
```

### 9.5 Gestion du mode dégradé

```java
// Page de statut interne des opérateurs
@Service
public class OperatorHealthService {

    @Scheduled(fixedDelay = 60_000)
    public void checkOperatorHealth() {
        for (MobileMoneyOperator op : operators) {
            boolean healthy = op.isHealthy();

            String redisKey = "operator:health:" + op.getType().name();
            OperatorHealth previous = getHealth(redisKey);

            if (!healthy && previous.isHealthy()) {
                // Dégradation détectée → alerter et notifier les marchands
                alertService.operatorDegraded(op.getType());
                webhookService.broadcastOperatorStatus(op.getType(), "degraded");
            } else if (healthy && !previous.isHealthy()) {
                // Rétablissement → notifier
                alertService.operatorRestored(op.getType());
                webhookService.broadcastOperatorStatus(op.getType(), "operational");
            }

            saveHealth(redisKey, new OperatorHealth(healthy, Instant.now()));
        }
    }

    // Endpoint public de statut
    @GetMapping("/v1/operators/{operator}/status")
    public OperatorStatusResponse getStatus(@PathVariable String operator) {
        return new OperatorStatusResponse(
            operator,
            getHealth("operator:health:" + operator).isHealthy() ? "operational" : "degraded",
            getCircuitBreakerState(operator),
            getRecentIncidents(operator)
        );
    }
}
```

---

## 10. Observabilité

### 10.1 Logging structuré (JSON)

```java
// Chaque log doit être structuré et contenir le contexte de la requête
// Utiliser logstash-logback-encoder ou similaire

// logback-spring.xml (production)
// Sortie : JSON ligne par ligne pour ingestion ELK / CloudWatch

// Exemple de log structuré attendu :
{
  "timestamp": "2024-03-15T10:30:00.123Z",
  "level": "INFO",
  "logger": "com.ebithex.service.PaymentService",
  "message": "Payment initiated",
  "requestId": "req_01HX4K2M3N5P6Q7R8S9T",
  "merchantId": "550e8400-...",
  "transactionId": "660e8400-...",
  "ebithexReference": "AP-P-20240315-0000001234",
  "operator": "MTN_MOMO",
  "amount": 5000,
  "currency": "XOF",
  "durationMs": 342,
  "environment": "production"
}

// Ne JAMAIS logger :
// - Numéros de téléphone complets (masquer : +2250712XXXX)
// - Emails complets (masquer : j***@example.com)
// - Clés API, mots de passe, tokens
// - Données de carte bancaire
// - Credentials opérateurs
```

### 10.2 Métriques (Micrometer + Prometheus)

```java
// Métriques métier à exposer

@Component
public class PaymentMetrics {

    // Compteur de transactions par statut, opérateur, devise
    Counter.builder("ebithex.transactions.total")
        .tag("status", status.name())
        .tag("operator", operator.name())
        .tag("currency", currency)
        .tag("country", country)
        .register(registry);

    // Histogram des montants de transactions
    DistributionSummary.builder("ebithex.transactions.amount")
        .tag("operator", operator.name())
        .tag("currency", currency)
        .baseUnit("XOF")
        .register(registry);

    // Timer des appels opérateurs
    Timer.builder("ebithex.operator.call.duration")
        .tag("operator", operator.name())
        .tag("operation", "collection")
        .tag("success", String.valueOf(success))
        .register(registry);

    // Gauge de transactions PROCESSING en attente
    Gauge.builder("ebithex.transactions.pending", transactionRepo,
        repo -> repo.countByStatusIn(List.of(PENDING, PROCESSING)))
        .register(registry);

    // Compteur d'échecs de webhook
    Counter.builder("ebithex.webhooks.failures")
        .tag("endpoint_id", endpointId)
        .register(registry);

    // État des circuit breakers
    // → Automatiquement exposé par Resilience4j Micrometer integration
}
```

### 10.3 Tracing distribué (OpenTelemetry)

```yaml
# application.yml
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0   # 100% en staging, 10-20% en production
  otlp:
    tracing:
      endpoint: http://jaeger:4318/v1/traces

# Chaque requête reçoit un trace ID propagé :
# Via headers : traceparent: 00-TRACE_ID-SPAN_ID-01
# → Corrélé avec requestId dans les logs
```

### 10.4 Alertes obligatoires

```yaml
# Règles d'alerte critiques

alerts:
  - name: HighTransactionFailureRate
    condition: rate(ebithex_transactions_total{status="FAILED"}[5m]) > 0.1
    severity: critical
    message: "Taux d'échec > 10% sur 5 minutes"

  - name: OperatorCircuitOpen
    condition: resilience4j_circuitbreaker_state{state="open"} == 1
    severity: critical
    message: "Circuit breaker ouvert pour {{ $labels.name }}"

  - name: WebhookDeliveryBacklog
    condition: ebithex_webhook_deliveries_pending > 1000
    severity: warning
    message: "File d'attente webhooks > 1000 livraisons en attente"

  - name: LowOperatorBalance
    condition: ebithex_operator_balance < ebithex_operator_low_balance_threshold
    severity: warning
    message: "Solde bas pour l'opérateur {{ $labels.operator }}"

  - name: ReconciliationFailure
    condition: ebithex_reconciliation_success == 0
    severity: critical
    message: "Échec de la réconciliation pour {{ $labels.operator }}"

  - name: DatabaseConnectionPoolExhausted
    condition: hikaricp_connections_pending > 0
    severity: warning

  - name: HighApiLatency
    condition: histogram_quantile(0.95, ebithex_http_request_duration_seconds) > 2
    severity: warning
    message: "P95 latence API > 2 secondes"
```

### 10.5 Health checks personnalisés

```java
@Component
public class OperatorHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        Map<String, Object> details = new HashMap<>();
        boolean allHealthy = true;

        for (MobileMoneyOperator op : operators) {
            boolean healthy = operatorHealthService.isHealthy(op.getType());
            details.put(op.getType().name().toLowerCase(), healthy ? "up" : "down");
            if (!healthy) allHealthy = false;
        }

        return allHealthy
            ? Health.up().withDetails(details).build()
            : Health.degraded().withDetails(details).build();
    }
}

@Component
public class WebhookQueueHealthIndicator implements HealthIndicator {

    @Override
    public Health health() {
        long pending = webhookDeliveryRepo.countPendingDeliveries();

        if (pending > 10_000) {
            return Health.down()
                .withDetail("pendingDeliveries", pending)
                .withDetail("reason", "webhook queue critically backlogged")
                .build();
        }

        return Health.up().withDetail("pendingDeliveries", pending).build();
    }
}
```

---

## 11. Conformité et réglementation

### 11.1 Réglementation UEMOA / BCEAO

| Exigence | Implémentation requise |
|----------|----------------------|
| **Identification des payers** | Stocker le numéro MSISDN, vérifier via l'opérateur |
| **Limites de transaction** | Appliquer les plafonds BCEAO par portefeuille et par opérateur |
| **Conservation des données** | 10 ans minimum pour les données de transaction |
| **Rapport d'activité** | Rapports mensuels pour la banque centrale |
| **Lutte anti-blanchiment (LCB-FT)** | KYC obligatoire au-delà des seuils réglementaires |
| **Contrôle des changes** | Traçabilité des devises étrangères |

### 11.2 KYC — Know Your Customer

```java
// Niveaux de KYC par volume de transactions

public enum KycLevel {
    NONE(0, 0),           // Non vérifié : aucune transaction autorisée
    BASIC(500_000, 5_000_000),    // Vérification email+téléphone : 500K XOF/transaction, 5M/mois
    STANDARD(2_000_000, 20_000_000),  // CNI vérifiée : 2M/transaction, 20M/mois
    ENHANCED(null, null);  // Vérification complète : illimité

    // Processus KYC obligatoire :
    // 1. Collecte des documents (CNI, RCCM pour les entreprises)
    // 2. Stockage chiffré sur S3 avec accès restreint
    // 3. Vérification manuelle ou via prestataire tiers (Smile ID, Sumsub, Onfido)
    // 4. Enregistrement de la décision avec horodatage et identité de l'agent
    // 5. Audit trail de la décision KYC
}
```

### 11.3 Audit trail immuable

```java
// Toutes les actions sensibles doivent être auditées
// L'audit trail ne doit jamais être modifiable (append-only)

public enum AuditEventType {
    // Authentification
    MERCHANT_LOGIN, MERCHANT_LOGIN_FAILED, MERCHANT_LOGOUT,
    API_KEY_CREATED, API_KEY_ROTATED, API_KEY_REVOKED,

    // Transactions
    TRANSACTION_CREATED, TRANSACTION_STATUS_CHANGED,
    TRANSACTION_REFUNDED, TRANSACTION_EXPIRED,

    // Administration
    MERCHANT_SUSPENDED, MERCHANT_ACTIVATED,
    KYC_SUBMITTED, KYC_APPROVED, KYC_REJECTED,
    FEE_RULE_CHANGED, LIMIT_CHANGED,

    // Sécurité
    SUSPICIOUS_ACTIVITY_DETECTED, RATE_LIMIT_EXCEEDED,
    INVALID_WEBHOOK_SIGNATURE, UNAUTHORIZED_ACCESS_ATTEMPT
}

@Entity
@Immutable  // Hibernate : jamais de UPDATE sur cette table
@Table(name = "audit_logs")
public class AuditLog {
    @Id private UUID id;
    private AuditEventType eventType;
    private UUID actorId;          // Qui a fait l'action
    private String actorType;      // MERCHANT | ADMIN | SYSTEM
    private UUID resourceId;       // Sur quelle ressource
    private String resourceType;   // TRANSACTION | MERCHANT | WEBHOOK...
    private JSONB before;          // État avant (pour les modifications)
    private JSONB after;           // État après
    private String ipAddress;
    private String userAgent;
    private TIMESTAMPTZ createdAt; // Jamais de updated_at sur une table d'audit
}
```

### 11.4 Protection des données (RGPD-like)

```java
// Droit à l'effacement : ne pas supprimer, mais anonymiser
public void anonymizeMerchantData(UUID merchantId) {
    Merchant merchant = merchantRepo.findById(merchantId).orElseThrow();

    // Conserver les transactions pour la conformité financière (10 ans)
    // Mais anonymiser les données personnelles
    transactionRepo.anonymizeByMerchant(merchantId);
    // UPDATE transactions SET phone_number = 'ANONYMIZED', customer_name = NULL,
    //   customer_email = NULL WHERE merchant_id = ?

    // Désactiver le compte
    merchant.setActive(false);
    merchant.setEmail("deleted_" + UUID.randomUUID() + "@deleted.invalid");
    merchant.setDeletedAt(Instant.now());
    merchantRepo.save(merchant);
}
```

---

## 12. Tests

### 12.1 Pyramide de tests

```
           /\
          /  \
         / E2E \        5% — Tests end-to-end (Postman, REST Assured)
        /--------\
       /Integration\    25% — Tests d'intégration (Spring Boot Test, Testcontainers)
      /------------\
     /    Unit       \  70% — Tests unitaires (JUnit 5, Mockito)
    /________________\
```

### 12.2 Tests unitaires obligatoires

```java
// Tests de la machine d'états
@Test
void shouldRejectInvalidTransition() {
    Transaction tx = buildTransaction(TransactionStatus.SUCCESS);

    assertThrows(InvalidTransitionException.class, () ->
        stateMachine.transition(tx, TransactionStatus.PENDING));
}

// Tests du calcul de frais
@ParameterizedTest
@CsvSource({
    "10000, MTN_MOMO, 0.0080, 80.00, 9920.00",
    "10000, ORANGE_MONEY, 0.0090, 90.00, 9910.00",
    "10000, WAVE, 0.0070, 70.00, 9930.00",
    "100, MTN_MOMO, 0.0080, 0.80, 99.20",     // Arrondi HALF_UP
})
void shouldCalculateFeesCorrectly(BigDecimal amount, OperatorType op,
                                   BigDecimal rate, BigDecimal expectedFee,
                                   BigDecimal expectedNet) {
    FeeCalculation result = feeService.calculate(amount, "XOF", op, null);

    assertThat(result.getFeeAmount()).isEqualByComparingTo(expectedFee);
    assertThat(result.getNetAmount()).isEqualByComparingTo(expectedNet);
}

// Tests de détection d'opérateur
@ParameterizedTest
@CsvSource({
    "+22507123456, CI, MTN_MOMO",
    "+22504123456, CI, ORANGE_MONEY",
    "+221771234567, SN, ORANGE_MONEY",
    "+221781234567, SN, WAVE",
})
void shouldDetectOperatorFromPhoneNumber(String phone, String country,
                                          OperatorType expected) {
    assertThat(operatorDetection.detect(phone, country)).isEqualTo(expected);
}

// Tests d'idempotence
@Test
void shouldReturnExistingTransactionOnDuplicateReference() {
    // Arrange
    String merchantRef = "CMD-001";
    PaymentRequest req = buildRequest(merchantRef);
    Merchant merchant = buildMerchant();

    // Première requête
    Transaction first = paymentService.initiatePayment(req, merchant);

    // Deuxième requête avec la même reference
    assertThrows(DuplicateTransactionException.class, () ->
        paymentService.initiatePayment(req, merchant));
}

// Tests de la signature webhook
@Test
void shouldVerifyWebhookSignature() {
    String payload = "{\"event\":\"payment.success\"}";
    String secret = "whsec_test123";

    String signature = webhookSigner.sign(payload, secret);

    assertTrue(webhookSigner.verify(payload, signature, secret));
    assertFalse(webhookSigner.verify(payload + "tampered", signature, secret));
}

// Test de validation des URLs webhook (SSRF)
@ParameterizedTest
@ValueSource(strings = {
    "http://evil.com/hook",           // HTTP non autorisé
    "https://127.0.0.1/hook",         // Loopback
    "https://192.168.1.1/hook",       // IP privée
    "https://169.254.169.254/hook",   // AWS metadata
    "ftp://evil.com/hook",            // Protocol invalide
})
void shouldRejectUnsafeWebhookUrls(String url) {
    assertThrows(ValidationException.class, () ->
        webhookValidator.validateUrl(url));
}
```

### 12.3 Tests d'intégration avec Testcontainers

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class PaymentIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @Autowired TestRestTemplate restTemplate;

    @MockBean MtnMomoOperator mtnOperator;  // Mock l'opérateur externe

    @Test
    void fullPaymentLifecycle_CollectionToSuccess() {
        // 1. Enregistrement marchand
        MerchantRegistrationResponse reg = registerMerchant();

        // 2. Mock de l'opérateur MTN
        when(mtnOperator.initiateCollection(any()))
            .thenReturn(new OperatorInitResponse(
                "mtn-ref-123", PROCESSING, null, "*133#", "Confirmez"));
        when(mtnOperator.checkCollectionStatus("mtn-ref-123"))
            .thenReturn(TransactionStatus.SUCCESS);

        // 3. Initier le paiement
        PaymentResponse payment = initiatePayment(reg.getApiKey(), "+22507123456", 5000);

        assertThat(payment.getStatus()).isEqualTo("PROCESSING");
        assertThat(payment.getEbithexReference()).matches("AP-S-\\d{8}-\\d{10}");

        // 4. Vérifier le statut (trigger sync)
        TransactionStatusResponse status = getStatus(reg.getApiKey(),
            payment.getEbithexReference());

        assertThat(status.getStatus()).isEqualTo("SUCCESS");

        // 5. Vérifier la livraison webhook
        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<WebhookDelivery> deliveries = webhookDeliveryRepo.findAll();
            assertThat(deliveries).hasSize(1);
            assertThat(deliveries.get(0).isDelivered()).isTrue();
        });
    }

    @Test
    void shouldReturn409OnDuplicateTransaction() {
        String apiKey = registerMerchant().getApiKey();

        initiatePayment(apiKey, "+22507123456", 5000, "REF-001");

        ResponseEntity<ApiErrorResponse> response = initiatePayment(
            apiKey, "+22507123456", 5000, "REF-001", ResponseEntity.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getError().getCode())
            .isEqualTo("DUPLICATE_TRANSACTION");
    }
}
```

### 12.4 Tests de charge

```yaml
# k6 — test de charge pour les endpoints critiques
# Critères de performance requis :

targets:
  - endpoint: POST /v1/payments
    p50_latency: < 500ms
    p95_latency: < 2000ms
    p99_latency: < 5000ms
    error_rate: < 0.1%
    throughput: > 100 req/s par instance

  - endpoint: GET /v1/payments/{reference}
    p50_latency: < 100ms
    p95_latency: < 500ms
    error_rate: < 0.01%

# Scénarios de test de charge :
scenarios:
  smoke_test:
    vus: 5
    duration: 1m

  load_test:
    stages:
      - duration: 2m, target: 50   # Montée en charge
      - duration: 5m, target: 100  # Plateau
      - duration: 2m, target: 0    # Descente

  stress_test:
    stages:
      - duration: 2m, target: 200
      - duration: 5m, target: 500  # Au-delà des limites normales

  spike_test:
    stages:
      - duration: 10s, target: 1000  # Pic soudain
      - duration: 1m, target: 100    # Retour normal
```

---

## 13. DevOps et déploiement

### 13.1 Variables d'environnement — gestion des secrets

```bash
# JAMAIS dans le code ou dans git :
# - Mots de passe, API keys, secrets JWT
# - Clés de chiffrement
# - Credentials opérateurs

# Outils recommandés :
# - AWS Secrets Manager / Parameter Store
# - HashiCorp Vault
# - Kubernetes Secrets (avec External Secrets Operator)

# Variables obligatoires :
DB_HOST=                          # Hôte PostgreSQL
DB_PORT=5432
DB_NAME=ebithex
DB_USER=ebithex_app
DB_PASSWORD=                      # Injecter depuis Vault/Secrets Manager
DB_SSL_MODE=require               # TLS obligatoire

REDIS_HOST=
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_TLS=true

JWT_PRIVATE_KEY=                  # Clé privée RSA (PEM)
JWT_PUBLIC_KEY=                   # Clé publique RSA (PEM)

ENCRYPTION_KEY_ID=                # ID de la clé dans AWS KMS ou Vault

# Opérateurs
MTN_SUBSCRIPTION_KEY=
MTN_API_USER=
MTN_API_KEY=
MTN_ENVIRONMENT=sandbox           # sandbox | production
MTN_CALLBACK_URL=https://api.ebithex.io/v1/callbacks/mtn-momo

ORANGE_CLIENT_ID=
ORANGE_CLIENT_SECRET=
ORANGE_MERCHANT_KEY=
ORANGE_RETURN_URL=https://ebithex.io/payment/return
ORANGE_CANCEL_URL=https://ebithex.io/payment/cancel
ORANGE_NOTIFY_URL=https://api.ebithex.io/v1/callbacks/orange-money

WAVE_API_KEY=
WAVE_WEBHOOK_SECRET=

APP_URL=https://api.ebithex.io
APP_ENVIRONMENT=production        # sandbox | production
```

### 13.2 Dockerfile de production

```dockerfile
# ============================================================
# Stage 1 : Build
# ============================================================
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /workspace

# Copier les fichiers de dépendances en premier (layer cache)
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q

# Compiler
COPY src/ src/
RUN ./mvnw package -DskipTests -q

# Extraire les couches Spring Boot (optimisation)
RUN java -Djarmode=layertools -jar target/*.jar extract

# ============================================================
# Stage 2 : Runtime minimal
# ============================================================
FROM eclipse-temurin:17-jre-alpine

# Sécurité : utilisateur non-root
RUN addgroup -S ebithex && adduser -S ebithex -G ebithex
USER ebithex

WORKDIR /app

# Copier les layers dans l'ordre (du plus stable au plus volatile)
COPY --from=builder /workspace/dependencies/ ./
COPY --from=builder /workspace/spring-boot-loader/ ./
COPY --from=builder /workspace/snapshot-dependencies/ ./
COPY --from=builder /workspace/application/ ./

# Paramètres JVM pour conteneurs
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75 \
               -XX:+UseG1GC \
               -XX:+UseStringDeduplication \
               -Djava.security.egd=file:/dev/./urandom \
               -Dspring.profiles.active=production"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://localhost:8080/api/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### 13.3 Pipeline CI/CD

```yaml
# .github/workflows/ci.yml

name: CI/CD Pipeline

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env: { POSTGRES_DB: ebithex_test, POSTGRES_PASSWORD: test }
        options: --health-cmd pg_isready
      redis:
        image: redis:7-alpine
        options: --health-cmd "redis-cli ping"
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '17', distribution: 'temurin' }
      - name: Run tests
        run: ./mvnw test
      - name: Coverage check (minimum 80%)
        run: ./mvnw jacoco:check
      - name: OWASP Dependency Check
        run: ./mvnw dependency-check:check
      - name: SonarCloud analysis
        run: ./mvnw sonar:sonar

  build:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - name: Build Docker image
        run: docker build -t ghcr.io/ebithex/backend:${{ github.sha }} .
      - name: Trivy vulnerability scan
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: ghcr.io/ebithex/backend:${{ github.sha }}
          exit-code: '1'
          severity: 'CRITICAL,HIGH'
      - name: Push to registry
        run: docker push ghcr.io/ebithex/backend:${{ github.sha }}

  deploy-staging:
    needs: build
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/develop'
    steps:
      - name: Deploy to staging
        run: |
          # kubectl set image deployment/ebithex-backend ...
          # ou ArgoCD sync

  deploy-production:
    needs: build
    runs-on: ubuntu-latest
    if: github.ref == 'refs/heads/main'
    environment: production    # Approbation manuelle requise
    steps:
      - name: Blue/Green deployment
        run: |
          # Déployer sur Green, basculer le traffic, monitorer, rollback si nécessaire
```

---

## 14. Performance et scalabilité

### 14.1 Objectifs de performance

| Métrique | Cible | Critique |
|----------|-------|---------|
| Latence P50 `/v1/payments` POST | < 300ms | < 1s |
| Latence P95 `/v1/payments` POST | < 1s | < 3s |
| Latence P99 `/v1/payments` POST | < 2s | < 5s |
| Latence P50 GET statut | < 50ms | < 200ms |
| Disponibilité | 99.95% | 99.9% |
| Throughput par instance | > 50 TPS | > 20 TPS |
| Webhook delivery time | < 5s (P50) | < 30s |
| Réconciliation | < 2h pour J-1 | < 6h |

### 14.2 Optimisations base de données

```sql
-- Connection pooling : dimensionner Hikari correctement
-- Rule of thumb : (cores * 2) + effective_spindle_count
-- Pour 4 CPU : max pool size = 10

-- Requêtes critiques à optimiser avec EXPLAIN ANALYZE :

-- 1. Recherche de transaction par référence marchand (idempotence check)
-- Doit utiliser l'index composite (merchant_id, merchant_reference)
EXPLAIN ANALYZE
SELECT id, ebithex_reference FROM transactions
WHERE merchant_id = $1 AND merchant_reference = $2;

-- 2. Transactions PROCESSING à synchroniser (scheduler)
-- Doit utiliser l'index partiel idx_transactions_pending_reconcile
EXPLAIN ANALYZE
SELECT * FROM transactions
WHERE status IN ('PROCESSING', 'PENDING')
AND expires_at > NOW()
AND last_attempt_at < NOW() - INTERVAL '30 seconds'
LIMIT 100;

-- 3. Webhooks à retenter
-- Doit utiliser l'index composite (delivered, next_retry_at)
EXPLAIN ANALYZE
SELECT * FROM webhook_deliveries
WHERE delivered = false
AND attempt_count < 7
AND next_retry_at <= NOW()
ORDER BY next_retry_at ASC
LIMIT 50;

-- Ajouter pg_stat_statements pour identifier les requêtes lentes
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Archivage des transactions anciennes (> 1 an) dans une table partitionnée
-- Utiliser le partitionnement par plage de date sur created_at
```

### 14.3 Stratégie de cache

```java
// Données à mettre en cache dans Redis :

// 1. Profil marchand (TTL : 5 minutes, invalidé lors d'une modification)
@Cacheable(value = "merchants", key = "#apiKeyHash")
public Optional<Merchant> findByApiKeyHash(String apiKeyHash) { ... }

@CacheEvict(value = "merchants", key = "#merchant.apiKeyHash")
public Merchant updateMerchant(Merchant merchant) { ... }

// 2. Tokens opérateurs (TTL : durée de vie du token - 60s)
// Voir OperatorTokenCache

// 3. Configuration des taux de frais (TTL : 1 heure, invalidé lors d'un changement)
@Cacheable(value = "fee-rules", key = "#operator + '-' + #merchantId")
public FeeRule getFeeRule(OperatorType operator, UUID merchantId) { ... }

// 4. Statut des opérateurs (TTL : 30 secondes)
@Cacheable(value = "operator-health", key = "#operator")
public boolean isOperatorHealthy(String operator) { ... }

// 5. Rate limit counters (TTL : selon la fenêtre)
// Géré directement par Bucket4j dans Redis

// NE PAS cacher :
// - Statuts de transactions (données temps-réel)
// - Données d'audit
// - Tokens JWT (validation doit être en DB ou blacklist)
```

---

## 15. Gestion des erreurs

### 15.1 Catalogue d'erreurs standardisé

```java
// Codes d'erreur internes — exhaustifs et documentés

// Authentification (AUTH_xxx)
AUTH_001 → "Identifiants invalides"
AUTH_002 → "Compte suspendu"
AUTH_003 → "Token expiré"
AUTH_004 → "Token invalide"
AUTH_005 → "Clé API invalide"
AUTH_006 → "KYC non vérifié — action requise"
AUTH_007 → "Trop de tentatives de connexion — compte temporairement bloqué"

// Validation (VAL_xxx)
VAL_001 → "Champ requis manquant : {field}"
VAL_002 → "Format invalide : {field}"
VAL_003 → "Numéro de téléphone invalide"
VAL_004 → "Montant invalide (doit être positif)"
VAL_005 → "Devise non supportée"
VAL_006 → "Pays non supporté"

// Transaction (TXN_xxx)
TXN_001 → "Transaction non trouvée"
TXN_002 → "Référence marchand déjà utilisée"
TXN_003 → "Montant inférieur au minimum autorisé ({min} {currency})"
TXN_004 → "Montant supérieur au maximum autorisé ({max} {currency})"
TXN_005 → "Limite journalière atteinte"
TXN_006 → "Limite mensuelle atteinte"
TXN_007 → "Transaction expirée"
TXN_008 → "Transition de statut invalide"
TXN_009 → "Remboursement impossible pour ce statut"
TXN_010 → "Montant de remboursement supérieur au montant initial"

// Opérateur (OP_xxx)
OP_001 → "Solde insuffisant"
OP_002 → "Numéro non enregistré chez l'opérateur"
OP_003 → "Compte client bloqué"
OP_004 → "Transaction refusée par le client"
OP_005 → "Opérateur temporairement indisponible — réessayez dans quelques minutes"
OP_006 → "Opérateur non supporté pour ce pays"

// Webhook (WH_xxx)
WH_001 → "URL webhook invalide"
WH_002 → "Événement webhook non reconnu"
WH_003 → "Nombre maximum d'endpoints atteint"
```

### 15.2 Stratégie de propagation des erreurs

```java
// Règle fondamentale : ne jamais exposer les erreurs techniques aux marchands

@ExceptionHandler(OperatorException.class)
public ResponseEntity<ApiErrorResponse> handleOperatorException(OperatorException ex) {

    // Logger le détail technique (pour l'équipe support)
    log.error("Operator error [code={}] [operator={}] [raw={}]",
        ex.getNormalizedCode(), ex.getOperator(), ex.getRawOperatorMessage());

    // Retourner un message normalisé et non-technique au marchand
    return ResponseEntity
        .status(mapToHttpStatus(ex.getNormalizedCode()))
        .body(ApiErrorResponse.of(
            ex.getNormalizedCode().name(),
            ex.getNormalizedCode().getUserMessage()  // Message traduit et épuré
        ));
}

@ExceptionHandler(Exception.class)
public ResponseEntity<ApiErrorResponse> handleUnexpectedException(Exception ex,
    HttpServletRequest req) {

    String requestId = req.getHeader("X-Request-ID");

    // Logger avec contexte complet pour investigation
    log.error("Unexpected error [requestId={}] [path={}]",
        requestId, req.getRequestURI(), ex);

    // Alerter l'équipe (Sentry, PagerDuty)
    alertService.notifyUnexpectedError(ex, requestId);

    // Message générique au marchand
    return ResponseEntity.status(500)
        .body(ApiErrorResponse.of("INTERNAL_ERROR",
            "Une erreur interne s'est produite. Référence: " + requestId));
}
```

---

## 16. Checklist de mise en production

### 16.1 Sécurité

- [ ] Callbacks opérateurs vérifiés par HMAC ou token
- [ ] Whitelist des IPs opérateurs configurée
- [ ] Rate limiting activé sur tous les endpoints publics
- [ ] JWT configuré avec RS256 ou ES256 (pas HS256)
- [ ] Durée de vie access token ≤ 15 minutes
- [ ] API keys stockées par hash (SHA-256) uniquement
- [ ] Données PII chiffrées en base (AES-256-GCM)
- [ ] Headers de sécurité HTTP configurés
- [ ] URLs webhook validées contre SSRF
- [ ] HTTPS/TLS 1.3 minimum obligatoire
- [ ] Actuator endpoints sécurisés (pas publics)
- [ ] Scan de vulnérabilités Docker image (Trivy)
- [ ] OWASP Dependency Check dans le CI

### 16.2 Résilience

- [ ] Circuit breakers configurés par opérateur
- [ ] Retry avec backoff exponentiel et jitter
- [ ] Bulkheads (pools de threads séparés par opérateur)
- [ ] Timeouts configurés sur tous les appels HTTP externes
- [ ] Transactions DB avec verrouillage optimiste (@Version)
- [ ] Idempotence sur les endpoints POST (X-Idempotency-Key)
- [ ] Queue de webhooks avec retry asynchrone
- [ ] Scheduler d'expiration des transactions actif
- [ ] Health checks personnalisés configurés

### 16.3 Observabilité

- [ ] Logging structuré JSON activé
- [ ] Données sensibles masquées dans les logs
- [ ] Métriques Prometheus/Micrometer exposées
- [ ] Tracing distribué (OpenTelemetry) configuré
- [ ] Alertes critiques configurées (PagerDuty/OpsGenie)
- [ ] Dashboard Grafana opérationnel
- [ ] Audit trail immuable actif
- [ ] Rétention des logs configurée (minimum 90 jours)

### 16.4 Data & Conformité

- [ ] Flyway migrations testées (up + down)
- [ ] Backup automatique PostgreSQL (RTO < 4h, RPO < 1h)
- [ ] Réconciliation automatique quotidienne activée
- [ ] Limites de transaction configurées par opérateur
- [ ] KYC workflow opérationnel
- [ ] Politique de conservation des données documentée
- [ ] Rapport d'activité BCEAO paramétré

### 16.5 Performance

- [ ] Connection pooling dimensionné correctement (Hikari)
- [ ] Cache Redis opérationnel (tokens opérateurs, profils marchands)
- [ ] Tests de charge réussis (objectifs P95 atteints)
- [ ] Indexes DB vérifiés avec EXPLAIN ANALYZE
- [ ] Partitionnement des tables prévu (transactions > 10M lignes)

### 16.6 Opérationnel

- [ ] .env.example à jour avec toutes les variables
- [ ] Runbook de mise en production documenté
- [ ] Procédure de rollback définie et testée
- [ ] Page de statut publique configurée (statuspageio ou equivalent)
- [ ] Contacts support opérateurs documentés
- [ ] Procédure de gestion d'incident définie
- [ ] Tests de DR (Disaster Recovery) planifiés

---

*Document maintenu par l'équipe technique Ebithex. Toute modification doit faire l'objet d'une revue et d'une approbation avant mise en vigueur.*
