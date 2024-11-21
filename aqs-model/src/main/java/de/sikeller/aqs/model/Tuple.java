package de.sikeller.aqs.model;

/**
 * A simple tuple class that stores two values of arbitrary type.
 *
 * @param <A> type of the first value
 * @param <B> type of the second value
 */
public record Tuple<A, B>(A v1, B v2) {}
