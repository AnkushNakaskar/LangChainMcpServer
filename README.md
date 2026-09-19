# LangChain MCP Server

A Java 17, Maven and Dropwizard 2 application that exposes Java methods as Model Context Protocol
(MCP) tools over HTTP.

The server uses:

- Dropwizard and Jersey for `POST /mcp`
- The official Java MCP SDK for JSON-RPC and MCP request handling
- Guice for discovering registered tool services
- LangChain4j `@Tool` and `@P` annotations for tool metadata

The application is both a normal Dropwizard service and an MCP server. It does not require a
separate MCP `main` class or Spring.

## 1. Install and configure a local LLM

The MCP server can list and execute tools without an LLM. An LLM is required when another
application, such as `LangChainMCPClient`, must understand a natural-language message, select the
appropriate MCP tool and convert the tool result into a final response.

For instructions covering local LLM installation, Ollama setup, model selection and integration
with a Java project, refer to:

[AnkushNakaskar/LangChainDemo](https://github.com/AnkushNakaskar/LangChainDemo)

Typical Ollama setup:

```bash
brew install ollama
ollama serve
ollama pull llama3.1:8b
```

Confirm that the model is installed:

```bash
ollama list
```

Configure the application that consumes this MCP server with the exact model name shown by
`ollama list`:

```yaml
llm:
  type: LOCAL
  baseUrl: http://localhost:11434/v1
  modelName: llama3.1:8b
  apiKey: not-needed
  temperature: 0.0
  timeoutSeconds: 120
```

For reliable tool selection, prefer a tool-capable model such as `llama3.1:8b` or `qwen2.5:7b`.
Very small models may copy a tool's JSON schema into its arguments instead of supplying the
required argument values.

## Architecture

```text
MCP client
   |
   | POST /mcp (JSON-RPC)
   v
McpResource
   |
   v
JerseyMcpTransport
   |
   v
Official Java MCP SDK
   |
   v
McpToolRegistrar
   |
   v
Set<ToolService> supplied by Guice
   |
   v
GitToolService -> local Git repository
```

`McpManagedService` starts and stops the MCP SDK with the Dropwizard lifecycle.

`McpToolRegistrar` reads public methods annotated with `@Tool`, generates their JSON schemas, and
registers handlers that invoke the corresponding Java methods. Maven compilation retains parameter
names with `-parameters`, allowing MCP arguments such as `baseRevision` to map to Java parameters.

## Available Git tools

| MCP tool | Java method | Arguments | Description |
|---|---|---|---|
| `review_merge_request` | `reviewMergeRequest` | `mergeRequest`, `context` | Fetches a merge request link, diffs it against its target branch, and returns the description and changed lines per file to review against the given context |
| `get_git_status` | `getGitStatus` | None | Shows the branch and working tree status |
| `get_working_tree_diff` | `getWorkingTreeDiff` | None | Returns unstaged changes |
| `get_staged_diff` | `getStagedDiff` | None | Returns staged changes |
| `get_diff_between_revisions` | `getDiffBetweenRevisions` | `baseRevision`, `headRevision` | Returns a review patch |
| `list_changed_files` | `listChangedFiles` | `baseRevision`, `headRevision` | Lists changed files and statuses |
| `get_recent_commits` | `getRecentCommits` | `maxCount` | Shows up to 100 recent commits |
| `show_commit` | `showCommit` | `revision` | Shows commit metadata, statistics, and patch |

All tools are read-only and invoke fixed Git commands rather than arbitrary shell input. By default,
commands run in the server process working directory. Set `GIT_REPOSITORY_PATH` or the
`git.repository.path` JVM system property to review another local repository.

## Configuration

MCP settings are in `src/main/resources/application.yml`:

```yaml
server:
  applicationConnectors:
    - type: http
      port: 8088

mcp:
  enabled: true
  name: langchain-mcp-server
  version: 1.0.0
  instructions: Use the tools registered from the service package to answer requests.
```

The MCP endpoint is:

```text
http://localhost:8088/mcp
```

## Build and run

Requirements:

- Java 17
- Maven 3.8 or later

Build and start the server:

```bash
cd /Users/ankush.nakaskar/Office/newCode/personal_projects/LangChainMcpServer
mvn clean package
java -jar target/langchain_mcp_server.jar
```

The MCP server does not need Ollama to execute Git tools directly. Ollama is only needed for the
optional `/langchain` chat APIs that use an LLM.

## Verify the MCP server

### Initialize

An MCP client normally performs this automatically.

```bash
curl -X POST http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-11-25",
      "capabilities": {},
      "clientInfo": {
        "name": "curl-client",
        "version": "1.0.0"
      }
    }
  }'
```

### List tools

```bash
curl -X POST http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {}
  }'
```

The response contains each tool's name, description and generated input schema.

### Call a tool

Get the working tree status:

```bash
curl -X POST http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "get_git_status",
      "arguments": {}
    }
  }'
```

Expected tool result:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "## main...origin/main"
      }
    ],
    "isError": false
  }
}
```

Compare a feature branch with `main`:

```bash
curl -X POST http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "get_diff_between_revisions",
      "arguments": {
        "baseRevision": "main",
        "headRevision": "feature/code-review"
      }
    }
  }'
