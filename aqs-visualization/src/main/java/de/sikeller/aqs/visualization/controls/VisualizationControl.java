package de.sikeller.aqs.visualization.controls;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.TitledBorder;

import de.sikeller.aqs.visualization.drawing.VisualizationProperties;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VisualizationControl extends AbstractControl {
  private final VisualizationProperties properties;

  public VisualizationControl(VisualizationProperties properties) {
    this.properties = properties;
    add(setup());
  }

  private JPanel setup() {
    var controls = new JPanel();
    controls.setBorder(new TitledBorder("Visualization Control"));
    controls.setLayout(new GridLayout(4, 2, GAP, GAP));

    controls.add(label("Scale", "scaleLabel"));

    controls.add(
        slider(
            "scaleSlider",
            "Scale the rendered visualization.",
            1,
            10,
            properties.getScale(),
            properties::setScale));

    controls.add(
        checkBox(
            "Show client paths",
            "showClientPaths",
            "Display a path between client start and target.",
            properties.isShowClientPaths(),
            properties::setShowClientPaths));

    controls.add(
        checkBox(
            "Show client names",
            "showClientNames",
            "Display a the client name.",
            properties.isShowClientNames(),
            properties::setShowClientNames));

    controls.add(
        checkBox(
            "Show finished clients",
            "showFinishedClients",
            "Show clients which are finished.",
            properties.isShowFinishedClients(),
            properties::setShowFinishedClients));

    controls.add(
        checkBox(
            "Show taxi paths",
            "showTaxiPaths",
            "Display the planned path for each taxi.",
            properties.isShowTaxiPaths(),
            properties::setShowTaxiPaths));

    controls.add(
        checkBox(
            "Show taxi names",
            "showTaxiNames",
            "Display the taxi names.",
            properties.isShowTaxiNames(),
            properties::setShowTaxiNames));

    return controls;
  }
}
