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
public class ToolStreamRegistry {

    private final Map<String, ToolStream> streams = new ConcurrentHashMap<>();

    public void register(final String progressToken, final ToolStream stream) {
        streams.put(progressToken, stream);
    }

    public void remove(final String progressToken) {
        streams.remove(progressToken);
    }

    public Optional<ToolStream> find(final String progressToken) {
        return progressToken == null
               ? Optional.empty()
               : Optional.ofNullable(streams.get(progressToken));
    }
}