```

Review a merge request from its link:

```bash
curl -X POST http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 5,
    "method": "tools/call",
    "params": {
      "name": "review_merge_request",
      "arguments": {
        "mergeRequest": "https://gitlab.example.com/group/project/-/merge_requests/727",
        "context": "Review for clean code, design patterns, function names, field names and constants."
      }
    }
  }'
```

## Consume from LangChainMCPClient

Use the dedicated MCP client repository:

[AnkushNakaskar/LangChainMCPClient](https://github.com/AnkushNakaskar/LangChainMCPClient)

Clone and configure the client:

```bash
git clone https://github.com/AnkushNakaskar/LangChainMCPClient.git
cd LangChainMCPClient
```

Configure its `application.yml`:

```yaml
mcpClient:
  enabled: true
  url: http://localhost:8088/mcp
  key: langchain-mcp-server
  timeoutSeconds: 30
  logRequests: false
  logResponses: false
```

Start this MCP server first, then start `LangChainMCPClient`:

```bash
mvn clean package
java -jar target/langchain_mcp_client.jar
```

Ask the LLM to use the Git tools:

```bash
curl -X POST http://localhost:8090/langchain/chat \
  -H 'Content-Type: application/json' \
  -d '{
    "prompt": "Review the changes on feature/code-review compared with main.",
    "assistant": "CODE_REVIEW",
    "sessionId": "code-review-demo",
    "useTools": true
  }'
```

The complete execution flow is:

```text
API request to LangChainMCPClient
  -> LLM selects the required Git tools
  -> LangChain4j McpToolProvider sends tools/call to this server
  -> GitToolService executes read-only Git commands
  -> MCP result returns to the LLM
  -> LangChainMCPClient returns the final natural-language response
```

## Add another tool

Add a public method to a class implementing `ToolService`:

```java
@Tool("Get the current Git branch name")
public String getCurrentBranch() {
    return runGit("No current branch.", "branch", "--show-current");
}
```

If creating a new service class, bind it into `Set<ToolService>` in `CoreModule`:

```java
Multibinder.newSetBinder(binder(), ToolService.class)
        .addBinding()
        .to(NewToolService.class);
```

After restarting the server, `tools/list` will include the new tool. Java method names are converted
to snake case, so `getCurrentBranch` becomes `get_current_branch`.

## Important rules for tool methods

- The class must implement `ToolService`.
- The method must be public and annotated with `@Tool`.
- Parameters should be annotated with `@P`.
- Tool method names must be unique after snake-case conversion.
- Argument names in `tools/call` must match Java parameter names.
- Return values are converted to MCP text content.
- Exceptions are returned as MCP tool errors.
