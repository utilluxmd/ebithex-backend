-- ============================================================
--  Ebithex Backend — Schéma complet (état final)
--  V1__init.sql
--
--  Script unique de création à partir de zéro (greenfield).
--  Représente l'état final après fusion de V1 → V19 + V2 + V3 :
--    · Tables créées directement dans leur forme définitive
--    · Plus d'ALTER / RENAME / UPDATE intermédiaires
--    · Données de référence (devises, règles de frais) incluses
--    · Colonnes version (@Version JPA) intégrées nativement
--    · Contraintes CHECK sur les statuts enum intégrées nativement
--    · Schéma sandbox créé en fin de script (LIKE … INCLUDING ALL)
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ────────────────────────────────────────────────────────────
--  MERCHANTS
--  Comptes marchands Ebithex.
--  Remarque : les clés API ne sont plus stockées ici — elles
--  vivent dans la table api_keys (relation 1-N).
-- ────────────────────────────────────────────────────────────
CREATE TABLE merchants (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name         VARCHAR(100)  NOT NULL,
    email                 VARCHAR(255)  NOT NULL UNIQUE,
    hashed_secret         VARCHAR(255)  NOT NULL,
    webhook_url           VARCHAR(500),
    country               VARCHAR(3),
    default_currency      VARCHAR(5)    DEFAULT 'XOF',
    custom_fee_rate       DECIMAL(5,2),
    active                BOOLEAN       DEFAULT TRUE,
    kyc_verified          BOOLEAN       DEFAULT FALSE,
    agency_id             UUID,
    kyc_status            VARCHAR(20)   NOT NULL DEFAULT 'NONE',
    kyc_rejection_reason  VARCHAR(500),
    kyc_submitted_at      TIMESTAMP,
    kyc_reviewed_at       TIMESTAMP,
    test_mode             BOOLEAN       NOT NULL DEFAULT FALSE,
    daily_payment_limit   DECIMAL(15,2),
    monthly_payment_limit DECIMAL(15,2),
    created_at            TIMESTAMP     DEFAULT NOW(),
    updated_at            TIMESTAMP     DEFAULT NOW(),

    CONSTRAINT chk_kyc_status CHECK (kyc_status IN ('NONE','PENDING','APPROVED','REJECTED'))
);

CREATE INDEX idx_merchants_agency     ON merchants(agency_id)   WHERE agency_id IS NOT NULL;
CREATE INDEX idx_merchants_kyc_status ON merchants(kyc_status);
CREATE INDEX idx_merchants_country    ON merchants(country);

COMMENT ON TABLE  merchants                        IS 'Comptes marchands Ebithex. Les clés API sont dans la table api_keys.';
COMMENT ON COLUMN merchants.hashed_secret          IS 'Mot de passe haché (BCrypt). Jamais stocké en clair.';
COMMENT ON COLUMN merchants.test_mode              IS 'Si TRUE, les transactions sont simulées (pas d''appel opérateur réel).';
COMMENT ON COLUMN merchants.daily_payment_limit    IS 'Plafond journalier (NULL = pas de limite). Somme PENDING+PROCESSING+SUCCESS.';
COMMENT ON COLUMN merchants.monthly_payment_limit  IS 'Plafond mensuel (NULL = pas de limite).';
COMMENT ON COLUMN merchants.kyc_status             IS 'NONE | PENDING | APPROVED | REJECTED';
COMMENT ON COLUMN merchants.kyc_rejection_reason   IS 'Motif du rejet — visible par le marchand.';
COMMENT ON COLUMN merchants.agency_id              IS 'Référence agence — non-null uniquement pour le rôle AGENT (hors scope MVP).';

