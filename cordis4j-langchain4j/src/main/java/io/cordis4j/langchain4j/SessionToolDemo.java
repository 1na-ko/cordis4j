/*
 * Copyright 2025 the Cordis4j contributors
 * SPDX-License-Identifier: MIT
 */
package io.cordis4j.langchain4j;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.cordis4j.core.Context;
import io.cordis4j.core.Contexts;
import io.cordis4j.core.Disposable;
import io.cordis4j.core.Disposables;
import io.cordis4j.core.Inject;
import io.cordis4j.core.Injects;
import io.cordis4j.core.ServiceKey;
import java.util.Optional;

/**
 * The vertical slice of the LangChain4j integration: an agent session whose tools are cordis
 * plugins, so they can be loaded, unloaded, and swapped mid-conversation and are reverted with the
 * session.
 *
 * <p><em>Not part of the module's API</em>: this is a runnable narrative demo that lives here only
 * because it exercises this module's own types; treat it as documentation, not as a dependency
 * target (the jackson {@code requires} of the module exists for it and for test fixtures).
 *
 * <p>No model provider is dialed - the agent loop is simulated - so this demo runs offline. The
 * session's tool set follows the reactive-coeffect lifecycle: the calculator appears when its
 * plugin loads, disappears when the plugin unloads (hot unload), and is replaced when a different
 * implementation is provided. Ending the session disposes the context and reverts everything.
 */
public final class SessionToolDemo {

  private SessionToolDemo() {}

  /**
   * Runs the demo: prints the simulated conversation and the tool set after every change.
   *
   * @param args command-line arguments (unused)
   */
  public static void main(String[] args) {
    Context root = Contexts.create();
    Context session = root.fork(); // one conversation = one disposable subtree
    CordisToolRegistry tools = CordisToolRegistry.create(session);
    tools.onChange(() -> System.out.println("  tool list -> " + names(tools)));
    tools.declare(ServiceKey.of(CordisTool.class, "calculator"));
    tools.declare(ServiceKey.of(CordisTool.class, "unit-converter"));

    // The session starts with an adding calculator plugin.
    Disposable calculator =
        session.plugin(
            ctx -> {
              ctx.provide(ServiceKey.of(CordisTool.class, "calculator"), new CalculatorTool("add"));
              return Disposables.none();
            });
    run(tools, "calculator", "{\"a\":2,\"b\":3}");

    // Mid-conversation hot unload: the registry follows reactively.
    calculator.dispose();
    System.out.println("agent: calculator after unload -> " + tools.tool("calculator"));

    // The tool returns as a different implementation: the agent's view is replaced.
    Disposable replacement =
        session.plugin(
            ctx -> {
              ctx.provide(ServiceKey.of(CordisTool.class, "calculator"), new CalculatorTool("sub"));
              return Disposables.none();
            });
    run(tools, "calculator", "{\"a\":7,\"b\":2}");
    replacement.dispose();

    // A tool whose service carries an @Inject dependency composes both P3 pieces: the injected
    // field holds an activation-time snapshot of the session database.
    Disposable converter =
        session.plugin(
            ctx -> {
              UnitConverter converterTool = new UnitConverter();
              Injects.injectFields(ctx, converterTool); // T24 annotation injection
              ctx.provide(ServiceKey.of(CordisTool.class, "unit-converter"), converterTool);
              return Disposables.none();
            });
    session.plugin(
        ctx -> {
          ctx.provide(new UnitDatabase(2.54)); // cm per inch
          return Disposables.none();
        });
    run(tools, "unit-converter", "{\"inches\":1}");
    converter.dispose();

    // Ending the session reverts everything: plugins, tools, injected fields, listeners.
    tools.dispose();
    session.dispose();
    System.out.println("session ended -> " + names(tools));
  }

  private static void run(CordisToolRegistry tools, String name, String arguments) {
    Optional<CordisToolHandle> tool = tools.tool(name);
    String result =
        tool.map(handle -> handle.executor().apply(request(name, arguments)))
            .orElse("tool not active");
    System.out.println("agent: " + name + " -> " + result);
  }

  private static ToolExecutionRequest request(String name, String arguments) {
    return ToolExecutionRequest.builder().name(name).arguments(arguments).build();
  }

  private static String names(CordisToolRegistry tools) {
    return tools.tools().stream().map(handle -> handle.specification().name()).toList().toString();
  }

  /** A calculator tool: {@code mode} selects addition or subtraction on {@code a} and {@code b}. */
  record CalculatorTool(String mode) implements CordisTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public ToolSpecification toolSpecification() {
      return ToolSpecification.builder()
          .name("calculator")
          .description("Adds or subtracts two integers.")
          .parameters(
              JsonObjectSchema.builder()
                  .addIntegerProperty("a", "the first operand")
                  .addIntegerProperty("b", "the second operand")
                  .required("a", "b")
                  .build())
          .build();
    }

    @Override
    public String execute(String arguments) {
      JsonNode json;
      try {
        json = JSON.readTree(arguments);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("invalid calculator arguments: " + arguments, e);
      }
      int a = json.path("a").asInt();
      int b = json.path("b").asInt();
      return String.valueOf("add".equals(mode) ? a + b : a - b);
    }
  }

  /** A tool whose service depends on a session database through annotation injection (T24). */
  static final class UnitConverter implements CordisTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Inject UnitDatabase units;

    @Override
    public ToolSpecification toolSpecification() {
      return ToolSpecification.builder()
          .name("unit-converter")
          .description("Converts inches to centimeters using the session's unit database.")
          .parameters(
              JsonObjectSchema.builder().addNumberProperty("inches", "length in inches").build())
          .build();
    }

    @Override
    public String execute(String arguments) {
      if (units == null) {
        return "no unit database bound in this session";
      }
      double inches;
      try {
        inches = JSON.readTree(arguments).path("inches").asDouble();
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("invalid converter arguments: " + arguments, e);
      }
      return inches + " in = " + (inches * units.cmPerInch()) + " cm";
    }
  }

  /** The session's unit database: a plain provided service. */
  record UnitDatabase(double cmPerInch) {}
}
