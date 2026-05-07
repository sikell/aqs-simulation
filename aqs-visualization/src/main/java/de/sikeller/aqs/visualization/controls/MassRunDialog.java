package de.sikeller.aqs.visualization.controls;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

final class MassRunDialog extends JDialog {
  private final List<String> availableAlgorithms;
  private final Map<String, JCheckBox> algorithmChecks = new LinkedHashMap<>();
  private final Map<String, JCheckBox> strategyChecks = new LinkedHashMap<>();
  private final Map<String, JCheckBox> spawnScenarioChecks = new LinkedHashMap<>();
  private final JTextField kHopsField;
  private final JTextField rqsRadiusField;
  private final JTextField overlayMinNeighborsField;
  private final JTextField overlayMaxNeighborsField;
  private final JTextField overlayShortcutsField;
  private final JTextField runsField;
  private final JTextField baseSeedField;
  private final JTextField outputDirField;
  private final JTextField taxiCountsField;
  private final JTextField clientCountsField;
  private final JTextField clientSpawnWindowField;
  private final JTextField clientSpeedField;
  private final JTextField taxiSeatCountsField;
  private final JTextField taxiSpeedField;
  private final JTextField simulationSpeedField;
  private final JTextField mapSizeField;
  // P2P Collector Options
  private final JCheckBox idleRoamingEnabledCheck;
  private final JTextField idleThresholdField;
  private final JTextField idleCheckThrottleField;
  private final JTextField randomTravelMaxDistanceField;
  private MassRunConfig result;

  private MassRunDialog(Component parent, List<String> availableAlgorithms, Defaults defaults) {
    super(JOptionPane.getFrameForComponent(parent), "Mass Run", true);
    this.availableAlgorithms = availableAlgorithms == null ? List.of() : List.copyOf(availableAlgorithms);

    JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    JPanel selectionPanel = new JPanel(new GridLayout(1, 3, 8, 8));
    selectionPanel.setBorder(BorderFactory.createTitledBorder("Selection"));
    selectionPanel.add(buildAlgorithmSelectionPanel(defaults.algorithmsCsv()));
    selectionPanel.add(buildStrategySelectionPanel(defaults.p2pStrategiesCsv()));
    selectionPanel.add(buildSpawnScenarioPanel(defaults.spawnScenariosCsv()));

    JPanel runPanel = new JPanel(new GridLayout(0, 2, 8, 8));
    runPanel.setBorder(BorderFactory.createTitledBorder("Run Setup"));
    runsField = new JTextField(String.valueOf(defaults.runs()));
    baseSeedField = new JTextField(String.valueOf(defaults.baseSeed()));
    outputDirField = new JTextField(defaults.outputDir());
    addRow(runPanel, "Runs", runsField);
    addRow(runPanel, "Base seed", baseSeedField);
    addRow(runPanel, "Output dir", outputDirField);

    JPanel worldPanel = new JPanel(new GridLayout(0, 2, 8, 8));
    worldPanel.setBorder(BorderFactory.createTitledBorder("World Parameters"));
    taxiCountsField = new JTextField(defaults.taxiCountsCsv());
    clientCountsField = new JTextField(defaults.clientCountsCsv());
    clientSpawnWindowField = new JTextField(String.valueOf(defaults.clientSpawnWindow()));
    clientSpeedField = new JTextField(String.valueOf(defaults.clientSpeed()));
    taxiSeatCountsField = new JTextField(defaults.taxiSeatCountsCsv());
    taxiSpeedField = new JTextField(String.valueOf(defaults.taxiSpeed()));
    simulationSpeedField = new JTextField(String.valueOf(defaults.simulationSpeed()));
    mapSizeField = new JTextField(String.valueOf(defaults.mapSize()));
    addRow(worldPanel, "Taxi counts (CSV)", taxiCountsField);
    addRow(worldPanel, "Client counts (CSV, pairwise with taxi counts)", clientCountsField);
    addRow(worldPanel, "Client spawn window", clientSpawnWindowField);
    addRow(worldPanel, "Client speed", clientSpeedField);
    addRow(worldPanel, "Taxi seat counts (CSV)", taxiSeatCountsField);
    addRow(worldPanel, "Taxi speed", taxiSpeedField);
    addRow(worldPanel, "Simulation speed", simulationSpeedField);
    addRow(worldPanel, "Map size [m]", mapSizeField);

    JPanel collectorPanel = new JPanel(new GridLayout(0, 2, 8, 8));
    collectorPanel.setBorder(BorderFactory.createTitledBorder("P2P Collector Options"));
    kHopsField = new JTextField(defaults.kHopsCsv());
    rqsRadiusField = new JTextField(defaults.rqsRadiusCsv());
    overlayMinNeighborsField = new JTextField(defaults.overlayMinNeighborsCsv());
    overlayMaxNeighborsField = new JTextField(defaults.overlayMaxNeighborsCsv());
    overlayShortcutsField = new JTextField(defaults.overlayShortcutsCsv());
    idleRoamingEnabledCheck = new JCheckBox();
    idleRoamingEnabledCheck.setSelected(defaults.idleRoamingEnabled());
    idleThresholdField = new JTextField(String.valueOf(defaults.idleThresholdTicks()));
    idleCheckThrottleField = new JTextField(String.valueOf(defaults.idleCheckThrottleTicks()));
    randomTravelMaxDistanceField = new JTextField(String.valueOf(defaults.randomTravelMaxDistanceMeters()));
    addRow(collectorPanel, "k-Hops (CSV)", kHopsField);
    addRow(collectorPanel, "RQS radius (CSV)", rqsRadiusField);
    addRow(collectorPanel, "Overlay min neighbors (CSV)", overlayMinNeighborsField);
    addRow(collectorPanel, "Overlay max neighbors (CSV)", overlayMaxNeighborsField);
    addRow(collectorPanel, "Overlay shortcuts (CSV)", overlayShortcutsField);
    addRow(collectorPanel, "Idle roaming enabled", idleRoamingEnabledCheck);
    addRow(collectorPanel, "Idle threshold [ticks]", idleThresholdField);
    addRow(collectorPanel, "Idle check throttle [ticks]", idleCheckThrottleField);
    addRow(collectorPanel, "Random travel max distance [m]", randomTravelMaxDistanceField);

    content.add(selectionPanel);
    content.add(runPanel);
    content.add(worldPanel);
    content.add(collectorPanel);

    JButton runButton = new JButton("Run");
    runButton.addActionListener(e -> onRun());
    JButton cancelButton = new JButton("Cancel");
    cancelButton.addActionListener(e -> dispose());
    JButton copyConfigButton = new JButton("Copy Config");
    copyConfigButton.addActionListener(e -> copyConfigToClipboard());
    JButton pasteConfigButton = new JButton("Paste Config");
    pasteConfigButton.addActionListener(e -> pasteConfigFromClipboard());

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    actions.add(copyConfigButton);
    actions.add(pasteConfigButton);
    actions.add(cancelButton);
    actions.add(runButton);

    setLayout(new BorderLayout(8, 8));
    JScrollPane scrollPane = new JScrollPane(content);
    scrollPane.getVerticalScrollBar().setUnitIncrement(20);
    scrollPane.getVerticalScrollBar().setBlockIncrement(80);
    add(scrollPane, BorderLayout.CENTER);
    add(actions, BorderLayout.SOUTH);
    setPreferredSize(new Dimension(920, 700));
    setMinimumSize(new Dimension(820, 620));
    pack();
    setLocationRelativeTo(parent);
  }

