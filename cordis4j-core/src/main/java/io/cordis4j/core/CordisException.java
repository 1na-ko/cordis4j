/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/** Base type of all Cordis4j exceptions (decision D6). */
public class CordisException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Constructs an exception with a message.
   *
   * @param message the detail message
   */
  public CordisException(String message) {
    super(message);
  }

  /**
   * Constructs an exception with a message and cause.
   *
   * @param message the detail message
   * @param cause the cause
   */
  public CordisException(String message, Throwable cause) {
    super(message, cause);
  }
}
