/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.demo;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.Plugin;
import io.cordis4j.core.ServiceKey;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-tenant isolation demo (the DeepSeek Harness session-sandbox pattern; the isolate derivation
 * of paper Section 3.2.2):
 *
 * <p>The root context provides shared infrastructure; each tenant is a subtree derived with {@code
 * isolate} - the same service type resolves to each tenant's own implementation inside its own
 * realm, invisible to the others; disposing a tenant discards its subtree wholesale, leaving the
 * root untouched.
 */
public final class MultiTenantDemo {

  interface Secrets {
    String tenantKey();
  }

  record TenantSecrets(String tenant, String key) implements Secrets {
    @Override
    public String tenantKey() {
      return key;
    }
  }

  public static void main(String[] args) {
    List<String> log = new ArrayList<>();
    Context app = Contexts.create();

    // Tenant alice: provides its own Secrets inside the isolated realm; lookups route there
    Context alice = app.isolate(Secrets.class, "alice");
    alice.plugin(new SecretsPlugin("alice"));
    // Tenant bob: same service type, independent realm
    Context bob = app.isolate(Secrets.class, "bob");
    bob.plugin(new SecretsPlugin("bob"));

    log.add("alice sees: " + alice.get(Secrets.class).tenantKey());
    log.add("bob   sees: " + bob.get(Secrets.class).tenantKey());
    log.add("app   sees secrets? " + app.find(Secrets.class).isPresent());

    // One more fork inside the session: the grandchild inherits the tenant realm
    Context aliceSession = alice.fork();
    log.add("alice session inherits: " + aliceSession.get(Secrets.class).tenantKey());

    alice.dispose(); // the whole tenant subtree is discarded
    log.add("after alice.dispose, bob still: " + bob.get(Secrets.class).tenantKey());

    log.forEach(System.out::println);
  }

  static final class SecretsPlugin implements Plugin {
    private final String tenant;

    SecretsPlugin(String tenant) {
      this.tenant = tenant;
    }

    @Override
    public Disposable apply(Context ctx) {
      ctx.provide(ServiceKey.of(Secrets.class), new TenantSecrets(tenant, "key-of-" + tenant));
      return Disposables.none();
    }
  }

  private MultiTenantDemo() {}
}
