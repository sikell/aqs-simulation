package de.sikeller.aqs.visualization.drawing;

import lombok.Data;

@Data
public class VisualizationProperties
    implements TaxiDrawing.TaxiDrawingProperties,
        ClientDrawing.ClientDrawingProperties,
        BackgroundDrawing.BackgroundDrawingProperties {
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
}
