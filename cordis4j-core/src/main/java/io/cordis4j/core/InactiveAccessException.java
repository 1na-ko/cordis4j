/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Reserved for dependency-declaration checks: accessing a service while the declaring component is
 * inactive (the paper's INACTIVE_ACCESS failure, Algorithm 6).
 *
 * <p>P1 only defines the type; it is thrown once P2 introduces dependency declarations.
 */
public class InactiveAccessException extends CordisException {

  private static final long serialVersionUID = 1L;

  private final transient ServiceKey<?> key;

  /**
   * Constructs the exception.
   *
   * @param key the key being accessed
   */
  public InactiveAccessException(ServiceKey<?> key) {
    super("Accessing " + key + " while its declaring component is inactive");
    this.key = key;
  }

  /**
   * Returns the key being accessed.
   *
   * @return the service key, never null
   */
  public ServiceKey<?> key() {
    return key;
  }
}
