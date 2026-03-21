package de.sikeller.aqs.p2p.service;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class KeyValuePayload {
  private KeyValuePayload() {}

  public static Map<String, String> parse(String payload) {
    Map<String, String> values = new HashMap<>();
    if (payload == null || payload.isBlank()) {
      return values;
    }

    String[] pairs = payload.split(";");
    for (String pair : pairs) {
      if (pair == null || pair.isBlank()) {
        continue;
      }
      int splitAt = pair.indexOf('=');
      if (splitAt < 0) {
        continue;
      }
      String key = pair.substring(0, splitAt).trim();
      String value = pair.substring(splitAt + 1).trim();
      if (!key.isEmpty()) {
        values.put(key, value);
      }
    }
    return values;
  }

  public static String write(Map<String, String> values) {
    StringJoiner joiner = new StringJoiner(";");
    for (Map.Entry<String, String> entry : values.entrySet()) {
      joiner.add(entry.getKey() + "=" + entry.getValue());
    }
    return joiner.toString();
  }
}

