package de.sikeller.aqs.visualization.controls;

import lombok.Data;

@Data
public class BatchProcessingProperties {
    private int batchCount = 1;
    private int taxiIncrement = 0;
    private int clientIncrement = 0;
    private int seatIncrement = 0;
}
