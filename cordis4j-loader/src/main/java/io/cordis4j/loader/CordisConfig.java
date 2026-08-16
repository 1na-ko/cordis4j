/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.loader;

import io.cordis4j.core.CordisException;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Reads a cordis configuration tree - the document root is a list of entry rows, exactly upstream's
 * shape (a {@code cordis.yml} / {@code .yaml} / {@code .json} file). YAML documents parse with
 * upstream's delayed {@code !!js} tag preserved as {@link JsExpr}; JSON documents never contain
 * expressions.
 *
 * <p>Reading is when a missing {@code id} becomes a generated 8-hex-digit identifier (upstream's
 * {@code ensureId}, minus the write-back this module deliberately does not do): the parsed entry is
 * stable for as long as the host holds it, and hosts wanting stable reloads across re-reads should
 * give their entries explicit ids.
 */
public final class CordisConfig {

  /** The supported configuration formats, dispatched by file extension. */
  public enum Format {
    /** YAML with the {@code !!js} tag preserved. */
    YAML,
    /** Plain JSON. */
    JSON
  }

  /** The full form of upstream's {@code !!js} YAML tag. */
  private static final Tag JS_TAG = new Tag("tag:yaml.org,2002:js");

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(CordisConfig.class.getName());

  private CordisConfig() {}

  /**
   * Reads a configuration file, dispatching on its extension ({@code .yml}/{@code .yaml} for YAML,
   * {@code .json} for JSON).
   *
   * @param file the configuration file
   * @return the parsed entries, in document order
   * @throws IllegalArgumentException if the extension is neither YAML nor JSON
   * @throws CordisException if the file cannot be read, is not a list of entry rows, or a row is
   *     malformed
   * @throws NullPointerException if {@code file} is null
   */
  public static List<CordisEntry> read(Path file) {
    Objects.requireNonNull(file, "file");
    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
    Format format;
    if (name.endsWith(".yml") || name.endsWith(".yaml")) {
      format = Format.YAML;
    } else if (name.endsWith(".json")) {
      format = Format.JSON;
    } else {
      throw new IllegalArgumentException("unsupported configuration extension: " + name);
    }
    try (Reader reader = Files.newBufferedReader(file)) {
      return read(reader, format);
    } catch (IOException failure) {
      throw new CordisException("cannot read configuration: " + file, failure);
    }
  }

  /**
   * Reads a configuration document in the given format.
   *
   * @param reader the document source
   * @param format the format
   * @return the parsed entries, in document order
   * @throws CordisException if the document is not a list of entry rows or a row is malformed
   * @throws NullPointerException if any argument is null
   */
  public static List<CordisEntry> read(Reader reader, Format format) {
    Objects.requireNonNull(reader, "reader");
    Objects.requireNonNull(format, "format");
    Object document;
    try {
      document =
          switch (format) {
            case YAML -> newYaml().load(reader);
            case JSON ->
                new com.fasterxml.jackson.databind.ObjectMapper().readValue(reader, Object.class);
          };
    } catch (IOException failure) {
      throw new CordisException("malformed " + format + " configuration", failure);
    } catch (org.yaml.snakeyaml.error.YAMLException failure) {
      throw new CordisException("malformed YAML configuration", failure);
    }
    if (!(document instanceof List<?> rows)) {
      throw new CordisException("configuration root must be a list of entry rows");
    }
    List<CordisEntry> entries = new ArrayList<>(rows.size());
    for (Object row : rows) {
      entries.add(fromRow(row));
    }
    return List.copyOf(entries);
  }

  /** Parses one entry row (a map) into a {@link CordisEntry}, generating a missing id. */
  private static CordisEntry fromRow(Object row) {
    if (!(row instanceof Map<?, ?> map)) {
      throw new CordisException("each entry row must be a mapping, found: " + row);
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> fields = (Map<String, Object>) map;
    Object nameField = fields.get("name");
    if (!(nameField instanceof String name) || name.isBlank()) {
      throw new CordisException("each entry row requires a non-blank name field");
    }
    String id = ensureId(fields.get("id"));
    Object config = fields.get("config");
    boolean group = booleanField(fields.get("group"));
    if (group && config instanceof List<?> children) {
      List<CordisEntry> parsed = new ArrayList<>(children.size());
      for (Object child : children) {
        parsed.add(fromRow(child));
      }
      config = List.copyOf(parsed);
    }
    Map<String, Object> intercept = mapField(fields.get("intercept"));
    Map<String, Object> isolate = mapField(fields.get("isolate"));
    Map<String, Object> extras = new LinkedHashMap<>();
    for (Map.Entry<String, Object> field : fields.entrySet()) {
      switch (field.getKey()) {
        case "id", "name", "config", "group", "disabled", "inject", "intercept", "isolate" -> {
          // recognized: not an extra
        }
        default -> extras.put(field.getKey(), field.getValue());
      }
    }
    return new CordisEntry(
        id,
        name,
        config,
        group,
        booleanField(fields.get("disabled")),
        fields.get("inject"),
        intercept,
        isolate,
        extras);
  }

  private static boolean booleanField(Object value) {
    return Boolean.TRUE.equals(value);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapField(Object value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new CordisException("expected a mapping, found: " + value);
    }
    return new LinkedHashMap<>((Map<String, Object>) map);
  }

  /**
   * Upstream's {@code if (!options.id)} falsiness: a missing or empty id generates one; a blank
   * string (" ") or a non-string value stays verbatim (stringified), with a warning for the latter.
   */
  private static String ensureId(Object idField) {
    if (idField == null || "".equals(idField)) {
      return generatedId();
    }
    if (idField instanceof String existing) {
      return existing;
    }
    LOG.warning(() -> "entry id is not a string, stringified verbatim: " + idField);
    return String.valueOf(idField);
  }

  /**
   * Upstream's ensureId shape: 8 lowercase hex digits ({@code Math.random().toString(16).slice(2,
   * 10)} in the original).
   */
  private static String generatedId() {
    return Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000_0000, Integer.MAX_VALUE));
  }

  /**
   * A fresh {@link Yaml} per document: the parser is stateful and not safe to share across threads.
   * The tag inspector admits exactly the delayed-expression tag - SnakeYAML's default rejects every
   * global tag - and nothing else.
   */
  private static Yaml newYaml() {
    LoaderOptions options = new LoaderOptions();
    options.setTagInspector(tag -> JS_TAG.equals(tag));
    return new Yaml(
        new SafeConstructor(options) {
          {
            yamlConstructors.put(JS_TAG, new ConstructJsExpr());
          }
        });
  }

  /** Constructs the delayed-expression node for the {@code !!js} tag. */
  private static final class ConstructJsExpr extends AbstractConstruct {
    @Override
    public Object construct(Node node) {
      if (node instanceof ScalarNode scalar) {
        return new JsExpr(scalar.getValue());
      }
      throw new CordisException("the !!js tag applies to scalars only: " + node.getTag());
    }
  }
}
