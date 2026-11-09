package io.github.carloslimajlle.liquimanager.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/** Entry point of the {@code lqm} command. */
@Command(name = "lqm", mixinStandardHelpOptions = true, versionProvider = Main.VersionProvider.class,
        description = "Runs Liquibase and reports every operation to LiquiManager.",
        subcommands = {
                Commands.Update.class, Commands.UpdateCount.class, Commands.UpdateToTag.class, Commands.UpdateSql.class, Commands.Status.class, Commands.History.class, Commands.Tag.class,
                Commands.Rollback.class, Commands.RollbackCount.class, Commands.RollbackToDate.class, Commands.RollbackOne.class, Commands.RollbackToChangeset.class, Commands.Validate.class, Commands.Checks.class, RunnerCommand.class,
                FlowCommand.class, SchemaCommands.Snapshot.class, SchemaCommands.Diff.class, SchemaCommands.DiffChangelog.class,
                LoginCommand.class, LoginCommand.Logout.class})
public final class Main implements Runnable {

    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {"lqm " + Version.current()};
        }
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
