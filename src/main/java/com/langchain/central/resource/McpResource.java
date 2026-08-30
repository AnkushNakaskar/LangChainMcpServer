package com.langchain.central.resource;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.langchain.central.mcp.JerseyMcpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpResource {

    private final JerseyMcpTransport transport;
    private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();
    private final ObjectMapper lenientArgumentMapper;

    @Inject
    public McpResource(
            final JerseyMcpTransport transport,
            final ObjectMapper objectMapper) {
        this.transport = transport;
        this.lenientArgumentMapper = objectMapper.copy()
                .enable(JsonParser.Feature.ALLOW_SINGLE_QUOTES);
    }

    @POST
    public Response handle(final String body) throws IOException {
        log.info("Input request for mcp server with tool executions with input {}",body);
        final McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

        if (message instanceof McpSchema.JSONRPCRequest request) {
            return handleRequest(request);
        }

        if (message instanceof McpSchema.JSONRPCNotification notification) {
            return handleNotification(notification);
        }

        return Response.status(Response.Status.BAD_REQUEST)
                .build();
    }

    private Response handleRequest(final McpSchema.JSONRPCRequest request) throws IOException {
        final McpSchema.JSONRPCRequest normalizedRequest = normalizeToolArguments(request);
        final McpSchema.JSONRPCResponse response = transport.handler()
                .handleRequest(McpTransportContext.EMPTY, normalizedRequest)
                .block();

        return Response.ok(jsonMapper.writeValueAsString(response), MediaType.APPLICATION_JSON)
                .build();
    }

    private McpSchema.JSONRPCRequest normalizeToolArguments(
            final McpSchema.JSONRPCRequest request) {
        if (!McpSchema.METHOD_TOOLS_CALL.equals(request.method())
                || !(request.params() instanceof Map<?, ?> params)
                || !(params.get("arguments") instanceof Map<?, ?> arguments)
                || !arguments.keySet().stream()
                        .allMatch(Set.of("type", "properties", "required")::contains)) {
            return request;
        }

        final Map<String, Object> normalizedArguments =
                readSchemaProperties(arguments.get("properties"));
        if (normalizedArguments.isEmpty()) {
            return request;
        }

        final Map<String, Object> normalizedParams = stringKeyMap(params);
        normalizedParams.put("arguments", normalizedArguments);
        log.warn("Normalized schema-shaped arguments for MCP tool call {}", params.get("name"));
        return new McpSchema.JSONRPCRequest(
                request.jsonrpc(),
                request.method(),
                request.id(),
                normalizedParams);
    }

    private Map<String, Object> readSchemaProperties(final Object properties) {
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

    private Map<String, Object> stringKeyMap(final Map<?, ?> source) {
        final Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, value);
            }
        });
        return result;
    }

    private Response handleNotification(final McpSchema.JSONRPCNotification notification) {
        transport.handler()
                .handleNotification(McpTransportContext.EMPTY, notification)
                .block();
        return Response.accepted()
                .build();
    }
}
