package io.tinox.central.frontend;

/**
 * Mirrors the backend's PackageMeta JSON shape
 * (registry-backend/src/PackageMeta.tnx) -- one published version.
 */
public class PackageMeta {

    public String group;
    public String artifactId;
    public String version;
    public String filename;
    public String sha256;
    public long sizeBytes;
    public long publishedAt;
}
