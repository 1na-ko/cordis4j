/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * The guard signal (paper, Section 4.3.2, L-Divert): thrown by {@link FiberHandle#checkDiverted()}
 * at an iteration boundary to stop a fiber whose target changed, letting the runtime revert the
 * effects accumulated so far.
 */
public class DivertedException extends CordisException {

  private static final long serialVersionUID = 1L;

  /** Constructs the signal. */
  public DivertedException() {
    super("Fiber target changed; diverting to the new target");
  }
}
