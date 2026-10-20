package cloud.mineplay.punishments.chat;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChatFilterTest {
  private YamlConfiguration settings(List<String> literals, List<String> regex) {
    YamlConfiguration config = new YamlConfiguration();
    config.set("chat-filter.banned-words", literals);
    config.set("chat-filter.regex-patterns", regex);
    return config;
  }

  private ChatFilter literal(String... words) {
    return ChatFilter.read(settings(List.of(words), List.of()));
  }

  private ChatFilter regex(String... patterns) {
    return ChatFilter.read(settings(List.of(), List.of(patterns)));
  }

  @Test
  void absentOrEmptyRulesPreserveExistingChatAndDoNotApplyInputLimits() {
    ChatFilter absent = ChatFilter.read(new YamlConfiguration());
    ChatFilter empty = literal();
    assertFalse(absent.active());
    assertFalse(empty.active());
    assertFalse(absent.blocks("a".repeat(4097)));
    assertFalse(empty.blocks("\uFDFA".repeat(4096)));
  }

  @Test
  void enabledDefaultsTrueAndDisablingOnlyStopsMatching() {
    YamlConfiguration config = settings(List.of("blocked"), List.of());
    assertTrue(ChatFilter.read(config).active());
    config.set("chat-filter.enabled", false);
    ChatFilter disabled = ChatFilter.read(config);
    assertFalse(disabled.active());
    assertFalse(disabled.blocks("BLOCKED"));
    assertFalse(disabled.blocks("x".repeat(5000)));
    config.set("chat-filter.regex-patterns", List.of("(?=unsupported)"));
    assertThrows(IllegalArgumentException.class, () -> ChatFilter.read(config));
  }

  @Test
  void literalsMatchWholeWordsIgnoringCaseAndRespectPunctuation() {
    ChatFilter filter = literal("ass", "bad word");
    assertTrue(filter.blocks("An ASS!"));
    assertTrue(filter.blocks("(bad word)."));
    assertFalse(filter.blocks("Scunthorpe and class assignments"));
    assertFalse(filter.blocks("a bad wording choice"));
    assertFalse(filter.blocks("bad  word"));
    assertFalse(filter.blocks("bad-word"));
  }

  @Test
  void laterOverlappingWholePhraseIsNotHiddenByEarlierPartialMatch() {
    ChatFilter filter = literal("a a");
    assertTrue(filter.blocks("xa a a"));
    assertFalse(filter.blocks("xa ax"));
  }

  @Test
  void literalRegexMetacharactersAndQuoteTerminatorsAreNotExecutable() {
    ChatFilter filter = literal("a.b", "[test]", "a+b", "\\E");
    assertTrue(filter.blocks("a.b!"));
    assertTrue(filter.blocks("[TEST]"));
    assertTrue(filter.blocks("A+B"));
    assertTrue(filter.blocks("\\E"));
    assertFalse(filter.blocks("axb"));
    assertFalse(filter.blocks("aaab"));
    assertFalse(filter.blocks("t"));
  }

  @Test
  void wordBoundariesIncludeUnicodeLettersMarksNumbersAndConnectors() {
    ChatFilter filter = literal("bad");
    assertFalse(filter.blocks("ébad"));
    assertFalse(filter.blocks("bad汉"));
    assertFalse(filter.blocks("bad\u0301"));
    assertFalse(filter.blocks("bad_"));
    assertFalse(filter.blocks("bad\u203F"));
    assertFalse(filter.blocks("bad\u0662"));
    assertTrue(filter.blocks("😀bad😀"));
  }

  @Test
  void nonLatinWordsAndSupplementaryLettersAreCaseInsensitive() {
    ChatFilter filter = literal("κόσμος", "плохо", "\uD801\uDC00");
    assertTrue(filter.blocks("ΚΌΣΜΟΣ!"));
    assertTrue(filter.blocks("ПЛОХО"));
    assertTrue(filter.blocks("\uD801\uDC28")); // Deseret lowercase long I.
    assertFalse(filter.blocks("неплохо"));
  }

  @Test
  void normalizationHandlesFullWidthCompatibilityCharactersAndCombiningMarks() {
    ChatFilter filter = literal("bad", "café", "office");
    assertTrue(filter.blocks("ＢＡＤ"));
    assertTrue(filter.blocks("cafe\u0301"));
    assertTrue(filter.blocks("o\uFB03ce"));
    assertTrue(literal("ＢＡＤ").blocks("bad"));
    assertTrue(literal("  café  ").blocks("CAFÉ"));
  }

  @Test
  void formatRemovalCannotPreventCompositionOrSplitBlockedWords() {
    ChatFilter filter = literal("bad", "café");
    assertTrue(filter.blocks("b\u200Bad"));
    assertTrue(filter.blocks("b\u200Ca\u200Dd"));
    assertTrue(filter.blocks("b\u202Ea\uFEFFd"));
    assertTrue(filter.blocks("cafe\u200B\u0301"));
    assertTrue(literal("b\u200Bad").blocks("bad"));
    assertFalse(filter.blocks("badger"));
  }

  @Test
  void regexUsesCaseInsensitiveFindAgainstNormalizedTextWithoutImplicitBoundaries() {
    ChatFilter filter = regex("b[a@]d", "café");
    assertTrue(filter.blocks("an embeddedbadword"));
    assertTrue(filter.blocks("B@D"));
    assertTrue(filter.blocks("ＢＡＤ"));
    assertTrue(filter.blocks("cafe\u200B\u0301"));
    assertFalse(filter.blocks("ordinary chat"));
  }

  @Test
  void regexSourceIsPreservedInsteadOfConvertingUnicodePunctuationToOperators() {
    ChatFilter filter = regex("（bad）");
    assertFalse(filter.blocks("bad"));
    assertFalse(filter.blocks("（bad）")); // Message NFKC becomes "(bad)".
    assertTrue(regex("\\(bad\\)").blocks("（ＢＡＤ）"));
  }

  @Test
  void inputLimitAppliesBeforeAndAfterNormalizationAndCountsUtf16() {
    ChatFilter filter = literal("blocked");
    assertFalse(filter.blocks("a".repeat(4096)));
    assertTrue(filter.blocks("a".repeat(4097)));
    assertTrue(filter.blocks("\u200B".repeat(4097)));
    assertFalse(filter.blocks("😀".repeat(2048)));
    assertTrue(filter.blocks("😀".repeat(2049)));
    assertTrue(filter.blocks("\uFDFA".repeat(228))); // NFKC expands each to 18 UTF-16 characters.
  }

  @Test
  void compiledSnapshotIsIndependentOfLaterConfigurationAndListChanges() {
    List<String> words = new ArrayList<>(List.of("first"));
    YamlConfiguration config = settings(words, List.of("second"));
    ChatFilter filter = ChatFilter.read(config);
    words.clear();
    config.set("chat-filter.enabled", false);
    config.set("chat-filter.banned-words", List.of("replacement"));
    config.set("chat-filter.regex-patterns", List.of());
    assertTrue(filter.active());
    assertTrue(filter.blocks("FIRST"));
    assertTrue(filter.blocks("SECOND"));
    assertFalse(filter.blocks("replacement"));
  }

  @Test
  void settingsRequireCorrectSectionBooleanAndListTypes() {
    YamlConfiguration config = new YamlConfiguration();
    config.set("chat-filter", "enabled");
    assertMessage(config, "chat-filter");
    config = settings(List.of(), List.of());
    config.set("chat-filter.enabled", "true");
    assertMessage(config, "chat-filter.enabled");
    config = settings(List.of(), List.of());
    config.set("chat-filter.banned-words", "not a list");
    assertMessage(config, "chat-filter.banned-words");
    config = settings(List.of(), List.of());
    config.set("chat-filter.regex-patterns", 4);
    assertMessage(config, "chat-filter.regex-patterns");
  }

  @Test
  void invalidEntriesUseOneBasedIndicesAndNeverEchoRuleContents() {
    YamlConfiguration config = settings(List.of("valid"), List.of());
    config.set("chat-filter.banned-words", Arrays.asList("valid", null));
    assertMessage(config, "chat-filter.banned-words[2]");
    config.set("chat-filter.banned-words", List.of("valid", 123));
    assertMessage(config, "chat-filter.banned-words[2]");
    config.set("chat-filter.banned-words", List.of("valid"));
    config.set("chat-filter.regex-patterns", List.of("valid", "(?=private_rule_text)"));
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> ChatFilter.read(config));
    assertTrue(failure.getMessage().contains("chat-filter.regex-patterns[2]"));
    assertFalse(failure.getMessage().contains("private_rule_text"));
    assertNull(failure.getCause());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\n\t", "\u200B", "\uFEFF"})
  void emptyOrInvisibleLiteralRulesAreRejected(String value) {
    assertMessage(settings(List.of(value), List.of()), "chat-filter.banned-words[1]");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "\n\t"})
  void blankRegexRulesAreRejected(String value) {
    assertMessage(settings(List.of(), List.of(value)), "chat-filter.regex-patterns[1]");
  }

  @Test
  void literalAndRegexCountsAndLengthsAreBoundedIncludingNormalizedLiterals() {
    assertTrue(literal("a".repeat(128)).active());
    assertTrue(regex("a".repeat(256)).active());
    assertMessage(settings(List.of("a".repeat(129)), List.of()), "banned-words[1]");
    assertMessage(settings(List.of(), List.of("a".repeat(257))), "regex-patterns[1]");
    assertMessage(settings(List.of("\uFDFA".repeat(8)), List.of()), "banned-words[1]");
    assertTrue(
        ChatFilter.read(settings(java.util.Collections.nCopies(256, "word"), List.of())).active());
    assertMessage(settings(java.util.Collections.nCopies(257, "word"), List.of()), "banned-words");
    assertTrue(
        ChatFilter.read(settings(List.of(), java.util.Collections.nCopies(32, "word"))).active());
    assertMessage(settings(List.of(), java.util.Collections.nCopies(33, "word")), "regex-patterns");
  }

  @ParameterizedTest
  @ValueSource(strings = {"(?=a)", "(?<=a)b", "(a)\\1", "[", "(", "a{1001}", "a++"})
  void malformedOrUnsupportedRegexFailsWithActionableSettingIndex(String value) {
    assertMessage(settings(List.of(), List.of(value)), "chat-filter.regex-patterns[1]");
  }

  @Test
  void nestedCountedRepetitionsFailBeforeExpensiveCompilation() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          IllegalArgumentException error =
              assertThrows(
                  IllegalArgumentException.class, () -> regex("(?:(?:a{1000}){1000}){1000}"));
          assertTrue(error.getMessage().contains("compilation budget"));
          assertMessage(settings(List.of(), List.of("(ab{100}){100}")), "regex-patterns[1]");
        });
  }

  @Test
  void escapedQuotedAndCharacterClassBracesAreNotCountedRepetitions() {
    assertTrue(regex("\\{999\\}\\{999\\}").blocks("{999}{999}"));
    assertTrue(regex("\\Q{999}{999}\\E").blocks("{999}{999}"));
    assertTrue(regex("[{999}]").blocks("{"));
    assertTrue(regex("[[:alpha:]{999}]").blocks("z"));
    assertTrue(regex("\\x{123}").blocks("\u0123"));
    assertTrue(regex("\\p{L}{2,4}").blocks("ABCD"));
    assertTrue(regex("(ab){2,4}").blocks("ABAB"));
  }

  @Test
  void adversarialBacktrackingShapesAndLongAlternationsRemainBounded() {
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          ChatFilter filter = regex("^(a+)+$", "^(a|aa)+$", "^(?:Σ+)+$");
          assertFalse(filter.blocks("a".repeat(4095) + "!"));
          assertFalse(filter.blocks("σ".repeat(4095) + "!"));
          assertTrue(filter.blocks("Σ".repeat(100)));
          String alternatives =
              "^(?:" + String.join("|", java.util.Collections.nCopies(60, "a")) + ")+$";
          assertFalse(regex(alternatives).blocks("a".repeat(4095) + "!"));
        });
  }

  @Test
  void sharedSnapshotSupportsConcurrentMatchingWithoutSharedMatcherState() throws Exception {
    ChatFilter filter = ChatFilter.read(settings(List.of("bad word", "café"), List.of("s[p@]am")));
    List<Callable<Boolean>> work = new ArrayList<>();
    for (int thread = 0; thread < 64; thread++) {
      work.add(
          () -> {
            for (int attempt = 0; attempt < 100; attempt++) {
              if (!filter.blocks("ＢＡＤ word")
                  || !filter.blocks("CAFE\u200B\u0301")
                  || !filter.blocks("S@AM")
                  || filter.blocks("ordinary words")) return false;
            }
            return true;
          });
    }
    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var result : executor.invokeAll(work, 10, TimeUnit.SECONDS))
        assertTrue(result.get(1, TimeUnit.SECONDS));
    }
  }

  private void assertMessage(YamlConfiguration config, String expected) {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> ChatFilter.read(config));
    assertTrue(failure.getMessage().contains(expected), failure.getMessage());
  }
}
