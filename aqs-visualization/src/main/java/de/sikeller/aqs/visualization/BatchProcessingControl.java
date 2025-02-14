package de.sikeller.aqs.visualization;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.*;

import static java.util.Map.entry;

public class BatchProcessingControl extends AbstractControl {

  private final BatchProcessingProperties properties;
  private Map<String, Component> componentMap;
  private final Map<String, Integer> defaultValues = Map.of(
          "batchCount",1,
          "taxiIncrement",0,
          "clientIncrement",0);
  public BatchProcessingControl(BatchProcessingProperties properties) {
    this.properties = properties;
    setup();
    Component[] components = getComponents();
    this.componentMap = new LinkedHashMap<>();
    for (Component component : components) {
      this.componentMap.put(component.getName(), component);
    }
  }

  private void setup() {
    setLayout(new GridBagLayout());
    setBorder(new TitledBorder("Batch-Processing"));
    GridBagConstraints bagConstraints = new GridBagConstraints();
    bagConstraints.anchor = GridBagConstraints.CENTER;
    add(
        0,
        1,
        spinner("batchCount", "Sets the number of Sequences of chosen Algorithm", 1, 1000000, 1, 1),
        bagConstraints);
    add(0, 0, label("Number of Runs", "batchCountLabel"), bagConstraints);

    add(
        1,
        1,
        spinner("taxiIncrement", "Sets the Increment of the Taxi-Parameter", 0, 1000000, 0, 1),
        bagConstraints);
    add(1, 0, label("Increment of Taxis", "taxiIncrementLabel"), bagConstraints);
    add(
        2,
        1,
        spinner("clientIncrement", "Sets the Increment of the Taxi-Parameter", 0, 1000000, 0, 1),
        bagConstraints);
    add(2, 0, label("Increment of Clients", "clientIncrementLabel"), bagConstraints);
    add(
            3,
            1,
            spinner("taxiSeatIncrement", "Sets the Increment of the Taxi-Seat-Parameter", 0, 1000000, 0, 1),
            bagConstraints);
    add(3, 0, label("Increment of Taxi-Seats", "taxiSeatIncrementLabel"), bagConstraints);

    add(
        4,
        1,
        button(
            "Reset",
            "batchReset",
            e -> {
              defaultValues.forEach(
                  (s, defaultValue) -> {
                    Object result = componentMap.get(s);
                    if (result instanceof JSpinner) {
                      ((JSpinner) componentMap.get(s)).setValue(defaultValue);
                    }
                  });
            }),
        bagConstraints);
  }

  private void add(int y, int x, Component component, GridBagConstraints bagConstraints) {
    bagConstraints.gridy = y;
    bagConstraints.gridx = x;
    add(component, bagConstraints);
  }
}
