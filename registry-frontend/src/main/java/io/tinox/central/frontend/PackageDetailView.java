package io.tinox.central.frontend;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Package detail view: versions, publish date, size, checksum, download
 * links (PLAN.md Phase 3). Download links point straight at the backend's
 * GET /api/v1/{group}/{artifactId}/{version} -- the frontend never proxies
 * artifact bytes through itself (see RegistryClient.downloadUrl).
 */
@Route("packages/:group/:artifact")
public class PackageDetailView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final RegistryClient client;

    @Inject
    public PackageDetailView(RegistryClient client) {
        this.client = client;
        addClassName("tinox-shell");
        setWidthFull();
    }

    public static RouteParameters routeParameters(String group, String artifactId) {
        return new RouteParameters(Map.of("group", group, "artifact", artifactId));
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        removeAll();
        String group = event.getRouteParameters().get("group").orElse("");
        String artifactId = event.getRouteParameters().get("artifact").orElse("");

        add(new TinoxHeader(group + " / " + artifactId, "published versions"));

        List<PackageMeta> versions;
        try {
            versions = client.listVersions(group, artifactId);
        } catch (Exception e) {
            Notification.show("Could not reach the registry backend: " + e.getMessage());
            versions = List.of();
        }

        if (versions.isEmpty()) {
            Paragraph empty = new Paragraph("No such package, or it has no published versions.");
            empty.addClassName("tinox-empty");
            add(empty);
            return;
        }

        Grid<PackageMeta> grid = new Grid<>(PackageMeta.class, false);
        grid.addColumn(m -> m.version).setHeader("Version").setSortable(true);
        grid.addColumn(m -> m.filename).setHeader("Filename");
        grid.addColumn(m -> formatSize(m.sizeBytes)).setHeader("Size");
        grid.addColumn(m -> m.sha256).setHeader("SHA-256");
        grid.addColumn(m -> TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(m.publishedAt))).setHeader("Published");
        grid.addComponentColumn(m -> {
            Anchor link = new Anchor(client.downloadUrl(group, artifactId, m.version), "Download");
            link.getElement().setAttribute("download", true);
            return link;
        }).setHeader("");
        String finalGroup = group;
        String finalArtifactId = artifactId;
        grid.addComponentColumn(m -> {
            Button docsButton = new Button("Docs");
            docsButton.addClassName("tinox-pill-button");
            docsButton.addClickListener(e -> openDocs(finalGroup, finalArtifactId, m.version));
            return docsButton;
        }).setHeader("");
        grid.setItems(versions);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();

        Div gridSurface = new Div(grid);
        gridSurface.addClassName("tinox-surface");
        gridSurface.setWidthFull();

        add(gridSurface);
    }

    /** Opens the version's generated docs.html inline in an iframe dialog,
     * instead of navigating away -- the registry stays the one tab open. Points
     * at our own /docs-proxy/... (DocsProxyResource), NOT raw.githubusercontent.com
     * directly -- confirmed live that GitHub's raw content sends
     * X-Frame-Options: deny, which the browser enforces no matter what this
     * page does, so embedding it straight is a dead end. The proxy re-fetches
     * server-side and re-serves same-origin, which has no such header. */
    private void openDocs(String group, String artifactId, String version) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(group + " / " + artifactId + " " + version + " -- docs");
        dialog.addClassName("tinox-docs-dialog");
        dialog.setWidth("92vw");
        dialog.setHeight("90vh");
        dialog.setResizable(true);
        dialog.setDraggable(true);

        // Trailing /docs.html matters, not just cosmetic: see DocsProxyResource's
        // own doc comment -- the served page's relative dependency links need
        // this extra path level to resolve correctly.
        String proxyPath = "/docs-proxy/" + encode(group) + "/" + encode(artifactId) + "/" + encode(version) + "/docs.html";
        IFrame iframe = new IFrame(proxyPath);
        iframe.setWidthFull();
        iframe.setHeightFull();
        iframe.getElement().getStyle().set("border", "none");
        dialog.add(iframe);

        Button close = new Button("Close", e -> dialog.close());
        dialog.getFooter().add(close);

        dialog.open();
    }

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KiB", bytes / 1024.0);
        }
        return String.format("%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
