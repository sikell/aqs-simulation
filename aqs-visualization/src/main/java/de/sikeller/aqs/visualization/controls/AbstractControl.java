package de.sikeller.aqs.visualization.controls;

import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.text.NumberFormat;
import java.util.UUID;
import java.util.function.Consumer;
import javax.swing.*;

public abstract class AbstractControl extends JPanel {
  protected static final int GAP = 2;

  protected JLabel label(String displayName, String name) {
    JLabel label = new JLabel(displayName + ":");
    label.setName(name);
    return label;
  }

  protected JLabel placeholder() {
    JLabel dummy = new JLabel("");
    dummy.setName(UUID.randomUUID().toString());
    return dummy;
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

  protected JSpinner spinner(
      String name, String tooltip, int min, int max, int initialValue, int step) {
    // set initial value later after setting an editor to correctly format the initial value
    SpinnerModel spinnerModel = new SpinnerNumberModel(min, min, max, step);
    JSpinner spinner = new JSpinner(spinnerModel);
    JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner);
    NumberFormat format = editor.getFormat();
    format.setGroupingUsed(false);
    spinner.setEditor(editor);
    spinner.setName(name);
    spinner.setValue(initialValue);
    spinner.setToolTipText(tooltip);
    return spinner;
  }

  protected JButton button(String displayName, String name, ActionListener event) {
    JButton button = new JButton(displayName);
    button.setName(name);
    button.addActionListener(event);
    return button;
  }
}
