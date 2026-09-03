-- Enable foreign key enforcement in SQLite
PRAGMA foreign_keys = ON;

-- 1. Users table: System users with authentication details and roles.
CREATE TABLE IF NOT EXISTS users (
    user_id TEXT PRIMARY KEY,
    username TEXT NOT NULL UNIQUE,
    name TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('ADMIN', 'VENDOR', 'USER')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2. Vendors table: Vendor organizations linked 1:1 to an owner user.
CREATE TABLE IF NOT EXISTS vendors (
    vendor_id TEXT PRIMARY KEY,
    owner_user_id TEXT NOT NULL UNIQUE,
    vendor_name TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_user_id) REFERENCES users(user_id)
);

-- 3. Sources table: Log sources associated with a vendor (e.g., FIREWALL, WEB_APP, DATABASE, SENSOR).
CREATE TABLE IF NOT EXISTS sources (
    source_id TEXT PRIMARY KEY,
    vendor_id TEXT NOT NULL,
    source_name TEXT NOT NULL,
    source_type TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (vendor_id) REFERENCES vendors(vendor_id)
);

-- 4. Credentials table: Ingestion API key hashes linked to a specific log source.
CREATE TABLE IF NOT EXISTS credentials (
    credential_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL,
    key_hash TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (source_id) REFERENCES sources(source_id)
);

-- 5. Mapping Versions table: Versioned JSON schema mapping configurations for log sources.
CREATE TABLE IF NOT EXISTS mapping_versions (
    mapping_id TEXT PRIMARY KEY,
    source_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    mapping_json TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('CANDIDATE', 'ACTIVE', 'RETIRED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (source_id) REFERENCES sources(source_id),
    UNIQUE (source_id, version)
);

-- 6. Onboarding Requests table: Requests for onboarding new vendors, sources, or schema updates.
CREATE TABLE IF NOT EXISTS onboarding_requests (
    request_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    source_id TEXT,
    request_type TEXT NOT NULL CHECK (request_type IN ('NEW_VENDOR', 'NEW_SOURCE', 'SCHEMA_UPDATE')),
    sample_metadata TEXT,
    status TEXT NOT NULL CHECK (status IN ('SUBMITTED', 'AI_ANALYSIS', 'HUMAN_REVIEW', 'APPROVED', 'REJECTED')),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id),
    FOREIGN KEY (source_id) REFERENCES sources(source_id)
);

-- 7. Notifications table: User notifications for system events and onboarding status updates.
CREATE TABLE IF NOT EXISTS notifications (
    notification_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    title TEXT NOT NULL,
    message TEXT NOT NULL,
    read BOOLEAN NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- 8. To store Embedding
CREATE TABLE IF NOT EXISTS mapping_embeddings (
    embedding_id    TEXT PRIMARY KEY,
    canonical_field TEXT NOT NULL,
    model_name      TEXT NOT NULL,
    model_version   TEXT NOT NULL,
    embedding       BLOB NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (canonical_field, model_name, model_version)
);

-- Index for fast credential key hash lookup on /v1/events ingestion requests
CREATE INDEX IF NOT EXISTS idx_credentials_key_hash ON credentials(key_hash);

-- Index for fast active mapping resolution per log source
CREATE INDEX IF NOT EXISTS idx_mapping_versions_source_status ON mapping_versions(source_id, status);
