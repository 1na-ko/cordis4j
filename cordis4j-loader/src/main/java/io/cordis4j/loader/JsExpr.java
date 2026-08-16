/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

/**
 * A delayed-evaluation scalar from a cordis configuration: the YAML {@code !!js} tag (the full form
 * {@code tag:yaml.org,2002:js}), exactly as upstream's include plugin parses it. The expression is
 * not evaluated here - see {@link ExpressionEvaluator}.
 *
 * @param expression the raw expression text, never null
 */
public record JsExpr(String expression) {

  /** Validates the expression. */
  public JsExpr {
    if (expression == null) {
      throw new NullPointerException("expression");
    }
  }
}
