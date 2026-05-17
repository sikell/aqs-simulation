package de.sikeller.aqs.visualization.controls;

import de.sikeller.aqs.model.ResultTable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class MassRunCsvWriter {
  private MassRunCsvWriter() {}

  static List<RunMetricRow> toRunRows(
      ResultTable table,
      String algorithm,
      int kHops,
      int rqsRadius,
      int taxiCount,
      int clientCount,
      int taxiSeatCount,
      String p2pStrategy,
      int p2pOverlayMinNeighbors,
      int p2pOverlayMaxNeighbors,
      int p2pOverlayShortcuts,
      String spawnScenario,
      int runIndex,
      int worldSeed,
      String timestamp) {
    List<RunMetricRow> rows = new ArrayList<>();
    if (table == null || table.getData() == null) {
      return rows;
    }
    for (Object[] row : table.getData()) {
      String metric = String.valueOf(row[0]);
      double min = parseDouble(row[1]);
      double max = parseDouble(row[2]);
      double avg = parseDouble(row[3]);
      double sum = parseDouble(row[4]);
      int count = (int) Math.round(parseDouble(row[5]));
      rows.add(
              new RunMetricRow(
              timestamp,
              algorithm,
              kHops,
              rqsRadius,
              taxiCount,
              clientCount,
              taxiSeatCount,
              p2pStrategy,
               p2pOverlayMinNeighbors,
               p2pOverlayMaxNeighbors,
               p2pOverlayShortcuts,
              spawnScenario,
              runIndex,
              worldSeed,
              metric,
              min,
              max,
              avg,
              sum,
              count,
              max - min));
    }
    return rows;
  }

  static OutputFiles write(String outputDir, List<RunMetricRow> runRows) throws IOException {
    Path directory = Paths.get(outputDir);
    Files.createDirectories(directory);
    List<AggregateMetricRow> aggregateRows = aggregate(runRows);

    Path runFile = directory.resolve("mass-run-results.csv");
    Path aggregateFile = directory.resolve("mass-run-aggregates.csv");

    writeRunCsv(runFile, runRows);
    writeAggregateCsv(aggregateFile, aggregateRows);
    return new OutputFiles(runFile, aggregateFile, null);
  }

  static Path writeConfig(String outputDir, MassRunDialog.MassRunConfig config) throws IOException {
    Path directory = Paths.get(outputDir);
    Files.createDirectories(directory);
    Path configFile = directory.resolve("mass-run-config.properties");
    java.util.Properties props = new java.util.Properties();
    props.setProperty("algorithms", String.join(",", config.algorithms()));
    props.setProperty("p2pStrategies", String.join(",", config.p2pStrategies()));
    props.setProperty("spawnScenarios", String.join(",", config.spawnScenarios()));
    props.setProperty("kHops", config.kHops().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty("rqsRadius", config.rqsRadiusValues().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty("overlayMinNeighbors", config.overlayMinNeighborsValues().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty("overlayMaxNeighbors", config.overlayMaxNeighborsValues().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty("overlayShortcuts", config.overlayShortcutsValues().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty("idleRoamingEnabled", String.valueOf(config.idleRoamingEnabled()));
    props.setProperty("idleRoamingStrategy", String.valueOf(config.idleRoamingStrategy()));
    props.setProperty("idleThresholdTicks", String.valueOf(config.idleThresholdTicks()));
    props.setProperty("idleCheckThrottleTicks", String.valueOf(config.idleCheckThrottleTicks()));
    props.setProperty("randomTravelMaxDistanceMeters", String.valueOf(config.randomTravelMaxDistanceMeters()));
    props.setProperty("runs", String.valueOf(config.runs()));
    props.setProperty("baseSeed", String.valueOf(config.baseSeed()));
    props.setProperty("outputDir", config.outputDir());
    props.setProperty("taxiCounts", config.taxiCounts().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty("clientCounts", config.clientCounts().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty("clientSpawnWindow", String.valueOf(config.clientSpawnWindow()));
    props.setProperty("clientSpeed", String.valueOf(config.clientSpeed()));
    props.setProperty("taxiSeatCounts", config.taxiSeatCounts().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty("taxiSpeed", String.valueOf(config.taxiSpeed()));
    props.setProperty("simulationSpeed", String.valueOf(config.simulationSpeed()));
    props.setProperty("mapSize", config.mapSizes().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    try (java.io.Writer w = Files.newBufferedWriter(configFile, StandardCharsets.UTF_8)) {
      props.store(w, "Mass Run Config – generated " + java.time.Instant.now());
    }
    return configFile;
  }

  private static List<AggregateMetricRow> aggregate(List<RunMetricRow> rows) {
    Map<String, List<RunMetricRow>> grouped = new LinkedHashMap<>();
    for (RunMetricRow row : rows) {
      String key =
          row.algorithm()
              + "|"
              + row.kHops()
              + "|"
              + row.rqsRadius()
              + "|"
              + row.taxiCount()
              + "|"
              + row.clientCount()
              + "|"
              + row.taxiSeatCount()
              + "|"
              + row.p2pStrategy()
              + "|"
              + row.p2pOverlayMinNeighbors()
              + "|"
              + row.p2pOverlayMaxNeighbors()
              + "|"
              + row.p2pOverlayShortcuts()
              + "|"
              + row.spawnScenario()
              + "|"
              + row.metric();
      grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
    }

    List<AggregateMetricRow> aggregateRows = new ArrayList<>();
    for (List<RunMetricRow> group : grouped.values()) {
      if (group.isEmpty()) {
        continue;
      }
      String algorithm = group.get(0).algorithm();
      int kHops = group.get(0).kHops();
      int rqsRadius = group.get(0).rqsRadius();
      int taxiCount = group.get(0).taxiCount();
      int clientCount = group.get(0).clientCount();
      int taxiSeatCount = group.get(0).taxiSeatCount();
      String p2pStrategy = group.get(0).p2pStrategy();
      int p2pOverlayMinNeighbors = group.get(0).p2pOverlayMinNeighbors();
      int p2pOverlayMaxNeighbors = group.get(0).p2pOverlayMaxNeighbors();
      int p2pOverlayShortcuts = group.get(0).p2pOverlayShortcuts();
      String spawnScenario = group.get(0).spawnScenario();
      String metric = group.get(0).metric();

      double avgOfAvg = average(group, RunMetricRow::avg);
      aggregateRows.add(
          new AggregateMetricRow(
              algorithm,
              kHops,
              rqsRadius,
              taxiCount,
              clientCount,
              taxiSeatCount,
              p2pStrategy,
               p2pOverlayMinNeighbors,
               p2pOverlayMaxNeighbors,
               p2pOverlayShortcuts,
              spawnScenario,
              metric,
              group.size(),
              avgOfAvg,
              stdDev(group, RunMetricRow::avg, avgOfAvg),
              min(group, RunMetricRow::avg),
              max(group, RunMetricRow::avg),
              average(group, RunMetricRow::spread),
              min(group, RunMetricRow::spread),
              max(group, RunMetricRow::spread)));
    }
    return aggregateRows;
  }

  private static void writeRunCsv(Path file, List<RunMetricRow> rows) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add(
        "timestamp,algorithm,kHops,p2pRqsRadius,taxiCount,clientCount,taxiSeatCount,p2pStrategy,p2pOverlayMinNeighbors,p2pOverlayMaxNeighbors,p2pOverlayShortcuts,spawnScenario,runIndex,worldSeed,metric,min,max,avg,sum,count,spread");
    for (RunMetricRow row : rows) {
      lines.add(
          csv(
              row.timestamp(),
              row.algorithm(),
              row.kHops(),
              row.rqsRadius(),
              row.taxiCount(),
              row.clientCount(),
              row.taxiSeatCount(),
              row.p2pStrategy(),
               row.p2pOverlayMinNeighbors(),
               row.p2pOverlayMaxNeighbors(),
               row.p2pOverlayShortcuts(),
              row.spawnScenario(),
              row.runIndex(),
              row.worldSeed(),
              row.metric(),
              row.min(),
              row.max(),
              row.avg(),
              row.sum(),
              row.count(),
              row.spread()));
    }
    Files.write(file, lines, StandardCharsets.UTF_8);
  }

  private static void writeAggregateCsv(Path file, List<AggregateMetricRow> rows) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add(
        "algorithm,kHops,p2pRqsRadius,taxiCount,clientCount,taxiSeatCount,p2pStrategy,p2pOverlayMinNeighbors,p2pOverlayMaxNeighbors,p2pOverlayShortcuts,spawnScenario,metric,runs,avgOfAvg,stdDevOfAvg,minAvg,maxAvg,avgSpread,minSpread,maxSpread");
    for (AggregateMetricRow row : rows) {
      lines.add(
          csv(
              row.algorithm(),
              row.kHops(),
              row.rqsRadius(),
              row.taxiCount(),
              row.clientCount(),
              row.taxiSeatCount(),
              row.p2pStrategy(),
               row.p2pOverlayMinNeighbors(),
               row.p2pOverlayMaxNeighbors(),
               row.p2pOverlayShortcuts(),
              row.spawnScenario(),
              row.metric(),
              row.runs(),
              row.avgOfAvg(),
              row.stdDevOfAvg(),
              row.minAvg(),
              row.maxAvg(),
              row.avgSpread(),
              row.minSpread(),
              row.maxSpread()));
    }
    Files.write(file, lines, StandardCharsets.UTF_8);
  }

  private static String csv(Object... values) {
    return Arrays.stream(values).map(MassRunCsvWriter::escape).reduce((l, r) -> l + "," + r).orElse("");
  }

  private static String escape(Object value) {
    String text = Objects.toString(value, "");
    boolean needsQuotes = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
    String escaped = text.replace("\"", "\"\"");
    return needsQuotes ? "\"" + escaped + "\"" : escaped;
  }

  private static double parseDouble(Object value) {
    if (value == null) {
      return 0d;
    }
    String text = Objects.toString(value, "").trim().replace(',', '.');
    if (text.isBlank()) {
      return 0d;
    }
    return Double.parseDouble(text);
  }

  private static double average(List<RunMetricRow> rows, java.util.function.ToDoubleFunction<RunMetricRow> selector) {
    if (rows.isEmpty()) {
      return 0d;
    }
    double sum = 0d;
    for (RunMetricRow row : rows) {
      sum += selector.applyAsDouble(row);
    }
    return sum / rows.size();
  }

  private static double min(List<RunMetricRow> rows, java.util.function.ToDoubleFunction<RunMetricRow> selector) {
    double min = Double.POSITIVE_INFINITY;
    for (RunMetricRow row : rows) {
      min = Math.min(min, selector.applyAsDouble(row));
    }
    return rows.isEmpty() ? 0d : min;
  }

  private static double max(List<RunMetricRow> rows, java.util.function.ToDoubleFunction<RunMetricRow> selector) {
    double max = Double.NEGATIVE_INFINITY;
    for (RunMetricRow row : rows) {
      max = Math.max(max, selector.applyAsDouble(row));
    }
    return rows.isEmpty() ? 0d : max;
  }

  private static double stdDev(
      List<RunMetricRow> rows,
      java.util.function.ToDoubleFunction<RunMetricRow> selector,
      double mean) {
    if (rows.isEmpty()) {
      return 0d;
    }
    double sumSquares = 0d;
    for (RunMetricRow row : rows) {
      double delta = selector.applyAsDouble(row) - mean;
      sumSquares += delta * delta;
    }
    return Math.sqrt(sumSquares / rows.size());
  }

  record RunMetricRow(
      String timestamp,
      String algorithm,
      int kHops,
      int rqsRadius,
      int taxiCount,
      int clientCount,
      int taxiSeatCount,
      String p2pStrategy,
      int p2pOverlayMinNeighbors,
      int p2pOverlayMaxNeighbors,
      int p2pOverlayShortcuts,
      String spawnScenario,
      int runIndex,
      int worldSeed,
      String metric,
      double min,
      double max,
      double avg,
      double sum,
      int count,
      double spread) {}

  record AggregateMetricRow(
      String algorithm,
      int kHops,
      int rqsRadius,
      int taxiCount,
      int clientCount,
      int taxiSeatCount,
      String p2pStrategy,
      int p2pOverlayMinNeighbors,
      int p2pOverlayMaxNeighbors,
      int p2pOverlayShortcuts,
      String spawnScenario,
      String metric,
      int runs,
      double avgOfAvg,
      double stdDevOfAvg,
      double minAvg,
      double maxAvg,
      double avgSpread,
      double minSpread,
      double maxSpread) {}

  record OutputFiles(Path runCsv, Path aggregateCsv, Path configFile) {
    @Override
    public String toString() {
      return String.format(Locale.ROOT, "%s | %s | %s", runCsv, aggregateCsv, configFile != null ? configFile : "no config");
    }
  }
}

