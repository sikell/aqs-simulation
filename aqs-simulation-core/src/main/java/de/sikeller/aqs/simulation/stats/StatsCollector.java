package de.sikeller.aqs.simulation.stats;

import de.sikeller.aqs.model.Algorithm;
import de.sikeller.aqs.model.ResultTable;
import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.model.events.EventClientFinished;
import de.sikeller.aqs.model.events.EventList;
import de.sikeller.aqs.simulation.stats.CollectorMinMaxAverage.Result;
import lombok.extern.slf4j.Slf4j;

import static java.lang.String.format;

@Slf4j
public class StatsCollector {
  private static final String DOUBLE_FORMAT = "%.02f";
  private Result<Double> travelDistance;
  private Result<Long> travelTime;
  private Algorithm algorithm;
  private int runCounter = 0;

  public void collect(EventList eventList, World world, Algorithm algorithm) {
    collectClientTravelTime(eventList);
    collectTaxiTravelDistance(world);
    this.algorithm = algorithm;
    runCounter++;
  }

  public ResultTable tableResults() {
    var columns = new String[] {"Result", "Min", "Max", "Avg", "Sum", "Count", "Algorithm", "Run"};

    var data = new Object[2][];
    data[0] =
        new Object[] {
          "Taxi Travel Distance",
          format(DOUBLE_FORMAT, travelDistance.min()),
          format(DOUBLE_FORMAT, travelDistance.max()),
          format(DOUBLE_FORMAT, travelDistance.avg()),
          format(DOUBLE_FORMAT, travelDistance.sum()),
          travelDistance.count(),
          algorithm.get().getClass().getSimpleName(),
          runCounter
        };
    data[1] =
        new Object[] {
          "Client Travel Time",
          travelTime.min(),
          travelTime.max(),
          format(DOUBLE_FORMAT, travelTime.avg()),
          travelTime.sum(),
          travelTime.count(),
          algorithm.get().getClass().getSimpleName(),
          runCounter
        };

    return new ResultTable(columns, data);
  }

  public void print() {
    if (travelDistance != null)
      log.info(
          "[ Taxi travel distance ] run: {}, min: {}, max: {}, avg: {}, sum: {}, count: {}, algorithm: {}",
          runCounter,
          format(DOUBLE_FORMAT, travelDistance.min()),
          format(DOUBLE_FORMAT, travelDistance.max()),
          format(DOUBLE_FORMAT, travelDistance.avg()),
          format(DOUBLE_FORMAT, travelDistance.sum()),
          travelDistance.count(),
          algorithm.get().getClass().getSimpleName());
    if (travelTime != null)
      log.info(
          "[Client travel time ] run: {}, min: {}, max: {}, avg: {}, sum: {}, count: {}, algorithm: {}",
          runCounter,
          travelTime.min(),
          travelTime.max(),
          format(DOUBLE_FORMAT, travelTime.avg()),
          travelTime.sum(),
          travelTime.count(),
          algorithm.get().getClass().getSimpleName());
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
