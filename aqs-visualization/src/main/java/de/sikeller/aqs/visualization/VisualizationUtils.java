package de.sikeller.aqs.visualization;

import java.awt.*;
import javax.swing.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VisualizationUtils {
  private static Font defaultFont;
  private static Font lightFont;

  public static Font defaultFont() {
    if (defaultFont == null) {
      defaultFont = UIManager.getFont("defaultFont");
    }
    return defaultFont;
  }

  public static Font smallFont() {
    if (lightFont == null) {
      lightFont = UIManager.getFont("light.font");
    }
    return lightFont;
  }
}
