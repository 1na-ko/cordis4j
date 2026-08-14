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

/**
 * The default {@link Context.EffectScope}: a self-contained LIFO group of tracked effects.
 *
 * <p>Methods are synchronized: an asynchronously activated fiber tracks effects from its carrier
 * thread while teardown may run on another. {@link #dispose()} runs the recorded disposables
 * outside the lock (they are user code and may join spawned tasks), so a disposable racing a
 * concurrent dispose is still reverted at most once.
 */
final class EffectScopeImpl implements Context.EffectScope {

  private Deque<Disposable> effects = new ArrayDeque<>();
  private boolean disposed;

  @Override
  public synchronized <D extends Disposable> D track(D effect) {
    Objects.requireNonNull(effect, "effect");
    if (disposed) {
      throw new IllegalStateException("Effect scope is already disposed");
    }
    effects.push(effect);
    return effect;
  }

  @Override
  public void dispose() {
    Deque<Disposable> pending;
    synchronized (this) {
      if (disposed) {
        return;
      }
      disposed = true;
      pending = effects;
      effects = new ArrayDeque<>();
    }
    SimpleLifecycle.INSTANCE.revert(pending);
  }
}
