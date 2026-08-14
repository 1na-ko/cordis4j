/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Raised by dependency-declaration checks (paper Algorithm 6): a declarative component tried to
 * resolve a key outside its declaration and its own supplies.
 *
 * <p>Declarations are enforced while a component declared through {@code inject} runs; plain
 * plugins (no declaration) keep unrestricted access.
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
    this(key, "accessing while the declaring component is inactive");
  }

  /**
   * Constructs the exception with a detail naming the check that failed.
   *
   * @param key the key being accessed
   * @param detail the failing check, e.g. {@code undeclared access}
   */
  public InactiveAccessException(ServiceKey<?> key, String detail) {
    super("Access to " + key + " rejected: " + detail);
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
