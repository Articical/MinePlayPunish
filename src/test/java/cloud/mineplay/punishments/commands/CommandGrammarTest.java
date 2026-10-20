package cloud.mineplay.punishments.commands;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

class CommandGrammarTest {
  @ParameterizedTest
  @ValueSource(
      strings = {
        "ban Steve Reason",
        "tempban Steve 1h Reason",
        "mute Steve Reason",
        "tempmute Steve 1h Reason",
        "warn Steve Reason",
        "kick Steve Reason",
        "unban Steve Reason",
        "unmute Steve Reason",
        "service Steve 100 Reason",
        "service issue Steve 100 Reason",
        "service add Steve 100 Reason",
        "service release Steve Reason"
      })
  void mutationFlagsAreAcceptedBeforeBetweenAndAfterArguments(String input) {
    List<String> tokens = List.of(Arguments.tokens(input));
    for (String flag : List.of("-s", "--silent")) {
      for (int position = 0; position < tokens.size(); position++) {
        List<String> invocation = new ArrayList<>(tokens.subList(1, tokens.size()));
        invocation.add(position, flag);
        var result =
            CommandGrammar.parser(tokens.getFirst()).parseArgs(invocation.toArray(String[]::new));
        boolean silent = result.matchedOptionValue("--silent", false);
        while (result.hasSubcommand()) {
          result = result.subcommand();
          silent |= result.matchedOptionValue("--silent", false);
        }
        assertTrue(silent, input + " at " + position);
        assertEquals("Steve", result.matchedPositionalValue(0, ""));
      }
    }
  }

  @Test
  void optionTerminatorPreservesSilentLookingReasonAndDefaultIsPublic() {
    var normal = CommandGrammar.parser("warn").parseArgs("Steve", "Reason");
    assertFalse(normal.matchedOptionValue("--silent", false));
    var literal = CommandGrammar.parser("warn").parseArgs("Steve", "--", "-s", "--silent");
    assertFalse(literal.hasMatchedOption("--silent"));
    assertArrayEquals(
        new String[] {"-s", "--silent"}, literal.matchedPositionalValue(1, new String[0]));
  }

  @Test
  void readAndAdminCommandsRejectSilentOptions() {
    for (String name : List.of("history", "warnings", "checkban", "checkmute")) {
      assertThrows(
          CommandLine.ParameterException.class,
          () -> CommandGrammar.parser(name).parseArgs("Steve", "-s"));
    }
    for (String[] args :
        List.of(
            new String[] {"admin", "status", "-s"},
            new String[] {"appeal", "id", "--silent"},
            new String[] {"acknowledge", "id", "-s"})) {
      assertThrows(
          CommandLine.ParameterException.class, () -> CommandGrammar.parser("mp").parseArgs(args));
    }
    assertThrows(
        CommandLine.ParameterException.class,
        () -> CommandGrammar.parser("service").parseArgs("progress", "--silent"));
  }

  @Test
  void historyAndWarningsAcceptOptionalTargetsAndTypedPages() {
    for (String name : List.of("history", "warnings")) {
      var own = CommandGrammar.parser(name).parseArgs("--page", "2");
      assertNull(own.matchedPositionalValue(0, null));
      assertEquals(2, own.matchedOptionValue("--page", 1));
      var other = CommandGrammar.parser(name).parseArgs("Steve", "--page", "7");
      assertEquals("Steve", other.matchedPositionalValue(0, ""));
      assertEquals(7, other.matchedOptionValue("--page", 1));
      assertThrows(
          CommandLine.ParameterException.class,
          () -> CommandGrammar.parser(name).parseArgs("Steve", "--page", "many"));
    }
  }

  @Test
  void sentenceParsesTrailingToolAndQuotedReason() {
    var args =
        CommandGrammar.parser("service")
            .parseArgs(
                Arguments.tokens("Steve 250 \"Griefing protected spawn structures\" --tool stone"));
    assertEquals("Steve", args.matchedPositionalValue(0, ""));
    assertEquals("250", args.matchedPositionalValue(1, ""));
    assertArrayEquals(
        new String[] {"Griefing protected spawn structures"},
        args.matchedPositionalValue(2, new String[0]));
    assertEquals("stone", args.matchedOptionValue("--tool", ""));
  }

  @Test
  void sentenceParsesUnquotedMultiwordReason() {
    var args =
        CommandGrammar.parser("service")
            .parseArgs(
                Arguments.tokens("Steve 250 Griefing protected spawn structures --tool wooden"));
    assertArrayEquals(
        new String[] {"Griefing", "protected", "spawn", "structures"},
        args.matchedPositionalValue(2, new String[0]));
  }

  @Test
  void requiredReasonIsNotSilentlyLost() {
    assertThrows(
        CommandLine.ParameterException.class,
        () -> CommandGrammar.parser("ban").parseArgs("Steve"));
  }

  @Test
  void serviceSubcommandDoesNotRequireSentenceArguments() {
    var args = CommandGrammar.parser("service").parseArgs("progress");
    assertTrue(args.hasSubcommand());
    assertEquals("progress", args.subcommand().commandSpec().name());
    assertNull(args.subcommand().matchedPositionalValue(0, null));
  }

