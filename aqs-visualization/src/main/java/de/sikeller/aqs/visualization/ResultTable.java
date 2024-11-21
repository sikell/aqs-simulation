package de.sikeller.aqs.visualization;

import lombok.Data;

@Data
public class ResultTable {
  private final String[] columns;
  private final Object[][] data;
}
