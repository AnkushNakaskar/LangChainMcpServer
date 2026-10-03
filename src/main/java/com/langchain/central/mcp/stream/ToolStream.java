package com.langchain.central.mcp.stream;

/**
 * Sink a long running tool writes its intermediate output to, so a caller sees the work as it is
 * produced instead of waiting for the whole tool call to finish.
 *
 * <p>Modelled on the partial-response callback of LangChain4j streaming: the tool still returns its
 * complete result, and every partial message published here is additionally delivered to the client
 * as an MCP {@code notifications/progress} event.
 */
public interface ToolStream {

    /** Discards partial output, used when a tool call was not made over a streaming request. */
    ToolStream NOOP = message -> {
    };

    /**
     * Publishes a piece of the result that is ready now.
     *
     * @param message human readable fragment of the tool result
     */
    void partial(String message);
}
