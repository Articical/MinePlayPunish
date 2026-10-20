"""Read-only assertions for the explicitly labelled localhost smoke fixtures.

Run after a clean staging shutdown: python scripts/verify-staging-store.py
Never point this script at a production database; it creates no data and changes none.
"""
import json
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
database = root / "run/plugins/MineplayPunishments/punishments.db"
connection = sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)
connection.row_factory = sqlite3.Row
assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
assert connection.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
assert not connection.execute("PRAGMA foreign_key_check").fetchall()
expected = {
    "101": ("BAN", "ACTIVE"),
    "102": ("BAN", "EXPIRED"),
    "103": ("MUTE", "REVOKED"),
    "104": ("MUTE", "EXPIRED"),
    "105": ("WARNING", "ACTIVE"),
    "106": ("KICK", "COMPLETED"),
}
rows = []
for suffix, state in expected.items():
    target = "00000000-0000-4000-8000-000000000" + suffix
    found = connection.execute(
        "SELECT reference, type, status, revision FROM punishments WHERE target_id=?", (target,)
    ).fetchall()
    assert len(found) == 1, (suffix, len(found))
    assert (found[0]["type"], found[0]["status"]) == state, (suffix, dict(found[0]))
    rows.append(dict(found[0]))
assert connection.execute(
    "SELECT delivery_state FROM pending_notifications WHERE notification_type='KICK'"
).fetchone()[0] == "FAILED"
warning = connection.execute(
    "SELECT delivery_state, acknowledged_at FROM pending_notifications WHERE notification_type='WARNING'"
).fetchone()
assert tuple(warning) == ("PENDING", None)
assert connection.execute("SELECT count(*) FROM community_service").fetchone()[0] == 0
assert connection.execute("SELECT count(*) FROM punishments").fetchone()[0] == 6
assert connection.execute("SELECT count(*) FROM sync_outbox WHERE delivery_state='DELIVERED'").fetchone()[0] == 0
events = connection.execute("SELECT count(*) FROM sync_outbox WHERE delivery_state='PENDING'").fetchone()[0]
assert events >= 10
report = {
    "database": "run/plugins/MineplayPunishments/punishments.db",
    "integrity": "ok", "journalMode": "wal", "foreignKeyViolations": 0,
    "punishments": rows, "offlineKick": "FAILED", "offlineWarning": "PENDING_UNACKNOWLEDGED",
    "unconfiguredQuarrySentences": 0, "durablePendingEvents": events,
}
connection.close()
(root / "verification").mkdir(exist_ok=True)
(root / "verification/staging-store.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, indent=2))
