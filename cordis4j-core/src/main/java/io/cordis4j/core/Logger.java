/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/** Minimal leveled logger, aligned with the upstream built-in logger service; zero dependencies. */
public interface Logger {

  /**
   * Logs a debug-level message.
   *
   * @param message the message template, "{}" placeholders allowed
   * @param args placeholder arguments
   * @throws NullPointerException if {@code message} is null
   */
  void debug(String message, Object... args);

  /**
   * Logs an info-level message.
   *
   * @param message the message template, "{}" placeholders allowed
   * @param args placeholder arguments
   * @throws NullPointerException if {@code message} is null
   */
  void info(String message, Object... args);

  /**
   * Logs a warn-level message.
   *
   * @param message the message template, "{}" placeholders allowed
   * @param args placeholder arguments
   * @throws NullPointerException if {@code message} is null
   */
  void warn(String message, Object... args);

  /**
   * Logs an error-level message.
   *
   * @param message the message template, "{}" placeholders allowed
   * @param args placeholder arguments
   * @throws NullPointerException if {@code message} is null
   */
  void error(String message, Object... args);

  /**
   * Returns a logger backed by {@link java.util.logging}.
   *
   * @param name the logger name
   * @return a JUL-backed logger
   * @throws NullPointerException if {@code name} is null
   */
  static Logger jul(String name) {
    java.util.logging.Logger delegate =
        java.util.logging.Logger.getLogger(Objects.requireNonNull(name, "name"));
    return new Logger() {
      @Override
      public void debug(String message, Object... args) {
        log(Level.FINE, message, args);
      }

      @Override
      public void info(String message, Object... args) {
        log(Level.INFO, message, args);
      }

      @Override
      public void warn(String message, Object... args) {
        log(Level.WARNING, message, args);
      }

      @Override
      public void error(String message, Object... args) {
        log(Level.SEVERE, message, args);
      }

      private void log(Level level, String message, Object... args) {
        delegate.log(
            new LogRecord(level, format(Objects.requireNonNull(message, "message"), args)));
      }
    };
  }

  /**
   * Substitutes "{}" placeholders with the string forms of {@code args}, left to right.
   *
   * <p>Placeholders without a matching argument are left as-is; surplus arguments are ignored.
   *
   * @param template the message template
   * @param args placeholder arguments
   * @return the formatted message
   * @throws NullPointerException if {@code template} is null
   */
  static String format(String template, Object... args) {
    Objects.requireNonNull(template, "template");
    StringBuilder builder = new StringBuilder(template.length() + 16);
    int cursor = 0;
    int index = 0;
    int placeholder;
    while ((placeholder = template.indexOf("{}", cursor)) >= 0) {
      builder.append(template, cursor, placeholder);
      builder.append(index < args.length ? String.valueOf(args[index++]) : "{}");
      cursor = placeholder + 2;
    }
    builder.append(template, cursor, template.length());
    return builder.toString();
  }
}
