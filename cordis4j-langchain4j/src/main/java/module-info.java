/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */

/** Cordis4j LangChain4j tool bridge: session tools that follow the reactive-coeffect lifecycle. */
module io.cordis4j.langchain4j {
  requires io.cordis4j.core;
  requires langchain4j.core;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.core;

  exports io.cordis4j.langchain4j;
}
