package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.ResultTable;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

public class ResultVisualization extends AbstractVisualization {

  private JTable table;
  private DefaultTableModel model;

  public ResultVisualization() {
    super("Taxi Scenario Results");
    frame.setMinimumSize(new Dimension(600, 200));
  }

  public void showResults(ResultTable resultTable) {

    if (table == null) {
      model =new DefaultTableModel(resultTable.getData(), resultTable.getColumns());
      table = new JTable(model);
      JScrollPane scrollPane = new JScrollPane(table);

      frame.add(scrollPane);
      frame.pack();
      frame.setVisible(true);
    } else {

      for(int i = 0; i < resultTable.getData().length; i++) {
        model.addRow(resultTable.getData()[i]);
      }
      SwingUtilities.updateComponentTreeUI(frame);
    }

  }

  public void showDiagram() {

  }

  public void openResults() {
    frame.setVisible(true);
  }
}
