package de.sikeller.aqs.visualization;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.TitledBorder;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VisualizationControl extends AbstractControl {
  private final VisualizationProperties properties;

  public VisualizationControl(VisualizationProperties properties) {
    this.properties = properties;
    setup();
  }

  private void setup() {
    setLayout(new GridBagLayout());
    setBorder(new TitledBorder("Visualization Control"));
    GridBagConstraints bagConstraints = new GridBagConstraints();
    bagConstraints.anchor = GridBagConstraints.WEST;

    add(
        0,
        0,
        checkBox(
            "Show client paths",
            "showClientPaths",
            "Display a path between client start and target.",
            properties.isShowClientPaths(),
            properties::setShowClientPaths),
        bagConstraints);

    add(
        0,
        1,
        checkBox(
            "Show client names",
            "showClientNames",
            "Display a the client name.",
            properties.isShowClientNames(),
            properties::setShowClientNames),
        bagConstraints);

    add(
        1,
        0,
        checkBox(
            "Show finished clients",
            "showFinishedClients",
            "Show clients which are finished.",
            properties.isShowFinishedClients(),
            properties::setShowFinishedClients),
        bagConstraints);

    add(
        2,
        0,
        checkBox(
            "Show taxi paths",
            "showTaxiPaths",
            "Display the planned path for each taxi.",
            properties.isShowTaxiPaths(),
            properties::setShowTaxiPaths),
        bagConstraints);

    add(
        2,
        1,
        checkBox(
            "Show taxi names",
            "showTaxiNames",
            "Display the taxi names.",
            properties.isShowTaxiNames(),
            properties::setShowTaxiNames),
        bagConstraints);

    add(3, 0, label("Scale", "scaleLabel"), bagConstraints);

    add(
        4,
        0,
        slider(
            "scaleSlider",
            "Scale the rendered visualization.",
            1,
            10,
            properties.getScale(),
            properties::setScale),
        bagConstraints);
  }

  private void add(int y, int x, Component component, GridBagConstraints bagConstraints) {
    bagConstraints.gridy = y;
    bagConstraints.gridx = x;
    add(component, bagConstraints);
  }
}
