/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.hmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T34: the stage-1 class isolation model (decision D27) - parent delegation means host classes win:
 * a same-named class packaged inside the plugin jar is shadowed, so plugin code always implements
 * the host's Plugin type and its identity guarantees hold.
 */
class HostShadowingTest {

  @TempDir Path dir;

  @Test
  @DisplayName("T34 宿主类优先：jar 内同名 Plugin 类被遮蔽，插件实现的是宿主类型")
  void hostClassShadowsPackagedCopy() throws Exception {
    String fakePlugin =
        "package io.cordis4j.core;\n"
            + "/** A same-named copy packaged into the plugin jar; shadowed by the host class. */\n"
            + "public interface Plugin {}\n";
    String greeting =
        "package p1;\n"
            + "public class GreetingPlugin implements io.cordis4j.core.Plugin {\n"
            + "  public io.cordis4j.core.Disposable apply(io.cordis4j.core.Context ctx) {\n"
            + "    ctx.provide(\n"
            + "        io.cordis4j.core.Plugin.class.getName()\n"
            + "            + \":\"\n"
            + "            + (this instanceof io.cordis4j.core.Plugin));\n"
            + "    return io.cordis4j.core.Disposables.none();\n"
            + "  }\n"
            + "}\n";
    Path jar =
        TestJars.compileJar(
            dir,
            "shadowed.jar",
            List.of(
                new TestJars.Source("io.cordis4j.core.Plugin", fakePlugin),
                new TestJars.Source("p1.GreetingPlugin", greeting)));

    Context ctx = Contexts.create();
    HotReloadingLoader hrl = HotReloadingLoader.of(ctx);
    PluginHandle handle = hrl.load("greeting", jar);

    assertTrue(
        handle.plugin() instanceof io.cordis4j.core.Plugin, "插件必须实现宿主 Plugin 类型（jar 内同名类被遮蔽）");
    assertEquals(
        "io.cordis4j.core.Plugin:true",
        ctx.get(String.class),
        "插件代码解析的 Plugin 必须是宿主类且 instanceof 成立");
    hrl.dispose();
  }
}
