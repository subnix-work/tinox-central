package io.tinox.central.frontend;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
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
        grid.setItems(versions);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();

        Div gridSurface = new Div(grid);
        gridSurface.addClassName("tinox-surface");
        gridSurface.setWidthFull();

        add(gridSurface);
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
