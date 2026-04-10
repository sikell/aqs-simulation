package de.sikeller.aqs.visualization.controls;

import de.sikeller.aqs.model.AlgorithmParameter;
import de.sikeller.aqs.model.P2PNetworkEdgeSnapshot;
import de.sikeller.aqs.model.P2PNetworkNodeSnapshot;
import de.sikeller.aqs.model.P2PNetworkSnapshot;
import de.sikeller.aqs.model.P2PStatusProvider;
import de.sikeller.aqs.model.SimulationControl;
import de.sikeller.aqs.model.TaxiAlgorithm;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.lang.reflect.InvocationTargetException;
import java.text.NumberFormat;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.Timer;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import de.sikeller.aqs.visualization.drawing.VisualizationProperties;
import de.sikeller.aqs.visualization.drawing.VisualizationUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaxiScenarioControl extends AbstractControl {
  private static final String P2P_COLLECTOR_SIMPLE_NAME = "TaxiAlgorithmP2PCollector";
  private static final String MODE_LOCAL = "LOCAL";
  private static final String MODE_P2P_SIMULATED = "P2P-SIMULATED";
  private static final String MODE_P2P_LAN = "P2P-LAN";
  private static final String P2P_MULTICAST_GROUP_FIELD = "p2pMulticastGroup";
  private static final Set<String> P2P_PORT_FIELDS =
      Set.of("p2pTcpPort", "p2pDiscoveryPort");
  private static final Set<String> P2P_CORE_PARAMETERS =
      Set.of(
          "p2pFixedSearchRadius",
          "p2pRequestForwardHops",
          "p2pOverlayMaxNeighbors",
          "p2pOverlayShortcuts",
          "p2pOfferCollectionTicks",
          "p2pRequestRepublishTicks",
          "p2pTopologyScanTicks",
          "p2pRqsRouteProximityMode",
          "CalculateFullTaxis",
          "p2pVehicleOpenClientStrategy");
  private static final Set<String> P2P_ADVANCED_HIDDEN_PARAMETERS =
      Set.of(
          "p2pVehicleCommitLeaseTicks",
          "p2pVehicleRebidIntervalTicks",
          "p2pVehicleRequestCacheTtlTicks");
  private List<Class<?>> algorithmList;
  private final SimulationControl simulation;
  private Map<String, Integer> inputParameterMap;
  private Map<String, Integer> algorithmParameterMap;
  private Map<String, Integer> allParameterMap = new HashMap<>();
  private HashMap<String, Component> componentMap;
  private BatchProcessingProperties batchProperties = new BatchProcessingProperties();
  private JPanel buttons;
  private JPanel worldInputs;
  private JPanel algorithmInputs;
  private JPanel controls;
  private JPanel selection;
  private JPanel batchProcessing;
  private JPanel p2pStatusPanel;
  private JButton p2pScanNowButton;
  private P2PTopologyPanel p2pTopologyPanel;
  private JLabel p2pModeValue;
  private JLabel p2pPeersValue;
  private JLabel p2pVehiclePeersValue;
  private JLabel p2pPendingValue;
  private JLabel p2pOverlayModeValue;
  private JLabel p2pOverlayNeighborsValue;
  private JLabel p2pRqsRadiusValue;
  private JLabel p2pRqsCenterValue;
  private JLabel p2pVehicleStrategyValue;
  private JLabel p2pVehicleOutOfRangeValue;
  private JLabel p2pTopologyEdgesValue;
  private JLabel p2pShortcutEdgesValue;
  private JLabel p2pLastEventValue;
  private JLabel p2pModeWarningLabel;
  private VisualizationProperties visualizationProperties;
  private Timer p2pStatusTimer;
  private boolean modeSwitchInProgress;
  private static final String DEFAULT_TAXI_COUNT_TOOLTIP =
      "Set the count of taxis to be spawned in the simulation run";

  public TaxiScenarioControl(SimulationControl simulation) {
    this.simulation = simulation;
    add(setup());
    p2pStatusTimer = new Timer(1000, e -> refreshP2PStatus());
    p2pStatusTimer.start();
  }

  private JPanel setup() {
    controls = new JPanel();
    buttons = new JPanel();
    worldInputs = new JPanel();
    algorithmInputs = new JPanel();
    algorithmParameterMap = new HashMap<>();
    selection = new JPanel();
    buttons.add(initializeSimulationButton());
    buttons.add(startButton());
    buttons.add(stopButton());
    buttons.add(copyConfigButton());
    buttons.add(pasteConfigButton());
    buttons.add(showResultsButton());
    selection.add(label("Simulation mode", "simulationModeLabel"));
    selection.add(modeSelectionBox());
    p2pModeWarningLabel = new JLabel(" ");
    p2pModeWarningLabel.setName("p2pModeWarningLabel");
    p2pModeWarningLabel.setForeground(new Color(183, 92, 0));
    p2pModeWarningLabel.setVisible(false);
    selection.add(p2pModeWarningLabel);
    selection.add(new JLabel(""));
    selection.add(label("Select algorithm", "algoSelectionLabel"));
    selection.add(algorithmSelectionBox());
    selection.add(algorithmSelectionButton());
    worldInputs.add(label("World seed", "worldSeed"));
    worldInputs.add(
        spinner(
            "worldSeed",
            "Set a simulation random seed",
            1,
            Integer.MAX_VALUE,
            new Random().nextInt(Integer.MAX_VALUE),
            1));
    worldInputs.add(label("Taxi count", "taxiCountLabel"));
    worldInputs.add(
        spinner(
            "taxiCount",
            "Set the count of taxis to be spawned in the simulation run",
            1,
            1_000_000_000,
            5,
            1));
    worldInputs.add(label("Client count", "clientCountLabel"));
    worldInputs.add(clientCountSpinner());
    worldInputs.add(label("Client spawn window [s]", "clientSpawnWindowLabel"));
    worldInputs.add(clientSpawnWindowSpinner());
    worldInputs.add(label("Client speed [km/h]", "clientSpeed"));
    worldInputs.add(clientSpeedSpinner());
    worldInputs.add(label("Taxi seats", "taxiSeatCount"));
    worldInputs.add(taxiSeatCountSpinner());
    worldInputs.add(label("Taxi speed [km/h]", "taxiSpeed"));
    worldInputs.add(taxiSpeedSpinner());
    worldInputs.add(label("Simulation speed", "simulationSpeedLabel"));
    worldInputs.add(simulationSpeed());
    worldInputs.setBorder(new TitledBorder("World Parameters"));
    controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
    worldInputs.setLayout(new GridLayout(8, 2, GAP, GAP));
    p2pStatusPanel = setupP2PStatusPanel();
    p2pScanNowButton = createP2PScanNowButton();
    p2pTopologyPanel = new P2PTopologyPanel();
    p2pTopologyPanel.setBorder(new TitledBorder("P2P Network Topology"));
    p2pTopologyPanel.setPreferredSize(new Dimension(460, 230));
    batchProcessing = new BatchProcessingControl(batchProperties);
    controls.add(selection);
    controls.add(buttons);
    controls.add(worldInputs);
    controls.add(algorithmInputs);
    controls.add(p2pStatusPanel);
    controls.add(p2pScanNowButton);
    controls.add(batchProcessing);

    createComponentMap();
    applyModeToUi();
    refreshP2PStatus();

    return controls;
  }

  private JComboBox<String> algorithmSelectionBox() {
    algorithmList = simulation.getAlgorithm().getAllAlgorithms();
    List<String> choices = new ArrayList<>();
    algorithmList.forEach(entry -> choices.add(entry.getSimpleName()));
    JComboBox<String> algorithms = new JComboBox<>();
    algorithms.setName("algorithmSelectionBox");
    choices.forEach(algorithms::addItem);
    algorithms.setSelectedItem(simulation.getAlgorithm().get().getClass().getSimpleName());
    return algorithms;
  }

  private JComboBox<String> modeSelectionBox() {
    JComboBox<String> modes = new JComboBox<>();
    modes.setName("simulationModeBox");
    modes.addItem(MODE_LOCAL);
    modes.addItem(MODE_P2P_SIMULATED);
    modes.addItem(MODE_P2P_LAN);
    modes.setSelectedItem(isP2PAlgorithm(simulation.getAlgorithm().get()) ? MODE_P2P_SIMULATED : MODE_LOCAL);
    modes.addActionListener(
        e -> {
          generateParameters();
          applyModeToUi();
        });
    return modes;
  }

  private JPanel setupP2PStatusPanel() {
    JPanel panel = new JPanel(new GridLayout(13, 2, GAP, GAP));
    panel.setBorder(new TitledBorder("P2P Network Status"));

    panel.add(label("Mode", "p2pModeLabel"));
    p2pModeValue = new JLabel("-");
    panel.add(p2pModeValue);

    panel.add(label("Known peers", "p2pKnownPeersLabel"));
    p2pPeersValue = new JLabel("-");
    panel.add(p2pPeersValue);

    panel.add(label("Vehicle peers", "p2pVehiclePeersLabel"));
    p2pVehiclePeersValue = new JLabel("-");
    panel.add(p2pVehiclePeersValue);

    panel.add(label("Pending requests", "p2pPendingLabel"));
    p2pPendingValue = new JLabel("-");
    panel.add(p2pPendingValue);

    panel.add(label("Overlay mode", "p2pOverlayModeLabel"));
    p2pOverlayModeValue = new JLabel("-");
    panel.add(p2pOverlayModeValue);

    panel.add(label("Overlay neighbors", "p2pOverlayNeighborsLabel"));
    p2pOverlayNeighborsValue = new JLabel("-");
    panel.add(p2pOverlayNeighborsValue);

    panel.add(label("Client RQS radius", "p2pRqsRadiusLabel"));
    p2pRqsRadiusValue = new JLabel("-");
    panel.add(p2pRqsRadiusValue);

    panel.add(label("RQS center", "p2pRqsCenterLabel"));
    p2pRqsCenterValue = new JLabel("-");
    panel.add(p2pRqsCenterValue);

    panel.add(label("Vehicle client strategy", "p2pVehicleStrategyLabel"));
    p2pVehicleStrategyValue = new JLabel("-");
    panel.add(p2pVehicleStrategyValue);

    panel.add(label("Client range filter", "p2pVehicleOutsideRadiusLabel"));
    p2pVehicleOutOfRangeValue = new JLabel("-");
    panel.add(p2pVehicleOutOfRangeValue);

    panel.add(label("Topology edges (veh)", "p2pTopologyEdgesLabel"));
    p2pTopologyEdgesValue = new JLabel("-");
    panel.add(p2pTopologyEdgesValue);

    panel.add(label("Shortcut edges (veh)", "p2pShortcutEdgesLabel"));
    p2pShortcutEdgesValue = new JLabel("-");
    panel.add(p2pShortcutEdgesValue);

    panel.add(label("Last event", "p2pLastEventLabel"));
    p2pLastEventValue = new JLabel("-");
    panel.add(p2pLastEventValue);

    return panel;
  }

  private JButton createP2PScanNowButton() {
    JButton button = new JButton("Scan now");
    button.setName("p2pScanNowButton");
    button.setToolTipText("Request an immediate topology scan from the P2P collector.");
    button.addActionListener(
        e -> {
          TaxiAlgorithm algorithm = simulation.getAlgorithm().get();
          if (algorithm instanceof P2PStatusProvider provider) {
            provider.requestP2PTopologyScan();
            refreshP2PStatus();
          }
        });
    return button;
  }

  private void applyModeToUi() {
    if (modeSwitchInProgress) {
      return;
    }

    modeSwitchInProgress = true;
    try {
      JComboBox<String> modeBox =
          getComponentByName("simulationModeBox") instanceof JComboBox<?> combo
              ? (JComboBox<String>) combo
              : null;
      if (modeBox == null) {
        return;
      }
      String selectedMode = Objects.toString(modeBox.getSelectedItem(), MODE_LOCAL);
      boolean p2pMode = isP2PModeSelected();
      boolean simulatedP2PMode = MODE_P2P_SIMULATED.equals(selectedMode);

      JComboBox<String> algorithmBox =
          getComponentByName("algorithmSelectionBox") instanceof JComboBox<?> combo
              ? (JComboBox<String>) combo
              : null;
      JButton algorithmSelectButton =
          getComponentByName("algorithmSelectionButton") instanceof JButton button ? button : null;
      if (algorithmBox != null && algorithmSelectButton != null) {
        if (p2pMode) {
          boolean changed = forceAlgorithmSelection(P2P_COLLECTOR_SIMPLE_NAME);
          if (changed) {
            generateParameters();
          }
        }
        algorithmBox.setEnabled(!p2pMode);
        algorithmSelectButton.setEnabled(!p2pMode);
      }

      Component taxiCountLabel = getComponentByName("taxiCountLabel");
      JComponent taxiCountSpinner =
          getComponentByName("taxiCount") instanceof JComponent component ? component : null;
      if (taxiCountLabel != null) {
        taxiCountLabel.setEnabled(!p2pMode || simulatedP2PMode);
      }
      if (taxiCountSpinner != null) {
        taxiCountSpinner.setEnabled(!p2pMode || simulatedP2PMode);
        taxiCountSpinner.setToolTipText(
            p2pMode && !simulatedP2PMode
                ? "In P2P-LAN mode, taxiCount is derived from discovered vehicle peers."
                : DEFAULT_TAXI_COUNT_TOOLTIP);
      }

      setComponentEnabled("taxiIncrement", !p2pMode || simulatedP2PMode);
      setComponentEnabled("taxiIncrementLabel", !p2pMode || simulatedP2PMode);

      algorithmParameterMap.put("p2pEmbeddedSimulation", simulatedP2PMode ? 1 : 0);

      if (p2pStatusPanel != null) {
        p2pStatusPanel.setVisible(p2pMode);
      }
      if (p2pTopologyPanel != null) {
        p2pTopologyPanel.setVisible(p2pMode);
      }
      if (p2pScanNowButton != null) {
        p2pScanNowButton.setVisible(p2pMode);
        p2pScanNowButton.setEnabled(p2pMode && simulation.getAlgorithm().get() instanceof P2PStatusProvider);
      }
      // Show algorithm parameters in every mode; P2P-specific fields are filtered in generateParameters().
      algorithmInputs.setVisible(true);

      worldInputs.setBorder(
          new TitledBorder(p2pMode ? "World Parameters (P2P Mode)" : "World Parameters"));
      algorithmInputs.revalidate();
      algorithmInputs.repaint();
      controls.revalidate();
      controls.repaint();
      updateP2PModeWarning(p2pMode);
    } finally {
      modeSwitchInProgress = false;
    }
  }

  private void updateP2PModeWarning(boolean p2pMode) {
    if (p2pModeWarningLabel == null) {
      return;
    }
    if (!p2pMode) {
      p2pModeWarningLabel.setVisible(false);
      p2pModeWarningLabel.setText(" ");
      p2pModeWarningLabel.setToolTipText(null);
      return;
    }

    if (!isAlgorithmAvailable(P2P_COLLECTOR_SIMPLE_NAME)) {
      p2pModeWarningLabel.setText(
          "Warning: " + P2P_COLLECTOR_SIMPLE_NAME + " was not found. P2P mode falls back to the current algorithm.");
      p2pModeWarningLabel.setToolTipText(
          "Check reflections scanning and module/classpath wiring for aqs-taxi-algorithm.");
      p2pModeWarningLabel.setVisible(true);
      return;
    }

    TaxiAlgorithm current = simulation.getAlgorithm().get();
    boolean collectorActive =
        current != null && P2P_COLLECTOR_SIMPLE_NAME.equals(current.getClass().getSimpleName());
    if (!collectorActive) {
      p2pModeWarningLabel.setText(
          "Warning: P2P mode is active, but the selected algorithm is not " + P2P_COLLECTOR_SIMPLE_NAME + ".");
      p2pModeWarningLabel.setToolTipText(
          "Select the P2P collector algorithm or switch back to LOCAL mode.");
      p2pModeWarningLabel.setVisible(true);
      return;
    }

    p2pModeWarningLabel.setVisible(false);
    p2pModeWarningLabel.setText(" ");
    p2pModeWarningLabel.setToolTipText(null);
  }

  private boolean isAlgorithmAvailable(String simpleClassName) {
    if (algorithmList == null) {
      return false;
    }
    return algorithmList.stream().anyMatch(clazz -> simpleClassName.equals(clazz.getSimpleName()));
  }

  private void refreshP2PStatus() {
    TaxiAlgorithm algorithm = simulation.getAlgorithm().get();
    if (algorithm instanceof P2PStatusProvider provider) {
      if (p2pScanNowButton != null) {
        p2pScanNowButton.setEnabled(true);
      }
      Map<String, String> status = provider.getP2PStatus();
      p2pModeValue.setText(status.getOrDefault("mode", "P2P"));
      p2pPeersValue.setText(status.getOrDefault("knownPeers", "0"));
      p2pVehiclePeersValue.setText(status.getOrDefault("vehiclePeers", "0"));
      p2pPendingValue.setText(status.getOrDefault("pendingRequests", "0"));
      p2pOverlayModeValue.setText(status.getOrDefault("overlayMode", "SMALL_WORLD"));
      p2pOverlayNeighborsValue.setText(
          status.getOrDefault("overlayMaxNeighbors", "3")
              + " (shortcuts="
              + status.getOrDefault("overlayShortcuts", "1")
              + ")");
      String rqsFixedRadius = status.getOrDefault("rqsFixedRadius", "-");
      p2pRqsRadiusValue.setText(rqsFixedRadius);
      p2pRqsCenterValue.setText(status.getOrDefault("rqsCenter", "client"));
      p2pVehicleStrategyValue.setText(status.getOrDefault("vehicleClientSelection", "nearest"));
      p2pVehicleOutOfRangeValue.setText(status.getOrDefault("clientRangeFilter", "off"));
      p2pLastEventValue.setText(status.getOrDefault("lastEvent", "-"));
      if (visualizationProperties != null) {
        Map<String, List<String>> taxiKnowledge = new LinkedHashMap<>();
        provider
            .getTaxiKnowledgeByClientIds()
            .forEach(
                (taxiId, clientIds) ->
                    taxiKnowledge.put(
                        taxiId,
                        clientIds == null
                            ? List.of()
                            : clientIds.stream()
                                .filter(id -> id != null && !id.isBlank())
                                .distinct()
                                .sorted()
                                .toList()));
        visualizationProperties.setTaxiKnownClientIds(taxiKnowledge);
      }
      if (p2pTopologyPanel != null) {
        P2PNetworkSnapshot topologySnapshot = provider.getP2PNetworkSnapshot();
        int rqsMinRadius = parseIntOrDefault(status.get("rqsFixedRadius"), 0);
        int[] edgeCounts = vehicleEdgeCounts(topologySnapshot);
        p2pTopologyEdgesValue.setText(String.valueOf(edgeCounts[0]));
        p2pShortcutEdgesValue.setText(String.valueOf(edgeCounts[1]));
        p2pTopologyPanel.setSnapshot(topologySnapshot);
        if (visualizationProperties != null) {
          visualizationProperties.setP2pNetworkSnapshot(topologySnapshot);
        }
        if (visualizationProperties != null && rqsMinRadius > 0) {
          visualizationProperties.setRqsRecognitionRadius(rqsMinRadius);
        }
      }
      return;
    }

    if (p2pScanNowButton != null) {
      p2pScanNowButton.setEnabled(false);
    }
    p2pModeValue.setText("LOCAL");
    p2pPeersValue.setText("-");
    p2pVehiclePeersValue.setText("-");
    p2pPendingValue.setText("-");
    p2pOverlayModeValue.setText("-");
    p2pOverlayNeighborsValue.setText("-");
    p2pRqsRadiusValue.setText("-");
    p2pRqsCenterValue.setText("-");
    p2pVehicleStrategyValue.setText("-");
    p2pVehicleOutOfRangeValue.setText("-");
    p2pTopologyEdgesValue.setText("-");
    p2pShortcutEdgesValue.setText("-");
    p2pLastEventValue.setText("-");
    if (visualizationProperties != null) {
      visualizationProperties.setTaxiKnownClientIds(Map.of());
      visualizationProperties.setP2pNetworkSnapshot(P2PNetworkSnapshot.empty());
    }
    if (p2pTopologyPanel != null) {
      p2pTopologyPanel.setSnapshot(P2PNetworkSnapshot.empty());
    }
  }

  private int parseIntOrDefault(String value, int defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  private int[] vehicleEdgeCounts(P2PNetworkSnapshot snapshot) {
    if (snapshot == null || snapshot.nodes() == null || snapshot.edges() == null) {
      return new int[] {0, 0};
    }

    Map<String, String> roleByNodeId = new HashMap<>();
    for (P2PNetworkNodeSnapshot node : snapshot.nodes()) {
      if (node == null || node.id() == null) {
        continue;
      }
      roleByNodeId.put(node.id(), node.role() == null ? "" : node.role().trim().toUpperCase(Locale.ROOT));
    }

    int vehicleEdges = 0;
    int shortcutEdges = 0;
    for (P2PNetworkEdgeSnapshot edge : snapshot.edges()) {
      if (edge == null) {
        continue;
      }
      if (!"VEHICLE".equals(roleByNodeId.getOrDefault(edge.fromNodeId(), ""))
          || !"VEHICLE".equals(roleByNodeId.getOrDefault(edge.toNodeId(), ""))) {
        continue;
      }
      vehicleEdges++;
      if (edge.shortcut()) {
        shortcutEdges++;
      }
    }
    return new int[] {vehicleEdges, shortcutEdges};
  }

  public JComponent getP2PTopologyComponent() {
    return p2pTopologyPanel;
  }

  public void setVisualizationProperties(VisualizationProperties visualizationProperties) {
    this.visualizationProperties = visualizationProperties;
  }

  private boolean forceAlgorithmSelection(String simpleClassName) {
    TaxiAlgorithm currentAlgorithm = simulation.getAlgorithm().get();
    if (currentAlgorithm != null
        && simpleClassName.equals(currentAlgorithm.getClass().getSimpleName())) {
      return false;
    }

    String selectedAlgorithm = "";
    for (Class<?> object : algorithmList) {
      if (object.getSimpleName().equals(simpleClassName)) {
        selectedAlgorithm = object.getName();
        break;
      }
    }
    if (selectedAlgorithm.isEmpty()) {
      log.warn("Could not force algorithm selection: {} not found in algorithm list.", simpleClassName);
      return false;
    }
    simulation.getAlgorithm().setAlgorithm(instantiateAlgorithm(selectedAlgorithm, algorithmParameterMap));

    JComboBox<String> algorithmBox =
        getComponentByName("algorithmSelectionBox") instanceof JComboBox<?> combo
            ? (JComboBox<String>) combo
            : null;
    if (algorithmBox != null) {
      algorithmBox.setSelectedItem(simpleClassName);
    }
    updateP2PModeWarning(isP2PModeSelected());
    return true;
  }

  private boolean isP2PModeSelected() {
    JComboBox<String> modeBox =
        getComponentByName("simulationModeBox") instanceof JComboBox<?> combo
            ? (JComboBox<String>) combo
            : null;
    if (modeBox == null) {
      return false;
    }
    String selected = Objects.toString(modeBox.getSelectedItem(), MODE_LOCAL);
    return MODE_P2P_SIMULATED.equals(selected) || MODE_P2P_LAN.equals(selected);
  }

  private boolean isP2PAlgorithm(TaxiAlgorithm algorithm) {
    return algorithm != null && algorithm.getClass().getSimpleName().contains("P2P");
  }

  private JButton algorithmSelectionButton() {
    JButton button = new JButton("Select");
    button.setName("algorithmSelectionButton");
    button.addActionListener(
        e -> {
          JComboBox<String> selection =
              (JComboBox<String>) getComponentByName("algorithmSelectionBox");
          String selectedItem;

          if (selection != null) {
            selectedItem = Objects.requireNonNull(selection.getSelectedItem()).toString();
            String selectedAlgorithm = "";

            for (Class<?> object : algorithmList) {
              if (object.getSimpleName().matches(selectedItem)) {
                selectedAlgorithm = object.getName();
              }
            }

            simulation
                .getAlgorithm()
                .setAlgorithm(instantiateAlgorithm(selectedAlgorithm, algorithmParameterMap));
            JComboBox<String> modeBox = (JComboBox<String>) getComponentByName("simulationModeBox");
            if (modeBox != null) {
              modeBox.setSelectedItem(
                  isP2PAlgorithm(simulation.getAlgorithm().get()) ? MODE_P2P_SIMULATED : MODE_LOCAL);
            }
          }
          generateParameters();
          applyModeToUi();
          updateP2PModeWarning(isP2PModeSelected());
        });
    generateParameters();
    return button;
  }

  private JButton startButton() {
    JButton button = new JButton("Start");
    button.setName("startButton");
    button.setEnabled(false);
    button.addActionListener(
        e -> {
          button.setEnabled(false);
          Objects.requireNonNull(getComponentByName("initializeSimulationButton")).setEnabled(true);
          Objects.requireNonNull(getComponentByName("stopButton")).setEnabled(true);

          int batchCount = (int) ((JSpinner) getComponentByName("batchCount")).getValue();
          if (batchCount == 1) {
            simulation.start();
          } else {
            new Thread(
                    () -> {
                      for (int i = 0; i < batchCount; i++) {
                        simulation.start();
                        do {} while (!simulation.isSimulationFinished());
                        int newTaxiCount =
                            ((int) ((JSpinner) getComponentByName("taxiCount")).getValue())
                                + ((int)
                                    ((JSpinner) getComponentByName("taxiIncrement")).getValue());
                        ((JSpinner) getComponentByName("taxiCount")).setValue(newTaxiCount);
                        int newClientCount =
                            ((int) ((JSpinner) getComponentByName("clientCount")).getValue())
                                + ((int)
                                    ((JSpinner) getComponentByName("clientIncrement")).getValue());
                        ((JSpinner) getComponentByName("clientCount")).setValue(newClientCount);
                        int newTaxiSeatCount =
                            ((int) ((JSpinner) getComponentByName("taxiSeatCount")).getValue())
                                + ((int)
                                    ((JSpinner) getComponentByName("taxiSeatIncrement"))
                                        .getValue());
                        ((JSpinner) getComponentByName("taxiSeatCount")).setValue(newTaxiSeatCount);
                        initializeSimulation();
                      }
                    })
                .start();
          }
        });
    return button;
  }

  private JButton stopButton() {
    JButton button = new JButton("Stop");
    button.setName("stopButton");
    button.setEnabled(false);
    button.addActionListener(
        e -> {
          simulation.stop();
          Objects.requireNonNull(getComponentByName("startButton")).setEnabled(true);
        });
    return button;
  }

  private JButton copyConfigButton() {
    JButton button = new JButton("Copy Config");
    button.setName("copyConfigButton");
    button.addActionListener(e -> copyConfigToClipboard());
    return button;
  }

  private JButton pasteConfigButton() {
    JButton button = new JButton("Paste Config");
    button.setName("pasteConfigButton");
    button.addActionListener(e -> pasteConfigFromClipboard());
    return button;
  }

  private JButton showResultsButton() {
    JButton button = new JButton("Show Results");
    button.setName("showResultsButton");
    button.addActionListener(e -> simulation.showResultVisualization());
    return button;
  }

  @SuppressWarnings(value = "BusyWait")
  private JButton initializeSimulationButton() {
    JButton button = new JButton("Initialize");
    button.setName("initializeSimulationButton");
    button.addActionListener(e -> initializeSimulation());
    return button;
  }

  private JSlider simulationSpeed() {
    JSlider slider = new JSlider();
    slider.setName("simulationSpeedSlider");
    slider.setToolTipText("Speed of simulation");
    slider.setMaximum(100);
    slider.setMinimum(1);
    slider.setValue(simulation.getSpeed());
    slider.addChangeListener(
        e -> {
          JSlider source = (JSlider) e.getSource();
          if (!source.getValueIsAdjusting()) {
            int value = source.getValue();
            simulation.setSpeed(value);
          }
        });
    return slider;
  }

  private JSpinner clientCountSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(100, 1, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    configureIntegerSpinner(spinner);
    spinner.setName("clientCount");
    spinner.setToolTipText("Set the Count of Clients for the Simulation");
    return spinner;
  }

  private JSpinner clientSpawnWindowSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(100_00, 1, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    configureIntegerSpinner(spinner);
    spinner.setName("clientSpawnWindow");
    spinner.setToolTipText("Set the spawn time window in which clients can randomly spawn");
    return spinner;
  }

  private JSpinner taxiSeatCountSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(2, 1, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    configureIntegerSpinner(spinner);
    spinner.setName("taxiSeatCount");
    spinner.setToolTipText("Set the Count of seats in the Taxi");
    return spinner;
  }

  private JSpinner clientSpeedSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(5, 0, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    configureIntegerSpinner(spinner);
    spinner.setName("clientSpeed");
    spinner.setToolTipText("Set the initial Speed of a Client");
    return spinner;
  }

  private JSpinner taxiSpeedSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(80, 1, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    configureIntegerSpinner(spinner);
    spinner.setName("taxiSpeed");
    spinner.setToolTipText("Set the initial Speed of the Taxi");
    return spinner;
  }

  private void configureIntegerSpinner(JSpinner spinner) {
    JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner);
    NumberFormat format = editor.getFormat();
    format.setGroupingUsed(false);
    spinner.setEditor(editor);
  }

  private void createComponentMap() {
    componentMap = new LinkedHashMap<>();
    List<Component> components = new ArrayList<>();
    Collections.addAll(components, controls.getComponents());
    Collections.addAll(components, buttons.getComponents());
    Collections.addAll(components, worldInputs.getComponents());
    Collections.addAll(components, selection.getComponents());
    Collections.addAll(components, batchProcessing.getComponents());
    for (Component component : components) {
      componentMap.put(component.getName(), component);
    }
  }

  private Component getComponentByName(String componentName) {
    if (componentMap == null) {
      return null;
    }
    if (componentMap.containsKey(componentName)) {
      return componentMap.get(componentName);
    }
    return null;
  }

  private static TaxiAlgorithm instantiateAlgorithm(
      String algorithmName, Map<String, Integer> parameters) {
    try {
      Class<?> algorithmClassName = Class.forName(algorithmName);
      TaxiAlgorithm algorithm =
          (TaxiAlgorithm) algorithmClassName.getDeclaredConstructor().newInstance();
      algorithm.setParameters(parameters);
      return algorithm;
    } catch (ClassNotFoundException
        | InvocationTargetException
        | InstantiationException
        | IllegalAccessException
        | NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  private void generateParameters() {
    Set<AlgorithmParameter> parameters = simulation.getSimulationParameters().getParameters();
    if (parameters.isEmpty()) {
      algorithmInputs.setBorder(null);
    } else {
      algorithmInputs.setBorder(new TitledBorder("Algorithm Parameters"));
    }
    algorithmInputs.removeAll();
    if (!algorithmParameterMap.isEmpty()) {
      algorithmParameterMap.clear();
    }
    inputParameterMap = new HashMap<>();
    inputParameterMap.put("taxiCount", 0);
    inputParameterMap.put("clientCount", 0);
    boolean multicastFieldAdded = false;
    int rowCount = 0;
    for (AlgorithmParameter parameter : parameters) {
      if (!shouldShowAlgorithmParameter(parameter.name())) {
        continue;
      }
      if (isP2PMulticastOctet(parameter.name())) {
        if (multicastFieldAdded) {
          continue;
        }
        JLabel label = new JLabel("p2pMulticastGroup");
        label.setName(P2P_MULTICAST_GROUP_FIELD + "Label");
        algorithmInputs.add(label);
        JTextField textField = new JTextField(buildMulticastGroupFromCurrentParameters());
        textField.setName(P2P_MULTICAST_GROUP_FIELD);
        textField.setToolTipText("IPv4 multicast group, z. B. 239.255.42.99");
        algorithmInputs.add(textField);
        inputParameterMap.put("p2pMulticastA", 239);
        inputParameterMap.put("p2pMulticastB", 255);
        inputParameterMap.put("p2pMulticastC", 42);
        inputParameterMap.put("p2pMulticastD", 99);
        multicastFieldAdded = true;
        rowCount++;
        continue;
      }

      if (isP2PPortField(parameter.name())) {
        JLabel label = new JLabel(displayLabelForParameter(parameter.name()));
        label.setName(parameter.name() + "Label");
        algorithmInputs.add(label);
        int defaultValue = parameter.defaultValue() != null ? parameter.defaultValue() : 1;
        JTextField textField = new JTextField(String.valueOf(defaultValue));
        textField.setName(parameter.name());
        textField.setToolTipText(parameterTooltip(parameter.name()));
        algorithmInputs.add(textField);
        inputParameterMap.put(parameter.name(), 0);
        rowCount++;
        continue;
      }

      JLabel label = new JLabel(displayLabelForParameter(parameter.name()));
      label.setName(parameter.name() + "Label");
      algorithmInputs.add(label);
      int defaultValue = parameter.defaultValue() != null ? parameter.defaultValue() : 1;
      SpinnerModel model = new SpinnerNumberModel(defaultValue, 0, Integer.MAX_VALUE, 1);
      JSpinner spinner = new JSpinner(model);
      configureIntegerSpinner(spinner);
      label.setLabelFor(spinner);
      spinner.setName(parameter.name());
      spinner.setToolTipText(parameterTooltip(parameter.name()));
      algorithmInputs.add(spinner);
      inputParameterMap.put(parameter.name(), 0);
      rowCount++;
    }
    algorithmInputs.setLayout(new GridLayout(Math.max(1, rowCount), 2, GAP, GAP));
    SwingUtilities.updateComponentTreeUI(worldInputs);
    applyModeToUi();
  }

  private boolean shouldShowAlgorithmParameter(String parameterName) {
    if ("p2pEmbeddedSimulation".equals(parameterName)) {
      return false;
    }
    if (!isP2PModeSelected()) {
      return true;
    }
    if (P2P_ADVANCED_HIDDEN_PARAMETERS.contains(parameterName)) {
      return false;
    }
    if (!P2P_CORE_PARAMETERS.contains(parameterName)
        && !isP2PPortField(parameterName)
        && !isP2PMulticastOctet(parameterName)
        && !"p2pDiscoveryWaitMs".equals(parameterName)) {
      return false;
    }
    String selectedMode =
        getComponentByName("simulationModeBox") instanceof JComboBox<?> combo
            ? Objects.toString(combo.getSelectedItem(), MODE_LOCAL)
            : MODE_LOCAL;
    if (MODE_P2P_SIMULATED.equals(selectedMode)) {
      return !isP2PPortField(parameterName)
          && !isP2PMulticastOctet(parameterName)
          && !"p2pDiscoveryWaitMs".equals(parameterName);
    }
    return true;
  }

  private void initializeSimulation() {
    simulation.stop();
    JButton startButton = (JButton) getComponentByName("startButton");
    try {
      updateP2PModeWarning(isP2PModeSelected());
      if (isP2PModeSelected() && p2pModeWarningLabel != null && p2pModeWarningLabel.isVisible()) {
        JOptionPane.showMessageDialog(
            this,
            p2pModeWarningLabel.getText(),
            "Inconsistent P2P configuration",
            JOptionPane.WARNING_MESSAGE);
      }

      allParameterMap.clear();
      algorithmParameterMap.clear();
      inputParameterMap = new HashMap<>();
      VisualizationUtils.resetTaxiColors();

      for (Component component : worldInputs.getComponents()) {
        if (component instanceof JTextField textField) {
          allParameterMap.put(textField.getName(), Integer.parseInt(textField.getText()));
        }
        if (component instanceof JSpinner spinner) {
          allParameterMap.put(spinner.getName(), (int) spinner.getValue());
        }
      }

      for (Component component : algorithmInputs.getComponents()) {
        if (component instanceof JSpinner spinner) {
          allParameterMap.put(spinner.getName(), (int) spinner.getValue());
          algorithmParameterMap.put(spinner.getName(), (int) spinner.getValue());
        }
        if (component instanceof JTextField textField && isP2PPortField(textField.getName())) {
          int port = parsePort(textField.getName(), textField.getText());
          allParameterMap.put(textField.getName(), port);
          algorithmParameterMap.put(textField.getName(), port);
        }
        if (component instanceof JTextField textField
            && P2P_MULTICAST_GROUP_FIELD.equals(textField.getName())) {
          int[] octets = parseMulticastGroup(textField.getText());
          allParameterMap.put("p2pMulticastA", octets[0]);
          allParameterMap.put("p2pMulticastB", octets[1]);
          allParameterMap.put("p2pMulticastC", octets[2]);
          allParameterMap.put("p2pMulticastD", octets[3]);
          algorithmParameterMap.put("p2pMulticastA", octets[0]);
          algorithmParameterMap.put("p2pMulticastB", octets[1]);
          algorithmParameterMap.put("p2pMulticastC", octets[2]);
          algorithmParameterMap.put("p2pMulticastD", octets[3]);
        }
      }

      if (isP2PModeSelected()) {
        String selectedMode =
            getComponentByName("simulationModeBox") instanceof JComboBox<?> combo
                ? Objects.toString(combo.getSelectedItem(), MODE_LOCAL)
                : MODE_LOCAL;
        int embeddedValue = MODE_P2P_SIMULATED.equals(selectedMode) ? 1 : 0;
        allParameterMap.put("p2pEmbeddedSimulation", embeddedValue);
        algorithmParameterMap.put("p2pEmbeddedSimulation", embeddedValue);
      }

      inputParameterMap.putAll(allParameterMap);

      startButton.setEnabled(true);

      simulation.init(inputParameterMap);
    } catch (Exception exception) {
      log.error(exception.getMessage(), exception);
      JOptionPane.showMessageDialog(this, "No valid input parameters provided!");
    }
  }

  private void setComponentEnabled(String componentName, boolean enabled) {
    Component component = getComponentByName(componentName);
    if (component != null) {
      component.setEnabled(enabled);
    }
  }


  private boolean isP2PMulticastOctet(String parameterName) {
    return "p2pMulticastA".equals(parameterName)
        || "p2pMulticastB".equals(parameterName)
        || "p2pMulticastC".equals(parameterName)
        || "p2pMulticastD".equals(parameterName);
  }

  private boolean isP2PPortField(String parameterName) {
    return P2P_PORT_FIELDS.contains(parameterName);
  }

  private String buildMulticastGroupFromCurrentParameters() {
    int a = algorithmParameterMap.getOrDefault("p2pMulticastA", 239);
    int b = algorithmParameterMap.getOrDefault("p2pMulticastB", 255);
    int c = algorithmParameterMap.getOrDefault("p2pMulticastC", 42);
    int d = algorithmParameterMap.getOrDefault("p2pMulticastD", 99);
    return a + "." + b + "." + c + "." + d;
  }

  private int[] parseMulticastGroup(String value) {
    String trimmed = value == null ? "" : value.trim();
    String[] parts = trimmed.split("\\.");
    if (parts.length != 4) {
      throw new IllegalArgumentException("Invalid multicast group. Expected IPv4 format a.b.c.d");
    }
    int[] octets = new int[4];
    for (int i = 0; i < 4; i++) {
      int octet = Integer.parseInt(parts[i].trim());
      if (octet < 0 || octet > 255) {
        throw new IllegalArgumentException("Multicast octet out of range: " + octet);
      }
      octets[i] = octet;
    }
    return octets;
  }

  private int parsePort(String fieldName, String value) {
    try {
      int port = Integer.parseInt(value == null ? "" : value.trim());
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("Port out of range for " + fieldName + ": " + port);
      }
      return port;
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("Invalid numeric value for " + fieldName + ": " + value);
    }
  }

  private String displayLabelForParameter(String parameterName) {
    return switch (parameterName) {
      case "p2pFixedSearchRadius" -> "Client RQS radius";
      case "p2pRqsRouteProximityMode" -> "RQS mode (0=taxi-position,1=taxi-route)";
      case "CalculateFullTaxis" -> "Include full taxis";
      case "p2pOverlayMaxNeighbors" -> "Overlay degree k (max neighbors)";
      case "p2pOverlayShortcuts" -> "Overlay shortcuts";
      case "p2pRequestForwardHops" -> "Flood TTL (Hops)";
      case "p2pRequestRepublishTicks" -> "Republish throttle [ticks]";
      case "p2pOfferCollectionTicks" -> "Offer collection window [ticks]";
      case "p2pTopologyScanTicks" -> "Topology scan interval [ticks]";
      case "p2pVehicleCommitLeaseTicks" -> "Vehicle commit lease [ticks]";
      case "p2pVehicleRebidIntervalTicks" -> "Vehicle rebid interval [ticks]";
      case "p2pVehicleRequestCacheTtlTicks" -> "Vehicle request cache TTL [ticks]";
      case "p2pDiscoveryWaitMs" -> "Discovery wait [ms]";
      case "p2pVehicleOpenClientStrategy" -> "Vehicle client strategy (0=greedy,1=nearest)";
      default -> parameterName;
    };
  }

  private String parameterTooltip(String parameterName) {
    return switch (parameterName) {
      case "p2pFixedSearchRadius" ->
          "Fixed radius around the client used for RQS seeding (world units), independent from trip distance.";
      case "p2pRqsRouteProximityMode" ->
          "RQS matching mode: 0 = strict taxi position only, 1 = legacy route proximity for moving taxis.";
      case "CalculateFullTaxis" ->
          "0 = only free taxis, 1 = include full/active taxis in RQS candidate search.";
      case "p2pOverlayMaxNeighbors" ->
          "Maximum overlay neighbors per node (k in the small-world graph).";
      case "p2pOverlayShortcuts" ->
          "Number of additional small-world shortcut links per node.";
      case "p2pRequestForwardHops" ->
          "TTL for flooding request forwarding in hops.";
      case "p2pRequestRepublishTicks" ->
          "Minimum simulation ticks before an unaccepted client request is republished.";
      case "p2pOfferCollectionTicks" ->
          "How many simulation ticks offers are collected before accepting the best one.";
      case "p2pTopologyScanTicks" ->
          "How many simulation ticks between automatic topology scans.";
      case "p2pVehicleCommitLeaseTicks" ->
          "How many simulation ticks a vehicle stays reserved after commit acceptance.";
      case "p2pVehicleRebidIntervalTicks" ->
          "Minimum simulation ticks between repeated offers for the same request.";
      case "p2pVehicleRequestCacheTtlTicks" ->
          "How many simulation ticks a vehicle keeps an unassigned request in cache.";
      case "p2pDiscoveryWaitMs" ->
          "LAN mode only: waiting time for peer discovery during init.";
      case "p2pVehicleOpenClientStrategy" ->
          "Taxi-side decision among open clients: 0 = greedy (oldest request first), 1 = nearest (shortest taxi->client distance).";
      default -> isP2PPortField(parameterName) ? "Port (1-65535)" : parameterName;
    };
  }

  private void copyConfigToClipboard() {
    Properties props = new Properties();

    // Algorithm Selection
    JComboBox<String> algoComboBox =
        (JComboBox<String>) getComponentByName("algorithmSelectionBox");
    if (algoComboBox != null) {
      props.setProperty("algorithmSelectionBox", (String) algoComboBox.getSelectedItem());
    }

    // World Parameters
    for (Component comp : worldInputs.getComponents()) {
      if (comp instanceof JSpinner spinner && comp.getName() != null) {
        props.setProperty(spinner.getName(), spinner.getValue().toString());
      }
      if (comp instanceof JSlider slider && comp.getName() != null) {
        props.setProperty(slider.getName(), String.valueOf(slider.getValue()));
      }
    }

    // dynamic Algorithm Parameters
    for (Component comp : algorithmInputs.getComponents()) {
      if (comp instanceof JSpinner spinner && comp.getName() != null) {
        props.setProperty(spinner.getName(), spinner.getValue().toString());
      }
      if (comp instanceof JTextField textField && comp.getName() != null) {
        props.setProperty(textField.getName(), textField.getText());
      }
    }

    // Batch Processing Parameters
    for (Component comp : batchProcessing.getComponents()) {
      if (comp instanceof JSpinner spinner && comp.getName() != null) {
        props.setProperty(spinner.getName(), spinner.getValue().toString());
      }
    }

    try (StringWriter writer = new StringWriter()) {
      props.store(writer, "AQS Simulation Configuration");
      String configString = writer.toString();

      StringSelection stringSelection = new StringSelection(configString);
      Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
      clipboard.setContents(stringSelection, null);
      JOptionPane.showMessageDialog(this, "Configuration copied to clipboard!");
      log.debug("Configuration copied to clipboard:\n{}", configString);

    } catch (IOException ex) {
      log.error("Error writing configuration to string", ex);
      JOptionPane.showMessageDialog(
          this,
          "Error copying configuration: " + ex.getMessage(),
          "Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private void pasteConfigFromClipboard() {
    try {
      Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
      String configString = (String) clipboard.getData(DataFlavor.stringFlavor);

      if (configString == null || configString.trim().isEmpty()) {
        JOptionPane.showMessageDialog(
            this,
            "Clipboard is empty or contains no text.",
            "Info",
            JOptionPane.INFORMATION_MESSAGE);
        return;
      }

      Properties props = new Properties();
      try (StringReader reader = new StringReader(configString)) {
        props.load(reader);
      }

      String algoNameFromProps = props.getProperty("algorithmSelectionBox");
      boolean algorithmChanged = false;

      if (algoNameFromProps != null) {
        JComboBox<String> algoComboBox =
            (JComboBox<String>) getComponentByName("algorithmSelectionBox");
        if (algoComboBox != null) {
          if (!algoNameFromProps.equals(algoComboBox.getSelectedItem())) {
            String selectedAlgorithmFullName = "";
            for (Class<?> algoClass : algorithmList) {
              if (algoClass.getSimpleName().equals(algoNameFromProps)) {
                selectedAlgorithmFullName = algoClass.getName();
                break;
              }
            }
            if (!selectedAlgorithmFullName.isEmpty()) {
              // instantiate the algorithm with empty parameters
              simulation
                  .getAlgorithm()
                  .setAlgorithm(instantiateAlgorithm(selectedAlgorithmFullName, new HashMap<>()));
              algoComboBox.setSelectedItem(algoNameFromProps);
              generateParameters();
              log.info("Algorithm set to {} and parameters regenerated.", algoNameFromProps);
              algorithmChanged = true;
            } else {
              log.warn(
                  "Pasted algorithm name '{}' not found in available algorithms.",
                  algoNameFromProps);
            }
          } else {
            generateParameters();
            algorithmChanged = true;
          }
        }
      }

      // generate Parameters
      if (!algorithmChanged) {
        generateParameters();
      }

      for (String name : props.stringPropertyNames()) {
        if (name.equals("algorithmSelectionBox")) continue; // already handled above

        String valueStr = props.getProperty(name);
        boolean valueSet = false;

        // 1. try: set algorithm parameters
        if (algorithmInputs != null) {
          for (Component compInAlgoPanel : algorithmInputs.getComponents()) {
            if (compInAlgoPanel instanceof JSpinner && name.equals(compInAlgoPanel.getName())) {
              try {
                ((JSpinner) compInAlgoPanel).setValue(Integer.parseInt(valueStr));
                log.trace("Set ALGORITHM JSpinner '{}' to '{}'", name, valueStr);
                valueSet = true;
                break;
              } catch (NumberFormatException nfe) {
                log.warn(
                    "Could not parse '{}' as integer for ALGORITHM spinner '{}'", valueStr, name);
              }
            }
            if (compInAlgoPanel instanceof JTextField textField
                && name.equals(compInAlgoPanel.getName())) {
              textField.setText(valueStr);
              valueSet = true;
              break;
            }
            if (compInAlgoPanel instanceof JSlider && name.equals(compInAlgoPanel.getName())) {
              try {
                ((JSlider) compInAlgoPanel).setValue(Integer.parseInt(valueStr));
                log.trace("Set ALGORITHM JSlider '{}' to '{}'", name, valueStr);
                valueSet = true;
                break;
              } catch (NumberFormatException nfe) {
                log.warn(
                    "Could not parse '{}' as integer for ALGORITHM slider '{}'", valueStr, name);
              }
            }
          }
        }

        // 2. try: set global parameters
        if (!valueSet) {
          Component generalComp = getComponentByName(name);
          if (generalComp instanceof JSpinner) {
            try {
              ((JSpinner) generalComp).setValue(Integer.parseInt(valueStr));
              log.trace("Set GENERAL JSpinner '{}' to '{}'", name, valueStr);
              valueSet = true;
            } catch (NumberFormatException nfe) {
              log.warn("Could not parse '{}' as integer for GENERAL spinner '{}'", valueStr, name);
            }
          }
          if (generalComp instanceof JSlider) {
            try {
              ((JSlider) generalComp).setValue(Integer.parseInt(valueStr));
              log.trace("Set GENERAL JSlider '{}' to '{}'", name, valueStr);
              valueSet = true;
            } catch (NumberFormatException nfe) {
              log.warn("Could not parse '{}' as integer for GENERAL slider '{}'", valueStr, name);
            }
          }
        }

        if (!valueSet) {
          log.warn(
              "Pasted parameter '{}' with value '{}' could not be applied to any known JSpinner.",
              name,
              valueStr);
        }
      }

      // refresh the UI to show the new values
      SwingUtilities.updateComponentTreeUI(this);
      JOptionPane.showMessageDialog(this, "Configuration pasted from clipboard!");

    } catch (UnsupportedFlavorException | IOException ex) {
      log.error("Error pasting configuration from clipboard", ex);
      JOptionPane.showMessageDialog(
          this,
          "Error pasting configuration: " + ex.getMessage(),
          "Error",
          JOptionPane.ERROR_MESSAGE);
    } catch (Exception ex) { // Catch-all for other issues like parsing or component finding
      log.error("Generic error during paste operation", ex);
      JOptionPane.showMessageDialog(
          this,
          "Error applying pasted configuration: " + ex.getMessage(),
          "Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private static class P2PTopologyPanel extends JPanel {
    private P2PNetworkSnapshot snapshot = P2PNetworkSnapshot.empty();
    private double zoom = 0.82;
    private double panX;
    private double panY;
    private Point lastDragPoint;
    private String draggedNodeId;
    private final Map<String, Point2D.Double> manualNodeOffsets = new HashMap<>();
    private final Map<String, Point> renderedNodePositions = new HashMap<>();

    private P2PTopologyPanel() {
      setBackground(Color.WHITE);
      setMinimumSize(new Dimension(420, 230));

      MouseAdapter interaction =
          new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
              lastDragPoint = e.getPoint();
              draggedNodeId = findNodeAt(e.getPoint());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
              lastDragPoint = null;
              draggedNodeId = null;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
              if (lastDragPoint == null) {
                return;
              }
              if (draggedNodeId != null) {
                Point center = panelCenter();
                manualNodeOffsets.put(draggedNodeId, toWorldOffset(e.getPoint(), center));
              } else if (SwingUtilities.isRightMouseButton(e) || e.isShiftDown()) {
                panX += e.getX() - lastDragPoint.getX();
                panY += e.getY() - lastDragPoint.getY();
              }
              lastDragPoint = e.getPoint();
              repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
              if (e.getClickCount() == 2) {
                String clickedNodeId = findNodeAt(e.getPoint());
                if (clickedNodeId != null) {
                  manualNodeOffsets.remove(clickedNodeId);
                } else {
                  zoom = 0.82;
                  panX = 0;
                  panY = 0;
                  manualNodeOffsets.clear();
                }
                repaint();
              }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
              Point center = panelCenter();
              Point2D.Double before = toWorldOffset(e.getPoint(), center);
              double factor = e.getWheelRotation() < 0 ? 1.12 : 0.89;
              zoom = Math.max(0.5, Math.min(3.5, zoom * factor));
              Point2D.Double after = toWorldOffset(e.getPoint(), center);
              panX += (after.x - before.x) * zoom;
              panY += (after.y - before.y) * zoom;
              repaint();
            }
          };
      addMouseListener(interaction);
      addMouseMotionListener(interaction);
      addMouseWheelListener(interaction);
    }

    private void setSnapshot(P2PNetworkSnapshot snapshot) {
      this.snapshot = snapshot == null ? P2PNetworkSnapshot.empty() : snapshot;
      Set<String> aliveIds =
          this.snapshot.nodes().stream().map(P2PNetworkNodeSnapshot::id).collect(java.util.stream.Collectors.toSet());
      manualNodeOffsets.keySet().removeIf(id -> !aliveIds.contains(id));
      repaint();
    }

    private Point panelCenter() {
      return new Point(
          getWidth() / 2 + (int) Math.round(panX),
          getHeight() / 2 + (int) Math.round(panY));
    }

    private Point2D.Double toWorldOffset(Point point, Point center) {
      double safeZoom = Math.max(0.0001, zoom);
      return new Point2D.Double((point.x - center.x) / safeZoom, (point.y - center.y) / safeZoom);
    }

    private Point toScreen(Point2D.Double offset, Point center) {
      int x = center.x + (int) Math.round(offset.x * zoom);
      int y = center.y + (int) Math.round(offset.y * zoom);
      return new Point(x, y);
    }

    private String findNodeAt(Point point) {
      for (Map.Entry<String, Point> entry : renderedNodePositions.entrySet()) {
        Point p = entry.getValue();
        if (p == null) {
          continue;
        }
        if (point.distance(p) <= 22) {
          return entry.getKey();
        }
      }
      return null;
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        List<P2PNetworkNodeSnapshot> nodes = snapshot.nodes();
        if (nodes == null || nodes.isEmpty()) {
          g2.setColor(Color.GRAY);
          g2.drawString("No P2P nodes discovered yet.", 16, 24);
          return;
        }

        int width = getWidth();
        int height = getHeight();
        int centerX = width / 2 + (int) Math.round(panX);
        int centerY = height / 2 + (int) Math.round(panY);
        Point center = new Point(centerX, centerY);
        int radius = Math.max(60, Math.min(width, height) / 2 - 85);

        P2PNetworkNodeSnapshot local =
            nodes.stream().filter(P2PNetworkNodeSnapshot::localNode).findFirst().orElse(nodes.getFirst());
        List<P2PNetworkNodeSnapshot> peers =
            nodes.stream()
                .filter(node -> !node.localNode())
                .sorted(
                    Comparator.comparing(P2PNetworkNodeSnapshot::role)
                        .thenComparing(P2PNetworkNodeSnapshot::id))
                .toList();

        renderedNodePositions.clear();
        renderedNodePositions.put(local.id(), new Point(centerX, centerY));

        for (int i = 0; i < peers.size(); i++) {
          P2PNetworkNodeSnapshot peer = peers.get(i);
          Point2D.Double autoOffset = calculateAutoOffset(radius, i, peers.size());
          Point2D.Double offset = manualNodeOffsets.getOrDefault(peer.id(), autoOffset);
          Point position = toScreen(offset, center);
          renderedNodePositions.put(peer.id(), position);
        }

        List<P2PNetworkEdgeSnapshot> edges = snapshot.edges();
        if (edges != null && !edges.isEmpty()) {
          drawEdges(g2, edges);
        }

        for (P2PNetworkNodeSnapshot peer : peers) {
          Point point = renderedNodePositions.get(peer.id());
          if (point == null) {
            continue;
          }
          drawNode(g2, peer, point.x, point.y);
        }

        drawNode(g2, local, centerX, centerY);
        drawLegendAndCounts(g2, nodes);
        drawViewportHint(g2);
      } finally {
        g2.dispose();
      }
    }

    private Point2D.Double calculateAutoOffset(int baseRadius, int index, int totalPeers) {
      int safeTotal = Math.max(1, totalPeers);
      int ringLevel = index / 12;
      int ringStart = ringLevel * 12;
      int peersInRing = Math.min(12, safeTotal - ringStart);
      int indexInRing = index - ringStart;

      double angle = (2 * Math.PI * indexInRing) / Math.max(1, peersInRing);
      double ringRadius = baseRadius + ringLevel * 55.0;

      double x = ringRadius * Math.cos(angle);
      double y = ringRadius * Math.sin(angle);
      return new Point2D.Double(x, y);
    }

    private void drawLegendAndCounts(Graphics2D g2, List<P2PNetworkNodeSnapshot> nodes) {
      long vehicleCount = nodes.stream().filter(n -> "VEHICLE".equals(n.role())).count();
      long clientCount = nodes.stream().filter(n -> "CLIENT".equals(n.role())).count();

      int x = 12;
      int y = 34;
      g2.setColor(new Color(250, 250, 250, 235));
      g2.fillRoundRect(x - 10, y - 18, 270, 98, 12, 12);
      g2.setColor(new Color(205, 205, 205));
      g2.drawRoundRect(x - 10, y - 18, 270, 98, 12, 12);

      drawLegendEntry(g2, x, y, colorForRole("LOCAL", true), "Collector/local");
      drawLegendEntry(g2, x, y + 18, colorForRole("VEHICLE", false), "Vehicle peers");
      drawLegendEntry(g2, x, y + 36, colorForRole("CLIENT", false), "Client peers");

      g2.setColor(Color.DARK_GRAY);
      g2.drawString("Counts: VEHICLE=" + vehicleCount + " CLIENT=" + clientCount, x, y + 58);
    }

    private void drawEdges(Graphics2D g2, List<P2PNetworkEdgeSnapshot> edges) {
      for (P2PNetworkEdgeSnapshot edge : edges) {
        if (edge == null) {
          continue;
        }
        Point from = renderedNodePositions.get(edge.fromNodeId());
        Point to = renderedNodePositions.get(edge.toNodeId());
        if (from == null || to == null) {
          continue;
        }
        drawEdge(g2, edge, from.x, from.y, to.x, to.y);
      }
    }

    private void drawLegendEntry(Graphics2D g2, int x, int y, Color color, String text) {
      g2.setColor(color);
      g2.fillRect(x, y - 10, 10, 10);
      g2.setColor(Color.DARK_GRAY);
      g2.drawRect(x, y - 10, 10, 10);
      g2.drawString(text, x + 16, y);
    }

    private void drawViewportHint(Graphics2D g2) {
      g2.setColor(new Color(90, 90, 90));
      g2.drawString(
          "Wheel=zoom, Shift/right-drag=pan, left-drag=node, double-click=node reset (zoom="
              + String.format(java.util.Locale.ROOT, "%.2f", zoom)
              + ")",
          12,
          getHeight() - 12);
    }

    private void drawEdge(Graphics2D g2, P2PNetworkEdgeSnapshot edge, int x1, int y1, int x2, int y2) {
      Stroke previous = g2.getStroke();
      if (edge.shortcut()) {
        g2.setStroke(
            new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[] {6f, 4f}, 0f));
        g2.setColor(new Color(0, 220, 120));
      } else {
        g2.setStroke(new BasicStroke(1.2f));
        g2.setColor(new Color(180, 180, 180));
      }
      g2.drawLine(x1, y1, x2, y2);
      g2.setStroke(previous);
    }

    private void drawNode(Graphics2D g2, P2PNetworkNodeSnapshot node, int x, int y) {
      Color color = colorForRole(node.role(), node.localNode());
      int nodeRadius = node.localNode() ? 20 : 14;
      int left = x - nodeRadius;
      int top = y - nodeRadius;

      g2.setColor(color);
      g2.fillOval(left, top, nodeRadius * 2, nodeRadius * 2);
      g2.setColor(Color.DARK_GRAY);
      g2.drawOval(left, top, nodeRadius * 2, nodeRadius * 2);

      g2.setColor(Color.BLACK);
      String label = node.id();
      g2.drawString(label, x - 34, y + nodeRadius + 16);

    }

    private Color colorForRole(String role, boolean localNode) {
      if (localNode) {
        return new Color(80, 140, 255);
      }
      if ("VEHICLE".equals(role)) {
        return new Color(80, 180, 95);
      }
      if ("CLIENT".equals(role)) {
        return new Color(245, 165, 70);
      }
      return new Color(170, 170, 170);
    }
  }
}
