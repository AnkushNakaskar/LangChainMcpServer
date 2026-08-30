package com.langchain.central.mcp;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.config.McpConfig;
import io.dropwizard.lifecycle.Managed;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

@Singleton
public class McpManagedService implements Managed {

    private final McpConfig config;
    private final JerseyMcpTransport transport;
    private final McpToolRegistrar toolRegistrar;
    private McpStatelessSyncServer server;

    @Inject
    public McpManagedService(
            final McpConfig config,
            final JerseyMcpTransport transport,
            final McpToolRegistrar toolRegistrar) {
        this.config = config;
        this.transport = transport;
        this.toolRegistrar = toolRegistrar;
    }

    @Override
    public void start() {
        if (!config.isEnabled()) {
            return;
        }
        server = McpServer.sync(transport)
                .serverInfo(config.getName(), config.getVersion())
                .instructions(config.getInstructions())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .tools(toolRegistrar.tools())
                .build();
    }

    @Override
    public void stop() {
        if (server != null) {
            server.closeGracefully();
        }
    }
}
