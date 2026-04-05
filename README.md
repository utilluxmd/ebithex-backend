# Ebithex Backend

Agrégateur de paiement Mobile Money pour l'Afrique de l'Ouest (UEMOA/CEMAC).
API REST production-grade : paiements entrants, virements, portefeuilles multi-devises, conformité AML/sanctions, reporting réglementaire.

---

## Stack

| Composant | Technologie |
|-----------|-------------|
| Langage | Java 21 |
| Framework | Spring Boot 3.4.3 |
| Build | Maven 3.8+ (multi-module) |
| Base de données | PostgreSQL 16 + Flyway |
| Cache / Idempotence | Redis 7 |
| Sécurité | Spring Security — API Key + JWT Bearer |
| Résilience | Resilience4j (circuit breaker, retry) |
| Observabilité | Prometheus + Grafana + OpenTelemetry (OTLP) |
| Logs | Logstash-Logback (JSON structuré) |
| Tests | JUnit 5 + Testcontainers |

---

## Modules

```
ebithex-backend/
├── module-app/          # Bootstrap, configuration globale, migrations Flyway
├── module-auth/         # Authentification JWT, gestion staff, audit logs
├── module-merchant/     # Onboarding marchand, KYC, back-office
├── module-operator/     # Adaptateurs opérateurs Mobile Money
├── module-payment/      # Paiements, virements, remboursements, frais, AML, litiges, rapprochements
├── module-wallet/       # Portefeuilles multi-devises, transferts internes
├── module-webhook/      # Gestion et livraison des webhooks marchands
├── module-notification/ # Notifications email & SMS
└── module-shared/       # DTOs partagés, utilitaires, exceptions, sécurité transverse
```

---

## Démarrage rapide

**Prérequis :** Java 21, Maven 3.8+, Docker

```bash
cp .env.example .env
# Remplir .env avec vos secrets (voir .env.example pour la liste complète)

# Démarrer PostgreSQL + Redis uniquement
docker compose up -d postgres redis

# Puis lancer l'application
mvn spring-boot:run -pl module-app -Dspring-boot.run.profiles=local
```

**Bootstrap du premier SUPER_ADMIN :**

Au premier démarrage, si aucun SUPER_ADMIN n'existe, l'application en crée un automatiquement
à partir des variables d'environnement `EBITHEX_SUPER_ADMIN_EMAIL` et `EBITHEX_SUPER_ADMIN_PASSWORD`
(configurées dans `application-local.properties` ou `.env`).

> ⚠️ **Changez le mot de passe** après le premier login. Supprimez ou videz ces variables une fois le compte créé.

**Outils de développement (app locale) :**

```bash
docker compose --profile tools up -d                          # + pgAdmin, Redis Insight
docker compose --profile observability up -d                   # + Prometheus, Grafana
docker compose --profile tools --profile observability up -d   # Tout sauf l'app
```

**Stack complète conteneurisée (production-like) :**

```bash
docker compose --profile full up -d
docker compose --profile full --profile tools --profile observability up -d  # Tout
```

**Accès locaux :**

| Service | URL | Credentials |
|---------|-----|-------------|
| API | http://localhost:8080/api | — |
| Swagger UI | http://localhost:8080/api/swagger-ui.html | — |
| pgAdmin | http://localhost:5050 | `admin@ebithex.com` / `admin` |
| Redis Insight | http://localhost:5540 | — |
| Prometheus | http://localhost:9090 | — |
| Grafana | http://localhost:3000 | `admin` / `admin` |

---

## Build & Tests

```bash
mvn clean package -DskipTests   # Build rapide
mvn clean package               # Build + tests (Docker requis pour Testcontainers)
mvn verify -Pcoverage           # Build + couverture JaCoCo (seuil : 80 %)
```

---

## Profils d'environnement

| Profil | Usage |
|--------|-------|
| `local` | Développement local — secrets dans `.env` (git-ignoré) |
| `dev` | Staging / CI — secrets via variables d'environnement |
| `prod` | Production |
| `test` | Tests automatisés (Testcontainers) |

---

## Authentification

**API Key** — intégrations marchands :
```
X-API-Key: ap_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

**JWT Bearer** — dashboard & back-office :
```
Authorization: Bearer <token>
```

Les tokens JWT acceptent les types `access` et `operator` uniquement. Les refresh tokens sont rejetés comme Bearer sur les ressources.

---

## Documentation

| Sujet | Fichier |
|-------|---------|
| Clés API — rotation, grâce | [`docs/api-keys.md`](docs/api-keys.md) |
| Authentification 2FA opérateurs | [`docs/two-factor-auth.md`](docs/two-factor-auth.md) |
| Rétention et purge des PII | [`docs/pii-retention.md`](docs/pii-retention.md) |
| AML & sanctions | [`docs/aml.md`](docs/aml.md) |
| KYC marchands | [`docs/kyc.md`](docs/kyc.md) |
| Litiges | [`docs/disputes.md`](docs/disputes.md) |
| Settlement & reconciliation | [`docs/settlement.md`](docs/settlement.md) |
| Rate limiting | [`docs/rate-limiting.md`](docs/rate-limiting.md) |
| Caching | [`docs/caching.md`](docs/caching.md) |
| Sandbox opérateurs | [`docs/sandbox.md`](docs/sandbox.md) |
| Opérateurs supportés | [`docs/operators.md`](docs/operators.md) |
| Flux & cas d'usage | [`docs/flows-et-cas-usage.md`](docs/flows-et-cas-usage.md) |
| Audit logs | [`docs/audit.md`](docs/audit.md) |
| Disaster recovery | [`docs/disaster-recovery.md`](docs/disaster-recovery.md) |

La documentation API interactive est disponible sur `/api/swagger-ui.html` (application démarrée).

---

## Health & Monitoring

```
GET /api/actuator/health      # Liveness / readiness
GET /api/actuator/prometheus  # Métriques Prometheus
```
