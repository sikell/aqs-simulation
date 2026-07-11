# AQS Simulation Taxi Scenario

Please use **google-java-format** for code formatting!

## Setup

```
mvn clean install
```

Run main class in `aqs-simulation-app/../Main.java`.

## Mass runs (UI)

Mass runs are executed via the visualization UI and written to CSV files.

Primary experiment dimensions in the UI flow:
- algorithm
- `kHops` (for collector algorithms)
- `p2pStrategy` (for collector algorithms)
- `taxiCount`
- `taxiSeatCount`
- `runs` and `baseSeed`

CSV output:
- `mass-run-results.csv` (one row per metric and run)
- `mass-run-aggregates.csv` (avg/stddev/min/max and spread per experiment group)

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
- `de.sikeller.aqs.runner.Main` (uses `de.sikeller.aqs.taxi.algorithm.collector.TaxiAlgorithmP2PCollector`)

LAN P2P setup:
1. Start one `VehicleNodeMain` per machine. No coordinates are needed; without `--id`, the node uses `vehicle-<hostname>`. For multiple nodes on one machine, use numeric ids such as `--id=vehicle-1`, `--id=vehicle-2` so ports are derived uniquely.
2. Start the simulation app with `TaxiAlgorithmP2PCollector`.
3. Set `p2pEmbeddedSimulation=0` in the P2P controls.
4. Keep all nodes on the same `p2pDiscoveryPort` and multicast group. Defaults are `45892` and `239.255.42.99`.

LAN flow:
- vehicle nodes announce themselves by UDP multicast and listen on TCP
- collector discovers vehicle peers, derives `taxiCount`, and maps sorted vehicle ids to simulated taxis `t0..tN`
- each simulation tick, collector sends the mapped taxi state to the matching vehicle node
- collector publishes ride requests to RQS-selected seed vehicles
- vehicle nodes decide locally, forward by k-hop overlay, and send `ride.commit`
- collector applies the first valid commit to the mapped simulated taxi and broadcasts `ride.assigned`
