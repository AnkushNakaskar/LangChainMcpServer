package com.langchain.central.mcp;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.config.McpConfig;
import io.dropwizard.lifecycle.Managed;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessAsyncServer;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Owns the MCP server for the lifetime of the application.
 *
 * <p>The server is built in its asynchronous form: a tool call is handed back as a {@code Mono},
 * so a call that runs for minutes holds a worker thread of its own instead of the thread that
 * accepted the request. That is what lets many tool calls, and the progress each one streams, be
 * in flight at the same time.
 */
@Singleton
public class McpManagedService implements Managed {

    private final McpConfig config;
    private final JerseyMcpTransport transport;
    private final McpToolProcessor toolRegistrar;
    private McpStatelessAsyncServer server;

    @Inject
    public McpManagedService(
            final McpConfig config,
            final JerseyMcpTransport transport,
            final McpToolProcessor toolRegistrar) {
        this.config = config;
        this.transport = transport;
        this.toolRegistrar = toolRegistrar;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            return;
        }
        server = McpServer.async(transport)
                .serverInfo(config.getName(), config.getVersion())
                .instructions(config.getInstructions())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(toolRegistrar.getAvailableTools())
                .build();
    }

    @Override
    public void stop() {
        if (server != null) {
            // shutdown is the one point where waiting is correct, otherwise the application would
            // exit while tool calls are still being torn down
            server.closeGracefully().block();
        }
    }
}
