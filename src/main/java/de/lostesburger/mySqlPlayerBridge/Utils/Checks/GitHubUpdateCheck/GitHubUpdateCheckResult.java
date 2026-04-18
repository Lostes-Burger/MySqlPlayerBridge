package de.lostesburger.mySqlPlayerBridge.Utils.Checks.GitHubUpdateCheck;

public class GitHubUpdateCheckResult {
    private final String releaseUrl;
    private final String repoName;
    private final String installedVersion;
    private final String latestVersion;
    private final boolean updateAvailable;
    private final boolean preRelease;
    private final boolean success;
    private final String errorMessage;

    public GitHubUpdateCheckResult(String releaseUrl, String repoName,
                             String installedVersion, String latestVersion,
                             boolean updateAvailable, boolean preRelease,
                             boolean success, String errorMessage) {

        this.releaseUrl = releaseUrl;
        this.repoName = repoName;
        this.installedVersion = installedVersion;
        this.latestVersion = latestVersion;
        this.updateAvailable = updateAvailable;
        this.preRelease = preRelease;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public String getReleaseUrl() {return this.releaseUrl; }
    public String getRepoName() {return this.repoName; }
    public String getInstalledVersion() {return this.installedVersion; }
    public String getLatestVersion() {return this.latestVersion; }
    public boolean isUpdateAvailable() {return this.updateAvailable; }
    public boolean isPreRelease() {return this.preRelease; }
    public boolean isSuccess() {return this.success; }
    public String getErrorMessage() {return this.errorMessage; }

    @Override
    public String toString() {
        return "GitHubCheckResult{" +
                "releaseUrl='" + releaseUrl + '\'' +
                ", repoName='" + repoName + '\'' +
                ", installedVersion='" + installedVersion + '\'' +
                ", latestVersion='" + latestVersion + '\'' +
                ", updateAvailable=" + updateAvailable +
                ", preRelease=" + preRelease +
                ", success=" + success +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
