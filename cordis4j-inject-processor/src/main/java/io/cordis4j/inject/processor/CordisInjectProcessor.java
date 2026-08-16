/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.inject.processor;

import io.cordis4j.core.Inject;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * The compile-time half of paper Section 6.4's annotation-mediated access: for every class with
 * {@link Inject} fields it validates the fields at compile time and emits a zero-reflection
 * injector that wires them into one reactive declaration through {@link
 * io.cordis4j.core.Injects#injectFields(io.cordis4j.core.Context, List)}.
 *
 * <p>The injector covers the class's whole superclass hierarchy, mirroring the runtime form's
 * full-hierarchy scan (decision D21): {@code @Inject} fields declared on any superclass are wired
 * too. Constraints (all reported as compile errors, located on the offending field): the annotated
 * class must be a public top-level class, and each field - inherited ones included - must be
 * non-static, non-final, non-primitive, of an accessible reference type, and reachable from the
 * generated accessor (which lives in the class's own package and assigns fields directly): {@code
 * public} anywhere, anything non-private within the same package; a {@code private}, cross-package
 * {@code protected}, or cross-package package-private superclass field is a compile error. This is
 * the declared boundary between the two forms: the runtime form's reflection reaches private
 * superclass fields, the compile-time form does not - no reflection and no {@code opens} are needed
 * at runtime, at the cost of field visibility.
 */
@SupportedAnnotationTypes("io.cordis4j.core.Inject")
public final class CordisInjectProcessor extends AbstractProcessor {

  /** Creates the processor (service-loaded by javac through the module's provides clause). */
  public CordisInjectProcessor() {}

  /** Tracks the newest source version javac offers, not a fixed RELEASE_* constant (m-9). */
  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    Map<TypeElement, List<VariableElement>> fieldsByClass = new LinkedHashMap<>();
    boolean failed = false;
    for (Element element : roundEnv.getElementsAnnotatedWith(Inject.class)) {
      if (element.getKind() != ElementKind.FIELD) {
        error(element, "@Inject applies to fields only: " + element);
        failed = true;
        continue;
      }
      VariableElement field = (VariableElement) element;
      TypeElement owner = (TypeElement) field.getEnclosingElement();
      failed |= !validateOwn(field, owner);
      fieldsByClass.computeIfAbsent(owner, unused -> new ArrayList<>()).add(field);
    }
    if (failed) {
      return false;
    }
    for (Map.Entry<TypeElement, List<VariableElement>> entry : fieldsByClass.entrySet()) {
      generate(entry.getKey(), entry.getValue());
    }
    return false; // do not claim the annotation: other processors may also react to it
  }

  private boolean validateOwn(VariableElement field, TypeElement owner) {
    boolean ok = true;
    if (field.getModifiers().contains(Modifier.STATIC)) {
      error(field, "@Inject field must not be static: " + field);
      ok = false;
    }
    if (field.getModifiers().contains(Modifier.FINAL)) {
      error(field, "@Inject field must not be final: " + field);
      ok = false;
    }
    if (field.asType().getKind().isPrimitive()) {
      error(field, "@Inject field must have a reference type: " + field);
      ok = false;
    }
    if (field.getModifiers().contains(Modifier.PRIVATE)) {
      error(
          field,
          "@Inject field must not be private for compile-time generation (the generated accessor"
              + " assigns it directly): "
              + field);
      ok = false;
    }
    if (!owner.getModifiers().contains(Modifier.PUBLIC)) {
      error(owner, "@Inject fields require a public class: " + owner.getQualifiedName());
      ok = false;
    }
    if (!(owner.getEnclosingElement() instanceof PackageElement)) {
      error(
          owner,
          "@Inject fields are supported in top-level classes only: " + owner.getQualifiedName());
      ok = false;
    }
    if (packageOf(owner).isEmpty()) {
      error(owner, "@Inject fields require a named package: " + owner.getQualifiedName());
      ok = false;
    }
    return ok;
  }

  /**
   * Collects the {@code @Inject} fields of {@code owner}'s whole superclass chain (own fields
   * first), rejecting the ones the generated accessor cannot reach.
   */
  private List<VariableElement> collectHierarchy(TypeElement owner, List<VariableElement> own) {
    List<VariableElement> all = new ArrayList<>(own);
    for (TypeElement sup = superclass(owner); sup != null; sup = superclass(sup)) {
      for (Element enclosed : sup.getEnclosedElements()) {
        if (enclosed.getKind() != ElementKind.FIELD
            || enclosed.getAnnotation(Inject.class) == null) {
          continue;
        }
        VariableElement field = (VariableElement) enclosed;
        if (field.getModifiers().contains(Modifier.STATIC)
            || field.getModifiers().contains(Modifier.FINAL)
            || field.asType().getKind().isPrimitive()) {
          error(
              field,
              "inherited @Inject field must be non-static, non-final, and of a reference type: "
                  + field);
          continue;
        }
        if (!reachableFromInjector(field, owner, sup)) {
          error(
              field,
              "inherited @Inject field is not reachable by compile-time generation from "
                  + owner.getQualifiedName()
                  + " (public anywhere, or non-private within the same package; the runtime"
                  + " reflection form is the alternative): "
                  + field);
          continue;
        }
        all.add(field);
      }
    }
    return all;
  }

  /** Whether the injector generated next to {@code owner} can assign {@code field} directly. */
  private static boolean reachableFromInjector(
      VariableElement field, TypeElement owner, TypeElement declaring) {
    if (field.getModifiers().contains(Modifier.PRIVATE)) {
      return false;
    }
    if (field.getModifiers().contains(Modifier.PUBLIC)) {
      return true;
    }
    return packageOf(owner).equals(packageOf(declaring));
  }

  private static TypeElement superclass(TypeElement type) {
    TypeMirror sup = type.getSuperclass();
    if (!(sup instanceof DeclaredType declared)) {
      return null; // java.lang.Object or an interface: nothing to inherit
    }
    return (TypeElement) declared.asElement();
  }

  private void generate(TypeElement owner, List<VariableElement> ownFields) {
    String packageName = packageOf(owner);
    String simpleName = owner.getSimpleName().toString();
    String injectorName = simpleName + "CordisInjector";
    String qualified = packageName.isEmpty() ? injectorName : packageName + "." + injectorName;
    Types types = processingEnv.getTypeUtils();
    List<VariableElement> fields = collectHierarchy(owner, ownFields);
    List<String> hierarchy = new ArrayList<>();
    for (TypeElement sup = superclass(owner); sup != null; sup = superclass(sup)) {
      hierarchy.add(sup.getQualifiedName().toString());
    }
    List<String> targetBlocks = new ArrayList<>();
    for (VariableElement field : fields) {
      TypeMirror erased = types.erasure(field.asType());
      if (!typeAccessibleFrom(erased, owner)) {
        error(
            field,
            "@Inject field type "
                + erased
                + " is not accessible from "
                + owner.getQualifiedName()
                + "'s package (the generated injector references it directly): "
                + field);
        continue;
      }
      String erasedName = erased.toString();
      String qualifier = field.getAnnotation(Inject.class).qualifier();
      targetBlocks.add(
          "        new io.cordis4j.core.Injects.FieldTarget() {\n"
              + "          @Override\n"
              + "          public io.cordis4j.core.ServiceKey<?> key() {\n"
              + "            return io.cordis4j.core.ServiceKey.of("
              + erasedName
              + ".class, \""
              + escape(qualifier)
              + "\");\n"
              + "          }\n"
              + "\n"
              + "          @Override\n"
              + "          @SuppressWarnings(\"unchecked\")\n"
              + "          public void set(Object value) {\n"
              + "            instance."
              + field.getSimpleName()
              + " = ("
              + erasedName
              + ") value;\n"
              + "          }\n"
              + "        }");
    }
    if (targetBlocks.isEmpty()) {
      return; // nothing wireable survived validation; errors (if any) already report why
    }
    String targets = String.join(",\n", targetBlocks);
    String source =
        "package "
            + packageName
            + ";\n\n"
            + "/** Generated by cordis4j-inject-processor: zero-reflection accessors for @Inject fields"
            + (hierarchy.isEmpty() ? "" : " (including the superclass hierarchy " + hierarchy + ")")
            + ". */\n"
            + "public final class "
            + injectorName
            + " {\n\n"
            + "  private "
            + injectorName
            + "() {}\n\n"
            + "  /**\n"
            + "   * Wires the @Inject fields of {@code instance} into one reactive declaration"
            + " (decision D21).\n"
            + "   *\n"
            + "   * @param ctx the context declaring the fiber\n"
            + "   * @param instance the instance whose fields are injected\n"
            + "   * @return a disposable that retires the declaration and clears the fields\n"
            + "   */\n"
            + "  public static io.cordis4j.core.Disposable injectFields(\n"
            + "      io.cordis4j.core.Context ctx, "
            + simpleName
            + " instance) {\n"
            + "    return io.cordis4j.core.Injects.injectFields(\n"
            + "        ctx,\n"
            + "        java.util.List.<io.cordis4j.core.Injects.FieldTarget>of(\n"
            + targets
            + "            ));\n"
            + "  }\n"
            + "}\n";
    try {
      Filer filer = processingEnv.getFiler();
      try (Writer writer = filer.createSourceFile(qualified, owner).openWriter()) {
        writer.write(source);
      }
    } catch (IOException failure) {
      // A Filer conflict or I/O failure must surface as a compile diagnostic at the source
      // element, not as an unlocated runtime exception from inside javac.
      error(owner, "cannot write generated injector " + qualified + ": " + failure);
    }
  }

  /** Whether the generated code (in {@code owner}'s package) can name {@code erased}. */
  private static boolean typeAccessibleFrom(TypeMirror erased, TypeElement owner) {
    if (!(erased instanceof DeclaredType declared)
        || !(declared.asElement() instanceof TypeElement type)) {
      return true; // arrays and type variables erode to their bounds; javac resolves those
    }
    return type.getModifiers().contains(Modifier.PUBLIC)
        || packageOf(owner).equals(packageOf(type));
  }

  private static String packageOf(TypeElement owner) {
    Element parent = owner.getEnclosingElement();
    return parent instanceof PackageElement packageElement
        ? packageElement.getQualifiedName().toString()
        : "";
  }

  private static String escape(String text) {
    StringBuilder escaped = new StringBuilder(text.length() + 8);
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> escaped.append("\\\\");
        case '"' -> escaped.append("\\\"");
        case '\n' -> escaped.append("\\n");
        case '\r' -> escaped.append("\\r");
        case '\t' -> escaped.append("\\t");
        default -> {
          if (c < 0x20 || c > 0x7e) {
            escaped.append(String.format("\\u%04x", (int) c));
          } else {
            escaped.append(c);
          }
        }
      }
    }
    return escaped.toString();
  }

  private void error(Element element, String message) {
    Messager messager = processingEnv.getMessager();
    messager.printMessage(Diagnostic.Kind.ERROR, message, element);
  }
}
