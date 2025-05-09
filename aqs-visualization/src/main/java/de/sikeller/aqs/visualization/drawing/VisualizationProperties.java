package de.sikeller.aqs.visualization.drawing;

import lombok.Data;

@Data
public class VisualizationProperties
    implements TaxiDrawing.TaxiDrawingProperties,
        ClientDrawing.ClientDrawingProperties,
        BackgroundDrawing.BackgroundDrawingProperties {
  private boolean showClientPaths = true;
  private boolean showClientNames = true;
  private boolean showClientPositions = true;
  private boolean showTaxiPaths = true;
  private boolean showTaxiPositions = true;
  private boolean showTaxiNames = true;
  private boolean showFinishedClients = false;
  private boolean showScale = true;
  private boolean showTime = true;
  private int scale = 6;
}
