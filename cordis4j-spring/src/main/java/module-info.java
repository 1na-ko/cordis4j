/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */

/** Cordis4j Spring: a Context bean and @CordisService beans that follow bean lifecycles. */
module io.cordis4j.spring {
  requires io.cordis4j.core;
  requires spring.beans;
  requires spring.context;
  requires spring.core;
  requires spring.aop;
  requires java.logging;

  exports io.cordis4j.spring;
}
