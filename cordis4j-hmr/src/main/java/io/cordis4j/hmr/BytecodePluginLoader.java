/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

import io.cordis4j.core.Plugin;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;

/**
 * Loads a plugin jar into its own class loader whose parent is the cordis4j-core loader, so the
 * plugin code and the runtime share the {@link Plugin} type identity (the stage-1 engine of
 * docs/design/hmr-evaluation.md: zero dependency, introduce/retract by loader reachability).
 *
 * <p>The jar must contain a public class implementing {@link Plugin} with a public no-argument
 * constructor; {@link #load(Path)} discovers it (exactly one implementation required), {@link
 * #load(Path, String)} takes its binary name explicitly. Loading the jar is a revertible effect in
 * the paper's sense: the returned handle holds the code strongly until it is unloaded or replaced,
 * and then lets the garbage collector reclaim it.
 */
public final class BytecodePluginLoader {

  private BytecodePluginLoader() {}

  /**
   * Loads the unique public {@link Plugin} implementation of a jar.
   *
   * @param jar the plugin jar
   * @return the handle of the loaded plugin
   * @throws IllegalArgumentException if the jar declares zero or several plugin implementations
   * @throws Cordis4jPluginException if the jar is unreadable or the plugin class cannot be
   *     instantiated
   * @throws NullPointerException if {@code jar} is null
   */
  public static PluginHandle load(Path jar) {
    Objects.requireNonNull(jar, "jar");
    List<String> candidates = new ArrayList<>();
    try (JarFile archive = new JarFile(jar.toFile())) {
      archive.stream()
          .filter(entry -> entry.getName().endsWith(".class") && !entry.isDirectory())
          .map(entry -> toClassName(entry.getName()))
          .filter(name -> name != null)
          .forEach(candidates::add);
    } catch (IOException e) {
      throw new Cordis4jPluginException("cannot read plugin jar: " + jar, e);
    }
    URLClassLoader loader = newLoader(jar);
    try {
      List<String> implementations = new ArrayList<>();
      for (String name : candidates) {
        Class<?> type;
        try {
          type = Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
          continue; // the entry does not load under its own name; it cannot be the plugin class
        }
        if (isInstantiablePlugin(type)) {
          implementations.add(name);
        }
      }
      if (implementations.isEmpty()) {
        throw new IllegalArgumentException(
            "no public Plugin implementation found in " + jar + "; pass the main class explicitly");
      }
      if (implementations.size() > 1) {
        throw new IllegalArgumentException(
            "several Plugin implementations in "
                + jar
                + ": "
                + implementations
                + "; pass the main class explicitly");
      }
      return instantiate(implementations.get(0), loader, jar);
    } catch (RuntimeException | LinkageError e) {
      closeQuietly(loader);
      throw e;
    }
  }

  /**
   * Loads the named plugin class of a jar.
   *
   * @param jar the plugin jar
   * @param mainClass the binary name of the class implementing {@link Plugin}
   * @return the handle of the loaded plugin
   * @throws IllegalArgumentException if {@code mainClass} does not name an instantiable {@link
   *     Plugin} implementation
   * @throws Cordis4jPluginException if the jar is unreadable or the class cannot be instantiated
   * @throws NullPointerException if {@code jar} or {@code mainClass} is null
   */
  public static PluginHandle load(Path jar, String mainClass) {
    Objects.requireNonNull(jar, "jar");
    Objects.requireNonNull(mainClass, "mainClass");
    URLClassLoader loader = newLoader(jar);
    try {
      return instantiate(mainClass, loader, jar);
    } catch (RuntimeException | LinkageError e) {
      closeQuietly(loader);
      throw e;
    }
  }

  private static PluginHandle instantiate(String name, URLClassLoader loader, Path jar) {
    try {
      Class<?> type = Class.forName(name, true, loader);
      if (!isInstantiablePlugin(type)) {
        throw new IllegalArgumentException(
            name + " in " + jar + " is not a public non-abstract Plugin with a no-arg constructor");
      }
      return PluginHandle.of((Plugin) type.getDeclaredConstructor().newInstance(), loader);
    } catch (ReflectiveOperationException e) {
      throw new Cordis4jPluginException("cannot instantiate plugin " + name + " from " + jar, e);
    }
  }

  private static URLClassLoader newLoader(Path jar) {
    try {
      return new URLClassLoader(new URL[] {jar.toUri().toURL()}, Plugin.class.getClassLoader());
    } catch (IOException e) {
      throw new Cordis4jPluginException("cannot open plugin jar: " + jar, e);
    }
  }

  private static void closeQuietly(URLClassLoader loader) {
    try {
      loader.close();
    } catch (IOException ignored) {
      // nothing was loaded from this brand-new loader yet; nothing to release
    }
  }

  private static boolean isInstantiablePlugin(Class<?> type) {
    return Plugin.class.isAssignableFrom(type)
        && !type.isInterface()
        && !Modifier.isAbstract(type.getModifiers())
        && Modifier.isPublic(type.getModifiers());
  }

  private static String toClassName(String entryName) {
    if (entryName.startsWith("META-INF/")
        || entryName.contains("$")
        || entryName.endsWith("module-info.class")) {
      return null; // service metadata, nested classes, and descriptors never name the entry class
    }
    return entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
  }
}
