package de.sikeller.aqs.visualization;

import static de.sikeller.aqs.visualization.VisualizationUtils.defaultFont;
import static de.sikeller.aqs.visualization.VisualizationUtils.smallFont;

import de.sikeller.aqs.model.ResultTable;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.CategoryItemLabelGenerator;
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.DefaultCategoryDataset;

public class ResultVisualization extends AbstractVisualization {

  private JTable table;
  private DefaultTableModel model;
  private JPanel panel;
  private final DefaultCategoryDataset taxiDataset = new DefaultCategoryDataset();
  private final DefaultCategoryDataset clientDataset = new DefaultCategoryDataset();

  public ResultVisualization() {
    super("Taxi Scenario Results");
    frame.setMinimumSize(new Dimension(600, 200));
    frame.setPreferredSize(new Dimension(1000, 400));
    frame.setLayout(new GridLayout());
    frame.addWindowListener(new WindowAdapter() {
      @Override
      public void windowDeactivated(WindowEvent e) {
        // Wenn das Fenster den Fokus verliert, schließen wir es
        System.out.println("Close Result-Window");
        frame.dispose();  // Schließt das Fenster
      }
    });
    showDiagram();
  }

  public void showResults(ResultTable resultTable) {
    ResultTable convertedResultTable = convertResultTable(resultTable);
    updateChart(convertedResultTable);
    if (table == null) {
      model = new DefaultTableModel(convertedResultTable.getData(), resultTable.getColumns());
      table = new JTable(model);
      JScrollPane scrollPane = new JScrollPane(table);
      int frameWidth = frame.getWidth();
      scrollPane.setPreferredSize(new Dimension((int) (frameWidth * 0.8), table.getPreferredSize().height));
      frame.setLayout(new GridLayout(3, 1));
      frame.add(panel);
      frame.add(scrollPane);
      frame.add(resetButton());
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
    ChartPanel taxiChartPanel =
        createBarChart("Taxi Travel Distance", null, "Distance in Kilometers", taxiDataset);
    ChartPanel clientChartPanel = createBarChart("Client Travel Time", null, "Time in Minutes", clientDataset);

    panel = new JPanel();
    panel.setLayout(new GridLayout(0,2));
    panel.add(taxiChartPanel);
    panel.add(clientChartPanel);

    frame.pack();
  }

  private ChartPanel createBarChart(
      String name, String xAxisName, String yAxisName, DefaultCategoryDataset clientDataset) {
    JFreeChart barChart = ChartFactory.createBarChart(name, xAxisName, yAxisName, clientDataset);
    barChart.setBackgroundPaint(null);
    CategoryPlot plot = barChart.getCategoryPlot();
    plot.setBackgroundPaint(null);
    plot.setOutlineVisible(false);
    plot.getDomainAxis().setLabelFont(defaultFont());
    plot.getDomainAxis().setTickLabelFont(smallFont());
    plot.getRangeAxis().setLabelFont(defaultFont());
    plot.getRangeAxis().setTickLabelFont(smallFont());
    LegendTitle legend = barChart.getLegend();
    legend.setBackgroundPaint(null);
    legend.setItemFont(smallFont());
    TextTitle title = barChart.getTitle();
    title.setFont(defaultFont());
    BarRenderer renderer = (BarRenderer) plot.getRenderer();
    CategoryItemLabelGenerator clientGenerator =
        new StandardCategoryItemLabelGenerator("{2}", NumberFormat.getInstance());
    renderer.setDefaultItemLabelGenerator(clientGenerator);
    renderer.setDefaultItemLabelFont(smallFont());
    renderer.setDefaultItemLabelsVisible(true);
    renderer.setShadowVisible(false);
    renderer.setItemMargin(0.5);

    renderer.setBarPainter(new StandardBarPainter());
    return new ChartPanel(barChart);
  }

  public void updateChart(ResultTable resultTable) {
    for (int i = 1; i < 4; i++) {
      String algorithmRun =
          resultTable.getString(0, resultTable.getColumns().length - 2)
              + " | Run "
              + resultTable.getString(0, resultTable.getColumns().length - 1);
      taxiDataset.addValue(resultTable.getDouble(0, i), algorithmRun, resultTable.getColumns()[i]);
      clientDataset.addValue(
          resultTable.getDouble(1, i), algorithmRun, resultTable.getColumns()[i]);
    }
    frame.pack();
  }

  public void openResults() {
    frame.setVisible(true);
  }

  private double convertDistanceToKilometers(double value) {
    return value / 1000;
  };

  private double convertTimeToMinutes(double value) {
    return value / 60;
  }

  private ResultTable convertResultTable(ResultTable resultTable) {
    Object[][] convertedData = resultTable.getData();
    for(int i = 1; i < convertedData[0].length-3; i++) {
      convertedData[0][i] = round(convertDistanceToKilometers(resultTable.getDouble(0,i)), 2);
      convertedData[1][i] = round(convertTimeToMinutes(resultTable.getDouble(1,i)),2);
    }
    return new ResultTable(resultTable.getColumns(), convertedData);
  }

  public static double round(double value, int places) {
    if (places < 0) throw new IllegalArgumentException();

    BigDecimal bd = BigDecimal.valueOf(value);
    bd = bd.setScale(places, RoundingMode.HALF_UP);
    return bd.doubleValue();
  }

  private void resetData() {
    model.getDataVector().removeAllElements();
    taxiDataset.clear();
    clientDataset.clear();
    SwingUtilities.updateComponentTreeUI(table);
  }

  private JButton resetButton() {
    JButton button = new JButton("Reset Data");
    button.addActionListener(e -> resetData());
    return button;
  }
}
