package com.langchain.central.util;

import java.util.Locale;
import lombok.experimental.UtilityClass;

@UtilityClass
public class McpToolSchemaUtil {

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
}
