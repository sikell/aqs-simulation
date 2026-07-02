package de.sikeller.aqs.visualization.controls;

import de.sikeller.aqs.model.RequestDataPoint;
import de.sikeller.aqs.model.ResultTable;
import de.sikeller.aqs.model.TickDataPoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
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
      int requestRepublishTicks,
      int rqsRadius,
      int taxiCount,
      int clientCount,
      int taxiSeatCount,
      String p2pStrategy,
      int p2pOverlayMinNeighbors,
      int p2pOverlayMaxNeighbors,
      int p2pOverlayShortcuts,
      int p2pOverlayMaxDistanceFactor,
      int p2pTopologyScanTicks,
      boolean idleRoamingEnabled,
      String idleRoamingStrategy,
      String idleRoamingMode,
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
              requestRepublishTicks,
              rqsRadius,
              taxiCount,
              clientCount,
              taxiSeatCount,
              p2pStrategy,
              p2pOverlayMinNeighbors,
              p2pOverlayMaxNeighbors,
              p2pOverlayShortcuts,
              p2pOverlayMaxDistanceFactor,
              p2pTopologyScanTicks,
              idleRoamingEnabled,
              idleRoamingStrategy,
              idleRoamingMode,
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

  /**
   * Appends run rows to the CSV file, creating the file with header if it does not exist. Returns
   * the number of rows written in this call.
   */
  static int appendRunRows(String outputDir, List<RunMetricRow> newRows) throws IOException {
    if (newRows == null || newRows.isEmpty()) return 0;
    Path directory = Paths.get(outputDir);
    Files.createDirectories(directory);
    Path runFile = directory.resolve("mass-run-results.csv");
    boolean writeHeader = !Files.exists(runFile) || Files.size(runFile) == 0;
    List<String> lines = new ArrayList<>();
    if (writeHeader) {
      lines.add(
          "timestamp,algorithm,kHops,p2pRequestRepublishTicks,p2pRqsRadius,taxiCount,clientCount,taxiSeatCount,p2pStrategy,p2pOverlayMinNeighbors,p2pOverlayMaxNeighbors,p2pOverlayShortcuts,p2pOverlayMaxDistanceFactor,p2pTopologyScanTicks,idleRoamingEnabled,idleRoamingStrategy,idleRoamingMode,spawnScenario,runIndex,worldSeed,metric,min,max,avg,sum,count,spread");
    }
    for (RunMetricRow row : newRows) {
      lines.add(
          csv(
              row.timestamp(),
              row.algorithm(),
              row.kHops(),
              row.requestRepublishTicks(),
              row.rqsRadius(),
              row.taxiCount(),
              row.clientCount(),
              row.taxiSeatCount(),
              row.p2pStrategy(),
              row.p2pOverlayMinNeighbors(),
              row.p2pOverlayMaxNeighbors(),
              row.p2pOverlayShortcuts(),
              row.p2pOverlayMaxDistanceFactor(),
              row.p2pTopologyScanTicks(),
              row.idleRoamingEnabled(),
              row.idleRoamingStrategy(),
              row.idleRoamingMode(),
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
    StandardOpenOption[] opts =
        writeHeader
            ? new StandardOpenOption[] {
              StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
            }
            : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND};
    Files.write(runFile, lines, StandardCharsets.UTF_8, opts);
    return newRows.size();
  }

  static int appendTickRows(
      String outputDir,
      List<TickDataPoint> points,
      String timestamp,
      String algorithm,
      int kHops,
      int requestRepublishTicks,
      int rqsRadius,
      int taxiCount,
      int clientCount,
      int taxiSeatCount,
      String p2pStrategy,
      int p2pOverlayMinNeighbors,
      int p2pOverlayMaxNeighbors,
      int p2pOverlayShortcuts,
      int p2pOverlayMaxDistanceFactor,
      int p2pTopologyScanTicks,
      boolean idleRoamingEnabled,
      String idleRoamingStrategy,
      String idleRoamingMode,
      String spawnScenario,
      int runIndex,
      int worldSeed)
      throws IOException {
    if (points == null || points.isEmpty()) return 0;
    List<String> lines = new ArrayList<>();
    for (TickDataPoint point : points) {
      double waitingAvg =
          point.waitingTimeCount() == 0
              ? 0
              : 1.0 * point.waitingTimeSum() / point.waitingTimeCount();
      lines.add(
          csv(
              timestamp,
              algorithm,
              kHops,
              requestRepublishTicks,
              rqsRadius,
              taxiCount,
              clientCount,
              taxiSeatCount,
              p2pStrategy,
              p2pOverlayMinNeighbors,
              p2pOverlayMaxNeighbors,
              p2pOverlayShortcuts,
              p2pOverlayMaxDistanceFactor,
              p2pTopologyScanTicks,
              idleRoamingEnabled,
              idleRoamingStrategy,
              idleRoamingMode,
              spawnScenario,
              runIndex,
              worldSeed,
              point.tick(),
              point.calculationTimeNanos(),
              point.calculationTimeNanos() / 1_000_000.0,
              point.activeClientCount(),
              point.servedRequestCount(),
              point.waitingTimeSum(),
              point.waitingTimeCount(),
              waitingAvg,
              point.finishedRequestCount()));
    }
    return appendRows(
        outputDir,
        "mass-run-time-series.csv",
        "timestamp,algorithm,kHops,p2pRequestRepublishTicks,p2pRqsRadius,taxiCount,clientCount,taxiSeatCount,p2pStrategy,p2pOverlayMinNeighbors,p2pOverlayMaxNeighbors,p2pOverlayShortcuts,p2pOverlayMaxDistanceFactor,p2pTopologyScanTicks,idleRoamingEnabled,idleRoamingStrategy,idleRoamingMode,spawnScenario,runIndex,worldSeed,tick,calculationTimeNanos,calculationTimeMillis,activeClientCount,servedRequestCount,waitingTimeSum,waitingTimeCount,waitingTimeAvg,finishedRequestCount",
        lines);
  }

  static int appendRequestRows(
      String outputDir,
      List<RequestDataPoint> points,
      String timestamp,
      String algorithm,
      int kHops,
      int requestRepublishTicks,
      int rqsRadius,
      int taxiCount,
      int clientCount,
      int taxiSeatCount,
      String p2pStrategy,
      int p2pOverlayMinNeighbors,
      int p2pOverlayMaxNeighbors,
      int p2pOverlayShortcuts,
      int p2pOverlayMaxDistanceFactor,
      int p2pTopologyScanTicks,
      boolean idleRoamingEnabled,
      String idleRoamingStrategy,
      String idleRoamingMode,
      String spawnScenario,
      int runIndex,
      int worldSeed)
      throws IOException {
    if (points == null || points.isEmpty()) return 0;
    List<String> lines = new ArrayList<>();
    for (RequestDataPoint point : points) {
      lines.add(
          csv(
              timestamp,
              algorithm,
              kHops,
              requestRepublishTicks,
              rqsRadius,
              taxiCount,
              clientCount,
              taxiSeatCount,
              p2pStrategy,
              p2pOverlayMinNeighbors,
              p2pOverlayMaxNeighbors,
              p2pOverlayShortcuts,
              p2pOverlayMaxDistanceFactor,
              p2pTopologyScanTicks,
              idleRoamingEnabled,
              idleRoamingStrategy,
              idleRoamingMode,
              spawnScenario,
              runIndex,
              worldSeed,
              point.clientName(),
              point.spawnTime(),
              point.pickupTime(),
              point.finishTime(),
              point.waitingTime(),
              point.travelTime(),
              point.originX(),
              point.originY(),
              point.targetX(),
              point.targetY()));
    }
    return appendRows(
        outputDir,
        "mass-run-requests.csv",
        "timestamp,algorithm,kHops,p2pRequestRepublishTicks,p2pRqsRadius,taxiCount,clientCount,taxiSeatCount,p2pStrategy,p2pOverlayMinNeighbors,p2pOverlayMaxNeighbors,p2pOverlayShortcuts,p2pOverlayMaxDistanceFactor,p2pTopologyScanTicks,idleRoamingEnabled,idleRoamingStrategy,idleRoamingMode,spawnScenario,runIndex,worldSeed,clientName,spawnTime,pickupTime,finishTime,waitingTime,travelTime,originX,originY,targetX,targetY",
        lines);
  }

  private static int appendRows(String outputDir, String fileName, String header, List<String> rows)
      throws IOException {
    Path directory = Paths.get(outputDir);
    Files.createDirectories(directory);
    Path file = directory.resolve(fileName);
    boolean writeHeader = !Files.exists(file) || Files.size(file) == 0;
    List<String> lines = new ArrayList<>();
    if (writeHeader) {
      lines.add(header);
    }
    lines.addAll(rows);
    Files.write(
        file,
        lines,
        StandardCharsets.UTF_8,
        writeHeader
            ? new StandardOpenOption[] {
              StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
            }
            : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.APPEND});
    return rows.size();
  }

  /**
   * Writes the aggregate CSV from ALL run rows (must be read back or accumulated for final
   * aggregation).
   */
  static void writeAggregateFromFile(String outputDir) throws IOException {
    Path directory = Paths.get(outputDir);
    Path runFile = directory.resolve("mass-run-results.csv");
    if (!Files.exists(runFile)) return;
    List<RunMetricRow> allRows = readRunCsv(runFile);
    List<AggregateMetricRow> aggregateRows = aggregate(allRows);
    Path aggregateFile = directory.resolve("mass-run-aggregates.csv");
    writeAggregateCsv(aggregateFile, aggregateRows);
  }

  private static List<RunMetricRow> readRunCsv(Path file) throws IOException {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    List<RunMetricRow> rows = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) { // skip header
      String line = lines.get(i);
      if (line.isBlank()) continue;
      String[] parts = parseCsvLine(line);
      if (parts.length < 27) continue;
      try {
        rows.add(
            new RunMetricRow(
                parts[0],
                parts[1],
                Integer.parseInt(parts[2]),
                Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]),
                Integer.parseInt(parts[5]),
                Integer.parseInt(parts[6]),
                Integer.parseInt(parts[7]),
                parts[8],
                Integer.parseInt(parts[9]),
                Integer.parseInt(parts[10]),
                Integer.parseInt(parts[11]),
                Integer.parseInt(parts[12]),
                Integer.parseInt(parts[13]),
                Boolean.parseBoolean(parts[14]),
                parts[15],
                parts[16],
                parts[17],
                Integer.parseInt(parts[18]),
                Integer.parseInt(parts[19]),
                parts[20],
                Double.parseDouble(parts[21]),
                Double.parseDouble(parts[22]),
                Double.parseDouble(parts[23]),
                Double.parseDouble(parts[24]),
                Integer.parseInt(parts[25]),
                Double.parseDouble(parts[26])));
      } catch (NumberFormatException ignored) {
        // skip malformed rows
      }
    }
    return rows;
  }

  private static String[] parseCsvLine(String line) {
    List<String> fields = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (inQuotes) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            current.append('"');
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          current.append(c);
        }
      } else {
        if (c == '"') {
          inQuotes = true;
        } else if (c == ',') {
          fields.add(current.toString());
          current.setLength(0);
        } else {
          current.append(c);
        }
      }
    }
    fields.add(current.toString());
    return fields.toArray(new String[0]);
  }

  static Path writeConfig(String outputDir, MassRunDialog.MassRunConfig config) throws IOException {
    Path directory = Paths.get(outputDir);
    Files.createDirectories(directory);
    Path configFile = directory.resolve("mass-run-config.properties");
    java.util.Properties props = new java.util.Properties();
    props.setProperty("algorithms", String.join(",", config.algorithms()));
    props.setProperty("p2pStrategies", String.join(",", config.p2pStrategies()));
    props.setProperty("spawnScenarios", String.join(",", config.spawnScenarios()));
    props.setProperty(
        "kHops",
        config.kHops().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty(
        "rqsRadius",
        config.rqsRadiusValues().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    props.setProperty(
        "overlayMinNeighbors",
        config.overlayMinNeighborsValues().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    props.setProperty(
        "overlayMaxNeighbors",
        config.overlayMaxNeighborsValues().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    props.setProperty(
        "overlayShortcuts",
        config.overlayShortcutsValues().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    props.setProperty(
        "requestRepublishTicks",
        config.requestRepublishTicksValues().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    props.setProperty(
        "topologyScanTicks",
        config.topologyScanTicksValues().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    props.setProperty(
        "overlayMaxDistanceFactor",
        config.overlayMaxDistanceFactorValues().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    props.setProperty("idleRoamingModes", String.join(",", config.idleRoamingModes()));
    props.setProperty("idleThresholdTicks", String.valueOf(config.idleThresholdTicks()));
    props.setProperty("idleCheckThrottleTicks", String.valueOf(config.idleCheckThrottleTicks()));
    props.setProperty(
        "randomTravelMaxDistanceMeters", String.valueOf(config.randomTravelMaxDistanceMeters()));
    props.setProperty("seenClientTtlTicks", String.valueOf(config.seenClientTtlTicks()));
    props.setProperty("runs", String.valueOf(config.runs()));
    props.setProperty("baseSeed", String.valueOf(config.baseSeed()));
    props.setProperty("outputDir", config.outputDir());
    props.setProperty("parallelWorkers", String.valueOf(config.parallelWorkers()));
    props.setProperty(
        "taxiCounts",
        config.taxiCounts().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
    props.setProperty(
        "clientCounts",
        config.clientCounts().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    props.setProperty("clientSpawnWindow", String.valueOf(config.clientSpawnWindow()));
    props.setProperty("clientSpeed", String.valueOf(config.clientSpeed()));
    props.setProperty(
        "taxiSeatCounts",
        config.taxiSeatCounts().stream()
            .map(String::valueOf)
            .reduce((a, b) -> a + "," + b)
            .orElse(""));
    props.setProperty("taxiSpeed", String.valueOf(config.taxiSpeed()));
    props.setProperty("simulationSpeed", String.valueOf(config.simulationSpeed()));
    props.setProperty(
        "mapSize",
        config.mapSizes().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse(""));
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
              + row.requestRepublishTicks()
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
              + row.p2pOverlayMaxDistanceFactor()
              + "|"
              + row.p2pTopologyScanTicks()
              + "|"
              + row.idleRoamingEnabled()
              + "|"
              + row.idleRoamingStrategy()
              + "|"
              + row.idleRoamingMode()
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
      String algorithm = group.getFirst().algorithm();
      int kHops = group.getFirst().kHops();
      int requestRepublishTicks = group.getFirst().requestRepublishTicks();
      int rqsRadius = group.getFirst().rqsRadius();
      int taxiCount = group.getFirst().taxiCount();
      int clientCount = group.getFirst().clientCount();
      int taxiSeatCount = group.getFirst().taxiSeatCount();
      String p2pStrategy = group.getFirst().p2pStrategy();
      int p2pOverlayMinNeighbors = group.getFirst().p2pOverlayMinNeighbors();
      int p2pOverlayMaxNeighbors = group.getFirst().p2pOverlayMaxNeighbors();
      int p2pOverlayShortcuts = group.getFirst().p2pOverlayShortcuts();
      int p2pOverlayMaxDistanceFactor = group.getFirst().p2pOverlayMaxDistanceFactor();
      int p2pTopologyScanTicks = group.getFirst().p2pTopologyScanTicks();
      boolean idleRoamingEnabled = group.getFirst().idleRoamingEnabled();
      String idleRoamingStrategy = group.getFirst().idleRoamingStrategy();
      String idleRoamingMode = group.getFirst().idleRoamingMode();
      String spawnScenario = group.getFirst().spawnScenario();
      String metric = group.getFirst().metric();

      double avgOfAvg = average(group, RunMetricRow::avg);
      aggregateRows.add(
          new AggregateMetricRow(
              algorithm,
              kHops,
              requestRepublishTicks,
              rqsRadius,
              taxiCount,
              clientCount,
              taxiSeatCount,
              p2pStrategy,
              p2pOverlayMinNeighbors,
              p2pOverlayMaxNeighbors,
              p2pOverlayShortcuts,
              p2pOverlayMaxDistanceFactor,
              p2pTopologyScanTicks,
              idleRoamingEnabled,
              idleRoamingStrategy,
              idleRoamingMode,
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

  private static void writeAggregateCsv(Path file, List<AggregateMetricRow> rows)
      throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add(
        "algorithm,kHops,p2pRequestRepublishTicks,p2pRqsRadius,taxiCount,clientCount,taxiSeatCount,p2pStrategy,p2pOverlayMinNeighbors,p2pOverlayMaxNeighbors,p2pOverlayShortcuts,p2pOverlayMaxDistanceFactor,p2pTopologyScanTicks,idleRoamingEnabled,idleRoamingStrategy,idleRoamingMode,spawnScenario,metric,runs,avgOfAvg,stdDevOfAvg,minAvg,maxAvg,avgSpread,minSpread,maxSpread");
    for (AggregateMetricRow row : rows) {
      lines.add(
          csv(
              row.algorithm(),
              row.kHops(),
              row.requestRepublishTicks(),
              row.rqsRadius(),
              row.taxiCount(),
              row.clientCount(),
              row.taxiSeatCount(),
              row.p2pStrategy(),
              row.p2pOverlayMinNeighbors(),
              row.p2pOverlayMaxNeighbors(),
              row.p2pOverlayShortcuts(),
              row.p2pOverlayMaxDistanceFactor(),
              row.p2pTopologyScanTicks(),
              row.idleRoamingEnabled(),
              row.idleRoamingStrategy(),
              row.idleRoamingMode(),
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
    return Arrays.stream(values)
        .map(MassRunCsvWriter::escape)
        .reduce((l, r) -> l + "," + r)
        .orElse("");
  }

  private static String escape(Object value) {
    String text = Objects.toString(value, "");
    boolean needsQuotes =
        text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
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

  private static double average(
      List<RunMetricRow> rows, java.util.function.ToDoubleFunction<RunMetricRow> selector) {
    if (rows.isEmpty()) {
      return 0d;
    }
    double sum = 0d;
    for (RunMetricRow row : rows) {
      sum += selector.applyAsDouble(row);
    }
    return sum / rows.size();
  }

  private static double min(
      List<RunMetricRow> rows, java.util.function.ToDoubleFunction<RunMetricRow> selector) {
    double min = Double.POSITIVE_INFINITY;
    for (RunMetricRow row : rows) {
      min = Math.min(min, selector.applyAsDouble(row));
    }
    return rows.isEmpty() ? 0d : min;
  }

  private static double max(
      List<RunMetricRow> rows, java.util.function.ToDoubleFunction<RunMetricRow> selector) {
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
      int requestRepublishTicks,
      int rqsRadius,
      int taxiCount,
      int clientCount,
      int taxiSeatCount,
      String p2pStrategy,
      int p2pOverlayMinNeighbors,
      int p2pOverlayMaxNeighbors,
      int p2pOverlayShortcuts,
      int p2pOverlayMaxDistanceFactor,
      int p2pTopologyScanTicks,
      boolean idleRoamingEnabled,
      String idleRoamingStrategy,
      String idleRoamingMode,
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
      int requestRepublishTicks,
      int rqsRadius,
      int taxiCount,
      int clientCount,
      int taxiSeatCount,
      String p2pStrategy,
      int p2pOverlayMinNeighbors,
      int p2pOverlayMaxNeighbors,
      int p2pOverlayShortcuts,
      int p2pOverlayMaxDistanceFactor,
      int p2pTopologyScanTicks,
      boolean idleRoamingEnabled,
      String idleRoamingStrategy,
      String idleRoamingMode,
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

  record OutputFiles(
      Path runCsv, Path aggregateCsv, Path timeSeriesCsv, Path requestCsv, Path configFile) {
    @Override
    public String toString() {
      return String.format(
          Locale.ROOT,
          "%s | %s | %s | %s | %s",
          runCsv,
          aggregateCsv,
          timeSeriesCsv,
          requestCsv,
          configFile != null ? configFile : "no config");
    }
  }
}
