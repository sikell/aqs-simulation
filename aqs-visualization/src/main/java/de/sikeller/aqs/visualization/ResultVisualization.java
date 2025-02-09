package de.sikeller.aqs.visualization;

import static de.sikeller.aqs.visualization.VisualizationUtils.defaultFont;
import static de.sikeller.aqs.visualization.VisualizationUtils.smallFont;

import de.sikeller.aqs.model.ResultTable;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.NumberFormat;
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
    updateChart(resultTable);
    if (table == null) {
      model = new DefaultTableModel(resultTable.getData(), resultTable.getColumns());
      table = new JTable(model);
      JScrollPane scrollPane = new JScrollPane(table);
      int frameWidth = frame.getWidth();
      scrollPane.setPreferredSize(new Dimension((int) (frameWidth * 0.8), table.getPreferredSize().height));
      frame.setLayout(new GridLayout(2, 1));
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
    ChartPanel taxiChartPanel =
        createBarChart("Taxi Travel Distance", null, "Distance", taxiDataset);
    ChartPanel clientChartPanel = createBarChart("Client Travel Time", null, "Time", clientDataset);

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
}
