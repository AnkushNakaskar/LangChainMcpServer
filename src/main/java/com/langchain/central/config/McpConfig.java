package com.langchain.central.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import javax.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class McpConfig {

    @JsonProperty("enabled")
    private boolean enabled = true;

    @NotBlank
    @JsonProperty("name")
    private String name = "langchain-mcp-server";

    @NotBlank
    @JsonProperty("version")
    private String version = "1.0.0";

    @NotBlank
    @JsonProperty("instructions")
    private String instructions = "Use the registered service tools to answer requests.";
}
