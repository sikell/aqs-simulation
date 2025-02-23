package de.sikeller.aqs.visualization.drawing;

import lombok.Data;

@Data
public class VisualizationProperties
    implements TaxiDrawing.TaxiDrawingProperties, ClientDrawing.ClientDrawingProperties {
  private boolean showClientPaths = true;
  private boolean showClientNames = true;
  private boolean showTaxiPaths = true;
  private boolean showTaxiNames = true;
  private boolean showFinishedClients = false;
  private int scale = 6;
}
