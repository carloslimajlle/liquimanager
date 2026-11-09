package io.github.carloslimajlle.liquimanager.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Filesystem {@link BlobStore}, for local development and tests. The reference is
 * {@code fs:<key>}; keys are resolved under one root and path traversal is refused.
 */
@Component
public class FileSystemBlobStore implements BlobStore {

    private static final String SCHEME = "fs:";

    private final Path root;

    public FileSystemBlobStore(@Value("${lqm.storage.dir:./data/blobs}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    @Override
    public String put(String key, String content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("could not store blob " + key, e);
        }
        return SCHEME + key;
    }

    @Override
    public Optional<String> get(String reference) {
        if (reference == null || !reference.startsWith(SCHEME)) {
            return Optional.empty();
        }
        Path target = resolve(reference.substring(SCHEME.length()));
        try {
            return Files.exists(target) ? Optional.of(Files.readString(target, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read blob " + reference, e);
        }
    }

    private Path resolve(String key) {
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("blob key escapes the storage root: " + key);
        }
        return target;
    }
}
