package com.langchain.central.resource;

import com.google.inject.Inject;
import com.langchain.central.mcp.JerseyMcpTransport;
import com.langchain.central.mcp.stream.McpStreamingResponder;
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
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Path("/mcp")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class McpResource {

    private final JerseyMcpTransport transport;
    private final McpStreamingResponder streamingResponder;
    private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

    @Inject
    public McpResource(
            final JerseyMcpTransport transport,
            final McpStreamingResponder streamingResponder) {
        this.transport = transport;
        this.streamingResponder = streamingResponder;
    }

    @POST
    @Produces({MediaType.APPLICATION_JSON, McpStreamingResponder.EVENT_STREAM_MEDIA_TYPE})
    public Response handle(final String body, @Context final HttpHeaders headers) throws IOException {
        log.info("Input request for mcp server with tool executions with input {}", body);
        final McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

        if (message instanceof McpSchema.JSONRPCRequest request) {
            return handleRequest(request, acceptsEventStream(headers));
        }

        if (message instanceof McpSchema.JSONRPCNotification notification) {
            return handleNotification(notification);
        }

        return Response.status(Response.Status.BAD_REQUEST)
                .build();
    }

    /**
     * A client that accepts an event stream is answered as it goes, so a tool call that runs for
     * minutes delivers each part of its result while it is still working.
     */
    private Response handleRequest(
            final McpSchema.JSONRPCRequest request,
            final boolean streaming) throws IOException {
        final McpSchema.JSONRPCRequest normalizedRequest = McpToolUtil.normalizeToolArguments(request);

        if (streaming) {
            return Response.ok(streamingResponder.stream(normalizedRequest))
                    .type(McpStreamingResponder.EVENT_STREAM_MEDIA_TYPE)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .build();
        }

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

    private boolean acceptsEventStream(final HttpHeaders headers) {
        final String accept = headers.getHeaderString(HttpHeaders.ACCEPT);
        return accept != null
                && accept.toLowerCase().contains(McpStreamingResponder.EVENT_STREAM_MEDIA_TYPE);
    }
}
