/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Raised when one or more reversion actions failed during disposal (paper, Section 4.3.4).
 *
 * <p>The individual failures are attached as suppressed exceptions; reversion of the remaining
 * effects still completes.
 */
public class DisposeException extends CordisException {

  private static final long serialVersionUID = 1L;

  /**
   * Constructs the exception.
   *
   * @param message the detail message
   */
  public DisposeException(String message) {
    super(message);
  }
}
