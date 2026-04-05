package com.ebithex.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration OpenAPI / Swagger UI pour Ebithex Backend.
 *
 * Expose deux schémas d'authentification :
 *  - BearerAuth (JWT)  → utilisé par les marchands et les StaffUsers (back-office)
 *  - ApiKeyAuth        → utilisé par les marchands (header X-API-Key)
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ebithexOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Ebithex Backend API")
                .description("""
                    API de l'agrégateur de paiements Ebithex.

                    **Authentification marchands** : JWT (Bearer) ou clé API (header `X-API-Key`).
                    **Authentification back-office** : JWT (Bearer) issu du login StaffUser avec 2FA (`/internal/auth/login`).

                    ## Rotation des clés JWT

                    Les JWT sont signés par la clé active (`JWT_SECRETS`, index 0).
                    Pour effectuer une rotation sans coupure de service :
                    1. Ajouter la nouvelle clé en **tête** de liste : `JWT_SECRETS=nouvelleClé,ancienneClé`
                    2. Redéployer l'application — les tokens existants (signés par l'ancienne clé) restent valides.
                    3. Une fois tous les tokens expirés, retirer l'ancienne clé : `JWT_SECRETS=nouvelleClé`

                    Chaque token porte un claim `kid` (Key ID) qui identifie la clé de signature.

                    ## Callbacks opérateurs

                    Les endpoints `/v1/callbacks/**` sont **publics** (aucun JWT ni clé API requis).
                    Ils sont sécurisés par signature **HMAC-SHA256** : chaque callback entrant est
                    authentifié via le header `X-{Operator}-Signature` calculé avec le secret partagé
                    de l'opérateur. Les badges 🔒 affichés par Swagger sur ces endpoints reflètent
                    la sécurité globale de l'API et ne s'appliquent pas aux callbacks.

                    ## Clés API — Scopes

                    Chaque clé API peut être créée avec un sous-ensemble de scopes. Une clé sans scope explicite
                    reçoit `FULL_ACCESS`. Les requêtes JWT (dashboard, back-office) ignorent les scopes.

                    | Scope | Opérations |
                    |-------|-----------|
                    | `FULL_ACCESS` | Toutes les opérations (clé par défaut sans scope explicite) |
                    | `PAYMENTS_WRITE` | Initier des paiements (`POST /v1/payments`), rembourser (`POST /v1/payments/{ref}/refund`), annuler (`POST /v1/payments/{ref}/cancel`) |
                    | `PAYMENTS_READ` | Lire le statut d'un paiement (`GET /v1/payments/{ref}`), lister les transactions (`GET /v1/payments`), vérifier un numéro (`GET /v1/payments/phone-check`) |
                    | `PAYOUTS_WRITE` | Initier des décaissements (`POST /v1/payouts`), bulk payouts (`POST /v1/payouts/bulk`) |
                    | `PAYOUTS_READ` | Lire l'état des payouts (`GET /v1/payouts`, `GET /v1/payouts/{ref}`) |
                    | `WEBHOOKS_READ` | Lire les webhooks et livraisons (`GET /v1/webhooks`, `GET /v1/webhooks/{id}/deliveries`) |
                    | `WEBHOOKS_WRITE` | Créer/modifier/supprimer des webhooks, envoyer des tests |
                    | `PROFILE_READ` | Lire le profil marchand (`GET /v1/merchants/me`) |

                    Une clé peut aussi être restreinte par **liste blanche d'IPs** (`allowedIps`) ou avoir une
                    **date d'expiration** (`expiresAt`). Une **politique de rotation forcée** (`rotationRequiredDays`)
                    peut être imposée par un SUPER_ADMIN.

                    ## Rate Limiting

                    Les requêtes sont limitées par clé API / IP :

                    | Plan | Limite / min | Limite / h | Attribué à |
                    |------|-------------|-----------|------------|
                    | `ANONYMOUS` | 10 | 300 | Requêtes sans auth (par IP) |
                    | `STANDARD` | 60 | 3 000 | MERCHANT, AGENT |
                    | `PREMIUM` | 300 | 10 000 | MERCHANT_KYC_VERIFIED |

                    En cas de dépassement, l'API retourne **429 Too Many Requests** avec les headers :
                    - `X-Rate-Limit-Limit` : quota de la fenêtre courante
                    - `X-Rate-Limit-Remaining` : requêtes restantes
                    - `X-Rate-Limit-Reset` : timestamp UNIX de réinitialisation de la fenêtre

                    ## Rôles marchands
                    | Rôle | Accès |
                    |------|-------|
                    | `MERCHANT` | Paiements, wallet (lecture), webhooks, KYC, gestion des clés API |
                    | `MERCHANT_KYC_VERIFIED` | Tout MERCHANT + payouts, retraits, bulk, litiges |
                    | `AGENT` | Paiements, payouts |

                    ## Rôles back-office
                    | Rôle | Périmètre |
                    |------|-----------|
                    | `SUPPORT` | Lecture transactions/payouts, litiges, DLQ webhooks |
                    | `FINANCE` | Wallets, retraits, float, settlement, rapports réglementaires |
                    | `RECONCILIATION` | Import relevés opérateurs, réconciliation, exports |
                    | `COMPLIANCE` | Alertes AML, listes de sanctions, **export SAR/CCF** |
                    | `COUNTRY_ADMIN` | Gestion marchands + KYC pour un pays donné |
                    | `ADMIN` | Toutes opérations back-office + révocation clés API marchands |
                    | `SUPER_ADMIN` | Accès complet (staff CRUD, règles tarifaires, rotation clés AES, politique rotation clés API) |

                    ## Authentification à deux facteurs (2FA)

                    Le 2FA par OTP email est **obligatoire et non-désactivable** pour les rôles `ADMIN` et `SUPER_ADMIN`.

                    **Flux login avec 2FA** :
                    1. `POST /internal/auth/login` → `{ requiresTwoFactor: true, tempToken, expiresInSeconds: 300 }`
                    2. Réception du code OTP par email (valide 5 min)
                    3. `POST /internal/auth/login/verify-otp` avec `tempToken` + `code` → JWT final

                    **Flux login sans 2FA** (rôles optionnels avec 2FA désactivé) :
                    1. `POST /internal/auth/login` → JWT final directement

                    **Enforcement** :
                    - `twoFactorEnabled` est forcé à `true` à la création pour `ADMIN`/`SUPER_ADMIN`
                    - `PUT /internal/staff-users/{id}` retourne `403 TWO_FACTOR_REQUIRED` si tentative de désactivation
                    - Auto-correction du flag en DB lors du login si incohérence détectée

                    **Protection bruteforce OTP** :
                    - Maximum **5 tentatives** de vérification par `tempToken`
                    - Après 5 échecs : `429 LOGIN_ATTEMPTS_EXCEEDED` + `tempToken` invalidé (nouveau login requis)
                    - Compteur stocké dans Redis sous `otp:attempts:{tempToken}` (TTL 5 min)

                    ## Export SAR/CCF — Déclaration aux autorités

                    Les endpoints suivants permettent à l'équipe `COMPLIANCE` de générer les déclarations réglementaires
                    (Suspicious Activity Reports) pour la CENTIF / BCEAO :

                    | Méthode | Endpoint | Description |
                    |---------|----------|-------------|
                    | `GET` | `/internal/aml/alerts/export/sar/preview` | Aperçu JSON (nombre d'alertes) |
                    | `GET` | `/internal/aml/alerts/export/sar` | Export CSV (attachment) |
                    | `POST` | `/internal/aml/alerts/export/sar/mark-reported` | Marquer comme déclarées (**irréversible**) |

                    Périmètre : alertes `HIGH` et `CRITICAL` au statut `OPEN` ou `UNDER_REVIEW`.

                    ## Clés JWT — Longueur minimale

                    Chaque secret dans `JWT_SECRETS` doit faire **minimum 32 octets** (256 bits) pour garantir
                    la sécurité de HMAC-SHA256. Le démarrage échoue avec une `IllegalArgumentException` si un
                    secret est trop court — y compris les clés de grâce (rotation multi-secrets).

                    ```
                    Générer un secret sécurisé : openssl rand -base64 48
                    ```

                    ## CORS — Validation au démarrage

                    En profil `prod`, le démarrage **échoue immédiatement** si `ebithex.security.cors.allowed-origins`
                    contient un wildcard `*`. Cela empêche un déploiement production avec CORS non configuré.
                    Définir des origines explicites (`https://dashboard.ebithex.io,https://app.ebithex.io`).

                    ## Rate Limiting — Comportement fail-safe

                    Le rate limiting utilise Redis comme source de vérité. En cas d'indisponibilité Redis,
                    le système **bascule automatiquement sur un fallback en mémoire locale** (par nœud) —
                    le rate limiting reste actif avec une précision légèrement réduite. Il n'y a pas de
                    fail-open (désactivation totale) en cas de panne Redis.
                    """)
                .version("1.0.0")
                .contact(new Contact()
                    .name("Ebithex Engineering")
                    .email("engineering@ebithex.io"))
                .license(new License()
                    .name("Proprietary")))
            .addSecurityItem(new SecurityRequirement()
                .addList("BearerAuth")
                .addList("ApiKeyAuth"))
            .components(new Components()
                .addSecuritySchemes("BearerAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT obtenu via POST /v1/auth/login ou /internal/auth/login"))
                .addSecuritySchemes("ApiKeyAuth", new SecurityScheme()
                    .type(SecurityScheme.Type.APIKEY)
                    .in(SecurityScheme.In.HEADER)
                    .name("X-API-Key")
                    .description("""
                        Clé API marchand au format `ap_live_<43 chars>` ou `ap_test_<43 chars>`.
                        Créée depuis le dashboard Ebithex ou via POST /v1/auth/api-keys.
                        Peut être restreinte par scopes, IPs autorisées et date d'expiration.
                        """)));
    }
}