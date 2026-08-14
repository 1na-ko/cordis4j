/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Raised when two distinct active fibers would supply the same store key (paper, Section 4.2,
 * supply uniqueness: the provide sets of distinct fibers are disjoint).
 *
 * <p>Ambient provisioning (outside any plugin domain) may still overwrite any binding, mirroring
 * the upstream {@code set} semantics of an administrator override.
 */
public class SupplyConflictException extends CordisException {

  private static final long serialVersionUID = 1L;

  private final transient ServiceKey<?> key;

  /**
   * Constructs the exception.
   *
   * @param key the contested key
   * @param message detail describing the two suppliers
   */
  public SupplyConflictException(ServiceKey<?> key, String message) {
    super("Two active components supply " + key + ": " + message);
    this.key = key;
  }

  /**
   * Returns the contested key.
   *
   * @return the key two fibers tried to supply, never null
   */
  public ServiceKey<?> key() {
    return key;
  }
}
