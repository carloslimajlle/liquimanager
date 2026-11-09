package io.github.carloslimajlle.liquimanager.developer;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.common.LqmException;

/**
 * Reads a file of a project's repository through GitLab, with the read token that is configuration
 * ({@code lqm.gitlab.read-token}) and never leaves the backend. The one place that talks to the repository API for reading: the
 * Developer Mode screen and the changeset screen both go through here.
 */
@Component
public class RepositoryFiles {

    private static final int MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_CANDIDATES = 8;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String gitlabUrl;
    private final String readToken;

    RepositoryFiles(@Value("${lqm.gitlab.base-url:}") String gitlabUrl, @Value("${lqm.gitlab.read-token:}") String readToken) {
        this.gitlabUrl = gitlabUrl;
        this.readToken = readToken;
    }

    public boolean configured() {
        return !gitlabUrl.isBlank() && !readToken.isBlank();
    }

    /** The file's text, or null when GitLab has no such file there. Any other answer is a 502 with the status only. */
    public String fetch(String project, String file, String ref) {
        String base = gitlabUrl.endsWith("/") ? gitlabUrl.substring(0, gitlabUrl.length() - 1) : gitlabUrl;
        URI uri = URI.create(base + "/api/v4/projects/" + encode(project) + "/repository/files/" + encode(file)
                + "/raw?ref=" + encode(ref));
        try {
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("PRIVATE-TOKEN", readToken).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() / 100 != 2) {
                throw new LqmException(HttpStatus.BAD_GATEWAY, "LQM-070", "GitLab answered " + response.statusCode());
            }
            if (response.body().length > MAX_FILE_BYTES) {
                throw LqmException.invalid("the file is larger than " + MAX_FILE_BYTES / 1000 + " KB");
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LqmException(HttpStatus.BAD_GATEWAY, "LQM-070", "GitLab could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LqmException(HttpStatus.BAD_GATEWAY, "LQM-070", "GitLab could not be reached");
        }
    }

    /**
     * Liquibase reports a changeset's file relative to the changelog's root, which is usually a subfolder of the
     * repository (src/main/resources). So the path as given is tried first, then under each folder above the master
     * changelog, deepest first.
     */
    public static List<String> candidates(String path, String masterPath) {
        List<String> out = new ArrayList<>();
        out.add(path);
        String folder = masterPath.contains("/") ? masterPath.substring(0, masterPath.lastIndexOf('/')) : "";
        while (!folder.isEmpty() && out.size() < MAX_CANDIDATES) {
            out.add(folder + "/" + path);
            folder = folder.contains("/") ? folder.substring(0, folder.lastIndexOf('/')) : "";
        }
        return out;
    }

    /** "https://gitlab.x/group/sub/project.git" -> "group/sub/project". */
    public static String projectPath(String repoUrl) {
        String path = URI.create(repoUrl.trim()).getPath();
        if (path == null) {
            throw LqmException.invalid("the repository URL has no path");
        }
        path = path.replaceAll("^/+", "").replaceAll("/+$", "").replaceAll("\\.git$", "");
        if (path.isEmpty()) {
            throw LqmException.invalid("the repository URL has no project path");
        }
        return path;
    }

    private static String encode(String value) {
        // GitLab wants the slashes of a path encoded too; URLEncoder turns spaces into "+", which a path does not read.
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
