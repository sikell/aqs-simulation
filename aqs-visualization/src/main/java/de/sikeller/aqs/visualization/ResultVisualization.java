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
import java.util.Arrays;

public class ResultVisualization extends AbstractVisualization {

  private JTable table;
  private DefaultTableModel model;
  private JScrollPane scrollPane;
  private JPanel panel;

  public ResultVisualization() {
    super("Taxi Scenario Results");
    frame.setMinimumSize(new Dimension(600, 200));
    frame.setLayout(new GridLayout());
  }

  public void showResults(ResultTable resultTable) {

    if (table == null) {
      model =new DefaultTableModel(resultTable.getData(), resultTable.getColumns());
      table = new JTable(model);
      scrollPane = new JScrollPane(table);
      scrollPane.setMaximumSize(new Dimension(500,200));
      showDiagram(resultTable);
      frame.setLayout(new GridLayout(0,1));
      frame.add(scrollPane);
      frame.add(panel);
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
    for(int i = 1; i < 4; i++) {
      taxiDataset.addValue(Double.parseDouble( resultTable.getData()[0][i].toString()) , resultTable.getColumns()[i], resultTable.getData()[0][0].toString());
      clientDataset.addValue(Double.parseDouble( resultTable.getData()[1][i].toString()), resultTable.getColumns()[i], resultTable.getData()[1][0].toString());
    }
    JFreeChart taxiChart = ChartFactory.createBarChart("Taxi-Data", "test1","test2", taxiDataset );
    JFreeChart clientChart = ChartFactory.createBarChart("Taxi-Data", "test1","test2", clientDataset );
    ChartPanel taxiChartPanel = new ChartPanel(taxiChart);
    ChartPanel clientChartPanel = new ChartPanel(clientChart);
    taxiChartPanel.setSize(new Dimension(300,50));
    clientChartPanel.setSize(new Dimension(300,50));
    panel = new JPanel();
    panel.setLayout(new GridLayout(0,2 ));
    panel.add(taxiChartPanel);
    panel.add(clientChartPanel);
    frame.pack();
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    } catch (Exception e) {
      e.printStackTrace();
    }
    System.out.println(Arrays.toString(scrollPane.getComponents()));
  }

  public void openResults() {
    frame.setVisible(true);
  }

}
