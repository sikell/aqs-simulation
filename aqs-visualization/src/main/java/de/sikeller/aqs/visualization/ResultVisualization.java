package de.sikeller.aqs.visualization;

import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.defaultFont;
import static de.sikeller.aqs.visualization.drawing.VisualizationUtils.smallFont;

import de.sikeller.aqs.model.ResultTable;
import de.sikeller.aqs.model.TickDataPoint;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import lombok.extern.slf4j.Slf4j;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.CategoryItemLabelGenerator;
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.category.BarRenderer;
import org.jfree.chart.renderer.category.StandardBarPainter;
import org.jfree.chart.renderer.xy.XYAreaRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.DefaultCategoryDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

@Slf4j
public class ResultVisualization extends AbstractVisualization {

  private JTable table;
  private DefaultTableModel model;
  private JPanel chartPanel;
  private final DefaultCategoryDataset taxiDataset = new DefaultCategoryDataset();
  private final DefaultCategoryDataset clientDataset = new DefaultCategoryDataset();
  private final DefaultCategoryDataset timeDataset = new DefaultCategoryDataset();
  private final XYSeriesCollection calcTimeCollection = new XYSeriesCollection();
  private final XYSeriesCollection clientCountCollection = new XYSeriesCollection();
  private final XYAreaRenderer loadAreaRenderer = new XYAreaRenderer();
  private final XYLineAndShapeRenderer loadLineRenderer = new XYLineAndShapeRenderer(true, false);

  public ResultVisualization() {
    super("Taxi Scenario Results");
    frame.setMinimumSize(new Dimension(600, 200));
    frame.setPreferredSize(new Dimension(1600, 800));
    frame.setLayout(new BorderLayout());
    addDiagrams();
  }

  public void showResults(ResultTable resultTable) {
    ResultTable convertedResultTable = convertResultTable(resultTable);
    SwingUtilities.invokeLater(
        () -> {
          updateChart(convertedResultTable);
          if (table == null) {
            model =
                new DefaultTableModel(convertedResultTable.getData(), resultTable.getColumns());
            table = new JTable(model);
            table.setRowSelectionAllowed(true);
            table.setColumnSelectionAllowed(false);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.addMouseListener(highlightTableRowsInRunMouseListener(resultTable));

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
            frame.revalidate();
            frame.repaint();
          }
        });
  }