-- ────────────────────────────────────────────────────────────
--  DEVISES
--  Table de référence des devises africaines + internationales.
-- ────────────────────────────────────────────────────────────
CREATE TABLE currencies (
    code           VARCHAR(5)   PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    symbol         VARCHAR(10),
    decimal_places INT          NOT NULL DEFAULT 2,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Zone UEMOA / CEMAC
INSERT INTO currencies (code, name, symbol, decimal_places) VALUES
    ('XOF', 'Franc CFA UEMOA', 'F CFA', 0),
    ('XAF', 'Franc CFA CEMAC', 'F CFA', 0);

-- Afrique de l'Ouest
INSERT INTO currencies (code, name, symbol, decimal_places) VALUES
    ('NGN', 'Naira nigérian',          '₦',   2),
    ('GHS', 'Cedi ghanéen',            'GH₵', 2),
    ('GMD', 'Dalasi gambien',          'D',   2),
    ('SLL', 'Leone sierra-léonais',    'Le',  2),
    ('LRD', 'Dollar libérien',         'L$',  2),
    ('GNF', 'Franc guinéen',           'FG',  0),
    ('CVE', 'Escudo cap-verdien',      '$',   2);

-- Afrique de l'Est
INSERT INTO currencies (code, name, symbol, decimal_places) VALUES
    ('KES', 'Shilling kényan',         'KSh', 2),
    ('TZS', 'Shilling tanzanien',      'TSh', 2),
    ('UGX', 'Shilling ougandais',      'USh', 0),
    ('RWF', 'Franc rwandais',          'FRw', 0),
    ('ETB', 'Birr éthiopien',          'Br',  2),
    ('SOS', 'Shilling somalien',       'Sh',  2),
    ('DJF', 'Franc djiboutien',        'Fdj', 0),
    ('ERN', 'Nakfa érythréen',         'Nfk', 2),
    ('SCR', 'Roupie des Seychelles',   '₨',   2),
    ('MUR', 'Roupie mauricienne',      '₨',   2),
    ('SSP', 'Pound sud-soudanais',     '£',   2);

-- Afrique Australe
INSERT INTO currencies (code, name, symbol, decimal_places) VALUES
    ('ZAR', 'Rand sud-africain',       'R',   2),
    ('ZMW', 'Kwacha zambien',          'ZK',  2),
    ('MWK', 'Kwacha malawien',         'MK',  2),
    ('MZN', 'Metical mozambicain',     'MT',  2),
    ('AOA', 'Kwanza angolais',         'Kz',  2),
    ('BWP', 'Pula botswanais',         'P',   2),
    ('NAD', 'Dollar namibien',         'N$',  2),
    ('SZL', 'Lilangeni swazilandais',  'L',   2),
    ('LSL', 'Loti lesothan',           'L',   2);

-- Afrique du Nord
INSERT INTO currencies (code, name, symbol, decimal_places) VALUES
    ('MAD', 'Dirham marocain',         'MAD', 2),
    ('EGP', 'Livre égyptienne',        'E£',  2),
    ('DZD', 'Dinar algérien',          'DA',  2),
    ('TND', 'Dinar tunisien',          'DT',  3),
    ('LYD', 'Dinar libyen',            'LD',  3),
    ('SDG', 'Livre soudanaise',        'LS',  2);

-- Îles africaines
INSERT INTO currencies (code, name, symbol, decimal_places) VALUES
    ('MGA', 'Ariary malgache',         'Ar',  2),
    ('STN', 'Dobra de São Tomé',       'Db',  2),
    ('KMF', 'Franc comorien',          'FC',  0),
    ('CDF', 'Franc congolais',         'FC',  2);

-- International
INSERT INTO currencies (code, name, symbol, decimal_places) VALUES
    ('USD', 'Dollar américain',        '$',   2),
    ('EUR', 'Euro',                    '€',   2),
    ('GBP', 'Livre sterling',          '£',   2);

-- ────────────────────────────────────────────────────────────
--  TAUX DE CHANGE (cache)
-- ────────────────────────────────────────────────────────────
CREATE TABLE exchange_rates (
    id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    from_currency VARCHAR(5)     NOT NULL REFERENCES currencies(code),
    to_currency   VARCHAR(5)     NOT NULL REFERENCES currencies(code),
    rate          DECIMAL(20,8)  NOT NULL,
    source        VARCHAR(50)    NOT NULL,
    fetched_at    TIMESTAMP      NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_exchange_rate UNIQUE (from_currency, to_currency)
);

CREATE INDEX idx_exchange_rates_pair    ON exchange_rates(from_currency, to_currency);
CREATE INDEX idx_exchange_rates_fetched ON exchange_rates(fetched_at);

-- ────────────────────────────────────────────────────────────
--  STAFF USERS (comptes internes back-office)
--  Anciennement "operators" — renommé pour éviter l'ambiguïté
--  avec les opérateurs Mobile Money (MTN, Orange, Wave…).
-- ────────────────────────────────────────────────────────────
CREATE TABLE staff_users (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL UNIQUE,
    hashed_password     VARCHAR(255) NOT NULL,
    role                VARCHAR(30)  NOT NULL,
    country             VARCHAR(3),
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    two_factor_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP    DEFAULT NOW(),
    updated_at          TIMESTAMP    DEFAULT NOW(),

    CONSTRAINT chk_staff_role CHECK (
        role IN ('SUPPORT','FINANCE','RECONCILIATION','COUNTRY_ADMIN','ADMIN','SUPER_ADMIN','COMPLIANCE')
    ),
    CONSTRAINT chk_country_admin_has_country CHECK (
        role != 'COUNTRY_ADMIN' OR country IS NOT NULL
    )
);

CREATE INDEX idx_staff_email  ON staff_users(email);
CREATE INDEX idx_staff_role   ON staff_users(role);
CREATE INDEX idx_staff_active ON staff_users(active) WHERE active = TRUE;

COMMENT ON TABLE  staff_users                     IS 'Comptes internes Ebithex (back-office). À ne pas confondre avec les opérateurs Mobile Money.';
COMMENT ON COLUMN staff_users.role                IS 'SUPPORT | FINANCE | RECONCILIATION | COUNTRY_ADMIN | ADMIN | SUPER_ADMIN | COMPLIANCE';
COMMENT ON COLUMN staff_users.country             IS 'Code ISO-3166-1 alpha-2 — requis pour COUNTRY_ADMIN, ignoré pour les autres rôles.';
COMMENT ON COLUMN staff_users.two_factor_enabled  IS 'Si TRUE, une vérification OTP par email est requise après le mot de passe.';

-- ────────────────────────────────────────────────────────────
--  FLOAT OPÉRATEURS (trésorerie Ebithex par opérateur)
-- ────────────────────────────────────────────────────────────
CREATE TABLE operator_floats (
    operator_type         VARCHAR(30)   PRIMARY KEY,
    balance               DECIMAL(18,2) NOT NULL DEFAULT 0.00,
    low_balance_threshold DECIMAL(18,2) NOT NULL DEFAULT 100000.00,
    version               BIGINT        NOT NULL DEFAULT 0,
    updated_at            TIMESTAMP     DEFAULT NOW(),

    CONSTRAINT chk_float_balance_non_negative CHECK (balance >= 0)
);

COMMENT ON TABLE  operator_floats                        IS 'Solde de trésorerie Ebithex chez chaque opérateur Mobile Money.';
COMMENT ON COLUMN operator_floats.balance                IS 'Fonds disponibles chez l''opérateur.';
COMMENT ON COLUMN operator_floats.low_balance_threshold  IS 'Seuil d''alerte bas — déclenche un avertissement si balance < seuil.';
COMMENT ON COLUMN operator_floats.version                IS 'Verrou optimiste JPA.';

-- ────────────────────────────────────────────────────────────
--  TRANSACTIONS (paiements entrants — collection)
-- ────────────────────────────────────────────────────────────
CREATE TABLE transactions (
    id                      UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    ebithex_reference       VARCHAR(50)   NOT NULL UNIQUE,
    merchant_reference      VARCHAR(100)  NOT NULL,
    merchant_id             UUID          NOT NULL REFERENCES merchants(id),
    amount                  DECIMAL(15,2) NOT NULL,
    fee_amount              DECIMAL(15,2),
    net_amount              DECIMAL(15,2),
    currency                VARCHAR(5)    DEFAULT 'XOF',
    phone_number            TEXT          NOT NULL,
    phone_number_index      VARCHAR(64),
    operator                VARCHAR(30)   NOT NULL,
    status                  VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    operator_reference      VARCHAR(255),
    operator_refund_reference VARCHAR(255),
    metadata                TEXT,
    customer_name           VARCHAR(100),
    customer_email          VARCHAR(255),
    description             VARCHAR(255),
    failure_reason          VARCHAR(500),
    refunded_amount         DECIMAL(15,2),
    pii_purged_at           TIMESTAMP,
    expires_at              TIMESTAMP,
    created_at              TIMESTAMP     DEFAULT NOW(),
    updated_at              TIMESTAMP     DEFAULT NOW(),
    version                 BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_tx_merchant_reference UNIQUE (merchant_id, merchant_reference),
    CONSTRAINT chk_tx_status CHECK (status IN (
        'PENDING','PROCESSING','SUCCESS','FAILED',
        'EXPIRED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED'
    ))
);

CREATE INDEX idx_tx_merchant     ON transactions(merchant_id);
CREATE INDEX idx_tx_operator_ref ON transactions(operator_reference);
CREATE INDEX idx_tx_status       ON transactions(status);
CREATE INDEX idx_tx_phone        ON transactions(phone_number_index);
CREATE INDEX idx_tx_created      ON transactions(created_at DESC);
CREATE INDEX idx_tx_pii_purge    ON transactions(created_at) WHERE pii_purged_at IS NULL;
CREATE INDEX idx_tx_partially_refunded ON transactions(merchant_id, created_at)
    WHERE status = 'PARTIALLY_REFUNDED';

COMMENT ON COLUMN transactions.phone_number            IS 'Numéro de téléphone chiffré AES-256-GCM. Jamais stocké en clair.';
COMMENT ON COLUMN transactions.phone_number_index      IS 'HMAC-SHA256 du numéro normalisé E.164 — permet les requêtes filtrées.';
COMMENT ON COLUMN transactions.operator_refund_reference IS 'Référence de remboursement renvoyée par l''opérateur lors d''un refund API.';
COMMENT ON COLUMN transactions.refunded_amount         IS 'Montant cumulatif remboursé. NULL = aucun remboursement. Quand refunded_amount = amount → statut REFUNDED.';
COMMENT ON COLUMN transactions.pii_purged_at           IS 'Date de pseudonymisation des données PII (phone_number).';
COMMENT ON COLUMN transactions.version                 IS 'Version JPA (@Version) pour le verrouillage optimiste — incrémenté à chaque UPDATE.';

-- ────────────────────────────────────────────────────────────
--  OUTBOX (livraison garantie d'événements asynchrones)
-- ────────────────────────────────────────────────────────────
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   UUID         NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        TEXT         NOT NULL,
    status         VARCHAR(20)  DEFAULT 'PENDING',
    created_at     TIMESTAMP    DEFAULT NOW(),
    dispatched_at  TIMESTAMP,

    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','DISPATCHED','FAILED'))
);

CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at)
    WHERE status = 'PENDING';

