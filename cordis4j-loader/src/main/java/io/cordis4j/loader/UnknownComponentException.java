/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import io.cordis4j.core.CordisException;

/**
 * Signals that a component name or service name could not be resolved by the host's {@link
 * ComponentResolver}: the JVM has no module registry, so where upstream's loader would import an
 * npm package or a {@code cordis:} builtin, the host decides what a name means.
 */
public final class UnknownComponentException extends CordisException {

  /**
   * Creates the exception.
   *
   * @param name the unresolvable name
   */
  public UnknownComponentException(String name) {
    super("no component or service type resolves the name: " + name);
  }
}
