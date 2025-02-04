package de.sikeller.aqs.visualization;

import java.awt.event.ItemEvent;
import java.util.function.Consumer;
import javax.swing.*;

public abstract class AbstractControl extends JPanel {
  protected JLabel label(String displayName, String name) {
    JLabel label = new JLabel(displayName + ":");
    label.setName(name);
    return label;
  }

  protected JCheckBox checkBox(
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

  protected JSlider slider(
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
