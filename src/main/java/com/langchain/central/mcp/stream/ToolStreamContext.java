package com.langchain.central.mcp.stream;

/**
 * The stream of the tool call running on this thread, so a tool can publish partial output without
 * taking a sink parameter and changing the schema it exposes to a model.
 */
public final class ToolStreamContext {

    private static final ThreadLocal<ToolStream> CURRENT = new ThreadLocal<>();

    private ToolStreamContext() {
    }

    /** The stream of the current tool call, or a sink that discards output when not streaming. */
    public static ToolStream current() {
        final ToolStream stream = CURRENT.get();
        return stream == null ? ToolStream.NOOP : stream;
    }

    public static void bind(final ToolStream stream) {
        CURRENT.set(stream == null ? ToolStream.NOOP : stream);
    }

    public static void clear() {
        CURRENT.remove();
    }
}
