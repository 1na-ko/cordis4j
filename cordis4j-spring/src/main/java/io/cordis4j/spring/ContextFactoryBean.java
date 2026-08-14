/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.spring;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;

/**
 * Exposes a cordis4j {@link Context} as a Spring bean: the default constructor creates a root
 * context, and the {@link ContextFactoryBean#ContextFactoryBean(Context)} constructor wraps an
 * existing one (for example a per-session fork). When the container closes, the context is
 * disposed, reverting every plugin, service, and child it carries - the paradigm's whole-session
 * teardown mapped onto Spring's shutdown.
 */
public final class ContextFactoryBean implements FactoryBean<Context>, DisposableBean {

  private final Context context;

  /** Creates a factory for a new root context. */
  public ContextFactoryBean() {
    this(Contexts.create());
  }

  /**
   * Creates a factory for an existing context.
   *
   * @param context the context to expose as a bean
   * @throws NullPointerException if {@code context} is null
   */
  public ContextFactoryBean(Context context) {
    this.context = Objects.requireNonNull(context, "context");
  }

  @Override
  public Context getObject() {
    return context;
  }

  @Override
  public Class<?> getObjectType() {
    return Context.class;
  }

  @Override
  public boolean isSingleton() {
    return true;
  }

  /** Disposes the exposed context when the container closes. */
  @Override
  public void destroy() {
    context.dispose();
  }
}
