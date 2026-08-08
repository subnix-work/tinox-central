package io.tinox.central.frontend;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;

/** Shared page header (gradient title + terminal-style subtitle line) used
 * by every view for a consistent look. */
public class TinoxHeader extends Div {

    public TinoxHeader(String title, String subtitle) {
        addClassName("tinox-header");

        H1 heading = new H1(title);
        heading.addClassName("tinox-title");

        Paragraph sub = new Paragraph(subtitle);
        sub.addClassName("tinox-subtitle");

        add(heading, sub);
    }
}
