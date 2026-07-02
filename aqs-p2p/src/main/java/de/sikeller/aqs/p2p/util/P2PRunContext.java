package de.sikeller.aqs.p2p.util;

import java.util.HashMap;
import java.util.Map;

public final class P2PRunContext {
  private static final ThreadLocal<Long> WORLD_SEED = new ThreadLocal<>();
  private static final ThreadLocal<Map<String, String>> PROPERTIES = new ThreadLocal<>();

  private P2PRunContext() {}

  public static void begin(long seed) {
    WORLD_SEED.set(seed);
    PROPERTIES.set(new HashMap<>());
  }

  public static void setWorldSeed(long seed) {
    if (PROPERTIES.get() == null) {
      PROPERTIES.set(new HashMap<>());
    }
    WORLD_SEED.set(seed);
  }

  public static long worldSeed() {
    Long seed = WORLD_SEED.get();
    return seed != null ? seed : Long.getLong("worldSeed", 0L);
  }

  public static void setProperty(String key, String value) {
    Map<String, String> properties = PROPERTIES.get();
    if (properties == null) {
      if (value == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, value);
      }
      return;
    }
    if (value == null) {
      properties.remove(key);
    } else {
      properties.put(key, value);
    }
  }

  public static boolean isActive() {
    return PROPERTIES.get() != null;
  }

  public static String getProperty(String key) {
    Map<String, String> properties = PROPERTIES.get();
    return properties != null && properties.containsKey(key) ? properties.get(key) : System.getProperty(key);
  }

  public static String getProperty(String key, String defaultValue) {
    String value = getProperty(key);
    return value == null ? defaultValue : value;
  }

  public static long getLong(String key, long defaultValue) {
    try {
      return Long.parseLong(getProperty(key, String.valueOf(defaultValue)).trim());
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  public static int getInt(String key, int defaultValue) {
    try {
      return Integer.parseInt(getProperty(key, String.valueOf(defaultValue)).trim());
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  public static double getDouble(String key, double defaultValue) {
    try {
      return Double.parseDouble(getProperty(key, String.valueOf(defaultValue)).trim());
    } catch (NumberFormatException ex) {
      return defaultValue;
    }
  }

  public static boolean getBoolean(String key, boolean defaultValue) {
    String value = getProperty(key);
    return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
  }

  public static void clear() {
    WORLD_SEED.remove();
    PROPERTIES.remove();
  }
}
