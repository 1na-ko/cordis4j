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
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * The compile-time half of paper Section 6.4's annotation-mediated access: for every class with
 * {@link Inject} fields it validates the fields at compile time and emits a zero-reflection
 * injector that wires them into one reactive declaration through {@link
 * io.cordis4j.core.Injects#injectFields(io.cordis4j.core.Context, List)}.
 *
 * <p>Constraints (all reported as compile errors): the annotated class must be a public top-level
 * class, and each field must be non-static, non-final, non-primitive, and non-private - the
 * generated accessors assign the fields directly, so no reflection and no {@code opens} are needed
 * at runtime, at the cost of field visibility.
 */
@SupportedAnnotationTypes("io.cordis4j.core.Inject")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class CordisInjectProcessor extends AbstractProcessor {

  /** Creates the processor (service-loaded by javac through the module's provides clause). */
  public CordisInjectProcessor() {}

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
      failed |= !validate(field, owner);
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

  private boolean validate(VariableElement field, TypeElement owner) {
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

  private void generate(TypeElement owner, List<VariableElement> fields) {
    String packageName = packageOf(owner);
    String simpleName = owner.getSimpleName().toString();
    String injectorName = simpleName + "CordisInjector";
    String qualified = packageName.isEmpty() ? injectorName : packageName + "." + injectorName;
    Types types = processingEnv.getTypeUtils();
    List<String> targetBlocks = new ArrayList<>();
    for (VariableElement field : fields) {
      String erased = types.erasure(field.asType()).toString();
      String qualifier = field.getAnnotation(Inject.class).qualifier();
      targetBlocks.add(
          "        new io.cordis4j.core.Injects.FieldTarget() {\n"
              + "          @Override\n"
              + "          public io.cordis4j.core.ServiceKey<?> key() {\n"
              + "            return io.cordis4j.core.ServiceKey.of("
              + erased
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
              + erased
              + ") value;\n"
              + "          }\n"
              + "        }");
    }
    String targets = String.join(",\n", targetBlocks);
    String source =
        "package "
            + packageName
            + ";\n\n"
            + "/** Generated by cordis4j-inject-processor: zero-reflection accessors for @Inject fields. */\n"
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
    } catch (IOException e) {
      throw new IllegalStateException("cannot write generated injector " + qualified, e);
    }
  }

  private static String packageOf(TypeElement owner) {
    Element parent = owner.getEnclosingElement();
    return parent instanceof PackageElement packageElement
        ? packageElement.getQualifiedName().toString()
        : "";
  }

  private static String escape(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private void error(Element element, String message) {
    Messager messager = processingEnv.getMessager();
    messager.printMessage(Diagnostic.Kind.ERROR, message, element);
  }
}
