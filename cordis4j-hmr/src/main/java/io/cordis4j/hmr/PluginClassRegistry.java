/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks loaded plugin code by id: the registry of Section 6.4's managed-runtime requirement, whose
 * entries can be evicted and observed.
 *
 * <p>Installing a handle for an id detaches the previous one (dropping the strong reference to the
 * old plugin instance), so replaced or uninstalled code can be garbage-collected; {@link
 * PluginHandle#collected()} reports when it has been. Uninstalling an id keeps its detached handle
 * in place for that observation, until a new install replaces it.
 */
public final class PluginClassRegistry {

  private final Map<String, PluginHandle> handles = new LinkedHashMap<>();

  private PluginClassRegistry() {}

  /**
   * Creates an empty registry.
   *
   * @return a new registry
   */
  public static PluginClassRegistry create() {
    return new PluginClassRegistry();
  }

  /**
   * Installs a handle under an id, detaching the previous handle if any.
   *
   * @param id the entry id
   * @param handle the loaded plugin
   * @return {@code handle}
   * @throws NullPointerException if {@code id} or {@code handle} is null
   */
  public synchronized PluginHandle install(String id, PluginHandle handle) {
    if (id == null) {
      throw new NullPointerException("id");
    }
    if (handle == null) {
      throw new NullPointerException("handle");
    }
    PluginHandle previous = handles.put(id, handle);
    if (previous != null) {
      previous.detach();
    }
    return handle;
  }

  /**
   * Returns the current handle of an id.
   *
   * @param id the entry id
   * @return the handle, or empty when the id is unknown
   * @throws NullPointerException if {@code id} is null
   */
  public synchronized Optional<PluginHandle> handle(String id) {
    if (id == null) {
      throw new NullPointerException("id");
    }
    return Optional.ofNullable(handles.get(id));
  }

  /**
   * Detaches the handle of an id: the plugin instance reference is dropped so its code becomes
   * collectable, while the detached handle stays observable through {@link
   * PluginHandle#collected()}.
   *
   * @param id the entry id
   * @throws NullPointerException if {@code id} is null
   */
  public synchronized void uninstall(String id) {
    if (id == null) {
      throw new NullPointerException("id");
    }
    PluginHandle handle = handles.get(id);
    if (handle != null) {
      handle.detach();
    }
  }
}
