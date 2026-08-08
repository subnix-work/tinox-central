package io.tinox.central.frontend;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;

/**
 * Thin REST client against the tinox-central registry backend
 * (registry-backend/) -- the frontend is just another consumer of the
 * same public API tinox's own package manager would use, no special
 * access (see ../PLAN.md, section 2: "the frontend has no special
 * access, it's just the first 'real' consumer besides the CLI").
 */
@ApplicationScoped
public class RegistryClient {

    @ConfigProperty(name = "registry.backend.url", defaultValue = "http://localhost:8080")
    String backendUrl;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public List<PackageSummary> listPackages() throws IOException, InterruptedException {
        HttpResponse<String> response = get("/api/v1/packages");
        if (response.statusCode() != 200) {
            throw new IOException("GET /api/v1/packages failed: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), mapper.getTypeFactory()
                .constructCollectionType(List.class, PackageSummary.class));
    }

    /** Empty list (not an error) if the package doesn't exist -- lets callers
     * distinguish "no such package" from a real backend failure via the
     * exception path. */
    public List<PackageMeta> listVersions(String group, String artifactId) throws IOException, InterruptedException {
        HttpResponse<String> response = get("/api/v1/" + encode(group) + "/" + encode(artifactId));
        if (response.statusCode() == 404) {
            return Collections.emptyList();
        }
        if (response.statusCode() != 200) {
            throw new IOException("GET /api/v1/" + group + "/" + artifactId + " failed: HTTP " + response.statusCode());
        }
        return mapper.readValue(response.body(), mapper.getTypeFactory()
                .constructCollectionType(List.class, PackageMeta.class));
    }

    /** Builds the direct download URL for a version -- the browser/user
     * downloads straight from the backend, the frontend never proxies the
     * (base64-wrapped, see PLAN.md 7.1) artifact bytes through itself. */
    public String downloadUrl(String group, String artifactId, String version) {
        return backendUrl + "/api/v1/" + encode(group) + "/" + encode(artifactId) + "/" + encode(version);
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(backendUrl + path))
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8);
    }
}
