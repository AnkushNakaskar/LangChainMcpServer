# LangChain MCP Server

This Maven project runs one Dropwizard 2 application that exposes:

- Existing REST endpoints under `/langchain`
- A stateless Model Context Protocol endpoint at `POST /mcp`
- Every LangChain4j `@Tool` method registered in Guice's `Set<ToolService>`

The Jersey resource adapts HTTP JSON-RPC requests to the official Java MCP SDK. No separate
MCP `main` process is required.

## Build and run

```bash
mvn clean package
java -jar target/langchain_mcp_server.jar
```

The application runs on port `8088` by default, so the MCP URL is:

```text
http://localhost:8088/mcp
```

Server identity and instructions are configured in `src/main/resources/application.yml`:

```yaml
mcp:
  enabled: true
  name: langchain-mcp-server
  version: 1.0.0
  instructions: Use the tools registered from the service package to answer requests.
```

## Registering tools

Implement `ToolService`, annotate public methods with LangChain4j `@Tool`, annotate parameters
with `@P`, and bind the service into `Set<ToolService>` in `CoreModule`.

The same service object can then be used by both LangChain4j and the MCP server. MCP tool names
are generated from method names, for example `getMovieGenre` becomes `get_movie_genre`.

## MCP request example

```bash
curl -X POST http://localhost:8088/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```
