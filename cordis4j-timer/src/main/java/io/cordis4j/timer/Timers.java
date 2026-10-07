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
 * disposing it interrupts the task (the landing is awaited by the context dispose's executor close,
 * not joined per handle - D30), and unloading the plugin that started it reverts the timer
 * automatically. Starting a timer is an effect whose inverse is stopping it.
 *
 * <p>Call wrappers ({@link #throttle}, {@link #debounce}) follow upstream's {@code ctx.throttle}
 * and {@code ctx.debounce} shape as {@link Trigger} handles: {@code run()} re-triggers the policy,
 * {@code dispose()} cancels the pending execution and makes later triggers no-ops.
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
   * @return a disposable that cancels the timer (interrupts its task; the landing is awaited by
   *     dispose's executor close)
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

  /**
   * A throttled or debounced invocation handle (the JVM form of upstream's {@code WithDispose} call
   * wrapper): {@link #run()} re-triggers the policy, {@link #dispose()} cancels the pending
   * execution and makes subsequent triggers no-ops. Like every timer handle, disposing interrupts
   * without joining (D30) - a running callback is not waited for.
   */
  public interface Trigger extends Runnable, Disposable {}

  /**
   * Returns a leading-edge throttle over {@code callback} (upstream {@code ctx.throttle(callback,
   * delay)}): the first trigger executes {@code callback} synchronously on the triggering thread,
   * triggers inside the {@code delayMillis} window are coalesced into one trailing execution at the
   * window end (the latest trigger wins), and a trigger after the window leads again.
   *
   * <p>Unlike upstream's single-threaded dispatch, a slow callback does not block later triggers -
   * executions of the same handle may overlap. On the leading edge a throwing callback propagates
   * to the trigger caller; on the trailing edge it fails only that timer task (logged, never
   * propagated). After {@code dispose()} every trigger is a no-op: an intentional difference from
   * upstream, whose throttle may still execute immediately once its window has elapsed.
   *
   * @param context the context owning the timer
   * @param callback the callback, never null
   * @param delayMillis the throttle window in milliseconds, must not be negative
   * @return the trigger handle; dispose cancels the pending execution and reverts with the domain
   * @throws IllegalArgumentException if {@code delayMillis} is negative
   * @throws NullPointerException if {@code context} or {@code callback} is null
   */
  public static Trigger throttle(Context context, Runnable callback, long delayMillis) {
    return throttle(context, callback, delayMillis, false);
  }

  /**
   * Returns a leading-edge throttle like {@link #throttle(Context, Runnable, long)}, with the
   * trailing execution suppressed when {@code noTrailing} is true (upstream's third argument).
   *
   * @param context the context owning the timer
   * @param callback the callback, never null
   * @param delayMillis the throttle window in milliseconds, must not be negative
   * @param noTrailing suppress the trailing execution at the window end
   * @return the trigger handle; dispose cancels the pending execution and reverts with the domain
   * @throws IllegalArgumentException if {@code delayMillis} is negative
   * @throws NullPointerException if {@code context} or {@code callback} is null
   */
  public static Trigger throttle(
      Context context, Runnable callback, long delayMillis, boolean noTrailing) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(callback, "callback");
    if (delayMillis < 0) {
      throw new IllegalArgumentException("delayMillis must not be negative: " + delayMillis);
    }
    TriggerState state = new TriggerState();
    long windowNanos = delayMillis * 1_000_000L;
    Runnable trailing =
        () -> {
          synchronized (state) {
            if (state.disposed) {
              return;
            }
            state.pending = null;
            state.called = true;
            state.lastCallNanos = System.nanoTime();
          }
          callback.run(); // outside the monitor: user code, like every teardown here
        };
    Disposable lifecycle = context.plugin(unused -> state::cancel);
    return new Trigger() {
      @Override
      public void run() {
        boolean lead = false;
        synchronized (state) {
          if (state.disposed) {
            return;
          }
          state.cancelPending();
          long elapsed = state.called ? System.nanoTime() - state.lastCallNanos : Long.MAX_VALUE;
          if (elapsed >= windowNanos) {
            state.called = true;
            state.lastCallNanos = System.nanoTime();
            lead = true;
          } else if (!noTrailing) {
            long remainingMillis = (windowNanos - elapsed + 999_999L) / 1_000_000L;
            state.pending = Timers.setTimeout(context, trailing, remainingMillis);
          }
        }
        if (lead) {
          callback.run();
        }
      }

      @Override
      public void dispose() {
        lifecycle.dispose(); // retires the guard fiber: state.cancel() drops the pending execution
      }
    };
  }

  /**
   * Returns a debounce over {@code callback} (upstream {@code ctx.debounce(callback, delay)}): each
   * trigger restarts the window, and {@code callback} executes once {@code delayMillis} of quiet
   * elapsed. After {@code dispose()} every trigger is a no-op (as upstream).
   *
   * @param context the context owning the timer
   * @param callback the callback, never null
   * @param delayMillis the quiet window in milliseconds, must not be negative
   * @return the trigger handle; dispose cancels the pending execution and reverts with the domain
   * @throws IllegalArgumentException if {@code delayMillis} is negative
   * @throws NullPointerException if {@code context} or {@code callback} is null
   */
  public static Trigger debounce(Context context, Runnable callback, long delayMillis) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(callback, "callback");
    if (delayMillis < 0) {
      throw new IllegalArgumentException("delayMillis must not be negative: " + delayMillis);
    }
    TriggerState state = new TriggerState();
    Runnable fire =
        () -> {
          synchronized (state) {
            if (state.disposed) {
              return;
            }
            state.pending = null;
          }
          callback.run(); // outside the monitor: user code
        };
    Disposable lifecycle = context.plugin(unused -> state::cancel);
    return new Trigger() {
      @Override
      public void run() {
        synchronized (state) {
          if (state.disposed) {
            return;
          }
          state.cancelPending();
          state.pending = Timers.setTimeout(context, fire, delayMillis);
        }
      }

      @Override
      public void dispose() {
        lifecycle.dispose();
      }
    };
  }

  /**
   * Per-handle mutable state. The monitor guards the fields only; callbacks always run outside it.
   * Cancellation is the upstream {@code _schedule} dispose: mark disposed, drop the pending timer.
   */
  private static final class TriggerState {

    boolean called;
    long lastCallNanos;
    Disposable pending;
    boolean disposed;

    void cancelPending() {
      if (pending != null) {
        pending.dispose();
        pending = null;
      }
    }

    void cancel() {
      synchronized (this) {
        disposed = true;
        cancelPending();
      }
    }
  }

  private static void sleep(long millis) throws InterruptedException {
    Thread.sleep(millis);
  }
}
