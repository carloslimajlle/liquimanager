package io.github.carloslimajlle.liquimanager.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.github.carloslimajlle.liquimanager.client.LqmClient;
import io.github.carloslimajlle.liquimanager.client.LqmConfig;
import io.github.carloslimajlle.liquimanager.client.RunnerJob;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code lqm runner}: takes approved rollbacks from LiquiManager and carries them out (docs/04 section 4.4).
 *
 * <p>It PULLS, so it runs anywhere that reaches both LiquiManager and the database, and it does not care what starts it.
 * Without {@code --once} it is a plain service: it asks for work every {@code --poll-seconds}, does it, asks again, and on
 * SIGTERM finishes the rollback in hand before it leaves, so a restart, a deploy or {@code systemctl stop} never cuts one in
 * half. With {@code --once} it takes at most one rollback and exits, for whatever scheduler a site already has (cron, a
 * scheduled pipeline, a Kubernetes CronJob). Several runners at once are safe: the claim is atomic on the server. Credentials of
 * the database are the runner's own (LQM_DB_USERNAME and LQM_DB_PASSWORD, or whatever injects them); LiquiManager sends none.
 *
 * <p>The changelog comes from {@code --git-url} at the commit of the ORIGINAL operation, so the rollback is of
 * the exact changeset that ran and not of whatever the branch says today; without {@code --git-url} the working
 * directory is assumed to be checked out already at the right commit. The rollback itself is the ordinary
 * {@code rollback-to-changeset} or {@code rollback --tag} command, reported as a new operation that answers the
 * original one.
 */
@Command(name = "runner", mixinStandardHelpOptions = true,
        description = "Carries out approved rollbacks taken from LiquiManager.")
final class RunnerCommand implements Callable<Integer> {

    @Option(names = "--lqm-url", description = "LiquiManager base URL.", defaultValue = "${env:LQM_URL:-}")
    String lqmUrl;

    @Option(names = "--lqm-token", description = "Service token with the runner scope. Prefer LQM_TOKEN.",
            defaultValue = "${env:LQM_TOKEN:-}")
    String lqmToken;

    @Option(names = "--once", description = "Take at most one rollback and exit (for a scheduler: cron, a scheduled pipeline).")
    boolean once;

    @Option(names = "--poll-seconds", description = "Wait between looks for work when not --once.", defaultValue = "10")
    int pollSeconds;

    @Option(names = "--grace-seconds", description = "On SIGTERM, how long to let a rollback in progress finish before giving up.",
            defaultValue = "600")
    int graceSeconds;

    @Option(names = "--workdir", description = "Checkout of the project, when --git-url is not given.",
            defaultValue = "${env:LQM_WORKDIR:-}")
    String workdir;

    @Option(names = "--git-url", description = "Repository to clone the changelog from, at the original commit.",
            defaultValue = "${env:LQM_GIT_URL:-}")
    String gitUrl;

    @Override
    public Integer call() {
        if (lqmUrl == null || lqmUrl.isBlank() || lqmToken == null || lqmToken.isBlank()) {
            System.err.println("lqm: the runner needs --lqm-url and a token (LQM_TOKEN)");
            return 2;
        }
        LqmClient client = new LqmClient(new LqmConfig(lqmUrl, lqmToken, Duration.ofSeconds(10), null));
        CountDownLatch stop = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        if (!once) {
            leaveGracefully(stop, finished);
            System.out.println("lqm runner: waiting for approved rollbacks every " + Math.max(1, pollSeconds) + "s");
        }
        try {
            return run(client, stop);
        } finally {
            finished.countDown();
        }
    }

