/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** T4: synchronous bubbling from the emitting context up to the root (decision D3). */
class EventBubblingTest {

  @Test
  @DisplayName("T4 子 emit 依次触发子→父→根监听器；父 emit 不触子")
  void bubblesChildToRoot() {
    Context root = Contexts.create();
    Context child = root.fork();
    List<String> trace = new ArrayList<>();
    root.on(Note.class, note -> trace.add("root:" + note.text()));
    child.on(Note.class, note -> trace.add("child:" + note.text()));

    child.emit(new Note("x"));
    assertEquals(List.of("child:x", "root:x"), trace, "子 emit 必须从子到根依次分发");

    trace.clear();
    root.emit(new Note("y"));
    assertEquals(List.of("root:y"), trace, "父 emit 不得触达子监听器");
  }

  @Test
  @DisplayName("T4 撤销子监听后全链仅剩祖先监听；祖先撤销后全链静默")
  void unregisterSilencesChain() {
    Context root = Contexts.create();
    Context child = root.fork();
    List<String> trace = new ArrayList<>();
    Disposable rootRegistration = root.on(Note.class, note -> trace.add("root"));
    Disposable childRegistration = child.on(Note.class, note -> trace.add("child"));

    childRegistration.dispose();
    child.emit(new Note("x"));
    assertEquals(List.of("root"), trace);

    trace.clear();
    rootRegistration.dispose();
    child.emit(new Note("z"));
    assertEquals(List.of(), trace, "全链撤销后必须静默");
  }

  private record Note(String text) {}
}
