# aqs-p2p

Basis-Modul fuer verteilte Ausfuehrung in der Taxi-Simulation.

## Enthalten

- P2P API fuer Node-Identitaet, Nachrichten und Netzwerk-Operationen
- In-Memory Netzwerk (`InMemoryP2PNetwork`) als lokale Referenz-Implementierung
- LAN Netzwerk (`LanP2PNetwork`) mit UDP-Multicast Auto-Discovery und TCP-Messaging
- Startbare Services fuer `CLIENT` und `VEHICLE`
- Startbarer Bootstrap-Runner (`VehicleNodeMain`) fuer echte Netzwerk-Demos

## Ziel

Dieses Modul ist die Startbasis fuer echte Netzwerktranporte (z. B. gRPC, QUIC, Gossip, DHT).
Die In-Memory Variante dient nur als schneller Integrations- und Logik-Test.

## Schnellstart (Vehicle Node)

Vehicle Node starten:

`de.sikeller.aqs.p2p.bootstrap.VehicleNodeMain`

Parameter-Beispiel:

`--id=vehicle-1 --tcpPort=46001 --discoveryPort=45892 --multicastGroup=239.255.42.99`

Optional (Overlay-Tuning fuer Standalone-Nodes):

`--overlayMinNeighbors=1 --overlayMaxDistance=10000 --overlayShortcuts=1 --overlayPositionTtlTicks=200`

Hinweis: Wenn `--tcpPort` fehlt, wird fuer IDs wie `vehicle-2` automatisch ein Port
aus der ID abgeleitet (`46000 + 2 => 46002`). Fuer parallele Starts sind dennoch
explizite, eindeutige Ports empfehlenswert.

## Schnellstart mit Simulation (empfohlen)

1. Vehicle Service starten (eigener Prozess):

`de.sikeller.aqs.p2p.bootstrap.VehicleNodeMain --id=vehicle-1 --tcpPort=46001 --discoveryPort=45892 --multicastGroup=239.255.42.99`

2. Die Simulation starten (`aqs-simulation-app`, `de.sikeller.aqs.runner.Main`).

Die App nutzt den `de.sikeller.aqs.taxi.algorithm.collector.TaxiAlgorithmP2PCollector`. Im eingebetteten
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
- `p2pOverlayMinNeighbors`: Mindestzahl lokaler Nachbarn pro Node
- `p2pOverlayShortcuts`: Anzahl deterministischer Small-World-Shortcuts je Node
- `p2pOverlayPositionTtlTicks`: Gueltigkeit empfangener Vehicle-Positionen fuer die Nachbarwahl
- `p2pRequestForwardHops`: k-Hop-TTL fuer Request-Flooding

Hinweis: Diese UI-Parameter setzen JVM-Properties im Simulationsprozess.
Extern gestartete Vehicle-Nodes (`VehicleNodeMain` in separaten Prozessen)
muessen dieselben Werte separat per `-Daqs.p2p.overlay.*` bekommen.

Zusatz: Vehicle-Nodes tauschen Positionen ueber `vehicle.position` aus; das Overlay verbindet
primaer Peers innerhalb einer Maximaldistanz (`aqs.p2p.overlay.maxDistance`) und fuellt bei Bedarf
auf `overlayMinNeighbors` auf. Shortcuts koennen weiterhin fuer Weitbereichsverbindungen genutzt werden.

