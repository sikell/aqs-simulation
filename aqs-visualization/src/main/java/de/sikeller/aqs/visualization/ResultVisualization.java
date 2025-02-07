package de.sikeller.aqs.visualization;

import de.sikeller.aqs.model.ResultTable;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.CategoryItemLabelGenerator;
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.category.CategoryItemRenderer;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.lang.reflect.Field;
import java.text.NumberFormat;
import java.util.Arrays;

public class ResultVisualization extends AbstractVisualization {

  private JTable table;
  private DefaultTableModel model;
  private JScrollPane scrollPane;
  private JPanel panel;
  private final DefaultCategoryDataset taxiDataset = new DefaultCategoryDataset();
  private final DefaultCategoryDataset clientDataset = new DefaultCategoryDataset();

  public ResultVisualization() {
    super("Taxi Scenario Results");
    frame.setMinimumSize(new Dimension(600, 200));
    frame.setLayout(new GridLayout());
    showDiagram();
  }

  public void showResults(ResultTable resultTable) {
    updateChart(resultTable);
    if (table == null) {
      model = new DefaultTableModel(resultTable.getData(), resultTable.getColumns());
      table = new JTable(model);
      scrollPane = new JScrollPane(table);
      scrollPane.setMaximumSize(new Dimension(500, 200));

      frame.setLayout(new GridLayout(0, 1));
      frame.add(panel);
      frame.add(scrollPane);
      frame.pack();
      openResults();
    } else {

      for (int i = 0; i < resultTable.getData().length; i++) {
        model.addRow(resultTable.getData()[i]);
      }
      SwingUtilities.updateComponentTreeUI(frame);
    }
  }

  public void showDiagram() {
    JFreeChart taxiChart =
        ChartFactory.createBarChart("Taxi Travel Distance", "Categories", "Distance", taxiDataset);
    JFreeChart clientChart =
        ChartFactory.createBarChart("Client Travel Time", "Categories", "Time", clientDataset);
    ChartPanel taxiChartPanel = new ChartPanel(taxiChart);
    ChartPanel clientChartPanel = new ChartPanel(clientChart);
    CategoryPlot taxiPlot = taxiChart.getCategoryPlot();
    CategoryItemRenderer taxiRenderer = taxiPlot.getRenderer();
    CategoryItemLabelGenerator generator =
        new StandardCategoryItemLabelGenerator("{2}", NumberFormat.getInstance());
    taxiRenderer.setDefaultItemLabelGenerator(generator);
    taxiRenderer.setDefaultItemLabelsVisible(true);

    CategoryPlot clientPlot = clientChart.getCategoryPlot();
    CategoryItemRenderer clientRenderer = clientPlot.getRenderer();
    CategoryItemLabelGenerator clientGenerator =
        new StandardCategoryItemLabelGenerator("{2}", NumberFormat.getInstance());
    clientRenderer.setDefaultItemLabelGenerator(clientGenerator);
    clientRenderer.setDefaultItemLabelsVisible(true);
    panel = new JPanel();
    panel.setLayout(new GridLayout(0, 2));
    panel.add(taxiChartPanel);
    panel.add(clientChartPanel);

    frame.pack();
  }

  public void updateChart(ResultTable resultTable) {
    for (int i = 1; i < 4; i++) {
      String algorithmRun =
          resultTable.getData()[0][resultTable.getColumns().length - 2].toString()
              + " Run: "
              + resultTable.getData()[0][resultTable.getColumns().length - 1].toString();
      taxiDataset.addValue(
          Double.parseDouble(resultTable.getData()[0][i].toString()),
          algorithmRun,
          resultTable.getColumns()[i]);
      clientDataset.addValue(
          Double.parseDouble(resultTable.getData()[1][i].toString()),
          algorithmRun,
          resultTable.getColumns()[i]);
    }
    frame.pack();
  }

  public void openResults() {
    frame.setVisible(true);
  }
}
