/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import io.cordis4j.core.Context;
import io.cordis4j.core.CordisException;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.ServiceKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The reactive bridge between a cordis session context and a LangChain4j agent's tool list: it
 * watches declared {@link CordisTool} service keys through {@link Context#inject(java.util.Set,
 * java.util.function.Function)} (Algorithm 3) so that the tool set mirrors the session's plugin
 * tree.
 *
 * <p>Declared tools follow the reactive-coeffect lifecycle: a tool appears the moment its key
 * resolves (its provider plugin loads), vanishes when the relied binding is withdrawn (its plugin
 * unloads), and is replaced when a different implementation is provided later. Ending the session
 * (disposing the context) reverts everything, and disposing this registry retires every declaration
 * and silences its listeners.
 *
 * <p>A tool whose specification build fails is routed like any failed activation of the core
 * (failure routed, recorded, never retried): the registry stays intact, the failing tool never
 * appears. Listener failures are isolated and logged, never routed into the activation that
 * notified them.
 *
 * <p>Threading (core decision D19): registry state is guarded by an internal monitor; user code -
 * change listeners - always runs outside it, on the thread that mutates the session (the thread
 * providing or unloading a plugin).
 */
public final class CordisToolRegistry implements Disposable {

  private static final String LISTENER_LOGGER = "io.cordis4j.langchain4j.tool";

  private final Context session;
  private final Set<ServiceKey<CordisTool>> declared = new LinkedHashSet<>();
  private final Map<ServiceKey<CordisTool>, CordisToolHandle> active = new LinkedHashMap<>();
  private final Map<ServiceKey<CordisTool>, Disposable> declarations = new LinkedHashMap<>();
  private final List<Runnable> listeners = new ArrayList<>();
  private final Disposable guard;
  private boolean closed;

  private CordisToolRegistry(Context session) {
    this.session = session;
    this.guard = Disposables.of(this::retireAll);
  }

  /**
   * Creates a registry watching {@code session}: the context whose {@link CordisTool} bindings are
   * reflected to the agent, typically one conversation's forked context.
   *
   * @param session the session context
   * @return a new registry
   * @throws NullPointerException if {@code session} is null
   */
  public static CordisToolRegistry create(Context session) {
    Objects.requireNonNull(session, "session");
    return new CordisToolRegistry(session);
  }

  /**
   * Declares one tool key: while a binding resolves under {@code key}, the registry carries the
   * tool's handle; a withdrawn binding removes it; a re-provided binding replaces it. The key is
   * conventionally {@code ServiceKey.of(CordisTool.class, toolName)}.
   *
   * <p>Disposing the returned disposable retires the declaration permanently (the tool then never
   * reappears, mirroring {@link Context#inject(java.util.Set, java.util.function.Function)}
   * retirement).
   *
   * @param key the tool service key to watch
   * @return a disposable that retires the declaration
   * @throws IllegalArgumentException if {@code key} is already declared
   * @throws IllegalStateException if this registry is disposed
   * @throws NullPointerException if {@code key} is null
   */
  public Disposable declare(ServiceKey<CordisTool> key) {
    Objects.requireNonNull(key, "key");
    synchronized (this) {
      requireOpen();
      if (!declared.add(key)) {
        throw new IllegalArgumentException("tool key already declared: " + key);
      }
    }
    Disposable fiber;
    try {
      fiber =
          session.inject(
              key,
              (ctx, tool) -> {
                CordisToolHandle handle =
                    new CordisToolHandle(
                        tool.toolSpecification(), request -> execute(key, request));
                synchronized (this) {
                  if (closed) {
                    return Disposables.none();
                  }
                  String name = tool.toolSpecification().name();
                  for (CordisToolHandle existing : active.values()) {
                    if (name.equals(existing.specification().name())) {
                      throw new CordisException(
                          "duplicate tool specification name: '" + name + "' (key " + key + ")");
                    }
                  }
                  active.put(key, handle);
                }
                notifyChanged();
                return Disposables.of(
                    () -> {
                      synchronized (this) {
                        active.remove(key, handle);
                      }
                      notifyChanged();
                    });
              });
    } catch (RuntimeException failure) {
      synchronized (this) {
        declared.remove(key);
      }
      throw failure;
    }
    Disposable wrapper =
        Disposables.of(
            () -> {
              fiber.dispose();
              synchronized (this) {
                declared.remove(key);
                active.remove(key);
                declarations.remove(key);
              }
              // No extra notification here: when the fiber ever activated, its own cleanup
              // already notified the removal; when it never did, the active set never changed.
            });
    boolean racedDispose;
    synchronized (this) {
      if (closed) {
        racedDispose = true; // a dispose ran between the declaration and this registration
      } else {
        declarations.put(key, wrapper);
        racedDispose = false;
      }
    }
    if (racedDispose) {
      wrapper.dispose(); // retire right here instead of leaking the declaration into the session
    }
    return wrapper;
  }

