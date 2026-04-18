package de.lostesburger.mySqlPlayerBridge.Utils.Checks.GitHubUpdateCheck;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class GitHubUpdateCheck {
    private static final String DEFAULT_RELEASE_URL = "https://github.com/Lostes-Burger/MySqlPlayerBridge/releases/latest";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final String repoUrl;
    private final String installedVersion;
    private final HttpClient client;

    public GitHubUpdateCheck(String repoUrl, String installedVersion) {
        this.repoUrl = repoUrl;
        this.installedVersion = installedVersion == null ? "" : installedVersion.trim();
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public GitHubUpdateCheckResult checkForUpdate() {
        String repoName = this.extractRepo(this.repoUrl);
        String ownerRepo = extractOwnerAndRepo(repoUrl);
        if (ownerRepo == null || ownerRepo.isBlank()) {
            return failure(repoName, "Invalid GitHub repository URL.");
        }

        String apiUrl = "https://api.github.com/repos/" + ownerRepo + "/releases?per_page=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "MySqlPlayerBridge-UpdateChecker")
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            return failure(repoName, "GitHub API is unreachable (" + getExceptionMessage(exception) + ").");
        }

        if (response.statusCode() != 200) {
            String detail = response.statusCode() == 403
                    ? "GitHub API rate limit reached."
                    : "GitHub API returned status " + response.statusCode() + ".";
            return failure(repoName, detail);
        }

        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return failure(repoName, "GitHub API returned an empty response.");
        }

        JsonArray releases;
        try {
            JsonElement root = JsonParser.parseString(responseBody);
            if (!root.isJsonArray()) {
                return failure(repoName, "GitHub API returned invalid release data.");
            }
            releases = root.getAsJsonArray();
        } catch (Exception exception) {
            return failure(repoName, "GitHub API returned invalid JSON.");
        }

        if (releases.isEmpty()) {
            return failure(repoName, "No GitHub releases found.");
        }

        JsonElement latestElement = releases.get(0);
        if (!latestElement.isJsonObject()) {
            return failure(repoName, "GitHub release entry is invalid.");
        }
        JsonObject latestRelease = latestElement.getAsJsonObject();

        String latestVersion = safeGetString(latestRelease, "tag_name");
        String releaseUrl = safeGetString(latestRelease, "html_url");
        boolean preRelease = safeGetBoolean(latestRelease, "prerelease");

        if (latestVersion == null || latestVersion.isBlank()) {
            return failure(repoName, "GitHub response does not contain a valid tag.", releaseUrl);
        }

        String cleanLatestVersion = latestVersion.trim();
        boolean updateAvailable = !this.installedVersion.equals(cleanLatestVersion);

        return new GitHubUpdateCheckResult(
                releaseUrl == null || releaseUrl.isBlank() ? DEFAULT_RELEASE_URL : releaseUrl,
                repoName,
                this.installedVersion,
                cleanLatestVersion,
                updateAvailable,
                preRelease,
                true,
                ""
        );
    }

    private String extractOwnerAndRepo(String url) {
        String[] p = normalizeRepoUrl(url).split("/");
        if (p.length < 2) {
            return null;
        }
        return p[p.length - 2] + "/" + p[p.length - 1];
    }

    private String extractRepo(String url){
        String[] p = normalizeRepoUrl(url).split("/");
        return p.length == 0 ? "" : p[p.length - 1];
    }

    private String safeGetString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            return json.get(key).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean safeGetBoolean(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return false;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeRepoUrl(String rawUrl) {
        String normalized = rawUrl == null ? "" : rawUrl.trim();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return normalized;
    }

    private GitHubUpdateCheckResult failure(String repoName, String errorMessage) {
        return failure(repoName, errorMessage, DEFAULT_RELEASE_URL);
    }

    private GitHubUpdateCheckResult failure(String repoName, String errorMessage, String releaseUrl) {
        String cleanReleaseUrl = (releaseUrl == null || releaseUrl.isBlank()) ? DEFAULT_RELEASE_URL : releaseUrl;
        String cleanErrorMessage = (errorMessage == null || errorMessage.isBlank()) ? "Unknown update check error." : errorMessage;
        return new GitHubUpdateCheckResult(
                cleanReleaseUrl,
                repoName == null ? "" : repoName,
                this.installedVersion,
                this.installedVersion,
                false,
                false,
                false,
                cleanErrorMessage
        );
    }

    private String getExceptionMessage(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }

}
