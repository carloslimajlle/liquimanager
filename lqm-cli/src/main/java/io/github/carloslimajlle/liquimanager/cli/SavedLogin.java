package io.github.carloslimajlle.liquimanager.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What {@code lqm login} keeps in {@code ~/.lqm/credentials} (or under {@code LQM_HOME}): the LiquiManager address and a service token, for a
 * person running the CLI from their own machine. A pipeline does not use it (it has LQM_URL and a masked LQM_TOKEN).
 *
 * <p>The token is plain text in a file only its owner can read, which is the trade of every CLI that has a login; the file never holds a
 * database credential. A saved token is only ever sent to the address it was saved for.
 */
final class SavedLogin {

    record Entry(String url, String token) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private SavedLogin() {
    }

    static Path file(Map<String, String> env) {
        String home = env.get("LQM_HOME");
        Path dir = home != null && !home.isBlank() ? Path.of(home) : Path.of(System.getProperty("user.home"), ".lqm");
        return dir.resolve("credentials");
    }

    /** The saved login, or empty when there is none or the file cannot be read (a damaged file is not a reason to fail a deploy). */
    static Optional<Entry> read(Map<String, String> env) {
        Path file = file(env);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            var node = JSON.readTree(Files.readAllBytes(file));
            String url = node.path("url").asText(null);
            String token = node.path("token").asText(null);
            return url == null || url.isBlank() || token == null || token.isBlank() ? Optional.empty() : Optional.of(new Entry(url, token));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Writes the login, readable by its owner only where the file system has owners. Returns the file. */
    static Path write(Map<String, String> env, String url, String token) throws IOException {
        Path file = file(env);
        Files.createDirectories(file.getParent());
        byte[] content = JSON.writeValueAsBytes(Map.of("url", url.replaceAll("/+$", ""), "token", token));
        // Create it private first, then fill it: there is no moment when the token sits in a file others can read.
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException notPosix) {
            Files.createFile(file);
        }
        Files.write(file, content);
        return file;
    }

    static boolean delete(Map<String, String> env) throws IOException {
        return Files.deleteIfExists(file(env));
    }

    /** The same address, spelled with or without a trailing slash or the /api/v1 suffix. */
    static boolean sameServer(String a, String b) {
        return normalise(a).equals(normalise(b));
    }

    private static String normalise(String url) {
        return url == null ? "" : url.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("/+$", "").replaceAll("/api/v1$", "");
    }
}
