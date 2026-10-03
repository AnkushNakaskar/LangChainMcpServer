package com.langchain.central.mcp.stream;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Hands partial tool output from the thread running the tool to the thread writing the HTTP
 * response. The MCP SDK runs a synchronous tool on its own scheduler, so the two are never the same
 * thread.
 */
public class QueuedToolStream implements ToolStream {

    /** Enqueued to end the wait of a reader as soon as the tool call itself is finished. */
    private static final String WAKE_UP = "\u0000";

    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

    @Override
    public void partial(final String message) {
        if (message != null && !message.isBlank()) {
            messages.add(message);
        }
    }

    /** Releases a reader that is waiting, used once no further partial result can arrive. */
    public void wakeUp() {
        messages.add(WAKE_UP);
    }

    /**
     * The next partial message, waited for only up to the given time so the caller can keep the
     * connection alive while a slow tool produces nothing.
     *
     * @return the next message, or {@code null} when none arrived in time
     */
    public String poll(final long timeout, final TimeUnit unit) throws InterruptedException {
        final String message = messages.poll(timeout, unit);
        return WAKE_UP.equals(message) ? null : message;
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }
}
