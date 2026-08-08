package io.tinox.central.frontend;

import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.dataview.GridListDataView;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Package browse/search view (PLAN.md Phase 3: "package list view (Grid)
 * with a search box"). Loads the full catalog once via GET /api/v1/packages
 * and filters client-side -- fine at the scale this registry targets (see
 * PLAN.md 5, the same reasoning already applied to the backend's own
 * /api/v1/packages endpoint).
 */
@Route("")
public class PackageListView extends VerticalLayout {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    @Inject
    public PackageListView(RegistryClient client) {
        addClassName("tinox-shell");
        add(new TinoxHeader("tinox-central", "central package registry for the tinox ecosystem"));

        Grid<PackageSummary> grid = new Grid<>(PackageSummary.class, false);
        grid.addComponentColumn(p -> new RouterLink(p.group, GroupView.class,
                        GroupView.routeParameters(p.group)))
                .setHeader("Group").setComparator(p -> p.group);
        grid.addColumn(p -> p.artifactId).setHeader("Artifact").setSortable(true);
        grid.addColumn(p -> p.latestVersion).setHeader("Latest version");
        grid.addColumn(p -> p.versionCount).setHeader("Versions");
        grid.addColumn(p -> TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(p.latestPublishedAt)))
                .setHeader("Last published");
        grid.addComponentColumn(p -> new RouterLink("View", PackageDetailView.class,
                        PackageDetailView.routeParameters(p.group, p.artifactId)))
                .setHeader("");
        grid.setAllRowsVisible(true);
        grid.setWidthFull();

        List<PackageSummary> packages;
        try {
            packages = client.listPackages();
        } catch (Exception e) {
            Notification.show("Could not reach the registry backend: " + e.getMessage());
            packages = List.of();
        }
        GridListDataView<PackageSummary> dataView = grid.setItems(packages);

        TextField search = new TextField();
        search.addClassName("tinox-search");
        search.setPlaceholder("Search group or artifact...");
        search.setClearButtonVisible(true);
        search.setWidthFull();
        search.addValueChangeListener(e -> {
            String query = e.getValue() == null ? "" : e.getValue().trim().toLowerCase();
            if (query.isEmpty()) {
                dataView.removeFilters();
            } else {
                dataView.setFilter(p ->
                        p.group.toLowerCase().contains(query) || p.artifactId.toLowerCase().contains(query));
            }
        });

        Div gridSurface = new Div(grid);
        gridSurface.addClassName("tinox-surface");
        gridSurface.setWidthFull();

        add(search, gridSurface);
        setWidthFull();
    }
}
