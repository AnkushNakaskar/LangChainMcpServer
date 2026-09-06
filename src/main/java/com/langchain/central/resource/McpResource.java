package com.langchain.central.resource;

import com.google.inject.Inject;
import com.langchain.central.mcp.JerseyMcpTransport;
import com.langchain.central.util.McpToolUtil;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
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

    @Inject
    public McpResource(
            final JerseyMcpTransport transport) {
        this.transport = transport;
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
        final McpSchema.JSONRPCRequest normalizedRequest = McpToolUtil.normalizeToolArguments(request);
        final McpSchema.JSONRPCResponse response = transport.handler()
                .handleRequest(McpTransportContext.EMPTY, normalizedRequest)
                .block();

        return Response.ok(jsonMapper.writeValueAsString(response), MediaType.APPLICATION_JSON)
                .build();
    }


    private Response handleNotification(final McpSchema.JSONRPCNotification notification) {
        transport.handler()
                .handleNotification(McpTransportContext.EMPTY, notification)
                .block();
        return Response.accepted()
                .build();
    }
}
