/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

/**
 * The fiber currently executing on this thread (paper Algorithm 6's mediation point).
 *
 * <p>Registrations made through any context of the tree while a fiber's effect function runs are
 * routed into that fiber's domain; service lookups are checked against the fiber's declaration. The
 * holder is thread-local so that virtual-thread activations (paper Section 4.3.3) keep their own
 * fiber even when several run concurrently.
 */
final class Domains {

  private static final ThreadLocal<EffectScopeImpl> DOMAIN = new ThreadLocal<>();
  private static final ThreadLocal<Fiber> FIBER = new ThreadLocal<>();

  private Domains() {}

  /** The effect scope registrations on this thread belong to, or null for the ambient context. */
  static EffectScopeImpl domain() {
    return DOMAIN.get();
  }

  /** The fiber executing on this thread, or null outside any fiber activation. */
  static Fiber fiber() {
    return FIBER.get();
  }

  static void set(EffectScopeImpl domain, Fiber fiber) {
    DOMAIN.set(domain);
    FIBER.set(fiber);
  }
}
