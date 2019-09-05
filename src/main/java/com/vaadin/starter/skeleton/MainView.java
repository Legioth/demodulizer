package com.vaadin.starter.skeleton;

import java.util.concurrent.ThreadLocalRandom;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dependency.HtmlImport;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.router.Route;

@Route("")
@HtmlImport("npm_components/a-avataaar/a-avataaar.html")
public class MainView extends VerticalLayout {

    public MainView() {
        Element avatar = new Element("a-avataaar");
        avatar.setAttribute("identifier", "demodulizer");

        getElement().appendChild(avatar);

        Button button = new Button("Click me",
                event -> avatar.setAttribute("identifier", String.valueOf(ThreadLocalRandom.current().nextLong())));
        add(button);

    }
}
