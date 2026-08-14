/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T2: fork isolation (paper, Section 3.3.1): children see parents, never the reverse. */
class ForkIsolationTest {

  @Test
  @DisplayName("T2 子上下文可见父服务；父不可见子注册；子 dispose 后父不受影响")
  void forkIsolatesChildRegistrations() {
    Context root = Contexts.create();
    Marker rootMarker = new Marker("root");
    root.provide(rootMarker);

    Context child = root.fork();
    assertSame(rootMarker, child.get(Marker.class), "子上下文必须可见父服务");

    child.provide(new Local());
    assertNotNull(child.get(Local.class), "子内注册必须在子内可见");
    assertTrue(root.find(Local.class).isEmpty(), "父不可见子注册");
    assertThrows(NoSuchServiceException.class, () -> root.get(Local.class));

    child.dispose();
    assertSame(rootMarker, root.get(Marker.class), "子 dispose 后父不受影响");
  }

  private static final class Marker {
    private final String name;

    private Marker(String name) {
      this.name = name;
    }

    @Override
    public String toString() {
      return "Marker[" + name + "]";
    }
  }

  private static final class Local {
    private Local() {}
  }
}
