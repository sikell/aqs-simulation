package de.sikeller.aqs.visualization.drawing;

import java.awt.*;
import javax.swing.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VisualizationUtils {
  private static Font defaultFont;
  private static Font lightFont;

  private static final Color lightBlue = new Color(13, 202, 240);
  private static final Color green = new Color(25, 135, 84);
  private static final Color yellow = new Color(251, 148, 3);

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

  public static Color todoColor() {
    return lightBlue;
  }

  public static Color successColor() {
    return green;
  }

  public static Color taxiColor() {
    return yellow;
  }
}