COMMENT ON TABLE  outbox_events             IS 'Outbox transactionnel — garantit la livraison des événements même en cas de crash.';
COMMENT ON COLUMN outbox_events.status      IS 'PENDING | DISPATCHED | FAILED';
COMMENT ON COLUMN outbox_events.aggregate_type IS 'Exemple : Transaction, Payout, Merchant.';

-- ────────────────────────────────────────────────────────────
--  CALLBACKS OPÉRATEUR (déduplication)
-- ────────────────────────────────────────────────────────────
CREATE TABLE operator_processed_callbacks (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    operator            VARCHAR(30)  NOT NULL,
    operator_reference  VARCHAR(255) NOT NULL,
    received_at         TIMESTAMP    DEFAULT NOW(),

    CONSTRAINT uq_operator_callback UNIQUE (operator, operator_reference)
);

CREATE INDEX idx_operator_callback_ref ON operator_processed_callbacks(operator, operator_reference);

COMMENT ON TABLE operator_processed_callbacks IS 'Table de déduplication des callbacks opérateurs. Prévient le double traitement.';

-- ────────────────────────────────────────────────────────────
--  WEBHOOKS
-- ────────────────────────────────────────────────────────────
CREATE TABLE webhook_endpoints (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id    UUID         NOT NULL REFERENCES merchants(id),
    url            VARCHAR(500) NOT NULL,
    signing_secret VARCHAR(100) NOT NULL,
    active         BOOLEAN      DEFAULT TRUE,
    created_at     TIMESTAMP    DEFAULT NOW()
);

CREATE TABLE webhook_endpoint_events (
    webhook_endpoint_id UUID         REFERENCES webhook_endpoints(id),
    events              VARCHAR(50),
    PRIMARY KEY (webhook_endpoint_id, events)
);

CREATE TABLE webhook_deliveries (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id      UUID         NOT NULL REFERENCES webhook_endpoints(id),
    transaction_id   UUID,
    event            VARCHAR(50),
    payload          TEXT,
    http_status      INTEGER,
    attempt_count    INTEGER      DEFAULT 0,
    delivered        BOOLEAN      DEFAULT FALSE,
    last_error       TEXT,
    next_retry_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  DEFAULT NOW(),
    delivered_at     TIMESTAMPTZ,
    dead_lettered    BOOLEAN      DEFAULT FALSE,
    dead_lettered_at TIMESTAMPTZ
);

CREATE INDEX idx_webhook_retry ON webhook_deliveries(delivered, next_retry_at)
    WHERE delivered = FALSE AND dead_lettered = FALSE AND attempt_count < 5;

CREATE INDEX idx_webhook_dlq ON webhook_deliveries(dead_lettered, dead_lettered_at)
    WHERE dead_lettered = TRUE;

COMMENT ON TABLE  webhook_endpoints                IS 'Endpoints de notification configurés par les marchands.';
COMMENT ON TABLE  webhook_deliveries               IS 'Tentatives de livraison des événements webhook avec retry automatique.';
COMMENT ON COLUMN webhook_deliveries.transaction_id IS 'UUID de la transaction ou du payout source (pas de FK — deux tables référencées).';

-- ────────────────────────────────────────────────────────────
--  PAYOUTS (décaissements — cash-out)
-- ────────────────────────────────────────────────────────────
CREATE TABLE payouts (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    ebithex_reference   VARCHAR(50)   NOT NULL UNIQUE,
    merchant_reference  VARCHAR(100)  NOT NULL,
    merchant_id         UUID          NOT NULL REFERENCES merchants(id),
    amount              DECIMAL(15,2) NOT NULL,
    fee_amount          DECIMAL(15,2),
    net_amount          DECIMAL(15,2),
    currency            VARCHAR(5)    DEFAULT 'XOF',
    phone_number        TEXT          NOT NULL,
    phone_number_index  VARCHAR(64),
    operator            VARCHAR(30)   NOT NULL,
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    operator_reference  VARCHAR(255),
    description         VARCHAR(255),
    beneficiary_name    VARCHAR(100),
    metadata            TEXT,
    failure_reason      VARCHAR(500),
    pii_purged_at       TIMESTAMP,
    expires_at          TIMESTAMP,
    created_at          TIMESTAMP     DEFAULT NOW(),
    updated_at          TIMESTAMP     DEFAULT NOW(),
    version             BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT uq_payout_merchant_reference UNIQUE (merchant_id, merchant_reference),
    CONSTRAINT chk_payout_status CHECK (status IN (
        'PENDING','PROCESSING','SUCCESS','FAILED',
        'EXPIRED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED'
    ))
);

