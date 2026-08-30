package com.langchain.central.mcp;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.service.tool.ToolService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
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

    private final Set<ToolService> services;

    @Inject
    public McpToolRegistrar(final Set<ToolService> services) {
        this.services = services;
    }

    public List<McpStatelessServerFeatures.SyncToolSpecification> tools() {
        final List<McpStatelessServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        for (ToolService service : services) {
            for (Method method : service.getClass().getMethods()) {
                final Tool annotation = method.getAnnotation(Tool.class);
                if (annotation != null) {
                    tools.add(toSpecification(service, method, annotation));
                }
            }
        }
        return tools;
    }

    private McpStatelessServerFeatures.SyncToolSpecification toSpecification(
            final ToolService service,
            final Method method,
            final Tool annotation) {
        final String[] descriptions = annotation.value();
        final String description = descriptions.length == 0
                ? method.getName()
                : String.join(" ", descriptions);
        final McpSchema.Tool tool = McpSchema.Tool.builder(toSnakeCase(method.getName()))
                .description(description)
                .inputSchema(inputSchema(method))
                .build();

        return McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, request) -> invoke(service, method, request))
                .build();
    }

    private Map<String, Object> inputSchema(final Method method) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        final List<String> required = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            final P annotation = parameter.getAnnotation(P.class);
            properties.put(parameter.getName(), Map.of(
                    "type", jsonType(parameter.getType()),
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

    private McpSchema.CallToolResult invoke(
            final ToolService service,
            final Method method,
            final McpSchema.CallToolRequest request) {
        try {
            final Map<String, Object> arguments =
                    request.arguments() == null ? Map.of() : request.arguments();
            final Parameter[] parameters = method.getParameters();
            final Object[] values = new Object[parameters.length];
            for (int index = 0; index < parameters.length; index++) {
                values[index] = convert(arguments.get(parameters[index].getName()),
                        parameters[index].getType());
            }
            final Object value = method.invoke(service, values);
            return result(String.valueOf(value), false);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            final Throwable cause = e instanceof InvocationTargetException
                    && e.getCause() != null ? e.getCause() : e;
            return result("Tool invocation failed: " + cause.getMessage(), true);
        }
    }

    private McpSchema.CallToolResult result(final String text, final boolean error) {
        return new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(text)), error, null, Map.of());
    }

    private Object convert(final Object value, final Class<?> type) {
        if (value == null || type.isInstance(value)) {
            return value;
        }
        if (type == String.class) {
            return String.valueOf(value);
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(String.valueOf(value));
        }
        if (type == long.class || type == Long.class) {
            return Long.valueOf(String.valueOf(value));
        }
        if (type == double.class || type == Double.class) {
            return Double.valueOf(String.valueOf(value));
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.valueOf(String.valueOf(value));
        }
        return value;
    }

    private String jsonType(final Class<?> type) {
        if (type == String.class || type.isEnum()) {
            return "string";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            return "number";
        }
        if (type.isArray() || Iterable.class.isAssignableFrom(type)) {
            return "array";
        }
        return "object";
    }

    private String toSnakeCase(final String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
