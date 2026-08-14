/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * A handle on the fiber currently executing on this thread (paper, Section 4.3.2, the guard of the
 * effect iterator).
 *
 * <p>Long-running work inside a component polls {@link #isDiverted()} - or calls {@link
 * #checkDiverted()} at iteration boundaries - to notice that its fiber's target changed: the plugin
 * was unloaded, or one of its declared dependencies was withdrawn. Returning or throwing at a guard
 * lets the runtime revert only the effects accumulated so far (the paper's partial rollback at
 * iteration boundaries).
 */
public interface FiberHandle {

  /**
   * Whether this fiber's target changed: it was retired, or a declared dependency no longer
   * resolves.
   *
   * @return true when the fiber should stop and let the runtime unload it
   */
  boolean isDiverted();

  /**
   * Throws {@link DivertedException} when {@link #isDiverted()} holds; the runtime treats it as the
   * signal to revert this fiber's accumulated effects.
   */
  void checkDiverted();
}
