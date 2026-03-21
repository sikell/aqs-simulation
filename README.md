# AQS Simulation Taxi Scenario

Please use **google-java-format** for code formatting!

## Setup

```
mvn clean install
```

Run main class in `aqs-simulation-app/../Main.java`.

## Structure

The general project structure and the module's responsibility.

### aqs-model

General model classes with minimal logic für the taxi scenario.

### aqs-taxi-algorithm

Implementations for the taxi algorithm used to control all taxis
and transport all clients to their destinations.

### aqs-simulation-core

Simulation logic to run and control a simulation and collect stats.

### aqs-visualization

A UI displaying the simulation state and provide control buttons etc.

### aqs-simulation-app

The main class to run this simulation application and do the final dependency
injection.

### aqs-p2p

Foundational P2P networking module with node services (client/vehicle) and a
first in-memory transport to prepare distributed execution.

Typical starters:
- `de.sikeller.aqs.p2p.bootstrap.VehicleNodeMain`
- `de.sikeller.aqs.p2p.bootstrap.ClientNodeMain`
- `de.sikeller.aqs.runner.Main` (uses `de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmP2PCollector`)

