/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T24: annotation-mediated injection (paper Section 6.4, decision D21): {@code @Inject} fields
 * declared through {@link Injects#injectFields} follow the reactive-coeffect lifecycle as
 * activation-time snapshots.
 */
class AnnotationInjectTest {

  record Database(String url) {}

  record Cache(int slots) {}

  static class Consumer {
    @Inject Database database;
    @Inject Cache cache;
  }

  static class BaseConsumer {
    @Inject Database database;
  }

  static class DerivedConsumer extends BaseConsumer {
    @Inject Cache cache;
  }

  static class TwoKeysOneService {
    @Inject Database primary;
    @Inject Database replica;
  }

  static class QualifiedConsumer {
    @Inject(qualifier = "backup")
    Database database;
  }

  static class RetireConsumer {
    @Inject Database database;
  }

  @Test
  @DisplayName("T24 所有键解析后填充字段；依赖撤销后清空；依赖回归后重新填充（快照语义）")
  void satisfactionWithdrawalReactivation() {
    Context ctx = Contexts.create();
    Consumer consumer = new Consumer();
    Injects.injectFields(ctx, consumer);

    assertNull(consumer.database, "依赖缺失时字段必须保持 null");
    assertNull(consumer.cache, "部分依赖满足也必须保持不激活");

    Database database = new Database("jdbc:main");
    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(database);
              c.provide(new Cache(64));
              return Disposables.none();
            });
    assertSame(database, consumer.database, "满足后必须填充当前绑定");
    assertEquals(64, consumer.cache.slots());

    provider.dispose(); // withdrawal of the relied bindings unloads the declaration
    assertNull(consumer.database, "依赖撤销后字段必须清空");
    assertNull(consumer.cache, "依赖撤销后全部字段必须清空");

    ctx.plugin(
        c -> {
          c.provide(new Database("jdbc:failover"));
          c.provide(new Cache(32));
          return Disposables.none();
        });
    assertEquals("jdbc:failover", consumer.database.url(), "依赖回归后必须重新填充");
    assertEquals(32, consumer.cache.slots(), "重新填充取的是新绑定");
  }

  @Test
  @DisplayName("T24 同键 ambient 覆盖不触碰已激活的快照；provider 卸载按供给关系 drain 声明")
  void overwriteKeepsActivatedSnapshot() {
    Context ctx = Contexts.create();
    RetireConsumer consumer = new RetireConsumer();
    Injects.injectFields(ctx, consumer);

    Database original = new Database("jdbc:main");
    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(original);
              return Disposables.none();
            });
    assertSame(original, consumer.database);

    Database replacement = new Database("jdbc:replacement");
    Disposable overwrite = ctx.provide(replacement); // ambient provides overwrite freely (D12)
    assertSame(original, consumer.database, "同键覆盖不是撤回，已激活的快照必须保持");

    provider
        .dispose(); // withdraws the fiber's supply relation (T23 removal is a no-op, D20 drains)
    assertNull(consumer.database, "fiber 卸载撤回其供给关系，drain 到声明；快照随之清空");
    assertSame(replacement, ctx.get(Database.class), "ambient 覆盖的绑定不受 provider 卸载影响，仍在位");

    overwrite.dispose(); // the binding then really leaves
    assertNull(consumer.database, "字段保持清空");
  }

  @Test
  @DisplayName("T24 父类字段与子类字段同属一个声明；共享同一键的两个字段都填充")
  void hierarchyAndSharedKey() {
    Context ctx = Contexts.create();
    DerivedConsumer derived = new DerivedConsumer();
    Injects.injectFields(ctx, derived);
    TwoKeysOneService shared = new TwoKeysOneService();
    Injects.injectFields(ctx, shared);

    Database database = new Database("jdbc:main");
    ctx.plugin(
        c -> {
          c.provide(database);
          c.provide(new Cache(8));
          return Disposables.none();
        });

    assertSame(database, derived.database, "父类字段必须被注入");
    assertSame(database, shared.primary, "共享键的第一个字段必须被注入");
    assertSame(database, shared.replica, "共享键的第二个字段必须被注入");
  }

  @Test
  @DisplayName("T24 qualifier 属性选择 realm 限定绑定")
  void qualifierSelectsBinding() {
    Context ctx = Contexts.create();
    QualifiedConsumer consumer = new QualifiedConsumer();
    Injects.injectFields(ctx, consumer);
    assertNull(consumer.database, "默认 realm 的绑定不满足 backup 限定声明");

    Database backup = new Database("jdbc:backup");
    ctx.plugin(
        c -> {
          c.provide(new Database("jdbc:main"));
          c.provide(ServiceKey.of(Database.class, "backup"), backup);
          return Disposables.none();
        });
    assertSame(backup, consumer.database, "必须注入 qualifier 选中的绑定");
  }

  @Test
  @DisplayName("T24 dispose 注解声明：字段清空且永久退役")
  void retiredDeclarationClearsAndNeverReactivates() {
    Context ctx = Contexts.create();
    RetireConsumer consumer = new RetireConsumer();
    Disposable declaration = Injects.injectFields(ctx, consumer);

    Disposable provider =
        ctx.plugin(
            c -> {
              c.provide(new Database("jdbc:main"));
              return Disposables.none();
            });
    assertEquals("jdbc:main", consumer.database.url());

    declaration.dispose();
    assertNull(consumer.database, "退役必须清空字段");
    assertEquals("jdbc:main", ctx.get(Database.class).url(), "退役只影响声明本身，提供者域保持完整");

    provider.dispose(); // binding withdrawn; a live declaration would have noticed nothing else
    assertNull(consumer.database, "退役的声明对绑定撤回不得产生新字段值");

    ctx.plugin(
        c -> {
          c.provide(new Database("jdbc:again"));
          return Disposables.none();
        });
    assertNull(consumer.database, "退役的声明不得重新激活");
  }

  @Test
  @DisplayName("T24 static / final / 原始类型字段在装配时立即失败（fail-fast）")
  void misconfiguredFieldsFailFast() {
    Context ctx = Contexts.create();
    assertThrows(
        IllegalArgumentException.class,
        () -> Injects.injectFields(ctx, new StaticConsumer()),
        "static 字段必须拒绝");
    assertThrows(
        IllegalArgumentException.class,
        () -> Injects.injectFields(ctx, new FinalConsumer()),
        "final 字段必须拒绝");
    assertThrows(
        IllegalArgumentException.class,
        () -> Injects.injectFields(ctx, new PrimitiveConsumer()),
        "原始类型字段必须拒绝");
  }

  @Test
  @DisplayName("T24 无注解字段是 no-op；null 参数抛 NullPointerException")
  void noOpAndNullChecks() {
    Context ctx = Contexts.create();
    Plain plain = new Plain();
    Disposable declaration = Injects.injectFields(ctx, plain);
    declaration.dispose();

    assertThrows(NullPointerException.class, () -> Injects.injectFields(null, plain));
    assertThrows(NullPointerException.class, () -> Injects.injectFields(ctx, null));
  }

  static class StaticConsumer {
    @Inject static Database database;
  }

  static class FinalConsumer {
    @Inject final Database database = null;
  }

  static class PrimitiveConsumer {
    @Inject int slots;
  }

  static class Plain {
    Database unannotated;
  }
}
