# Ebithex — Authentification à deux facteurs (2FA)

## Table des matières

1. [Vue d'ensemble](#1-vue-densemble)
2. [Politique d'obligation par rôle](#2-politique-dobligation-par-rôle)
3. [Flux d'authentification](#3-flux-dauthentification)
4. [Enforcement technique](#4-enforcement-technique)
5. [Protection bruteforce](#5-protection-bruteforce)
6. [Codes d'erreur](#6-codes-derreur)
7. [Référence API](#7-référence-api)

---

## 1. Vue d'ensemble

Ebithex implémente une authentification à deux facteurs (2FA) par OTP email pour les utilisateurs back-office (`StaffUser`). Le OTP est généré côté serveur, stocké dans Redis avec un TTL de 5 minutes, et transmis par email à l'adresse associée au compte.

---

## 2. Politique d'obligation par rôle

| Rôle | 2FA obligatoire | Peut être désactivé |
|------|----------------|---------------------|
| `SUPER_ADMIN` | Oui | Non |
| `ADMIN` | Oui | Non |
| `COUNTRY_ADMIN` | Non (optionnel) | Oui |
| `FINANCE` | Non (optionnel) | Oui |
| `RECONCILIATION` | Non (optionnel) | Oui |
| `COMPLIANCE` | Non (optionnel) | Oui |
| `SUPPORT` | Non (optionnel) | Oui |

> **Justification** : Les rôles ADMIN et SUPER_ADMIN disposent d'un accès complet à l'infrastructure, aux données financières et aux configurations de sécurité. La 2FA est une exigence non-négociable pour ces rôles, indépendamment des préférences utilisateur.

---

## 3. Flux d'authentification

### 3.1 Sans 2FA (rôles non-obligatoires avec flag désactivé)

```
POST /internal/auth/login
  { "email": "...", "password": "..." }
      │
      ▼
  Vérification mot de passe
      │
      ▼
  Réponse : { "accessToken": "...", "staffUserId": "...", "role": "..." }
```

### 3.2 Avec 2FA (2FA activé ou rôle obligatoire)

```
POST /internal/auth/login
  { "email": "...", "password": "..." }
      │
      ▼
  Vérification mot de passe
      │
      ▼
  Réponse : { "requiresTwoFactor": "true", "tempToken": "...", "expiresInSeconds": "300" }
      │
      ▼
  [Utilisateur reçoit l'OTP par email]
      │
      ▼
POST /internal/auth/verify-otp
  { "tempToken": "...", "code": "123456" }
      │
      ▼
  Réponse : { "accessToken": "...", "staffUserId": "...", "role": "..." }
```

---

## 4. Enforcement technique

### Création de compte (`StaffUserService.create()`)

Pour ADMIN et SUPER_ADMIN, `twoFactorEnabled` est forcé à `true` quelle que soit la valeur envoyée dans la requête :

```java
boolean twoFactorEnabled = isTwoFactorMandatory(request.role()) || request.twoFactorEnabled();
```

### Mise à jour de compte (`StaffUserService.update()`)

Tenter de désactiver le 2FA pour un rôle obligatoire lève une exception `TWO_FACTOR_REQUIRED` :

```java
if (Boolean.FALSE.equals(request.twoFactorEnabled()) && isTwoFactorMandatory(user.getRole())) {
    throw new EbithexException(ErrorCode.TWO_FACTOR_REQUIRED,
        "Le 2FA ne peut pas être désactivé pour le rôle " + user.getRole().name());
}
```

### Connexion (`StaffUserService.login()`)

Si un compte ADMIN/SUPER_ADMIN a `twoFactorEnabled = false` en base (incohérence), le flag est automatiquement corrigé lors de la connexion :

```java
boolean requires2FA = user.isTwoFactorEnabled() || isTwoFactorMandatory(user.getRole());
if (requires2FA) {
    if (!user.isTwoFactorEnabled()) {
        user.setTwoFactorEnabled(true);
        staffUserRepository.save(user); // auto-correction DB
        log.warn("2FA auto-activé pour {} ({}) — flag manquant en DB corrigé", ...);
    }
    // → flow OTP
}
```

---

## 5. Protection bruteforce

### 5.1 Mécanisme

Chaque tentative de vérification OTP incrémente un compteur Redis lié au `tempToken` :

```
otp:attempts:{tempToken}  →  entier (0..N)  |  TTL : 5 minutes
```

À partir de **5 tentatives incorrectes**, le `tempToken` est invalidé et l'utilisateur doit effectuer un nouveau login complet.

```
Tentative 1–4 (code incorrect) → OTP_INVALID + compteur incrémenté
Tentative 5 (code incorrect)   → LOGIN_ATTEMPTS_EXCEEDED + token supprimé
Tentative réussie (code ok)    → JWT final + suppression de toutes les clés Redis
```

### 5.2 Clés Redis impliquées

| Clé Redis | Contenu | TTL |
|-----------|---------|-----|
| `otp:code:{email}` | Code OTP à 6 chiffres | 5 min |
| `otp:token:{tempToken}` | Email de l'utilisateur | 5 min |
| `otp:attempts:{tempToken}` | Nombre de tentatives échouées | 5 min |

Toutes les clés sont supprimées simultanément après un succès ou après dépassement du seuil.

### 5.3 Justification du seuil

5 tentatives représentent un compromis entre usabilité (erreur de saisie possible) et sécurité (espace de 10⁶ codes à 6 chiffres — force brute impossible en 5 essais). Après invalidation, un nouvel OTP avec nouveau `tempToken` doit être demandé via le login.

---

## 6. Codes d'erreur

| Code | HTTP | Description |
|------|------|-------------|
| `TWO_FACTOR_REQUIRED` | 403 | Tentative de désactivation du 2FA sur un rôle obligatoire |
| `INVALID_CREDENTIALS` | 401 | Email ou mot de passe incorrect |
| `ACCOUNT_DISABLED` | 403 | Compte back-office désactivé |
| `OTP_INVALID` | 401 | Code OTP incorrect (compteur incrémenté) |
| `OTP_EXPIRED` | 401 | Token temporaire expiré (> 5 minutes) |
| `LOGIN_ATTEMPTS_EXCEEDED` | 429 | 5 tentatives OTP incorrectes — tempToken invalidé, nouveau login requis |

---

## 7. Référence API

### `POST /internal/auth/login`

**Corps** :
```json
{ "email": "admin@ebithex.com", "password": "..." }
```

**Réponse sans 2FA** (200) :
```json
{ "accessToken": "eyJ...", "staffUserId": "uuid", "role": "SUPPORT" }
```

**Réponse avec 2FA** (200) :
```json
{ "requiresTwoFactor": "true", "tempToken": "uuid-temp", "expiresInSeconds": "300" }
```

---

### `POST /internal/auth/verify-otp`

**Corps** :
```json
{ "tempToken": "uuid-temp", "code": "847392" }
```

**Réponse** (200) :
```json
{ "accessToken": "eyJ...", "staffUserId": "uuid", "role": "ADMIN" }
```

---

*Dernière mise à jour : 5 avril 2026*
