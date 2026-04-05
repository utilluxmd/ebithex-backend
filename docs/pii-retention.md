# Ebithex — Documentation Rétention et Purge des Données PII

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Données concernées](#2-données-concernées)
3. [Cycle de vie des données PII](#3-cycle-de-vie-des-données-pii)
4. [Mécanisme de pseudonymisation](#4-mécanisme-de-pseudonymisation)
5. [Job de purge planifié](#5-job-de-purge-planifié)
6. [Impact sur les requêtes et l'API](#6-impact-sur-les-requêtes-et-lapi)
7. [Schéma de base de données](#7-schéma-de-base-de-données)
8. [Référence de configuration](#8-référence-de-configuration)
9. [Conformité réglementaire](#9-conformité-réglementaire)
10. [Codes d'erreur et logs](#10-codes-derreur-et-logs)
11. [Runbook — Opérations manuelles](#11-runbook--opérations-manuelles)

---

## 1. Vue d'ensemble

Ebithex traite des données à caractère personnel (PII — *Personally Identifiable Information*) dans le cadre des paiements et décaissements Mobile Money. La donnée la plus sensible est le **numéro de téléphone** du payeur ou du bénéficiaire.

Conformément aux réglementations applicables (RGPD, CEDEAO Protection des Données Personnelles, lois nationales), ces données ne peuvent être conservées indéfiniment. Ebithex implémente une **purge automatisée par pseudonymisation** qui :

1. Préserve l'intégrité comptable et l'auditabilité (montants, statuts, références)
2. Rend le numéro de téléphone irrecoverable après la période de rétention
3. N'impacte pas les transactions récentes ni les workflows opérationnels actifs

**Durée de rétention par défaut : 5 ans** (configurable).

---

## 2. Données concernées

### 2.1 Tables impactées

| Table | Champ PII | Type de stockage | Champ index |
|-------|-----------|-----------------|-------------|
| `transactions` | `phone_number` | AES-256-GCM (chiffré) | `phone_number_index` (HMAC-SHA256) |
| `payouts` | `phone_number` | AES-256-GCM (chiffré) | `phone_number_index` (HMAC-SHA256) |

### 2.2 Données NON purgées

Les champs suivants sont **conservés indéfiniment** pour des raisons comptables et réglementaires (obligations de conservation des transactions financières) :

- Montants (`amount`, `fee_amount`, `net_amount`)
- Devises (`currency`)
- Statuts (`status`)
- Références (`ebithex_reference`, `merchant_reference`, `operator_reference`)
- Horodatages (`created_at`, `updated_at`)
- Identifiant marchand (`merchant_id`)
- Références de remboursement (`operator_refund_reference`)

### 2.3 Données client optionnelles

`customer_name`, `customer_email`, `description` et `metadata` sont **hors scope** de la purge automatisée — ils ne constituent pas des PII au sens de l'identification biométrique. Si ces champs doivent aussi être purgés (selon la législation locale), un paramètre `ebithex.pii.purge-customer-fields=true` peut être ajouté dans une version future.

---

## 3. Cycle de vie des données PII

```
Jour 0 — Transaction créée
  │
  │  phone_number = AES-GCM("+22507123456")
  │  phone_number_index = HMAC-SHA256("+22507123456")
  │  pii_purged_at = NULL
  │
  │  [Données PII accessibles — déchiffrement possible]
  │
  ▼
Ans 0-5 — Rétention active
  │
  │  Utilisé pour : support client, litiges, reporting marchand
  │
  ▼
Année 5 — Seuil de rétention atteint
  │
  │  Job de purge (03:00 UTC chaque nuit)
  │
  ▼
Post-purge — Pseudonymisation
  │
  │  phone_number = AES-GCM("PURGED")
  │  phone_number_index = NULL
  │  pii_purged_at = 2031-03-17T03:00:00
  │
  │  [Numéro irrecoverable — comptabilité intacte]
  │
  ▼
Conservation comptable indéfinie
```

---

## 4. Mécanisme de pseudonymisation

### 4.1 Remplacement du numéro

La pseudonymisation remplace les valeurs PII par un **placeholder standardisé** :

```
Avant purge:
  phone_number       = "dGhpcyBpcyBhIHRlc3Q..."  (AES-GCM de "+22507123456")
  phone_number_index = "a3f4b2c1..."               (HMAC-SHA256 de "+22507123456")

Après purge:
  phone_number       = "cHVyZ2Vk..."               (AES-GCM de "PURGED")
  phone_number_index = NULL
  pii_purged_at      = 2031-03-17T03:00:00.000
```

### 4.2 Pourquoi `phone_number_index = NULL` ?

`phone_number_index` est utilisé pour les **recherches filtrées par numéro** (`WHERE phone_number_index = HMAC("0707123456")`). Après purge, ces recherches doivent retourner 0 résultat — conserver le HMAC permettrait encore de confirmer qu'un numéro a effectué une transaction à une date donnée, ce qui constitue toujours une information PII indirecte.

### 4.3 Pourquoi chiffrer "PURGED" plutôt que laisser NULL ?

- Le champ `phone_number` est `NOT NULL` en base pour garantir l'intégrité des données transactionnelles
- Chiffrer le placeholder maintient la contrainte et permet à l'API de retourner une valeur cohérente (`"PURGED"`) plutôt qu'une NPE ou un JSON null inattendu

### 4.4 Irrecoverabilité

La clé de chiffrement AES-256 est identique avant et après purge. Cela signifie que le déchiffrement de `phone_number` retournera la chaîne `"PURGED"`. La valeur originale est définitivement perdue.

> Les transactions sandbox vivent dans le schéma `sandbox` (Modèle B). Le job PII s'exécute toujours sur le schéma `public` (prod) via `SandboxContextHolder.set(false)` — les données sandbox ne sont donc jamais atteintes structurellement.

---

## 5. Job de purge planifié

### 5.1 Paramètres du job

| Paramètre | Défaut | Description |
|-----------|--------|-------------|
| `ebithex.pii.cron` | `0 0 3 * * *` | Déclenchement quotidien à 03:00 UTC |
| `ebithex.pii.retention-years` | `5` | Ancienneté minimale pour être éligible à la purge |
| `ebithex.pii.batch-size` | `200` | Nombre de lignes traitées par transaction Spring |

### 5.2 Architecture transactionnelle

Le job est décomposé en deux classes pour garantir le bon fonctionnement des transactions Spring :

- **`PiiRetentionJob`** : orchestre le flux, gère `SandboxContextHolder`, délègue la purge.
- **`PiiRetentionService`** : contient les méthodes `@Transactional`. Séparé intentionnellement pour éviter le piège Spring AOP de la _self-invocation_ : un `this.method()` contourne le proxy Spring et ignore `@Transactional` silencieusement.

### 5.3 Algorithme

```
PiiRetentionJob.run()
  │
  ├─ cutoff = NOW() - retentionYears (ex: 2021-03-17 si on est en 2026)
  │
  ├─ SandboxContextHolder.set(false) → pool prod
  ├─ purgeTransactions(cutoff)  →  boucle do/while sur PiiRetentionService
  │    │
  │    └─ PiiRetentionService.purgeTransactionBatch(cutoff)  @Transactional
  │         ├─ findPurgeCandidates(cutoff, page=0)   ← toujours page 0 (voir §5.4)
  │         ├─ Pour chaque transaction :
  │         │    phone_number = encrypt("PURGED")
  │         │    phone_number_index = null
  │         │    pii_purged_at = now()
  │         ├─ saveAll(batch)
  │         └─ retourne batch.size()  (0 = terminé)
  │
  ├─ SandboxContextHolder.set(true) → pool sandbox
  └─ [même séquence sur sandbox.transactions]
```

### 5.4 Pagination sans offset (keyset implicite)

Au lieu d'une pagination offset classique (`LIMIT 200 OFFSET N*200` — coût O(N²) sur les grandes tables), le service demande **toujours la page 0** avec le filtre `pii_purged_at IS NULL`. Après chaque batch sauvegardé, les lignes traitées ont `pii_purged_at != NULL` et disparaissent de la requête suivante :

```
Itération 1 : SELECT ... WHERE created_at < cutoff AND pii_purged_at IS NULL LIMIT 200
              → 200 lignes, traitées, pii_purged_at défini
Itération 2 : même requête, page 0 → 200 nouvelles lignes (les précédentes filtrées)
Itération N : page 0 → 0 lignes → boucle terminée
```

Chaque `SELECT` scanne uniquement les lignes non encore purgées (index partiel sur `created_at WHERE pii_purged_at IS NULL`), quelle que soit la taille totale de la table.

### 5.5 Traitement par batch

Le service traite `batchSize` lignes par transaction Spring pour éviter :
- Les `OutOfMemoryError` sur les tables volumineuses
- Les transactions de base de données trop longues (lock escalation)
- Une consommation excessive de connexions JDBC

Chaque batch est indépendant — si le job est interrompu en cours d'exécution, il reprend au prochain déclenchement sans re-purger les lignes déjà traitées (grâce au filtre `pii_purged_at IS NULL`).

### 5.4 Logs de suivi

```
INFO  PiiRetentionJob - === Début du job de purge PII (rétention=5 ans) ===
INFO  PiiRetentionJob - PII purgé: 1247 transactions antérieures au 2021-03-17
INFO  PiiRetentionJob - PII purgé: 342 payouts antérieurs au 2021-03-17
INFO  PiiRetentionJob - === Fin du job de purge PII : 1247 transactions, 342 payouts purgés ===
```

Si aucune donnée n'est éligible :
```
INFO  PiiRetentionJob - === Début du job de purge PII (rétention=5 ans) ===
INFO  PiiRetentionJob - === Fin du job de purge PII : 0 transactions, 0 payouts purgés ===
```

---

## 6. Impact sur les requêtes et l'API

### 6.1 Comportement API après purge

Lorsqu'un marchand ou un agent back-office consulte une transaction purgée :

```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "ebithexReference": "AP-20210315-XYZ123",
  "status": "SUCCESS",
  "amount": 5000.00,
  "currency": "XOF",
  "phoneNumber": "PURGED",
  "operator": "MTN_MOMO_CI",
  "createdAt": "2021-03-15T14:22:00"
}
```

> `"phoneNumber": "PURGED"` est la valeur retournée par `EncryptionService.decrypt()` sur la donnée post-purge — c'est le comportement attendu.

### 6.2 Recherche par numéro de téléphone

Les requêtes de recherche par numéro (`/api/v1/payments?phone=+22507123456`) utilisent `phone_number_index`. Après purge, cet index est `NULL` — la transaction n'apparaîtra plus dans les résultats de recherche par numéro.

```
Avant purge :
  findByMerchantIdAndPhoneNumberIndex(merchantId, hmac("+22507123456"))
  → [Transaction AP-20210315-XYZ123]

Après purge :
  findByMerchantIdAndPhoneNumberIndex(merchantId, hmac("+22507123456"))
  → []  (index NULL, pas de match)
```

### 6.3 Export CSV de réconciliation

Les exports CSV (`GET /internal/reconciliation/export/transactions`) incluront `"PURGED"` comme valeur du numéro de téléphone pour les transactions purgées. C'est intentionnel et documenté dans l'en-tête CSV.

### 6.4 Rapports AML

Les contrôles de vélocité AML (`AmlScreeningService`) utilisent `phone_number_index`. Pour les transactions purgées, ces contrôles ne peuvent plus identifier les patterns historiques liés à ce numéro. C'est acceptable car :
- Les transactions purgées ont plus de 5 ans
- Les patterns AML pertinents concernent les 24h/7j/30j glissants
- Les contrôles de sanctions s'appliquent au moment de la transaction, pas rétrospectivement

---

## 7. Schéma de base de données

### Colonnes ajoutées (migration V13)

```sql
-- Sur transactions
ALTER TABLE transactions ADD COLUMN pii_purged_at TIMESTAMP;

-- Sur payouts
ALTER TABLE payouts ADD COLUMN pii_purged_at TIMESTAMP;

-- Index partial pour que le job scanne uniquement les candidates non purgées
CREATE INDEX idx_tx_pii_purge_candidates     ON transactions(created_at) WHERE pii_purged_at IS NULL;
CREATE INDEX idx_payout_pii_purge_candidates ON payouts(created_at)      WHERE pii_purged_at IS NULL;
```

### Requête de purge exécutée par le job

```sql
-- Équivalent JPQL (exécuté par batch de 200 — schéma public via search_path)
SELECT t FROM Transaction t
WHERE t.createdAt < :cutoff
  AND t.piiPurgedAt IS NULL
ORDER BY t.createdAt ASC   -- implicite via pagination
LIMIT 200
-- Note : le pool prod (search_path TO public) garantit que seul public.transactions est lu.
--        sandbox.transactions est hors de portée structurellement.
```

### Audit des purges

L'horodatage `pii_purged_at` permet un audit complet :

```sql
-- Volume de données purgées par mois
SELECT DATE_TRUNC('month', pii_purged_at) AS month,
       COUNT(*)                           AS purged_count
FROM transactions
WHERE pii_purged_at IS NOT NULL
GROUP BY 1
ORDER BY 1;

-- Transactions purgées les plus récentes (vérification)
SELECT ebithex_reference, created_at, pii_purged_at
FROM transactions
WHERE pii_purged_at IS NOT NULL
ORDER BY pii_purged_at DESC
LIMIT 20;

-- Transactions encore actives éligibles dans les 30 prochains jours (schéma prod)
SELECT COUNT(*) AS eligible_soon
FROM public.transactions
WHERE pii_purged_at IS NULL
  AND created_at < NOW() - INTERVAL '5 years' + INTERVAL '30 days';
```

---

## 8. Référence de configuration

```properties
# -- PII data retention ----------------------------------------
# Durée de rétention des données PII en années (défaut : 5)
# Modifier selon les exigences réglementaires du pays :
#   - RGPD (UE) : pas de durée fixe, "nécessité" → typiquement 5-10 ans pour transactions
#   - UEMOA (Côte d'Ivoire, Sénégal...) : 5 ans (réglementation e-monnaie)
#   - Kenya (CBK) : 7 ans minimum
#   - Nigeria (CBN) : 7 ans minimum
ebithex.pii.retention-years=5

# Nombre de transactions traitées par batch (défaut : 200)
# Augmenter si le job est trop lent, diminuer si problèmes mémoire
ebithex.pii.batch-size=200

# Cron de déclenchement (défaut : 03:00 UTC chaque jour)
# Choisir une heure creuse — décaler si conflit avec settlement (01:00) ou réconciliation (02:30)
ebithex.pii.cron=0 0 3 * * *
```

### Configuration par environnement

```properties
# application-prod.properties
# Période de rétention plus longue pour le Kenya et le Nigeria
# (à adapter si déploiement multi-pays avec configurations distinctes)
ebithex.pii.retention-years=7

# application-test.properties
# Durée très courte pour les tests (purge des données de test vieilles d'1 an)
ebithex.pii.retention-years=1
```

---

## 9. Conformité réglementaire

### 9.1 Cadre légal applicable

| Zone | Réglementation | Durée minimale | Durée maximale | Recommandation Ebithex |
|------|---------------|---------------|---------------|----------------------|
| UEMOA (CI, SN, BJ...) | BCEAO e-monnaie 2015 | 5 ans | Non définie | 5 ans |
| CEMAC (CM) | BEAC e-monnaie | 5 ans | Non définie | 5 ans |
| Kenya | CBK PSP Regulations 2014 | 7 ans | Non définie | 7 ans |
| Nigeria | CBN PSB Framework | 7 ans | Non définie | 7 ans |
| Ghana | BoG e-money guidelines | 6 ans | Non définie | 6 ans |

> Les durées ci-dessus concernent les **enregistrements de transactions**. Les données PII strictes (numéro de téléphone) peuvent avoir des durées différentes. Consulter le DPO avant toute modification.

### 9.2 Droits des personnes (RGPD / lois nationales)

**Droit à l'effacement (droit à l'oubli) :**
La suppression anticipée des PII d'un client spécifique n'est pas implémentée automatiquement. Elle requiert une intervention manuelle (voir runbook §11.3) et ne peut s'appliquer qu'aux transactions hors période de conservation légale obligatoire.

**Droit d'accès :**
Tant que les données ne sont pas purgées, l'API supporte la recherche par numéro de téléphone via l'index HMAC. Après purge, la confirmation de l'existence d'une transaction liée à un numéro n'est plus possible.

**Minimisation des données :**
Ebithex ne stocke jamais le numéro en clair. Le chiffrement AES-256-GCM et l'index HMAC garantissent que même un accès direct à la base de données ne révèle pas les numéros.

### 9.3 Données transmises aux opérateurs

Le numéro de téléphone est transmis en clair aux opérateurs Mobile Money lors de l'initiation des paiements et décaissements. Ces transmissions sont :
- Chiffrées en transit (TLS 1.2+)
- Régies par les accords de traitement de données avec chaque opérateur
- Hors du scope de la purge Ebithex (la copie chez l'opérateur n'est pas contrôlable)

---

## 10. Codes d'erreur et logs

### Erreurs potentielles du job

| Situation | Log | Impact |
|-----------|-----|--------|
| Erreur de chiffrement sur une ligne | `ERROR PiiRetentionJob - Erreur de purge PII transaction {id}: ...` | La ligne est ignorée, le batch continue |
| Exception globale non anticipée | `ERROR PiiRetentionJob - Erreur critique...` | Le job s'arrête ; les lignes déjà purgées le restent |
| Aucune donnée éligible | Log normal "0 transactions, 0 payouts purgés" | Normal |

### Codes d'erreur API (recherche post-purge)

Il n'y a pas d'erreur API pour les données purgées — l'API retourne simplement `"phoneNumber": "PURGED"`. Aucun code d'erreur spécifique n'est émis.

---

## 11. Runbook — Opérations manuelles

### 11.1 Vérifier le bon fonctionnement du job

```bash
# Logs du job des dernières 24h
grep "PiiRetentionJob" application.log | grep "$(date '+%Y-%m-%d')"

# Volume purgé en base
psql -c "
SELECT
    'transactions' AS table,
    COUNT(*) FILTER (WHERE pii_purged_at IS NOT NULL) AS purged,
    COUNT(*) FILTER (WHERE pii_purged_at IS NULL
                        AND created_at < NOW() - INTERVAL '5 years') AS pending_purge,
    COUNT(*) FILTER (WHERE pii_purged_at IS NULL) AS active
FROM transactions
UNION ALL
SELECT
    'payouts',
    COUNT(*) FILTER (WHERE pii_purged_at IS NOT NULL),
    COUNT(*) FILTER (WHERE pii_purged_at IS NULL
                        AND created_at < NOW() - INTERVAL '5 years'),
    COUNT(*) FILTER (WHERE pii_purged_at IS NULL)
FROM payouts;
"
```

### 11.2 Déclencher une purge manuelle (hors schedule)

```bash
# Via Spring Actuator (si activé en local/dev)
curl -X POST http://localhost:8080/api/actuator/scheduledtasks

# Ou forcer directement via psql (ATTENTION : irréversible)
# Utiliser uniquement en cas d'urgence réglementaire

UPDATE public.transactions
SET
    phone_number = '<AES-GCM de "PURGED">',
    phone_number_index = NULL,
    pii_purged_at = NOW()
WHERE
    created_at < NOW() - INTERVAL '5 years'
    AND pii_purged_at IS NULL;
```

> ⚠️ La valeur AES-GCM de "PURGED" doit être générée par Ebithex (via `EncryptionService.encrypt("PURGED")`). Ne pas insérer une valeur aléatoire — cela corromprait le déchiffrement API.

### 11.3 Purge anticipée sur demande (droit à l'effacement)

```sql
-- Identifier les transactions liées à un numéro
-- (Obtenir le HMAC via l'API interne ou EncryptionService.hmacForIndex())
SELECT id, ebithex_reference, created_at, pii_purged_at
FROM transactions
WHERE phone_number_index = '<HMAC du numéro demandeur>'
  AND created_at < (NOW() - INTERVAL '<retention légale obligatoire>');
-- Ne purger QUE les transactions hors période légale obligatoire

-- Purger (remplacer par la valeur chiffrée appropriée)
UPDATE transactions
SET phone_number = '<encrypt("PURGED")>', phone_number_index = NULL, pii_purged_at = NOW()
WHERE id IN (<liste des IDs identifiés>);
```

### 11.4 Rollback impossible

> **La purge est irréversible.** Il n'existe pas de procédure de rollback.
>
> Avant toute exécution manuelle ou modification du `retention-years`, faire valider par le responsable technique et le DPO.

### 11.5 Alerte si le job ne s'exécute pas

Si aucune purge n'est enregistrée pendant plus de 7 jours alors que des données éligibles existent :

```bash
# Requête de détection d'anomalie (à intégrer dans le monitoring)
SELECT COUNT(*) AS overdue
FROM public.transactions
WHERE pii_purged_at IS NULL
  AND created_at < NOW() - INTERVAL '5 years' - INTERVAL '7 days';
-- Si COUNT > 0 → alerter l'équipe engineering
```