package cloud.mineplay.punishments.commands;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Brigadier handles quoted token syntax; picocli handles the command grammar. */
public final class Arguments {
  private static final Pattern DURATION = Pattern.compile("([1-9][0-9]*)([smhdw])");

  private Arguments() {}

  public static String[] tokens(String raw) {
    StringReader reader = new StringReader(raw);
    List<String> values = new ArrayList<>();
    try {
      while (reader.canRead()) {
        reader.skipWhitespace();
        if (!reader.canRead()) break;
        if (StringReader.isQuotedStringStart(reader.peek())) {
          values.add(reader.readQuotedString());
          if (reader.canRead() && !Character.isWhitespace(reader.peek()))
            throw new IllegalArgumentException("Expected a space after quoted text");
        } else {
          int start = reader.getCursor();
          while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
          values.add(raw.substring(start, reader.getCursor()));
        }
      }
    } catch (CommandSyntaxException ex) {
      throw new IllegalArgumentException(
          "Invalid quoted argument: " + ex.getRawMessage().getString());
    }
    return values.toArray(String[]::new);
  }

  public static Duration duration(String input) {
    if ("permanent".equalsIgnoreCase(input)) return null;
    Matcher matcher = DURATION.matcher(input.toLowerCase(Locale.ROOT));
    if (!matcher.matches())
      throw new IllegalArgumentException("Use a duration such as 30m, 2h, 7d or permanent");
    try {
      long multiplier =
          switch (matcher.group(2)) {
            case "s" -> 1;
            case "m" -> 60;
            case "h" -> 3600;
            case "d" -> 86400;
            case "w" -> 604800;
            default -> throw new IllegalArgumentException();
          };
      long seconds = Math.multiplyExact(Long.parseLong(matcher.group(1)), multiplier);
      if (seconds > 100L * 366 * 86400)
        throw new IllegalArgumentException("Duration cannot exceed 100 years");
      return Duration.ofSeconds(seconds);
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("Duration is too large");
    }
  }

  public static String bareCommand(String command) {
    String label = command.startsWith("/") ? command.substring(1) : command;
    label = label.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    int colon = label.lastIndexOf(':');
    return colon < 0 ? label : label.substring(colon + 1);
  }
}
