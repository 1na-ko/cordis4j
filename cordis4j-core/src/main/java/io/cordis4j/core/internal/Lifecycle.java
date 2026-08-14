/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.Disposable;
import java.util.Deque;

/**
 * The lifecycle seam (decision D7): defines how a context's accumulated effects are reverted.
 *
 * <p>P1 ships {@link SimpleLifecycle}, the synchronous two-state model. P2 replaces it with the
 * inertial state machine of paper Algorithm 5 without touching the public API.
 */
interface Lifecycle {

  /**
   * Reverts effects in LIFO order; failures are collected and reported aggregated.
   *
   * @param effects the accumulator to drain, most recently registered first
   */
  void revert(Deque<Disposable> effects);
}
