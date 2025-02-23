package de.sikeller.aqs.visualization.controls;

import java.awt.*;
import java.util.*;
import javax.swing.*;
import javax.swing.border.TitledBorder;

public class BatchProcessingControl extends AbstractControl {

  private final BatchProcessingProperties properties;
  private final Map<String, Component> componentMap;
  private final Map<String, Integer> defaultValues;

  public BatchProcessingControl(BatchProcessingProperties properties) {
    this.properties = properties;
    setup();
    Component[] components = getComponents();
    this.componentMap = new LinkedHashMap<>();
    for (Component component : components) {
      this.componentMap.put(component.getName(), component);
    }
    defaultValues =
        Map.of(
            "batchCount",
            properties.getBatchCount(),
            "taxiIncrement",
            properties.getTaxiIncrement(),
            "clientIncrement",
            properties.getClientIncrement(),
            "taxiSeatIncrement",
            properties.getSeatIncrement());
  }

  private void setup() {
    setLayout(new GridBagLayout());
    setBorder(new TitledBorder("Batch-Processing"));
    setLayout(new GridLayout(5, 2, GAP, GAP));

    add(label("Number of Runs", "batchCountLabel"));
    add(
        spinner(
            "batchCount",
            "Sets the number of Sequences of chosen Algorithm",
            1,
            1000000,
            properties.getBatchCount(),
            1));

    add(label("Increment of Taxis", "taxiIncrementLabel"));
    add(
        spinner(
            "taxiIncrement",
            "Sets the Increment of the Taxi-Parameter",
            0,
            1000000,
            properties.getTaxiIncrement(),
            1));

    add(label("Increment of Clients", "clientIncrementLabel"));
    add(
        spinner(
            "clientIncrement",
            "Sets the Increment of the Taxi-Parameter",
            0,
            1000000,
            properties.getClientIncrement(),
            1));

    add(label("Increment of Taxi-Seats", "taxiSeatIncrementLabel"));
    add(
        spinner(
            "taxiSeatIncrement",
            "Sets the Increment of the Taxi-Seat-Parameter",
            0,
            1000000,
            properties.getSeatIncrement(),
            1));

    add(placeholder());
    add(
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
            }));
  }
}
