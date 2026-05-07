package de.sikeller.aqs.visualization.controls;

import de.sikeller.aqs.model.AlgorithmParameter;
import de.sikeller.aqs.model.P2PNetworkEdgeSnapshot;
import de.sikeller.aqs.model.P2PNetworkNodeSnapshot;
import de.sikeller.aqs.model.P2PNetworkSnapshot;
import de.sikeller.aqs.model.P2PStatusProvider;
import de.sikeller.aqs.model.ResultTable;
import de.sikeller.aqs.model.SimulationControl;
import de.sikeller.aqs.model.TaxiAlgorithm;
import de.sikeller.aqs.p2p.api.P2PSystemProperties;
import de.sikeller.aqs.visualization.drawing.VisualizationProperties;
import de.sikeller.aqs.visualization.drawing.VisualizationUtils;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.text.NumberFormat;
import java.util.*;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TaxiScenarioControl extends AbstractControl {
  private static final String P2P_COLLECTOR_SIMPLE_NAME = "TaxiAlgorithmP2PCollector";
  private static final String MODE_LOCAL = "LOCAL";
  private static final String MODE_P2P_SIMULATED = "P2P-SIMULATED";
  private static final String MODE_P2P_LAN = "P2P-LAN";
  private static final String P2P_VEHICLE_STRATEGY_PROPERTY =
      P2PSystemProperties.VEHICLE_OPEN_REQUEST_STRATEGY;
  private static final String P2P_VEHICLE_STRATEGY_CONFIG_KEY = "p2pVehicleDecisionStrategy";
  private static final String P2P_STRATEGY_NEAREST = "nearest";
  private static final String P2P_STRATEGY_GREEDY = "greedy";
  private static final String P2P_MULTICAST_GROUP_FIELD = "p2pMulticastGroup";
  private static final Set<String> P2P_PORT_FIELDS = Set.of("p2pTcpPort", "p2pDiscoveryPort");
  private static final Set<String> P2P_CORE_PARAMETERS =
      Set.of(
          "p2pFixedSearchRadius",
          "p2pRequestForwardHops",
          "p2pOverlayMinNeighbors",
          "p2pOverlayMaxNeighbors",
          "p2pOverlayShortcuts",
          "p2pOverlayMaxDistanceFactor",
          "p2pRequestRepublishTicks",
          "p2pTopologyScanTicks");
  private List<Class<?>> algorithmList;
  private final SimulationControl simulation;
  private Map<String, Integer> inputParameterMap;
  private Map<String, Integer> algorithmParameterMap;
  private final Map<String, Integer> allParameterMap = new HashMap<>();
  private HashMap<String, Component> componentMap;
  private final BatchProcessingProperties batchProperties = new BatchProcessingProperties();
  private JPanel buttons;
  private JPanel worldInputs;
  private JPanel algorithmInputs;
  private JPanel controls;
  private JPanel selection;
  private JPanel batchProcessing;
  private JPanel p2pStatusPanel;
  private JButton p2pScanNowButton;
  private JButton p2pToggleTopologyButton;
  private JButton p2pToggleCollectorButton;
  private JComboBox<String> p2pVehicleStrategyBox;
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
  private JSpinner p2pPositionRevisionThrottleSpinner;
  private JSpinner p2pPositionRevisionMinMoveSpinner;
  private JComboBox<String> p2pShortcutStrategyBox;
  private JSpinner p2pShortcutKleinbergRSpinner;
  private JSpinner p2pShortcutNodeProbabilitySpinner;
  // Idle vehicle random travel UI controls
  private JCheckBox p2pIdleRandomTravelEnabledCheckBox;
  private JSpinner p2pIdleThresholdSpinner;
  private JSpinner p2pIdleCheckThrottleSpinner;
  private JSpinner p2pRandomTravelMaxDistanceSpinner;
  private volatile boolean massRunInProgress;
  private boolean modeSwitchInProgress;
  private Consumer<Boolean> p2pModeUiListener = ignored -> {};
  private static final String DEFAULT_TAXI_COUNT_TOOLTIP =
      "Set the count of taxis to be spawned in the simulation run";
  private static final long MASS_RUN_ITERATION_TIMEOUT_MS =
      Long.getLong("aqs.massRun.iterationTimeoutMs", 600_000L);
  private static final long MASS_RUN_WAIT_POLL_MS = 20L;

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
    buttons.add(massRunButton());
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
    worldInputs.add(label("Spawn scenario", "spawnScenarioLabel"));
    worldInputs.add(spawnScenarioCombo());
    worldInputs.add(label("Map size [m]", "mapSizeLabel"));
    worldInputs.add(mapSizeSpinner());
    worldInputs.setBorder(new TitledBorder("World Parameters"));
    controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
    worldInputs.setLayout(new GridLayout(10, 2, GAP, GAP));
    p2pStatusPanel = setupP2PStatusPanel();
    p2pScanNowButton = createP2PScanNowButton();
    p2pTopologyPanel = new P2PTopologyPanel();
    p2pTopologyPanel.setShowLocalCollector(false);
    p2pTopologyPanel.setBorder(new TitledBorder("P2P Network Topology"));
    p2pTopologyPanel.setPreferredSize(new Dimension(460, 230));
    p2pToggleCollectorButton = createP2PToggleCollectorButton();
    p2pTopologyPanel.setLegendToggleButton(p2pToggleCollectorButton);
    p2pToggleTopologyButton = createP2PToggleTopologyButton();
    batchProcessing = new BatchProcessingControl(batchProperties);
    controls.add(selection);
    controls.add(buttons);
    controls.add(worldInputs);
    controls.add(algorithmInputs);
    controls.add(p2pStatusPanel);
    controls.add(p2pScanNowButton);
    controls.add(p2pToggleTopologyButton);
    controls.add(batchProcessing);

    createComponentMap();
    generateParameters();
    applyModeToUi();
    refreshP2PStatus();
    // Ensure any JScrollBar UIs create non-null decrease/increase buttons to avoid
    // NullPointerException in some Look&Feels during layout.
    SwingUtilities.invokeLater(() -> ensureScrollbarsHaveButtons(controls));

    return controls;
  }

  private JComboBox<String> createP2PVehicleStrategyBox() {
    JComboBox<String> strategyBox = new JComboBox<>();
    strategyBox.setName(P2P_VEHICLE_STRATEGY_CONFIG_KEY);
    strategyBox.addItem(P2P_STRATEGY_NEAREST);
    strategyBox.addItem(P2P_STRATEGY_GREEDY);
    String configured = System.getProperty(P2P_VEHICLE_STRATEGY_PROPERTY, P2P_STRATEGY_NEAREST);
    strategyBox.setSelectedItem(normalizedStrategyKey(configured));
    strategyBox.setToolTipText("Taxi decision strategy for selecting open client requests.");
    strategyBox.addActionListener(e -> applySelectedP2PVehicleStrategy());
    p2pVehicleStrategyBox = strategyBox;
    return strategyBox;
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
    modes.setSelectedItem(
        isP2PAlgorithm(simulation.getAlgorithm().get()) ? MODE_P2P_SIMULATED : MODE_LOCAL);
    modes.addActionListener(
        e -> {
          generateParameters();
          applyModeToUi();
        });
    return modes;
  }

  private JPanel setupP2PStatusPanel() {
    JPanel panel = new JPanel(new GridLayout(15, 2, GAP, GAP));
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

  private JButton createP2PToggleCollectorButton() {
    JButton button = new JButton();
    button.setName("p2pToggleCollectorButton");
    updateP2PToggleCollectorButtonLabel(button, false);
    button.setToolTipText("Toggle visibility of the local collector node in the topology view.");
    button.addActionListener(
        e -> {
          if (p2pTopologyPanel == null) {
            return;
          }
          boolean next = !p2pTopologyPanel.isShowLocalCollector();
          p2pTopologyPanel.setShowLocalCollector(next);
          updateP2PToggleCollectorButtonLabel(button, next);
        });
    return button;
  }

  private void updateP2PToggleCollectorButtonLabel(JButton button, boolean collectorVisible) {
    if (button == null) {
      return;
    }
    button.setText(collectorVisible ? "Hide collector" : "Show collector");
  }

  private JButton createP2PToggleTopologyButton() {
    JButton button = new JButton("Hide topology");
    button.setName("p2pToggleTopologyButton");
    button.setToolTipText("Toggle visibility of the P2P topology view.");
    button.addActionListener(
        e -> {
          if (p2pTopologyPanel == null) {
            return;
          }
          boolean nowVisible = !p2pTopologyPanel.isVisible();
          p2pTopologyPanel.setVisible(nowVisible);
          button.setText(nowVisible ? "Hide topology" : "Show topology");
          // trigger layout update in parent container
          Container parent = p2pTopologyPanel.getParent();
          if (parent != null) {
            parent.revalidate();
            parent.repaint();
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
      p2pModeUiListener.accept(p2pMode);

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

      if (p2pVehicleStrategyBox != null) {
        p2pVehicleStrategyBox.setEnabled(p2pMode);
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
      if (p2pToggleTopologyButton != null) {
        p2pToggleTopologyButton.setVisible(p2pMode);
        if (p2pMode && !p2pTopologyPanel.isVisible()) {
          // restore topology panel visibility when re-entering p2p mode
          p2pTopologyPanel.setVisible(true);
        }
        p2pToggleTopologyButton.setText(
            (p2pTopologyPanel != null && p2pTopologyPanel.isVisible())
                ? "Hide topology"
                : "Show topology");
      }
      if (p2pScanNowButton != null) {
        p2pScanNowButton.setVisible(p2pMode);
        p2pScanNowButton.setEnabled(
            p2pMode && simulation.getAlgorithm().get() instanceof P2PStatusProvider);
      }
      if (p2pToggleCollectorButton != null) {
        p2pToggleCollectorButton.setEnabled(p2pMode);
      }
      // Show algorithm parameters in every mode; P2P-specific fields are filtered in
      // generateParameters().
      algorithmInputs.setVisible(true);

      worldInputs.setBorder(
          new TitledBorder(p2pMode ? "World Parameters (P2P Mode)" : "World Parameters"));
      algorithmInputs.revalidate();
      algorithmInputs.repaint();
      controls.revalidate();
      controls.repaint();
      updateP2PModeWarning(p2pMode);
      applySelectedP2PVehicleStrategy();
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
          "Warning: "
              + P2P_COLLECTOR_SIMPLE_NAME
              + " was not found. P2P mode falls back to the current algorithm.");
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
          "Warning: P2P mode is active, but the selected algorithm is not "
              + P2P_COLLECTOR_SIMPLE_NAME
              + ".");
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
      if (p2pToggleCollectorButton != null) {
        p2pToggleCollectorButton.setEnabled(true);
        updateP2PToggleCollectorButtonLabel(
            p2pToggleCollectorButton,
            p2pTopologyPanel != null && p2pTopologyPanel.isShowLocalCollector());
      }
      Map<String, String> status = provider.getP2PStatus();
      p2pModeValue.setText(status.getOrDefault("mode", "P2P"));
      p2pPeersValue.setText(status.getOrDefault("knownPeers", "0"));
      p2pVehiclePeersValue.setText(status.getOrDefault("vehiclePeers", "0"));
      p2pPendingValue.setText(status.getOrDefault("pendingRequests", "0"));
      p2pOverlayModeValue.setText(status.getOrDefault("overlayMode", "SMALL_WORLD"));
      String minNeighbors = status.getOrDefault("overlayMinNeighbors", "1");
      String maxDistance = status.getOrDefault("overlayMaxDistance", "-");
      p2pOverlayNeighborsValue.setText(
          "min="
              + minNeighbors
              + ", d_max="
              + maxDistance
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
    if (p2pToggleCollectorButton != null) {
      p2pToggleCollectorButton.setEnabled(false);
      updateP2PToggleCollectorButtonLabel(
          p2pToggleCollectorButton,
          p2pTopologyPanel != null && p2pTopologyPanel.isShowLocalCollector());
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
    return Integer.parseInt(value.trim());
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
      roleByNodeId.put(
          node.id(), node.role() == null ? "" : node.role().trim().toUpperCase(Locale.ROOT));
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

  public void setP2PModeUiListener(Consumer<Boolean> listener) {
    this.p2pModeUiListener = listener == null ? ignored -> {} : listener;
    this.p2pModeUiListener.accept(isP2PModeSelected());
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
      log.warn(
          "Could not force algorithm selection: {} not found in algorithm list.", simpleClassName);
      return false;
    }
    simulation
        .getAlgorithm()
        .setAlgorithm(instantiateAlgorithm(selectedAlgorithm, algorithmParameterMap));

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
                  isP2PAlgorithm(simulation.getAlgorithm().get())
                      ? MODE_P2P_SIMULATED
                      : MODE_LOCAL);
            }
          }
          generateParameters();
          applyModeToUi();
          updateP2PModeWarning(isP2PModeSelected());
        });
    // generateParameters() is called explicitly in setup() after createComponentMap() – not here
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

  private JButton massRunButton() {
    JButton button = new JButton("Mass Run");
    button.setName("massRunButton");
    button.setToolTipText("Run multiple simulations and export CSV statistics.");
    button.addActionListener(
        e -> {
          if (massRunInProgress) {
            return;
          }
          List<String> availableAlgorithms = resolveAlgorithmSimpleNames();
          MassRunDialog.MassRunConfig config =
              MassRunDialog.open(this, availableAlgorithms, defaultMassRunDialogValues());
          if (config == null) {
            return;
          }
          startMassRun(config);
        });
    return button;
  }

  private MassRunDialog.Defaults defaultMassRunDialogValues() {
    List<String> availableAlgorithms = resolveAlgorithmSimpleNames();
    int defaultKHops = readSpinnerValue("p2pRequestForwardHops", 1);
    int defaultRqsRadius = readSpinnerValue("p2pFixedSearchRadius", 500);
    int taxiCount = readSpinnerValue("taxiCount", 5);
    int clientCount = readSpinnerValue("clientCount", 100);
    int clientSpawnWindow = readSpinnerValue("clientSpawnWindow", 10000);
    int clientSpeed = readSpinnerValue("clientSpeed", 5);
    int taxiSeatCount = readSpinnerValue("taxiSeatCount", 2);
    int taxiSpeed = readSpinnerValue("taxiSpeed", 80);
    int simulationSpeed = 100;
    int mapSize = readSpinnerValue("mapSize", 40000);
    int defaultOverlayMaxNeighbors = readSpinnerValue("p2pOverlayMaxNeighbors", 5);
    boolean idleRoamingEnabled = readCheckboxValue("p2pIdleRandomTravelEnabled", true);
    int idleThresholdTicks = readSpinnerValue("p2pIdleThresholdTicks", 60);
    int idleCheckThrottleTicks = readSpinnerValue("p2pIdleCheckThrottleTicks", 5);
    int randomTravelMaxDistance = readSpinnerValue("p2pRandomTravelMaxDistanceMeters", 20000);
    return new MassRunDialog.Defaults(
        resolveDefaultMassRunAlgorithmsCsv(availableAlgorithms),
        String.valueOf(defaultKHops),
        String.valueOf(defaultRqsRadius),
        P2P_STRATEGY_NEAREST,
        String.valueOf(readSpinnerValue("p2pOverlayMinNeighbors", 1)),
        String.valueOf(defaultOverlayMaxNeighbors),
        String.valueOf(readSpinnerValue("p2pOverlayShortcuts", 1)),
        "BASELINE",
        10,
        1,
        "mass-run-results",
        String.valueOf(taxiCount),
        String.valueOf(clientCount),
        clientSpawnWindow,
        clientSpeed,
        String.valueOf(taxiSeatCount),
        taxiSpeed,
        simulationSpeed,
        mapSize,
        idleRoamingEnabled,
        idleThresholdTicks,
        idleCheckThrottleTicks,
        randomTravelMaxDistance);
  }

  private String resolveDefaultMassRunAlgorithmsCsv(List<String> availableAlgorithms) {
    List<String> defaults = new ArrayList<>();
    if (availableAlgorithms.contains(P2P_COLLECTOR_SIMPLE_NAME)) {
      defaults.add(P2P_COLLECTOR_SIMPLE_NAME);
    }
    if (availableAlgorithms.contains("TaxiAlgorithmSinglePassenger")) {
      defaults.add("TaxiAlgorithmSinglePassenger");
    } else if (availableAlgorithms.contains("TaxiAlgorithmVehicleRouting")) {
      defaults.add("TaxiAlgorithmVehicleRouting");
    }
    if (defaults.isEmpty() && !availableAlgorithms.isEmpty()) {
      defaults.add(availableAlgorithms.getFirst());
    }
    return String.join(",", defaults);
  }

  private List<String> resolveAlgorithmSimpleNames() {
    algorithmList = simulation.getAlgorithm().getAllAlgorithms();
    return algorithmList.stream().map(Class::getSimpleName).sorted().toList();
  }

  private void startMassRun(MassRunDialog.MassRunConfig config) {
    massRunInProgress = true;
    setControlsEnabledForMassRun(false);
    simulation.stop();
    simulation.setRealtimeVisualizationEnabled(false);
    if (p2pStatusTimer != null) {
      p2pStatusTimer.stop();
    }

    JDialog progressDialog = createMassRunProgressDialog();
    SwingWorker<MassRunCsvWriter.OutputFiles, int[]> worker =
        new SwingWorker<>() {
          @Override
          protected MassRunCsvWriter.OutputFiles doInBackground() throws Exception {
            List<MassRunCsvWriter.RunMetricRow> runRows = new ArrayList<>();
            int totalRuns =
                config.algorithms().stream()
                    .mapToInt(
                        algorithmSimpleName ->
                            effectiveKHopsForAlgorithm(algorithmSimpleName, config).size()
                                * effectiveRqsRadiusForAlgorithm(algorithmSimpleName, config).size()
                                * effectiveStrategiesForAlgorithm(algorithmSimpleName, config)
                                    .size()
                                * effectiveOverlayMinNeighborsForAlgorithm(
                                        algorithmSimpleName, config)
                                    .size()
                                * effectiveOverlayShortcutsForAlgorithm(algorithmSimpleName, config)
                                    .size()
                                * config.spawnScenarios().size()
                                * config.taxiCounts().size()
                                * config.taxiSeatCounts().size()
                                * config.runs())
                    .sum();
            int doneRuns = 0;

            for (String algorithmSimpleName : config.algorithms()) {
              List<Integer> effectiveKHops =
                  effectiveKHopsForAlgorithm(algorithmSimpleName, config);
              List<Integer> effectiveRqsRadius =
                  effectiveRqsRadiusForAlgorithm(algorithmSimpleName, config);
              List<String> effectiveStrategies =
                  effectiveStrategiesForAlgorithm(algorithmSimpleName, config);
              List<Integer> effectiveOverlayMinNeighbors =
                  effectiveOverlayMinNeighborsForAlgorithm(algorithmSimpleName, config);
              List<Integer> effectiveOverlayMaxNeighbors =
                  effectiveOverlayMaxNeighborsForAlgorithm(algorithmSimpleName, config);
              List<Integer> effectiveOverlayShortcuts =
                  effectiveOverlayShortcutsForAlgorithm(algorithmSimpleName, config);
              for (int kHops : effectiveKHops) {
                for (int rqsRadius : effectiveRqsRadius) {
                  for (String p2pStrategy : effectiveStrategies) {
                    for (int overlayMinNeighbors : effectiveOverlayMinNeighbors) {
                      for (int overlayMaxNeighbors : effectiveOverlayMaxNeighbors) {
                        for (int overlayShortcuts : effectiveOverlayShortcuts) {
                          for (String spawnScenario : config.spawnScenarios()) {
                            for (int pairIndex = 0;
                                pairIndex < config.taxiCounts().size();
                                pairIndex++) {
                              int taxiCount = config.taxiCounts().get(pairIndex);
                              int clientCount = config.clientCounts().get(pairIndex);
                              for (int taxiSeatCount : config.taxiSeatCounts()) {
                                for (int runIndex = 1; runIndex <= config.runs(); runIndex++) {
                                  int seed = config.baseSeed() + (runIndex - 1);
                                  MassRunIterationResult result =
                                      executeMassRunIteration(
                                          config,
                                          algorithmSimpleName,
                                          kHops,
                                          rqsRadius,
                                          p2pStrategy,
                                          overlayMinNeighbors,
                                          overlayMaxNeighbors,
                                          overlayShortcuts,
                                          spawnScenario,
                                          taxiCount,
                                          clientCount,
                                          taxiSeatCount,
                                          seed);
                                  String timestamp = java.time.Instant.now().toString();
                                  runRows.addAll(
                                      MassRunCsvWriter.toRunRows(
                                          result.table(),
                                          result.executedAlgorithm(),
                                          kHops,
                                          result.executedRqsRadius(),
                                          taxiCount,
                                          clientCount,
                                          taxiSeatCount,
                                          result.executedStrategy(),
                                          result.executedOverlayMinNeighbors(),
                                          result.executedOverlayMaxNeighbors(),
                                          result.executedOverlayShortcuts(),
                                          result.executedSpawnScenario(),
                                          runIndex,
                                          seed,
                                          timestamp));

                                  // Fortschritt pro abgeschlossener Iteration aktualisieren
                                  doneRuns++;
                                  int progress =
                                      (int) Math.round(doneRuns * 100.0 / Math.max(1, totalRuns));
                                  setProgress(Math.max(0, Math.min(100, progress)));
                                  publish(
                                      new int[] {
                                        doneRuns, totalRuns, Math.max(0, Math.min(100, progress))
                                      });
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }

            // write config file for reproducibility
            MassRunCsvWriter.OutputFiles csvOutput = MassRunCsvWriter.write(config.outputDir(), runRows);
            java.nio.file.Path configFile = MassRunCsvWriter.writeConfig(config.outputDir(), config);
            return new MassRunCsvWriter.OutputFiles(csvOutput.runCsv(), csvOutput.aggregateCsv(), configFile);
          }

          @Override
          protected void process(List<int[]> chunks) {
            if (chunks == null || chunks.isEmpty()) {
              return;
            }
            int[] last = chunks.get(chunks.size() - 1);
            if (last == null || last.length < 3) {
              return;
            }
            updateMassRunProgress(progressDialog, last[2], last[0], last[1]);
          }

          @Override
          protected void done() {
            progressDialog.dispose();
            massRunInProgress = false;
            simulation.setRealtimeVisualizationEnabled(true);
            if (p2pStatusTimer != null && !p2pStatusTimer.isRunning()) {
              p2pStatusTimer.start();
            }
            setControlsEnabledForMassRun(true);
            try {
              MassRunCsvWriter.OutputFiles output = get();
              JOptionPane.showMessageDialog(
                  TaxiScenarioControl.this,
                  "Mass run completed.\n" + output,
                  "Mass run finished",
                  JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
              log.error("Mass run failed", ex);
              JOptionPane.showMessageDialog(
                  TaxiScenarioControl.this,
                  ex.getMessage(),
                  "Mass run failed",
                  JOptionPane.ERROR_MESSAGE);
            }
          }
        };

    worker.execute();
    progressDialog.setVisible(true);
  }

  private MassRunIterationResult executeMassRunIteration(
      MassRunDialog.MassRunConfig config,
      String algorithmSimpleName,
      int kHops,
      int rqsRadius,
      String p2pStrategy,
      int overlayMinNeighbors,
      int overlayMaxNeighbors,
      int overlayShortcuts,
      String spawnScenario,
      int taxiCount,
      int clientCount,
      int taxiSeatCount,
      int seed)
      throws Exception {
    MassRunIterationResult result =
        runOnEdt(
            () -> {
              selectAlgorithmForMassRun(algorithmSimpleName);
              setSpinnerValueIfPresent("worldSeed", seed);
              setSpinnerValueIfPresent("taxiCount", taxiCount);
              setSpinnerValueIfPresent("clientCount", clientCount);
              setSpinnerValueIfPresent("clientSpawnWindow", config.clientSpawnWindow());
              setSpinnerValueIfPresent("clientSpeed", config.clientSpeed());
              setSpinnerValueIfPresent("taxiSeatCount", taxiSeatCount);
              setSpinnerValueIfPresent("taxiSpeed", config.taxiSpeed());
              setSpinnerValueIfPresent("mapSize", config.mapSize());
              setComboIndexIfPresent(
                  "spawnScenario",
                  de.sikeller.aqs.model.SpawnScenario.fromLabel(spawnScenario).ordinal());
              String executedAlgorithm = simulation.getAlgorithm().get().getClass().getSimpleName();
              String executedStrategy = "n/a";
              int executedRqsRadius = -1;
              int executedOverlayMinNeighbors = -1;
              int executedOverlayMaxNeighbors = -1;
              int executedOverlayShortcuts = -1;
              if (isCollectorAlgorithmName(executedAlgorithm)) {
                setSpinnerValueIfPresent("p2pRequestForwardHops", kHops);
                setSpinnerValueIfPresent("p2pFixedSearchRadius", rqsRadius);
                setSpinnerValueIfPresent("p2pOverlayMinNeighbors", overlayMinNeighbors);
                setSpinnerValueIfPresent("p2pOverlayMaxNeighbors", overlayMaxNeighbors);
                setSpinnerValueIfPresent("p2pOverlayShortcuts", overlayShortcuts);
                // Apply idle roaming system properties for this iteration
                System.setProperty(
                    de.sikeller.aqs.p2p.api.P2PSystemProperties.VEHICLE_IDLE_RANDOM_TRAVEL_ENABLED,
                    String.valueOf(config.idleRoamingEnabled()));
                System.setProperty(
                    de.sikeller.aqs.p2p.api.P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS,
                    String.valueOf(config.idleThresholdTicks()));
                System.setProperty(
                    de.sikeller.aqs.p2p.api.P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS,
                    String.valueOf(config.idleCheckThrottleTicks()));
                System.setProperty(
                    de.sikeller.aqs.p2p.api.P2PSystemProperties.VEHICLE_RANDOM_TRAVEL_MAX_DISTANCE_METERS,
                    String.valueOf(config.randomTravelMaxDistanceMeters()));
                // Sync UI checkboxes/spinners so they reflect the applied values
                if (p2pIdleRandomTravelEnabledCheckBox != null) {
                  p2pIdleRandomTravelEnabledCheckBox.setSelected(config.idleRoamingEnabled());
                }
                setSpinnerValueIfPresent("p2pIdleThresholdTicks", config.idleThresholdTicks());
                setSpinnerValueIfPresent("p2pIdleCheckThrottleTicks", config.idleCheckThrottleTicks());
                setSpinnerValueIfPresent("p2pRandomTravelMaxDistanceMeters", config.randomTravelMaxDistanceMeters());
                // Mass-runs: keep topology scan ticks as configured to ensure proper protocol
                // behavior.
                executedRqsRadius = rqsRadius;
                executedOverlayMinNeighbors = overlayMinNeighbors;
                executedOverlayMaxNeighbors = overlayMaxNeighbors;
                executedOverlayShortcuts = overlayShortcuts;
                executedStrategy = applyP2PStrategyForMassRun(p2pStrategy);
              }
              simulation.setSpeed(config.simulationSpeed());
              initializeSimulation();
              simulation.start();
              return new MassRunIterationResult(
                  null,
                  executedAlgorithm,
                  executedStrategy,
                  executedRqsRadius,
                  executedOverlayMinNeighbors,
                  executedOverlayMaxNeighbors,
                  executedOverlayShortcuts,
                  spawnScenario);
            });

    long timeoutMs = Math.max(1_000L, MASS_RUN_ITERATION_TIMEOUT_MS);
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (!simulation.isSimulationFinished()) {
      if (System.currentTimeMillis() >= deadline) {
        simulation.stop();
        throw new IllegalStateException(
            String.format(
                Locale.ROOT,
                "Mass-run iteration timeout after %d ms (algorithm=%s, kHops=%d, rqsRadius=%d, overlayMinNeighbors=%d, overlayMaxNeighbors=%d, overlayShortcuts=%d, taxiCount=%d, clientCount=%d, taxiSeatCount=%d, seed=%d)",
                timeoutMs,
                algorithmSimpleName,
                kHops,
                rqsRadius,
                overlayMinNeighbors,
                overlayMaxNeighbors,
                overlayShortcuts,
                taxiCount,
                clientCount,
                taxiSeatCount,
                seed));
      }
      Thread.sleep(MASS_RUN_WAIT_POLL_MS);
    }

    ResultTable table = simulation.getLatestResultTable();
    if (table == null) {
      throw new IllegalStateException("Simulation completed without result table.");
    }
    return new MassRunIterationResult(
        table,
        result.executedAlgorithm(),
        result.executedStrategy(),
        result.executedRqsRadius(),
        result.executedOverlayMinNeighbors(),
        result.executedOverlayMaxNeighbors(),
        result.executedOverlayShortcuts(),
        result.executedSpawnScenario());
  }

  private void selectAlgorithmForMassRun(String algorithmSimpleName) {
    // Prevent LOCAL algorithms from being overridden by P2P-mode collector forcing.
    setSimulationModeForMassRunAlgorithm(algorithmSimpleName);
    Class<?> algorithmClass = resolveAlgorithmClassBySimpleName(algorithmSimpleName);
    simulation
        .getAlgorithm()
        .setAlgorithm(instantiateAlgorithm(algorithmClass.getName(), algorithmParameterMap));
    generateParameters();
    applyModeToUi();
    updateP2PModeWarning(isP2PModeSelected());
  }

  private Class<?> resolveAlgorithmClassBySimpleName(String algorithmSimpleName) {
    algorithmList = simulation.getAlgorithm().getAllAlgorithms();
    for (Class<?> algorithmClass : algorithmList) {
      if (algorithmClass.getSimpleName().equals(algorithmSimpleName)) {
        return algorithmClass;
      }
    }
    // Fallback: try to load common fully-qualified locations for taxi algorithms.
    // This helps in environments where Reflections scanning didn't discover the class
    // (classpath/module-classloader quirks). Try a few known packages before failing.
    String[] fallbackPackages =
        new String[] {
          "de.sikeller.aqs.taxi.algorithm.collector",
          "de.sikeller.aqs.taxi.algorithm",
          "de.sikeller.aqs.taxi.algorithm.distributed",
          "de.sikeller.aqs.taxi.algorithm.p2p"
        };
    for (String pkg : fallbackPackages) {
      String fq = pkg + "." + algorithmSimpleName;
      try {
        return Class.forName(fq);
      } catch (ClassNotFoundException ignored) {
        // try next
      }
    }

    // Nothing found - provide a clearer error including the available algorithm simple names
    String available =
        algorithmList == null
            ? "[]"
            : algorithmList.stream().map(Class::getSimpleName).sorted().toList().toString();
    throw new IllegalArgumentException(
        "Unknown algorithm: " + algorithmSimpleName + ". Available: " + available);
  }

  private void setSpinnerValueIfPresent(String name, int value) {
    Component component = getComponentByName(name);
    if (component instanceof JSpinner spinner) {
      spinner.setValue(value);
      return;
    }
    // Algorithm parameter spinners live in algorithmInputs which is rebuilt dynamically
    // and is therefore NOT included in the static componentMap – search it explicitly.
    forEachAlgorithmComponent(
        comp -> {
          if (comp instanceof JSpinner s && name.equals(s.getName())) {
            s.setValue(value);
          }
        });
  }

  private void setComboIndexIfPresent(String name, int index) {
    Component component = getComponentByName(name);
    if (component instanceof JComboBox<?> combo) {
      if (index >= 0 && index < combo.getItemCount()) {
        combo.setSelectedIndex(index);
        return;
      }
    }
    // Also search worldInputs combos (e.g. spawnScenario) that may not be in componentMap
    for (Component comp : worldInputs.getComponents()) {
      if (comp instanceof JComboBox<?> combo && name.equals(combo.getName())) {
        if (index >= 0 && index < combo.getItemCount()) {
          combo.setSelectedIndex(index);
        }
        return;
      }
    }
  }

  private int readSpinnerValue(String name, int defaultValue) {
    Component component = getComponentByName(name);
    if (component instanceof JSpinner spinner) {
      Object value = spinner.getValue();
      if (value instanceof Number number) {
        return number.intValue();
      }
    }
    // Also search algorithmInputs (not in static componentMap)
    final int[] result = {defaultValue};
    forEachAlgorithmComponent(
        comp -> {
          if (comp instanceof JSpinner s && name.equals(s.getName())) {
            Object val = s.getValue();
            if (val instanceof Number number) {
              result[0] = number.intValue();
            }
          }
        });
    return result[0];
  }

  private boolean readCheckboxValue(String name, boolean defaultValue) {
    final boolean[] result = {defaultValue};
    forEachAlgorithmComponent(
        comp -> {
          if (comp instanceof JCheckBox cb && name.equals(cb.getName())) {
            result[0] = cb.isSelected();
          }
        });
    return result[0];
  }

  private String applyP2PStrategyForMassRun(String strategy) {
    String normalized = normalizedStrategyKey(strategy);
    if (p2pVehicleStrategyBox != null) {
      p2pVehicleStrategyBox.setSelectedItem(normalized);
    }
    System.setProperty(P2P_VEHICLE_STRATEGY_PROPERTY, normalized);
    return normalized;
  }

  private void setSimulationModeForMassRunAlgorithm(String algorithmSimpleName) {
    Component component = getComponentByName("simulationModeBox");
    if (!(component instanceof JComboBox<?> modeBox)) {
      return;
    }
    String targetMode =
        isCollectorAlgorithmName(algorithmSimpleName) ? MODE_P2P_SIMULATED : MODE_LOCAL;
    modeBox.setSelectedItem(targetMode);
  }

  private boolean isCollectorAlgorithmName(String algorithmSimpleName) {
    if (algorithmSimpleName == null || algorithmSimpleName.isBlank()) {
      return false;
    }
    return algorithmSimpleName.equals(P2P_COLLECTOR_SIMPLE_NAME)
        || algorithmSimpleName.toLowerCase(Locale.ROOT).contains("p2pcollector");
  }

  private List<Integer> effectiveKHopsForAlgorithm(
      String algorithmSimpleName, MassRunDialog.MassRunConfig config) {
    if (isCollectorAlgorithmName(algorithmSimpleName)) {
      return config.kHops();
    }
    return List.of(0);
  }

  private List<Integer> effectiveRqsRadiusForAlgorithm(
      String algorithmSimpleName, MassRunDialog.MassRunConfig config) {
    if (isCollectorAlgorithmName(algorithmSimpleName)) {
      return config.rqsRadiusValues();
    }
    return List.of(-1);
  }

  private List<String> effectiveStrategiesForAlgorithm(
      String algorithmSimpleName, MassRunDialog.MassRunConfig config) {
    if (isCollectorAlgorithmName(algorithmSimpleName)) {
      return config.p2pStrategies();
    }
    return List.of("n/a");
  }

  private List<Integer> effectiveOverlayMinNeighborsForAlgorithm(
      String algorithmSimpleName, MassRunDialog.MassRunConfig config) {
    if (isCollectorAlgorithmName(algorithmSimpleName)) {
      return config.overlayMinNeighborsValues();
    }
    return List.of(-1);
  }

  private List<Integer> effectiveOverlayMaxNeighborsForAlgorithm(
      String algorithmSimpleName, MassRunDialog.MassRunConfig config) {
    if (isCollectorAlgorithmName(algorithmSimpleName)) {
      return config.overlayMaxNeighborsValues();
    }
    return List.of(-1);
  }

  private List<Integer> effectiveOverlayShortcutsForAlgorithm(
      String algorithmSimpleName, MassRunDialog.MassRunConfig config) {
    if (isCollectorAlgorithmName(algorithmSimpleName)) {
      return config.overlayShortcutsValues();
    }
    return List.of(-1);
  }

  private record MassRunIterationResult(
      ResultTable table,
      String executedAlgorithm,
      String executedStrategy,
      int executedRqsRadius,
      int executedOverlayMinNeighbors,
      int executedOverlayMaxNeighbors,
      int executedOverlayShortcuts,
      String executedSpawnScenario) {}

  private void setControlsEnabledForMassRun(boolean enabled) {
    for (Component component : buttons.getComponents()) {
      if (component != null) {
        component.setEnabled(enabled);
      }
    }
    Object speedSlider = getComponentByName("simulationSpeedSlider");
    if (speedSlider instanceof JSlider slider) {
      slider.setEnabled(enabled);
    }
  }

  private JDialog createMassRunProgressDialog() {
    JDialog dialog =
        new JDialog(
            SwingUtilities.getWindowAncestor(this),
            "Mass run progress",
            Dialog.ModalityType.APPLICATION_MODAL);
    dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    dialog.getContentPane().setLayout(new BorderLayout(8, 8));
    JProgressBar progressBar = new JProgressBar(0, 100);
    progressBar.setName("massRunProgressBar");
    progressBar.setStringPainted(true);
    progressBar.setValue(0);
    progressBar.setString("0% (0/0)");
    JLabel counterLabel = new JLabel("Runs: 0 / 0");
    counterLabel.setName("massRunCounterLabel");
    dialog.getContentPane().add(new JLabel("Running mass simulation..."), BorderLayout.NORTH);
    dialog.getContentPane().add(progressBar, BorderLayout.CENTER);
    dialog.getContentPane().add(counterLabel, BorderLayout.SOUTH);
    dialog.pack();
    dialog.setLocationRelativeTo(this);
    return dialog;
  }

  private void updateMassRunProgress(JDialog dialog, int progress, int doneRuns, int totalRuns) {
    int safeTotal = Math.max(0, totalRuns);
    int safeDone = Math.max(0, Math.min(doneRuns, safeTotal));
    for (Component component : dialog.getContentPane().getComponents()) {
      if (component instanceof JProgressBar progressBar) {
        progressBar.setValue(progress);
        progressBar.setString(progress + "% (" + safeDone + "/" + safeTotal + ")");
      }
      if (component instanceof JLabel label && "massRunCounterLabel".equals(label.getName())) {
        label.setText("Runs: " + safeDone + " / " + safeTotal);
      }
    }
  }

  private void ensureScrollbarsHaveButtons(Container root) {
    if (root == null) {
      return;
    }
    List<Component> stack = new ArrayList<>();
    stack.add(root);
    while (!stack.isEmpty()) {
      Component comp = stack.remove(stack.size() - 1);
      if (comp instanceof JScrollBar sb) {
        // Replace UI with a safe BasicScrollBarUI that always creates buttons
        sb.setUI(
            new javax.swing.plaf.basic.BasicScrollBarUI() {
              @Override
              protected JButton createDecreaseButton(int orientation) {
                JButton b = new JButton();
                b.setFocusable(false);
                b.setBorderPainted(false);
                b.setOpaque(false);
                return b;
              }

              @Override
              protected JButton createIncreaseButton(int orientation) {
                JButton b = new JButton();
                b.setFocusable(false);
                b.setBorderPainted(false);
                b.setOpaque(false);
                return b;
              }
            });
      } else if (comp instanceof Container container) {
        for (Component child : container.getComponents()) {
          stack.add(child);
        }
      }
    }
  }

  private <T> T runOnEdt(Callable<T> action) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
      return action.call();
    }
    final Object[] holder = new Object[1];
    final Exception[] error = new Exception[1];
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            holder[0] = action.call();
          } catch (Exception ex) {
            error[0] = ex;
          }
        });
    if (error[0] != null) {
      throw error[0];
    }
    @SuppressWarnings("unchecked")
    T result = (T) holder[0];
    return result;
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

  private JComboBox<String> spawnScenarioCombo() {
    JComboBox<String> combo = new JComboBox<>();
    combo.setName("spawnScenario");
    combo.addItem("BASELINE");
    combo.addItem("RUSH_HOUR");
    combo.addItem("SPATIAL_IMBALANCE");
    combo.addItem("SPATIAL_ISLANDS");
    combo.setSelectedIndex(0);
    combo.setToolTipText(
        "S1 BASELINE: constant load | S2 RUSH_HOUR: peaks at 7-9h / 17-19h | S3 SPATIAL_IMBALANCE: CBD hotspot | S4 SPATIAL_ISLANDS: 6 distributed hotspot islands");
    return combo;
  }

  private JSpinner taxiSpeedSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(80, 1, 1_000_000_000, 1);
    JSpinner spinner = new JSpinner(spinnerModel);
    configureIntegerSpinner(spinner);
    spinner.setName("taxiSpeed");
    spinner.setToolTipText("Set the initial Speed of the Taxi");
    return spinner;
  }

  private JSpinner mapSizeSpinner() {
    SpinnerModel spinnerModel = new SpinnerNumberModel(40000, 1, 1_000_000_000, 1000);
    JSpinner spinner = new JSpinner(spinnerModel);
    configureIntegerSpinner(spinner);
    spinner.setName("mapSize");
    spinner.setToolTipText("Set the size of the simulation map in meters (width and height)");
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

  // Explicit display order for P2P algorithm parameters, grouped by concern.
  // Parameters not listed here are rendered at the end in their natural order.
  private static final List<String> P2P_PARAMETER_DISPLAY_ORDER =
      List.of(
          // --- Request Routing ---
          "p2pRequestForwardHops",
          "p2pFixedSearchRadius",
          "p2pRequestRepublishTicks",
          // --- Overlay Topology (vehicle decision inserted between these groups in code) ---
          "p2pOverlayMinNeighbors",
          "p2pOverlayMaxNeighbors",
          "p2pOverlayMaxDistanceFactor",
          "p2pOverlayShortcuts",
          "p2pTopologyScanTicks",
          // --- Network / LAN (shortcut strategy inserted above these in code) ---
          "p2pDiscoveryWaitMs",
          "p2pTcpPort",
          "p2pDiscoveryPort",
          "p2pMulticastA");

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
    int rowCount = 0;

    boolean showP2PStrategyOption =
        isP2PModeSelected()
            || isP2PAlgorithm(simulation.getAlgorithm().get())
            || parameters.stream()
                .map(AlgorithmParameter::name)
                .anyMatch(
                    name ->
                        P2P_CORE_PARAMETERS.contains(name)
                            || isP2PPortField(name)
                            || isP2PMulticastOctet(name)
                            || "p2pDiscoveryWaitMs".equals(name));

    // Build a lookup map so we can render parameters in explicit order
    Map<String, AlgorithmParameter> paramByName = new LinkedHashMap<>();
    for (AlgorithmParameter p : parameters) {
      paramByName.put(p.name(), p);
    }

    if (showP2PStrategyOption) {
      // GridBagLayout on algorithmInputs guarantees every group panel fills the full width
      // without BoxLayout alignment pitfalls.
      algorithmInputs.setLayout(new GridBagLayout());

      // P2P-specific controls are only shown when a P2P mode is actually selected.
      // Fall back to algorithm-based detection if componentMap isn't ready yet (startup).
      boolean inP2PMode =
          isP2PModeSelected()
              || (componentMap == null && isP2PAlgorithm(simulation.getAlgorithm().get()));

      String selectedMode =
          getComponentByName("simulationModeBox") instanceof JComboBox<?> combo
              ? Objects.toString(combo.getSelectedItem(), MODE_LOCAL)
              : (inP2PMode ? MODE_P2P_SIMULATED : MODE_LOCAL);

      // === Group 1: Request Routing ===
      JPanel routingGroup = newGroupPanel("Request Routing");
      int routingRows = 0;
      if (inP2PMode)
        routingRows +=
            addStandardParamRowIfPresent("p2pRequestForwardHops", paramByName, routingGroup);
      routingRows +=
          addStandardParamRowIfPresent("p2pFixedSearchRadius", paramByName, routingGroup);
      routingRows +=
          addStandardParamRowIfPresent("p2pRequestRepublishTicks", paramByName, routingGroup);
      if (routingRows > 0) {
        routingGroup.setLayout(new GridLayout(routingRows, 2, GAP, GAP));
        algorithmInputs.add(routingGroup, fullWidthGbc());
      }

      // === Group 2: Vehicle Selection (P2P mode only) ===
      if (inP2PMode) {
        JPanel selectionGroup = newGroupPanel("Vehicle Selection");
        JLabel strategyLabel = new JLabel("Vehicle decision");
        strategyLabel.setName("p2pVehicleDecisionStrategyLabel");
        selectionGroup.add(strategyLabel);
        if (p2pVehicleStrategyBox == null) createP2PVehicleStrategyBox();
        p2pVehicleStrategyBox.setEnabled(true);
        selectionGroup.add(p2pVehicleStrategyBox);
        selectionGroup.setLayout(new GridLayout(1, 2, GAP, GAP));
        algorithmInputs.add(selectionGroup, fullWidthGbc());
      }

      // === Group 3: Overlay Topology ===
      JPanel topologyGroup = newGroupPanel("Overlay Topology");
      int topologyRows = 0;
      topologyRows +=
          addStandardParamRowIfPresent("p2pOverlayMinNeighbors", paramByName, topologyGroup);
      topologyRows +=
          addStandardParamRowIfPresent("p2pOverlayMaxNeighbors", paramByName, topologyGroup);
      topologyRows +=
          addStandardParamRowIfPresent("p2pOverlayMaxDistanceFactor", paramByName, topologyGroup);
      topologyRows +=
          addStandardParamRowIfPresent("p2pOverlayShortcuts", paramByName, topologyGroup);
      topologyRows +=
          addStandardParamRowIfPresent("p2pTopologyScanTicks", paramByName, topologyGroup);
      if (topologyRows > 0) {
        topologyGroup.setLayout(new GridLayout(topologyRows, 2, GAP, GAP));
        algorithmInputs.add(topologyGroup, fullWidthGbc());
      }

      // === Group 4: Shortcut Strategy (P2P mode only) ===
      if (inP2PMode) {
        JPanel shortcutGroup = newGroupPanel("Shortcut Strategy");
        int shortcutRows = 0;

        JLabel overlayStrategyLabel = new JLabel("Overlay shortcut strategy");
        overlayStrategyLabel.setName("p2pOverlayShortcutStrategyLabel");
        shortcutGroup.add(overlayStrategyLabel);
        JComboBox<String> shortcutStrategyBox = new JComboBox<>();
        shortcutStrategyBox.setName("p2pOverlayShortcutStrategy");
        shortcutStrategyBox.addItem("kleinberg");
        shortcutStrategyBox.addItem("ring");
        String configuredStrategy =
            System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUT_STRATEGY, "kleinberg")
                .trim()
                .toLowerCase(Locale.ROOT);
        shortcutStrategyBox.setSelectedItem(configuredStrategy);
        shortcutStrategyBox.setToolTipText("Shortcut selection strategy (kleinberg|ring)");
        shortcutGroup.add(shortcutStrategyBox);
        p2pShortcutStrategyBox = shortcutStrategyBox;
        shortcutRows++;

        JLabel rLabel = new JLabel("Kleinberg exponent r");
        rLabel.setName("p2pOverlayKleinbergRLabel");
        shortcutGroup.add(rLabel);
        double defaultR =
            Math.max(
                0.0,
                Double.parseDouble(
                    System.getProperty(P2PSystemProperties.OVERLAY_SHORTCUT_KLEINBERG_R, "2.0")));
        JSpinner rSpinner = new JSpinner(new SpinnerNumberModel(defaultR, 0.0, 10.0, 0.1));
        rSpinner.setEditor(new JSpinner.NumberEditor(rSpinner, "0.0"));
        rSpinner.setName("p2pOverlayKleinbergR");
        rSpinner.setToolTipText("Kleinberg exponent r (only used when strategy=kleinberg)");
        shortcutGroup.add(rSpinner);
        p2pShortcutKleinbergRSpinner = rSpinner;
        shortcutRows++;

        JLabel nodeProbLabel = new JLabel("Shortcut node probability");
        nodeProbLabel.setName("p2pOverlayShortcutNodeProbabilityLabel");
        shortcutGroup.add(nodeProbLabel);
        double defaultNodeProb =
            Math.max(
                0.0,
                Math.min(
                    1.0,
                    Double.parseDouble(
                        System.getProperty(
                            P2PSystemProperties.OVERLAY_SHORTCUT_NODE_PROBABILITY, "0.2"))));
        JSpinner nodeProbSpinner =
            new JSpinner(new SpinnerNumberModel(defaultNodeProb, 0.0, 1.0, 0.01));
        nodeProbSpinner.setEditor(new JSpinner.NumberEditor(nodeProbSpinner, "0.00"));
        nodeProbSpinner.setName("p2pOverlayShortcutNodeProbability");
        nodeProbSpinner.setToolTipText(
            "Fraction of nodes that create Kleinberg shortcuts (0.0-1.0)");
        shortcutGroup.add(nodeProbSpinner);
        p2pShortcutNodeProbabilitySpinner = nodeProbSpinner;
        shortcutRows++;

        shortcutGroup.setLayout(new GridLayout(shortcutRows, 2, GAP, GAP));
        algorithmInputs.add(shortcutGroup, fullWidthGbc());

        shortcutStrategyBox.addActionListener(
            e -> {
              Object sel = shortcutStrategyBox.getSelectedItem();
              p2pShortcutKleinbergRSpinner.setEnabled(
                  sel != null && "kleinberg".equalsIgnoreCase(sel.toString()));
              System.setProperty(
                  P2PSystemProperties.OVERLAY_SHORTCUT_STRATEGY,
                  Objects.toString(sel, "kleinberg"));
              System.setProperty(
                  P2PSystemProperties.OVERLAY_SHORTCUT_KLEINBERG_R,
                  String.valueOf(((Number) p2pShortcutKleinbergRSpinner.getValue()).doubleValue()));
            });
        rSpinner.addChangeListener(
            e ->
                System.setProperty(
                    P2PSystemProperties.OVERLAY_SHORTCUT_KLEINBERG_R,
                    String.valueOf(((Number) rSpinner.getValue()).doubleValue())));
        nodeProbSpinner.addChangeListener(
            e -> {
              Object v = nodeProbSpinner.getValue();
              System.setProperty(
                  P2PSystemProperties.OVERLAY_SHORTCUT_NODE_PROBABILITY,
                  String.valueOf(
                      (v instanceof Number n)
                          ? n.doubleValue()
                          : Double.parseDouble(String.valueOf(v))));
            });
        p2pShortcutKleinbergRSpinner.setEnabled("kleinberg".equalsIgnoreCase(configuredStrategy));
      }

      // === Group 5: Network / LAN (only for P2P-LAN mode) ===
      if (MODE_P2P_LAN.equals(selectedMode)) {
        JPanel networkGroup = newGroupPanel("Network (LAN)");
        int networkRows = 0;
        networkRows +=
            addStandardParamRowIfPresent("p2pDiscoveryWaitMs", paramByName, networkGroup);
        networkRows += addPortParamRowIfPresent("p2pTcpPort", paramByName, networkGroup);
        networkRows += addPortParamRowIfPresent("p2pDiscoveryPort", paramByName, networkGroup);
        if (paramByName.containsKey("p2pMulticastA")) {
          JLabel label = new JLabel("p2pMulticastGroup");
          label.setName(P2P_MULTICAST_GROUP_FIELD + "Label");
          networkGroup.add(label);
          JTextField textField = new JTextField(buildMulticastGroupFromCurrentParameters());
          textField.setName(P2P_MULTICAST_GROUP_FIELD);
          textField.setToolTipText("IPv4 multicast group, z. B. 239.255.42.99");
          networkGroup.add(textField);
          inputParameterMap.put("p2pMulticastA", 239);
          inputParameterMap.put("p2pMulticastB", 255);
          inputParameterMap.put("p2pMulticastC", 42);
          inputParameterMap.put("p2pMulticastD", 99);
          networkRows++;
        }
        if (networkRows > 0) {
          networkGroup.setLayout(new GridLayout(networkRows, 2, GAP, GAP));
          algorithmInputs.add(networkGroup, fullWidthGbc());
        }
      }

      // === Group 6: Vehicle Position Revision ===
      JPanel positionRevisionGroup = newGroupPanel("Vehicle Position Revision");
      int posRevisionRows = 0;

      JLabel posThrottleLabel = new JLabel("Position revision throttle [ticks]");
      posThrottleLabel.setName("p2pPosRevThrottleLabel");
      positionRevisionGroup.add(posThrottleLabel);
      int defaultThrottle =
          Integer.parseInt(
              System.getProperty(
                  P2PSystemProperties.OVERLAY_POSITION_REVISION_THROTTLE_TICKS, "5"));
      SpinnerModel throttleModel = new SpinnerNumberModel(defaultThrottle, 0, Integer.MAX_VALUE, 1);
      p2pPositionRevisionThrottleSpinner = new JSpinner(throttleModel);
      configureIntegerSpinner(p2pPositionRevisionThrottleSpinner);
      p2pPositionRevisionThrottleSpinner.setName("p2pPositionRevisionThrottleTicks");
      p2pPositionRevisionThrottleSpinner.setToolTipText(
          "Throttle ticks before bumping vehicle position revision");
      p2pPositionRevisionThrottleSpinner.addChangeListener(
          e ->
              System.setProperty(
                  P2PSystemProperties.OVERLAY_POSITION_REVISION_THROTTLE_TICKS,
                  String.valueOf(
                      ((Number) p2pPositionRevisionThrottleSpinner.getValue()).longValue())));
      positionRevisionGroup.add(p2pPositionRevisionThrottleSpinner);
      posRevisionRows++;

      JLabel posMinMoveLabel = new JLabel("Position revision min move [m]");
      posMinMoveLabel.setName("p2pPosRevMinMoveLabel");
      positionRevisionGroup.add(posMinMoveLabel);
      int defaultMinMove =
          Integer.parseInt(
              System.getProperty(
                  P2PSystemProperties.OVERLAY_POSITION_REVISION_MIN_MOVE_METERS, "50"));
      SpinnerModel minMoveModel = new SpinnerNumberModel(defaultMinMove, 0, Integer.MAX_VALUE, 1);
      p2pPositionRevisionMinMoveSpinner = new JSpinner(minMoveModel);
      configureIntegerSpinner(p2pPositionRevisionMinMoveSpinner);
      p2pPositionRevisionMinMoveSpinner.setName("p2pPositionRevisionMinMoveMeters");
      p2pPositionRevisionMinMoveSpinner.setToolTipText(
          "Minimum move in meters to bump position revision");
      p2pPositionRevisionMinMoveSpinner.addChangeListener(
          e ->
              System.setProperty(
                  P2PSystemProperties.OVERLAY_POSITION_REVISION_MIN_MOVE_METERS,
                  String.valueOf(
                      ((Number) p2pPositionRevisionMinMoveSpinner.getValue()).intValue())));
      positionRevisionGroup.add(p2pPositionRevisionMinMoveSpinner);
      posRevisionRows++;

      if (posRevisionRows > 0) {
        positionRevisionGroup.setLayout(new GridLayout(posRevisionRows, 2, GAP, GAP));
        algorithmInputs.add(positionRevisionGroup, fullWidthGbc());
      }

      // === Group 7: Idle Vehicle Random Travel ===
      JPanel idleTravelGroup = newGroupPanel("Idle Vehicle Random Travel");
      int idleTravelRows = 0;

      JLabel idleEnabledLabel = new JLabel("Enabled");
      idleEnabledLabel.setName("p2pIdleRandomTravelLabel");
      idleTravelGroup.add(idleEnabledLabel);
      p2pIdleRandomTravelEnabledCheckBox = new JCheckBox();
      p2pIdleRandomTravelEnabledCheckBox.setName("p2pIdleRandomTravelEnabled");
      p2pIdleRandomTravelEnabledCheckBox.setToolTipText(
          "Enable idle vehicles to start traveling randomly");
      // Default to enabled so UI and JVM assume idle-random-travel on when no property provided
      boolean idleRandomTravelDefault =
          Boolean.parseBoolean(
              System.getProperty(P2PSystemProperties.VEHICLE_IDLE_RANDOM_TRAVEL_ENABLED, "true"));
      p2pIdleRandomTravelEnabledCheckBox.setSelected(idleRandomTravelDefault);
      p2pIdleRandomTravelEnabledCheckBox.addActionListener(
          e ->
              System.setProperty(
                  P2PSystemProperties.VEHICLE_IDLE_RANDOM_TRAVEL_ENABLED,
                  String.valueOf(p2pIdleRandomTravelEnabledCheckBox.isSelected())));
      idleTravelGroup.add(p2pIdleRandomTravelEnabledCheckBox);
      idleTravelRows++;

      JLabel idleThresholdLabel = new JLabel("Idle threshold [ticks]");
      idleThresholdLabel.setName("p2pIdleThresholdLabel");
      idleTravelGroup.add(idleThresholdLabel);
      long defaultIdleThreshold =
          Long.getLong(P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS, 60L);
      SpinnerModel idleThresholdModel =
          new SpinnerNumberModel(defaultIdleThreshold, 1L, Long.MAX_VALUE, 10L);
      p2pIdleThresholdSpinner = new JSpinner(idleThresholdModel);
      configureIntegerSpinner(p2pIdleThresholdSpinner);
      p2pIdleThresholdSpinner.setName("p2pIdleThresholdTicks");
      p2pIdleThresholdSpinner.setToolTipText("Ticks of idleness before random travel is triggered");
      p2pIdleThresholdSpinner.addChangeListener(
          e ->
              System.setProperty(
                  P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS,
                  String.valueOf(((Number) p2pIdleThresholdSpinner.getValue()).longValue())));
      idleTravelGroup.add(p2pIdleThresholdSpinner);
      idleTravelRows++;

      JLabel idleCheckThrottleLabel = new JLabel("Idle check throttle [ticks]");
      idleCheckThrottleLabel.setName("p2pIdleCheckThrottleLabel");
      idleTravelGroup.add(idleCheckThrottleLabel);
      long defaultIdleCheckThrottle =
          Long.getLong(P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS, 5L);
      SpinnerModel idleCheckThrottleModel =
          new SpinnerNumberModel(defaultIdleCheckThrottle, 1L, Long.MAX_VALUE, 1L);
      p2pIdleCheckThrottleSpinner = new JSpinner(idleCheckThrottleModel);
      configureIntegerSpinner(p2pIdleCheckThrottleSpinner);
      p2pIdleCheckThrottleSpinner.setName("p2pIdleCheckThrottleTicks");
      p2pIdleCheckThrottleSpinner.setToolTipText(
          "Throttle idle checks to avoid performance overhead");
      p2pIdleCheckThrottleSpinner.addChangeListener(
          e ->
              System.setProperty(
                  P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS,
                  String.valueOf(((Number) p2pIdleCheckThrottleSpinner.getValue()).longValue())));
      idleTravelGroup.add(p2pIdleCheckThrottleSpinner);
      idleTravelRows++;

      JLabel randomDistanceLabel = new JLabel("Random travel max distance [m]");
      randomDistanceLabel.setName("p2pRandomTravelMaxDistanceLabel");
      idleTravelGroup.add(randomDistanceLabel);
      int defaultRandomTravelMaxDistance =
          Integer.getInteger(P2PSystemProperties.VEHICLE_RANDOM_TRAVEL_MAX_DISTANCE_METERS, 20000);
      SpinnerModel randomTravelMaxDistanceModel =
          new SpinnerNumberModel(defaultRandomTravelMaxDistance, 1, Integer.MAX_VALUE, 100);
      p2pRandomTravelMaxDistanceSpinner = new JSpinner(randomTravelMaxDistanceModel);
      configureIntegerSpinner(p2pRandomTravelMaxDistanceSpinner);
      p2pRandomTravelMaxDistanceSpinner.setName("p2pRandomTravelMaxDistanceMeters");
      p2pRandomTravelMaxDistanceSpinner.setToolTipText(
          "Maximum distance for random travel from current position (meters)");
      p2pRandomTravelMaxDistanceSpinner.addChangeListener(
          e ->
              System.setProperty(
                  P2PSystemProperties.VEHICLE_RANDOM_TRAVEL_MAX_DISTANCE_METERS,
                  String.valueOf(
                      ((Number) p2pRandomTravelMaxDistanceSpinner.getValue()).intValue())));
      idleTravelGroup.add(p2pRandomTravelMaxDistanceSpinner);
      idleTravelRows++;

      if (idleTravelRows > 0) {
        idleTravelGroup.setLayout(new GridLayout(idleTravelRows, 2, GAP, GAP));
        algorithmInputs.add(idleTravelGroup, fullWidthGbc());
      }

      // === Group 8: Remaining parameters not in any named group ===
      Set<String> alreadyRendered = new HashSet<>(P2P_PARAMETER_DISPLAY_ORDER);
      alreadyRendered.addAll(
          Set.of(
              "p2pOverlayMaxDistanceFactor",
              "p2pMulticastB",
              "p2pMulticastC",
              "p2pMulticastD",
              "p2pEmbeddedSimulation"));
      JPanel otherGroup = newGroupPanel("Other");
      int otherRows = 0;
      for (AlgorithmParameter parameter : parameters) {
        if (alreadyRendered.contains(parameter.name())) continue;
        if (!shouldShowAlgorithmParameter(parameter.name())) continue;
        otherRows += addStandardRow(parameter, otherGroup);
      }
      if (otherRows > 0) {
        otherGroup.setLayout(new GridLayout(otherRows, 2, GAP, GAP));
        algorithmInputs.add(otherGroup, fullWidthGbc());
      }

      // Vertical filler so groups don't stretch to fill remaining space
      GridBagConstraints fillerGbc = fullWidthGbc();
      fillerGbc.weighty = 1.0;
      algorithmInputs.add(new JPanel(), fillerGbc);

    } else {
      // Non-P2P mode: render all parameters flat in natural order
      boolean multicastFieldAdded = false;
      for (AlgorithmParameter parameter : parameters) {
        if (!shouldShowAlgorithmParameter(parameter.name())) continue;
        if (isP2PMulticastOctet(parameter.name())) {
          if (multicastFieldAdded) continue;
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
          rowCount += addPortRowDirect(parameter, algorithmInputs);
          continue;
        }
        rowCount += addStandardRow(parameter, algorithmInputs);
      }
      algorithmInputs.setLayout(new GridLayout(Math.max(1, rowCount), 2, GAP, GAP));
    }

    SwingUtilities.updateComponentTreeUI(worldInputs);
    applyModeToUi();
  }

  /**
   * GridBagConstraints for a group panel that fills the full width of algorithmInputs. Each call
   * creates a new instance (GBC is mutable).
   */
  private GridBagConstraints fullWidthGbc() {
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.gridx = 0;
    gbc.gridy = GridBagConstraints.RELATIVE;
    gbc.gridwidth = GridBagConstraints.REMAINDER;
    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 1.0;
    gbc.weighty = 0.0;
    gbc.insets = new Insets(0, 0, GAP, 0);
    return gbc;
  }

  /** Creates a group sub-panel with a TitledBorder. Content is added before calling setLayout. */
  private JPanel newGroupPanel(String title) {
    JPanel panel = new JPanel();
    panel.setBorder(new TitledBorder(title));
    return panel;
  }

  /**
   * Visits every component in {@code algorithmInputs} and its sub-panels depth-first, passing each
   * to {@code visitor}. Used by initializeSimulation, copy/paste etc.
   */
  private void forEachAlgorithmComponent(java.util.function.Consumer<Component> visitor) {
    forEachComponent(algorithmInputs, visitor);
  }

  private void forEachComponent(
      Container container, java.util.function.Consumer<Component> visitor) {
    for (Component comp : container.getComponents()) {
      visitor.accept(comp);
      if (comp instanceof Container child) {
        forEachComponent(child, visitor);
      }
    }
  }

  /** Adds a standard spinner row for a named parameter if it exists and should be shown. */
  private int addStandardParamRowIfPresent(
      String name, Map<String, AlgorithmParameter> paramByName, JPanel target) {
    AlgorithmParameter p = paramByName.get(name);
    if (p == null || !shouldShowAlgorithmParameter(name)) return 0;
    return addStandardRow(p, target);
  }

  /** Adds a port text-field row for a named parameter if it exists and should be shown. */
  private int addPortParamRowIfPresent(
      String name, Map<String, AlgorithmParameter> paramByName, JPanel target) {
    AlgorithmParameter p = paramByName.get(name);
    if (p == null || !shouldShowAlgorithmParameter(name)) return 0;
    return addPortRowDirect(p, target);
  }

  /** Adds a standard label + spinner row for the given parameter into {@code target}. Returns 1. */
  private int addStandardRow(AlgorithmParameter parameter, JPanel target) {
    JLabel label = new JLabel(displayLabelForParameter(parameter.name()));
    label.setName(parameter.name() + "Label");
    target.add(label);
    int defaultValue = parameter.defaultValue() != null ? parameter.defaultValue() : 1;
    SpinnerModel model = new SpinnerNumberModel(defaultValue, 0, Integer.MAX_VALUE, 1);
    JSpinner spinner = new JSpinner(model);
    configureIntegerSpinner(spinner);
    label.setLabelFor(spinner);
    spinner.setName(parameter.name());
    spinner.setToolTipText(parameterTooltip(parameter.name()));
    target.add(spinner);
    inputParameterMap.put(parameter.name(), 0);
    return 1;
  }

  /** Adds a label + text-field row for a port parameter into {@code target}. Returns 1. */
  private int addPortRowDirect(AlgorithmParameter parameter, JPanel target) {
    JLabel label = new JLabel(displayLabelForParameter(parameter.name()));
    label.setName(parameter.name() + "Label");
    target.add(label);
    int defaultValue = parameter.defaultValue() != null ? parameter.defaultValue() : 1;
    JTextField textField = new JTextField(String.valueOf(defaultValue));
    textField.setName(parameter.name());
    textField.setToolTipText(parameterTooltip(parameter.name()));
    target.add(textField);
    inputParameterMap.put(parameter.name(), 0);
    return 1;
  }

  private boolean shouldShowAlgorithmParameter(String parameterName) {
    if ("p2pEmbeddedSimulation".equals(parameterName)) {
      return false;
    }
    if (!isP2PModeSelected()) {
      return true;
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
          Object val = spinner.getValue();
          int intVal =
              (val instanceof Number number)
                  ? number.intValue()
                  : Integer.parseInt(String.valueOf(val));
          allParameterMap.put(spinner.getName(), intVal);
        }
        if (component instanceof JComboBox<?> combo && combo.getName() != null) {
          allParameterMap.put(combo.getName(), combo.getSelectedIndex());
        }
      }

      forEachAlgorithmComponent(
          component -> {
            if (component instanceof JSpinner spinner) {
              Object val = spinner.getValue();
              int intVal =
                  (val instanceof Number number)
                      ? number.intValue()
                      : Integer.parseInt(String.valueOf(val));
              allParameterMap.put(spinner.getName(), intVal);
              algorithmParameterMap.put(spinner.getName(), intVal);
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
          });

      if (isP2PModeSelected()) {
        String selectedMode =
            getComponentByName("simulationModeBox") instanceof JComboBox<?> combo
                ? Objects.toString(combo.getSelectedItem(), MODE_LOCAL)
                : MODE_LOCAL;
        int embeddedValue = MODE_P2P_SIMULATED.equals(selectedMode) ? 1 : 0;
        allParameterMap.put("p2pEmbeddedSimulation", embeddedValue);
        algorithmParameterMap.put("p2pEmbeddedSimulation", embeddedValue);
        applySelectedP2PVehicleStrategy();
      }

      // Apply UI-controlled P2P system properties for position revision throttling
      if (p2pPositionRevisionThrottleSpinner != null) {
        Object val = p2pPositionRevisionThrottleSpinner.getValue();
        System.setProperty(
            P2PSystemProperties.OVERLAY_POSITION_REVISION_THROTTLE_TICKS,
            String.valueOf(((Number) val).longValue()));
      }
      if (p2pPositionRevisionMinMoveSpinner != null) {
        Object val = p2pPositionRevisionMinMoveSpinner.getValue();
        System.setProperty(
            P2PSystemProperties.OVERLAY_POSITION_REVISION_MIN_MOVE_METERS,
            String.valueOf(((Number) val).intValue()));
      }
      // Apply UI-controlled P2P overlay shortcut strategy and Kleinberg r
      if (p2pShortcutStrategyBox != null) {
        Object sel = p2pShortcutStrategyBox.getSelectedItem();
        System.setProperty(
            P2PSystemProperties.OVERLAY_SHORTCUT_STRATEGY, Objects.toString(sel, "kleinberg"));
      }
      if (p2pShortcutKleinbergRSpinner != null) {
        Object rval = p2pShortcutKleinbergRSpinner.getValue();
        System.setProperty(
            P2PSystemProperties.OVERLAY_SHORTCUT_KLEINBERG_R,
            String.valueOf(((Number) rval).doubleValue()));
      }
      if (p2pShortcutNodeProbabilitySpinner != null) {
        Object nval = p2pShortcutNodeProbabilitySpinner.getValue();
        double dval =
            (nval instanceof Number num)
                ? num.doubleValue()
                : Double.parseDouble(String.valueOf(nval));
        System.setProperty(
            P2PSystemProperties.OVERLAY_SHORTCUT_NODE_PROBABILITY,
            String.valueOf(Math.max(0.0, Math.min(1.0, dval))));
      }

      // Apply UI-controlled idle vehicle random travel properties
      if (p2pIdleRandomTravelEnabledCheckBox != null) {
        System.setProperty(
            P2PSystemProperties.VEHICLE_IDLE_RANDOM_TRAVEL_ENABLED,
            String.valueOf(p2pIdleRandomTravelEnabledCheckBox.isSelected()));
      }
      if (p2pIdleThresholdSpinner != null) {
        Object val = p2pIdleThresholdSpinner.getValue();
        System.setProperty(
            P2PSystemProperties.VEHICLE_IDLE_THRESHOLD_TICKS,
            String.valueOf(((Number) val).longValue()));
      }
      if (p2pIdleCheckThrottleSpinner != null) {
        Object val = p2pIdleCheckThrottleSpinner.getValue();
        System.setProperty(
            P2PSystemProperties.VEHICLE_IDLE_CHECK_THROTTLE_TICKS,
            String.valueOf(((Number) val).longValue()));
      }
      if (p2pRandomTravelMaxDistanceSpinner != null) {
        Object val = p2pRandomTravelMaxDistanceSpinner.getValue();
        System.setProperty(
            P2PSystemProperties.VEHICLE_RANDOM_TRAVEL_MAX_DISTANCE_METERS,
            String.valueOf(((Number) val).intValue()));
      }

      if (p2pIdleRandomTravelEnabledCheckBox != null) {
        int enabled = p2pIdleRandomTravelEnabledCheckBox.isSelected() ? 1 : 0;
        allParameterMap.put("p2pIdleTravelEnabled", enabled);
        algorithmParameterMap.put("p2pIdleTravelEnabled", enabled);
      }
      if (p2pIdleThresholdSpinner != null) {
        Object v = p2pIdleThresholdSpinner.getValue();
        int thresh = (int) Math.max(1L, ((Number) v).longValue());
        allParameterMap.put("p2pIdleTravelThresholdTicks", thresh);
        algorithmParameterMap.put("p2pIdleTravelThresholdTicks", thresh);
      }

      inputParameterMap.putAll(allParameterMap);

      Object ws = allParameterMap.getOrDefault("worldSeed", 0);
      System.setProperty("worldSeed", String.valueOf(ws));

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

  private void applySelectedP2PVehicleStrategy() {
    String selected =
        p2pVehicleStrategyBox != null
            ? Objects.toString(p2pVehicleStrategyBox.getSelectedItem(), P2P_STRATEGY_NEAREST)
            : P2P_STRATEGY_NEAREST;
    System.setProperty(P2P_VEHICLE_STRATEGY_PROPERTY, normalizedStrategyKey(selected));
  }

  private String normalizedStrategyKey(String value) {
    String key = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    return P2P_STRATEGY_GREEDY.equals(key) ? P2P_STRATEGY_GREEDY : P2P_STRATEGY_NEAREST;
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
    int port = Integer.parseInt(value == null ? "" : value.trim());
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("Port out of range for " + fieldName + ": " + port);
    }
    return port;
  }

  private String displayLabelForParameter(String parameterName) {
    return switch (parameterName) {
      case "p2pFixedSearchRadius" -> "Client RQS radius";
      case "p2pOverlayMinNeighbors" -> "Overlay min neighbors";
      case "p2pOverlayMaxNeighbors" -> "Overlay max neighbors";
      case "p2pOverlayMaxDistanceFactor" -> "Overlay max distance factor";
      case "p2pOverlayShortcuts" -> "Overlay shortcuts";
      case "p2pRequestForwardHops" -> "Flood TTL (Hops)";
      case "p2pRequestRepublishTicks" -> "Republish throttle [ticks]";
      case "p2pTopologyScanTicks" -> "Topology scan interval [ticks]";
      case "p2pDiscoveryWaitMs" -> "Discovery wait [ms]";
      default -> parameterName;
    };
  }

  private String parameterTooltip(String parameterName) {
    return switch (parameterName) {
      case "p2pFixedSearchRadius" ->
          "Fixed radius around the client used for RQS seeding (world units), independent from trip distance.";
      case "p2pOverlayMinNeighbors" ->
          "Minimum overlay neighbors per node; additional neighbors can appear within distance bound.";
      case "p2pOverlayMaxNeighbors" ->
          "Maximum primary overlay neighbors per node; minNeighbors is always respected. Shortcuts and pinned collector may exceed this cap.";
      case "p2pOverlayMaxDistanceFactor" ->
          "Maximum geo distance factor for neighbor selection (0 = unlimited).";
      case "p2pOverlayShortcuts" -> "Number of additional small-world shortcut links per node.";
      case "p2pRequestForwardHops" -> "TTL for flooding request forwarding in hops.";
      case "p2pRequestRepublishTicks" ->
          "Minimum simulation ticks before an unaccepted client request is republished.";
      case "p2pTopologyScanTicks" -> "How many simulation ticks between automatic topology scans.";
      case "p2pDiscoveryWaitMs" -> "LAN mode only: waiting time for peer discovery during init.";
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
    if (p2pVehicleStrategyBox != null) {
      props.setProperty(
          P2P_VEHICLE_STRATEGY_CONFIG_KEY,
          Objects.toString(p2pVehicleStrategyBox.getSelectedItem(), P2P_STRATEGY_NEAREST));
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

    // dynamic Algorithm Parameters (deep traversal for sub-panel/TitledBorder groups)
    forEachAlgorithmComponent(
        comp -> {
          if (comp instanceof JSpinner spinner && comp.getName() != null) {
            props.setProperty(spinner.getName(), spinner.getValue().toString());
          }
          if (comp instanceof JTextField textField && comp.getName() != null) {
            props.setProperty(textField.getName(), textField.getText());
          }
          if (comp instanceof JComboBox<?> combo && comp.getName() != null) {
            props.setProperty(combo.getName(), Objects.toString(combo.getSelectedItem(), ""));
          }
        });

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
      String pastedStrategy = props.getProperty(P2P_VEHICLE_STRATEGY_CONFIG_KEY);
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

      if (p2pVehicleStrategyBox != null && pastedStrategy != null) {
        p2pVehicleStrategyBox.setSelectedItem(normalizedStrategyKey(pastedStrategy));
        applySelectedP2PVehicleStrategy();
      }

      for (String name : props.stringPropertyNames()) {
        if (name.equals("algorithmSelectionBox") || name.equals(P2P_VEHICLE_STRATEGY_CONFIG_KEY)) {
          continue; // already handled above
        }

        String effectiveName = name;
        String valueStr = props.getProperty(name);
        boolean valueSet = false;

        // 1. try: set algorithm parameters (deep traversal for sub-panel/TitledBorder groups)
        if (algorithmInputs != null) {
          final String lookupName = effectiveName;
          final String lookupValue = valueStr;
          final boolean[] found = {false};
          forEachAlgorithmComponent(
              compInAlgoPanel -> {
                if (found[0]) return;
                if (compInAlgoPanel instanceof JSpinner
                    && lookupName.equals(compInAlgoPanel.getName())) {
                  ((JSpinner) compInAlgoPanel).setValue(Integer.parseInt(lookupValue));
                  log.trace("Set ALGORITHM JSpinner '{}' to '{}'", lookupName, lookupValue);
                  found[0] = true;
                } else if (compInAlgoPanel instanceof JTextField textField
                    && lookupName.equals(compInAlgoPanel.getName())) {
                  textField.setText(lookupValue);
                  found[0] = true;
                } else if (compInAlgoPanel instanceof JComboBox<?> combo
                    && lookupName.equals(compInAlgoPanel.getName())) {
                  try {
                    combo.setSelectedItem(lookupValue);
                    log.trace("Set ALGORITHM JComboBox '{}' to '{}'", lookupName, lookupValue);
                    found[0] = true;
                  } catch (Exception ex) {
                    log.warn(
                        "Could not set '{}' to '{}' for ALGORITHM combo '{}'",
                        lookupValue,
                        lookupName,
                        ex.getMessage());
                  }
                } else if (compInAlgoPanel instanceof JSlider
                    && lookupName.equals(compInAlgoPanel.getName())) {
                  ((JSlider) compInAlgoPanel).setValue(Integer.parseInt(lookupValue));
                  log.trace("Set ALGORITHM JSlider '{}' to '{}'", lookupName, lookupValue);
                  found[0] = true;
                }
              });
          valueSet = found[0];
        }

        // 2. try: set global parameters
        if (!valueSet) {
          Component generalComp = getComponentByName(effectiveName);
          if (generalComp instanceof JSpinner) {
            ((JSpinner) generalComp).setValue(Integer.parseInt(valueStr));
            log.trace("Set GENERAL JSpinner '{}' to '{}'", name, valueStr);
            valueSet = true;
          }
          if (generalComp instanceof JSlider) {
            ((JSlider) generalComp).setValue(Integer.parseInt(valueStr));
            log.trace("Set GENERAL JSlider '{}' to '{}'", name, valueStr);
            valueSet = true;
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
    private JButton legendToggleButton;
    private boolean showLocalCollector;
    private double zoom = 0.82;
    private double panX;
    private double panY;
    private Point lastDragPoint;
    private String draggedNodeId;
    private final Map<String, Point2D.Double> manualNodeOffsets = new HashMap<>();
    private final Map<String, Point> renderedNodePositions = new HashMap<>();

    private P2PTopologyPanel() {
      setBackground(Color.WHITE);
      setLayout(null);
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
          this.snapshot.nodes().stream()
              .map(P2PNetworkNodeSnapshot::id)
              .collect(java.util.stream.Collectors.toSet());
      manualNodeOffsets.keySet().removeIf(id -> !aliveIds.contains(id));
      updateLegendToggleBounds();
      repaint();
    }

    private void setLegendToggleButton(JButton button) {
      if (legendToggleButton != null) {
        remove(legendToggleButton);
      }
      legendToggleButton = button;
      if (legendToggleButton != null) {
        legendToggleButton.setFocusable(false);
        add(legendToggleButton);
      }
      updateLegendToggleBounds();
      revalidate();
      repaint();
    }

    private void setShowLocalCollector(boolean showLocalCollector) {
      this.showLocalCollector = showLocalCollector;
      updateLegendToggleBounds();
      repaint();
    }

    private boolean isShowLocalCollector() {
      return showLocalCollector;
    }

    private Point panelCenter() {
      return new Point(
          getWidth() / 2 + (int) Math.round(panX), getHeight() / 2 + (int) Math.round(panY));
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
      updateLegendToggleBounds();
      Graphics2D g2 = (Graphics2D) g.create();
      try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        List<P2PNetworkNodeSnapshot> nodes = snapshot.nodes();
        if (nodes == null || nodes.isEmpty()) {
          g2.setColor(Color.GRAY);
          g2.drawString("No P2P nodes discovered yet.", 16, 24);
          return;
        }

        List<P2PNetworkNodeSnapshot> visibleNodes =
            nodes.stream().filter(node -> showLocalCollector || !node.localNode()).toList();
        if (visibleNodes.isEmpty()) {
          g2.setColor(Color.GRAY);
          g2.drawString("Collector hidden; no other nodes visible.", 16, 24);
          return;
        }

        int width = getWidth();
        int height = getHeight();
        int centerX = width / 2 + (int) Math.round(panX);
        int centerY = height / 2 + (int) Math.round(panY);
        Point center = new Point(centerX, centerY);
        int radius = Math.max(60, Math.min(width, height) / 2 - 85);

        P2PNetworkNodeSnapshot local =
            nodes.stream().filter(P2PNetworkNodeSnapshot::localNode).findFirst().orElse(null);
        boolean drawLocal = showLocalCollector && local != null;
        List<P2PNetworkNodeSnapshot> peers =
            visibleNodes.stream()
                .filter(node -> !drawLocal || !node.localNode())
                .sorted(
                    Comparator.comparing(P2PNetworkNodeSnapshot::role)
                        .thenComparing(P2PNetworkNodeSnapshot::id))
                .toList();

        renderedNodePositions.clear();
        if (drawLocal) {
          renderedNodePositions.put(local.id(), new Point(centerX, centerY));
        }

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

        if (drawLocal) {
          drawNode(g2, local, centerX, centerY);
        }
        drawLegendAndCounts(g2, visibleNodes, drawLocal);
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

    private void updateLegendToggleBounds() {
      if (legendToggleButton == null) {
        return;
      }
      legendToggleButton.setVisible(isLegendVisible());
      int x = 12;
      int y = 34;
      int buttonWidth = 120;
      int buttonHeight = 22;
      legendToggleButton.setBounds(x + 130, y - 14, buttonWidth, buttonHeight);
    }

    private boolean isLegendVisible() {
      return snapshot != null && snapshot.nodes() != null && !snapshot.nodes().isEmpty();
    }

    private void drawLegendAndCounts(
        Graphics2D g2, List<P2PNetworkNodeSnapshot> nodes, boolean drawLocal) {
      long vehicleCount = nodes.stream().filter(n -> "VEHICLE".equals(n.role())).count();

      int x = 12;
      int y = 34;
      g2.setColor(new Color(250, 250, 250, 235));
      g2.fillRoundRect(x - 10, y - 18, 270, 78, 12, 12);
      g2.setColor(new Color(205, 205, 205));
      g2.drawRoundRect(x - 10, y - 18, 270, 78, 12, 12);

      if (drawLocal) {
        drawLegendEntry(g2, x, y, colorForRole("LOCAL", true), "Collector/local");
      }
      drawLegendEntry(
          g2, x, y + (drawLocal ? 18 : 0), colorForRole("VEHICLE", false), "Vehicle peers");

      g2.setColor(Color.DARK_GRAY);
      g2.drawString("Counts: VEHICLE=" + vehicleCount, x, y + 38);
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

    private void drawEdge(
        Graphics2D g2, P2PNetworkEdgeSnapshot edge, int x1, int y1, int x2, int y2) {
      Stroke previous = g2.getStroke();
      if (edge.shortcut()) {
        g2.setStroke(
            new BasicStroke(
                1.6f,
                BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND,
                10f,
                new float[] {6f, 4f},
                0f));
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
