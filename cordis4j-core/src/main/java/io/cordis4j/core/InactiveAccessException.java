/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Raised by dependency-declaration checks (paper Algorithm 6): a declarative component tried to
 * resolve a key outside its declaration and its own supplies, or resolved a declared key through a
 * view that does not carry it.
 *
 * <p>The detail message discriminates the paper's two failure kinds (D31): {@code undeclared
 * access} (UNDECLARED_ACCESS - the key is outside the component's declarations) and {@code inactive
 * access} (INACTIVE_ACCESS - declared or self-supplied, but not committed in the accessed view).
 * Plain lookups outside a declarative component keep the store semantics instead
 * (NoSuchServiceException).
 *
 * <p>Declarations are enforced while a component declared through {@code inject} runs; plain
 * plugins (no declaration) keep unrestricted access.
 */
public class InactiveAccessException extends CordisException {

  private static final long serialVersionUID = 1L;

  private final transient ServiceKey<?> key;

  /**
   * Constructs the exception for the inactive kind (declared but uncommitted in the accessed view).
   *
   * @param key the key being accessed
   */
  public InactiveAccessException(ServiceKey<?> key) {
    this(key, "inactive access");
  }

  /**
   * Constructs the exception with a detail naming the check that failed.
   *
   * @param key the key being accessed
   * @param detail the failing check: {@code undeclared access} or {@code inactive access} (D31)
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
