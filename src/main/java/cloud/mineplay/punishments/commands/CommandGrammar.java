package cloud.mineplay.punishments.commands;

import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/** A fresh parser model per invocation, so concurrent commands never share argument state. */
public final class CommandGrammar {
  private CommandGrammar() {}

  public static CommandLine parser(String name) {
    CommandSpec root = CommandSpec.create().name(name);
    switch (name) {
      case "ban", "mute", "warn", "kick" -> {
        silent(root);
        target(root, false);
        reason(root, 1, true);
      }
      case "tempban", "tempmute" -> {
        silent(root);
        target(root, false);
        parameter(root, 1, "duration", false);
        reason(root, 2, true);
      }
      case "unban", "unmute" -> {
        silent(root);
        target(root, false);
        reason(root, 1, false);
      }
      case "checkban", "checkmute" -> target(root, false);
      case "history", "warnings" -> {
        target(root, true);
        root.addOption(
            OptionSpec.builder("--page")
                .type(int.class)
                .arity("1")
                .defaultValue("1")
                .paramLabel("number")
                .build());
      }
      case "service" -> {
        silent(root);
        target(root, true);
        parameter(root, 1, "blocks", true);
        reason(root, 2, false);
        root.addOption(
            OptionSpec.builder("--tool").type(String.class).paramLabel("wooden|stone").build());
        CommandSpec issue = CommandSpec.create().name("issue");
        silent(issue);
        target(issue, false);
        parameter(issue, 1, "blocks", false);
        reason(issue, 2, true);
        issue.addOption(
            OptionSpec.builder("--tool").type(String.class).paramLabel("wooden|stone").build());
        CommandSpec release = CommandSpec.create().name("release");
        silent(release);
        target(release, false);
        reason(release, 1, false);
        CommandSpec add = CommandSpec.create().name("add");
        silent(add);
        target(add, false);
        parameter(add, 1, "blocks", false);
        reason(add, 2, true);
        CommandSpec progress = CommandSpec.create().name("progress");
        target(progress, true);
        root.addSubcommand("issue", new CommandLine(issue));
        root.addSubcommand("release", new CommandLine(release));
        root.addSubcommand("add", new CommandLine(add));
        root.addSubcommand("progress", new CommandLine(progress));
      }
      case "mp" -> {
        CommandSpec admin = CommandSpec.create().name("admin");
        for (String simple : List.of("status", "sync", "reload"))
          admin.addSubcommand(simple, new CommandLine(CommandSpec.create().name(simple)));
        CommandSpec recover = CommandSpec.create().name("recover");
        target(recover, false);
        CommandSpec quarry = CommandSpec.create().name("quarry");
        parameter(quarry, 0, "operation", false);
        CommandSpec prison = CommandSpec.create().name("prison");
        parameter(prison, 0, "operation", false);
        admin.addSubcommand("recover", new CommandLine(recover));
        admin.addSubcommand("quarry", new CommandLine(quarry));
        admin.addSubcommand("prison", new CommandLine(prison));
        root.addSubcommand("admin", new CommandLine(admin));
        CommandSpec ack = CommandSpec.create().name("acknowledge");
        parameter(ack, 0, "punishment-uuid", false);
        root.addSubcommand("acknowledge", new CommandLine(ack));
        CommandSpec appeal = CommandSpec.create().name("appeal");
        parameter(appeal, 0, "punishment-uuid", false);
        appeal.addOption(OptionSpec.builder("--rotate").type(boolean.class).build());
        root.addSubcommand("appeal", new CommandLine(appeal));
      }
      default -> throw new IllegalArgumentException("Unknown command");
    }
    return new CommandLine(root)
        .setExpandAtFiles(false)
        .setInterpolateVariables(false)
        .setPosixClusteredShortOptionsAllowed(false)
        .setSubcommandsCaseInsensitive(true);
  }

  private static void silent(CommandSpec spec) {
    spec.addOption(OptionSpec.builder("-s", "--silent").type(boolean.class).build());
  }

  private static void target(CommandSpec spec, boolean optional) {
    parameter(spec, 0, "player|uuid", optional);
  }

  private static void parameter(CommandSpec spec, int index, String label, boolean optional) {
    spec.addPositional(
        PositionalParamSpec.builder()
            .index(Integer.toString(index))
            .arity(optional ? "0..1" : "1")
            .required(!optional)
            .type(String.class)
            .paramLabel(label)
            .build());
  }

  private static void reason(CommandSpec spec, int index, boolean required) {
    spec.addPositional(
        PositionalParamSpec.builder()
            .index(index + "..*")
            .arity(required ? "1..*" : "0..*")
            .required(required)
            .type(String[].class)
            .paramLabel("reason")
            .build());
  }
}
