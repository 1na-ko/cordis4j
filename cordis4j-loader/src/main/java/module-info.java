/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */

/**
 * Cordis4j Loader: the cordis configuration-format bridge - entry trees, patch layers, dsh
 * manifests, and the mapping onto the core's component composition.
 */
module io.cordis4j.loader {
  requires transitive io.cordis4j.core; // the exported API speaks core types
  requires java.logging;
  requires org.yaml.snakeyaml;
  requires com.fasterxml.jackson.databind;

  exports io.cordis4j.loader;
}
