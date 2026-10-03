package com.langchain.central.mcp;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.mcp.stream.ToolStream;
import com.langchain.central.mcp.stream.ToolStreamContext;
import com.langchain.central.mcp.stream.ToolStreamRegistry;
import com.langchain.central.service.tool.ToolService;
import com.langchain.central.util.McpToolUtil;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
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

@Singleton
public class McpToolRegistrar {

    private static final String PROGRESS_TOKEN = "progressToken";

    private final Set<ToolService> services;
    private final McpToolArgumentConverter argumentConverter;
    private final ToolStreamRegistry streamRegistry;

    @Inject
    public McpToolRegistrar(
            final Set<ToolService> services,
            final McpToolArgumentConverter argumentConverter,
            final ToolStreamRegistry streamRegistry) {
        this.services = services;
        this.argumentConverter = argumentConverter;
        this.streamRegistry = streamRegistry;
    }

    public List<SyncToolSpecification> tools() {
        final List<SyncToolSpecification> tools = new ArrayList<>();
        for (ToolService service : services) {
            for (Method method : service.getClass().getMethods()) {
                SyncToolSpecification specification = toSpecification(service, method);
                if(specification != null) {
                    tools.add(specification);
                }
            }
        }
        return tools;
    }

    private SyncToolSpecification toSpecification(
            final ToolService service,
            final Method method) {
        if(method.isAnnotationPresent(Tool.class)) {
            final Tool annotation = method.getAnnotation(Tool.class);
            final String[] descriptions = annotation.value();
            final String description = descriptions.length == 0
                                       ? method.getName()
                                       : String.join(" ", descriptions);
            final McpSchema.Tool tool = McpSchema.Tool.builder(
                            McpToolUtil.toToolName(method.getName()))
                    .description(description)
                    .inputSchema(inputSchema(method))
                    .build();

            return SyncToolSpecification.builder()
                    .tool(tool)
                    .callHandler((context, request) -> invoke(service, method, request))
                    .build();
        }
        return null;
    }

    private Map<String, Object> inputSchema(final Method method) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        final List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            final P annotation = parameter.getAnnotation(P.class);
            properties.put(parameter.getName(), Map.of(
                    "type", McpToolUtil.toJsonSchemaType(parameter.getType()),
                    "description", annotation == null
                            ? parameter.getName()
                            : annotation.value()));
            required.add(parameter.getName());
        }
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required);
    }

    /**
     * Runs the tool with the stream of its request bound to this thread, so a slow tool can publish
     * its result in parts while it works instead of only when it returns.
     */
    private CallToolResult invoke(
            final ToolService service,
            final Method method,
            final CallToolRequest request) {
        ToolStreamContext.bind(streamOf(request));
        try {
            final Map<String, Object> arguments =
                    request.arguments() == null ? Map.of() : request.arguments();
            final Parameter[] parameters = method.getParameters();
            final Object[] values = new Object[parameters.length];
            for (int index = 0; index < parameters.length; index++) {
                values[index] = argumentConverter.convert(
                        arguments.get(parameters[index].getName()),
                        parameters[index].getType());
            }
            final Object value = method.invoke(service, values);
            return result(String.valueOf(value), false);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            final Throwable cause = e instanceof InvocationTargetException
                    && e.getCause() != null ? e.getCause() : e;
            return result("Tool invocation failed: " + cause.getMessage(), true);
        } finally {
            ToolStreamContext.clear();
        }
    }

    /** The stream the request is being answered on, or a sink that discards partial output. */
    private ToolStream streamOf(final CallToolRequest request) {
        final Map<String, Object> meta = request.meta();
        final Object progressToken = meta == null ? null : meta.get(PROGRESS_TOKEN);
        return streamRegistry.find(progressToken == null ? null : String.valueOf(progressToken))
                .orElse(ToolStream.NOOP);
    }

    private CallToolResult result(final String text, final boolean error) {
        return new CallToolResult(
                List.of(new McpSchema.TextContent(text)), error, null, Map.of());
    }

}
