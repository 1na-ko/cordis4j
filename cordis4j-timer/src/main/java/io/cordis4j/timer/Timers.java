/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.timer;

import io.cordis4j.core.Context;
import io.cordis4j.core.Disposable;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Reversible timers (the JVM form of {@code @cordisjs/timer}, per the upstream parity baseline):
 * every timer is a {@link Context#spawn(Runnable)} task, so its handle is a tracked effect -
 * disposing it interrupts and joins the task, and unloading the plugin that started it reverts the
 * timer automatically. Starting a timer is an effect whose inverse is stopping it.
 *
 * <p>Callbacks run on the context tree's virtual-thread executor; a throwing callback fails the
 * timer task, which is reported to the {@code io.cordis4j.core.task} logger and never propagates
 * (the core's task semantics, D15).
 */
public final class Timers {

  private Timers() {}

  /**
   * Runs {@code callback} once after {@code delayMillis} on the tree's virtual-thread executor.
   *
   * @param context the context owning the timer
   * @param callback the callback, never null
   * @param delayMillis the delay in milliseconds, must not be negative
   * @return a disposable that cancels the timer (no-op once fired or cancelled)
   * @throws IllegalArgumentException if {@code delayMillis} is negative
   * @throws NullPointerException if {@code context} or {@code callback} is null
   */
  public static Disposable setTimeout(Context context, Runnable callback, long delayMillis) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(callback, "callback");
    if (delayMillis < 0) {
      throw new IllegalArgumentException("delayMillis must not be negative: " + delayMillis);
    }
    return context.spawn(
        () -> {
          try {
            sleep(delayMillis);
            callback.run();
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); // cancelled: the task ends quietly
          }
        });
  }

  /**
   * Runs {@code callback} every {@code periodMillis} until the returned handle (or the owning
   * plugin domain) is disposed.
   *
   * <p>The schedule is fixed-delay, not fixed-rate: the next period starts after the callback
   * returns, so a slow callback delays the following tick instead of accumulating backlog.
   *
   * @param context the context owning the timer
   * @param callback the callback, never null
   * @param periodMillis the period in milliseconds, must be positive
   * @return a disposable that cancels the timer (interrupts and joins its task)
   * @throws IllegalArgumentException if {@code periodMillis} is not positive
   * @throws NullPointerException if {@code context} or {@code callback} is null
   */
  public static Disposable setInterval(Context context, Runnable callback, long periodMillis) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(callback, "callback");
    if (periodMillis <= 0) {
      throw new IllegalArgumentException("periodMillis must be positive: " + periodMillis);
    }
    return context.spawn(
        () -> {
          try {
            while (true) {
              sleep(periodMillis);
              callback.run();
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); // cancelled: the task ends quietly
          }
        });
  }

  /**
   * Returns a future completing after {@code delayMillis}; when the owning domain unloads (or the
   * returned future's timer is otherwise interrupted) first, it completes exceptionally with a
   * {@link CancellationException}.
   *
   * @param context the context owning the timer
   * @param delayMillis the delay in milliseconds, must not be negative
   * @return the future
   * @throws IllegalArgumentException if {@code delayMillis} is negative
   * @throws NullPointerException if {@code context} is null
   */
  public static CompletableFuture<Void> timeout(Context context, long delayMillis) {
    Objects.requireNonNull(context, "context");
    if (delayMillis < 0) {
      throw new IllegalArgumentException("delayMillis must not be negative: " + delayMillis);
    }
    CompletableFuture<Void> future = new CompletableFuture<>();
    context.spawn(
        () -> {
          try {
            sleep(delayMillis);
            future.complete(null);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(
                new CancellationException("timer interrupted before completion"));
          }
        });
    return future;
  }

  private static void sleep(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }
}
