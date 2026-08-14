/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

/**
 * Signals a plugin jar that cannot be read or whose plugin class cannot be instantiated - the
 * loading half of a failed hot reload.
 */
public class Cordis4jPluginException extends RuntimeException {

  /**
   * Creates the exception.
   *
   * @param message the failure description
   * @param cause the underlying failure, or null
   */
  public Cordis4jPluginException(String message, Throwable cause) {
    super(message, cause);
  }
}
