# UK adult-community chat-filter preset

This preset allows ordinary profanity and targets selected slurs, direct abuse,
extremist praise, self-harm encouragement and solicitation. It is designed for
MineplayPunishments **1.1.0**. It does not need a new JAR or a plugin code change.

Use the complete [ready-to-paste YAML](presets/chat-filter-uk-adult.yml).
It contains **182 literal entries and 32 regex rules**. The literal list includes
plurals and compounds; it is not a count of distinct types of abuse.
The separate regex rules cover priority evasions and specific
directed phrases. The literal list does not automatically acquire regex evasion
handling.

## Install

1. Back up `plugins/MineplayPunishments/config.yml`.
2. Replace its existing **`chat-filter:` section only** with the complete contents
   of [chat-filter-uk-adult.yml](presets/chat-filter-uk-adult.yml). Keep the other
   plugin settings. This file is a configuration fragment, not a replacement for
   the entire plugin configuration.
3. Run `/mp admin reload` and confirm success. No restart is needed if 1.1.0 is
   already running. A refused reload leaves the prior rules active; repair the
   file before restarting.

Keep the YAML single quotes and backslashes unchanged. The preset uses the
1.1.0 limit of 32 regex rules. Additional regex rules require replacing or
combining existing ones; simply appending a 33rd rule will reject the reload.
The literal list has a separate limit of 256 entries.

## What the preset permits and blocks

Ordinary swearing such as `fuck`, `shit`, `bollocks`, `twat`, and `cunt` is not
blocked just for containing those words. That does not make targeted harassment
acceptable. A harmful compound containing a swear word may still be listed.

Literal entries use the plugin's case-insensitive Unicode whole-word matching.
Consequently words are not matched merely because their letters appear inside
`Scunthorpe`, `assignment`, `knickers`, `Nigeria`, or `rapeseed`. The plugin already
normalizes full-width characters and removes Unicode FORMAT characters.

The regex rules add selected number substitutions and inter-letter separators.
They do not remove arbitrary text, treat every number as a letter, or fuzzy-match
every similar-looking word. Those broader approaches would damage normal chat.
Each regex has a comment explaining its target; the reviewed
[regex source](presets/uk-adult-regex.json) includes positive and negative examples.

Ambiguous UK uses need particular care. Bare `fag`, `faggots`, `dyke`, `chink`,
`queer`, `retard`, `tranny`, and `coon` are not blanket literal bans. Specific
directed-insult rules cover some abusive uses, while cigarette references,
traditional food, drainage, engineering, car maintenance and identities can pass.
`Troon`, `Niger`, religious/nationality labels, `88`, `1488`, and ordinary PvP
language are not automatic reasons to block a message.

For the same reason, the phrase rules use narrow message shapes. They preserve
safeguarding advice such as “never send nudes” and reports of threats. This
deliberately means that additional surrounding text, unlisted spellings, quoted
messages, different scripts and some abuse will evade them. The preset is not
a complete classifier of harassment, fraud, extremism or sexual content.

**Zero false positives and perfect evasion detection are not achievable with a
word list or these regex rules.** Quoting or reclaiming a listed slur can still
block a message. Legitimate personal names may collide with a literal. Staff
should accept private reports of those cases, review the context and adjust
individual rules. Do not add a broad “allowed phrase anywhere” exception:
someone could append that phrase to abusive text.

The filter cancels a matched public message and gives its sender feedback. It
does not automatically warn, mute or ban anyone. It does not cover `/msg`,
Discord messages, signs, books, names, skins or builds. Earlier relays or plugins
that ignore event cancellation can still publish a message; validate the actual
installed chat stack.

## Official policy check: 20 October 2026

Mojang's **30 June 2022** Java reporting announcement says profanity alone is
not a reason for a player ban, and distinguishes it from harassment, hate,
sexual solicitation and real threats. The same announcement says private
servers must follow the governing policies. This historical explanation of
player-report enforcement is not an exemption for server owners.
[Addressing Player Reporting Tool](https://www.minecraft.net/en-us/article/addressing-player-chat-reporting-tool)

Current Minecraft Community Standards incorporate Xbox standards and prohibit
hate, harassment, threats, sexual solicitation, extremism and fraud. They also
cover player-created content beyond chat.
[Minecraft Community Standards](https://www.minecraft.net/en-us/community-standards)

Xbox's current standards distinguish game-focused banter from personal abuse and
require public messages to be suitable for all ages. They also prohibit sexual
content, self-harm encouragement and privacy violations. An allowed swear word in
this preset does not determine whether a particular message meets those standards.
[Xbox Community Standards](https://www.xbox.com/en-GB/legal/community-standards)

Minecraft's Usage Guidelines include an all-ages suitability condition for
servers, entitlements and advertising. Their commercial-use definition also
includes shared projects that do not charge money. No adults-only or private
whitelist waiver was found in the reviewed documents.
[Minecraft Usage Guidelines](https://www.minecraft.net/en-us/usage-guidelines)

Accordingly, this preset preserves your requested ordinary-profanity policy
without claiming that an “18+” label or a chat filter establishes Mojang/Xbox
compliance. Staff still need to address contextual abuse and other server
content. No official dated announcement confirming a recent profanity-enforcement
crackdown was found; the guidance above uses the published rules rather than
community reports of individual enforcement cases.

## Suggested staff rule

“Ordinary swearing is allowed. Hate speech, targeted harassment, threats,
encouragement of self-harm, sexual solicitation, doxxing, scams and extremist
promotion are not. These rules apply to chat and other player-created content.
Bypassing the filter does not make a message acceptable. Report abuse privately
to staff so they can assess it in context.”

## Reproducible validation

The generator uses the reviewed literal and regex source files in `docs/presets/`.
Regenerate the YAML with `python scripts/build-uk-adult-chat-preset.py`, or check
that it is current with `python scripts/build-uk-adult-chat-preset.py --check`.

`UkAdultChatPresetTest` reads the delivered YAML and evaluates it with the actual
production `ChatFilter`, including per-rule evasion/counterexample tests and an
independently prepared UK benign-message corpus. It also verifies that the YAML
is the fragment described above, stays within 1.1.0 limits and matches the source
lists. These checks establish the tested cases, not that every innocent sentence
will pass or every abusive sentence will be caught.

The preset-specific suite includes 182 literal cases, 309 independent benign UK
messages, 120 regex blocking examples, 152 regex counterexamples and one structure/
compatibility check. Each regex example is tested against its individual rule
and the complete preset. Literal cases also check uppercase and punctuation.
Gradle declares the preset files as test inputs, so changing the YAML or source
lists forces the tests to run again.

On 20 October 2026 all **764 preset cases** passed; the full suite passed
**1,207 tests with no failures, errors or skipped tests**. The live server has not
been changed or tested with this preset. See [the verification record](verification.md)
for the retained evidence and file hash.
