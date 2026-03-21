package de.sikeller.aqs.p2p.transport.network;

import de.sikeller.aqs.p2p.api.P2PMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;

/**
 * Versionierter Codec fuer P2P-Nachrichten.
 *
 * <p>Schreibt und liest ausschliesslich SER1 (Java-Serializable P2PMessage).
 */
final class P2PMessageWireCodec {
  private static final String MESSAGE_TYPE_SER1 = "SER1";
  private static final int MAX_WIRE_BYTES = 64 * 1024;

  private P2PMessageWireCodec() {}

  static String encode(P2PMessage message) {
    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos)) {
      oos.writeObject(message);
      oos.flush();
      return MESSAGE_TYPE_SER1
          + "|"
          + Base64.getUrlEncoder().encodeToString(bos.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException("Could not encode P2P message", e);
    }
  }

  static P2PMessage decode(String payload) {
    String[] parts = payload.split("\\|", -1);
    if (parts.length == 2 && MESSAGE_TYPE_SER1.equals(parts[0])) {
      return decodeSerializable(parts[1]);
    }
    throw new IllegalArgumentException("Invalid message format");
  }

  private static P2PMessage decodeSerializable(String encodedEnvelope) {
    byte[] wireBytes = Base64.getUrlDecoder().decode(encodedEnvelope);
    if (wireBytes.length > MAX_WIRE_BYTES) {
      throw new IllegalArgumentException("Message exceeds maximum wire size");
    }

    try (ByteArrayInputStream bis = new ByteArrayInputStream(wireBytes);
        ObjectInputStream ois = new ObjectInputStream(bis)) {
      // Restrict deserialization to our envelope to avoid unexpected gadget/object graphs.
      ObjectInputFilter filter =
          ObjectInputFilter.Config.createFilter(
              "maxbytes="
                  + MAX_WIRE_BYTES
                  + ";de.sikeller.aqs.p2p.api.P2PMessage;java.base/*;!*"
                  );
      ois.setObjectInputFilter(filter);

      Object deserialized = ois.readObject();
      if (!(deserialized instanceof P2PMessage message)) {
        throw new IllegalArgumentException("Unexpected serialized payload type");
      }
      return message;
    } catch (IOException | ClassNotFoundException e) {
      throw new IllegalArgumentException("Invalid serialized message payload", e);
    }
  }
}

