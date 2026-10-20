-- UTC timestamps are ISO-8601 Instants. Compare parsed Instants, not variable-precision strings.
CREATE TABLE players (
    uuid TEXT PRIMARY KEY,
    last_name TEXT NOT NULL,
    first_seen TEXT NOT NULL,
    last_seen TEXT NOT NULL
);
CREATE INDEX players_last_name ON players(last_name COLLATE NOCASE);

CREATE TABLE punishments (
    id TEXT PRIMARY KEY,
    reference TEXT NOT NULL UNIQUE,
    target_id TEXT NOT NULL,
    target_name TEXT NOT NULL,
    type TEXT NOT NULL CHECK(type IN ('BAN','MUTE','WARNING','KICK','COMMUNITY_SERVICE')),
    reason TEXT NOT NULL,
    issuer TEXT NOT NULL,
    source TEXT NOT NULL CHECK(source IN ('IN_GAME','CONSOLE','WEBSITE')),
    issued_at TEXT NOT NULL,
    expires_at TEXT,
    status TEXT NOT NULL CHECK(status IN ('ACTIVE','EXPIRED','REVOKED','COMPLETED')),
    revision INTEGER NOT NULL CHECK(revision > 0),
    appeal_url TEXT,
    revoked_at TEXT,
    revoked_by TEXT,
    revocation_reason TEXT
);
CREATE INDEX punishments_target ON punishments(target_id, issued_at);
CREATE INDEX punishments_active ON punishments(status, expires_at);
CREATE UNIQUE INDEX one_active_punishment_per_type ON punishments(target_id,type)
    WHERE status='ACTIVE' AND type IN ('BAN','MUTE','COMMUNITY_SERVICE');

CREATE TABLE community_service (
    id TEXT PRIMARY KEY,
    punishment_id TEXT NOT NULL UNIQUE REFERENCES punishments(id),
    target_id TEXT NOT NULL,
    required INTEGER NOT NULL CHECK(required > 0),
    completed INTEGER NOT NULL DEFAULT 0 CHECK(completed >= 0),
    tool TEXT NOT NULL CHECK(tool IN ('WOODEN_PICKAXE','STONE_PICKAXE')),
    state TEXT NOT NULL CHECK(state IN ('ASSIGNED','SNAPSHOTTED','ACTIVE','WORK_COMPLETE','RESTORING','RECOVERY','COMPLETED')),
    started_at TEXT,
    completed_at TEXT
);
CREATE UNIQUE INDEX one_unfinished_sentence ON community_service(target_id) WHERE state<>'COMPLETED';

CREATE TABLE punishment_events (
    event_id TEXT PRIMARY KEY,
    punishment_id TEXT NOT NULL REFERENCES punishments(id),
    event_type TEXT NOT NULL,
    actor TEXT NOT NULL,
    created_at TEXT NOT NULL,
    revision INTEGER NOT NULL,
    payload TEXT NOT NULL
);
CREATE INDEX punishment_events_punishment ON punishment_events(punishment_id,revision);

CREATE TABLE pending_notifications (
    player_id TEXT NOT NULL,
    punishment_id TEXT NOT NULL REFERENCES punishments(id),
    notification_type TEXT NOT NULL CHECK(notification_type IN ('WARNING','KICK')),
    delivery_state TEXT NOT NULL CHECK(delivery_state IN ('PENDING','DELIVERED','EXECUTING','EXECUTED','FAILED','EXPIRED')),
    delivered_at TEXT,
    acknowledged_at TEXT,
    valid_until TEXT,
    last_error TEXT,
    PRIMARY KEY(punishment_id,notification_type)
);
CREATE INDEX notifications_pending ON pending_notifications(player_id,delivery_state);

CREATE TABLE appeal_requests (
    punishment_id TEXT PRIMARY KEY REFERENCES punishments(id),
    provisioning_id TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING'
);

CREATE TABLE sync_outbox (
    event_id TEXT PRIMARY KEY REFERENCES punishment_events(event_id),
    event_type TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TEXT NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    last_attempt TEXT,
    delivery_state TEXT NOT NULL DEFAULT 'PENDING',
    next_attempt TEXT
);
CREATE INDEX outbox_pending ON sync_outbox(delivery_state,created_at);

CREATE TABLE remote_inbox (
    action_id TEXT PRIMARY KEY,
    source_revision INTEGER,
    received_at TEXT NOT NULL,
    validation_status TEXT NOT NULL,
    execution_status TEXT NOT NULL,
    execution_at TEXT,
    result_json TEXT NOT NULL,
    payload_hash TEXT NOT NULL
);

CREATE TABLE sync_state (
    server_id TEXT PRIMARY KEY,
    remote_cursor TEXT,
    last_success TEXT,
    snapshot_revision TEXT
);

-- The private ledger covers local retries as well as website actions. It never enters the outbox.
CREATE TABLE local_actions (
    action_id TEXT PRIMARY KEY,
    payload_hash TEXT NOT NULL,
    result_json TEXT NOT NULL,
    completed_at TEXT NOT NULL
);