  private Component buildAlgorithmSelectionPanel(String defaultsCsv) {
    JPanel panel = new JPanel();
    panel.setBorder(BorderFactory.createTitledBorder("Algorithms"));
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

    Set<String> defaults = parseCsvStringsOrEmpty(defaultsCsv);
    for (String algorithm : availableAlgorithms) {
      JCheckBox check = new JCheckBox(algorithm, defaults.contains(algorithm));
      algorithmChecks.put(algorithm, check);
      panel.add(check);
    }
    return new JScrollPane(panel);
  }

  private Component buildSpawnScenarioPanel(String defaultsCsv) {
    JPanel panel = new JPanel();
    panel.setBorder(BorderFactory.createTitledBorder("Spawn Scenarios"));
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

    Set<String> defaults = parseCsvStringsOrEmpty(defaultsCsv);
    addSpawnScenarioCheck(panel, "BASELINE", defaults);
    addSpawnScenarioCheck(panel, "RUSH_HOUR", defaults);
    addSpawnScenarioCheck(panel, "SPATIAL_IMBALANCE", defaults);
    addSpawnScenarioCheck(panel, "SPATIAL_ISLANDS", defaults);
    return panel;
  }

  private void addSpawnScenarioCheck(JPanel panel, String scenario, Set<String> defaults) {
    JCheckBox check = new JCheckBox(scenario, defaults.contains(scenario));
    spawnScenarioChecks.put(scenario, check);
    panel.add(check);
  }

