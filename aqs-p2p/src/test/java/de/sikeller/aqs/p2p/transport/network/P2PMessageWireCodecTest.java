package de.sikeller.aqs.p2p.transport.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.sikeller.aqs.p2p.api.P2PMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class P2PMessageWireCodecTest {

  @Test
  void encodeDecodeRoundtripUsesSerializableFormat() {
    P2PMessage original =
        new P2PMessage(
            P2PMessage.SCHEMA_VERSION,
            "req-1",
            "corr-1",
            "node-a",
            "topic.test",
            "payload|with|pipes",
            Instant.ofEpochMilli(1_700_000_000_000L));

    String wire = P2PMessageWireCodec.encode(original);
    assertTrue(wire.startsWith("SER1|"));

    P2PMessage decoded = P2PMessageWireCodec.decode(wire);
    assertEquals(original, decoded);
  }


  @Test
  void decodeRejectsLegacyMsg1() {
    String wire =
        String.join(
            "|", "MSG", enc("node-c"), enc("topic.v1"), enc("payload-v1"), "1700000000001");

    assertThrows(IllegalArgumentException.class, () -> P2PMessageWireCodec.decode(wire));
  }

  @Test
  void decodeRejectsInvalidPayload() {
    assertThrows(IllegalArgumentException.class, () -> P2PMessageWireCodec.decode("not-a-wire-message"));
  }

  private static String enc(String value) {
    return Base64.getUrlEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}

