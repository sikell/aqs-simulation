package de.sikeller.aqs.p2p.service.util;

@FunctionalInterface
public interface TriConsumer<A, B, C> {
  void accept(A a, B b, C c);
}
