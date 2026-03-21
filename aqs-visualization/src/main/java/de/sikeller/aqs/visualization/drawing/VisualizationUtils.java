package de.sikeller.aqs.visualization.drawing;

import java.awt.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VisualizationUtils {
  private static Font defaultFont;
  private static Font lightFont;

  private static final Color lightBlue = new Color(13, 202, 240);
  private static final Color green = new Color(25, 135, 84);
  private static final Color yellow = new Color(251, 148, 3);
  private static final Map<String, Color> taxiColorsById = new ConcurrentHashMap<>();
  private static float taxiHueCursor = (float) Math.random();

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
    return taxiColor("taxi-default");
  }

  public static Color taxiColor(String taxiId) {
    if (taxiId == null || taxiId.isBlank()) {
      return yellow;
    }
    return taxiColorsById.computeIfAbsent(taxiId, ignored -> nextTaxiColor());
  }

  public static synchronized void resetTaxiColors() {
    taxiColorsById.clear();
    taxiHueCursor = (float) Math.random();
  }

  private static synchronized Color nextTaxiColor() {
    // Golden-ratio stepping spreads hues and avoids near-identical neighbors.
    taxiHueCursor = (taxiHueCursor + 0.61803398875f) % 1.0f;
    return Color.getHSBColor(taxiHueCursor, 0.78f, 0.95f);
  }
}
