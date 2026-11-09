package io.github.carloslimajlle.liquimanager.ingest;

import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The {@link BlobStore} the rest of the API uses. New blobs go to object storage when it is configured ({@code lqm.storage.type=s3}, what
 * a deployment with more than one replica needs) and to the local directory otherwise. Reading follows the reference: {@code s3:...} goes to
 * object storage and {@code fs:...} to the directory, so a log written before the switch is still readable after it.
 */
@Primary
@Component
class RoutingBlobStore implements BlobStore {

    private final FileSystemBlobStore files;
    private final ObjectProvider<S3BlobStore> objects;

    RoutingBlobStore(FileSystemBlobStore files, ObjectProvider<S3BlobStore> objects) {
        this.files = files;
        this.objects = objects;
    }

    @Override
    public String put(String key, String content) {
        S3BlobStore s3 = objects.getIfAvailable();
        return s3 != null ? s3.put(key, content) : files.put(key, content);
    }

    @Override
    public Optional<String> get(String reference) {
        if (reference != null && reference.startsWith(S3BlobStore.SCHEME)) {
            S3BlobStore s3 = objects.getIfAvailable();
            return s3 == null ? Optional.empty() : s3.get(reference);
        }
        return files.get(reference);
    }
}
