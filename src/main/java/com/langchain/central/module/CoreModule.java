package com.langchain.central.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.langchain.central.BasicConfiguration;
import com.langchain.central.config.LLMConfig;
import com.langchain.central.config.McpConfig;
import com.langchain.central.dao.InMemoryMovieDao;
import com.langchain.central.dao.MovieDao;
import com.langchain.central.service.tool.MovieToolService;
import com.langchain.central.service.tool.ToolService;

/**
 * Wires the three layers together: the DAO the tools read from, the tools the assistant is given,
 * and the configuration block the service layer needs.
 *
 * @author ankush.nakaskar
 */
public class CoreModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(MovieDao.class).to(InMemoryMovieDao.class);

        // the sets are injected into LangChainService and offered to the model as callable tools,
        // so a new tool class only has to be added here
        Multibinder.newSetBinder(binder(), MovieToolService.class)
                .addBinding().to(MovieToolService.class);

        Multibinder.newSetBinder(binder(), ToolService.class)
                .addBinding().to(MovieToolService.class);
    }

    /** Exposes the {@code llm} block of application.yml to the service layer. */
    @Provides
    @Singleton
    public LLMConfig llmConfig(final BasicConfiguration configuration) {
        return configuration.getLlm();
    }

    @Provides
    @Singleton
    public McpConfig mcpConfig(final BasicConfiguration configuration) {
        return configuration.getMcp();
    }

}
