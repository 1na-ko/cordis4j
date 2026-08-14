/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.spring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a Spring bean as a cordis4j service: {@link CordisServiceRegistrar} provides the bean into
 * the container's cordis4j {@link io.cordis4j.core.Context} after initialization, keyed by the
 * bean's concrete class and this annotation's {@link #qualifier()}; the binding is withdrawn when
 * the container closes (the temporal dimension of the paradigm over bean lifecycles).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CordisService {

  /**
   * The realm qualifier of the service key (decision D5); the default {@code ""} is the default
   * realm.
   *
   * @return the qualifier of the provided binding
   */
  String qualifier() default "";
}
