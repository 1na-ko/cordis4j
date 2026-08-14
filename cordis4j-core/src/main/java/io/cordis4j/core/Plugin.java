/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * A component (paper, Section 4.1): the effect function paired with the context it runs in.
 *
 * <p>{@link Context#plugin(Plugin)} runs {@link #apply(Context)} inside an implicit effect scope:
 * every registration made through the context during {@code apply} belongs to the plugin and is
 * reverted in LIFO order when the plugin's domain is disposed. The returned disposable is tracked
 * by the domain as an additional cleanup; return {@link Disposables#none()} when none is needed.
 *
 * <p>If {@code apply} throws, the registrations made so far are reverted (paper, Section 4.3.4) and
 * the exception is rethrown, with any reversion failures attached as suppressed exceptions.
 */
@FunctionalInterface
public interface Plugin {

  /**
   * Applies the component to a context.
   *
   * @param ctx the context the component runs in, never null
   * @return an extra cleanup disposable, never null
   */
  Disposable apply(Context ctx);
}
