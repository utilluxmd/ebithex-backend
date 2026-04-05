# Ebithex — Documentation KYC (Know Your Customer)

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Architecture](#2-architecture)
3. [Cycle de vie du dossier KYC](#3-cycle-de-vie-du-dossier-kyc)
4. [Types de documents et règles de validation](#4-types-de-documents-et-règles-de-validation)
5. [Stockage](#5-stockage)
6. [Abstraction des prestataires KYC](#6-abstraction-des-prestataires-kyc)
7. [Référence API REST](#7-référence-api-rest)
8. [Événements et notifications](#8-événements-et-notifications)
9. [Schéma de base de données](#9-schéma-de-base-de-données)
10. [Référence de configuration](#10-référence-de-configuration)
11. [Mise en place par environnement](#11-mise-en-place-par-environnement)
12. [Modèle de sécurité](#12-modèle-de-sécurité)
13. [Codes d'erreur](#13-codes-derreur)
14. [Ajouter un nouveau prestataire KYC](#14-ajouter-un-nouveau-prestataire-kyc)

---

## 1. Vue d'ensemble

Ebithex exige de tous les marchands qu'ils complètent une vérification **KYC (Know Your Customer)** avant d'accéder aux services avancés tels que les retraits, les décaissements et les paiements à volume élevé. Le processus KYC garantit la conformité aux réglementations LCB-FT (lutte contre le blanchiment et le financement du terrorisme) sur l'ensemble des marchés africains supportés.

Le système prend en charge :
- Le téléversement multi-documents avec validation MIME côté serveur
- La vérification automatisée via des prestataires KYC tiers (Smile Identity, Sumsub)
- Un mode de révision manuelle en back-office en cas d'indisponibilité des prestataires
- La couverture panafricaine : ~54 pays via un routage multi-prestataires
- Le stockage d'objets compatible S3 (AWS S3, Cloudflare R2, MinIO)
- La publication d'événements via le pattern Outbox pour les notifications et webhooks

---

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        module-merchant                          │
│                                                                 │
│  ┌──────────────┐   ┌───────────────────┐   ┌───────────────┐  │
│  │KycController │   │KycBackOfficeCtrl  │   │MerchantCtrl   │  │
│  │(API Marchand)│   │(API Admin)        │   │POST /kyc      │  │
│  └──────┬───────┘   └────────┬──────────┘   └──────┬────────┘  │
│         │                    │                      │           │
│         └────────────────────┼──────────────────────┘           │
│                              ▼                                  │
│                  ┌───────────────────────┐                      │
│                  │   KycDocumentService  │                      │
│                  │  - upload()           │                      │
│                  │  - list()             │                      │
│                  │  - presignedUrl()     │                      │
│                  │  - softDelete()       │                      │
│                  │  - review()           │                      │
│                  │  - isDossierComplete()│                      │
│                  └──┬─────────┬──────────┘                      │
│                     │         │                                 │
│           ┌─────────┘         └──────────────┐                 │
│           ▼                                  ▼                  │
│  ┌─────────────────┐              ┌──────────────────────┐     │
│  │  StorageService │              │  KycProviderRegistry │     │
│  │  (interface)    │              │  forCountry(iso)     │     │
│  └────────┬────────┘              └──────────┬───────────┘     │
│           │                                  │                  │
│    ┌──────┴──────┐              ┌────────────┼─────────────┐   │
│    │S3Storage    │              │            │             │   │
│    │LocalStorage │              │SmileIdent. │Sumsub       │   │
│    └─────────────┘              │(Afr. sub-  │(Afr. Nord + │   │
│                                 │saharienne) │mondial)     │   │
│                                 └────────────┴─────────────┘   │
└─────────────────────────────────────────────────────────────────┘

┌──────────────────────────────┐
│       module-shared          │
│  KycStatusChangedEvent       │  ──► OutboxWriter ──► Outbox DB
│  (APPROVED / REJECTED)       │
└──────────────────────────────┘
         │
         ▼
┌──────────────────────────────┐
│    module-notification       │
│  NotificationService         │  ──► email au marchand
└──────────────────────────────┘
```

---

## 3. Cycle de vie du dossier KYC

### 3.1 Cycle complet

```
                    ┌─────────┐
    Inscription     │  NONE   │  (état par défaut à la création du compte)
                    └────┬────┘
                         │ Le marchand téléverse les documents requis
                         │ Tous les documents requis atteignent le statut ACCEPTED
                         │
                         ▼
             POST /v1/merchants/kyc ──► vérification isDossierComplete()
                         │
                         ▼
                    ┌─────────┐
                    │ PENDING │  (dossier soumis, en attente de révision)
                    └────┬────┘
                         │
            ┌────────────┴─────────────┐
            ▼                         ▼
       ┌──────────┐              ┌──────────┐
       │ APPROVED │              │ REJECTED │
       └──────────┘              └──────────┘
       kycVerified=true          kycVerified=false
       Rôle élevé à              Raison stockée dans
       MERCHANT_KYC_VERIFIED     kycRejectionReason
```

### 3.2 Cycle de vie d'un document

```
    Téléversement (POST /v1/merchants/kyc/documents)
            │
            ▼
        UPLOADED ──► (soumission auto si ebithex.kyc.auto-submit-to-provider=true)
            │
            ├── Prestataire disponible ──► PROCESSING
            │                                  │
            │                      ┌───────────┴──────────┐
            │                      ▼                      ▼
            │                  ACCEPTED               REJECTED
            │
            └── Pas de prestataire / erreur ──► UPLOADED (révision manuelle)
                                                      │
                                            Révision back-office PUT /review
                                                      │
                                            ┌─────────┴──────────┐
                                            ▼                    ▼
                                        ACCEPTED             REJECTED
```

### 3.3 Contrôle de complétude du dossier

`POST /v1/merchants/kyc` (soumission du dossier) vérifie que **les trois types de documents obligatoires** disposent chacun d'au moins un document au statut `ACCEPTED` :

| Type requis             | Objet                                             |
|-------------------------|---------------------------------------------------|
| `NATIONAL_ID`           | Identité du représentant légal                    |
| `BUSINESS_REGISTRATION` | Preuve d'existence juridique de l'entreprise      |
| `PROOF_OF_ADDRESS`      | Adresse physique de l'établissement               |

Si l'un des types requis est absent ou ne possède aucun document accepté, l'API retourne `400 KYC_INCOMPLETE_DOSSIER`.

---

## 4. Types de documents et règles de validation

### 4.1 Types de documents acceptés

| Valeur enum             | Description                                                  | Obligatoire |
|-------------------------|--------------------------------------------------------------|-------------|
| `NATIONAL_ID`           | Carte nationale d'identité émise par l'État                  | ✅           |
| `PASSPORT`              | Passeport international                                      |             |
| `DRIVERS_LICENSE`       | Permis de conduire                                           |             |
| `BUSINESS_REGISTRATION` | Certificat d'immatriculation / extrait RCCM                  | ✅           |
| `TAX_CERTIFICATE`       | Certificat d'identifiant fiscal                              |             |
| `BANK_STATEMENT`        | Relevé bancaire des 3 derniers mois                          |             |
| `PROOF_OF_ADDRESS`      | Facture de service public ou bail de moins de 3 mois         | ✅           |
| `UBO_DECLARATION`       | Déclaration des bénéficiaires effectifs (Ultimate Beneficial Owner) |      |
| `OTHER`                 | Tout autre document justificatif                             |             |

### 4.2 Règles de validation des fichiers

| Règle                         | Valeur                  | Code d'erreur         |
|-------------------------------|-------------------------|-----------------------|
| Taille maximale               | **10 Mo**               | `FILE_TOO_LARGE`      |
| Types MIME autorisés          | `application/pdf`, `image/jpeg`, `image/png`, `image/webp` | `INVALID_MIME_TYPE` |
| Méthode de détection MIME     | **Apache Tika** (magic bytes — ignore le header `Content-Type` du client) | |
| Détection des doublons        | Hash SHA-256 par marchand | `DUPLICATE_DOCUMENT` |

> **Note de sécurité** : le type MIME est toujours détecté à partir du contenu binaire du fichier (magic bytes), jamais depuis le `Content-Type` déclaré par le client. Cela prévient les attaques par usurpation de type MIME.

### 4.3 Nommage des fichiers

Le nom de fichier original est assaini avant stockage (tous les caractères autres que `a-z A-Z 0-9 . _ -` sont remplacés par `_`). La clé de stockage est un chemin interne opaque qui n'est jamais retourné aux clients.

---

## 5. Stockage

### 5.1 Abstraction du stockage

L'interface `StorageService` découple l'application de tout backend de stockage d'objets particulier :

```java
public interface StorageService {
    void store(String key, InputStream data, String contentType, long sizeBytes);
    String presignedUrl(String key, Duration ttl);
    void delete(String key);
}
```

Deux implémentations sont fournies :

| Implémentation       | Profil         | Description                                    |
|----------------------|----------------|------------------------------------------------|
| `S3StorageService`   | dev / prod     | AWS S3, Cloudflare R2, MinIO                   |
| `LocalStorageService`| local / test   | Système de fichiers — développement uniquement |

### 5.2 Format de la clé de stockage

```
kyc/{merchantId}/{type_document}/{uuid}.bin
```

Exemple :
```
kyc/550e8400-e29b-41d4-a716-446655440000/national_id/3f7a1b2c-...-.bin
```

L'extension `.bin` est intentionnelle : le type MIME réel est stocké en base de données, empêchant tout rendu direct par un navigateur via un CDN mal configuré.

### 5.3 URLs pré-signées

Les documents ne sont **jamais servis directement** par l'API. Les clients reçoivent une URL pré-signée S3 :

- Durée de validité : **15 minutes** (valeur fixe)
- Portée : lecture seule (`GET`)
- Aucun header d'authentification requis par le porteur

URL marchand : `GET /v1/merchants/kyc/documents/{id}/url`
URL admin : `GET /internal/merchants/kyc/documents/{id}/url`

### 5.4 Configuration S3-compatible (exemple Cloudflare R2)

```properties
# Endpoint Cloudflare R2
ebithex.kyc.storage.provider=s3
ebithex.kyc.storage.s3.bucket=ebithex-kyc-prod
ebithex.kyc.storage.s3.region=auto
ebithex.kyc.storage.s3.endpoint=https://<account_id>.r2.cloudflarestorage.com
ebithex.kyc.storage.s3.access-key=${KYC_S3_ACCESS_KEY}
ebithex.kyc.storage.s3.secret-key=${KYC_S3_SECRET_KEY}
```

Le bucket doit être configuré avec :
- **Accès privé** (pas d'ACL public)
- **Chiffrement au niveau objet** (AES-256 côté serveur, défini automatiquement par `S3StorageService`)
- **Versioning désactivé** (les documents sont supprimés de façon logique, pas écrasés)
- **Règle de cycle de vie** : suppression définitive des objets dont `deleted_at IS NOT NULL` et âgés de plus de 90 jours

---

## 6. Abstraction des prestataires KYC

### 6.1 Interface

```java
public interface KycProvider {
    Set<String> supportedCountries();       // codes ISO 3166-1 alpha-2
    String submitVerification(KycDocument, String countryCode);
    VerificationResult checkStatus(String providerRef);
    String providerName();
}
```

### 6.2 Routage des prestataires

`KycProviderRegistry.forCountry(isoCode)` résout les prestataires dans l'ordre suivant :

1. Premier prestataire dont `supportedCountries()` contient le code pays du marchand
2. Prestataire nommé `"sumsub"` — repli mondial
3. Prestataire nommé `"mock"` — dernier recours (sûr en local/test)

### 6.3 Carte de couverture des prestataires

| Prestataire          | Région                      | Pays couverts (liste partielle)                       | Statut   |
|----------------------|-----------------------------|-------------------------------------------------------|----------|
| **Smile Identity**   | Afrique subsaharienne       | NG, GH, KE, SN, CI, CM, TZ, UG, ZA, ET, BJ, BF, ML, NE, TG… (~35) | Stub |
| **Sumsub**           | Afrique du Nord + mondial   | MA, DZ, TN, LY, EG, SD, MG, MU, NA, BW…              | Stub     |
| **MockKycProvider**  | Local / Test                | (aucun — repli uniquement)                            | Actif    |

> Les prestataires Smile Identity et Sumsub sont des **stubs** : l'interface et le routage sont câblés, mais les appels HTTP réels ne sont pas encore implémentés. Les TODO sont annotés dans chaque classe. Le mock est utilisé dans tous les environnements hors production.

### 6.4 Activer un prestataire

```properties
# Activer Smile Identity pour l'Afrique subsaharienne
ebithex.kyc.provider.smile.enabled=true
ebithex.kyc.provider.smile.partner-id=${KYC_SMILE_PARTNER_ID}
ebithex.kyc.provider.smile.api-key=${KYC_SMILE_API_KEY}
ebithex.kyc.provider.smile.base-url=https://testapi.smileidentity.com/v1

# Activer Sumsub comme repli mondial
ebithex.kyc.provider.sumsub.enabled=true
ebithex.kyc.provider.sumsub.app-token=${KYC_SUMSUB_APP_TOKEN}
ebithex.kyc.provider.sumsub.secret-key=${KYC_SUMSUB_SECRET_KEY}
```

Les deux peuvent être activés simultanément — le registre gère le routage automatiquement.

### 6.5 Soumission automatique

Lorsque `ebithex.kyc.auto-submit-to-provider=true`, chaque document téléversé est immédiatement soumis au prestataire correspondant au pays du marchand. En cas d'échec de l'appel prestataire, le document repasse au statut `UPLOADED` pour une révision manuelle.

---

## 7. Référence API REST

### Authentification

Tous les endpoints marchands nécessitent l'un des headers suivants :
- `X-API-Key: ap_live_<clé>`
- `Authorization: Bearer <jwt>`

Tous les endpoints back-office nécessitent un JWT avec un rôle back-office.

---

### 7.1 Marchand — Téléversement d'un document

```http
POST /v1/merchants/kyc/documents
Content-Type: multipart/form-data
X-API-Key: ap_live_...

type=NATIONAL_ID
file=<binaire>
```

**Paramètres**

| Champ  | Type             | Obligatoire | Description                                     |
|--------|------------------|-------------|-------------------------------------------------|
| `type` | `KycDocumentType`| ✅           | Type de document (voir §4.1)                   |
| `file` | fichier          | ✅           | Fichier binaire (max 10 Mo, PDF/JPEG/PNG/WEBP) |

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "documentType": "NATIONAL_ID",
    "status": "UPLOADED",
    "fileName": "carte_identite.pdf",
    "contentType": "application/pdf",
    "fileSizeBytes": 204800,
    "providerName": null,
    "reviewerNotes": null,
    "uploadedAt": "2026-03-17T10:00:00Z",
    "expiresAt": null
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur        | Condition                                     |
|--------|----------------------|-----------------------------------------------|
| 400    | `EMPTY_FILE`         | Fichier nul ou vide                           |
| 400    | `FILE_TOO_LARGE`     | Fichier dépasse 10 Mo                         |
| 400    | `INVALID_MIME_TYPE`  | Format non autorisé (ni PDF, ni JPEG/PNG/WEBP)|
| 409    | `DUPLICATE_DOCUMENT` | Fichier identique déjà téléversé              |

---

### 7.2 Marchand — Lister les documents

```http
GET /v1/merchants/kyc/documents
X-API-Key: ap_live_...
```

Retourne tous les documents actifs (non supprimés) du marchand authentifié, triés par date de téléversement décroissante.

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": [
    {
      "id": "...",
      "documentType": "NATIONAL_ID",
      "status": "ACCEPTED",
      ...
    },
    {
      "id": "...",
      "documentType": "PROOF_OF_ADDRESS",
      "status": "UPLOADED",
      ...
    }
  ]
}
```

---

### 7.3 Marchand — Obtenir une URL de téléchargement pré-signée

```http
GET /v1/merchants/kyc/documents/{documentId}/url
X-API-Key: ap_live_...
```

Retourne une URL pré-signée valable **15 minutes**.

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "url": "https://ebithex-kyc.r2.cloudflarestorage.com/kyc/550e.../national_id/...?X-Amz-Signature=..."
  }
}
```

**Réponses d'erreur**

| Statut | Code d'erreur        | Condition                                              |
|--------|----------------------|--------------------------------------------------------|
| 404    | `DOCUMENT_NOT_FOUND` | Document inexistant ou appartenant à un autre marchand |

---

### 7.4 Marchand — Supprimer un document

```http
DELETE /v1/merchants/kyc/documents/{documentId}
X-API-Key: ap_live_...
```

Supprime logiquement un document. L'objet en stockage n'est **pas** immédiatement effacé — la suppression physique est gérée par une règle de cycle de vie.

**Contrainte** : impossible de supprimer un document au statut `ACCEPTED`.

**Réponse `200 OK`**

```json
{ "success": true, "data": null }
```

**Réponses d'erreur**

| Statut | Code d'erreur        | Condition                                         |
|--------|----------------------|---------------------------------------------------|
| 400    | `DOCUMENT_LOCKED`    | Le document est ACCEPTED — suppression impossible |
| 404    | `DOCUMENT_NOT_FOUND` | Document introuvable pour ce marchand             |

---

### 7.5 Marchand — Soumettre le dossier KYC

```http
POST /v1/merchants/kyc
X-API-Key: ap_live_...
```

Fait passer le statut KYC du marchand de `NONE` (ou `REJECTED`) à `PENDING`. Déclenche la révision back-office.

**Pré-condition** : les trois types de documents obligatoires (`NATIONAL_ID`, `BUSINESS_REGISTRATION`, `PROOF_OF_ADDRESS`) doivent chacun avoir au moins un document au statut `ACCEPTED`.

**Réponses d'erreur**

| Statut | Code d'erreur              | Condition                                          |
|--------|----------------------------|----------------------------------------------------|
| 400    | `KYC_ALREADY_APPROVED`     | Le KYC est déjà approuvé                           |
| 400    | `KYC_INCOMPLETE_DOSSIER`   | Documents requis manquants ou pas encore acceptés  |

---

### 7.6 Back-office — Lister les documents d'un marchand

```http
GET /internal/merchants/{merchantId}/kyc/documents
Authorization: Bearer <jwt-admin>
```

**Rôles requis** : `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`, `SUPPORT`

Retourne tous les documents KYC actifs du marchand spécifié.

---

### 7.7 Back-office — Lister les documents en attente de révision

```http
GET /internal/merchants/kyc/documents/pending
Authorization: Bearer <jwt-admin>
```

**Rôles requis** : `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`, `SUPPORT`

Retourne tous les documents au statut `UPLOADED` parmi tous les marchands, triés par date de téléversement croissante (les plus anciens en premier).

---

### 7.8 Back-office — Obtenir une URL pré-signée (admin)

```http
GET /internal/merchants/kyc/documents/{documentId}/url
Authorization: Bearer <jwt-admin>
```

**Rôles requis** : `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`, `SUPPORT`

Retourne une URL pré-signée valable 15 minutes. Aucune vérification d'appartenance au marchand (un admin peut consulter n'importe quel document).

---

### 7.9 Back-office — Réviser un document

```http
PUT /internal/merchants/kyc/documents/{documentId}/review
Authorization: Bearer <jwt-admin>
Content-Type: application/json

{
  "status": "ACCEPTED",
  "notes": "Document lisible, valide jusqu'en 2028"
}
```

**Rôles requis** : `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`

**Corps de la requête**

| Champ    | Type                     | Obligatoire | Description                                    |
|----------|--------------------------|-------------|------------------------------------------------|
| `status` | `ACCEPTED` \| `REJECTED` | ✅           | Nouveau statut du document                    |
| `notes`  | chaîne                   |             | Notes libres du réviseur (visibles en admin uniquement) |

**Réponse `200 OK`**

```json
{
  "success": true,
  "data": {
    "id": "...",
    "status": "ACCEPTED",
    "reviewerNotes": "Document lisible, valide jusqu'en 2028",
    "uploadedAt": "2026-03-17T10:00:00Z",
    ...
  }
}
```

---

### 7.10 Back-office — Approuver / Rejeter le dossier KYC

Ces endpoints agissent sur le **statut KYC du marchand** (dossier global), pas sur les documents individuels.

```http
PUT /internal/merchants/{merchantId}/kyc/approve
PUT /internal/merchants/{merchantId}/kyc/reject
```

Voir la documentation générale de l'API back-office pour les détails.

Lors de l'approbation, le rôle du marchand est élevé à `MERCHANT_KYC_VERIFIED`, lui donnant accès aux retraits et décaissements. Un `KycStatusChangedEvent` est publié dans l'outbox dans la même transaction.

---

## 8. Événements et notifications

### 8.1 KycStatusChangedEvent

Publié dans l'outbox (dans la même transaction que l'approbation ou le rejet) lorsque le dossier KYC d'un marchand atteint un état terminal.

```java
public record KycStatusChangedEvent(
    UUID   merchantId,
    String merchantEmail,
    String businessName,
    String newStatus,        // "APPROVED" | "REJECTED"
    String rejectionReason   // null si APPROVED
) {}
```

**Consommateurs**

| Consommateur         | Action                                                      |
|----------------------|-------------------------------------------------------------|
| `NotificationService`| Envoie un email d'approbation ou de rejet au marchand       |
| *(à venir)*          | Webhook vers l'URL configurée par le marchand               |

### 8.2 Modèles d'emails

**En cas d'APPROBATION** — objet : `Votre KYC a été approuvé`
- Informe le marchand que son compte est entièrement activé
- Liste les services nouvellement accessibles (paiements, décaissements, retraits)

**En cas de REJET** — objet : `Votre KYC nécessite des corrections`
- Inclut la raison du rejet
- Invite le marchand à re-téléverser les documents corrigés et à soumettre à nouveau son dossier

---

## 9. Schéma de base de données

### Table : `kyc_documents`

| Colonne          | Type            | Contraintes                    | Description                                          |
|------------------|-----------------|--------------------------------|------------------------------------------------------|
| `id`             | UUID            | PK, DEFAULT gen_random_uuid()  | Identifiant du document                              |
| `merchant_id`    | UUID            | NOT NULL, FK → merchants(id)   | Marchand propriétaire                                |
| `document_type`  | VARCHAR(50)     | NOT NULL                       | Valeur de l'enum `KycDocumentType`                   |
| `status`         | VARCHAR(30)     | NOT NULL, DEFAULT 'UPLOADED'   | Valeur de l'enum `KycDocumentStatus`                 |
| `storage_key`    | TEXT            | NOT NULL                       | Clé S3 opaque — jamais retournée aux clients         |
| `file_name`      | TEXT            | NOT NULL                       | Nom de fichier original assaini                      |
| `content_type`   | VARCHAR(100)    | NOT NULL                       | Type MIME détecté par Apache Tika                    |
| `file_size_bytes`| BIGINT          | NOT NULL                       | Nombre exact d'octets                                |
| `checksum_sha256`| VARCHAR(64)     | NOT NULL                       | SHA-256 hexadécimal — utilisé pour la déduplication  |
| `provider_name`  | VARCHAR(50)     |                                | `smile_identity` \| `sumsub` \| `mock` \| NULL       |
| `provider_ref`   | VARCHAR(255)    |                                | Référence de job/candidat attribuée par le prestataire |
| `reviewer_notes` | TEXT            |                                | Notes libres du réviseur back-office                 |
| `reviewed_by`    | VARCHAR(255)    |                                | Nom d'utilisateur du réviseur                        |
| `reviewed_at`    | TIMESTAMPTZ     |                                | Horodatage de la révision                            |
| `uploaded_at`    | TIMESTAMPTZ     | NOT NULL, DEFAULT NOW()        | Horodatage du téléversement                          |
| `expires_at`     | TIMESTAMPTZ     |                                | Date d'expiration du document (passeport, etc.)      |
| `deleted_at`     | TIMESTAMPTZ     |                                | Horodatage de suppression logique — NULL = actif     |

**Index**

| Nom de l'index                | Colonnes                        | Utilité                                      |
|-------------------------------|---------------------------------|----------------------------------------------|
| `idx_kyc_docs_merchant`       | `merchant_id`                   | Lister tous les docs d'un marchand           |
| `idx_kyc_docs_status`         | `status`                        | Retrouver les documents en attente de révision |
| `idx_kyc_docs_merchant_type`  | `merchant_id, document_type`    | Vérifier si un type est déjà téléversé       |

### Colonnes pertinentes dans la table `merchants`

| Colonne                | Type         | Description                                            |
|------------------------|--------------|--------------------------------------------------------|
| `kyc_status`           | VARCHAR(20)  | `NONE` \| `PENDING` \| `APPROVED` \| `REJECTED`       |
| `kyc_verified`         | BOOLEAN      | `true` quand le statut est APPROVED                    |
| `kyc_submitted_at`     | TIMESTAMP    | Dernière date de soumission du dossier                 |
| `kyc_reviewed_at`      | TIMESTAMP    | Dernière date de révision du dossier                   |
| `kyc_rejection_reason` | TEXT         | Motif du dernier rejet                                 |

---

## 10. Référence de configuration

### 10.1 Configuration du stockage

| Propriété                               | Défaut                  | Description                                         |
|-----------------------------------------|-------------------------|-----------------------------------------------------|
| `ebithex.kyc.storage.provider`          | `local`                 | `local` ou `s3`                                     |
| `ebithex.kyc.storage.s3.bucket`         | `ebithex-kyc`           | Nom du bucket S3                                    |
| `ebithex.kyc.storage.s3.region`         | `auto`                  | Région AWS ou `auto` pour R2                        |
| `ebithex.kyc.storage.s3.endpoint`       | _(défaut AWS)_          | Override pour R2 / MinIO                            |
| `ebithex.kyc.storage.s3.access-key`     | —                       | Clé d'accès S3                                      |
| `ebithex.kyc.storage.s3.secret-key`     | —                       | Clé secrète S3                                      |
| `ebithex.kyc.storage.local.path`        | `${tmpdir}/ebithex-kyc` | Chemin du système de fichiers local                 |

### 10.2 Configuration des prestataires

| Propriété                                 | Défaut  | Description                                            |
|-------------------------------------------|---------|--------------------------------------------------------|
| `ebithex.kyc.auto-submit-to-provider`     | `false` | Soumission automatique au téléversement                |
| `ebithex.kyc.provider.mock.enabled`       | `false` | Activer le prestataire mock (local/test)               |
| `ebithex.kyc.provider.smile.enabled`      | `false` | Activer Smile Identity                                 |
| `ebithex.kyc.provider.smile.partner-id`   | —       | Identifiant partenaire Smile Identity                  |
| `ebithex.kyc.provider.smile.api-key`      | —       | Clé API Smile Identity                                 |
| `ebithex.kyc.provider.smile.base-url`     | `https://testapi.smileidentity.com/v1` | Override pour la production |
| `ebithex.kyc.provider.sumsub.enabled`     | `false` | Activer Sumsub                                         |
| `ebithex.kyc.provider.sumsub.app-token`   | —       | Token applicatif Sumsub                                |
| `ebithex.kyc.provider.sumsub.secret-key`  | —       | Clé secrète Sumsub                                     |
| `ebithex.kyc.provider.sumsub.base-url`    | `https://api.sumsub.com` | URL de base Sumsub                    |

---

## 11. Mise en place par environnement

### 11.1 Développement local

Aucun service externe n'est nécessaire. Tous les documents sont stockés sur le système de fichiers local et le prestataire mock accepte tout automatiquement.

```bash
# Démarrer l'infrastructure
docker compose up -d

# Démarrer l'application
mvn spring-boot:run -pl module-app -Dspring-boot.run.profiles=local
```

Le chemin de stockage local est `${java.io.tmpdir}/ebithex-kyc` par défaut.

### 11.2 Développement / Staging

Utiliser un bucket S3 dédié ou une instance MinIO. Définir `KYC_MOCK_ENABLED=true` pour éviter les appels prestataires réels.

Variables d'environnement requises :
```bash
KYC_STORAGE_PROVIDER=s3
KYC_S3_BUCKET=ebithex-kyc-dev
KYC_S3_REGION=auto
KYC_S3_ENDPOINT=https://<account>.r2.cloudflarestorage.com
KYC_S3_ACCESS_KEY=...
KYC_S3_SECRET_KEY=...
KYC_MOCK_ENABLED=true
```

### 11.3 Production

Variables d'environnement requises :
```bash
# Stockage (obligatoire)
KYC_S3_BUCKET=ebithex-kyc-prod
KYC_S3_REGION=auto
KYC_S3_ENDPOINT=https://<account>.r2.cloudflarestorage.com
KYC_S3_ACCESS_KEY=...
KYC_S3_SECRET_KEY=...

# Smile Identity (optionnel jusqu'à la finalisation de l'intégration)
KYC_AUTO_SUBMIT=false
KYC_SMILE_ENABLED=false
KYC_SMILE_PARTNER_ID=
KYC_SMILE_API_KEY=

# Sumsub (optionnel jusqu'à la finalisation de l'intégration)
KYC_SUMSUB_ENABLED=false
KYC_SUMSUB_APP_TOKEN=
KYC_SUMSUB_SECRET_KEY=
```

---

## 12. Modèle de sécurité

### 12.1 Contrôle d'accès

| Endpoint                                            | Rôle(s) requis                                         |
|-----------------------------------------------------|--------------------------------------------------------|
| `POST /v1/merchants/kyc/documents`                  | `MERCHANT`, `MERCHANT_KYC_VERIFIED`                    |
| `GET /v1/merchants/kyc/documents`                   | `MERCHANT`, `MERCHANT_KYC_VERIFIED`                    |
| `GET /v1/merchants/kyc/documents/{id}/url`          | `MERCHANT`, `MERCHANT_KYC_VERIFIED`                    |
| `DELETE /v1/merchants/kyc/documents/{id}`           | `MERCHANT`, `MERCHANT_KYC_VERIFIED`                    |
| `POST /v1/merchants/kyc`                            | `MERCHANT`, `MERCHANT_KYC_VERIFIED`                    |
| `GET /internal/merchants/{id}/kyc/documents`        | `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`, `SUPPORT`     |
| `GET /internal/merchants/kyc/documents/pending`     | `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`, `SUPPORT`     |
| `GET /internal/merchants/kyc/documents/{id}/url`    | `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`, `SUPPORT`     |
| `PUT /internal/merchants/kyc/documents/{id}/review` | `COUNTRY_ADMIN`, `ADMIN`, `SUPER_ADMIN`                |

### 12.2 Isolation des données

- Les endpoints marchands appliquent la vérification de propriété : `findActiveByIdAndMerchantId(docId, merchantId)` — un marchand ne peut jamais accéder aux documents d'un autre marchand.
- La `storage_key` n'est **jamais retournée** aux clients ; seule une URL pré-signée à durée limitée est fournie.

### 12.3 Sécurité des fichiers

- **Validation MIME par magic bytes** (Apache Tika) : le client ne peut pas usurper le type de contenu.
- **Déduplication SHA-256** par marchand : empêche le re-téléversement d'un fichier identique.
- **Chiffrement côté serveur** : S3 `AES256` appliqué à chaque requête `PutObject`.
- **Masquage de l'extension** : tous les fichiers stockés portent l'extension `.bin` quel que soit le type de contenu.
- **TTL d'URL de 15 minutes** : les URLs pré-signées expirent rapidement, limitant la fenêtre d'exposition.
- **Suppression logique** : les documents supprimés sont retirés logiquement, mais l'objet en stockage est conservé à des fins d'audit jusqu'au nettoyage par la règle de cycle de vie.

### 12.4 Piste d'audit

Chaque action sur un document génère une entrée dans le journal d'audit :

| Action                        | Événement d'audit         |
|-------------------------------|---------------------------|
| Téléversement                 | `KYC_DOCUMENT_UPLOADED`   |
| Document accepté              | `KYC_DOCUMENT_ACCEPTED`   |
| Document rejeté               | `KYC_DOCUMENT_REJECTED`   |
| Dossier approuvé              | `KYC_APPROVED`            |
| Dossier rejeté                | `KYC_REJECTED`            |

---

## 13. Codes d'erreur

| Code d'erreur               | HTTP | Description                                                        |
|-----------------------------|------|--------------------------------------------------------------------|
| `EMPTY_FILE`                | 400  | Fichier nul ou vide                                                |
| `FILE_TOO_LARGE`            | 400  | Fichier dépasse 10 Mo                                              |
| `INVALID_MIME_TYPE`         | 400  | Format non autorisé (doit être PDF, JPEG, PNG ou WEBP)             |
| `DUPLICATE_DOCUMENT`        | 409  | Un fichier avec le même hash SHA-256 a déjà été téléversé          |
| `STORAGE_ERROR`             | 500  | Impossible d'écrire dans le stockage d'objets                      |
| `DOCUMENT_NOT_FOUND`        | 404  | Document inexistant ou appartenant à un autre marchand             |
| `DOCUMENT_LOCKED`           | 400  | Impossible de supprimer un document ACCEPTED                       |
| `INVALID_STATUS`            | 400  | Statut cible invalide pour la révision                             |
| `KYC_ALREADY_APPROVED`      | 400  | Le dossier KYC est déjà dans l'état APPROVED                       |
| `KYC_INCOMPLETE_DOSSIER`    | 400  | Types de documents requis manquants ou pas encore acceptés         |
| `KYC_INVALID_STATE`         | 400  | Action sur le dossier incompatible avec le statut KYC actuel       |
| `MERCHANT_NOT_FOUND`        | 404  | Marchand introuvable                                               |

---

## 14. Ajouter un nouveau prestataire KYC

1. **Créer la classe du prestataire** dans `module-merchant/src/main/java/io/ebithex/merchant/kyc/` :

```java
@Component
@ConditionalOnProperty(name = "ebithex.kyc.provider.monprestataire.enabled", havingValue = "true")
public class MonKycProvider implements KycProvider {

    @Override
    public Set<String> supportedCountries() {
        return Set.of("XX", "YY"); // codes ISO 3166-1 alpha-2
    }

    @Override
    public String submitVerification(KycDocument document, String countryCode) {
        // Appel API prestataire, retourner sa référence de job
        return providerClient.submit(document.getStorageKey(), document.getDocumentType());
    }

    @Override
    public VerificationResult checkStatus(String providerRef) {
        // Interroger l'API prestataire
        ProviderStatus s = providerClient.getStatus(providerRef);
        return new VerificationResult(
            providerRef,
            s.isVerified() ? VerificationStatus.ACCEPTED : VerificationStatus.REJECTED,
            s.getReason()
        );
    }

    @Override
    public String providerName() { return "monprestataire"; }
}
```

2. **Ajouter les propriétés de configuration** dans `application-prod.properties` :

```properties
ebithex.kyc.provider.monprestataire.enabled=${KYC_MONPRESTATAIRE_ENABLED:false}
ebithex.kyc.provider.monprestataire.api-key=${KYC_MONPRESTATAIRE_API_KEY:}
```

3. **Aucune modification nécessaire** dans `KycProviderRegistry` — il détecte automatiquement tous les beans `KycProvider` présents dans le contexte Spring.

4. **Implémenter un callback webhook** (optionnel) : si le prestataire envoie des résultats de façon asynchrone, créer un endpoint `POST /api/v1/callbacks/kyc/{prestataire}`, valider la signature HMAC, puis appeler `kycDocumentService.review()` ou `submitToProvider()` selon le cas.

---

*Dernière mise à jour : 17 mars 2026*