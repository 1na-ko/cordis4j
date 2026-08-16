/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Raised when a synchronous activation re-enters a fiber that is already activating on the same
 * thread - a self-cycle where a body provides the very key its own fiber depends on (paper, Section
 * 4.4, Progress: the precedence relation must be acyclic for progress).
 *
 * <p>Mutually cyclic declarations do <em>not</em> raise this exception: like upstream, both fibers
 * simply stay INACTIVE because their dependencies are never satisfied - there is no recursive
 * activation to reject. The type stays part of the public API for the self-cycle path above.
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
