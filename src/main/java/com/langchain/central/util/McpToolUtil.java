package com.langchain.central.util;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
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

    public McpSchema.JSONRPCRequest normalizeToolArguments(
            final McpSchema.JSONRPCRequest request) {
        if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())
                || !(request.params() instanceof Map<?, ?> params)
                || !(params.get("arguments") instanceof Map<?, ?> arguments)
                || !arguments.keySet().stream()
                .allMatch(Set.of("type", "properties", "required")::contains)) {
            return request;
        }

        final Map<String, Object> normalizedArguments = McpToolUtil.readSchemaProperties(arguments.get("properties"));
        if (normalizedArguments.isEmpty()) {
            return request;
        }

        final Map<String, Object> normalizedParams = McpToolUtil.stringKeyMap(params);
        normalizedParams.put("arguments", normalizedArguments);
        log.warn("Normalized schema-shaped arguments for MCP tool call {}", params.get("name"));
        return new McpSchema.JSONRPCRequest(
                request.jsonrpc(),
                request.method(),
                request.id(),
                normalizedParams);
    }

}
