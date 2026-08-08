package io.tinox.central.frontend;

/**
 * Mirrors the backend's PackageSummary JSON shape
 * (registry-backend/src/PackageSummary.tnx) -- one row of GET /api/v1/packages.
 */
public class PackageSummary {

    public String group;
    public String artifactId;
    public String latestVersion;
    public long versionCount;
    public long latestPublishedAt;
}
