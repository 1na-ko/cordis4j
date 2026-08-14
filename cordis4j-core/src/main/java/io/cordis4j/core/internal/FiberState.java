/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

/**
 * The lifecycle states of a fiber (paper, Section 4.2, synchronous form of Section 4.3).
 *
 * <p>The asynchronous inertial transitions (paper Section 4.3.3) collapse here: a synchronous
 * migration always runs to completion before the next one is considered, so LOADING and UNLOADING
 * are transient states observed only from inside the transition itself.
 */
enum FiberState {
  /** Registered but not running: dependencies unsatisfied, failed, or retired. */
  INACTIVE,
  /** The effect function is running inside its domain (the synchronous form of reloading). */
  LOADING,
  /** The effect function completed; the fiber's effects are live in the coeffect tables. */
  ACTIVE,
  /** The fiber's domain is being reverted, dependents drained first (paper Theorem 63). */
  UNLOADING
}
