CREATE TABLE IF NOT EXISTS telemetry_players (
    server_id TEXT NOT NULL,
    player_id TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK(revision > 0 AND revision < 9223372036854775807),
    observation_json TEXT NOT NULL,
    needs_publish INTEGER NOT NULL DEFAULT 1,
    queue_order INTEGER NOT NULL DEFAULT 0,
    rejection_category TEXT,
    PRIMARY KEY(server_id,player_id)
);
CREATE TABLE IF NOT EXISTS telemetry_sessions (
    server_id TEXT NOT NULL,
    player_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    joined_at TEXT NOT NULL,
    left_at TEXT,
    changed_revision INTEGER NOT NULL,
    acknowledged_revision INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(server_id,player_id,session_id),
    FOREIGN KEY(server_id,player_id) REFERENCES telemetry_players(server_id,player_id)
);
CREATE TABLE IF NOT EXISTS telemetry_addresses (
    server_id TEXT NOT NULL,
    player_id TEXT NOT NULL,
    address TEXT NOT NULL,
    last_seen_at TEXT NOT NULL,
    PRIMARY KEY(server_id,player_id,address),
    FOREIGN KEY(server_id,player_id) REFERENCES telemetry_players(server_id,player_id)
);
CREATE TABLE IF NOT EXISTS telemetry_pending (
    server_id TEXT NOT NULL,
    player_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    snapshot_json TEXT NOT NULL,
    PRIMARY KEY(server_id,player_id),
    FOREIGN KEY(server_id,player_id) REFERENCES telemetry_players(server_id,player_id)
);
CREATE INDEX IF NOT EXISTS telemetry_queue ON telemetry_players(server_id,needs_publish);
CREATE INDEX IF NOT EXISTS telemetry_fair_queue ON telemetry_players(server_id,queue_order);
CREATE INDEX IF NOT EXISTS telemetry_session_queue ON telemetry_sessions(server_id,player_id,acknowledged_revision,changed_revision);
