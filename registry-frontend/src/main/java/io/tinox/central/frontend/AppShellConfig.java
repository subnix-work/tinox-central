package io.tinox.central.frontend;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.lumo.Lumo;

/** Wires the custom "tinox-central" theme (dark Lumo variant + neon overrides,
 * see frontend/themes/tinox-central/styles.css) app-wide. */
@Theme(value = "tinox-central", variant = Lumo.DARK)
public class AppShellConfig implements AppShellConfigurator {
}
