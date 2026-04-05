# Ebithex — Plan de Reprise d'Activité (PRA / Disaster Recovery)

> **Version :** 1.0 — Mars 2026
> **Propriétaire :** Ebithex SRE / Infrastructure
> **Classification :** CONFIDENTIEL — Usage interne uniquement

---

## 1. Objectifs de reprise (RPO / RTO)

| Composant | RPO (perte de données max) | RTO (durée d'interruption max) | Justification |
|---|---|---|---|
| Base de données PostgreSQL | **1 minute** | **15 minutes** | Transactions financières — critiques |
| Cache Redis | **0** (données éphémères) | **5 minutes** | Reconstruit automatiquement |
| Application ebithex-backend | **N/A** | **5 minutes** | Stateless — redémarrage immédiat |
| Fichiers CSV de réconciliation | **24 heures** | **4 heures** | Peut être re-importé manuellement |

> **Exigences BCEAO** : L'instruction 008-05-2015 impose un RTO ≤ 4 heures pour les établissements de monnaie électronique. Les cibles ci-dessus sont plus strictes.

---

## 2. Architecture de haute disponibilité

```
┌─────────────────────────────────────────────────────────┐
│                   Zone de disponibilité A               │
│  ┌──────────────┐   ┌──────────────┐                   │
│  │ App Node 1   │   │ App Node 2   │  (minimum 2 pods) │
│  └──────┬───────┘   └──────┬───────┘                   │
│         └─────────┬─────────┘                           │
│              Load Balancer                               │
└──────────────────────────────────────────────────────────┘
            │                    │
┌───────────▼──────┐  ┌─────────▼────────────────────────┐
│ PostgreSQL       │  │ Redis (Sentinel ou Cluster)        │
│ Primary (Zone A) │  │ Primary + 2 Replicas              │
└───────────┬──────┘  └──────────────────────────────────┘
            │
┌───────────▼──────┐
│ PostgreSQL       │
│ Standby (Zone B) │  ← Streaming Replication sync
│ WAL archivage S3 │
└──────────────────┘
```

### Composants critiques

| Composant | Mode HA | Basculement |
|---|---|---|
| PostgreSQL | Primary + Hot Standby | Automatique (Patroni) ou manuel |
| Redis | Sentinel (3 nœuds) | Automatique (<30 s) |
| Application | 2+ pods Kubernetes | Rolling deploy / HPA |
| Ingress | 2 réplicas NGINX | Automatique |

---

## 3. Stratégie de sauvegarde

### 3.1 PostgreSQL

| Type | Fréquence | Rétention | Stockage | Vérification |
|---|---|---|---|---|
| WAL continu (Point-in-Time Recovery) | En continu | 7 jours | S3 chiffré (AES-256) | Automatique |
| Sauvegarde base complète (pg_dump) | Quotidienne à 00:30 UTC | 30 jours | S3 chiffré | Hebdomadaire |
| Sauvegarde longue durée (conformité) | Mensuelle | 5 ans | S3 Glacier | Semestrielle |

**Configuration WAL archivage** (`postgresql.conf`) :
```
wal_level = replica
archive_mode = on
archive_command = 'aws s3 cp %p s3://ebithex-backups/wal/%f'
max_wal_senders = 5
wal_keep_size = 1GB
```

**Script de sauvegarde quotidienne** (`/opt/scripts/pg_backup.sh`) :
```bash
#!/bin/bash
BACKUP_FILE="ebithex_$(date +%Y%m%d_%H%M%S).dump"
pg_dump -Fc -U ebithex ebithex_db | \
  aws s3 cp - "s3://ebithex-backups/daily/${BACKUP_FILE}" \
  --sse aws:kms --sse-kms-key-id arn:aws:kms:...
echo "Sauvegarde ${BACKUP_FILE} terminée"
```

### 3.2 Redis

Redis est utilisé pour :
- Sessions JWT blacklistées (TTL auto, non critiques)
- Rate limiting (reconstruit automatiquement)
- OTP 2FA (TTL 10 min, non critiques)

**Aucune sauvegarde persistante requise** — les données Redis sont soit éphémères, soit reconstruites depuis PostgreSQL.

### 3.3 Clés de chiffrement AES

> ⚠️ **Criticité maximale** — la perte des clés AES rend toutes les données PII irrécupérables.

| Stockage | Description |
|---|---|
| **Production** | AWS Secrets Manager ou HashiCorp Vault (HSM si disponible) |
| **Backup clés** | Envelope encryption : clé AES chiffrée par KMS AWS → stockée en S3 Glacier |
| **Procédure d'urgence** | Clé de récupération M-of-N (Shamir Secret Sharing) : 3/5 directeurs doivent approuver |
| **Rotation** | Annuelle (NIST SP 800-57) via `POST /internal/admin/key-rotation` |

---

## 4. Scénarios de panne et runbooks

### 4.1 Panne de base de données primaire (RTO 15 min)

**Détection** : Alerte Prometheus `pg_up == 0` ou `pg_replication_lag > 30s`

**Runbook** :

```bash
# 1. Vérifier l'état de la réplication
psql -h pg-standby -c "SELECT pg_is_in_recovery(), pg_last_wal_receive_lsn();"

# 2. Promouvoir le standby en primaire (Patroni)
patronictl -c /etc/patroni/config.yml failover ebithex-cluster \
  --master pg-primary --candidate pg-standby --force

# 3. Mettre à jour la configuration application (si DNS non automatique)
kubectl set env deployment/ebithex-backend \
  SPRING_DATASOURCE_URL=jdbc:postgresql://pg-standby:5432/ebithex_db

# 4. Vérifier la connectivité
curl -f https://api.ebithex.io/api/actuator/health

# 5. Redémarrer l'ancien primaire en mode standby une fois récupéré
```

**Post-incident** :
- Vérifier l'intégrité des données depuis le dernier WAL
- Recalculer les agrégats de settlement si la panne couvre une fenêtre de settlement
- Notifier la BCEAO si l'interruption dépasse 4 heures

---

### 4.2 Perte de données (suppression accidentelle / corruption)

**RTO estimé** : 30-60 minutes selon la fenêtre PITR

**Runbook — Point-in-Time Recovery** :

```bash
# 1. Identifier le timestamp cible (dernier état cohérent connu)
TARGET_TIME="2026-03-19 14:30:00 UTC"

# 2. Restaurer depuis le backup complet le plus récent avant TARGET_TIME
aws s3 cp s3://ebithex-backups/daily/ebithex_20260319_003000.dump ./restore.dump
pg_restore -Fc -d ebithex_db_restore -U postgres ./restore.dump

# 3. Appliquer les WAL jusqu'au point cible
# (configurer recovery_target_time dans postgresql.conf du serveur de restauration)
echo "recovery_target_time = '${TARGET_TIME}'" >> /var/lib/postgresql/data/postgresql.conf
echo "restore_command = 'aws s3 cp s3://ebithex-backups/wal/%f %p'" >> postgresql.conf

# 4. Démarrer PostgreSQL en mode recovery
pg_ctl start -D /var/lib/postgresql/data

# 5. Valider les données restaurées
psql -c "SELECT COUNT(*), MAX(created_at) FROM transactions;"

# 6. Basculer le trafic vers la base restaurée
# 7. Notifier les marchands impactés si des transactions sont perdues
```

---

### 4.3 Compromission des clés AES

> **Criticité : CRITIQUE** — déclencher immédiatement la procédure de gestion de crise

**Runbook** :

1. **Contenir** : Révoquer immédiatement la clé compromise dans AWS Secrets Manager / Vault
2. **Évaluer** : Identifier quelles données ont été exposées (scope, durée)
3. **Générer** une nouvelle clé :
   ```bash
   openssl rand -base64 32
   # → Stocker dans Secrets Manager avec version N+1
   ```
4. **Configurer** :
   ```properties
   ebithex.security.encryption.active-version=<N+1>
   ebithex.security.encryption.versions[<N+1>]=<nouvelle-clé>
   # Garder l'ancienne clé en versions[N] pour déchiffrer les données existantes
   ```
5. **Redémarrer** l'application et **lancer** la rotation :
   ```bash
   curl -X POST https://api.ebithex.io/api/internal/admin/key-rotation \
     -H "Authorization: Bearer <super-admin-token>"
   ```
6. **Notifier** : CNIL/DPA locale, BCEAO, marchands impactés (selon réglementation)
7. **Documenter** l'incident et mettre à jour le DPIA

---

### 4.4 Indisponibilité Redis (RTO 5 min)

**Impact** : Rate limiting désactivé (fail-open), OTP 2FA indisponible temporairement

**Runbook** :

```bash
# 1. Vérifier l'état Sentinel
redis-cli -h redis-sentinel -p 26379 SENTINEL masters

# 2. Si basculement manuel nécessaire
redis-cli -h redis-sentinel -p 26379 SENTINEL failover mymaster

# 3. Vérifier les connexions de l'application
curl https://api.ebithex.io/api/actuator/health | jq '.components.redis'
```

---

### 4.5 Indisponibilité d'un opérateur Mobile Money

**Impact** : Paiements vers cet opérateur échouent (circuit breaker OPEN)

**Comportement automatique** :
- Circuit breaker `operator-payment` s'ouvre après 50% d'échecs sur 10 appels
- Les paiements vers cet opérateur retournent immédiatement `503 OPERATOR_UNAVAILABLE`
- Le circuit se referme automatiquement après 30 secondes (demi-ouverture)

**Runbook manuel** :

```bash
# 1. Vérifier l'état des circuit breakers
curl https://api.ebithex.io/api/actuator/health | jq '.components.circuitBreakers'

# 2. Vérifier les métriques opérateur (Grafana)
# Dashboard: "Ebithex Operators" → panel "Circuit Breaker States"

# 3. Si l'opérateur revient, le circuit se referme automatiquement
# Si l'opérateur est en maintenance longue, communiquer aux marchands

# 4. Pour forcer la réinitialisation (si nécessaire) :
curl -X POST https://api.ebithex.io/api/actuator/circuitbreakers/operator-payment/reset
```

---

## 5. Tests de reprise

| Test | Fréquence | Procédure |
|---|---|---|
| Restauration PITR sur environnement de test | Mensuelle | Automatisée via pipeline CI/CD |
| Failover PostgreSQL Primary → Standby | Trimestrielle | Manuel + observation métriques |
| Test de sauvegarde complète (pg_restore) | Mensuelle | Vérifier intégrité + nombre lignes |
| Simulation panne Redis | Trimestrielle | `redis-cli DEBUG SLEEP 60` sur primary |
| Rotation clés AES en pré-production | Semestrielle | Procédure complète (génération → rotation → validation) |
| Exercice DRP complet | Annuelle | Simulation panne datacenter sur staging |

---

## 6. Contacts d'urgence

| Rôle | Responsabilité | Canal |
|---|---|---|
| SRE On-call | Incident technique | PagerDuty + Slack #incidents |
| DPO (Data Protection Officer) | Violation de données personnelles | Email direct + WhatsApp |
| RSSI | Compromission clés / sécurité | Téléphone + Signal |
| BCEAO — CENTIF | Interruption > 4h, compromission données financières | Contact réglementaire officiel |
| Support opérateurs (MTN, Orange, Wave) | Indisponibilité opérateur | Contacts techniques dédiés |

---

## 7. Procédure de communication de crise

### Niveaux de sévérité

| Niveau | Définition | Communication |
|---|---|---|
| **P0 — Critique** | Perte de données financières, RTO dépassé, compromission clés | Notif immédiate BCEAO + marchands + presse (si public) |
| **P1 — Majeur** | Indisponibilité > 30 min, toutes transactions échouent | Status page + email marchands dans les 15 min |
| **P2 — Modéré** | Opérateur partiel indisponible, dégradation partielle | Status page dans les 30 min |
| **P3 — Mineur** | Latence augmentée, non-critique | Monitoring interne uniquement |

### Template de notification marchands (P0/P1)

```
Objet: [Ebithex] Incident en cours — [DATE/HEURE UTC]

Nous vous informons d'un incident affectant notre plateforme.

Impact: [décrire l'impact précis]
Début: [timestamp UTC]
Statut actuel: En cours d'investigation / Résolution en cours

Actions prises:
- [action 1]
- [action 2]

Prochaine mise à jour: dans [N] minutes sur https://status.ebithex.io

Nous nous excusons pour la gêne occasionnée.
L'équipe Ebithex
```

---

## 8. Conformité réglementaire

| Exigence | Source | Statut |
|---|---|---|
| RTO ≤ 4 heures | BCEAO instruction 008-05-2015 | ✅ RTO cible 15 min (PostgreSQL) |
| Sauvegarde données 5 ans | UEMOA / RGPD | ✅ S3 Glacier 5 ans |
| Notification violations données | RGPD Art. 33 (72h) | ✅ Procédure P0 définie |
| Notification BCEAO incidents majeurs | Instruction BCEAO | ✅ Contact réglementaire défini |
| Tests PRA documentés | Bonne pratique ISO 22301 | ✅ Tests trimestriels planifiés |
| Chiffrement sauvegardes | RGPD Art. 32 | ✅ S3 SSE-KMS |

---

*Document maintenu par l'équipe Infrastructure Ebithex. Révision annuelle obligatoire ou après tout incident P0/P1.*