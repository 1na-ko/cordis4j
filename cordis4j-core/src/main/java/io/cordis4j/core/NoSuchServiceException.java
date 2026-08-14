/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Raised when a service lookup walks to the root without finding a binding (the paper's
 * undeclared-access failure, Algorithm 6, surfaced eagerly).
 */
public class NoSuchServiceException extends CordisException {

  private static final long serialVersionUID = 1L;

  private final transient ServiceKey<?> key;
  private final transient String lookupPath;

  /**
   * Constructs the exception.
   *
   * @param key the key that failed to resolve
   * @param lookupPath a description of the context chain that was searched
   */
  public NoSuchServiceException(ServiceKey<?> key, String lookupPath) {
    super("No service bound for " + key + "; " + lookupPath);
    this.key = key;
    this.lookupPath = lookupPath;
  }

  /**
   * Returns the key that failed to resolve.
   *
   * @return the service key, never null
   */
  public ServiceKey<?> key() {
    return key;
  }

  /**
   * Returns a description of the context chain that was searched.
   *
   * @return the lookup path, never null
   */
  public String lookupPath() {
    return lookupPath;
  }
}
