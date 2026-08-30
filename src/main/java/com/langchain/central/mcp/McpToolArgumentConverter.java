package com.langchain.central.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;

@Singleton
public class McpToolArgumentConverter {

    private final ObjectMapper objectMapper;

    @Inject
    public McpToolArgumentConverter(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Object convert(final Object value, final Class<?> targetType) {
        if (value == null) {
            if (targetType.isPrimitive()) {
                throw new IllegalArgumentException(
                        "A value is required for primitive type " + targetType.getSimpleName());
            }
            return null;
        }
        if (targetType.isInstance(value)) {
            return value;
        }
        try {
            return objectMapper.convertValue(value, targetType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Cannot convert MCP argument value '" + value + "' to "
                            + targetType.getSimpleName(),
                    e);
        }
    }
}
