# aqs-p2p

Basis-Modul fuer verteilte Ausfuehrung in der Taxi-Simulation.

## Enthalten

- P2P API fuer Node-Identitaet, Nachrichten und Netzwerk-Operationen
- In-Memory Netzwerk (`InMemoryP2PNetwork`) als lokale Referenz-Implementierung
- LAN Netzwerk (`LanP2PNetwork`) mit UDP-Multicast Auto-Discovery und TCP-Messaging
- Startbare Services fuer `CLIENT` und `VEHICLE`
- Startbare Bootstrap-Runner (`VehicleNodeMain`, `ClientNodeMain`) fuer echte Netzwerk-Demos

## Ziel

Dieses Modul ist die Startbasis fuer echte Netzwerktranporte (z. B. gRPC, QUIC, Gossip, DHT).
Die In-Memory Variante dient nur als schneller Integrations- und Logik-Test.

## Schnellstart (2 Prozesse)

Vehicle Node starten:

`de.sikeller.aqs.p2p.bootstrap.VehicleNodeMain`

Parameter-Beispiel:

`--id=vehicle-1 --tcpPort=46001 --discoveryPort=45892 --multicastGroup=239.255.42.99`

Optional (Overlay-Tuning fuer Standalone-Nodes):

`--overlayMaxNeighbors=3 --overlayShortcuts=1 --overlayPositionTtlTicks=200`

Hinweis: Wenn `--tcpPort` fehlt, wird fuer IDs wie `vehicle-2` automatisch ein Port
aus der ID abgeleitet (`46000 + 2 => 46002`). Fuer parallele Starts sind dennoch
explizite, eindeutige Ports empfehlenswert.

Client Node starten:

`de.sikeller.aqs.p2p.bootstrap.ClientNodeMain`

Parameter-Beispiel:

`--id=client-1 --tcpPort=46002 --discoveryPort=45892 --multicastGroup=239.255.42.99`

Optional fuer dezentrale Vehicle-Entscheidung mit Hop-Weiterleitung:

`--requestHops=2`

## Schnellstart mit Simulation (empfohlen)

1. Vehicle Service starten (eigener Prozess):

`de.sikeller.aqs.p2p.bootstrap.VehicleNodeMain --id=vehicle-1 --tcpPort=46001 --discoveryPort=45892 --multicastGroup=239.255.42.99`

2. Die Simulation starten (`aqs-simulation-app`, `de.sikeller.aqs.runner.Main`).

Die App nutzt den `de.sikeller.aqs.taxi.algorithm.TaxiAlgorithmP2PCollector`. Im eingebetteten
Simulationsmodus (`p2pEmbeddedSimulation=1`) wird ein In-Memory-P2P-Netz erzeugt und pro Taxi ein
`VehicleP2PService`-Agent gestartet. Requests werden mit k-Hop-Weiterleitung verteilt, Vehicles
entscheiden lokal ueber Offers (inkl. Radius/ETA), und der Collector orchestriert Commit/World-Mutation.

Zusatz in der UI: Im P2P-Modus wird neben den Statuswerten auch eine einfache Topologie-Ansicht
des lokalen Overlays angezeigt (Collector im Zentrum, bekannte Peers aussen inkl. Rolle).

Die Request-Seeding-Menge wird ueber das bestehende RQS (`SimulatedRangeQuerySystem`) bestimmt,
z. B. mit `InitialSearchRadiusFactor`, `p2pMinSearchRadius` und `CalculateFullTaxis`.
Zusammen mit `p2pRequestForwardHops` ergibt das lokale, stufenweise Reichweite statt Full-Mesh.

Zusatz (UI-steuerbar im Algorithmus-Parameterbereich des `TaxiAlgorithmP2PCollector`):

- `p2pEmbeddedSimulation`: `1` = alles lokal simuliert (kein externer Vehicle-Prozess noetig), `0` = LAN-Mode
- `p2pOverlayMaxNeighbors`: maximale lokale Nachbarn pro Node
- `p2pOverlayShortcuts`: Anzahl deterministischer Small-World-Shortcuts je Node
- `p2pOverlayPositionTtlTicks`: Gueltigkeit empfangener Vehicle-Positionen fuer die Nachbarwahl
- `p2pRequestForwardHops`: k-Hop-TTL fuer Request-Flooding

Hinweis: Diese UI-Parameter setzen JVM-Properties im Simulationsprozess.
Extern gestartete Nodes (`VehicleNodeMain`/`ClientNodeMain` in separaten Prozessen)
muessen dieselben Werte separat per `-Daqs.p2p.overlay.*` bekommen.

Zusatz: Vehicle-Nodes tauschen Positionen ueber `vehicle.position` aus; das Small-World-Overlay
bevorzugt dadurch nahe Vehicle-Peers und nutzt Shortcuts fuer Weitbereichsverbindungen.

