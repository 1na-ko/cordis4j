/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Static factories for common {@link Disposable} shapes.
 *
 * <p>All factories reject {@code null} arguments with {@link NullPointerException}.
 */
public final class Disposables {

  private static final Disposable NONE =
      new Disposable() {
        @Override
        public void dispose() {}
      };

  private Disposables() {}

  /**
   * Returns a no-op disposable.
   *
   * @return a shared immutable disposable whose {@link Disposable#dispose()} does nothing
   */
  public static Disposable none() {
    return NONE;
  }

  /**
   * Wraps an action so that it runs at most once, on the first {@link Disposable#dispose()}.
   *
   * <p>The once guarantee is atomic: two threads racing to dispose the same wrapper still run
   * {@code action} exactly once.
   *
   * @param action the reversion action
   * @return a disposable that executes {@code action} exactly once
   * @throws NullPointerException if {@code action} is null
   */
  public static Disposable of(Runnable action) {
    Objects.requireNonNull(action, "action");
    AtomicBoolean disposed = new AtomicBoolean();
    return () -> {
      if (disposed.compareAndSet(false, true)) {
        action.run();
      }
    };
  }

  /**
   * Combines several disposables into one that disposes them sequentially, in argument order.
   *
   * <p>Every part runs even when earlier parts fail. Failures are collected and reported through a
   * {@link DisposeException} carrying them as suppressed exceptions.
   *
   * @param parts the disposables to combine
   * @return a disposable that runs all {@code parts} on its first {@link Disposable#dispose()}
   * @throws NullPointerException if {@code parts} or any element is null
   */
  public static Disposable composite(Disposable... parts) {
    Objects.requireNonNull(parts, "parts");
    Disposable[] copy = parts.clone();
    for (Disposable part : copy) {
      Objects.requireNonNull(part, "part");
    }
    return of(
        () -> {
          List<Throwable> failures = new ArrayList<>();
          for (Disposable part : copy) {
            try {
              part.dispose();
            } catch (Throwable failure) {
              failures.add(failure);
            }
          }
          if (!failures.isEmpty()) {
            DisposeException error =
                new DisposeException("Disposal failed with " + failures.size() + " error(s)");
            for (Throwable failure : failures) {
              error.addSuppressed(failure);
            }
            throw error;
          }
        });
  }
}
