# Public chat filter

The filter cancels public Paper chat messages that match your configured literal or
regex rules. The sender receives a generic message that you can change in
`messages.yml`. It does not issue warnings, mutes, kicks or other punishments.
There is no filter bypass permission: staff and operators use the same rules.

This applies to Paper's public chat event. It does not inspect `/msg`, `/mail`,
commands, signs, books or private plugin channels. Existing mute enforcement remains
separate, including its configured private-message command restrictions.

## Setup

Install `MineplayPunishments-1.1.0.jar` and fully restart Paper to load the new code.
Existing configuration files are retained. Add the following optional section to
`plugins/MineplayPunishments/config.yml` if it is absent:

```yaml
chat-filter:
  enabled: true
  banned-words: []
  regex-patterns: []
```

Both lists are empty by default, so no filter rules are active until you add them.
There is no supplied profanity list. Missing settings also use empty lists.

For a small staging example, replace that section with:

```yaml
chat-filter:
  enabled: true
  banned-words:
    - 'sampleword'
    - 'sample phrase'
  regex-patterns:
    - 'example[-_ ]?pattern'
    - '^demo[0-9]{1,3}$'
```

These are harmless demonstration terms, not a recommended moderation policy.
Replace them with the words and patterns your server actually needs. YAML single
quotes preserve regex backslashes; if a rule contains an apostrophe, double it
inside the quoted YAML value. For example, write a regex word boundary as
`'\bexample\b'`, not `"\bexample\b"`: YAML interprets `\b` inside double quotes
as a backspace character.

To customize the feedback, add or edit this key in `messages.yml`:

```yaml
chat-filter-blocked: '<red>Your message was blocked by the chat filter. Please rephrase it.</red>'
```

The template supports MiniMessage styling. Keep it generic; the filter does not
insert the blocked message or the matching rule into the response.

Apply configuration and message changes with `/mp admin reload`, requiring
`mineplaypunishments.admin` and `mineplaypunishments.admin.reload`, or use console.
A successful reload replaces the active rules. An invalid rule rejects the reload
and retains the previous working filter; fix the indicated setting and reload again.
The rejected file edit remains on disk, so repair it before restarting. Set
`enabled: false` and successfully reload to disable filtering. Configured rules are
validated even when disabled, so remove or repair invalid rules first.

## Matching rules

Literal rules are case-insensitive whole words or phrases. For example,
`sampleword` blocks `SAMPLEWORD!` but not `samplewords` or `my_sampleword`.
Letters, combining marks, numbers and connector punctuation such as underscore
count as part of a Unicode word. A phrase must retain its configured internal
spacing: `sample phrase` does not mean `sample   phrase`. Outer whitespace is trimmed
from literal rules.

Before matching, chat text is normalized with Unicode NFKC and Unicode FORMAT
characters are removed, including zero-width format characters. Literal rules
receive the same normalization. This catches compatibility forms such as full-width
letters and inserted zero-width format characters. It is not a general detector
for all look-alike alphabets, deliberate misspellings or leetspeak. Use explicit
rules for variants that matter to your server. Normalization is used for checking;
the filter does not rewrite messages it allows through.

Regex rules use case-insensitive **find** matching against that normalized text:
matching part of the message is enough. The example `example[-_ ]?pattern` also
matches inside `hello example-pattern today`. Use `^` and `$` when the entire
message must match, as in `^demo[0-9]{1,3}$`. Regex source syntax is kept as supplied;
it is not normalized like a literal rule.

The engine is RE2/J, not Java's backtracking regex engine. Lookarounds and
backreferences are unsupported. Ordinary repetition operators such as `*`, `+`,
`?` and reasonable counted repetitions are supported; complex counted repetitions
that exceed the compilation budget are rejected. Consult the
[RE2/J project](https://github.com/google/re2j) for engine behavior and compatibility
differences.

Regex `\b` uses ASCII word boundaries, unlike the Unicode boundaries of the literal
list. Prefer `banned-words` for a whole literal word in multilingual chat. See the
[RE2 syntax reference](https://github.com/google/re2/wiki/Syntax) for boundary and
character-class behavior.

| Setting or input | Limit |
| --- | --- |
| `banned-words` | 256 entries, at most 128 characters each |
| `regex-patterns` | 32 entries, at most 256 characters each |
| Regex expansion during compilation | Budget of 4,096 estimated instructions per pattern |
| Chat text while rules are active | Text over 4,096 characters before or after normalization is blocked |

Lengths count Java UTF-16 characters, so some symbols count as two. Blank or
normalized-empty literal rules and blank regex rules are invalid. Pattern complexity
is checked separately from source length: a short expression can still exceed its
compilation budget. The chat-length guard applies only when the filter is enabled
and at least one rule is active; empty lists do not introduce a new chat-length limit.

## Threads and other chat plugins

Normal player chat arrives through Paper's asynchronous chat event. Matching reads
an immutable snapshot of already compiled rules; it does not read configuration,
compile patterns, access the database or make network requests for each message.
Configuration loading and compilation happen at startup or admin reload on the main
server thread. Sender feedback is scheduled on the main thread. A chat event fired
synchronously by another plugin is checked on its calling thread with the same
bounded inputs and rules. Paper explains the asynchronous event and Bukkit API
constraints in its [chat-event guide](https://docs.papermc.io/paper/dev/chat-events/).

The filter checks the event's original and current plain text, including an early
check and a later check after normal message edits by other plugins.
Cancellation cannot retract a message another plugin has already relayed, and a
plugin that ignores cancellation may still publish it. Test the installed renderer,
proxy, Discord bridge and other chat routes together. This filter does not claim
to moderate every route those plugins provide.

Use the [public-chat staging checklist](staging-acceptance.md#public-chat-filter)
with real clients before relying on the filter for the live server. Automated
matching tests do not prove delivery behavior across a complete plugin stack.
