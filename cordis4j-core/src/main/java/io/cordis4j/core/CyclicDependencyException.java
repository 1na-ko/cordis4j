/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Raised when reactive activation would revisit a fiber that is already activating, meaning the
 * dependency relation has a cycle (paper, Section 4.4, Progress: the precedence relation must be
 * acyclic for progress).
 *
 * <p>The message describes the cycle as a chain of fiber components.
 */
public class CyclicDependencyException extends CordisException {

  private static final long serialVersionUID = 1L;

  /**
   * Constructs the exception.
   *
   * @param cycle the activating chain that closes onto itself, described fiber by fiber
   */
  public CyclicDependencyException(String cycle) {
    super("Cyclic component dependency: " + cycle);
  }
}
