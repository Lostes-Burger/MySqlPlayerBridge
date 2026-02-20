package de.lostesburger.mySqlPlayerBridge.Utils.Checks.GitHubUpdateCheck;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class GitHubUpdateCheck {
    private final String repoUrl;
    private final String installedVersion;
    private final HttpClient client;

    public GitHubUpdateCheck(String repoUrl, String installedVersion) {
        this.repoUrl = repoUrl;
        this.installedVersion = installedVersion;
        this.client = HttpClient.newHttpClient();
    }

    public GitHubUpdateCheckResult checkForUpdate() throws Exception {

        String ownerRepo = extractOwnerAndRepo(repoUrl);
        String apiUrl = "https://api.github.com/repos/" + ownerRepo + "/releases/latest";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Accept", "application/vnd.github.v3+json")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            if (response.statusCode() == 403) {
                System.out.println("GitHub API rate limit. Trying again later...");
            } else {
                System.out.println("GitHub API returned " + response.statusCode());
            }
            return new GitHubUpdateCheckResult(
                    "",
                    this.extractRepo(this.repoUrl),
                    installedVersion,
                    installedVersion,
                    false
            );
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();

        String latestVersion = safeGetString(json, "tag_name");
        String releaseUrl = safeGetString(json, "html_url");
        if (latestVersion == null) {
            return new GitHubUpdateCheckResult(
                    releaseUrl == null ? "" : releaseUrl,
                    this.extractRepo(this.repoUrl),
                    installedVersion,
                    installedVersion,
                    false
            );
        }

        boolean updateAvailable = compareVersions(installedVersion, latestVersion);

        return new GitHubUpdateCheckResult(
                releaseUrl == null ? "" : releaseUrl,
                this.extractRepo(this.repoUrl),
                installedVersion,
                latestVersion,
                updateAvailable
        );
    }

    private String extractOwnerAndRepo(String url) {
        String[] p = url.split("/");
        return p[3] + "/" + p[4];
    }

    private String extractRepo(String url){
        String[] p = url.split("/");
        return p[4];
    }

    private boolean compareVersions(String installed, String latest) {
        return installed.compareTo(latest) < 0;
    }

    private String safeGetString(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        return json.get(key).getAsString();
    }



}
