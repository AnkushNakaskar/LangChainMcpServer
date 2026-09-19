package com.langchain.central.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class McpToolUtil {

    private static ObjectMapper lenientArgumentMapper;

    public static void init(ObjectMapper inputMapper){
        lenientArgumentMapper = inputMapper;
        lenientArgumentMapper.enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES);
    }


    public String toJsonSchemaType(final Class<?> javaType) {
        if (javaType == String.class || javaType.isEnum()) {
            return "string";
        }
        if (javaType == boolean.class || javaType == Boolean.class) {
            return "boolean";
        }
        if (Number.class.isAssignableFrom(javaType) || javaType.isPrimitive()) {
            return "number";
        }
        if (javaType.isArray() || Iterable.class.isAssignableFrom(javaType)) {
            return "array";
        }
        return "object";
    }

    public String toToolName(final String javaMethodName) {
        return javaMethodName
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }

    public Map<String, Object> readSchemaProperties(final Object properties) {
        if (properties instanceof Map<?, ?> propertyMap) {
            return stringKeyMap(propertyMap);
        }
        if (properties instanceof String propertyJson) {
            try {
                return lenientArgumentMapper.readValue(
                        propertyJson, new TypeReference<>() {
                        });
            } catch (JsonProcessingException e) {
                log.warn("Unable to parse schema-shaped MCP tool arguments: {}", propertyJson, e);
            }
        }
        return Map.of();
    }

    public Map<String, Object> stringKeyMap(final Map<?, ?> source) {
        final Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, value);
            }
        });
        return result;
    }

    /** Shape of a model that returns the tool's own input schema instead of argument values. */
    private static final Set<String> SCHEMA_SHAPE_KEYS = Set.of("type", "properties", "required");

    /** Keys a model nests the real argument values under when it echoes its tool-call object. */
    private static final List<String> ENVELOPE_ARGUMENT_KEYS =
            List.of("parameters", "arguments", "input", "args");

    /** Keys that mark a map as a tool-call object rather than as the argument values. */
    private static final Set<String> ENVELOPE_MARKER_KEYS =
            Set.of("type", "function", "name", "tool", "tool_name");

    /**
     * Rewrites the arguments of a tool call that a model has wrapped in its own tool-call object,
     * or filled in with the tool's input schema, into the plain argument values the tool declares.
     *
     * <p>Smaller models frequently emit {@code {"type":"function","parameters":{...}}} or the
     * schema itself. Correcting it here keeps every such call working instead of failing schema
     * validation inside the MCP SDK.
     */
    public McpSchema.JSONRPCRequest normalizeToolArguments(
            final McpSchema.JSONRPCRequest request) {
        if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())
                || !(request.params() instanceof Map<?, ?> params)
                || !(params.get("arguments") instanceof Map<?, ?> arguments)) {
            return request;
        }

        final Map<String, Object> originalArguments = stringKeyMap(arguments);
        final Map<String, Object> normalizedArguments = normalizeArguments(originalArguments);
        if (normalizedArguments.isEmpty() || normalizedArguments.equals(originalArguments)) {
            return request;
        }

        final Map<String, Object> normalizedParams = stringKeyMap(params);
        normalizedParams.put("arguments", normalizedArguments);
        log.warn("Normalized wrapped arguments for MCP tool call {}", params.get("name"));
        return new McpSchema.JSONRPCRequest(
                request.jsonrpc(),
                request.method(),
                request.id(),
                normalizedParams);
    }

    private Map<String, Object> normalizeArguments(final Map<String, Object> arguments) {
        if (isSchemaShaped(arguments)) {
            return readSchemaProperties(arguments.get("properties"));
        }
        final Map<String, Object> unwrapped = unwrapEnvelope(arguments);
        return unwrapped == null ? arguments : normalizeArguments(unwrapped);
    }

    private boolean isSchemaShaped(final Map<String, Object> arguments) {
        return arguments.containsKey("properties")
                && SCHEMA_SHAPE_KEYS.containsAll(arguments.keySet());
    }

    /** The nested argument values of a tool-call object, or {@code null} when there is no wrapper. */
    private Map<String, Object> unwrapEnvelope(final Map<String, Object> arguments) {
        final boolean marked = arguments.keySet().stream().anyMatch(ENVELOPE_MARKER_KEYS::contains);
        for (final String key : ENVELOPE_ARGUMENT_KEYS) {
            if ((marked || arguments.size() == 1)
                    && arguments.get(key) instanceof Map<?, ?> nested) {
                return stringKeyMap(nested);
            }
        }
        return null;
    }

}
