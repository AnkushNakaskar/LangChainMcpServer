package com.langchain.central.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.langchain.central.assistance.AssistanceType;
import com.langchain.central.assistance.MovieAssistance;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.model.AIRequest;
import com.langchain.central.model.AIResponse;
import com.langchain.central.service.tool.MovieToolService;
import com.langchain.central.service.tool.ToolService;
import com.langchain.central.util.LLMConvertors;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Serves chat requests: it owns the assistants, sends the prompt to the configured LLM and maps
 * the outcome onto the API model.
 *
 * <p>An assistant is built once per model and tool combination and then reused, because each one
 * carries an HTTP client and the chat memory of every session it has served. Building one per
 * request would drop the conversation and open a new client each time.
 *
 * <p>The tools are handed to langchain4j as plain annotated objects. It reads their {@code @Tool}
 * methods, offers them to the model and executes the ones the model calls, so nothing in this
 * application has to sit between the model and a tool.
 *
 * @author ankush.nakaskar
 */
@Slf4j
@Singleton
public class LangChainService {

    private final LLMConfig llmConfig;
    private final Set<MovieToolService> movieTools;
    private final Map<String, MovieAssistance> assistants = new ConcurrentHashMap<>();

    @Inject
    public LangChainService(final LLMConfig llmConfig, final Set<MovieToolService> movieTools) {
        this.llmConfig = llmConfig;
        this.movieTools = movieTools;
        log.info("LLM configured as {} model {} at {}",
                llmConfig.getType(), llmConfig.getModelName(), llmConfig.getBaseUrl());
        log.info("Movie assistant registered tools {}",
                movieTools.stream().map(ToolService::name).collect(Collectors.toList()));
    }






    /**
     * Sends the prompt to the model and maps the outcome, including any tool the model called,
     * onto {@link AIResponse}.
     */
    public AIResponse chat(final AIRequest request) {
        final AssistanceType assistanceType = request.getAssistant();
        final String modelName = request.getModelOrDefault(llmConfig.getModelName());
        final String sessionId = request.getSessionId();
        final long startedAt = System.nanoTime();

        log.info("Chat request on session {} for assistant {} using model {}, tools {}",
                sessionId, assistanceType, modelName,
                request.isUseTools() ? "enabled" : "disabled");

        final MovieAssistance assistant = assistantFor(modelName, request.isUseTools());
        final Result<String> result = assistant.chat(sessionId, request.getPrompt());

        return LLMConvertors.toAIResponse(
                result, modelName, sessionId, System.nanoTime() - startedAt);
    }

    /** Assistants are cached per model and tool combination; see the class comment. */
    private MovieAssistance assistantFor(final String modelName, final boolean useTools) {
        return assistants.computeIfAbsent(modelName + "|tools=" + useTools,
                ignored -> build(modelName, useTools));
    }

    private MovieAssistance build(final String modelName, final boolean useTools) {
        final ChatModel model = LLMConvertors.toChatModel(llmConfig, modelName);

        final AiServices<MovieAssistance> builder = AiServices.builder(MovieAssistance.class)
                .chatModel(model)
                // one memory per sessionId, so concurrent conversations do not read each other
                .chatMemoryProvider(sessionId ->
                        MessageWindowChatMemory.withMaxMessages(llmConfig.getMaxMemoryMessages()));

        if (useTools && !movieTools.isEmpty()) {
            final Collection<Object> tools = new ArrayList<>(movieTools);
            builder.tools(tools)
                    // a small model that is unhappy with a tool result will otherwise keep calling
                    // tools; langchain4j allows a hundred rounds by default
                    .maxToolCallingRoundTrips(llmConfig.getMaxToolCallingRoundTrips());
        }
        log.info("Built movie assistant for model {} with tools {}", modelName,
                useTools ? toolNames() : List.of());
        return builder.build();
    }

    private List<String> toolNames() {
        return movieTools.stream().map(ToolService::name).collect(Collectors.toList());
    }
}
