package de.sikeller.aqs.runner.stats;

import de.sikeller.aqs.model.Taxi;
import de.sikeller.aqs.model.World;
import de.sikeller.aqs.model.events.EventClientFinished;
import de.sikeller.aqs.model.events.EventList;
import de.sikeller.aqs.runner.stats.CollectorMinMaxAverage.Result;
import de.sikeller.aqs.visualization.ResultTable;
import lombok.extern.slf4j.Slf4j;

import static java.lang.String.format;

@Slf4j
public class StatsCollector {
  private static final String DOUBLE_FORMAT = "%.02f";
  private Result<Double> travelDistance;
  private Result<Long> travelTime;

  public void collect(EventList eventList, World world) {
    collectClientTravelTime(eventList);
    collectTaxiTravelDistance(world);
  }

  public ResultTable tableResults() {
    var columns = new String[] {"Result", "Min", "Max", "Avg", "Sum", "Count"};

    var data = new Object[2][];
    data[0] =
        new Object[] {
          "Taxi Travel Distance",
          format(DOUBLE_FORMAT, travelDistance.min()),
          format(DOUBLE_FORMAT, travelDistance.max()),
          format(DOUBLE_FORMAT, travelDistance.avg()),
          format(DOUBLE_FORMAT, travelDistance.sum()),
          travelDistance.count()
        };
    data[1] =
        new Object[] {
          "Client Travel Time",
          travelTime.min(),
          travelTime.max(),
          format(DOUBLE_FORMAT, travelTime.avg()),
          travelTime.sum(),
          travelTime.count()
        };

    return new ResultTable(columns, data);
  }

  public void print() {
    if (travelDistance != null)
      log.info(
          "[Taxi travel distance] min: {}, max: {}, avg: {}, sum: {}, count: {}",
          format(DOUBLE_FORMAT, travelDistance.min()),
          format(DOUBLE_FORMAT, travelDistance.max()),
          format(DOUBLE_FORMAT, travelDistance.avg()),
          format(DOUBLE_FORMAT, travelDistance.sum()),
          travelDistance.count());
    if (travelTime != null)
      log.info(
          "[Client travel time] min: {}, max: {}, avg: {}, sum: {}, count: {}",
          travelTime.min(),
          travelTime.max(),
          format(DOUBLE_FORMAT, travelTime.avg()),
          travelTime.sum(),
          travelTime.count());
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