  /**
   * Registers a listener notified after every change of the active tool set: a tool appearance,
   * removal, or replacement. The listener runs outside the registry monitor, on the mutating
   * thread; a throwing listener is isolated (logged, other listeners still run, and the mutation
   * that notified it is not failed).
   *
   * <p>A notification that fires while a tool's declaration activates runs inside that fiber's
   * domain, so declaration mediation of the core applies (decision D13): the listener must not
   * perform service lookups on the session there, only read the registry.
   *
   * @param listener the listener, never null
   * @return a disposable that unregisters the listener
   * @throws NullPointerException if {@code listener} is null
   */
  public Disposable onChange(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    synchronized (this) {
      listeners.add(listener);
    }
    return Disposables.of(
        () -> {
          synchronized (this) {
            listeners.remove(listener);
          }
        });
  }

  /**
   * Returns the active tools in appearance order (the order their bindings resolved).
   *
   * @return an immutable snapshot of the active tool handles
   */
  public List<CordisToolHandle> tools() {
    synchronized (this) {
      return List.copyOf(active.values());
    }
  }

  /**
   * Finds an active tool by its specification name.
   *
   * @param name the tool name an agent sent a request for
   * @return the tool's handle, or empty when the tool is not active
   * @throws NullPointerException if {@code name} is null
   */
  public Optional<CordisToolHandle> tool(String name) {
    Objects.requireNonNull(name, "name");
    synchronized (this) {
      for (CordisToolHandle handle : active.values()) {
        // name is non-null here; the specification's name may be null and must not throw
        if (name.equals(handle.specification().name())) {
          return Optional.of(handle);
        }
      }
      return Optional.empty();
    }
  }

  /**
   * Retires every declared tool and silences this registry. Idempotent; the session context itself
   * is left untouched.
   */
  @Override
  public void dispose() {
    guard.dispose();
  }

  private void retireAll() {
    List<Disposable> handles;
    synchronized (this) {
      closed = true;
      listeners.clear();
      declared.clear();
      active.clear();
      handles = new ArrayList<>(declarations.values());
      declarations.clear();
    }
    // Aggregated outside the monitor: every fiber's cleanup runs even when an earlier one throws,
    // and the failures surface together as one DisposeException (the core's disposal discipline).
    Disposables.composite(handles.toArray(Disposable[]::new)).dispose();
  }

  private void requireOpen() {
    if (closed) {
      throw new IllegalStateException("tool registry is disposed");
    }
  }

  private String execute(ServiceKey<CordisTool> key, ToolExecutionRequest request) {
    CordisTool tool =
        session
            .find(key)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tool is not active: " + request.name() + " (" + key + ")"));
    return tool.execute(request.arguments());
  }

  private void notifyChanged() {
    List<Runnable> pending;
    synchronized (this) {
      if (closed) {
        return;
      }
      pending = new ArrayList<>(listeners);
    }
    for (Runnable listener : pending) {
      failSafeNotify(listener);
    }
  }

  private void failSafeNotify(Runnable listener) {
    try {
      listener.run();
    } catch (Throwable failure) {
      session
          .logger(LISTENER_LOGGER)
          .warn(
              "a cordis4j-langchain4j tool-change listener failed; isolation keeps the mutation intact",
              failure);
    }
  }
}
