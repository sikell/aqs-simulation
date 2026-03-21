package de.sikeller.aqs.p2p.bootstrap;

import de.sikeller.aqs.p2p.service.ClientP2PService;
import de.sikeller.aqs.p2p.service.VehicleP2PService;
import de.sikeller.aqs.p2p.transport.inmemory.InMemoryP2PNetwork;

public class P2PMain {
  public static void main(String[] args) {
    var network = new InMemoryP2PNetwork();

    try (var client = new ClientP2PService("client-1", network);
        var vehicle = new VehicleP2PService("vehicle-1", network)) {
      client.start();
      vehicle.start();

      client.requestRide("(100,100)", "(900,900)");
      vehicle.sendOffer("client-1", "etaSeconds=120;price=12");

      Thread.sleep(250);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

