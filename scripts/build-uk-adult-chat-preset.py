"""Generate the copy/paste YAML from reviewed literal and regex sources (standard library only)."""

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PRESETS = ROOT / "docs" / "presets"


def quoted(value):
    return "'" + value.replace("'", "''") + "'"


def render():
    groups = json.loads((PRESETS / "uk-adult-literals.json").read_text(encoding="utf-8"))
    rules = json.loads((PRESETS / "uk-adult-regex.json").read_text(encoding="utf-8"))["rules"]
    words = [word for group in groups.values() for word in group]
    assert len(words) == len(set(words)), "Duplicate literal"
    assert len(words) <= 256, "Too many literals for 1.1.0"
    assert len(rules) <= 32, "Too many regexes for 1.1.0"
    assert all(0 < len(rule["pattern"]) <= 256 for rule in rules), "Invalid regex source length"
    assert len({rule["id"] for rule in rules}) == len(rules), "Duplicate rule identifier"
    lines = [
        "# Mineplay UK adult-community moderation preset, reviewed 2026-10-20.",
        "# Requires MineplayPunishments 1.1.0; no new JAR needed.",
        "# REPLACE ONLY your existing chat-filter section in the plugin config.yml.",
        "# Do not replace the rest of config.yml. This is not a standalone plugin config.",
        "# Apply with /mp admin reload. Existing failed regex edits must be repaired.",
        "# Ordinary profanity remains allowed. This is not a Mojang/Xbox compliance certificate.",
        "# Literal forms are exact whole words/phrases; obfuscation coverage is the 32 named rules.",
        "# Some quoted/reclaimed slurs still match. Report those privately to staff.",
        "# No keyword filter guarantees zero false positives or catches every evasion.",
        "# Source, exclusions, tests and policy notes: docs/chat-filter-uk-adult.md",
        "chat-filter:",
        "  enabled: true",
        f"  # {len(words)} literal entries; includes plurals and compounds.",
        "  banned-words:",
    ]
    for category, entries in groups.items():
        lines.append("    # " + category)
        lines.extend("    - " + quoted(entry) for entry in entries)
    lines.extend([
        f"  # {len(rules)} RE2/J rules; keep YAML single quotes and backslashes unchanged.",
        "  # NFKC/case folding/FORMAT removal already happen in the plugin.",
        "  # Explicit Unicode boundaries prevent matches inside longer ordinary words.",
        "  # Anchored rules deliberately leave longer/ambiguous messages for staff review.",
        "  regex-patterns:",
    ])
    for rule in rules:
        lines.append("    # " + rule["id"] + ": " + rule["comment"])
        lines.append("    - " + quoted(rule["pattern"]))
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Fail if the YAML needs regeneration")
    args = parser.parse_args()
    output = PRESETS / "chat-filter-uk-adult.yml"
    content = render()
    if args.check:
        assert output.read_text(encoding="utf-8") == content, "Preset is stale; regenerate it"
        print("Preset matches its reviewed source files.")
    else:
        output.write_text(content, encoding="utf-8")
        print(output)
