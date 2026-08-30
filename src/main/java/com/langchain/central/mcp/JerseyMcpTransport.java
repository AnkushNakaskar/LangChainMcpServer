package com.langchain.central.mcp;

import com.google.inject.Singleton;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

@Singleton
public class JerseyMcpTransport implements McpStatelessServerTransport {

    private volatile McpStatelessServerHandler handler;

    @Override
    public void setMcpHandler(final McpStatelessServerHandler handler) {
        this.handler = handler;
    }

    public McpStatelessServerHandler handler() {
        final McpStatelessServerHandler current = handler;
        if (current == null) {
            throw new IllegalStateException("MCP server has not started");
        }
        return current;
    }

    @Override
    public Mono<Void> closeGracefully() {
        handler = null;
        return Mono.empty();
    }
}
