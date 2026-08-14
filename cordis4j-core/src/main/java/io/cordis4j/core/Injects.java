/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Assembles {@link Inject}-annotated fields into a reactive-coeffect declaration (paper, Section
 * 6.4: absent transparent access interception, runtime reflection mediates dependency access;
 * compile-time generation supplies the same declaration through {@link #injectFields(Context,
 * List)} with generated zero-reflection accessors).
 *
 * <p>{@link #injectFields(Context, Object)} scans the instance's class hierarchy (up to, but not
 * including, {@code Object}) for annotated fields and declares one fiber through {@link
 * Context#inject(Set, Function)}: when every field's key resolves, each field is set to its
 * binding; when a relied binding is withdrawn, every annotated field is cleared to {@code null} and
 * the declaration re-activates when the dependency returns. A field therefore holds an
 * activation-time snapshot, never a live view - the closest Java equivalent of the paper's mediated
 * access without bytecode generation.
 *
 * <p>Annotated fields must be non-static, non-final, and of a reference type; anything else fails
 * assembly with {@link IllegalArgumentException}. Reflection access requires the field's declaring
 * class to be open to this module: in the module path, the target's package must be {@code open} to
 * {@code io.cordis4j.core} (or the field must be accessible), which is the standard constraint of
 * reflection-based injection on the JVM.
 */
public final class Injects {

  private Injects() {}

  /**
   * One injected field of an instance: its service key and the assignment that writes the current
   * binding into it. The setter receives {@code null} when the declaration unloads, clearing the
   * field.
   *
   * <p>This is the accessor shape of paper Section 6.4: the runtime form implements it with
   * reflection, and the compile-time processor generates it as direct field assignments.
   */
  public interface FieldTarget {

    /**
     * Returns the service key of the field's dependency.
     *
     * @return the key, never null
     */
    ServiceKey<?> key();

    /**
     * Writes a value into the field: the resolved binding on activation, {@code null} on unload.
     *
     * @param value the binding to store, or null to clear
     */
    void set(Object value);
  }

  /**
   * Wires the given field targets to {@code context} as one reactive declaration (decision D21):
   * the returned fiber activates - setting every field - as soon as all of their keys resolve,
   * clears them (in reverse order) when a relied binding is withdrawn, and re-activates when the
   * declaration is satisfied again. The targets must not change while the declaration runs.
   *
   * @param context the context declaring the fiber
   * @param targets the fields to wire, in population order
   * @return a disposable that retires the declaration and clears the fields
   * @throws IllegalStateException if {@code context} is disposed
   * @throws NullPointerException if {@code context}, {@code targets}, or any element is null
   */
  public static Disposable injectFields(Context context, List<FieldTarget> targets) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(targets, "targets");
    if (targets.isEmpty()) {
      return Disposables.none();
    }
    Set<ServiceKey<?>> dependencies = new LinkedHashSet<>();
    for (FieldTarget target : targets) {
      Objects.requireNonNull(target, "target");
      dependencies.add(Objects.requireNonNull(target.key(), "target key"));
    }
    return context.inject(
        dependencies,
        ctx -> {
          for (FieldTarget target : targets) {
            target.set(ctx.get(target.key()));
          }
          return Disposables.of(
              () -> {
                for (int i = targets.size() - 1; i >= 0; i--) {
                  targets.get(i).set(null);
                }
              });
        });
  }

  /**
   * Wires the {@link Inject}-annotated fields of {@code instance} to {@code context} as one
   * reactive declaration (decision D21): the returned fiber activates - populating every annotated
   * field - as soon as all of their keys resolve, clears them when a relied binding is withdrawn,
   * and re-activates when the declaration is satisfied again.
   *
   * <p>Fields are populated in declaration order (subclass first, superclasses after, declaration
   * order within a class) and cleared in reverse. An instance without annotated fields is a no-op
   * ({@link Disposables#none()}). Assembly fails fast on static, final, or primitive annotated
   * fields; resolution and access failures inside an activation follow the failure routing of
   * decision D14 (reverted, recorded, never propagated, never retried).
   *
   * @param context the context declaring the fiber
   * @param instance the instance whose annotated fields are injected
   * @return a disposable that retires the declaration and clears the fields
   * @throws IllegalArgumentException if an annotated field is static, final, or primitive
   * @throws IllegalStateException if {@code context} is disposed
   * @throws NullPointerException if {@code context} or {@code instance} is null
   */
  public static Disposable injectFields(Context context, Object instance) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(instance, "instance");
    Map<Class<?>, List<Field>> declaredByClass = new LinkedHashMap<>();
    for (Class<?> type = instance.getClass();
        type != null && type != Object.class;
        type = type.getSuperclass()) {
      List<Field> annotated = new ArrayList<>();
      for (Field field : type.getDeclaredFields()) {
        if (field.isAnnotationPresent(Inject.class)) {
          requireInjectable(field);
          field.setAccessible(true);
          annotated.add(field);
        }
      }
      if (!annotated.isEmpty()) {
        declaredByClass.put(type, annotated);
      }
    }
    if (declaredByClass.isEmpty()) {
      return Disposables.none();
    }
    List<FieldTarget> targets = new ArrayList<>();
    for (List<Field> annotated : declaredByClass.values()) {
      for (Field field : annotated) {
        ServiceKey<?> key = ServiceKey.of(field.getType(), qualifierOf(field));
        targets.add(new ReflectiveTarget(key, field, instance));
      }
    }
    return injectFields(context, targets);
  }

  /** A {@link FieldTarget} that writes through reflection. */
  private record ReflectiveTarget(ServiceKey<?> key, Field field, Object instance)
      implements FieldTarget {

    @Override
    public void set(Object value) {
      try {
        field.set(instance, value);
      } catch (IllegalAccessException e) {
        throw new IllegalStateException("inaccessible @Inject field: " + field, e);
      }
    }
  }

  private static void requireInjectable(Field field) {
    int modifiers = field.getModifiers();
    if (Modifier.isStatic(modifiers)) {
      throw new IllegalArgumentException("@Inject field must not be static: " + field);
    }
    if (Modifier.isFinal(modifiers)) {
      throw new IllegalArgumentException("@Inject field must not be final: " + field);
    }
    if (field.getType().isPrimitive()) {
      throw new IllegalArgumentException("@Inject field must have a reference type: " + field);
    }
  }

  private static String qualifierOf(Field field) {
    return field.getAnnotation(Inject.class).qualifier();
  }
}
