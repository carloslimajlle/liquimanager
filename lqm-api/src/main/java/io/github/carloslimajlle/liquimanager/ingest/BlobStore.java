package io.github.carloslimajlle.liquimanager.ingest;

import java.util.Optional;

/**
 * Storage for large operation text (execution log, generated SQL).
 *
 * <p>docs/05 sends these to object storage (D-09: MinIO) and keeps only a reference
 * in the {@code operation} row. This interface is that seam: the filesystem
 * implementation serves local development and tests, and an S3/MinIO one slots in
 * behind the same contract without touching ingestion or the operation screens.
 */
public interface BlobStore {

    /** Stores the content and returns an opaque reference to keep in the database. */
    String put(String key, String content);

    Optional<String> get(String reference);
}
