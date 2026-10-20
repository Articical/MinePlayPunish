package cloud.mineplay.punishments.chat;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;

/** Immutable validated rules; matchers and normalized input belong to each individual call. */
public final class ChatFilter {
  private static final int MAX_INPUT = 4096;
  private static final int MAX_PROGRAM_ESTIMATE = 4096;
  private static final String WORD = "\\p{L}\\p{M}\\p{N}\\p{Pc}";
  private final boolean enabled;
  private final List<Pattern> rules;

  private ChatFilter(boolean enabled, List<Pattern> rules) {
    this.enabled = enabled;
    this.rules = List.copyOf(rules);
  }

  /** Reads and validates all settings, including rules when disabled. Errors never quote rules. */
  public static ChatFilter read(ConfigurationSection root) {
    Objects.requireNonNull(root, "root");
    Object section = root.get("chat-filter");
    if (section != null && !(section instanceof ConfigurationSection))
      throw invalid("chat-filter", "must be a configuration section");
    Object configuredEnabled = root.get("chat-filter.enabled");
    if (configuredEnabled != null && !(configuredEnabled instanceof Boolean))
      throw invalid("chat-filter.enabled", "must be true or false");
    boolean enabled = configuredEnabled == null || (Boolean) configuredEnabled;
    List<Pattern> rules = new ArrayList<>();
    List<String> literals = entries(root, "chat-filter.banned-words", 256, 128);
    for (int i = 0; i < literals.size(); i++) {
      String key = indexed("chat-filter.banned-words", i);
      String literal = normalize(literals.get(i)).strip();
      if (literal.isBlank() || literal.length() > 128)
        throw invalid(key, "must contain 1 to 128 characters after normalization");
      // Include boundaries in the expression so failed partial matches cannot hide a later
      // overlapping whole phrase. RE2's ASCII-only \b is unsuitable for Unicode player chat.
      rules.add(
          Pattern.compile(
              "(^|[^" + WORD + "])(?:" + Pattern.quote(literal) + ")($|[^" + WORD + "])",
              Pattern.CASE_INSENSITIVE));
    }
    List<String> regexes = entries(root, "chat-filter.regex-patterns", 32, 256);
    for (int i = 0; i < regexes.size(); i++) {
      String key = indexed("chat-filter.regex-patterns", i);
      String regex = regexes.get(i);
      checkExpansion(regex, key);
      try {
        // Preserve regex syntax. Authors write expressions against normalized message text.
        rules.add(Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
      } catch (PatternSyntaxException invalidRegex) {
        throw invalid(
            key,
            "contains invalid or unsupported RE2 syntax; lookarounds and backreferences are not"
                + " supported");
      }
    }
    return new ChatFilter(enabled, rules);
  }

  public boolean active() {
    return enabled && !rules.isEmpty();
  }

  /**
   * Oversized messages are denied only when filtering is active. Limits count UTF-16 characters.
   */
  public boolean blocks(String text) {
    if (!active()) return false;
    Objects.requireNonNull(text, "text");
    if (text.length() > MAX_INPUT) return true;
    String normalized = normalize(text);
    if (normalized.length() > MAX_INPUT) return true;
    for (Pattern rule : rules) if (rule.matcher(normalized).find()) return true;
    return false;
  }

  private static List<String> entries(
      ConfigurationSection root, String key, int maximum, int length) {
    Object configured = root.get(key);
    if (configured == null) return List.of();
    if (!(configured instanceof List<?> values)) throw invalid(key, "must be a list of strings");
    if (values.size() > maximum) throw invalid(key, "must contain at most " + maximum + " entries");
    List<String> result = new ArrayList<>(values.size());
    for (int i = 0; i < values.size(); i++) {
      Object value = values.get(i);
      if (!(value instanceof String text) || text.isBlank() || text.length() > length)
        throw invalid(
            indexed(key, i), "must be a nonblank string of 1 to " + length + " characters");
      result.add(text);
    }
    return result;
  }

  private static String normalize(String text) {
    // Remove format characters first so an inserted zero-width character cannot prevent NFKC
    // from composing a base letter and its combining mark.
    return withoutFormat(Normalizer.normalize(withoutFormat(text), Normalizer.Form.NFKC));
  }

  private static String withoutFormat(String text) {
    StringBuilder result = new StringBuilder(text.length());
    text.codePoints()
        .filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
        .forEach(result::appendCodePoint);
    return result.toString();
  }

  private static String indexed(String key, int index) {
    return key + "[" + (index + 1) + "]";
  }

  private static IllegalArgumentException invalid(String key, String detail) {
    return new IllegalArgumentException(key + " " + detail);
  }

  /**
   * RE2/J 1.8 avoids matching backtracking, but expands counted repetitions during compilation.
   * This conservative instruction estimate bounds that expansion before calling its compiler. It
   * deliberately overestimates syntax such as flags; RE2 remains the syntax authority.
   */
  private static void checkExpansion(String regex, String key) {
    ArrayDeque<Estimate> groups = new ArrayDeque<>();
    Estimate current = new Estimate();
    for (int i = 0; i < regex.length(); ) {
      char ch = regex.charAt(i++);
      switch (ch) {
        case '\\' -> {
          if (i == regex.length()) break; // RE2 reports the invalid trailing escape.
          char escaped = regex.charAt(i++);
          if (escaped == 'Q') {
            while (i < regex.length()) {
              if (regex.charAt(i) == '\\' && i + 1 < regex.length() && regex.charAt(i + 1) == 'E') {
                i += 2;
                break;
              }
              i += Character.charCount(regex.codePointAt(i));
              current.atom(1, key);
            }
          } else {
            if ((escaped == 'p' || escaped == 'P' || escaped == 'x')
                && i < regex.length()
                && regex.charAt(i) == '{') {
              int closing = regex.indexOf('}', i + 1);
              i = closing < 0 ? regex.length() : closing + 1;
            }
            current.atom(1, key);
          }
        }
        case '[' -> {
          i = endClass(regex, i);
          current.atom(1, key);
        }
        case '(' -> {
          groups.push(current);
          current = new Estimate();
        }
        case ')' -> {
          long cost = current.size() + 2;
          if (!groups.isEmpty()) current = groups.pop();
          else current = new Estimate(); // RE2 reports the unmatched closing parenthesis.
          current.atom(cost, key);
        }
        case '*', '+', '?' -> {
          current.last += 1;
          current.check(key);
        }
        case '{' -> {
          Counted counted = counted(regex, i);
          if (counted == null) current.atom(1, key);
          else {
            // Include copies and optional/split instructions, with one for the empty case.
            current.last = Math.max(1, current.last) * (counted.maximum + 1L) * 2;
            current.check(key);
            i = counted.end;
          }
        }
        default -> current.atom(1, key);
      }
    }
    while (!groups.isEmpty()) {
      long cost = current.size() + 2;
      current = groups.pop();
      current.atom(cost, key);
    }
  }

  private static int endClass(String regex, int offset) {
    int i = offset;
    if (i < regex.length() && regex.charAt(i) == '^') i++;
    if (i < regex.length() && regex.charAt(i) == ']') i++;
    while (i < regex.length()) {
      char ch = regex.charAt(i++);
      if (ch == '\\' && i < regex.length()) i++;
      else if (ch == ']') return i;
      else if (ch == '[' && i < regex.length() && regex.charAt(i) == ':') {
        int end = regex.indexOf(":]", i + 1);
        if (end >= 0) i = end + 2;
      }
    }
    return i;
  }

  private record Counted(int maximum, int end) {}

  private static Counted counted(String regex, int offset) {
    int i = offset, minimum = 0, maximum;
    while (i < regex.length() && asciiDigit(regex.charAt(i))) {
      minimum = Math.min(1001, minimum * 10 + regex.charAt(i++) - '0');
    }
    if (i == offset) return null;
    maximum = minimum;
    if (i < regex.length() && regex.charAt(i) == ',') {
      int start = ++i;
      maximum = 0;
      while (i < regex.length() && asciiDigit(regex.charAt(i))) {
        maximum = Math.min(1001, maximum * 10 + regex.charAt(i++) - '0');
      }
      if (i == start) maximum = minimum + 1; // {n,} adds a loop after the n copies.
    }
    return i < regex.length() && regex.charAt(i) == '}'
        ? new Counted(Math.max(minimum, maximum), i + 1)
        : null;
  }

  private static boolean asciiDigit(char value) {
    return value >= '0' && value <= '9';
  }

  private static final class Estimate {
    long previous;
    long last;

    long size() {
      return previous + last;
    }

    void atom(long cost, String key) {
      previous += last;
      last = cost;
      check(key);
    }

    void check(String key) {
      if (size() > MAX_PROGRAM_ESTIMATE)
        throw invalid(
            key, "exceeds the 4096 instruction compilation budget; reduce counted repetitions");
    }
  }
}
