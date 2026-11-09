package io.github.carloslimajlle.liquimanager.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code lqm login}: keeps the LiquiManager address and a service token in {@code ~/.lqm/credentials}, so a person running the CLI from their
 * own machine does not type them every time (docs/07 § 2). Not for pipelines: they have {@code LQM_URL} and a masked {@code LQM_TOKEN}.
 * The token is read from the console without echo, or from the first line of the standard input (so it can be piped), or from LQM_TOKEN;
 * it is never taken from a flag, which a process list would show.
 */
@Command(name = "login", mixinStandardHelpOptions = true,
        description = "Saves the LiquiManager address and token in ~/.lqm/credentials, for the CLI on your own machine.")
final class LoginCommand implements Callable<Integer> {

    @Option(names = "--lqm-url", description = "LiquiManager base URL.", defaultValue = "${env:LQM_URL:-}")
    String url;

    @Option(names = "--token-stdin", description = "Read the token from the standard input instead of asking for it.")
    boolean tokenFromStdin;

    @Override
    public Integer call() throws IOException {
        if (url == null || url.isBlank()) {
            System.err.println("lqm: missing --lqm-url (or LQM_URL)");
            return 2;
        }
        String token = System.getenv("LQM_TOKEN");
        if (token == null || token.isBlank()) {
            token = readToken();
        }
        if (token == null || token.isBlank()) {
            System.err.println("lqm: no token: set LQM_TOKEN, type it when asked, or pipe it with --token-stdin");
            return 2;
        }
        token = token.trim();
        if (!token.startsWith("lqm_")) {
            System.err.println("lqm: that does not look like a LiquiManager service token (they start with lqm_)");
            return 2;
        }
        Path saved = SavedLogin.write(System.getenv(), url.trim(), token);
        System.out.println("Saved to " + saved.toString().replace(java.io.File.separatorChar, '/') + " (readable by you only). Remove it with `lqm logout`.");
        return 0;
    }

    private String readToken() throws IOException {
        var console = System.console();
        if (console != null && !tokenFromStdin) {
            char[] typed = console.readPassword("LiquiManager token: ");
            return typed == null ? null : new String(typed);
        }
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    }

    @Command(name = "logout", mixinStandardHelpOptions = true, description = "Removes what `lqm login` saved.")
    static final class Logout implements Callable<Integer> {

        @Override
        public Integer call() throws IOException {
            System.out.println(SavedLogin.delete(System.getenv()) ? "Removed." : "There was nothing saved.");
            return 0;
        }
    }
}
