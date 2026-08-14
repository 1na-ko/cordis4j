/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

/** Rethrows a {@link Throwable} without wrapping: context methods declare no checked failures. */
final class Throwables {

  private Throwables() {}

  @SuppressWarnings("unchecked")
  static <T extends Throwable> RuntimeException sneak(Throwable failure) throws T {
    throw (T) failure;
  }
}
