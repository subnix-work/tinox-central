package io.tinox.central.frontend;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Re-serves a tinox-core module's generated docs.html same-origin, so
 * PackageDetailView's iframe can actually display it. raw.githubusercontent.com
 * itself sends X-Frame-Options: deny, which the browser enforces regardless of
 * anything the framing page does -- confirmed live (Playwright), the iframe
 * request is refused outright with that exact header cited in the console.
 * Fetching it server-side here and returning it as our own response sidesteps
 * that: this response carries no such header, so the browser has no reason to
 * refuse it.
 *
 * Path MUST end in a literal /docs.html segment, not just
 * /{group}/{artifactId}/{version} -- the served page's own Dependencies links
 * are relative (`../../{artifactId}/{version}/docs.html`), written for the
 * real 4-level GitHub layout (docs/tinox-core/<module>/<version>/docs.html).
 * Without the trailing docs.html here, the browser has one directory level
 * too few to resolve those against, and `../../` climbs past the group
 * segment entirely -- confirmed live: decimal's "string" dependency link
 * resolved to /docs-proxy/string/1-0-0/docs.html instead of
 * /docs-proxy/tinox.core/string/1-0-0/docs.html, a 404. The literal
 * "docs.html" segment's own text is irrelevant to the browser, only the
 * extra path level matters -- but keeping the same name as the real file
 * avoids the confusion of two different conventions.
 */
@Path("/docs-proxy")
public class DocsProxyResource {

    @Inject
    RegistryClient client;

    @GET
    @Path("/{group}/{artifactId}/{version}/docs.html")
    @Produces(MediaType.TEXT_HTML)
    public Response docs(@PathParam("group") String group,
                          @PathParam("artifactId") String artifactId,
                          @PathParam("version") String version) {
        String html;
        try {
            html = client.fetchDocsHtml(group, artifactId, version);
        } catch (Exception e) {
            return Response.status(Response.Status.BAD_GATEWAY)
                    .entity("Could not reach the docs source: " + e.getMessage())
                    .build();
        }
        if (html == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No docs published for " + group + "/" + artifactId + "/" + version)
                    .build();
        }
        return Response.ok(html).build();
    }
}
