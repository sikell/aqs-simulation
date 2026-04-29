package de.sikeller.aqs.runner;

import de.sikeller.aqs.model.Algorithm;
import de.sikeller.aqs.model.AlgorithmParameter;
import de.sikeller.aqs.model.ResultTable;
import de.sikeller.aqs.model.TaxiAlgorithm;
import de.sikeller.aqs.model.WorldObject;
import de.sikeller.aqs.simulation.SimulationRunner;
import de.sikeller.aqs.model.SpawnScenario;
import de.sikeller.aqs.simulation.WorldGeneratorScenario;
import de.sikeller.aqs.taxi.algorithm.collector.TaxiAlgorithmP2PCollector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MassRunMain {
  private static final String PARAM_FORWARD_HOPS = "p2pRequestForwardHops";

  private MassRunMain() {}

  public static void main(String[] args) {
    MassRunConfig config = MassRunConfig.from(args);
    List<RunMetricRow> runRows = new ArrayList<>();

    List<Class<? extends TaxiAlgorithm>> algorithms = resolveAlgorithmClasses(config.algorithms());
    for (Class<? extends TaxiAlgorithm> algorithmClass : algorithms) {
      for (Integer kHops : config.kHops()) {
        for (String scenarioLabel : config.spawnScenarios()) {
          SpawnScenario scenario = SpawnScenario.fromLabel(scenarioLabel);
          for (int runIndex = 1; runIndex <= config.runs(); runIndex++) {
            int seed = config.baseSeed() + (runIndex - 1);
            runRows.addAll(runOnce(config, algorithmClass, kHops, scenario, runIndex, seed));
          }
        }
      }
    }

    List<AggregatedMetricRow> aggregateRows = aggregate(runRows);
    writeCsvs(config.outputDir(), runRows, aggregateRows);
  }

  private static List<RunMetricRow> runOnce(
      MassRunConfig config,
      Class<? extends TaxiAlgorithm> algorithmClass,
      int kHops,
      SpawnScenario scenario,
      int runIndex,
      int seed) {
    TaxiAlgorithm algorithmInstance = instantiateAlgorithm(algorithmClass);
    try {
      Map<String, Integer> algorithmParameters = defaultAlgorithmParameters(algorithmInstance);
      algorithmParameters.put(PARAM_FORWARD_HOPS, Math.max(0, kHops));
      algorithmInstance.setParameters(algorithmParameters);

      Map<String, Integer> runParameters = defaultWorldParameters(config, seed, scenario);
      runParameters.putAll(algorithmParameters);

      WorldObject world = WorldObject.builder().maxX(config.maxX()).maxY(config.maxY()).build();
      SimulationRunner runner =
          new SimulationRunner(
              world,
              new Algorithm(algorithmInstance),
              new WorldGeneratorScenario(),
              SimulationRunner.noVisualizationResultSink());
      runner.setSpeed(100);
      runner.init(runParameters);
      runner.start();
      runner.run();

      String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
      return extractRows(
          runner.getStatsCollector().tableResults(),
          timestamp,
          algorithmClass.getSimpleName(),
          algorithmInstance.getName(),
          kHops,
          scenario.name(),
          runIndex,
          seed);
    } finally {
      algorithmInstance.shutdown();
    }
  }

  private static Map<String, Integer> defaultWorldParameters(
      MassRunConfig config, int worldSeed, SpawnScenario scenario) {
    Map<String, Integer> parameters = new LinkedHashMap<>();
    parameters.put("worldSeed", worldSeed);
    parameters.put("taxiCount", config.taxiCount());
    parameters.put("clientCount", config.clientCount());
    parameters.put("clientSpawnWindow", config.clientSpawnWindow());
    parameters.put("clientSpeed", config.clientSpeed());
    parameters.put("taxiSeatCount", config.taxiSeatCount());
    parameters.put("taxiSpeed", config.taxiSpeed());
    parameters.put("p2pEmbeddedSimulation", 1);
    parameters.put("spawnScenario", scenario.ordinal());
    return parameters;
  }

  private static Map<String, Integer> defaultAlgorithmParameters(TaxiAlgorithm algorithm) {
    Map<String, Integer> values = new HashMap<>();
    for (AlgorithmParameter parameter : algorithm.getParameters().getParameters()) {
      values.put(parameter.name(), parameter.defaultValue());
    }
    return values;
  }

  private static TaxiAlgorithm instantiateAlgorithm(Class<? extends TaxiAlgorithm> algorithmClass) {
    try {
      return algorithmClass.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException ex) {
      throw new IllegalArgumentException("Cannot instantiate algorithm: " + algorithmClass.getName(), ex);
    }
  }

  private static List<Class<? extends TaxiAlgorithm>> resolveAlgorithmClasses(List<String> requestedNames) {
    Algorithm scanner = new Algorithm(new TaxiAlgorithmP2PCollector());
    Map<String, Class<? extends TaxiAlgorithm>> bySimpleName = new LinkedHashMap<>();
    Map<String, Class<? extends TaxiAlgorithm>> byClassName = new LinkedHashMap<>();

    for (Class<?> raw : scanner.getAllAlgorithms()) {
      if (!TaxiAlgorithm.class.isAssignableFrom(raw)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Class<? extends TaxiAlgorithm> typed = (Class<? extends TaxiAlgorithm>) raw;
      bySimpleName.put(raw.getSimpleName().toLowerCase(Locale.ROOT), typed);
      byClassName.put(raw.getName().toLowerCase(Locale.ROOT), typed);
    }

    if (requestedNames.isEmpty()) {
      return List.of(TaxiAlgorithmP2PCollector.class);
    }

    List<Class<? extends TaxiAlgorithm>> resolved = new ArrayList<>();
    for (String requestedName : requestedNames) {
      String key = requestedName.trim().toLowerCase(Locale.ROOT);
      Class<? extends TaxiAlgorithm> match = bySimpleName.get(key);
      if (match == null) {
        match = byClassName.get(key);
      }
      if (match == null) {
        throw new IllegalArgumentException("Unknown algorithm: " + requestedName);
      }
      resolved.add(match);
    }
    return resolved;
  }

  private static List<RunMetricRow> extractRows(
      ResultTable table,
      String timestamp,
      String algorithmClass,
      String algorithmLabel,
      int kHops,
      String spawnScenario,
      int runIndex,
      int worldSeed) {
    List<RunMetricRow> rows = new ArrayList<>();
    for (Object[] row : table.getData()) {
      String metric = String.valueOf(row[0]);
      double min = parseDouble(row[1]);
      double max = parseDouble(row[2]);
      double avg = parseDouble(row[3]);
      double sum = parseDouble(row[4]);
      int count = (int) Math.round(parseDouble(row[5]));
      double spread = max - min;

      rows.add(
          new RunMetricRow(
              timestamp,
              algorithmClass,
              algorithmLabel,
              kHops,
              spawnScenario,
              runIndex,
              worldSeed,
              metric,
              min,
              max,
              avg,
              sum,
              count,
              spread));
    }
    return rows;
  }

  private static double parseDouble(Object value) {
    if (value == null) {
      return 0d;
    }
    String normalized = String.valueOf(value).trim().replace(',', '.');
    if (normalized.isEmpty()) {
      return 0d;
    }
    return Double.parseDouble(normalized);
  }

  private static List<AggregatedMetricRow> aggregate(List<RunMetricRow> runRows) {
    Map<String, List<RunMetricRow>> grouped = new LinkedHashMap<>();
    for (RunMetricRow row : runRows) {
      String key = row.algorithmClass() + "|" + row.kHops() + "|" + row.spawnScenario() + "|" + row.metric();
      grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
    }

    List<AggregatedMetricRow> result = new ArrayList<>();
    for (List<RunMetricRow> group : grouped.values()) {
      if (group.isEmpty()) {
        continue;
      }
      String algorithmClass = group.getFirst().algorithmClass();
      String algorithmLabel = group.getFirst().algorithmLabel();
      int kHops = group.getFirst().kHops();
      String spawnScenario = group.getFirst().spawnScenario();
      String metric = group.getFirst().metric();

      int runs = group.size();
      double avgOfAvg = average(group, RunMetricRow::avg);
      double stdDevOfAvg = stdDev(group, RunMetricRow::avg, avgOfAvg);
      double minAvg = min(group, RunMetricRow::avg);
      double maxAvg = max(group, RunMetricRow::avg);
      double avgSpread = average(group, RunMetricRow::spread);
      double minSpread = min(group, RunMetricRow::spread);
      double maxSpread = max(group, RunMetricRow::spread);

      result.add(
          new AggregatedMetricRow(
              algorithmClass,
              algorithmLabel,
              kHops,
              spawnScenario,
              metric,
              runs,
              avgOfAvg,
              stdDevOfAvg,
              minAvg,
              maxAvg,
              avgSpread,
              minSpread,
              maxSpread));
    }
    return result;
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

  private static void writeCsvs(
      String outputDir,
      List<RunMetricRow> runRows,
      List<AggregatedMetricRow> aggregateRows) {
    Path directory = Paths.get(outputDir);
    try {
      Files.createDirectories(directory);
      writeRunCsv(directory.resolve("mass-run-results.csv"), runRows);
      writeAggregateCsv(directory.resolve("mass-run-aggregates.csv"), aggregateRows);
    } catch (IOException ex) {
      throw new RuntimeException("Could not write CSV output to " + directory, ex);
    }
  }

  private static void writeRunCsv(Path file, List<RunMetricRow> rows) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add(
        "timestamp,algorithmClass,algorithmLabel,kHops,spawnScenario,runIndex,worldSeed,metric,min,max,avg,sum,count,spread");
    for (RunMetricRow row : rows) {
      lines.add(
          csvRow(
              row.timestamp(),
              row.algorithmClass(),
              row.algorithmLabel(),
              row.kHops(),
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

  private static void writeAggregateCsv(Path file, List<AggregatedMetricRow> rows) throws IOException {
    List<String> lines = new ArrayList<>();
    lines.add(
        "algorithmClass,algorithmLabel,kHops,spawnScenario,metric,runs,avgOfAvg,stdDevOfAvg,minAvg,maxAvg,avgSpread,minSpread,maxSpread");
    for (AggregatedMetricRow row : rows) {
      lines.add(
          csvRow(
              row.algorithmClass(),
              row.algorithmLabel(),
              row.kHops(),
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

  private static String csvRow(Object... values) {
    return Arrays.stream(values).map(MassRunMain::csvEscape).reduce((a, b) -> a + "," + b).orElse("");
  }

  private static String csvEscape(Object value) {
    String text = Objects.toString(value, "");
    boolean needsQuotes = text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r");
    String escaped = text.replace("\"", "\"\"");
    return needsQuotes ? "\"" + escaped + "\"" : escaped;
  }

  private record RunMetricRow(
      String timestamp,
      String algorithmClass,
      String algorithmLabel,
      int kHops,
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

  private record AggregatedMetricRow(
      String algorithmClass,
      String algorithmLabel,
      int kHops,
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

  private record MassRunConfig(
      List<String> algorithms,
      List<Integer> kHops,
      List<String> spawnScenarios,
      int runs,
      int baseSeed,
      String outputDir,
      int maxX,
      int maxY,
      int taxiCount,
      int clientCount,
      int clientSpawnWindow,
      int clientSpeed,
      int taxiSeatCount,
      int taxiSpeed) {
    private static final String PREFIX = "aqs.mass.";

    static MassRunConfig from(String[] args) {
      Map<String, String> cli = parseCliArgs(args);
      List<String> algorithms = splitCsv(read(cli, "algorithms", "TaxiAlgorithmP2PCollector"));
      List<Integer> kHops = parseIntList(read(cli, "kHops", "2"), 0);
      List<String> spawnScenarios = splitCsvStrings(read(cli, "spawnScenarios", "BASELINE"));

      return new MassRunConfig(
          algorithms,
          kHops,
          spawnScenarios,
          parseInt(read(cli, "runs", "10"), 1),
          parseInt(read(cli, "baseSeed", "1"), Integer.MIN_VALUE),
          read(cli, "outputDir", "mass-run-results"),
          parseInt(read(cli, "maxX", "40000"), 1),
          parseInt(read(cli, "maxY", "40000"), 1),
          parseInt(read(cli, "taxiCount", "5"), 1),
          parseInt(read(cli, "clientCount", "100"), 1),
          parseInt(read(cli, "clientSpawnWindow", "10000"), 0),
          parseInt(read(cli, "clientSpeed", "5"), 0),
          parseInt(read(cli, "taxiSeatCount", "2"), 1),
          parseInt(read(cli, "taxiSpeed", "80"), 1));
    }

    private static Map<String, String> parseCliArgs(String[] args) {
      Map<String, String> result = new LinkedHashMap<>();
      if (args == null) {
        return result;
      }
      for (String arg : args) {
        if (arg == null || arg.isBlank() || !arg.startsWith("--")) {
          continue;
        }
        int separator = arg.indexOf('=');
        if (separator <= 2 || separator >= arg.length() - 1) {
          continue;
        }
        String key = arg.substring(2, separator).trim();
        String value = arg.substring(separator + 1).trim();
        if (!key.isBlank()) {
          result.put(key, value);
        }
      }
      return result;
    }

    private static String read(Map<String, String> cli, String key, String defaultValue) {
      String cliValue = cli.get(key);
      if (cliValue != null && !cliValue.isBlank()) {
        return cliValue;
      }
      String propertyValue = System.getProperty(PREFIX + key, "");
      if (!propertyValue.isBlank()) {
        return propertyValue;
      }
      return defaultValue;
    }

    private static int parseInt(String value, int minValue) {
      try {
        int parsed = Integer.parseInt(value.trim());
        return Math.max(minValue, parsed);
      } catch (Exception ex) {
        throw new IllegalArgumentException("Invalid integer value: " + value, ex);
      }
    }

    private static List<String> splitCsvStrings(String csv) {
      Set<String> deduplicated = new LinkedHashSet<>();
      for (String entry : csv.split(",")) {
        String trimmed = entry.trim();
        if (!trimmed.isBlank()) {
          deduplicated.add(trimmed.toUpperCase(Locale.ROOT));
        }
      }
      return deduplicated.isEmpty() ? List.of("BASELINE") : List.copyOf(deduplicated);
    }

    private static List<String> splitCsv(String csv) {
      Set<String> deduplicated = new LinkedHashSet<>();
      for (String entry : csv.split(",")) {
        String trimmed = entry.trim();
        if (!trimmed.isBlank()) {
          deduplicated.add(trimmed);
        }
      }
      return deduplicated.isEmpty() ? List.of("TaxiAlgorithmP2PCollector") : List.copyOf(deduplicated);
    }

    private static List<Integer> parseIntList(String csv, int minValue) {
      Set<Integer> deduplicated = new LinkedHashSet<>();
      for (String entry : csv.split(",")) {
        String trimmed = entry.trim();
        if (!trimmed.isBlank()) {
          deduplicated.add(parseInt(trimmed, minValue));
        }
      }
      return deduplicated.isEmpty() ? List.of(2) : List.copyOf(deduplicated);
    }
  }
}

