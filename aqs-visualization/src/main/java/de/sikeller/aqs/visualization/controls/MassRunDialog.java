package de.sikeller.aqs.visualization.controls;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
  private final JTextField kHopsField;
  private final JTextField overlayMinNeighborsField;
  private final JTextField overlayShortcutsField;
  private final JTextField runsField;
  private final JTextField baseSeedField;
  private final JTextField outputDirField;
  private final JTextField taxiCountsField;
  private final JTextField clientCountField;
  private final JTextField clientSpawnWindowField;
  private final JTextField clientSpeedField;
  private final JTextField taxiSeatCountsField;
  private final JTextField taxiSpeedField;
  private final JTextField simulationSpeedField;
  private MassRunConfig result;

  private MassRunDialog(Component parent, List<String> availableAlgorithms, Defaults defaults) {
    super(JOptionPane.getFrameForComponent(parent), "Mass Run", true);
    this.availableAlgorithms = availableAlgorithms == null ? List.of() : List.copyOf(availableAlgorithms);

    JPanel content = new JPanel();
    content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
    content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

    JPanel selectionPanel = new JPanel(new GridLayout(1, 2, 8, 8));
    selectionPanel.setBorder(BorderFactory.createTitledBorder("Selection"));
    selectionPanel.add(buildAlgorithmSelectionPanel(defaults.algorithmsCsv()));
    selectionPanel.add(buildStrategySelectionPanel(defaults.p2pStrategiesCsv()));

    JPanel runPanel = new JPanel(new GridLayout(0, 2, 8, 8));
    runPanel.setBorder(BorderFactory.createTitledBorder("Run Setup"));
    kHopsField = new JTextField(defaults.kHopsCsv());
    overlayMinNeighborsField = new JTextField(String.valueOf(defaults.overlayMinNeighbors()));
    overlayShortcutsField = new JTextField(String.valueOf(defaults.overlayShortcuts()));
    runsField = new JTextField(String.valueOf(defaults.runs()));
    baseSeedField = new JTextField(String.valueOf(defaults.baseSeed()));
    outputDirField = new JTextField(defaults.outputDir());
    addRow(runPanel, "k-Hops (CSV)", kHopsField);
    addRow(runPanel, "Overlay min neighbors", overlayMinNeighborsField);
    addRow(runPanel, "Overlay shortcuts", overlayShortcutsField);
    addRow(runPanel, "Runs", runsField);
    addRow(runPanel, "Base seed", baseSeedField);
    addRow(runPanel, "Output dir", outputDirField);

    JPanel worldPanel = new JPanel(new GridLayout(0, 2, 8, 8));
    worldPanel.setBorder(BorderFactory.createTitledBorder("World Parameters"));
    taxiCountsField = new JTextField(defaults.taxiCountsCsv());
    clientCountField = new JTextField(String.valueOf(defaults.clientCount()));
    clientSpawnWindowField = new JTextField(String.valueOf(defaults.clientSpawnWindow()));
    clientSpeedField = new JTextField(String.valueOf(defaults.clientSpeed()));
    taxiSeatCountsField = new JTextField(defaults.taxiSeatCountsCsv());
    taxiSpeedField = new JTextField(String.valueOf(defaults.taxiSpeed()));
    simulationSpeedField = new JTextField(String.valueOf(defaults.simulationSpeed()));
    addRow(worldPanel, "Taxi counts (CSV)", taxiCountsField);
    addRow(worldPanel, "Client count", clientCountField);
    addRow(worldPanel, "Client spawn window", clientSpawnWindowField);
    addRow(worldPanel, "Client speed", clientSpeedField);
    addRow(worldPanel, "Taxi seat counts (CSV)", taxiSeatCountsField);
    addRow(worldPanel, "Taxi speed", taxiSpeedField);
    addRow(worldPanel, "Simulation speed", simulationSpeedField);

    content.add(selectionPanel);
    content.add(runPanel);
    content.add(worldPanel);

    JButton runButton = new JButton("Run");
    runButton.addActionListener(e -> onRun());
    JButton cancelButton = new JButton("Cancel");
    cancelButton.addActionListener(e -> dispose());

    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    actions.add(cancelButton);
    actions.add(runButton);

    setLayout(new BorderLayout(8, 8));
    add(new JScrollPane(content), BorderLayout.CENTER);
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
      List<Integer> kHops = parseCsvInts(kHopsField.getText(), 0, "k-hop");
      int overlayMinNeighbors = parseInt(overlayMinNeighborsField.getText(), 1);
      int overlayShortcuts = parseInt(overlayShortcutsField.getText(), 0);
      int runs = parseInt(runsField.getText(), 1);
      int baseSeed = Integer.parseInt(baseSeedField.getText().trim());
      String outputDir = outputDirField.getText().trim();
      List<Integer> taxiCounts = parseCsvInts(taxiCountsField.getText(), 1, "taxi count");
      int clientCount = parseInt(clientCountField.getText(), 1);
      int clientSpawnWindow = parseInt(clientSpawnWindowField.getText(), 0);
      int clientSpeed = parseInt(clientSpeedField.getText(), 0);
      List<Integer> taxiSeatCounts = parseCsvInts(taxiSeatCountsField.getText(), 1, "taxi seat count");
      int taxiSpeed = parseInt(taxiSpeedField.getText(), 1);
      int simulationSpeed = parseInt(simulationSpeedField.getText(), 1);

      if (outputDir.isBlank()) {
        throw new IllegalArgumentException("Output dir must not be blank");
      }

      result =
          new MassRunConfig(
              algorithms,
              kHops,
              p2pStrategies,
              overlayMinNeighbors,
              overlayShortcuts,
              runs,
              baseSeed,
              outputDir,
              taxiCounts,
              clientCount,
              clientSpawnWindow,
              clientSpeed,
              taxiSeatCounts,
              taxiSpeed,
              simulationSpeed);
      dispose();
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid mass run config", JOptionPane.ERROR_MESSAGE);
    }
  }

  private List<String> selectedAlgorithms() {
    List<String> selected = new ArrayList<>();
    for (Map.Entry<String, JCheckBox> entry : algorithmChecks.entrySet()) {
      if (entry.getValue().isSelected()) {
        selected.add(entry.getKey());
      }
    }
    if (selected.isEmpty()) {
      throw new IllegalArgumentException("Select at least one algorithm");
    }
    return selected;
  }

  private List<String> selectedStrategies() {
    List<String> selected = new ArrayList<>();
    for (Map.Entry<String, JCheckBox> entry : strategyChecks.entrySet()) {
      if (entry.getValue().isSelected()) {
        selected.add(entry.getKey());
      }
    }
    if (selected.isEmpty()) {
      throw new IllegalArgumentException("Select at least one strategy");
    }
    return selected;
  }

  private static int parseInt(String text, int min) {
    int value = Integer.parseInt(text.trim());
    return Math.max(min, value);
  }

  private static List<Integer> parseCsvInts(String text, int min, String label) {
    Set<Integer> values = new LinkedHashSet<>();
    for (String part : text.split(",")) {
      String trimmed = part.trim();
      if (trimmed.isBlank()) {
        continue;
      }
      int value = Integer.parseInt(trimmed);
      values.add(Math.max(min, value));
    }
    if (values.isEmpty()) {
      throw new IllegalArgumentException("At least one " + label + " value is required");
    }
    return new ArrayList<>(values);
  }

  private static Set<String> parseCsvStringsOrEmpty(String text) {
    Set<String> values = new LinkedHashSet<>();
    if (text == null || text.isBlank()) {
      return values;
    }
    for (String part : text.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isBlank()) {
        values.add(trimmed);
      }
    }
    return values;
  }

  record Defaults(
      String algorithmsCsv,
      String kHopsCsv,
      String p2pStrategiesCsv,
      int overlayMinNeighbors,
      int overlayShortcuts,
      int runs,
      int baseSeed,
      String outputDir,
      String taxiCountsCsv,
      int clientCount,
      int clientSpawnWindow,
      int clientSpeed,
      String taxiSeatCountsCsv,
      int taxiSpeed,
      int simulationSpeed) {}

  record MassRunConfig(
      List<String> algorithms,
      List<Integer> kHops,
      List<String> p2pStrategies,
      int overlayMinNeighbors,
      int overlayShortcuts,
      int runs,
      int baseSeed,
      String outputDir,
      List<Integer> taxiCounts,
      int clientCount,
      int clientSpawnWindow,
      int clientSpeed,
      List<Integer> taxiSeatCounts,
      int taxiSpeed,
      int simulationSpeed) {}
}