  private Component buildStrategySelectionPanel(String defaultsCsv) {
    JPanel panel = new JPanel();
    panel.setBorder(BorderFactory.createTitledBorder("P2P strategies"));
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

    Set<String> defaults = parseCsvStringsOrEmpty(defaultsCsv);
    addStrategyCheck(panel, "nearest", defaults);
    addStrategyCheck(panel, "greedy", defaults);
    return panel;
  }

  private void addStrategyCheck(JPanel panel, String strategy, Set<String> defaults) {
    JCheckBox check = new JCheckBox(strategy, defaults.contains(strategy));
    strategyChecks.put(strategy, check);
    panel.add(check);
  }

  static MassRunConfig open(Component parent, List<String> availableAlgorithms, Defaults defaults) {
    MassRunDialog dialog = new MassRunDialog(parent, availableAlgorithms, defaults);
    dialog.setVisible(true);
    return dialog.result;
  }

  private void addRow(JPanel form, String label, Component value) {
    JLabel jLabel = new JLabel(label);
    jLabel.setToolTipText(label);
    form.add(jLabel);
    form.add(value);
  }

  private void onRun() {
    try {
      List<String> algorithms = selectedAlgorithms();
      List<String> p2pStrategies = selectedStrategies();
      List<String> spawnScenarios = selectedSpawnScenarios();
      List<Integer> kHops = parseCsvInts(kHopsField.getText(), 0, "k-hop");
      List<Integer> rqsRadiusValues = parseCsvInts(rqsRadiusField.getText(), 1, "RQS radius");
      List<Integer> overlayMinNeighborsValues =
          parseCsvInts(overlayMinNeighborsField.getText(), 1, "overlay min neighbors");
      List<Integer> overlayMaxNeighborsValues =
          parseCsvInts(overlayMaxNeighborsField.getText(), 1, "overlay max neighbors");
      List<Integer> overlayShortcutsValues =
          parseCsvInts(overlayShortcutsField.getText(), 0, "overlay shortcuts");
      int runs = parseInt(runsField.getText(), 1);
      int baseSeed = Integer.parseInt(baseSeedField.getText().trim());
      String outputDir = outputDirField.getText().trim();
      List<Integer> taxiCounts = parseCsvIntList(taxiCountsField.getText(), 1, "taxi count");
      List<Integer> clientCounts = parseCsvIntList(clientCountsField.getText(), 1, "client count");
      int clientSpawnWindow = parseInt(clientSpawnWindowField.getText(), 0);
      int clientSpeed = parseInt(clientSpeedField.getText(), 0);
      List<Integer> taxiSeatCounts = parseCsvInts(taxiSeatCountsField.getText(), 1, "taxi seat count");
      int taxiSpeed = parseInt(taxiSpeedField.getText(), 1);
      int simulationSpeed = parseInt(simulationSpeedField.getText(), 1);
      int mapSize = parseInt(mapSizeField.getText(), 1);
      boolean idleRoamingEnabled = idleRoamingEnabledCheck.isSelected();
      int idleThresholdTicks = parseInt(idleThresholdField.getText(), 1);
      int idleCheckThrottleTicks = parseInt(idleCheckThrottleField.getText(), 1);
      int randomTravelMaxDistance = parseInt(randomTravelMaxDistanceField.getText(), 1);

      if (outputDir.isBlank()) {
        throw new IllegalArgumentException("Output dir must not be blank");
      }
      if (taxiCounts.size() != clientCounts.size()) {
        throw new IllegalArgumentException(
            "Taxi counts and client counts must have the same number of entries for pairwise runs");
      }

      result =
          new MassRunConfig(
              algorithms,
              kHops,
              rqsRadiusValues,
              p2pStrategies,
              overlayMinNeighborsValues,
              overlayMaxNeighborsValues,
              overlayShortcutsValues,
              spawnScenarios,
              runs,
              baseSeed,
              outputDir,
              taxiCounts,
              clientCounts,
              clientSpawnWindow,
              clientSpeed,
              taxiSeatCounts,
              taxiSpeed,
              simulationSpeed,
              mapSize,
              idleRoamingEnabled,
              idleThresholdTicks,
              idleCheckThrottleTicks,
              randomTravelMaxDistance);
      dispose();
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid mass run config", JOptionPane.ERROR_MESSAGE);
    }
  }

