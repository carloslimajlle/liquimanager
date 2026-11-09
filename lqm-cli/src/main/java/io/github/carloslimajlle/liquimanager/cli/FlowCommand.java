package io.github.carloslimajlle.liquimanager.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import io.github.carloslimajlle.liquimanager.cli.flow.FlowFile;
import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import io.github.carloslimajlle.liquimanager.client.LqmClient;
import io.github.carloslimajlle.liquimanager.client.LqmConfig;
import io.github.carloslimajlle.liquimanager.collector.ReportBuilder;
import io.github.carloslimajlle.liquimanager.collector.RunInfo;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code lqm flow}: runs the stages of {@code lqm.flow.yaml} one after another (docs/07 § 2.2).
 *
 * <p>Each stage is the ordinary command it names, run with the flow's own options, so it reports itself as the
 * operation it is (an {@code update} stage is an update, with its changesets) and answers to the flow through
 * {@code parentOperationId}. The flow adds one operation of its own, {@code flow}, which says how the whole went and
 * carries no changesets: the deploys are the stages', and counting them twice would inflate every indicator.
 *
 * <p>Exit code: that of the stage that stopped the flow, or {@code 0}. A stage marked {@code onFailure: continue}
 * that fails is reported but does not fail the flow.
 */
@Command(name = "flow", mixinStandardHelpOptions = true, description = "Runs the stages of lqm.flow.yaml in order.")
final class FlowCommand implements Callable<Integer> {

    /** What each stage can run. A command that does not exist here is refused before anything runs. */
    private static final List<List<String>> COMMANDS = List.of(
            List.of("update"), List.of("update-sql"), List.of("status"), List.of("tag"), List.of("validate"),
            List.of("rollback"), List.of("rollback-one-changeset"), List.of("rollback-to-changeset"), List.of("checks", "run"));

    @Mixin
    RunOptions options;

    @Option(names = "--flow-file", description = "The flow definition.", defaultValue = "lqm.flow.yaml")
    String flowFile;

    @Override
    public Integer call() {
        Path file = base().resolve(flowFile);
        FlowFile flow;
        try {
            flow = FlowFile.parse(Files.readString(file), System.getenv());
            refuseWhatCannotRun(flow);
        } catch (IOException e) {
            System.err.println("lqm: cannot read " + file + ": " + e.getMessage());
            return 2;
        } catch (FlowFile.Invalid e) {
            System.err.println("lqm: " + file.getFileName() + ": " + e.getMessage());
            return 2;
        }

        options.useSavedLogin(System.getenv());
        UUID flowId = UUID.randomUUID();
        Instant started = Instant.now();
        StringBuilder log = new StringBuilder();
        int exit = 0;
        String stopped = null;

        for (FlowFile.Stage stage : flow.stages()) {
            if (!FlowFile.applies(stage, options.environment)) {
                say(log, "[SKIP] " + stage.name() + "  (" + stage.when() + ")");
                continue;
            }
            say(log, "[FLOW] " + stage.name() + "  " + stage.run());
            int code = new CommandLine(new Main()).execute(arguments(stage, flowId).toArray(new String[0]));
            if (code == 0) {
                continue;
            }
            if (stage.onFailure() == FlowFile.OnFailure.CONTINUE) {
                say(log, "[WARN] " + stage.name() + " failed (exit " + code + ") and the flow goes on");
                continue;
            }
            say(log, "[STOP] " + stage.name() + " failed (exit " + code + ")");
            exit = code;
            stopped = stage.name();
            break;
        }

        report(flowId, started, exit, stopped, log.toString());
        return exit;
    }

    /** The command of the stage, then the flow's options, then what ties the stage to this flow. */
    List<String> arguments(FlowFile.Stage stage, UUID flowId) {
        List<String> args = new ArrayList<>(List.of(stage.run().trim().split("\\s+")));
        args.addAll(options.toArguments());
        args.addAll(List.of("--parent-operation", flowId.toString()));
        if (stage.artifact() != null) {
            args.addAll(List.of("--artifact", base().resolve(stage.artifact()).toString()));
        }
        return args;
    }

    private static void refuseWhatCannotRun(FlowFile flow) {
        for (FlowFile.Stage stage : flow.stages()) {
            List<String> words = List.of(stage.run().trim().split("\\s+"));
            boolean known = COMMANDS.stream().anyMatch(command -> words.size() >= command.size()
                    && words.subList(0, command.size()).equals(command));
            if (!known) {
                throw new FlowFile.Invalid("stage " + stage.name() + " runs '" + stage.run() + "', which the flow cannot run"
                        + (words.get(0).startsWith("diff") ? " (drift is checked by the LiquiManager server, not by the CLI)" : ""));
            }
        }
    }

    private void report(UUID flowId, Instant started, int exit, String stopped, String log) {
        if (options.report != RunOptions.Toggle.on || options.lqmUrl == null || options.lqmUrl.isBlank()
                || options.project == null || options.environment == null) {
            return;
        }
        Path buffer = options.bufferDir == null || options.bufferDir.isBlank()
                ? Path.of(System.getProperty("user.home"), ".lqm", "buffer") : Path.of(options.bufferDir);
        LqmClient client = new LqmClient(new LqmConfig(options.lqmUrl, options.lqmToken, null, buffer));
        if (!client.isConfigured()) {
            return;
        }
        RunInfo info = new RunInfo("flow", options.source != null && !options.source.isBlank() ? options.source
                : RunInfo.detectCi(System.getenv()) != null ? "pipeline" : "cli-local",
                options.project, options.environment, options.connectionKey, options.changelogFile, options.contexts,
                options.labels, null, System.getenv("CI_PIPELINE_ID"), "cli", Version.current(),
                RunInfo.detectGit(System.getenv()), RunInfo.detectCi(System.getenv()));
        String status = exit == 0 ? "SUCCESS" : exit == 3 ? "BLOCKED" : "FAILURE";
        IngestPayload payload = new ReportBuilder(info, flowId).build(status, null, options.url, null, Optional.empty(),
                stopped == null ? null : "the flow stopped at stage " + stopped, exit == 0 ? null : exit, log, null);
        client.report(payload);
    }

    private Path base() {
        return (options.workdir == null || options.workdir.isBlank() ? Path.of("") : Path.of(options.workdir)).toAbsolutePath();
    }

    private static void say(StringBuilder log, String line) {
        log.append(line).append('\n');
        System.out.println(line);
    }
}
