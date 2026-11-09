package io.github.carloslimajlle.liquimanager.ingest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Object-storage {@link BlobStore} (D-09), for when the API runs in more than one replica: a log written by one replica has to be read
 * by the others, which a local directory cannot do. Any S3-compatible service works (RustFS and MinIO in the local environment).
 *
 * <p>Turned on with {@code lqm.storage.type=s3}. The reference is {@code s3:<key>}. Requests are signed with AWS Signature V4 here,
 * with the JDK's own HTTP client, so the API carries no cloud SDK for two calls (PUT and GET of an object, and creating the bucket once).
 * Path-style addressing ({@code <endpoint>/<bucket>/<key>}), which is what the S3-compatible services expect.
 */
@Component
@ConditionalOnProperty(name = "lqm.storage.type", havingValue = "s3")
public class S3BlobStore implements BlobStore {

    static final String SCHEME = "s3:";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String endpoint;
    private final String region;
    private final String bucket;
    private final String accessKey;
    private final String secretKey;
    private volatile boolean bucketReady;

    public S3BlobStore(@Value("${lqm.storage.s3.endpoint}") String endpoint,
                       @Value("${lqm.storage.s3.region:us-east-1}") String region,
                       @Value("${lqm.storage.s3.bucket:lqm}") String bucket,
                       @Value("${lqm.storage.s3.access-key}") String accessKey,
                       @Value("${lqm.storage.s3.secret-key}") String secretKey) {
        this.endpoint = endpoint.replaceAll("/+$", "");
        this.region = region;
        this.bucket = bucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public String put(String key, String content) {
        ensureBucket();
        HttpResponse<byte[]> response = send("PUT", "/" + bucket + "/" + key, content.getBytes(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            // The status only: the body of an object-storage error can name the bucket and the key.
            throw new UncheckedIOException(new IOException("object storage answered " + response.statusCode() + " storing a blob"));
        }
        return SCHEME + key;
    }

    @Override
    public Optional<String> get(String reference) {
        if (reference == null || !reference.startsWith(SCHEME)) {
            return Optional.empty();
        }
        HttpResponse<byte[]> response = send("GET", "/" + bucket + "/" + reference.substring(SCHEME.length()), new byte[0]);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw new UncheckedIOException(new IOException("object storage answered " + response.statusCode() + " reading a blob"));
        }
        return Optional.of(new String(response.body(), StandardCharsets.UTF_8));
    }

    /** Creates the bucket the first time: a fresh object store has none. A bucket that already exists is not an error. */
    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        HttpResponse<byte[]> response = send("PUT", "/" + bucket, new byte[0]);
        int status = response.statusCode();
        if (status / 100 == 2 || status == 409) {
            bucketReady = true;
            return;
        }
        throw new UncheckedIOException(new IOException("object storage answered " + status + " creating the bucket"));
    }

    // ------------------------------------------------------------------ signing (AWS Signature Version 4)

    private HttpResponse<byte[]> send(String method, String path, byte[] body) {
        URI uri = URI.create(endpoint + encodePath(path));
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String amzDate = AMZ_DATE.format(now);
        String day = DAY.format(now);
        String payloadHash = hex(sha256(body));
        String host = uri.getPort() == -1 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();

        String canonicalHeaders = "host:" + host + "\n" + "x-amz-content-sha256:" + payloadHash + "\n" + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + uri.getRawPath() + "\n" + "" + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = day + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] key = hmac(hmac(hmac(hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day), region), "s3"), "aws4_request");
        String signature = hex(hmac(key, stringToSign));

        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                        + ", Signature=" + signature)
                .method(method, body.length == 0 && "GET".equals(method) ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("object storage request interrupted"));
        } catch (IOException e) {
            throw new UncheckedIOException("could not reach the object storage", e);
        }
    }

    /** Each path segment is percent-encoded the way S3 signs it (unreserved characters stay, {@code /} separates). */
    static String encodePath(String path) {
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~"));
        }
        return out.toString();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
