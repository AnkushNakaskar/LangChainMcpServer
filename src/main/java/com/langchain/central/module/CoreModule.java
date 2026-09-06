package com.langchain.central.module;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.langchain.central.BasicConfiguration;
import com.langchain.central.config.McpConfig;
import com.langchain.central.service.tool.GitToolService;
import com.langchain.central.service.tool.ToolService;

/**
 * Wires the tools the assistant is given and the configuration block the service layer needs.
 *
 * @author ankush.nakaskar
 */
public class CoreModule extends AbstractModule {

    @Override
    protected void configure() {
        Multibinder.newSetBinder(binder(), ToolService.class)
                .addBinding().to(GitToolService.class);
    }


    @Provides
    @Singleton
    public McpConfig mcpConfig(final BasicConfiguration configuration) {
        return configuration.getMcp();
    }

}
