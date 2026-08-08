package io.tinox.central.frontend;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.router.RouterLink;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * All artifacts published under one group -- filters the same
 * GET /api/v1/packages catalog PackageListView already loads (there's no
 * dedicated backend endpoint for this; a client-side filter over the flat
 * catalog matches the project's existing approach to search, see PLAN.md 5
 * and PackageListView's search box).
 */
@Route("groups/:group")
public class GroupView extends VerticalLayout implements BeforeEnterObserver {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final RegistryClient client;

    @Inject
    public GroupView(RegistryClient client) {
        this.client = client;
        addClassName("tinox-shell");
        setWidthFull();
    }

    public static RouteParameters routeParameters(String group) {
        return new RouteParameters(Map.of("group", group));
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        removeAll();
        String group = event.getRouteParameters().get("group").orElse("");

        add(new TinoxHeader(group, "all artifacts in this group"));

        List<PackageSummary> packages;
        try {
            packages = client.listPackages().stream()
                    .filter(p -> p.group.equals(group))
                    .toList();
        } catch (Exception e) {
            Notification.show("Could not reach the registry backend: " + e.getMessage());
            packages = List.of();
        }

        if (packages.isEmpty()) {
            Paragraph empty = new Paragraph("No artifacts published under this group.");
            empty.addClassName("tinox-empty");
            add(empty);
            return;
        }

        Grid<PackageSummary> grid = new Grid<>(PackageSummary.class, false);
        grid.addColumn(p -> p.artifactId).setHeader("Artifact").setSortable(true);
        grid.addColumn(p -> p.latestVersion).setHeader("Latest version");
        grid.addColumn(p -> p.versionCount).setHeader("Versions");
        grid.addColumn(p -> TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(p.latestPublishedAt)))
                .setHeader("Last published");
        grid.addComponentColumn(p -> new RouterLink("View", PackageDetailView.class,
                        PackageDetailView.routeParameters(p.group, p.artifactId)))
                .setHeader("");
        grid.setItems(packages);
        grid.setAllRowsVisible(true);
        grid.setWidthFull();

        Div gridSurface = new Div(grid);
        gridSurface.addClassName("tinox-surface");
        gridSurface.setWidthFull();

        add(gridSurface);
    }
}
