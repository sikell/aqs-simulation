package de.sikeller.aqs.model;

import lombok.Data;

/**
 * The ResultTable class represents a table structure with predefined columns and data. It is used
 * to store and organize results in a tabular format.
 *
 * <p>The columns represent the headers of the table, while the data array holds the corresponding
 * values for each row. Each row is represented as an array of objects corresponding to the columns.
 *
 * <p>Usage example:
 *
 * <pre>
 * String[] columns = {"Name", "Age", "Occupation"};
 * Object[][] data = {
 *     {"Alice", 30, "Engineer"},
 *     {"Bob", 25, "Designer"}
 * };
 * ResultTable resultTable = new ResultTable(columns, data);
 * </pre>
 */
@Data
public class ResultTable {
  private final String[] columns;
  private final Object[][] data;
}
