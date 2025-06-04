# TaxiAlgorithmDistributed

The `TaxiAlgorithmDistributed` implements a distributed approach for ad-hoc ride-sharing. It aims to efficiently match clients requesting rides with available taxis by simulating interactions between clients, a Range Query System (RQS), and individual taxis.

## Core Functionality

The algorithm processes ride requests from clients in each simulation step (`nextStep`) as follows:

1.  **Client Request Processing**: For each waiting client:
    * **Limit Calculation**: It first calculates theoretical limits for the search:
        * `maxClientWalkingTime_s`: The time it would take the client to walk directly to their destination.
        * `maxTheoreticalPickupDistance_m`: The maximum distance a taxi can be from the client's starting position while still (theoretically) being able to get the client to their destination faster than if the client walked. This distance serves as an upper cap for the search radius.
    * **Initial Search Radius**: An initial search radius is determined for the client. This is calculated as a percentage (`InitialSearchRadiusFactor`) of the `maxTheoreticalPickupDistance_m`. The current search radius for a client is stored and potentially increased across simulation steps if no taxi is found.
    * **Range Query (RQS)**: The client (conceptually) queries a Range Query System (RQS) with its start position, target position, and the current search radius. The RQS returns a set of candidate taxis whose routes or service areas are geographically relevant.
    * **Offer Solicitation**: The client then requests offers from these candidate taxis.
        * Each candidate taxi (if it has capacity, or if `CalculateFullTaxis` is enabled) calculates the marginal cost to include the new client. This is handled by the `CostCalculator`.
        * The `CostCalculator` also considers the `maxClientWalkingTime_s` to ensure the proposed ride is viable for the client.
    * **Best Offer Selection**: The client evaluates all feasible offers received from the taxis and selects the one with the minimum marginal cost.
    * **Assignment**: If a suitable offer is found, the client is assigned to the chosen taxi. The taxi's route is updated to include the new client, using the specific optimal route determined by the `CostCalculator`. The client's search radius is reset for future requests.
    * **Radius Increase**: If no suitable taxi is found (either no candidates from RQS, or no feasible offers), the client's search radius is increased by the `RadiusIncreaseFactor` for the next simulation step, capped сознательно by `maxTheoreticalPickupDistance_m`. The client remains in a waiting state.

## Components

The algorithm relies on two main pluggable components for its core logic:

### 1. Range Query System (RQS)
   - **Interface**: `de.sikeller.aqs.taxi.algorithm.distributed.rqs.RangeQuerySystem`
   - **Current Implementation**: `de.sikeller.aqs.taxi.algorithm.distributed.rqs.SimulatedRangeQuerySystem`
   - **Purpose**: The RQS is responsible for efficiently finding taxis that are geographically "close" or relevant to a client's requested ride. It takes the client's start, destination, and a search radius as input and returns a set of candidate taxis. This helps to avoid querying every single taxi in the system, which would not be scalable.
   - *Future Work (as implied by concept)*: In a real-world distributed system, the RQS itself could be a sophisticated, parallelized service.

### 2. Cost Calculator
   - **Interface**: `de.sikeller.aqs.taxi.algorithm.distributed.costing.CostCalculator`
   - **Current Implementation**: `de.sikeller.aqs.taxi.algorithm.distributed.costing.SequentialCostCalculator`
   - **Purpose**: The `CostCalculator` determines the marginal cost for a specific taxi to serve a new client. This involves:
      - Finding the optimal way to insert the new client's pickup and dropoff points into the taxi's existing route.
      - Ensuring that taxi capacity constraints are not violated.
      - Verifying that the new total travel time for the client (including pickup) does not exceed the client's `maxClientWalkingTime_s`.
   - It returns a `CostCalculationResult` which includes the calculated cost (e.g., additional travel distance) and the new optimal sequence of `OrderNode`s for the taxi if the client were to be accepted.
   - In Final System it could run lokal and parallel in every Taxi.

## Configuration Parameters

The behavior of the `TaxiAlgorithmDistributed` can be configured through the following parameters, set via the simulation UI:

1.  **`InitialSearchRadiusFactor`**
    * **Description**: A percentage value (e.g., 10 for 10%) that determines the initial geometric search radius for a client.
    * **Calculation**: `initialSearchRadius = maxTheoreticalPickupDistance_m * (InitialSearchRadiusFactor / 100.0)`
    * **Effect**: A smaller factor leads to a very localized initial search, potentially finding very close taxis quickly but missing slightly further optimal ones. A larger factor broadens the initial search at the cost of querying more taxis via RQS.
    * **Default Value**: 10 (i.e., 10%)

2.  **`RadiusIncreaseFactor`**
    * **Description**: The factor by which a client's current search radius is multiplied if no suitable taxi is found in the current simulation step.
    * **Effect**: Controls how quickly the search area expands for a waiting client. A factor of 2 means the radius doubles in the next attempt. This expansion is capped by the client's `maxTheoreticalPickupDistance_m`.
    * **Default Value**: 2

3.  **`CalculateFullTaxis`**
    * **Description**: A boolean-like toggle (0 for false, 1 for true).
    * **Effect**:
        * If `0` (false): The algorithm will only request cost calculations from taxis that report `taxi.hasCapacity()` at the time of the request.
        * If `1` (true): The algorithm will also request cost calculations from taxis that are currently full. This allows the `CostCalculator` to potentially find a valid route if, for example, an existing passenger can be dropped off before the new client needs to be picked up, thus freeing capacity.
    * **Default Value**: 0 (false)