package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.ResultTable;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.lang.reflect.Field;

public class ResultVisualization extends AbstractVisualization {

  private JTable table;
  private DefaultTableModel model;
  private JScrollPane scrollPane;

  public ResultVisualization() {
    super("Taxi Scenario Results");
    frame.setMinimumSize(new Dimension(600, 200));
  }

  public void showResults(ResultTable resultTable) {

    if (table == null) {
      model =new DefaultTableModel(resultTable.getData(), resultTable.getColumns());
      table = new JTable(model);
      scrollPane = new JScrollPane(table);
      showDiagram(resultTable);
      frame.add(scrollPane);
      frame.pack();
      openResults();
    } else {

      for(int i = 0; i < resultTable.getData().length; i++) {
        model.addRow(resultTable.getData()[i]);
      }
      SwingUtilities.updateComponentTreeUI(frame);
    }

  }

  public void showDiagram(ResultTable resultTable) {
    DefaultCategoryDataset taxiDataset = new DefaultCategoryDataset();
    DefaultCategoryDataset clientDataset = new DefaultCategoryDataset();
    for(int i = 1; i < resultTable.getColumns().length; i++) {
      taxiDataset.addValue(Double.parseDouble( resultTable.getData()[0][i].toString()) , resultTable.getColumns()[i], resultTable.getData()[0][0].toString());
      clientDataset.addValue(Double.parseDouble( resultTable.getData()[1][i].toString()), resultTable.getColumns()[i], resultTable.getData()[1][0].toString());
    }
    JFreeChart taxiChart = ChartFactory.createBarChart("Taxi-Data", "test1","test2", taxiDataset );
    JFreeChart clientChart = ChartFactory.createBarChart("Taxi-Data", "test1","test2", clientDataset );

    ChartPanel taxiChartPanel = new ChartPanel(taxiChart);
    ChartPanel clientChartPanel = new ChartPanel(clientChart);
    JPanel panel = new JPanel();
    panel.add(taxiChartPanel);
    panel.add(clientChartPanel);
    frame.add(panel);
  }

  public void openResults() {
    frame.setVisible(true);
  }

}
