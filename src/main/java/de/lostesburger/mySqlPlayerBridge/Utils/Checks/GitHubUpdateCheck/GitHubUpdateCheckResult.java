package de.lostesburger.mySqlPlayerBridge.Utils.Checks.GitHubUpdateCheck;

public class GitHubUpdateCheckResult {
    private final String releaseUrl;
    private final String repoName;
    private final String installedVersion;
    private final String latestVersion;
    private final boolean updateAvailable;

    public GitHubUpdateCheckResult(String releaseUrl, String repoName,
                             String installedVersion, String latestVersion,
                             boolean updateAvailable) {

        this.releaseUrl = releaseUrl;
        this.repoName = repoName;
        this.installedVersion = installedVersion;
        this.latestVersion = latestVersion;
        this.updateAvailable = updateAvailable;
    }

    public String getReleaseUrl() {return this.releaseUrl; }
    public String getRepoName() {return this.repoName; }
    public String getInstalledVersion() {return this.installedVersion; }
    public String getLatestVersion() {return this.latestVersion; }
    public boolean isUpdateAvailable() {return this.updateAvailable; }

    @Override
    public String toString() {
        return "GitHubCheckResult{" +
                "releaseUrl='" + releaseUrl + '\'' +
                ", repoName='" + repoName + '\'' +
                ", installedVersion='" + installedVersion + '\'' +
                ", latestVersion='" + latestVersion + '\'' +
                ", updateAvailable=" + updateAvailable +
                '}';
    }
}
