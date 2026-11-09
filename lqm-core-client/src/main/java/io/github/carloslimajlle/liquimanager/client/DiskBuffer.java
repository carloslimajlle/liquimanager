package io.github.carloslimajlle.liquimanager.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reports that could not be delivered, kept as one JSON file each until a later run can
 * send them. This is what makes fail-open lossless: LiquiManager being down must not
 * stop a deploy, and it should not erase the record of it either.
 *
 * <p>The buffer holds payloads only, which carry no credentials (RNF-06).
 */
final class DiskBuffer {

    private static final String SUFFIX = ".json";

    private final Path dir;

    DiskBuffer(Path dir) {
        this.dir = dir;
    }

    boolean isEnabled() {
        return dir != null;
    }

    void store(IngestPayload payload) throws IOException {
        Files.createDirectories(dir);
        Path target = dir.resolve(payload.operationId() + SUFFIX);
        Path temp = dir.resolve(payload.operationId() + ".tmp");
        Files.write(temp, Json.MAPPER.writeValueAsBytes(payload));
        // Rename last: a crash mid-write must never leave a half file that looks complete.
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    List<Path> pending() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(SUFFIX)).sorted().toList();
        }
    }

    IngestPayload read(Path file) throws IOException {
        return Json.MAPPER.readValue(Files.readAllBytes(file), IngestPayload.class);
    }

    void discard(Path file) throws IOException {
        Files.deleteIfExists(file);
    }
}
