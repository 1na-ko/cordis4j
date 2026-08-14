/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.Disposable;

/** A fiber's effect function; allows checked exceptions for {@code AsyncPlugin} adapters. */
@FunctionalInterface
interface FiberBody {

  /**
   * Applies the component to its context.
   *
   * @param ctx the fiber's owner context
   * @return an extra cleanup disposable, never null
   * @throws Exception when the component fails; routed per paper Section 4.3.4
   */
  Disposable apply(ContextImpl ctx) throws Exception;
}
