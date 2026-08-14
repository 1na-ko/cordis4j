/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.langchain4j;

import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * The cordis-side contract of an agent tool (paper, Section 6.4 in the ecosystem form): a service
 * implementing this interface can be exposed to a LangChain4j agent through a {@link
 * CordisToolRegistry}.
 *
 * <p>A tool service is provided under a realm-qualified key, conventionally {@code
 * ServiceKey.of(CordisTool.class, toolName)}. Providing it activates the registry entry, unloading
 * its plugin withdraws it (the reactive-coeffect lifecycle of the core), and replacing the binding
 * swaps the tool for its successor. The implementor supplies the tool's specification once, and
 * executes calls - {@code arguments} is the raw JSON object string of the agent's {@link
 * dev.langchain4j.agent.tool.ToolExecutionRequest} arguments.
 */
public interface CordisTool {

  /**
   * Returns the LangChain4j tool specification this tool advertises to agents.
   *
   * @return the tool specification, never null
   */
  ToolSpecification toolSpecification();

  /**
   * Executes one tool call.
   *
   * @param arguments the raw JSON arguments string of the agent's tool execution request
   * @return the result string reported back to the agent
   */
  String execute(String arguments);
}