  @Test
  void serviceAddIsExplicitAndRequiresReason() {
    var args =
        CommandGrammar.parser("service")
            .parseArgs("add", "Steve", "20", "More", "damage")
            .subcommand();
    assertEquals("add", args.commandSpec().name());
    assertEquals("Steve", args.matchedPositionalValue(0, ""));
    assertThrows(
        CommandLine.ParameterException.class,
        () -> CommandGrammar.parser("service").parseArgs("add", "Steve", "20"));
  }

  @Test
  void serviceIssueAliasParsesTargetBlocksReasonAndTool() {
    var args =
        CommandGrammar.parser("service")
            .parseArgs(Arguments.tokens("IsSuE Steve 250 \"Repair the damage\" --tool stone"))
            .subcommand();
    assertEquals("issue", args.commandSpec().name());
    assertEquals("Steve", args.matchedPositionalValue(0, ""));
    assertEquals("250", args.matchedPositionalValue(1, ""));
    assertArrayEquals(
        new String[] {"Repair the damage"}, args.matchedPositionalValue(2, new String[0]));
    assertEquals("stone", args.matchedOptionValue("--tool", ""));
  }

  @Test
  void serviceIssueAliasRequiresAllSentenceArgumentsAndRejectsUnknownOptions() {
    for (String input :
        new String[] {"issue", "issue Steve", "issue Steve 20", "issue Steve 20 reason --op"}) {
      assertThrows(
          CommandLine.ParameterException.class,
          () -> CommandGrammar.parser("service").parseArgs(Arguments.tokens(input)));
    }
  }

  @Test
  void prisonSetupHasAnExplicitSingleOperation() {
    for (String operation : new String[] {"setpos1", "setpos2", "copyquarry", "validate"}) {
      var args =
          CommandGrammar.parser("mp")
              .parseArgs("AdMiN", "PrIsOn", operation)
              .subcommand()
              .subcommand();
      assertEquals("prison", args.commandSpec().name());
      assertEquals(operation, args.matchedPositionalValue(0, ""));
    }
    assertThrows(
        CommandLine.ParameterException.class,
        () -> CommandGrammar.parser("mp").parseArgs("admin", "prison"));
    assertThrows(
        CommandLine.ParameterException.class,
        () ->
            CommandGrammar.parser("mp").parseArgs("admin", "prison", "setpos1", "10", "64", "10"));
  }

  @Test
  void adminTreeIsNotFreeFormCommandExecution() {
    var args =
        CommandGrammar.parser("mp")
            .parseArgs("admin", "quarry", "validate")
            .subcommand()
            .subcommand();
    assertEquals("quarry", args.commandSpec().name());
    assertEquals("validate", args.matchedPositionalValue(0, ""));
    assertThrows(
        CommandLine.ParameterException.class,
        () -> CommandGrammar.parser("mp").parseArgs("admin", "execute", "op", "Steve"));
  }

  @Test
  void noArgumentFileOrVariableExpansion() {
    var args = CommandGrammar.parser("ban").parseArgs("Steve", "@secrets.txt", "${env:TOKEN}");
    assertArrayEquals(
        new String[] {"@secrets.txt", "${env:TOKEN}"},
        args.matchedPositionalValue(1, new String[0]));
  }

  @Test
  void rejectsUnknownAndDuplicateFlags() {
    assertThrows(
        CommandLine.ParameterException.class,
        () -> CommandGrammar.parser("service").parseArgs("Steve", "10", "reason", "--op"));
    assertThrows(
        CommandLine.ParameterException.class,
        () ->
            CommandGrammar.parser("service")
                .parseArgs("Steve", "10", "reason", "--tool", "stone", "--tool", "wooden"));
  }

  @Test
  void lexerSupportsEscapedQuotesAndPunctuationWithoutNaiveSplitting() {
    assertArrayEquals(
        new String[] {"Steve", "He said \"hello\"", "O'Brien!"},
        Arguments.tokens("Steve \"He said \\\"hello\\\"\" O'Brien!"));
    assertThrows(IllegalArgumentException.class, () -> Arguments.tokens("Steve \"unterminated"));
    assertThrows(IllegalArgumentException.class, () -> Arguments.tokens("\"abc\"xyz"));
  }

  @Test
  void durationsAreBoundedPositiveAndReadable() {
    assertEquals(Duration.ofMinutes(30), Arguments.duration("30m"));
    assertEquals(Duration.ofDays(7), Arguments.duration("7d"));
    assertNull(Arguments.duration("permanent"));
    for (String value :
        new String[] {"0s", "-1h", "1.5d", "20years", "9999999999999999999999w", "999999d"})
      assertThrows(IllegalArgumentException.class, () -> Arguments.duration(value));
  }

  @Test
  void muteCommandLabelsIncludeNamespacedForms() {
    assertEquals("msg", Arguments.bareCommand("/essentials:msg Steve hello"));
    assertEquals("reply", Arguments.bareCommand("/Reply hello"));
    assertEquals("spawn", Arguments.bareCommand("/spawn"));
  }
}
