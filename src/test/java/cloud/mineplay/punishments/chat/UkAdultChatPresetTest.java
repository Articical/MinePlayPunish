package cloud.mineplay.punishments.chat;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Executes the actual user-delivered preset through the production matcher, not Java regex. */
class UkAdultChatPresetTest {
  private static final Path PRESETS = Path.of("docs", "presets");

  private static YamlConfiguration config() throws Exception {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.loadFromString(Files.readString(PRESETS.resolve("chat-filter-uk-adult.yml")));
    return yaml;
  }

  private static JsonArray rules() throws IOException {
    return JsonParser.parseString(Files.readString(PRESETS.resolve("uk-adult-regex.json")))
        .getAsJsonObject()
        .getAsJsonArray("rules");
  }

  @Test
  void copyPasteSectionIsValidForVersion110AndMatchesReviewedInputs() throws Exception {
    YamlConfiguration yaml = config();
    assertEquals(java.util.Set.of("chat-filter"), yaml.getKeys(false));
    assertTrue(ChatFilter.read(yaml).active());
    var words = yaml.getStringList("chat-filter.banned-words");
    var patterns = yaml.getStringList("chat-filter.regex-patterns");
    assertTrue(words.size() <= 256);
    assertTrue(patterns.size() <= 32);
    assertEquals(words.size(), new HashSet<>(words).size());
    assertEquals(patterns.size(), new HashSet<>(patterns).size());
    JsonObject literals =
        JsonParser.parseString(Files.readString(PRESETS.resolve("uk-adult-literals.json")))
            .getAsJsonObject();
    List<String> expectedWords = new ArrayList<>();
    literals
        .entrySet()
        .forEach(
            entry ->
                entry
                    .getValue()
                    .getAsJsonArray()
                    .forEach(word -> expectedWords.add(word.getAsString())));
    assertEquals(expectedWords, words);
    List<String> expectedPatterns = new ArrayList<>();
    rules()
        .forEach(rule -> expectedPatterns.add(rule.getAsJsonObject().get("pattern").getAsString()));
    assertEquals(expectedPatterns, patterns);
    for (String accepted :
        List.of(
            "fuck",
            "shit",
            "fucking",
            "bollocks",
            "cunt",
            "twat",
            "Jewish",
            "Muslim",
            "gay",
            "queer",
            "dyke",
            "Niger",
            "Scunthorpe",
            "88",
            "1488")) assertFalse(ChatFilter.read(yaml).blocks(accepted), accepted);
  }

  @TestFactory
  Stream<DynamicTest> literalFormsAndCaseVariantsBlock() throws Exception {
    var yaml = config();
    var filter = ChatFilter.read(yaml);
    var words = yaml.getStringList("chat-filter.banned-words");
    List<DynamicTest> tests = new ArrayList<>();
    for (int i = 0; i < words.size(); i++) {
      String word = words.get(i);
      tests.add(
          DynamicTest.dynamicTest(
              "literal entry " + (i + 1),
              () -> {
                assertTrue(filter.blocks(word));
                assertTrue(filter.blocks(word.toUpperCase(Locale.ROOT) + "!"));
                assertTrue(filter.blocks("(" + word + ")"));
              }));
    }
    return tests.stream();
  }

  @TestFactory
  Stream<DynamicTest> independentBenignUkCorpusRemainsAllowed() throws Exception {
    var filter = ChatFilter.read(config());
    List<String> examples;
    try (var stream = getClass().getResourceAsStream("/chat-filter/uk-adult-allowed.txt")) {
      assertNotNull(stream);
      examples =
          new String(stream.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .filter(line -> !line.isBlank() && !line.startsWith("#"))
              .toList();
    }
    assertEquals(examples.size(), new HashSet<>(examples).size());
    List<DynamicTest> tests = new ArrayList<>();
    for (int i = 0; i < examples.size(); i++) {
      String example = examples.get(i);
      tests.add(
          DynamicTest.dynamicTest(
              "benign UK example " + (i + 1),
              () -> assertFalse(filter.blocks(example), () -> "Unexpected block: " + example)));
    }
    return tests.stream();
  }

  @TestFactory
  Stream<DynamicTest> everyRegexMatchesItsEvasionsAndPreservesItsCounterexamples()
      throws Exception {
    var complete = ChatFilter.read(config());
    List<DynamicTest> tests = new ArrayList<>();
    for (var value : rules()) {
      var rule = value.getAsJsonObject();
      String id = rule.get("id").getAsString();
      YamlConfiguration single = new YamlConfiguration();
      single.set("chat-filter.regex-patterns", List.of(rule.get("pattern").getAsString()));
      var isolated = ChatFilter.read(single);
      for (String outcome : List.of("blocked", "allowed")) {
        var cases = rule.getAsJsonArray(outcome);
        assertNotNull(cases, id + " needs " + outcome + " examples");
        assertFalse(cases.isEmpty(), id + " needs " + outcome + " examples");
        for (int i = 0; i < cases.size(); i++) {
          String text = cases.get(i).getAsString();
          boolean expected = outcome.equals("blocked");
          tests.add(
              DynamicTest.dynamicTest(
                  id + " " + outcome + " " + (i + 1),
                  () -> {
                    assertEquals(expected, isolated.blocks(text), id + " isolated rule");
                    assertEquals(expected, complete.blocks(text), id + " complete preset: " + text);
                  }));
        }
      }
    }
    return tests.stream();
  }
}
