/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * A revertible registration: the runtime form of an effect's inverse (paper, Section 3.1).
 *
 * <p>Implementations must make {@link #dispose()} idempotent: calls after the first have no effect.
 * {@link #close()} delegates to {@link #dispose()} so disposables compose with try-with-resources
 * statements.
 */
public interface Disposable extends AutoCloseable {

  /**
   * Reverts the registration. Idempotent: the first call performs the reversion, and every
   * subsequent call is a no-op.
   */
  void dispose();

  /** Delegates to {@link #dispose()}. */
  @Override
  default void close() {
    dispose();
  }
}
