/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */

/** Cordis4j Inject Processor: compile-time generation of zero-reflection injectors. */
module io.cordis4j.inject.processor {
  requires io.cordis4j.core;
  requires java.compiler;

  exports io.cordis4j.inject.processor;

  provides javax.annotation.processing.Processor with
      io.cordis4j.inject.processor.CordisInjectProcessor;
}
