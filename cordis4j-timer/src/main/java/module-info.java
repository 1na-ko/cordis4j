/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */

/** Cordis4j Timer: reversible one-shot and periodic timers over the core's spawn model. */
module io.cordis4j.timer {
  requires transitive io.cordis4j.core; // the exported API speaks core types

  exports io.cordis4j.timer;
}
