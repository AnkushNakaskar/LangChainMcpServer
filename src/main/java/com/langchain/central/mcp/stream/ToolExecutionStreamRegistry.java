package com.langchain.central.mcp.stream;

import com.google.inject.Singleton;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The streams of the tool calls that are currently in flight, keyed by the progress token carried
 * by the request. That token is the only value that reaches the tool handler, which the MCP SDK
 * invokes on its own scheduler.
 */
@Singleton
public class ToolExecutionStreamRegistry {

    private final Map<String, ToolStream> toolExecutionStreams = new ConcurrentHashMap<>();

    public void register(final String toolExecutionToken,
                         final ToolStream stream) {
        toolExecutionStreams.put(toolExecutionToken, stream);
    }

    public void removeToolExecutionStream(final String toolExecutionToken) {
        toolExecutionStreams.remove(toolExecutionToken);
    }

    public Optional<ToolStream> getToolExecution(final String toolExecutionToken) {
        return Optional.ofNullable(toolExecutionStreams.getOrDefault(toolExecutionToken,null));
    }
}
