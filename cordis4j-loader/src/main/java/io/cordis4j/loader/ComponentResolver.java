/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import io.cordis4j.core.Plugin;
import java.nio.file.Path;

/**
 * Decides what a cordis component name means on this host - the counterpart of upstream's module
 * resolution ({@code cordis:} builtins, bare npm names, paths relative to {@code baseUrl}, URLs).
 * The JVM has no import registry, so this is host policy: a builtins table, classpath lookup,
 * plugin jars, or a JavaScript bridge are all valid implementations.
 */
public interface ComponentResolver {

  /**
   * Resolves a component name from an entry row.
   *
   * @param name the entry's {@code name} field (a bare name, a path, or any host convention)
   * @param baseUrl the base directory path-like names resolve against
   * @return the plugin instance to load (the same name should yield a stable instance, or
   *     reconciles will reload)
   * @throws UnknownComponentException when the name resolves to nothing on this host
   */
  Plugin resolve(String name, Path baseUrl);

  /**
   * Resolves a service name from an {@code isolate} table or an {@code intercept} key to its JVM
   * type - string service names are a cordis convention; the JVM needs the {@link Class} to build
   * typed isolation realms and interception keys. The default rejects every name.
   *
   * @param serviceName the service name from the configuration
   * @return the service type
   * @throws UnknownComponentException when this host knows no such service
   */
  default Class<?> serviceType(String serviceName) {
    throw new UnknownComponentException(serviceName);
  }
}
