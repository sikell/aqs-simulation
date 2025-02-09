package de.sikeller.aqs.visualization;

import lombok.Data;

@Data
public class BatchProcessingProperties {
    private int batchCount = 1;
    private int taxiIncrement = 0;
    private int clientIncrement = 0;
}
