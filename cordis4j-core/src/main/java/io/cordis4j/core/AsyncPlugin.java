/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * A component whose effect function may block (paper, Section 4.3.3, asynchrony): the runtime runs
 * it on a virtual thread so blocking costs no platform thread.
 *
 * <p>{@code apply} runs inside the plugin's own effect domain exactly like {@link Plugin}; the only
 * difference is the carrier thread and the permission to throw checked exceptions. Long-lived work
 * started from {@code apply} should run through {@code Context.spawn} so that unloading the plugin
 * interrupts and joins it - the inverse of starting a task (paper Section 3.1, reversible effects),
 * and the Java form of the paper's inertia: a migration waits for in-flight work to land.
 */
@FunctionalInterface
public interface AsyncPlugin {

  /**
   * Applies the component to a context; may block and may throw checked exceptions.
   *
   * @param ctx the context the component runs in, never null
   * @return an extra cleanup disposable, never null
   * @throws Exception when the component fails; its partial effects revert and the failure
   *     propagates (or is recorded, for declarative fibers)
   */
  Disposable apply(Context ctx) throws Exception;
}
