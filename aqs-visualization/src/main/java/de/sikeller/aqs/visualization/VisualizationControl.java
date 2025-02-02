package de.sikeller.aqs.visualization;

import java.awt.*;
import java.awt.event.ItemEvent;
import java.util.function.Consumer;
import javax.swing.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VisualizationControl extends JPanel {
  private final VisualizationProperties properties;

  public VisualizationControl(VisualizationProperties properties) {
    this.properties = properties;
    setup();
  }

  private void setup() {
    setLayout(new GridBagLayout());
    GridBagConstraints bagConstraints = new GridBagConstraints();
    bagConstraints.anchor = GridBagConstraints.WEST;

    bagConstraints.gridy = 0;
    bagConstraints.gridx = 0;
    add(
        checkBox(
            "Show client paths",
            "showClientPaths",
            "Display a path between client start and target.",
            properties.isShowClientPaths(),
            properties::setShowClientPaths),
        bagConstraints);

    bagConstraints.gridy = 0;
    bagConstraints.gridx = 1;
    add(
        checkBox(
            "Show client names",
            "showClientNames",
            "Display a the client name.",
            properties.isShowClientNames(),
            properties::setShowClientNames),
        bagConstraints);

    bagConstraints.gridy = 1;
    bagConstraints.gridx = 0;
    add(
        checkBox(
            "Show taxi paths",
            "showTaxiPaths",
            "Display the planned path for each taxi.",
            properties.isShowTaxiPaths(),
            properties::setShowTaxiPaths),
        bagConstraints);

    bagConstraints.gridy = 1;
    bagConstraints.gridx = 1;
    add(
        checkBox(
            "Show taxi names",
            "showTaxiNames",
            "Display the taxi names.",
            properties.isShowTaxiNames(),
            properties::setShowTaxiNames),
        bagConstraints);

    bagConstraints.gridy = 2;
    bagConstraints.gridx = 0;
    add(label("Scale", "scaleLabel"), bagConstraints);

    bagConstraints.gridy = 3;
    bagConstraints.gridx = 0;
    add(
        slider(
            "scaleSlider",
            "Scale the rendered visualization.",
            1,
            10,
            properties.getScale(),
            properties::setScale),
        bagConstraints);
  }

  private JLabel label(String displayName, String name) {
    JLabel label = new JLabel(displayName + ":");
    label.setName(name);
    return label;
  }

  private JCheckBox checkBox(
      String displayName,
      String name,
      String tooltip,
      boolean defaultValue,
      Consumer<Boolean> onChange) {
    JCheckBox checkBox = new JCheckBox();
    checkBox.setName(name);
    checkBox.setText(displayName);
    checkBox.setToolTipText(tooltip);
    checkBox.setSelected(defaultValue);
    checkBox.addItemListener(e -> onChange.accept(e.getStateChange() == ItemEvent.SELECTED));
    return checkBox;
  }

  private JSlider slider(
      String name, String tooltip, int min, int max, int defaultValue, Consumer<Integer> onChange) {
    JSlider slider = new JSlider();
    slider.setName(name);
    slider.setToolTipText(tooltip);
    slider.setMaximum(max);
    slider.setMinimum(min);
    slider.setValue(defaultValue);
    slider.addChangeListener(
        e -> {
          JSlider source = (JSlider) e.getSource();
          if (!source.getValueIsAdjusting()) {
            int value = source.getValue();
            onChange.accept(value);
          }
        });
    return slider;
  }
}