  @Nonnull
  private MouseAdapter highlightTableRowsInRunMouseListener(ResultTable resultTable) {
    // if a row is selected all related rows in all other runs are also selected
    return new MouseAdapter() {
      @Override
      public void mouseClicked(MouseEvent e) {
        int clickedViewRow = table.rowAtPoint(e.getPoint());
        if (clickedViewRow < 0) return;

        int rowsPerRun = resultTable.getGroupSize();
        int clickedModelRow = table.convertRowIndexToModel(clickedViewRow);
        int rowInsideRun = clickedModelRow % rowsPerRun;

        table.clearSelection();
        for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) {
          int modelRow = table.convertRowIndexToModel(viewRow);
          if (modelRow % rowsPerRun == rowInsideRun) {
            table.addRowSelectionInterval(viewRow, viewRow);
          }
        }
      }
    };
  }

  public void addDiagrams() {
    chartPanel = new JPanel();
    chartPanel.setLayout(new GridLayout(0, 4));
    chartPanel.add(
        createBarChart("Taxi Travel Distance", null, "Distance in Kilometers", taxiDataset));
    chartPanel.add(createBarChart("Client Travel Time", null, "Time in Minutes", clientDataset));
    chartPanel.add(createBarChart("Calculation Time", null, "Time in Millis", timeDataset));
    chartPanel.add(createLoadChart());
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
    return new ResultTable(resultTable.getColumns(), convertedData, resultTable.getGroupSize());
  }

  public static double round(double value, int places) {
    if (places < 0) throw new IllegalArgumentException();

    BigDecimal bd = BigDecimal.valueOf(value);
    bd = bd.setScale(places, RoundingMode.HALF_UP);
    return bd.doubleValue();
  }

  private void resetData() {
    SwingUtilities.invokeLater(
        () -> {
          model.getDataVector().removeAllElements();
          model.fireTableDataChanged();
          taxiDataset.clear();
          clientDataset.clear();
          timeDataset.clear();
          calcTimeCollection.removeAllSeries();
          clientCountCollection.removeAllSeries();
          frame.revalidate();
          frame.repaint();
        });
  }

  private ChartPanel createLoadChart() {
    NumberAxis timeAxis = new NumberAxis("Simulation Tick");
    timeAxis.setAutoRangeIncludesZero(false);
    timeAxis.setLabelFont(defaultFont());
    timeAxis.setTickLabelFont(smallFont());

    NumberAxis calcAxis = new NumberAxis("Calc Time [ms]");
    calcAxis.setAutoRangeIncludesZero(true);
    calcAxis.setLabelFont(defaultFont());
    calcAxis.setTickLabelFont(smallFont());

    NumberAxis clientAxis = new NumberAxis("Active Clients");
    clientAxis.setAutoRangeIncludesZero(true);
    clientAxis.setLabelFont(defaultFont());
    clientAxis.setTickLabelFont(smallFont());

    XYAreaRenderer areaRenderer = loadAreaRenderer;
    areaRenderer.setOutline(true);
    areaRenderer.setAutoPopulateSeriesPaint(false);
    areaRenderer.setAutoPopulateSeriesOutlinePaint(false);
    areaRenderer.setAutoPopulateSeriesOutlineStroke(false);

    XYLineAndShapeRenderer lineRenderer = loadLineRenderer;
    lineRenderer.setAutoPopulateSeriesStroke(false);
    lineRenderer.setDefaultStroke(new BasicStroke(1.5f));

    XYPlot plot = new XYPlot();
    plot.setDomainAxis(timeAxis);
    plot.setRangeAxis(0, calcAxis);
    plot.setRangeAxis(1, clientAxis);
    plot.setDataset(0, clientCountCollection);
    plot.setRenderer(0, areaRenderer);
    plot.mapDatasetToRangeAxis(0, 1);
    plot.setDataset(1, calcTimeCollection);
    plot.setRenderer(1, lineRenderer);
    plot.mapDatasetToRangeAxis(1, 0);
    plot.setBackgroundPaint(null);
    plot.setOutlineVisible(false);

    JFreeChart chart =
        new JFreeChart("Load Over Time", JFreeChart.DEFAULT_TITLE_FONT, plot, true);
    chart.setBackgroundPaint(null);
    LegendTitle legend = chart.getLegend();
    if (legend != null) {
      legend.setBackgroundPaint(null);
      legend.setItemFont(smallFont());
    }
    chart.getTitle().setFont(defaultFont());
    return new ChartPanel(chart);
  }

  public void showLoadChart(List<TickDataPoint> tickDataPoints, String algorithmName) {
    if (tickDataPoints == null || tickDataPoints.isEmpty()) return;
    int runIndex = calcTimeCollection.getSeriesCount();
    String runLabel = algorithmName + " | Run " + (runIndex + 1);

    XYSeries calcSeries = new XYSeries(runLabel, true, false);
    XYSeries clientSeries = new XYSeries(runLabel, true, false);
    long lastTick = Long.MIN_VALUE;
    for (TickDataPoint dp : tickDataPoints) {
      if (dp.tick() <= lastTick) continue;
      lastTick = dp.tick();
      double ms = TimeUnit.NANOSECONDS.toMicros(dp.calculationTimeNanos()) / 1000.0;
      calcSeries.add(dp.tick(), ms);
      clientSeries.add(dp.tick(), dp.activeClientCount());
    }

    final int capturedRunIndex = runIndex;
    final XYSeries capturedCalc = calcSeries;
    final XYSeries capturedClient = clientSeries;
    SwingUtilities.invokeLater(
        () -> {
          calcTimeCollection.addSeries(capturedCalc);
          clientCountCollection.addSeries(capturedClient);
          Color base = (Color) loadLineRenderer.lookupSeriesPaint(capturedRunIndex);
          Color areaFill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 35);
          Color areaOutline = new Color(base.getRed(), base.getGreen(), base.getBlue(), 130);
          Stroke dashedStroke =
              new BasicStroke(
                  1.0f,
                  BasicStroke.CAP_BUTT,
                  BasicStroke.JOIN_MITER,
                  10.0f,
                  new float[] {4.0f, 4.0f},
                  0.0f);
          loadAreaRenderer.setSeriesPaint(capturedRunIndex, areaFill);
          loadAreaRenderer.setSeriesOutlinePaint(capturedRunIndex, areaOutline);
          loadAreaRenderer.setSeriesOutlineStroke(capturedRunIndex, dashedStroke);
          loadLineRenderer.setSeriesStroke(capturedRunIndex, new BasicStroke(1.5f));
          frame.pack();
        });
  }

  private JButton resetButton() {
    JButton button = new JButton("Reset Data");
    button.addActionListener(e -> resetData());
    return button;
  }
}
