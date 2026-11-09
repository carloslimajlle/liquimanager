package io.github.carloslimajlle.liquimanager.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

/** The object-storage store: path encoding and routing without a server; the real round trip only against one (LQM_S3_TEST_ENDPOINT). */
class S3BlobStoreTest {

    @Test
    @DisplayName("each path segment is percent-encoded the way S3 signs it, the slashes stay")
    void encodesSegments() {
        assertThat(S3BlobStore.encodePath("/lqm/operations/0d7c/log")).isEqualTo("/lqm/operations/0d7c/log");
        assertThat(S3BlobStore.encodePath("/lqm/a b/c+d/é")).isEqualTo("/lqm/a%20b/c%2Bd/%C3%A9");
        assertThat(S3BlobStore.encodePath("/lqm")).isEqualTo("/lqm");
    }

    @Test
    @DisplayName("without object storage everything goes to the directory, and an s3 reference is simply not found")
    void routesToTheDirectory(@TempDir Path dir) {
        @SuppressWarnings("unchecked")
        ObjectProvider<S3BlobStore> none = org.mockito.Mockito.mock(ObjectProvider.class);
        RoutingBlobStore store = new RoutingBlobStore(new FileSystemBlobStore(dir.toString()), none);

        String reference = store.put("operations/" + UUID.randomUUID() + "/log", "hello");

        assertThat(reference).startsWith("fs:");
        assertThat(store.get(reference)).contains("hello");
        assertThat(store.get("s3:operations/x/log")).isEmpty();
        assertThat(store.get(null)).isEmpty();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "LQM_S3_TEST_ENDPOINT", matches = ".+")
    @DisplayName("against a real S3-compatible server: the bucket is created, a blob is written and read back, a missing one is empty")
    void realRoundTrip() {
        S3BlobStore store = new S3BlobStore(System.getenv("LQM_S3_TEST_ENDPOINT"), "us-east-1", "lqm-test-" + UUID.randomUUID().toString().substring(0, 8),
                System.getenv("LQM_S3_TEST_ACCESS_KEY"), System.getenv("LQM_S3_TEST_SECRET_KEY"));
        String key = "operations/" + UUID.randomUUID() + "/log";

        String reference = store.put(key, "linha 1\nlinha 2 – acentuação");

        assertThat(reference).isEqualTo("s3:" + key);
        assertThat(store.get(reference)).contains("linha 1\nlinha 2 – acentuação");
        assertThat(store.get("s3:operations/" + UUID.randomUUID() + "/log")).isEmpty();
    }
}
