package de.sikeller.aqs.visualization;

import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.defaultFont;
import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.smallFont;

import de.sikeller.aqs.model.ResultTable;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.labels.CategoryItemLabelGenerator;
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.LineAndShapeRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.DefaultCategoryDataset;

@Slf4j
public class ResultVisualization extends AbstractVisualization {

  private JTable table;
  private DefaultTableModel model;
  private JPanel chartPanel;
  private final DefaultCategoryDataset taxiDataset = new DefaultCategoryDataset();
  private final DefaultCategoryDataset clientDataset = new DefaultCategoryDataset();
  private final DefaultCategoryDataset timeDataset = new DefaultCategoryDataset();

  public ResultVisualization() {
    super("Taxi Scenario Results");
    frame.setMinimumSize(new Dimension(600, 200));
    frame.setPreferredSize(new Dimension(1200, 800));
    frame.setLayout(new BorderLayout());
    addDiagrams();
  }

  public void showResults(ResultTable resultTable) {
    ResultTable convertedResultTable = convertResultTable(resultTable);
    updateChart(convertedResultTable);
    if (table == null) {
      model = new DefaultTableModel(convertedResultTable.getData(), resultTable.getColumns());
      table = new JTable(model);
      JScrollPane scrollPane = new JScrollPane(table);
      int frameWidth = frame.getWidth();
      scrollPane.setMinimumSize(
          new Dimension(
              (int) (frameWidth * 0.8), table.getRowHeight() * (model.getRowCount() + 2)));
      chartPanel.setPreferredSize(new Dimension(frameWidth, 700));
      frame.add(chartPanel, BorderLayout.CENTER);
      JPanel tablePanel = new JPanel(new GridBagLayout());
      GridBagConstraints constraints = new GridBagConstraints();
      constraints.gridx = 0;
      constraints.gridy = 0;
      constraints.weightx = 1.0;
      constraints.fill = GridBagConstraints.HORIZONTAL;
      tablePanel.add(scrollPane, constraints);
      constraints.gridy = 1;
      constraints.fill = GridBagConstraints.NONE;
      tablePanel.add(resetButton(), constraints);

      frame.add(tablePanel, BorderLayout.SOUTH);
      frame.pack();
      openResults();
    } else {

      for (int i = 0; i < resultTable.getData().length; i++) {
        model.addRow(resultTable.getData()[i]);
      }
      SwingUtilities.updateComponentTreeUI(frame);
    }
  }

  public void addDiagrams() {
    chartPanel = new JPanel();
    chartPanel.setLayout(new GridLayout(0, 3));
    chartPanel.add(
        createBarChart("Taxi Travel Distance", null, "Distance in Kilometers", taxiDataset));
    chartPanel.add(createBarChart("Client Travel Time", null, "Time in Minutes", clientDataset));
    chartPanel.add(createBarChart("Calculation Time", null, "Time in Millis", timeDataset));
    frame.pack();
  }

  private ChartPanel createBarChart(
      String name, String xAxisName, String yAxisName, DefaultCategoryDataset dataset) {
    JFreeChart chart = ChartFactory.createBarChart(name, xAxisName, yAxisName, dataset);
    chart.setBackgroundPaint(null);
    CategoryPlot plot = chart.getCategoryPlot();
    plot.setBackgroundPaint(null);
    plot.setOutlineVisible(false);
    plot.getDomainAxis().setLabelFont(defaultFont());
    plot.getDomainAxis().setTickLabelFont(smallFont());
    plot.getRangeAxis().setLabelFont(defaultFont());
    plot.getRangeAxis().setTickLabelFont(smallFont());
    LegendTitle legend = chart.getLegend();
    legend.setBackgroundPaint(null);
    legend.setItemFont(smallFont());
    TextTitle title = chart.getTitle();
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
    return new ChartPanel(chart);
  }

  private ChartPanel createLineChart(
      String name, String xAxisName, String yAxisName, DefaultCategoryDataset dataset) {
    JFreeChart chart = ChartFactory.createLineChart(name, xAxisName, yAxisName, dataset);
    chart.setBackgroundPaint(null);
    CategoryPlot plot = chart.getCategoryPlot();
    plot.setBackgroundPaint(null);
    plot.setOutlineVisible(false);
    plot.getDomainAxis().setLabelFont(defaultFont());
    plot.getDomainAxis().setTickLabelFont(smallFont());
    plot.getRangeAxis().setLabelFont(defaultFont());
    plot.getRangeAxis().setTickLabelFont(smallFont());
    LegendTitle legend = chart.getLegend();
    legend.setBackgroundPaint(null);
    legend.setItemFont(smallFont());
    TextTitle title = chart.getTitle();
    title.setFont(defaultFont());
    LineAndShapeRenderer renderer = (LineAndShapeRenderer) plot.getRenderer();
    CategoryItemLabelGenerator clientGenerator =
        new StandardCategoryItemLabelGenerator("{2}", NumberFormat.getInstance());
    renderer.setDefaultItemLabelGenerator(clientGenerator);
    renderer.setDefaultItemLabelFont(smallFont());
    renderer.setDefaultItemLabelsVisible(true);
    renderer.setItemMargin(0.5);
    return new ChartPanel(chart);
  }

  public void updateChart(ResultTable resultTable) {
    String algorithmRun =
        "%s | Run %s"
            .formatted(
                resultTable.getString(0, resultTable.getColumns().length - 2),
                resultTable.getString(0, resultTable.getColumns().length - 1));
    // iterate through (1: min, 2: max, 3: avg):
    for (int i = 1; i <= 3; i++) {
      taxiDataset.addValue(resultTable.getDouble(0, i), algorithmRun, resultTable.getColumns()[i]);
      clientDataset.addValue(
          resultTable.getDouble(1, i), algorithmRun, resultTable.getColumns()[i]);
    }
    // only show average value for time calculation (column 3):
    timeDataset.addValue(resultTable.getDouble(2, 3), algorithmRun, resultTable.getColumns()[3]);
    frame.pack();
  }

  public void openResults() {
    frame.setVisible(true);
  }

  private double convertDistanceToKilometers(double value) {
    return value / 1000;
  }

  private double convertTimeToMinutes(double value) {
    return value / 60;
  }

  private ResultTable convertResultTable(ResultTable resultTable) {
    Object[][] convertedData = resultTable.getData();
    for (int i = 1; i < convertedData[0].length - 3; i++) {
      convertedData[0][i] = round(convertDistanceToKilometers(resultTable.getDouble(0, i)), 2);
      convertedData[1][i] = round(convertTimeToMinutes(resultTable.getDouble(1, i)), 2);
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
    timeDataset.clear();
    SwingUtilities.updateComponentTreeUI(table);
  }

  private JButton resetButton() {
    JButton button = new JButton("Reset Data");
    button.addActionListener(e -> resetData());
    return button;
  }
}
