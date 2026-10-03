package com.langchain.central.mcp.stream;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.mcp.JerseyMcpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.ws.rs.core.StreamingOutput;
import lombok.extern.slf4j.Slf4j;

/**
 * Answers a JSON-RPC request as a Server-Sent Events stream: every partial result a tool publishes
 * is written as a {@code notifications/progress} event while the tool is still running, and the
 * JSON-RPC response closes the stream.
 *
 * <p>This is what makes a tool call that takes minutes usable, because the caller receives each
 * part as it becomes available instead of waiting for the whole call, and the connection is held
 * open by comment frames rather than timing out.
 */
@Slf4j
@Singleton
public class McpStreamingResponder {

    public static final String EVENT_STREAM_MEDIA_TYPE = "text/event-stream";

    /** Longest silence written to the stream before a comment frame keeps the connection alive. */
    private static final long KEEP_ALIVE_SECONDS = 15;

    private static final String PROGRESS_TOKEN = "progressToken";

    private final JerseyMcpTransport transport;
    private final ToolStreamRegistry registry;
    private final McpJsonMapper jsonMapper = McpJsonDefaults.getMapper();

    @Inject
    public McpStreamingResponder(
            final JerseyMcpTransport transport,
            final ToolStreamRegistry registry) {
        this.transport = transport;
        this.registry = registry;
    }

    /**
     * Runs the request and streams its progress, so the response body starts before the tool call
     * has finished.
     */
    public StreamingOutput stream(final McpSchema.JSONRPCRequest request) {
        final String progressToken = progressTokenOf(request);
        final QueuedToolStream toolStream = new QueuedToolStream();
        registry.register(progressToken, toolStream);

        final CompletableFuture<McpSchema.JSONRPCResponse> result = new CompletableFuture<>();
        transport.handler()
                .handleRequest(McpTransportContext.EMPTY, withProgressToken(request, progressToken))
                .subscribe(result::complete, result::completeExceptionally);
        // Ends the wait for the next partial result the moment the call is over, so the response
        // is not held back by a keep-alive interval that nothing will be published in.
        result.whenComplete((response, error) -> toolStream.wakeUp());

        return output -> write(output, request, progressToken, toolStream, result);
    }

    private void write(
            final OutputStream output,
            final McpSchema.JSONRPCRequest request,
            final String progressToken,
            final QueuedToolStream toolStream,
            final CompletableFuture<McpSchema.JSONRPCResponse> result) throws IOException {
        try (Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            long published = 0;
            while (!result.isDone() || !toolStream.isEmpty()) {
                final String partial = awaitPartial(toolStream);
                if (partial != null) {
                    writeEvent(writer, output, progressNotification(
                            progressToken, ++published, partial));
                } else if (!result.isDone()) {
                    writeKeepAlive(writer, output);
                }
            }
            writeEvent(writer, output, response(request, result));
        } finally {
            registry.remove(progressToken);
        }
    }

    /** Waits briefly for the next partial result, returning {@code null} when none was produced. */
    private String awaitPartial(final QueuedToolStream toolStream) {
        try {
            return toolStream.poll(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The response of the call, as an error response when the call itself failed. */
    private McpSchema.JSONRPCResponse response(
            final McpSchema.JSONRPCRequest request,
            final CompletableFuture<McpSchema.JSONRPCResponse> result) {
        try {
            return result.join();
        } catch (RuntimeException e) {
            log.error("MCP request {} failed while streaming", request.id(), e);
            return McpSchema.JSONRPCResponse.error(
                    request.id(),
                    new McpSchema.JSONRPCResponse.JSONRPCError(
                            McpSchema.ErrorCodes.INTERNAL_ERROR, e.getMessage()));
        }
    }

    private McpSchema.JSONRPCNotification progressNotification(
            final String progressToken, final double progress, final String message) {
        final Map<String, Object> params = new LinkedHashMap<>();
        params.put(PROGRESS_TOKEN, progressToken);
        params.put("progress", progress);
        params.put("message", message);
        return new McpSchema.JSONRPCNotification(
                McpSchema.JSONRPC_VERSION, McpSchema.METHOD_NOTIFICATION_PROGRESS, params);
    }

    private void writeEvent(
            final Writer writer,
            final OutputStream output,
            final McpSchema.JSONRPCMessage message) throws IOException {
        writer.write("event: message\n");
        writer.write("data: ");
        writer.write(jsonMapper.writeValueAsString(message));
        writer.write("\n\n");
        flush(writer, output);
    }

    /** A comment frame, which a client ignores and a proxy treats as traffic on the connection. */
    private void writeKeepAlive(final Writer writer, final OutputStream output) throws IOException {
        writer.write(": keep-alive\n\n");
        flush(writer, output);
    }

    /** Both buffers are flushed, otherwise an event waits in the servlet buffer until the end. */
    private void flush(final Writer writer, final OutputStream output) throws IOException {
        writer.flush();
        output.flush();
    }

    /** The token the client asked progress to be reported under, or one created for this call. */
    private String progressTokenOf(final McpSchema.JSONRPCRequest request) {
        if (request.params() instanceof Map<?, ?> params
                && params.get("_meta") instanceof Map<?, ?> meta
                && meta.get(PROGRESS_TOKEN) != null) {
            return String.valueOf(meta.get(PROGRESS_TOKEN));
        }
        return UUID.randomUUID().toString();
    }

    /**
     * The request carrying the progress token, which is how the tool handler, invoked on a
     * scheduler of the MCP SDK, finds the stream to publish its partial results to.
     */
    private McpSchema.JSONRPCRequest withProgressToken(
            final McpSchema.JSONRPCRequest request, final String progressToken) {
        if (!(request.params() instanceof Map<?, ?> params)) {
            return request;
        }
        final Map<String, Object> updatedParams = new LinkedHashMap<>();
        params.forEach((key, value) -> updatedParams.put(String.valueOf(key), value));

        final Map<String, Object> meta = new LinkedHashMap<>();
        if (updatedParams.get("_meta") instanceof Map<?, ?> existingMeta) {
            existingMeta.forEach((key, value) -> meta.put(String.valueOf(key), value));
        }
        meta.put(PROGRESS_TOKEN, progressToken);
        updatedParams.put("_meta", meta);

        return new McpSchema.JSONRPCRequest(
                request.jsonrpc(), request.method(), request.id(), updatedParams);
    }
}
