package io.github.carloslimajlle.liquimanager.collector;

import java.util.Map;

import io.github.carloslimajlle.liquimanager.client.IngestPayload;

/**
 * Who is running what, where: everything about an operation that Liquibase itself does not
 * know. The CLI fills it from flags and the environment, the starter from properties.
 */
public record RunInfo(
        String command,
        String source,
        String project,
        String environment,
        String connectionKey,
        String changelogPath,
        String contexts,
        String labels,
        String tag,
        String deploymentId,
        String collector,
        String collectorVersion,
        IngestPayload.Git git,
        IngestPayload.Ci ci) {

    /** Fills git and CI from the pipeline's environment variables, when the run is inside one. */
    public static IngestPayload.Ci detectCi(Map<String, String> env) {
        if (env.get("GITLAB_CI") != null) {
            String iid = env.get("CI_MERGE_REQUEST_IID");
            String project = env.get("CI_MERGE_REQUEST_PROJECT_URL");
            return new IngestPayload.Ci("gitlab", env.get("CI_PIPELINE_ID"), env.get("CI_JOB_URL"), blankToNull(iid),
                    blankToNull(env.get("CI_MERGE_REQUEST_TITLE")),
                    iid == null || iid.isBlank() || project == null || project.isBlank() ? null : project + "/-/merge_requests/" + iid);
        }
        if (env.get("GITHUB_ACTIONS") != null) {
            String server = env.getOrDefault("GITHUB_SERVER_URL", "https://github.com");
            String run = env.get("GITHUB_RUN_ID");
            return new IngestPayload.Ci("github", run,
                    run == null ? null : server + "/" + env.get("GITHUB_REPOSITORY") + "/actions/runs/" + run);
        }
        if (env.get("JENKINS_URL") != null) {
            return new IngestPayload.Ci("jenkins", env.get("BUILD_NUMBER"), env.get("BUILD_URL"));
        }
        return null;
    }

    public static IngestPayload.Git detectGit(Map<String, String> env) {
        String commit = first(env, "CI_COMMIT_SHA", "GITHUB_SHA", "GIT_COMMIT");
        String branch = first(env, "CI_COMMIT_REF_NAME", "GITHUB_REF_NAME", "GIT_BRANCH");
        return commit == null && branch == null ? null : new IngestPayload.Git(commit, branch);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String first(Map<String, String> env, String... names) {
        for (String name : names) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