  private void copyConfigToClipboard() {
    try {
      String text = serializeConfig();
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(this, "Failed to copy config: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private void pasteConfigFromClipboard() {
    try {
      String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
      applyConfig(text);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(this, "Failed to paste config: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  String serializeConfig() {
    Properties props = new Properties();
    List<String> checkedAlgos = new ArrayList<>();
    algorithmChecks.forEach((k, v) -> { if (v.isSelected()) checkedAlgos.add(k); });
    props.setProperty("algorithms", String.join(",", checkedAlgos));
    List<String> checkedStrategies = new ArrayList<>();
    strategyChecks.forEach((k, v) -> { if (v.isSelected()) checkedStrategies.add(k); });
    props.setProperty("p2pStrategies", String.join(",", checkedStrategies));
    List<String> checkedScenarios = new ArrayList<>();
    spawnScenarioChecks.forEach((k, v) -> { if (v.isSelected()) checkedScenarios.add(k); });
    props.setProperty("spawnScenarios", String.join(",", checkedScenarios));
    props.setProperty("kHops", kHopsField.getText());
    props.setProperty("rqsRadius", rqsRadiusField.getText());
    props.setProperty("overlayMinNeighbors", overlayMinNeighborsField.getText());
    props.setProperty("overlayMaxNeighbors", overlayMaxNeighborsField.getText());
    props.setProperty("overlayShortcuts", overlayShortcutsField.getText());
    props.setProperty("idleRoamingEnabled", String.valueOf(idleRoamingEnabledCheck.isSelected()));
    props.setProperty("idleThresholdTicks", idleThresholdField.getText());
    props.setProperty("idleCheckThrottleTicks", idleCheckThrottleField.getText());
    props.setProperty("randomTravelMaxDistanceMeters", randomTravelMaxDistanceField.getText());
    props.setProperty("runs", runsField.getText());
    props.setProperty("baseSeed", baseSeedField.getText());
    props.setProperty("outputDir", outputDirField.getText());
    props.setProperty("taxiCounts", taxiCountsField.getText());
    props.setProperty("clientCounts", clientCountsField.getText());
    props.setProperty("clientSpawnWindow", clientSpawnWindowField.getText());
    props.setProperty("clientSpeed", clientSpeedField.getText());
    props.setProperty("taxiSeatCounts", taxiSeatCountsField.getText());
    props.setProperty("taxiSpeed", taxiSpeedField.getText());
    props.setProperty("simulationSpeed", simulationSpeedField.getText());
    props.setProperty("mapSize", mapSizeField.getText());
    StringWriter sw = new StringWriter();
    try {
      props.store(sw, "Mass Run Config");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return sw.toString();
  }

  void applyConfig(String configText) {
    Properties props = new Properties();
    try {
      props.load(new StringReader(configText));
    } catch (IOException e) {
      JOptionPane.showMessageDialog(this, "Failed to parse config: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
      return;
    }
    Set<String> algos = parseCsvStringsOrEmpty(props.getProperty("algorithms", ""));
    algorithmChecks.forEach((k, v) -> v.setSelected(algos.contains(k)));
    Set<String> strategies = parseCsvStringsOrEmpty(props.getProperty("p2pStrategies", ""));
    strategyChecks.forEach((k, v) -> v.setSelected(strategies.contains(k)));
    Set<String> scenarios = parseCsvStringsOrEmpty(props.getProperty("spawnScenarios", ""));
    spawnScenarioChecks.forEach((k, v) -> v.setSelected(scenarios.contains(k)));
    setFieldIfPresent(kHopsField, props, "kHops");
    setFieldIfPresent(rqsRadiusField, props, "rqsRadius");
    setFieldIfPresent(overlayMinNeighborsField, props, "overlayMinNeighbors");
    setFieldIfPresent(overlayMaxNeighborsField, props, "overlayMaxNeighbors");
    setFieldIfPresent(overlayShortcutsField, props, "overlayShortcuts");
    if (props.containsKey("idleRoamingEnabled")) {
      idleRoamingEnabledCheck.setSelected(Boolean.parseBoolean(props.getProperty("idleRoamingEnabled")));
    }
    setFieldIfPresent(idleThresholdField, props, "idleThresholdTicks");
    setFieldIfPresent(idleCheckThrottleField, props, "idleCheckThrottleTicks");
    setFieldIfPresent(randomTravelMaxDistanceField, props, "randomTravelMaxDistanceMeters");
    setFieldIfPresent(runsField, props, "runs");
    setFieldIfPresent(baseSeedField, props, "baseSeed");
    setFieldIfPresent(outputDirField, props, "outputDir");
    setFieldIfPresent(taxiCountsField, props, "taxiCounts");
    setFieldIfPresent(clientCountsField, props, "clientCounts");
    setFieldIfPresent(clientSpawnWindowField, props, "clientSpawnWindow");
    setFieldIfPresent(clientSpeedField, props, "clientSpeed");
    setFieldIfPresent(taxiSeatCountsField, props, "taxiSeatCounts");
    setFieldIfPresent(taxiSpeedField, props, "taxiSpeed");
    setFieldIfPresent(simulationSpeedField, props, "simulationSpeed");
    setFieldIfPresent(mapSizeField, props, "mapSize");
  }

  private static void setFieldIfPresent(JTextField field, Properties props, String key) {
    if (props.containsKey(key)) {
      field.setText(props.getProperty(key));
    }
  }

  private List<String> selectedAlgorithms() {
    List<String> selected = new ArrayList<>();
    for (Map.Entry<String, JCheckBox> entry : algorithmChecks.entrySet()) {
      if (entry.getValue().isSelected()) selected.add(entry.getKey());
    }
    if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one algorithm");
    return selected;
  }

  private List<String> selectedSpawnScenarios() {
    List<String> selected = new ArrayList<>();
    for (Map.Entry<String, JCheckBox> entry : spawnScenarioChecks.entrySet()) {
      if (entry.getValue().isSelected()) selected.add(entry.getKey());
    }
    if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one spawn scenario");
    return selected;
  }

  private List<String> selectedStrategies() {
    List<String> selected = new ArrayList<>();
    for (Map.Entry<String, JCheckBox> entry : strategyChecks.entrySet()) {
      if (entry.getValue().isSelected()) selected.add(entry.getKey());
    }
    if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one strategy");
    return selected;
  }

  private static int parseInt(String text, int min) {
    return Math.max(min, Integer.parseInt(text.trim()));
  }

  private static List<Integer> parseCsvInts(String text, int min, String label) {
    Set<Integer> values = new LinkedHashSet<>();
    for (String part : text.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isBlank()) values.add(Math.max(min, parseIntToken(trimmed, label)));
    }
    if (values.isEmpty()) throw new IllegalArgumentException("At least one " + label + " value is required");
    return new ArrayList<>(values);
  }

  private static List<Integer> parseCsvIntList(String text, int min, String label) {
    List<Integer> values = new ArrayList<>();
    for (String part : text.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isBlank()) values.add(Math.max(min, parseIntToken(trimmed, label)));
    }
    if (values.isEmpty()) throw new IllegalArgumentException("At least one " + label + " value is required");
    return values;
  }

  private static int parseIntToken(String token, String label) {
    if (token == null) throw new IllegalArgumentException("Invalid " + label + " value: null");
    String n = token.trim();
    if (n.equalsIgnoreCase("INF") || n.equalsIgnoreCase("MAX") || n.equalsIgnoreCase("Integer.MAX_VALUE"))
      return Integer.MAX_VALUE;
    return Integer.parseInt(n);
  }

  private static Set<String> parseCsvStringsOrEmpty(String text) {
    Set<String> values = new LinkedHashSet<>();
    if (text == null || text.isBlank()) return values;
    for (String part : text.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isBlank()) values.add(trimmed);
    }
    return values;
  }

  record Defaults(
      String algorithmsCsv,
      String kHopsCsv,
      String rqsRadiusCsv,
      String p2pStrategiesCsv,
      String overlayMinNeighborsCsv,
      String overlayMaxNeighborsCsv,
      String overlayShortcutsCsv,
      String spawnScenariosCsv,
      int runs,
      int baseSeed,
      String outputDir,
      String taxiCountsCsv,
      String clientCountsCsv,
      int clientSpawnWindow,
      int clientSpeed,
      String taxiSeatCountsCsv,
      int taxiSpeed,
      int simulationSpeed,
      int mapSize,
      boolean idleRoamingEnabled,
      int idleThresholdTicks,
      int idleCheckThrottleTicks,
      int randomTravelMaxDistanceMeters) {}

  record MassRunConfig(
      List<String> algorithms,
      List<Integer> kHops,
      List<Integer> rqsRadiusValues,
      List<String> p2pStrategies,
      List<Integer> overlayMinNeighborsValues,
      List<Integer> overlayMaxNeighborsValues,
      List<Integer> overlayShortcutsValues,
      List<String> spawnScenarios,
      int runs,
      int baseSeed,
      String outputDir,
      List<Integer> taxiCounts,
      List<Integer> clientCounts,
      int clientSpawnWindow,
      int clientSpeed,
      List<Integer> taxiSeatCounts,
      int taxiSpeed,
      int simulationSpeed,
      int mapSize,
      boolean idleRoamingEnabled,
      int idleThresholdTicks,
      int idleCheckThrottleTicks,
      int randomTravelMaxDistanceMeters) {}
}
