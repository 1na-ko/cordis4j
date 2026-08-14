/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */

/** Cordis4j Spring: a Context bean and @CordisService beans that follow bean lifecycles. */
module io.cordis4j.spring {
  requires io.cordis4j.core;
  requires spring.beans;

  exports io.cordis4j.spring;
}