    /**
     * SIGTERM (a restart, a deploy, {@code systemctl stop}) asks the loop to stop and then WAITS for it: the JVM does not exit
     * until this hook returns, so a rollback in hand is finished, not cut in half. After {@code --grace-seconds} it gives up
     * and the process ends; the request then stays RUNNING until the server's timeout job fails it.
     */
    private void leaveGracefully(CountDownLatch stop, CountDownLatch finished) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop.countDown();
            boolean done = false;
            try {
                // The end of the LOOP, not of the main thread: by then main is calling System.exit, which blocks while a shutdown
                // is in progress, and waiting for it would hold the process until somebody kills it.
                done = finished.await(Math.max(1, graceSeconds), TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Asked to stop and stopped cleanly is a success (0), not the JVM's 143 for "died of SIGTERM", which a supervisor
            // such as systemd reads as a failure; running out of grace with a rollback still going is not.
            Runtime.getRuntime().halt(done ? 0 : 1);
        }, "lqm-runner-shutdown"));
    }

    /** The loop: work until the latch is released (or, with {@code --once}, after one look). Package-private for the test. */
    int run(LqmClient client, CountDownLatch stop) {
        int exit = 0;
        while (stop.getCount() > 0) {
            Optional<RunnerJob> job = client.claimRollback();
            if (job.isPresent()) {
                exit = Math.max(exit, execute(client, job.get()));
            } else if (once) {
                System.out.println("lqm runner: nothing to do");
            }
            if (once) {
                return exit;
            }
            if (job.isEmpty()) {
                try {
                    // Asked to stop while idle: leave at once instead of finishing the wait.
                    if (stop.await(Math.max(1, pollSeconds), TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        System.out.println("lqm runner: stopped");
        return exit;
    }

    private int execute(LqmClient client, RunnerJob job) {
        UUID result = UUID.randomUUID();
        Path checkout = null;
        try {
            Path root = workdir == null || workdir.isBlank() ? null : Path.of(workdir);
            if (gitUrl != null && !gitUrl.isBlank()) {
                checkout = clone(gitUrl, job.gitCommit());
                root = checkout;
            }
            int exit = new CommandLine(new Main()).execute(arguments(job, result, root).toArray(new String[0]));
            client.completeRollback(job.id(), exit == 0, result, exit == 0 ? null : "the rollback exited with code " + exit
                    + "; the operation " + result + " has the detail");
            return exit == 0 ? 0 : 1;
        } catch (Exception e) {
            client.completeRollback(job.id(), false, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            System.err.println("lqm runner: " + e.getMessage());
            return 1;
        } finally {
            deleteQuietly(checkout);
        }
    }

    /** The ordinary command a person would have typed, with the identity of the request filled in. */
    List<String> arguments(RunnerJob job, UUID resultOperation, Path root) {
        List<String> args = new ArrayList<>();
        if (job.isTag()) {
            args.addAll(List.of("rollback", "--tag", job.tag()));
        } else {
            // Back to the changeset: it and everything after it, newest first (Liquibase's own order), and exactly what was shown.
            args.addAll(List.of("rollback-to-changeset", "--changeset-id", job.changeset().id()));
            if (job.plan() != null) {
                job.plan().forEach(entry -> args.addAll(List.of("--expect", entry)));
            }
            if (job.changeset().author() != null) {
                args.addAll(List.of("--author", job.changeset().author()));
            }
            if (job.changeset().filePath() != null) {
                args.addAll(List.of("--changeset-file", job.changeset().filePath()));
            }
        }
        args.addAll(List.of("--url", job.connection().jdbcUrl(), "--changelog-file", job.changelogPath(),
                "--env", job.environment(), "--project", job.project() == null ? "" : job.project(),
                "--connection-key", job.connection().key(), "--lqm-url", lqmUrl, "--lqm-token", lqmToken,
                "--parent-operation", job.parentOperationId().toString(), "--operation-id", resultOperation.toString(),
                "--source", "ui", "--checks", "off"));
        if (root != null) {
            args.addAll(List.of("--workdir", root.toString()));
        }
        if (job.contexts() != null && !job.contexts().isBlank()) {
            args.addAll(List.of("--contexts", job.contexts()));
        }
        return args;
    }

    private static Path clone(String url, String commit) throws IOException, InterruptedException {
        Path dir = Files.createTempDirectory("lqm-runner");
        git(dir.getParent(), "clone", "--quiet", url, dir.toString());
        if (commit != null && !commit.isBlank()) {
            git(dir, "checkout", "--quiet", commit);
        }
        return dir;
    }

    private static void git(Path in, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            // The URL may carry a token; the error is git's own words without echoing the command.
            throw new IOException("git " + args[0] + " failed: " + output.strip());
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a leftover temporary checkout is not worth failing a rollback for
        }
    }
}
