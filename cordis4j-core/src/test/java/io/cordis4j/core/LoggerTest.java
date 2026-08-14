/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link Logger}. */
class LoggerTest {

  @Test
  @DisplayName("format 依次替换 {} 占位符；缺失保留原样，多余忽略")
  void formatSubstitutesPlaceholders() {
    assertEquals("hello world!", Logger.format("hello {}!", "world"));
    assertEquals("a b {}c", Logger.format("a {} {}c", "b"));
    assertEquals("no placeholders", Logger.format("no placeholders", 1, 2));
  }

  @Test
  @DisplayName("format 拒绝 null 模板")
  void formatRejectsNull() {
    assertThrows(NullPointerException.class, () -> Logger.format(null));
  }

  @Test
  @DisplayName("jul(name) 冒烟：各级日志不抛异常")
  void julLoggerSmoke() {
    Logger logger = Logger.jul("cordis4j.test");
    assertDoesNotThrow(
        () -> {
          logger.debug("debug {}", 1);
          logger.info("info");
          logger.warn("warn {}", "w");
          logger.error("error");
        });
  }
}
