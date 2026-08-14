/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */

/** Cordis4j HMR: bytecode-level hot module replacement on a custom ClassLoader engine. */
module io.cordis4j.hmr {
  requires io.cordis4j.core;
  requires static java.compiler; // the tests compile plugin sources at test time

  exports io.cordis4j.hmr;
}
