package io.github.carloslimajlle.liquimanager.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

/** {@code lqm login}: what it keeps, who can read it, and that a saved token only goes to the address it was saved for. */
class SavedLoginTest {

    @TempDir
    Path home;

    private String previousHome;
    private java.io.InputStream previousIn;

    @BeforeEach
    void isolate() {
        previousHome = System.getProperty("user.home");
        previousIn = System.in;
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", previousHome);
        System.setIn(previousIn);
    }

    private int login(String token, String... args) {
        System.setIn(new ByteArrayInputStream((token + "\n").getBytes(StandardCharsets.UTF_8)));
        java.io.PrintStream out = System.out;
        System.setOut(new java.io.PrintStream(new java.io.ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return new CommandLine(new Main()).execute(prepend(args));
        } finally {
            System.setOut(out);
        }
    }

    private static String[] prepend(String[] args) {
        String[] all = new String[args.length + 1];
        all[0] = "login";
        System.arraycopy(args, 0, all, 1, args.length);
        return all;
    }

    @Test
    @DisplayName("login saves the address and the token for its owner only, and logout removes them")
    void saves() throws IOException {
        assertThat(login("lqm_abc12345_secretsecretsecretsecretsecret12", "--lqm-url", "https://lqm.example/api/v1/", "--token-stdin")).isZero();

        Path file = home.resolve(".lqm").resolve("credentials");
        assertThat(file).exists();
        assertThat(SavedLogin.read(Map.of()).orElseThrow().url()).isEqualTo("https://lqm.example/api/v1");
        assertThat(SavedLogin.read(Map.of()).orElseThrow().token()).startsWith("lqm_abc12345_");
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertThat(Files.getPosixFilePermissions(file)).isEqualTo(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }

        assertThat(new CommandLine(new Main()).execute("logout")).isZero();
        assertThat(file).doesNotExist();
        assertThat(SavedLogin.read(Map.of())).isEmpty();
    }

    @Test
    @DisplayName("something that is not a service token, or no address, is refused and nothing is written")
    void refuses() {
        assertThat(login("password123", "--lqm-url", "https://lqm.example", "--token-stdin")).isEqualTo(2);
        assertThat(login("lqm_a_b", "--token-stdin")).isEqualTo(2);

        assertThat(home.resolve(".lqm").resolve("credentials")).doesNotExist();
    }

    @Test
    @DisplayName("the saved login fills only what was not given, and its token only goes to its own address")
    void usedOnlyForItsAddress() throws IOException {
        SavedLogin.write(Map.of(), "https://lqm.example", "lqm_saved_token");

        RunOptions none = new RunOptions();
        none.useSavedLogin(Map.of());
        assertThat(none.lqmUrl).isEqualTo("https://lqm.example");
        assertThat(none.lqmToken).isEqualTo("lqm_saved_token");

        RunOptions same = new RunOptions();
        same.lqmUrl = "https://LQM.example/api/v1/";
        same.useSavedLogin(Map.of());
        assertThat(same.lqmToken).as("the same server, spelled another way").isEqualTo("lqm_saved_token");

        RunOptions other = new RunOptions();
        other.lqmUrl = "https://elsewhere.example";
        other.useSavedLogin(Map.of());
        assertThat(other.lqmToken).as("never sent to another address").isNull();

        RunOptions given = new RunOptions();
        given.lqmUrl = "https://elsewhere.example";
        given.lqmToken = "lqm_from_variable";
        given.useSavedLogin(Map.of());
        assertThat(given.lqmToken).as("what was given wins").isEqualTo("lqm_from_variable");
    }

    @Test
    @DisplayName("a damaged file is ignored, not a reason to fail")
    void damaged() throws IOException {
        Files.createDirectories(home.resolve(".lqm"));
        Files.writeString(home.resolve(".lqm").resolve("credentials"), "{ not json");

        assertThat(SavedLogin.read(Map.of())).isEmpty();
    }
}
