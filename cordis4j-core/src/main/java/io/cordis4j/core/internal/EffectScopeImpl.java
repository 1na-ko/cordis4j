/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.Context;
import io.cordis4j.core.Disposable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** The default {@link Context.EffectScope}: a self-contained LIFO group of tracked effects. */
final class EffectScopeImpl implements Context.EffectScope {

  private final Deque<Disposable> effects = new ArrayDeque<>();
  private boolean disposed;

  @Override
  public <D extends Disposable> D track(D effect) {
    Objects.requireNonNull(effect, "effect");
    if (disposed) {
      throw new IllegalStateException("Effect scope is already disposed");
    }
    effects.push(effect);
    return effect;
  }

  @Override
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    SimpleLifecycle.INSTANCE.revert(effects);
  }
}
