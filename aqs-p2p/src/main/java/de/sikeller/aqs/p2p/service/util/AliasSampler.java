package de.sikeller.aqs.p2p.service.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;

/**
 * Utility for alias table sampling (Vose/Alias method).
 */
public final class AliasSampler {
  private AliasSampler() {}

  public static final class Table {
    public final double[] prob;
    public final int[] alias;

    public Table(double[] prob, int[] alias) {
      this.prob = prob;
      this.alias = alias;
    }
  }

  public static Table build(double[] weights, double totalWeight) {
    int m = weights.length;
    double[] prob = new double[m];
    int[] alias = new int[m];
    double scale = m / totalWeight;
    Deque<Integer> small = new ArrayDeque<>();
    Deque<Integer> large = new ArrayDeque<>();
    double[] scaled = new double[m];
    for (int i = 0; i < m; i++) {
      scaled[i] = weights[i] * scale;
      if (scaled[i] < 1.0) small.add(i); else large.add(i);
    }
    while (!small.isEmpty() && !large.isEmpty()) {
      int less = small.removeLast();
      int more = large.removeLast();
      prob[less] = scaled[less];
      alias[less] = more;
      scaled[more] = (scaled[more] + scaled[less]) - 1.0;
      if (scaled[more] < 1.0) small.add(more); else large.add(more);
    }
    while (!large.isEmpty()) prob[large.removeLast()] = 1.0;
    while (!small.isEmpty()) prob[small.removeLast()] = 1.0;
    return new Table(prob, alias);
  }

  public static int sample(Random rnd, Table table) {
    int m = table.prob.length;
    int k = (int) (rnd.nextDouble() * m);
    double u = rnd.nextDouble();
    return (u < table.prob[k]) ? k : table.alias[k];
  }
}