CREATE INDEX idx_po_merchant     ON payouts(merchant_id);
CREATE INDEX idx_po_operator_ref ON payouts(operator_reference);
CREATE INDEX idx_po_status       ON payouts(status);
CREATE INDEX idx_po_phone        ON payouts(phone_number_index);
CREATE INDEX idx_po_created      ON payouts(created_at DESC);
CREATE INDEX idx_payout_pii_purge ON payouts(created_at) WHERE pii_purged_at IS NULL;

COMMENT ON TABLE  payouts              IS 'Décaissements (cash-out) depuis Ebithex vers portefeuille Mobile Money.';
COMMENT ON COLUMN payouts.phone_number IS 'Numéro bénéficiaire chiffré AES-256-GCM.';
COMMENT ON COLUMN payouts.net_amount   IS 'Montant reçu par le bénéficiaire = amount - fee_amount.';
COMMENT ON COLUMN payouts.pii_purged_at IS 'Date de pseudonymisation des données PII (phone_number).';
COMMENT ON COLUMN payouts.version      IS 'Version JPA (@Version) pour le verrouillage optimiste.';

-- ────────────────────────────────────────────────────────────
--  BULK PAYOUTS (décaissements groupés)
-- ────────────────────────────────────────────────────────────
CREATE TABLE bulk_payouts (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ebithex_batch_reference  VARCHAR(50)  NOT NULL UNIQUE,
    merchant_batch_reference VARCHAR(100) NOT NULL,
    merchant_id              UUID         NOT NULL REFERENCES merchants(id),
    label                    VARCHAR(255),
    total_items              INTEGER      NOT NULL DEFAULT 0,
    processed_items          INTEGER      NOT NULL DEFAULT 0,
    success_items            INTEGER      NOT NULL DEFAULT 0,
    failed_items             INTEGER      NOT NULL DEFAULT 0,
    status                   VARCHAR(30)  NOT NULL DEFAULT 'PENDING',
    created_at               TIMESTAMP    DEFAULT NOW(),
    updated_at               TIMESTAMP    DEFAULT NOW(),

    CONSTRAINT uq_bulk_payout_merchant_reference UNIQUE (merchant_id, merchant_batch_reference),
    CONSTRAINT chk_bulk_payout_status CHECK (status IN (
        'PENDING','PROCESSING','COMPLETED','FAILED','PARTIALLY_COMPLETED'
    ))
);

CREATE INDEX idx_bpo_merchant ON bulk_payouts(merchant_id);
CREATE INDEX idx_bpo_status   ON bulk_payouts(status);
CREATE INDEX idx_bpo_created  ON bulk_payouts(created_at DESC);

CREATE TABLE bulk_payout_items (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    bulk_payout_id      UUID          NOT NULL REFERENCES bulk_payouts(id) ON DELETE CASCADE,
    item_index          INTEGER       NOT NULL,
    merchant_reference  VARCHAR(100)  NOT NULL,
    phone_number        TEXT          NOT NULL,
    phone_number_index  VARCHAR(64),
    amount              DECIMAL(15,2) NOT NULL,
    currency            VARCHAR(5)    DEFAULT 'XOF',
    operator            VARCHAR(30),
    description         VARCHAR(255),
    beneficiary_name    VARCHAR(100),
    status              VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    payout_id           UUID          REFERENCES payouts(id),
    ebithex_reference   VARCHAR(50),
    failure_reason      VARCHAR(500),
    created_at          TIMESTAMP     DEFAULT NOW(),
    updated_at          TIMESTAMP     DEFAULT NOW(),

    CONSTRAINT chk_bulk_item_status CHECK (status IN (
        'PENDING','PROCESSING','SUCCESS','FAILED',
        'EXPIRED','CANCELLED','REFUNDED','PARTIALLY_REFUNDED'
    ))
);

CREATE INDEX idx_bpoi_batch  ON bulk_payout_items(bulk_payout_id);
CREATE INDEX idx_bpoi_payout ON bulk_payout_items(payout_id);
CREATE INDEX idx_bpoi_status ON bulk_payout_items(status);

COMMENT ON TABLE bulk_payouts      IS 'Lots de décaissements (bulk cash-out) : masse salariale, remboursements groupés.';
COMMENT ON TABLE bulk_payout_items IS 'Bénéficiaires individuels d''un lot. Chaque item génère un Payout (PO-).';

-- ────────────────────────────────────────────────────────────
--  WALLETS (portefeuilles marchands — un par devise)
-- ────────────────────────────────────────────────────────────
CREATE TABLE wallets (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id       UUID          NOT NULL REFERENCES merchants(id),
    available_balance DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    pending_balance   DECIMAL(15,2) NOT NULL DEFAULT 0.00,
    currency          VARCHAR(5)    NOT NULL DEFAULT 'XOF' REFERENCES currencies(code),
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMP     DEFAULT NOW(),
    updated_at        TIMESTAMP     DEFAULT NOW(),

    CONSTRAINT uq_wallet_merchant_currency UNIQUE (merchant_id, currency),
    CONSTRAINT chk_available_balance_non_negative CHECK (available_balance >= 0),
    CONSTRAINT chk_pending_balance_non_negative   CHECK (pending_balance   >= 0)
);

CREATE INDEX idx_wallets_merchant          ON wallets(merchant_id);
CREATE INDEX idx_wallets_merchant_currency ON wallets(merchant_id, currency);

CREATE TABLE wallet_transactions (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    wallet_id         UUID          NOT NULL REFERENCES wallets(id),
    merchant_id       UUID          NOT NULL REFERENCES merchants(id),
    type              VARCHAR(30)   NOT NULL,
    amount            DECIMAL(15,2) NOT NULL,
    balance_after     DECIMAL(15,2) NOT NULL,
    ebithex_reference VARCHAR(50)   NOT NULL,
    description       VARCHAR(255),
    created_at        TIMESTAMP     DEFAULT NOW(),

    CONSTRAINT uq_wallet_tx_ref_type UNIQUE (ebithex_reference, type)
);

CREATE INDEX idx_wallet_tx_wallet   ON wallet_transactions(wallet_id);
CREATE INDEX idx_wallet_tx_merchant ON wallet_transactions(merchant_id);
CREATE INDEX idx_wallet_tx_created  ON wallet_transactions(created_at DESC);
CREATE INDEX idx_wallet_tx_ref      ON wallet_transactions(ebithex_reference);

