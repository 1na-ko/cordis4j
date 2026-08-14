/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * An operation accepting three arguments and producing a result; used by {@code inject}.
 *
 * @param <A> the first argument type
 * @param <B> the second argument type
 * @param <C> the third argument type
 * @param <R> the result type
 */
@FunctionalInterface
public interface TriFunction<A, B, C, R> {

  /**
   * Performs the operation.
   *
   * @param a the first argument
   * @param b the second argument
   * @param c the third argument
   * @return the result
   */
  R apply(A a, B b, C c);
}
