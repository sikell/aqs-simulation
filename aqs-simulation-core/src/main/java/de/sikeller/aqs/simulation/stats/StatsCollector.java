package de.sikeller.aqs.simulation.stats;

import static java.lang.String.format;

import de.sikeller.aqs.model.*;
import de.sikeller.aqs.model.events.EventClientFinished;
import de.sikeller.aqs.model.events.EventList;
import de.sikeller.aqs.simulation.stats.CollectorMinMaxAverage.Result;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StatsCollector {
  private static final String DOUBLE_FORMAT = "%.02f";
  private Result<Double> travelDistance;
  private Result<Long> travelTime;
  private Result<Long> calculationTime;
  private Algorithm algorithm;
  private int runCounter = 0;

  public void collect(
      EventList eventList, World world, Algorithm algorithm, Result<Long> calculationTime) {
    collectClientTravelTime(eventList);
    collectTaxiTravelDistance(world);
    this.calculationTime = calculationTime;
    this.algorithm = algorithm;
    runCounter++;
  }

  public ResultTable tableResults() {
    var columns = new String[] {"Result", "Min", "Max", "Avg", "Sum", "Count", "Algorithm", "Run"};

    var data = new Object[3][];
    data[0] =
        new Object[] {
          "Taxi Travel Distance [km]",
          format(DOUBLE_FORMAT, travelDistance.min()),
          format(DOUBLE_FORMAT, travelDistance.max()),
          format(DOUBLE_FORMAT, travelDistance.avg()),
          format(DOUBLE_FORMAT, travelDistance.sum()),
          travelDistance.count(),
          algorithm.get().getName(),
          runCounter
        };
    data[1] =
        new Object[] {
          "Client Travel Time [min]",
          travelTime.min(),
          travelTime.max(),
          format(DOUBLE_FORMAT, travelTime.avg()),
          travelTime.sum(),
          travelTime.count(),
          algorithm.get().getName(),
          runCounter
        };
    data[2] =
        new Object[] {
          "Calculation Time [millis]",
          calculationTime.min(),
          calculationTime.max(),
          format(DOUBLE_FORMAT, calculationTime.avg()),
          calculationTime.sum(),
          calculationTime.count(),
          algorithm.get().getName(),
          runCounter
        };

    return new ResultTable(columns, data);
  }

  public void print() {
    var table = tableResults();
    for (Object[] data : table.getData()) {
      log.info(
          "[{}] min: {}, max: {}, avg: {}, sum: {}, count: {}, algorithm: {}, run: {}",
          data[0],
          data[1],
          data[2],
          data[3],
          data[4],
          data[5],
          data[6],
          data[7]);
    }
  }

  private void collectTaxiTravelDistance(World world) {
    travelDistance =
        new CollectorMinMaxAverage<Taxi>().collectDouble(world.getTaxis(), Taxi::getTravelDistance);
  }

  private void collectClientTravelTime(EventList eventList) {
    var finishedEvents =
        eventList.getAll().stream()
            .filter(e -> e instanceof EventClientFinished)
            .map(e -> (EventClientFinished) e)
            .toList();

    travelTime =
        new CollectorMinMaxAverage<EventClientFinished>()
            .collectLong(finishedEvents, EventClientFinished::getTravelTime);
  }
}
