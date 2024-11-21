package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.ResultTable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class ResultVisualization extends AbstractVisualization {

  public ResultVisualization() {
    super("Taxi Scenario Results");
    frame.setMinimumSize(new Dimension(600, 200));
  }

  public void showResults(ResultTable resultTable) {
    DefaultTableModel model =
        new DefaultTableModel(resultTable.getData(), resultTable.getColumns());
    JTable table = new JTable(model);
    JScrollPane scrollPane = new JScrollPane(table);

    frame.add(scrollPane);
    frame.pack();
    frame.setVisible(true);
  }
}
