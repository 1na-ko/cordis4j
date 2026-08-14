/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.util.Objects;

/**
 * A typed service key: the pair of a service type and a realm qualifier (paper, Section 3.2 and
 * Section 5.1.2).
 *
 * <p>The qualifier is Cordis4j's projection of the paper's realm symbol. In contexts that carry no
 * isolation override for {@link #type()}, bindings at distinct qualifiers are independent, so
 * several implementations of one interface coexist (paper, Section 6.2). An isolation override
 * redirects the key to the override realm for the whole subtree.
 *
 * @param <T> the service type
 * @param type the service interface, never null
 * @param qualifier the realm qualifier, never null; {@code ""} is the default realm
 */
public record ServiceKey<T>(Class<T> type, String qualifier) {

  /** Validates the components of this key. */
  public ServiceKey {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(qualifier, "qualifier");
  }

  /**
   * Returns a key with the default (empty) qualifier.
   *
   * @param <T> the service type
   * @param type the service interface
   * @return a default-realm key
   * @throws NullPointerException if {@code type} is null
   */
  public static <T> ServiceKey<T> of(Class<T> type) {
    return of(type, "");
  }

  /**
   * Returns a key for the given type and qualifier.
   *
   * @param <T> the service type
   * @param type the service interface
   * @param qualifier the realm qualifier
   * @return a realm-qualified key
   * @throws NullPointerException if {@code type} or {@code qualifier} is null
   */
  public static <T> ServiceKey<T> of(Class<T> type, String qualifier) {
    return new ServiceKey<>(type, qualifier);
  }
}