COMMENT ON TABLE  wallets                    IS 'Portefeuilles marchands — un wallet par (merchant_id, currency). Créé automatiquement lors du premier paiement.';
COMMENT ON COLUMN wallets.available_balance  IS 'Fonds disponibles — retrait ou nouveau payout possible.';
COMMENT ON COLUMN wallets.pending_balance    IS 'Fonds bloqués par des décaissements en cours.';
COMMENT ON COLUMN wallets.version            IS 'Verrou optimiste JPA — prévient les corruptions de balance concurrentes.';
COMMENT ON COLUMN wallets.currency           IS 'Code devise ISO 4217 — référence currencies(code). Un marchand peut avoir plusieurs wallets.';
COMMENT ON TABLE  wallet_transactions        IS 'Grand livre immuable. Chaque ligne = un mouvement de balance.';
COMMENT ON COLUMN wallet_transactions.type   IS 'CREDIT_PAYMENT | DEBIT_PAYOUT | CONFIRM_PAYOUT | REFUND_PAYOUT | WITHDRAWAL | B2B_TRANSFER_DEBIT | B2B_TRANSFER_CREDIT';
COMMENT ON COLUMN wallet_transactions.balance_after IS 'Snapshot du solde available après ce mouvement — facilite les audits.';

-- ────────────────────────────────────────────────────────────
--  DEMANDES DE RETRAIT
-- ────────────────────────────────────────────────────────────
CREATE TABLE withdrawal_requests (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id      UUID          NOT NULL REFERENCES merchants(id),
    amount           DECIMAL(15,2) NOT NULL,
    currency         VARCHAR(5)    NOT NULL DEFAULT 'XOF',
    reference        VARCHAR(50)   NOT NULL UNIQUE,
    description      VARCHAR(500),
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    reviewed_by      UUID          REFERENCES staff_users(id),
    rejection_reason VARCHAR(500),
    reviewed_at      TIMESTAMP,
    created_at       TIMESTAMP     DEFAULT NOW(),
    updated_at       TIMESTAMP     DEFAULT NOW(),

    CONSTRAINT chk_withdrawal_status CHECK (status IN ('PENDING','APPROVED','REJECTED','EXECUTED')),
    CONSTRAINT chk_withdrawal_amount  CHECK (amount > 0)
);

CREATE INDEX idx_withdrawal_merchant ON withdrawal_requests(merchant_id);
CREATE INDEX idx_withdrawal_status   ON withdrawal_requests(status);
CREATE INDEX idx_withdrawal_created  ON withdrawal_requests(created_at DESC);

COMMENT ON TABLE withdrawal_requests IS
    'Demandes de retrait marchand. PENDING = en attente validation finance. '
    'APPROVED = solde débité, exécution en cours. REJECTED = refusée. EXECUTED = finalisée.';

-- ────────────────────────────────────────────────────────────
--  AUDIT LOGS (journal immuable des actions admin)
-- ────────────────────────────────────────────────────────────
CREATE TABLE audit_logs (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id    UUID,
    operator_email VARCHAR(255),
    action         VARCHAR(60)  NOT NULL,
    entity_type    VARCHAR(50),
    entity_id      VARCHAR(100),
    details        TEXT,
    ip_address     VARCHAR(45),
    created_at     TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX idx_audit_operator   ON audit_logs(operator_id);
CREATE INDEX idx_audit_entity     ON audit_logs(entity_type, entity_id);
CREATE INDEX idx_audit_action     ON audit_logs(action);
CREATE INDEX idx_audit_created_at ON audit_logs(created_at DESC);

COMMENT ON TABLE  audit_logs        IS 'Journal immuable des actions back-office. Jamais modifié après insertion.';
COMMENT ON COLUMN audit_logs.action IS 'KYC_APPROVED | KYC_REJECTED | MERCHANT_ACTIVATED | MERCHANT_DEACTIVATED | FLOAT_ADJUSTED | WEBHOOK_DLQ_RETRY | OPERATOR_LOGIN | OPERATOR_LOGOUT';

-- ────────────────────────────────────────────────────────────
--  RÈGLES DE FRAIS DYNAMIQUES
--  Priorité de résolution (décroissante) :
--    1. Marchand + opérateur spécifique
--    2. Marchand spécifique (tous opérateurs)
--    3. Opérateur spécifique (tous marchands)
--    4. Pays
--    5. Global (fallback)
-- ────────────────────────────────────────────────────────────
CREATE TABLE fee_rules (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100)  NOT NULL,
    description     TEXT,
    merchant_id     UUID          REFERENCES merchants(id) ON DELETE CASCADE,
    operator        VARCHAR(30),
    country         VARCHAR(3),
    fee_type        VARCHAR(15)   NOT NULL DEFAULT 'PERCENTAGE'
                    CHECK (fee_type IN ('PERCENTAGE','FLAT','MIXED')),
    percentage_rate DECIMAL(6,4),
    flat_amount     DECIMAL(15,2),
    min_fee         DECIMAL(15,2),
    max_fee         DECIMAL(15,2),
    min_amount      DECIMAL(15,2),
    max_amount      DECIMAL(15,2),
    priority        INTEGER       NOT NULL DEFAULT 0,
    active          BOOLEAN       NOT NULL DEFAULT TRUE,
    valid_from      TIMESTAMP     NOT NULL DEFAULT NOW(),
    valid_until     TIMESTAMP,
    created_at      TIMESTAMP     DEFAULT NOW(),
    updated_at      TIMESTAMP     DEFAULT NOW()
);

CREATE INDEX idx_fee_rules_merchant  ON fee_rules(merchant_id) WHERE merchant_id IS NOT NULL;
CREATE INDEX idx_fee_rules_operator  ON fee_rules(operator)    WHERE operator IS NOT NULL;
CREATE INDEX idx_fee_rules_country   ON fee_rules(country)     WHERE country IS NOT NULL;
CREATE INDEX idx_fee_rules_priority  ON fee_rules(priority DESC, created_at);
CREATE INDEX idx_fee_rules_active    ON fee_rules(active)      WHERE active = TRUE;

COMMENT ON TABLE  fee_rules          IS 'Règles tarifaires dynamiques. Priorité : marchand+opérateur > marchand > opérateur > pays > global.';
COMMENT ON COLUMN fee_rules.priority IS 'Plus haut = prioritaire lors de la résolution. Permet les promotions temporaires.';
COMMENT ON COLUMN fee_rules.fee_type IS 'PERCENTAGE: taux %. FLAT: montant fixe. MIXED: taux % + montant fixe.';

