/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.ServiceKey;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fiber (paper, Section 4.1): one instantiation of a component - its dependency declaration
 * {@code d}, its effect function, and the effect domain the function's registrations belong to.
 *
 * <p>Fibers with an empty {@link #dependencies} set (plain plugins) are always satisfied and never
 * undergo reactive refresh; fibers with a non-empty set activate when every declared key resolves
 * from {@link #owner} and unload reactively when a binding they rely on is withdrawn.
 */
final class Fiber {

  final long uid;
  final ContextImpl owner;
  final Set<ServiceKey<?>> dependencies;

  /** The fiber's effect accumulator; replaced with a fresh scope on unload (paper L-Begin). */
  EffectScopeImpl domain = new EffectScopeImpl();

  /** The keys this fiber currently supplies, as declared at provide time (access mediation). */
  final Set<ServiceKey<?>> providedKeys = ConcurrentHashMap.newKeySet();

  final FiberBody body;

  /** Whether an activation failure propagates to the caller instead of being routed to unload. */
  final boolean propagateFailure;

  volatile FiberState state = FiberState.INACTIVE;

  /** Set when the fiber's handle was disposed; the fiber will not activate again. */
  volatile boolean retired;

  /** Set after a failed activation (paper Section 4.3.4): the fiber never retries. */
  boolean failed;

  Throwable failure;

  Fiber(
      long uid,
      ContextImpl owner,
      Set<ServiceKey<?>> dependencies,
      FiberBody body,
      boolean propagateFailure) {
    this.uid = uid;
    this.owner = owner;
    this.dependencies = dependencies;
    this.body = body;
    this.propagateFailure = propagateFailure;
  }

  /** Whether this fiber enforces dependency-declaration checks on service access (Algorithm 6). */
  boolean declarative() {
    return !dependencies.isEmpty();
  }
}
