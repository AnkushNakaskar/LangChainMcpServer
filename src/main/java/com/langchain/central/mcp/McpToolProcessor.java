package com.langchain.central.mcp;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.mcp.stream.ToolStream;
import com.langchain.central.mcp.stream.ToolStreamContext;
import com.langchain.central.mcp.stream.ToolExecutionStreamRegistry;
import com.langchain.central.service.tool.ToolService;
import com.langchain.central.util.McpToolUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.AsyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Singleton
public class McpToolProcessor {

    private static final String PROGRESS_TOKEN = "progressToken";
    private final Set<ToolService> services;
    private final ToolExecutionStreamRegistry toolExecutionStreamRegistry;

    @Inject
    public McpToolProcessor(final Set<ToolService> services,
                            final ToolExecutionStreamRegistry toolExecutionStreamRegistry) {
        this.services = services;
        this.toolExecutionStreamRegistry = toolExecutionStreamRegistry;
    }

    public List<AsyncToolSpecification> getAvailableTools() {
        final List<AsyncToolSpecification> tools = new ArrayList<>();
        for (ToolService service : services) {
            for (Method method : service.getClass()
                    .getMethods()) {
                AsyncToolSpecification specification = toToolSpecification(service, method);
                if (specification != null) {
                    tools.add(specification);
                }
            }
        }
        return tools;
    }

    private AsyncToolSpecification toToolSpecification(final ToolService service,
                                                       final Method method) {
        if (method.isAnnotationPresent(Tool.class)) {
            final Tool annotation = method.getAnnotation(Tool.class);
            final String[] descriptions = annotation.value();
            final String description = descriptions.length == 0
                                       ? method.getName()
                                       : String.join(" ", descriptions);
            final McpSchema.Tool tool = McpSchema.Tool.builder(McpToolUtil.toToolName(method.getName()))
                    .description(description)
                    .inputSchema(prepareAndGetToolInputSchema(method))
                    .build();

            return AsyncToolSpecification.builder()
                    .tool(tool)
                    // The tool bodies are blocking (git, HTTP, LLM calls), so each call is
                    // subscribed on a worker thread and never occupies the thread that is driving
                    // the request. The ThreadLocal stream is bound inside the callable, which is
                    // the very thread the tool runs on.
                    .callHandler((context, request) -> Mono
                            .fromCallable(() -> invokeTool(service, method, request))
                            .subscribeOn(Schedulers.boundedElastic()))
                    .build();
        }
        return null;
    }

    private Map<String, Object> prepareAndGetToolInputSchema(final Method method) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        final List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            final P annotation = parameter.getAnnotation(P.class);
            properties.put(parameter.getName(),
                    Map.of("type", McpToolUtil.toJsonSchemaType(parameter.getType()), "description", annotation == null
                                                                                                     ? parameter.getName() : annotation.value()));
            required.add(parameter.getName());
        }
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private CallToolResult invokeTool(final ToolService service,
                                      final Method method,
                                      final CallToolRequest request) {
        ToolStreamContext.bind(streamOf(request));
        try {
            final Map<String, Object> arguments = request.arguments() == null
                                                  ? Map.of()
                                                  : request.arguments();
            final Parameter[] parameters = method.getParameters();
            final Object[] values = new Object[parameters.length];
            for (int index = 0; index < parameters.length; index++) {
                values[index] = McpToolUtil.convert(arguments.get(parameters[index].getName()),
                        parameters[index].getType());
            }
            final Object value = method.invoke(service, values);
            return prepareToolExecutionResult(String.valueOf(value), false);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            final Throwable cause = e instanceof InvocationTargetException && e.getCause() != null
                                    ? e.getCause()
                                    : e;
            return prepareToolExecutionResult("Tool invocation failed: " + cause.getMessage(), true);
        } finally {
            ToolStreamContext.clear();
        }
    }

    /**
     * The stream the request is being answered on, or a sink that discards partial output. A call
     * made without a progress token is not being streamed, so there is nothing to look up.
     */
    private ToolStream streamOf(final CallToolRequest request) {
        final Map<String, Object> meta = request.meta();
        final Object progressToken = meta == null
                                     ? null
                                     : meta.get(PROGRESS_TOKEN);
        if (progressToken == null) {
            return ToolStream.NOOP;
        }
        return toolExecutionStreamRegistry.getToolExecution(String.valueOf(progressToken))
                .orElse(ToolStream.NOOP);
    }

    private CallToolResult prepareToolExecutionResult(final String text,
                                                      final boolean error) {
        return new CallToolResult(List.of(new McpSchema.TextContent(text)), error, null, Map.of());
    }

}
