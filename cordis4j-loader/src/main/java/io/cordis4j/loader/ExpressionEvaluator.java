/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import io.cordis4j.core.Context;

/**
 * Evaluates {@link JsExpr} expressions against a context - the host-supplied counterpart of
 * upstream's {@code interpolate} (a {@code with (ctx) eval(expr)} in the TypeScript original).
 *
 * <p>Cordis4j deliberately ships no JavaScript engine: the module is a format bridge, not a
 * runtime. A host that wants upstream's exact {@code !!js} semantics plugs a GraalJS-backed
 * evaluator here; one that wants a safer dialect plugs its own. The default evaluator rejects every
 * expression with {@link UnsupportedOperationException}.
 */
@FunctionalInterface
public interface ExpressionEvaluator {

  /** The rejecting default: no expression evaluates without a host-provided engine. */
  ExpressionEvaluator NONE =
      (expression, context) -> {
        throw new UnsupportedOperationException(
            "no expression evaluator configured for: " + expression);
      };

  /**
   * Evaluates one delayed expression.
   *
   * @param expression the raw expression text
   * @param context the context the expression resolves against
   * @return the evaluated value (may be null)
   */
  Object evaluate(String expression, Context context);
}
