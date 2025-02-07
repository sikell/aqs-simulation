package de.sikeller.aqs.model;

import java.text.NumberFormat;
import java.util.Locale;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ResultTableUtils {
  private static final NumberFormat format = NumberFormat.getInstance(Locale.getDefault());

  public static Double parseDouble(String value) {
    try {
      return format.parse(value.replace(',', '.')).doubleValue();
    } catch (Exception e) {
      return null;
    }
  }
}
