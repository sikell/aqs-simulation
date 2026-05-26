package de.sikeller.aqs.visualization.drawing;

import de.sikeller.aqs.model.P2PNetworkSnapshot;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class VisualizationProperties
    implements TaxiDrawing.TaxiDrawingProperties,
        ClientDrawing.ClientDrawingProperties,
        BackgroundDrawing.BackgroundDrawingProperties {
  private boolean showPageRankHq = true;
  private Map<String, int[]> taxiPageRankHqPositions = Map.of();
  private boolean showClientPaths = false;
  private boolean showClientNames = false;
  private boolean showClientPositions = true;
  private boolean showTaxiPaths = false;
  private boolean showTaxiPositions = true;
  private boolean showTaxiNames = false;
  private boolean showFinishedClients = false;
  private boolean showScale = true;
  private boolean showTime = true;
  private int scale = 4;
  private boolean showRqsRecognitionRange = true;
  private int rqsRecognitionRadius = 5000;
  private boolean showTaxiTopologyLinks = true;
  private P2PNetworkSnapshot p2pNetworkSnapshot = P2PNetworkSnapshot.empty();
  private boolean showClientKnowledgeColors = true;
  private Map<String, List<String>> taxiKnownClientIds = Map.of();
}
