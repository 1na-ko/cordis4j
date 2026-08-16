/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import io.cordis4j.core.Plugin;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Ready-made {@link ComponentResolver} shapes. */
public final class ComponentResolvers {

  private ComponentResolvers() {}

  /**
   * A resolver over fixed tables - the JVM form of upstream's {@code cordis:} builtins: names map
   * to plugin instances, service names map to types. Table instances are stable by construction, so
   * reconciles do not reload.
   *
   * @param components the component table (name to plugin instance)
   * @param serviceTypes the service-name table (name to JVM type), may be empty
   * @return a resolver backed by the given tables
   * @throws NullPointerException if any argument, key, or value is null
   */
  public static ComponentResolver builtins(
      Map<String, Plugin> components, Map<String, Class<?>> serviceTypes) {
    Objects.requireNonNull(components, "components");
    Objects.requireNonNull(serviceTypes, "serviceTypes");
    Map<String, Plugin> componentsCopy = Map.copyOf(components);
    Map<String, Class<?>> typesCopy = Map.copyOf(serviceTypes);
    return new ComponentResolver() {
      @Override
      public Plugin resolve(String name, Path baseUrl) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Plugin component = componentsCopy.get(name);
        if (component == null) {
          throw new UnknownComponentException(name);
        }
        return component;
      }

      @Override
      public Class<?> serviceType(String serviceName) {
        Objects.requireNonNull(serviceName, "serviceName");
        Class<?> type = typesCopy.get(serviceName);
        if (type == null) {
          throw new UnknownComponentException(serviceName);
        }
        return type;
      }
    };
  }

  /**
   * A resolver over a component table only; service names are rejected.
   *
   * @param components the component table (name to plugin instance)
   * @return a resolver backed by the given table
   * @throws NullPointerException if any argument, key, or value is null
   */
  public static ComponentResolver builtins(Map<String, Plugin> components) {
    return builtins(components, new LinkedHashMap<>());
  }
}
