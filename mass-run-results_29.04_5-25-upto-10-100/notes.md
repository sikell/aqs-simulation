- suspicious low taxi travel time for p2p + high client travel time --> clients arent served (or served badly)
- pretty much no difference between k = 1,... --> misimplemented or not useful ?
  - assumably k-hop model is not representative for information distribution

---

## Current P2P Simulation State (as of 29.04.2026)

### Architecture
- **Embedded mode** (`p2pEmbeddedSimulation=1`): all vehicle nodes run in-process via `InMemoryP2PNetwork`;
  no separate processes needed. This is what mass runs use.
- **Single collector node** (`sim-collector-local`) acts as the client-side broadcaster.
  Each taxi gets a corresponding `VehicleP2PService` node (`vehicle-<name>`).

### Request flow
1. Collector publishes `RIDE_REQUEST` to seed vehicles within a configurable radius (`p2pFixedSearchRadius`, default 5000 m).
2. Each receiving vehicle **autonomously decides** whether to commit – no offer/accept roundtrip.
   It sends `RIDE_COMMIT` directly back to the collector if it considers itself the best candidate.
3. **First commit wins** at the collector (first-come-first-serve collision resolution).
4. Collector broadcasts `RIDE_ASSIGNED` so losing vehicles immediately release their busy-lease.

### k-hop forwarding
- Vehicles forward received requests to their **overlay neighbours** with `hopsRemaining - 1`.
  Default `p2pRequestForwardHops = 2`.
- Forwarded requests bypass the range filter (`FORWARDED_BY` field set), so neighbours outside the
  original search radius can still respond.
- Vehicle self-selection: a vehicle only commits if it is the **best known vehicle** among its overlay
  neighbours (lowest estimated ETA). For forwarded requests this check is skipped to keep diffusion useful.

### Overlay
- Small-world style: each node maintains `p2pOverlayMinNeighbors` (default 1) spatial neighbours
  plus `p2pOverlayShortcuts` (default 1) deterministic long-range links.
- Position of each vehicle is advertised each tick and used for neighbour selection and ETA estimation.
- Default `p2pOverlayMaxNeighbors = 4`.

### Known limitations / open questions (at time of this run)
- k-hop variation shows almost no effect → either the overlay is too densely connected for the
  simulated fleet size (every vehicle already reachable in 1 hop), or the best-vehicle check
  suppresses most forwarded commits anyway.
- High client travel time with P2P suggests unserved or late-served clients; root cause unclear –
  candidates: seed radius too small, busy-lease too long, or first-commit race in embedded mode.
- `p2pRequestRepublishTicks = 3` means stale requests are re-published every 3 ticks, which may
  cause duplicate commit races.
