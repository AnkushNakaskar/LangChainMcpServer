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
import java.io.UncheckedIOException;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;

/**
 * The HTTP face of the MCP server.
 *
 * <p>The request is suspended rather than awaited: the MCP server answers asynchronously, so the
 * thread that accepted the request is released immediately and a tool call that runs for minutes
 * no longer occupies one of the container's threads. The response is sent from whichever thread
 * completes the call.
 */
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
    public void handle(final String body,
                       @Context final HttpHeaders headers,
                       @Suspended final AsyncResponse asyncResponse) throws IOException {
        log.info("Input request for mcp server with tool executions with input {}", body);
        final McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

        if (message instanceof McpSchema.JSONRPCRequest request) {
            handleRequest(request, acceptsEventStream(headers), asyncResponse);
            return;
        }

        if (message instanceof McpSchema.JSONRPCNotification notification) {
            handleNotification(notification, asyncResponse);
            return;
        }

        asyncResponse.resume(Response.status(Response.Status.BAD_REQUEST)
                .build());
    }

    /**
     * A client that accepts an event stream is answered as it goes, so a tool call that runs for
     * minutes delivers each part of its result while it is still working. A client that does not
     * is answered once, but only when the call completes, without a thread waiting in between.
     */
    private void handleRequest(
            final McpSchema.JSONRPCRequest request,
            final boolean streaming,
            final AsyncResponse asyncResponse) {
        final McpSchema.JSONRPCRequest normalizedRequest = McpToolUtil.normalizeToolArguments(request);

        if (streaming) {
            asyncResponse.resume(Response.ok(streamingResponder.stream(normalizedRequest))
                    .type(McpStreamingResponder.EVENT_STREAM_MEDIA_TYPE)
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                    .header("X-Accel-Buffering", "no")
                    .build());
            return;
        }

        transport.handler()
                .handleRequest(McpTransportContext.EMPTY, normalizedRequest)
                .subscribe(
                        response -> asyncResponse.resume(json(response)),
                        error -> asyncResponse.resume(json(errorResponse(request, error))));
    }

    private void handleNotification(final McpSchema.JSONRPCNotification notification,
                                    final AsyncResponse asyncResponse) {
        transport.handler()
                .handleNotification(McpTransportContext.EMPTY, notification)
                .subscribe(
                        ignored -> {
                        },
                        error -> {
                            log.error("MCP notification {} failed", notification.method(), error);
                            asyncResponse.resume(Response.serverError()
                                    .build());
                        },
                        () -> asyncResponse.resume(Response.accepted()
                                .build()));
    }

    private Response json(final McpSchema.JSONRPCResponse response) {
        try {
            return Response.ok(jsonMapper.writeValueAsString(response), MediaType.APPLICATION_JSON)
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A failed call is still a JSON-RPC answer, so the client is not left with a bare 500. */
    private McpSchema.JSONRPCResponse errorResponse(final McpSchema.JSONRPCRequest request,
                                                    final Throwable error) {
        log.error("MCP request {} failed", request.id(), error);
        return McpSchema.JSONRPCResponse.error(
                request.id(),
                new McpSchema.JSONRPCResponse.JSONRPCError(
                        McpSchema.ErrorCodes.INTERNAL_ERROR, error.getMessage()));
    }

    private boolean acceptsEventStream(final HttpHeaders headers) {
        final String accept = headers.getHeaderString(HttpHeaders.ACCEPT);
        return accept != null
                && accept.toLowerCase().contains(McpStreamingResponder.EVENT_STREAM_MEDIA_TYPE);
    }
}
