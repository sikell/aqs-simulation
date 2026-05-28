package de.sikeller.aqs.visualization.controls;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.TitledBorder;

import de.sikeller.aqs.visualization.drawing.VisualizationProperties;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VisualizationControl extends AbstractControl {
  private final VisualizationProperties properties;
  private JCheckBox showP2PRqsRangeCheckBox;

  public VisualizationControl(VisualizationProperties properties) {
    this.properties = properties;
    add(setup());
  }

  private JPanel setup() {
    var controls = new JPanel();
    controls.setBorder(new TitledBorder("Visualization Control"));
    controls.setLayout(new GridLayout(0, 2, GAP, GAP));

    controls.add(
            checkBox(
                    "Enable realtime visualization",
                    "enableRealTimeVisualization",
                    "Enable the visualization feature - or disable completely for performance reasons",
                    properties.isEnableRealtimeVisualization(),
                    properties::setEnableRealtimeVisualization));
    controls.add(placeholder());

    controls.add(label("Scale", "scaleLabel"));

    controls.add(
        slider(
            "scaleSlider",
            "Scale the rendered visualization.",
            1,
            10,
            properties.getScale(),
            properties::setScale));

    controls.add(
        checkBox(
            "Show client paths",
            "showClientPaths",
            "Display a path between client start and target.",
            properties.isShowClientPaths(),
            properties::setShowClientPaths));

    controls.add(
            checkBox(
                    "Show client positions",
                    "showClientPositions",
                    "Display the client positions.",
                    properties.isShowClientPositions(),
                    properties::setShowClientPositions));

    controls.add(
        checkBox(
            "Show client names",
            "showClientNames",
            "Display a the client name.",
            properties.isShowClientNames(),
            properties::setShowClientNames));

    controls.add(
        checkBox(
            "Show finished clients",
            "showFinishedClients",
            "Show clients which are finished.",
            properties.isShowFinishedClients(),
            properties::setShowFinishedClients));

    controls.add(
        checkBox(
            "Show taxi paths",
            "showTaxiPaths",
            "Display the planned path for each taxi.",
            properties.isShowTaxiPaths(),
            properties::setShowTaxiPaths));

    controls.add(
        checkBox(
            "Show taxi positions",
            "showTaxiPositions",
            "Display the taxi positions.",
            properties.isShowTaxiPositions(),
            properties::setShowTaxiPositions));

    controls.add(
        checkBox(
            "Show taxi names",
            "showTaxiNames",
            "Display the taxi names.",
            properties.isShowTaxiNames(),
            properties::setShowTaxiNames));

    controls.add(
        checkBox(
            "Show scale info",
            "showScale",
            "Display a scale info.",
            properties.isShowScale(),
            properties::setShowScale));

    controls.add(
        checkBox(
            "Show current time",
            "showTime",
            "Display the current time.",
            properties.isShowTime(),
            properties::setShowTime));


    showP2PRqsRangeCheckBox =
        checkBox(
            "Show RQS range overlay",
            "showP2PRqsRange",
            "Display the RQS range overlay in the live simulation view.",
            properties.isShowRqsRecognitionRange(),
            properties::setShowRqsRecognitionRange);
    controls.add(showP2PRqsRangeCheckBox);

    controls.add(
        checkBox(
            "Show taxi topology links",
            "showTaxiTopologyLinks",
            "Display taxi-to-taxi P2P overlay links on the simulation map.",
            properties.isShowTaxiTopologyLinks(),
            properties::setShowTaxiTopologyLinks));

    controls.add(
        checkBox(
            "Color clients by taxi knowledge",
            "showClientKnowledgeColors",
            "Color clients by taxis that currently know them; multiple taxis are shown as radial color segments.",
            properties.isShowClientKnowledgeColors(),
            properties::setShowClientKnowledgeColors));

    controls.add(
        checkBox(
            "Show page-rank HQ markers",
            "showPageRankHq",
            "Display the average pickup position (page-rank HQ) for each taxi as a small triangle.",
            properties.isShowPageRankHq(),
            properties::setShowPageRankHq));

    return controls;
  }

  public void setP2PModeUiState(boolean p2pMode) {
    if (showP2PRqsRangeCheckBox == null) {
      return;
    }
    showP2PRqsRangeCheckBox.setVisible(p2pMode);
    showP2PRqsRangeCheckBox.setEnabled(p2pMode);
    if (!p2pMode) {
      showP2PRqsRangeCheckBox.setSelected(false);
      properties.setShowRqsRecognitionRange(false);
    }
  }
}
