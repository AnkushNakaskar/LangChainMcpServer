package com.langchain.central.resource;

import com.google.inject.Inject;
import com.langchain.central.config.McpConfig;
import com.langchain.central.mcp.JerseyMcpTransport;
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

@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpResource {

    private final JerseyMcpTransport transport;
    private final McpConfig config;
    private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

    @Inject
    public McpResource(final JerseyMcpTransport transport, final McpConfig config) {
        this.transport = transport;
        this.config = config;
    }

    @POST
    public Response handle(final String body) throws IOException {
        if (!config.isEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        final McpSchema.JSONRPCMessage message =
                McpSchema.deserializeJsonRpcMessage(jsonMapper, body);
        if (message instanceof McpSchema.JSONRPCRequest request) {
            final McpSchema.JSONRPCResponse response = transport.handler()
                    .handleRequest(McpTransportContext.EMPTY, request)
                    .block();
            return Response.ok(jsonMapper.writeValueAsString(response)).build();
        }
        if (message instanceof McpSchema.JSONRPCNotification notification) {
            transport.handler()
                    .handleNotification(McpTransportContext.EMPTY, notification)
                    .block();
            return Response.accepted().build();
        }
        return Response.status(Response.Status.BAD_REQUEST).build();
    }
}
