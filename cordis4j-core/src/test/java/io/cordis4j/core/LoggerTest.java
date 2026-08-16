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

  @Test
  @DisplayName("jul 转发的 LogRecord 保留 logger 名（默认构造会丢失为 null）")
  void julRecordsCarryTheLoggerName() {
    java.util.logging.Logger sink = java.util.logging.Logger.getLogger("cordis4j.test.named");
    java.util.List<java.util.logging.LogRecord> captured =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord record) {
            captured.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    sink.setUseParentHandlers(false);
    sink.addHandler(handler);
    try {
      Logger.jul("cordis4j.test.named").warn("carry the name");
      assertEquals(1, captured.size(), "必须发出一条日志");
      assertEquals(
          "cordis4j.test.named",
          captured.get(0).getLoggerName(),
          "LogRecord 的 loggerName 必须是委托 logger 的名字（默认构造为 null）");
    } finally {
      sink.removeHandler(handler);
    }
  }
}
