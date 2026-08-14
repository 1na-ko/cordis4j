/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

/**
 * Interception metadata that knows how to combine along the context chain (paper, Section 5.1.2,
 * the metadata monoid of the intercept handler).
 *
 * <p>When every binding for a key along the chain implements this interface, {@code
 * Context.interceptOf} merges them from the root toward the lookup origin - the binding declared
 * nearer to the lookup wins on conflict, mirroring the paper's right-biased merge. Metadata that
 * does not implement this interface keeps nearest-wins semantics.
 */
public interface InterceptMetadata {

  /**
   * Merges {@code nearer} over this metadata.
   *
   * @param nearer the metadata declared closer to the lookup origin, never null
   * @return the combined metadata, never null
   */
  InterceptMetadata merge(InterceptMetadata nearer);
}
