/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core.internal;

import io.cordis4j.core.CyclicDependencyException;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Logger;
import io.cordis4j.core.ServiceKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fiber registry and reactive scheduler (paper, Section 4.2 and Algorithms 3/5): tracks every
 * instantiated fiber, the dependency-declaration table, and drives the refresh cascade that keeps
 * each fiber's committed state equal to its target state.
 *
 * <p>Semantics implemented here:
 *
 * <ul>
 *   <li><b>Satisfaction and notify</b> (Algorithm 3): a binding that appears classifies every
 *       dependent as activating, deactivating, or neutral; activating fibers load.
 *   <li><b>Withdrawal drain</b> (Algorithm 5, Theorem 63): before a binding leaves the store, the
 *       active fibers that rely on it unload first - and their teardown still resolves the binding,
 *       because the removal happens only after the drain.
 *   <li><b>Inertia</b> (Section 4.3.3): a migration already in flight lands before the next one
 *       starts - unloading a fiber that is still activating waits for the activation to finish.
 *   <li><b>Failure routing</b> (Section 4.3.4): an activation that raises reverts its own domain,
 *       records the failure on the fiber, and never retries; the failure does not propagate to
 *       sibling fibers (only to the direct caller of {@code plugin}).
 *   <li><b>Cycle rejection</b> (Progress theorem): activation revisiting a fiber already on the
 *       activation stack throws {@link CyclicDependencyException} to the triggering caller.
 * </ul>
 *
 * <p>Concurrency: registry state is guarded by {@link #lock}. User code - effect functions,
 * disposables, service hooks - always runs <em>outside</em> the lock, so a long activation never
 * blocks unrelated lookups, and teardown may join spawned tasks without deadlocking. The lock is
 * acquired in one direction only (this registry, then the per-context stores), so no cycle of lock
 * holders can form.
 */
final class FiberRegistry {

  private final ContextImpl root;
  private final Logger log;
  private final Object lock = new Object();
  private long nextUid = 1;
  private final Map<Long, Fiber> fibers = new LinkedHashMap<>();
  private final Map<ServiceKey<?>, Set<Fiber>> dependents = new LinkedHashMap<>();
  private final ArrayDeque<Fiber> activationStack = new ArrayDeque<>();

  FiberRegistry(ContextImpl root) {
    this.root = root;
    this.log = root.logger("io.cordis4j.core.fiber");
  }

  /** Instantiates and registers a fiber (O-Insert of paper Section 4.2). */
  Fiber register(
      ContextImpl owner,
      Set<ServiceKey<?>> dependencies,
      FiberBody body,
      boolean propagateFailure) {
    Fiber fiber = new Fiber(nextUid++, owner, Set.copyOf(dependencies), body, propagateFailure);
    synchronized (lock) {
      fibers.put(fiber.uid, fiber);
      for (ServiceKey<?> key : fiber.dependencies) {
        dependents.computeIfAbsent(key, unused -> new LinkedHashSet<>()).add(fiber);
      }
    }
    return fiber;
  }

  /** Removes a fiber from the registry and from every dependency index. */
  void unregister(Fiber fiber) {
    synchronized (lock) {
      fibers.remove(fiber.uid);
      for (ServiceKey<?> key : fiber.dependencies) {
        Set<Fiber> set = dependents.get(key);
        if (set != null) {
          set.remove(fiber);
          if (set.isEmpty()) {
            dependents.remove(key);
          }
        }
      }
    }
  }

  /**
   * The satisfaction predicate (paper Section 3.2.2): every declared key resolves for the owner.
   */
  boolean satisfied(Fiber fiber) {
    for (ServiceKey<?> key : fiber.dependencies) {
      if (fiber.owner.registry.get(key) == null) {
        return false;
      }
    }
    return true;
  }

  /**
   * The activating half of Algorithm 3: called after a binding appeared under {@code key}; loads
   * every idle, non-failed, non-retired dependent whose declaration just became satisfied.
   */
  void notifyBound(ServiceKey<?> key) {
    List<Fiber> toActivate = null;
    synchronized (lock) {
      Set<Fiber> declared = dependents.get(key);
      if (declared != null) {
        for (Fiber fiber : declared) {
          if (fiber.state == FiberState.INACTIVE
              && !fiber.failed
              && !fiber.retired
              && satisfied(fiber)) {
            if (toActivate == null) {
              toActivate = new ArrayList<>();
            }
            toActivate.add(fiber);
          }
        }
      }
    }
    if (toActivate != null) {
      for (Fiber fiber : toActivate) {
        activate(fiber); // outside the lock: user code must not hold it
      }
    }
  }

  /**
   * Runs a fiber's effect function inside its domain (L-Begin/L-Iter/L-Finish of paper Section
   * 4.3.2, synchronous: the whole effect function is one iteration).
   *
   * @param fiber the fiber to activate
   * @throws CyclicDependencyException if the activation would revisit a fiber already activating
   */
  void activate(Fiber fiber) {
    synchronized (lock) {
      if (activationStack.contains(fiber)) {
        throw new CyclicDependencyException(describeCycle(fiber));
      }
      fiber.state = FiberState.LOADING;
      activationStack.push(fiber);
    }
    EffectScopeImpl previousDomain = Domains.domain();
    Fiber previousFiber = Domains.fiber();
    Domains.set(fiber.domain, fiber);
    Throwable failure = null;
    Disposable extra = null;
    try {
      extra = fiber.body.apply(fiber.owner);
    } catch (Throwable thrown) {
      failure = thrown;
    }
    synchronized (lock) {
      activationStack.pop();
      Domains.set(previousDomain, previousFiber);
      if (failure == null) {
        if (extra != null) {
          fiber.domain.track(extra); // reverted first: the plugin's own cleanup, LIFO
        }
        fiber.state = FiberState.ACTIVE;
        lock.notifyAll();
      }
    }
    if (failure != null) {
      revertFailedActivation(fiber, failure);
    }
  }

  /**
   * Unloads a fiber (L-Leave/L-Unload of paper Section 4.3.1): reverts its domain LIFO. The
   * relied-guard of Theorem 63 is enforced by {@link #withdraw}: callers drain dependents before
   * removing a binding, so by the time a provider's own effects revert, no active fiber still
   * relies on them.
   *
   * <p>Inertia (Section 4.3.3): a fiber still activating must land first - unloading waits until
   * the in-flight activation completes, then unloads the landed fiber (or returns, when it failed).
   * Idempotent and reentrant-safe: unloading an already unloading fiber is a no-op, which also
   * terminates cascades around dependency cycles at teardown time.
   */
  void unload(Fiber fiber) {
    synchronized (lock) {
      while (fiber.state == FiberState.LOADING) {
        try {
          lock.wait(); // inertia: let the in-flight migration land
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted while waiting for an in-flight component");
        }
      }
      if (fiber.state != FiberState.ACTIVE) {
        return;
      }
      fiber.state = FiberState.UNLOADING;
    }
    withdrawSupplies(fiber); // L-Leave: stop supplying before any effect reverts (Theorem 63)
    fiber.domain.dispose(); // outside the lock: teardown may join spawned tasks
    synchronized (lock) {
      fiber.state = FiberState.INACTIVE;
      fiber.domain = new EffectScopeImpl(); // fresh accumulator for the next activation
      if (fiber.retired || fiber.failed) {
        unregister(fiber); // only a retired or failed fiber leaves the registry;
        // a reactively unloaded fiber stays indexed, ready to re-activate
      }
      lock.notifyAll();
    }
  }

  /**
   * Drains the dependents of every key this fiber supplies (paper L-Leave): all of them unload
   * before any of the provider's own effects revert. Idempotent with the per-removal drain.
   */
  private void withdrawSupplies(Fiber fiber) {
    List<ServiceKey<?>> supplies;
    synchronized (lock) {
      supplies = List.copyOf(fiber.providedKeys);
    }
    for (ServiceKey<?> key : supplies) {
      withdraw(key);
    }
  }

  /**
   * The drain of Theorem 63: unloads every active fiber relying on {@code key} before the binding
   * leaves the store, so each dependent's teardown can still resolve the dependency.
   *
   * <p>Fibers still activating join the drain too: {@link #unload} waits for the in-flight
   * migration to land and then unloads it (the chained unload of paper Section 4.3.3) - a
   * dependency withdrawn mid-activation never leaves a dependent running.
   */
  void withdraw(ServiceKey<?> key) {
    List<Fiber> toUnload = null;
    synchronized (lock) {
      Set<Fiber> declared = dependents.get(key);
      if (declared != null) {
        for (Fiber fiber : declared) {
          if (fiber.state == FiberState.ACTIVE || fiber.state == FiberState.LOADING) {
            if (toUnload == null) {
              toUnload = new ArrayList<>();
            }
            toUnload.add(fiber);
          }
        }
      }
    }
    if (toUnload != null) {
      for (Fiber fiber : toUnload) {
        unload(fiber); // outside the lock: teardown is user code
      }
    }
  }

  /**
   * Returns the user-facing handle of a fiber: disposing it retires the fiber (it will not activate
   * again) and unloads it (Algorithm 4's parent effect carrying the child unload).
   *
   * <p>Once disposed, the handle drops its fiber reference, so a retired fiber - and with it the
   * plugin instance and, in a bytecode-level reload, its class loader - becomes collectable even
   * while the ambient scope that tracked the handle still lives (reference discipline; the disposal
   * behavior itself is unchanged and idempotent).
   */
  Disposable handle(Fiber fiber) {
    return new FiberHandleDisposable(this, fiber);
  }

  /**
   * A fiber handle whose fiber reference is released on dispose. A static class, not an anonymous
   * one: anonymous classes capture constructor parameters into synthetic final fields, which would
   * pin the fiber forever when an ambient scope tracks the handle.
   */
  private static final class FiberHandleDisposable implements Disposable {

    private final FiberRegistry registry;
    private Fiber target;

    FiberHandleDisposable(FiberRegistry registry, Fiber target) {
      this.registry = registry;
      this.target = target;
    }

    @Override
    public void dispose() {
      Fiber fiberToRetire = target;
      if (fiberToRetire == null) {
        return;
      }
      synchronized (registry.lock) {
        fiberToRetire.retired = true;
      }
      registry.unload(fiberToRetire);
      target = null;
    }
  }

  /** Records a key supplied by a fiber (for declaration checks); part of the provide protocol. */
  void supplied(Fiber fiber, ServiceKey<?> declaredKey) {
    fiber.providedKeys.add(declaredKey); // fiber-confined until teardown joins its thread
  }

  /** Forgets a key supplied by a fiber; part of the removal protocol. */
  void unsupplied(Fiber fiber, ServiceKey<?> declaredKey) {
    fiber.providedKeys.remove(declaredKey);
  }

  /** The fiber executing on the calling thread, if any. */
  Fiber currentFiber() {
    return Domains.fiber();
  }

  /**
   * The guard predicate (paper Section 4.3.2): the target changed for this fiber - it was retired,
   * its migration already began, or a declared dependency no longer resolves. During a withdrawal
   * drain the binding is deliberately still readable (Theorem 63), so the state, not the store,
   * signals diversion.
   */
  boolean diverted(Fiber fiber) {
    if (fiber.retired) {
      return true;
    }
    if (fiber.state != FiberState.LOADING && fiber.state != FiberState.ACTIVE) {
      return true; // INACTIVE or UNLOADING: the target moved away from "running"
    }
    return !satisfied(fiber);
  }

  /** Failure routing (Section 4.3.4): revert the partial domain, mark the fiber failed. */
  private void revertFailedActivation(Fiber fiber, Throwable failure) {
    synchronized (lock) {
      fiber.state = FiberState.UNLOADING;
    }
    try {
      fiber.domain.dispose();
    } catch (Throwable reversion) {
      failure.addSuppressed(reversion);
    } finally {
      synchronized (lock) {
        fiber.state = FiberState.INACTIVE;
        fiber.domain = new EffectScopeImpl();
        fiber.failed = true;
        fiber.failure = failure;
        lock.notifyAll();
      }
    }
    if (fiber.propagateFailure) {
      throw Throwables.sneak(failure);
    }
    log.warn(
        "component #{} activation failed and was routed to unload; it will not retry: {}",
        fiber.uid,
        failure);
  }

  private String describeCycle(Fiber fiber) {
    StringBuilder cycle = new StringBuilder();
    boolean seen = false;
    synchronized (lock) {
      for (Fiber activating : activationStack) {
        if (activating == fiber) {
          seen = true;
        }
        if (seen) {
          cycle.append("#").append(activating.uid).append(" -> ");
        }
      }
    }
    cycle.append("#").append(fiber.uid);
    return cycle.toString();
  }
}