-- Règles par défaut par opérateur (avec les bons codes opérateur)
INSERT INTO fee_rules (name, description, operator, fee_type, percentage_rate, priority) VALUES
    ('MTN MoMo CI — Taux par défaut',
     'Taux appliqué à tous les paiements MTN MoMo — Côte d''Ivoire',
     'MTN_MOMO_CI',    'PERCENTAGE', 1.50, 0),
    ('Orange Money CI — Taux par défaut',
     'Taux appliqué à tous les paiements Orange Money — Côte d''Ivoire',
     'ORANGE_MONEY_CI', 'PERCENTAGE', 1.75, 0),
    ('Wave CI — Taux par défaut',
     'Taux appliqué à tous les paiements Wave',
     'WAVE_CI',         'PERCENTAGE', 1.00, 0),
    ('Taux global par défaut',
     'Taux de repli si aucune règle spécifique ne correspond',
     NULL,              'PERCENTAGE', 1.50, -10);

-- ────────────────────────────────────────────────────────────
--  KYC DOCUMENTS
-- ────────────────────────────────────────────────────────────
CREATE TABLE kyc_documents (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID         NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    document_type   VARCHAR(50)  NOT NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'UPLOADED',
    storage_key     TEXT         NOT NULL,
    file_name       TEXT         NOT NULL,
    content_type    VARCHAR(100) NOT NULL,
    file_size_bytes BIGINT       NOT NULL,
    checksum_sha256 VARCHAR(64)  NOT NULL,
    provider_name   VARCHAR(50),
    provider_ref    VARCHAR(255),
    reviewer_notes  TEXT,
    reviewed_by     VARCHAR(255),
    reviewed_at     TIMESTAMPTZ,
    uploaded_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ,
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT chk_kyc_doc_status CHECK (status IN (
        'UPLOADED','PROCESSING','ACCEPTED','REJECTED','EXPIRED'
    ))
);

CREATE INDEX idx_kyc_docs_merchant      ON kyc_documents(merchant_id);
CREATE INDEX idx_kyc_docs_status        ON kyc_documents(status);
CREATE INDEX idx_kyc_docs_merchant_type ON kyc_documents(merchant_id, document_type);

COMMENT ON TABLE  kyc_documents              IS 'Documents KYC soumis par les marchands pour vérification d''identité.';
COMMENT ON COLUMN kyc_documents.storage_key  IS 'Clé de stockage S3 ou local — jamais le contenu du fichier.';
COMMENT ON COLUMN kyc_documents.deleted_at   IS 'Soft delete — NULL = document actif.';

