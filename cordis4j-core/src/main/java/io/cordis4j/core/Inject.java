/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one injected service field (paper, Section 6.4: annotation-mediated dependency access).
 *
 * <p>A non-static, non-final, non-primitive field carrying this annotation becomes one declared
 * dependency of its instance: {@link Injects#injectFields(Context, Object)} turns every annotated
 * field into the {@link ServiceKey} of the field's type and this annotation's {@link #qualifier()},
 * and wires the instance into the reactive-coeffect machinery of {@link Context#inject(Set,
 * Function)}.
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Inject {

  /**
   * The realm qualifier of the dependency (decision D5); the default {@code ""} is the default
   * realm.
   *
   * @return the qualifier of the required binding
   */
  String qualifier() default "";
}
