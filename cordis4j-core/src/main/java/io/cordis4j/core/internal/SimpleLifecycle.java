/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.Disposable;
import io.cordis4j.core.DisposeException;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * P1 lifecycle: synchronous two-state reversion in LIFO order with failure aggregation (paper
 * Algorithm 1 accumulator; failure handling of paper Section 4.3.4).
 */
final class SimpleLifecycle implements Lifecycle {

  static final SimpleLifecycle INSTANCE = new SimpleLifecycle();

  private SimpleLifecycle() {}

  @Override
  public void revert(Deque<Disposable> effects) {
    List<Throwable> failures = null;
    Disposable disposable;
    while ((disposable = effects.pollFirst()) != null) {
      try {
        disposable.dispose();
      } catch (Throwable failure) {
        if (failures == null) {
          failures = new ArrayList<>();
        }
        failures.add(failure);
      }
    }
    if (failures != null) {
      DisposeException error =
          new DisposeException("Disposal failed with " + failures.size() + " error(s)");
      for (Throwable failure : failures) {
        error.addSuppressed(failure);
      }
      throw error;
    }
  }
}
