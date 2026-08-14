/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import io.cordis4j.core.internal.ContextImpl;

/** Factory for root {@link Context}s. */
public final class Contexts {

  private Contexts() {}

  /**
   * Creates a root context: it has no parent and is its own {@link Context#root()}.
   *
   * @return a new root context
   */
  public static Context create() {
    return new ContextImpl(null);
  }
}
