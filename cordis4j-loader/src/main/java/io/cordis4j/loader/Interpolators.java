/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import io.cordis4j.core.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Recursive replacement of {@link JsExpr} nodes in a configuration tree, the JVM form of upstream's
 * {@code interpolate}: maps and lists are walked, expression nodes go to the evaluator, everything
 * else passes through unchanged. The input tree is not modified.
 */
public final class Interpolators {

  private Interpolators() {}

  /**
   * Replaces every {@link JsExpr} in {@code tree} with its evaluation.
   *
   * @param tree the configuration tree (maps, lists, scalars, expression nodes)
   * @param evaluator the evaluator for expression nodes
   * @param context the context expressions resolve against
   * @return the interpolated tree, or null when {@code tree} is null
   * @throws NullPointerException if {@code evaluator} or {@code context} is null
   */
  public static Object interpolate(Object tree, ExpressionEvaluator evaluator, Context context) {
    Objects.requireNonNull(evaluator, "evaluator");
    Objects.requireNonNull(context, "context");
    return interpolateValue(tree, evaluator, context);
  }

  private static Object interpolateValue(
      Object value, ExpressionEvaluator evaluator, Context context) {
    if (value instanceof JsExpr expression) {
      return evaluator.evaluate(expression.expression(), context);
    }
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> interpolated = new LinkedHashMap<>(map.size());
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        interpolated.put(entry.getKey(), interpolateValue(entry.getValue(), evaluator, context));
      }
      return interpolated;
    }
    if (value instanceof List<?> list) {
      List<Object> interpolated = new ArrayList<>(list.size());
      for (Object item : list) {
        interpolated.add(interpolateValue(item, evaluator, context));
      }
      return interpolated;
    }
    return value;
  }
}