-- ────────────────────────────────────────────────────────────
--  API KEYS
--  Un marchand peut avoir plusieurs clés (LIVE et TEST).
--  La valeur brute n'est jamais stockée — seulement son SHA-256.
-- ────────────────────────────────────────────────────────────
CREATE TABLE api_keys (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id            UUID         NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    key_hash               VARCHAR(64)  NOT NULL UNIQUE,
    key_hint               VARCHAR(8)   NOT NULL,
    prefix                 VARCHAR(20)  NOT NULL,
    type                   VARCHAR(10)  NOT NULL CHECK (type IN ('LIVE','TEST')),
    label                  VARCHAR(100),
    scopes                 TEXT         NOT NULL DEFAULT 'FULL_ACCESS',
    allowed_ips            TEXT,
    expires_at             TIMESTAMPTZ,
    last_used_at           TIMESTAMPTZ,
    active                 BOOLEAN      NOT NULL DEFAULT TRUE,
    previous_hash          VARCHAR(64),
    previous_expires_at    TIMESTAMPTZ,
    aging_reminder_sent_at TIMESTAMPTZ,
    rotation_required_days INTEGER,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_api_keys_merchant ON api_keys(merchant_id);
CREATE INDEX idx_api_keys_hash     ON api_keys(key_hash);
CREATE INDEX idx_api_keys_active   ON api_keys(active) WHERE active = TRUE;

COMMENT ON TABLE  api_keys                     IS 'Clés API marchands. La valeur brute n''est jamais stockée.';
COMMENT ON COLUMN api_keys.key_hash            IS 'SHA-256 hex de la clé brute.';
COMMENT ON COLUMN api_keys.key_hint            IS '4 derniers caractères de la clé brute (ex: "...xK3a") pour affichage.';
COMMENT ON COLUMN api_keys.prefix              IS '"ap_live_" ou "ap_test_" — identifie visuellement le type.';
COMMENT ON COLUMN api_keys.scopes              IS 'Scopes séparés par des virgules. Ex : "PAYMENTS_WRITE,PAYMENTS_READ" ou "FULL_ACCESS".';
COMMENT ON COLUMN api_keys.allowed_ips         IS 'IPs autorisées séparées par des virgules. NULL = aucune restriction.';
COMMENT ON COLUMN api_keys.previous_hash       IS 'Hash de l''ancienne clé — valide jusqu''à previous_expires_at (grace period après rotation).';
COMMENT ON COLUMN api_keys.rotation_required_days IS 'Nombre de jours max avant désactivation automatique. NULL = pas de contrainte.';

-- ────────────────────────────────────────────────────────────
--  AML ALERTS (Anti-Money Laundering)
-- ────────────────────────────────────────────────────────────
CREATE TABLE aml_alerts (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id     UUID          NOT NULL REFERENCES merchants(id),
    transaction_id  UUID          REFERENCES transactions(id),
    rule_code       VARCHAR(50)   NOT NULL,
    severity        VARCHAR(10)   NOT NULL DEFAULT 'MEDIUM',
    status          VARCHAR(20)   NOT NULL DEFAULT 'OPEN',
    details         TEXT,
    amount          DECIMAL(15,2),
    currency        VARCHAR(5),
    created_at      TIMESTAMP     DEFAULT NOW(),
    reviewed_at     TIMESTAMP,
    reviewed_by     VARCHAR(100),
    resolution_note TEXT,

    CONSTRAINT chk_aml_severity CHECK (severity IN ('LOW','MEDIUM','HIGH','CRITICAL')),
    CONSTRAINT chk_aml_status   CHECK (status   IN ('OPEN','UNDER_REVIEW','CLEARED','REPORTED'))
);

CREATE INDEX idx_aml_merchant    ON aml_alerts(merchant_id);
CREATE INDEX idx_aml_status      ON aml_alerts(status, created_at DESC);
CREATE INDEX idx_aml_transaction ON aml_alerts(transaction_id);
CREATE INDEX idx_aml_rule        ON aml_alerts(rule_code);

COMMENT ON TABLE  aml_alerts           IS 'Alertes AML générées automatiquement par les règles de détection.';
COMMENT ON COLUMN aml_alerts.rule_code IS 'VELOCITY_HOURLY | VELOCITY_DAILY | VELOCITY_WEEKLY | HIGH_AMOUNT | STRUCTURING | HIGH_RISK_COUNTRY';
COMMENT ON COLUMN aml_alerts.severity  IS 'LOW | MEDIUM | HIGH | CRITICAL';
COMMENT ON COLUMN aml_alerts.status    IS 'OPEN | UNDER_REVIEW | CLEARED | REPORTED';

-- ────────────────────────────────────────────────────────────
--  DISPUTES (Litiges / Chargebacks)
-- ────────────────────────────────────────────────────────────
CREATE TABLE disputes (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    ebithex_reference VARCHAR(50)  NOT NULL,
    merchant_id      UUID          NOT NULL REFERENCES merchants(id),
    transaction_id   UUID          REFERENCES transactions(id),
    reason           VARCHAR(30)   NOT NULL,
    description      TEXT,
    amount           DECIMAL(15,2),
    currency         VARCHAR(5),
    status           VARCHAR(30)   NOT NULL DEFAULT 'OPEN',
    opened_at        TIMESTAMP     DEFAULT NOW(),
    resolved_at      TIMESTAMP,
    resolved_by      VARCHAR(100),
    resolution_notes TEXT,
    evidence_urls    TEXT,

    CONSTRAINT chk_dispute_status CHECK (status IN ('OPEN','UNDER_REVIEW','RESOLVED_MERCHANT','RESOLVED_CUSTOMER','CANCELLED')),
    CONSTRAINT chk_dispute_reason CHECK (reason IN ('UNAUTHORIZED','DUPLICATE','NOT_RECEIVED','WRONG_AMOUNT','OTHER'))
);

CREATE INDEX idx_dispute_merchant    ON disputes(merchant_id);
CREATE INDEX idx_dispute_status      ON disputes(status, opened_at DESC);
CREATE INDEX idx_dispute_ebithex_ref ON disputes(ebithex_reference);

COMMENT ON TABLE  disputes               IS 'Litiges et chargebacks ouverts par les marchands.';
COMMENT ON COLUMN disputes.reason        IS 'UNAUTHORIZED | DUPLICATE | NOT_RECEIVED | WRONG_AMOUNT | OTHER';
COMMENT ON COLUMN disputes.status        IS 'OPEN | UNDER_REVIEW | RESOLVED_MERCHANT | RESOLVED_CUSTOMER | CANCELLED';
COMMENT ON COLUMN disputes.evidence_urls IS 'Tableau JSON d''URLs de preuves téléversées (captures, relevés, etc.).';

-- ────────────────────────────────────────────────────────────
--  SETTLEMENT (Cycles de règlement quotidien)
-- ────────────────────────────────────────────────────────────
CREATE TABLE settlement_batches (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_reference   VARCHAR(50)   NOT NULL UNIQUE,
    operator          VARCHAR(30)   NOT NULL,
    currency          VARCHAR(5)    NOT NULL,
    period_start      TIMESTAMP     NOT NULL,
    period_end        TIMESTAMP     NOT NULL,
    transaction_count INTEGER       NOT NULL DEFAULT 0,
    gross_amount      DECIMAL(15,2) NOT NULL DEFAULT 0,
    fee_amount        DECIMAL(15,2) NOT NULL DEFAULT 0,
    net_amount        DECIMAL(15,2) NOT NULL DEFAULT 0,
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    settled_at        TIMESTAMP,
    created_at        TIMESTAMP     DEFAULT NOW(),

    CONSTRAINT chk_settlement_status CHECK (status IN ('PENDING','PROCESSING','SETTLED','FAILED'))
);

CREATE INDEX idx_settlement_operator ON settlement_batches(operator, period_start DESC);
CREATE INDEX idx_settlement_status   ON settlement_batches(status);
CREATE INDEX idx_settlement_period   ON settlement_batches(period_start, period_end);

CREATE TABLE settlement_entries (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id       UUID          NOT NULL REFERENCES settlement_batches(id),
    transaction_id UUID          NOT NULL,
    entry_type     VARCHAR(20)   NOT NULL,
    amount         DECIMAL(15,2) NOT NULL,
    fee_amount     DECIMAL(15,2),
    operator       VARCHAR(30)   NOT NULL,
    currency       VARCHAR(5)    NOT NULL,
    created_at     TIMESTAMP     DEFAULT NOW(),

    CONSTRAINT chk_entry_type CHECK (entry_type IN ('COLLECTION','DISBURSEMENT'))
);

CREATE INDEX idx_settlement_entry_batch ON settlement_entries(batch_id);
CREATE INDEX idx_settlement_entry_tx    ON settlement_entries(transaction_id);

COMMENT ON TABLE  settlement_batches              IS 'Lots de règlement quotidien Ebithex → opérateurs.';
COMMENT ON COLUMN settlement_batches.gross_amount IS 'Montant brut collecté via cet opérateur sur la période.';
COMMENT ON COLUMN settlement_batches.fee_amount   IS 'Commissions Ebithex retenues.';
COMMENT ON COLUMN settlement_batches.net_amount   IS 'Montant net dû à l''opérateur = gross - fee.';
COMMENT ON TABLE  settlement_entries              IS 'Lignes individuelles d''un lot de règlement (1 ligne = 1 transaction).';

-- ────────────────────────────────────────────────────────────
--  SANCTIONS ENTRIES (listes OFAC, ONU, UE, ECOWAS)
-- ────────────────────────────────────────────────────────────
CREATE TABLE sanctions_entries (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    list_name    VARCHAR(30)  NOT NULL,
    entity_name  VARCHAR(255) NOT NULL,
    aliases      TEXT,
    country_code VARCHAR(5),
    entity_type  VARCHAR(30),
    is_active    BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    DEFAULT NOW(),

    CONSTRAINT chk_sanctions_list CHECK (list_name IN (
        'OFAC_SDN','UN_CONSOLIDATED','EU_CONSOLIDATED','ECOWAS_LOCAL','CUSTOM'
    ))
);

CREATE INDEX idx_sanctions_list    ON sanctions_entries(list_name);
CREATE INDEX idx_sanctions_country ON sanctions_entries(country_code);
CREATE INDEX idx_sanctions_name    ON sanctions_entries(entity_name);
CREATE INDEX idx_sanctions_active  ON sanctions_entries(is_active);

COMMENT ON TABLE  sanctions_entries             IS 'Entrées des listes de sanctions réglementaires.';
COMMENT ON COLUMN sanctions_entries.list_name   IS 'OFAC_SDN | UN_CONSOLIDATED | EU_CONSOLIDATED | ECOWAS_LOCAL | CUSTOM';
COMMENT ON COLUMN sanctions_entries.entity_name IS 'Nom officiel de l''entité sanctionnée.';
COMMENT ON COLUMN sanctions_entries.aliases     IS 'Noms alternatifs séparés par des virgules.';
COMMENT ON COLUMN sanctions_entries.entity_type IS 'INDIVIDUAL | ENTITY | VESSEL | AIRCRAFT';
COMMENT ON COLUMN sanctions_entries.is_active   IS 'FALSE = entrée retirée de la liste sans suppression physique.';

-- ────────────────────────────────────────────────────────────
--  SANCTIONS SYNC LOG (journal de synchronisation)
-- ────────────────────────────────────────────────────────────
CREATE TABLE sanctions_sync_log (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    list_name        VARCHAR(30)  NOT NULL,
    synced_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    status           VARCHAR(20)  NOT NULL,
    entries_imported INT          NOT NULL DEFAULT 0,
    error_message    TEXT,
    duration_ms      BIGINT,

    CONSTRAINT chk_sync_list   CHECK (list_name IN (
        'OFAC_SDN','UN_CONSOLIDATED','EU_CONSOLIDATED','ECOWAS_LOCAL','CUSTOM'
    )),
    CONSTRAINT chk_sync_status CHECK (status IN ('SUCCESS','FAILED','PARTIAL'))
);

CREATE INDEX idx_sync_log_list ON sanctions_sync_log(list_name);
CREATE INDEX idx_sync_log_at   ON sanctions_sync_log(synced_at DESC);

COMMENT ON TABLE  sanctions_sync_log                  IS 'Journal des synchronisations des listes de sanctions réglementaires.';
COMMENT ON COLUMN sanctions_sync_log.entries_imported IS 'Nombre d''entrées insérées lors de cette synchronisation.';
COMMENT ON COLUMN sanctions_sync_log.status           IS 'SUCCESS | FAILED | PARTIAL';

-- ────────────────────────────────────────────────────────────
--  RÉCONCILIATION OPÉRATEUR
-- ────────────────────────────────────────────────────────────
CREATE TABLE operator_statements (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    operator          VARCHAR(50)  NOT NULL,
    statement_date    DATE         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    total_lines       INT          NOT NULL DEFAULT 0,
    matched_lines     INT          NOT NULL DEFAULT 0,
    discrepancy_lines INT          NOT NULL DEFAULT 0,
    imported_by       UUID,
    imported_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    reconciled_at     TIMESTAMP,
    notes             TEXT,

    CONSTRAINT uq_operator_statement UNIQUE (operator, statement_date)
);

CREATE TABLE operator_statement_lines (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    statement_id        UUID          NOT NULL REFERENCES operator_statements(id) ON DELETE CASCADE,
    operator_reference  VARCHAR(255)  NOT NULL,
    ebithex_reference   VARCHAR(255),
    amount              NUMERIC(15,2) NOT NULL,
    currency            VARCHAR(10)   NOT NULL,
    operator_status     VARCHAR(50)   NOT NULL,
    operator_date       TIMESTAMP,
    discrepancy_type    VARCHAR(30),
    discrepancy_note    TEXT,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_op_stmt_operator_date  ON operator_statements(operator, statement_date);
CREATE INDEX idx_op_stmt_status         ON operator_statements(status);
CREATE INDEX idx_stmt_lines_operator_ref ON operator_statement_lines(operator_reference);
CREATE INDEX idx_stmt_lines_statement_id ON operator_statement_lines(statement_id);
CREATE INDEX idx_stmt_lines_discrepancy  ON operator_statement_lines(statement_id, discrepancy_type)
    WHERE discrepancy_type IS NOT NULL AND discrepancy_type != 'MATCHED';

COMMENT ON TABLE  operator_statements              IS 'Relevés opérateurs importés pour réconciliation.';
COMMENT ON TABLE  operator_statement_lines         IS 'Lignes individuelles d''un relevé opérateur.';
COMMENT ON COLUMN operator_statement_lines.discrepancy_type IS 'MATCHED | AMOUNT_MISMATCH | MISSING_IN_EBITHEX | MISSING_IN_OPERATOR | STATUS_MISMATCH';

-- ────────────────────────────────────────────────────────────
--  SCHÉMA SANDBOX
--
--  Isolation physique du trafic de test.
--  Les tables transactionnelles sont dupliquées dans le schéma
--  sandbox (structure + index, sans FK inter-schémas).
--  Les tables partagées (marchands, staff_users, fee_rules,
--  kyc_documents, api_keys, webhooks, aml_alerts, sanctions,
--  operator_statements, settlement_batches, disputes,
--  audit_logs) restent dans public uniquement.
--
--  Le pool sandbox utilise : SET search_path TO sandbox, public
--
--  INCLUDING ALL (PostgreSQL 14+) : copie colonnes, DEFAULT,
--  contraintes CHECK, index et commentaires — garantit que le
--  schéma sandbox reste strictement identique au schéma public.
-- ────────────────────────────────────────────────────────────
CREATE SCHEMA IF NOT EXISTS sandbox;

CREATE TABLE sandbox.transactions        (LIKE public.transactions        INCLUDING ALL);
CREATE TABLE sandbox.payouts             (LIKE public.payouts             INCLUDING ALL);
CREATE TABLE sandbox.wallets             (LIKE public.wallets             INCLUDING ALL);
CREATE TABLE sandbox.wallet_transactions (LIKE public.wallet_transactions INCLUDING ALL);
CREATE TABLE sandbox.withdrawal_requests (LIKE public.withdrawal_requests INCLUDING ALL);
CREATE TABLE sandbox.bulk_payouts        (LIKE public.bulk_payouts        INCLUDING ALL);
CREATE TABLE sandbox.bulk_payout_items   (LIKE public.bulk_payout_items   INCLUDING ALL);
CREATE TABLE sandbox.webhook_deliveries  (LIKE public.webhook_deliveries  INCLUDING ALL);
