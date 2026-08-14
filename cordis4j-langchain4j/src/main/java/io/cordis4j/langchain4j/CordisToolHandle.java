/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.Objects;
import java.util.function.Function;

/**
 * An active tool entry of a {@link CordisToolRegistry}: the specification the agent was shown plus
 * the executor that runs requests against the tool's current binding.
 *
 * <p>The executor is a live view keyed by the tool's service key, not a snapshot of the activation:
 * it always executes through the binding currently present in the session context, and fails with
 * {@link IllegalStateException} when the tool has been unloaded in the meantime.
 *
 * @param specification the tool specification advertised to agents, never null
 * @param executor runs a {@link ToolExecutionRequest} through the current binding of the tool's
 *     service key
 */
public record CordisToolHandle(
    ToolSpecification specification, Function<ToolExecutionRequest, String> executor) {

  /** Validates the components of this handle. */
  public CordisToolHandle {
    Objects.requireNonNull(specification, "specification");
    Objects.requireNonNull(executor, "executor");
  }
}
