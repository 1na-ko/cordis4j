/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Optional lifecycle hooks for services (extension D9; not part of the paper's semantics).
 *
 * <p>A provided object implementing this interface receives {@link #start()} when it is provided
 * within an active plugin domain, and {@link #stop()} when the providing domain is reverted. Stop
 * calls run in reverse order of provisioning (LIFO), and also when a binding is overwritten by a
 * new provisioning of the same key.
 */
public interface Service {

  /** Invoked when the service is provided. Default: no-op. */
  default void start() {}

  /** Invoked when the providing domain is reverted. Default: no-op. */
  default void stop() {}
}
