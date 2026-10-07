package com.intelhub.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class McpToolClient {
    private static final Logger log = LoggerFactory.getLogger(McpToolClient.class);
    private final String url;
    private volatile McpSyncClient client;

    public McpToolClient(@Value("${intelhub.mcp.url:http://localhost:9099/mcp}") String url) { this.url = url; }

    @PostConstruct
    void connect() {
        try { ensureConnected(); }
        catch (Exception ignored) { /* The application remains usable when an optional MCP server is offline. */ }
    }

    public String invokeForQuestion(String question) {
        String toolName = null;
        Map<String, Object> args = Map.of();
        if (question.contains("工单") || question.toLowerCase(java.util.Locale.ROOT).contains("ticket")) toolName = "ticket_summary";
        else if (question.contains("制度目录")) { toolName = "policy_lookup"; args = Map.of("topic", question); }
        if (toolName == null) return "";
        try {
            McpSyncClient active = client;
            if (active == null) active = ensureConnected();
            CallToolResult result = active.callTool(new CallToolRequest(toolName, args));
            if (result.isError()) return "MCP 工具调用失败。";
            return result.content().stream().filter(TextContent.class::isInstance).map(TextContent.class::cast).map(TextContent::text).reduce("", (a, b) -> a + b);
        } catch (Exception ex) {
            log.warn("MCP tool call failed: {}", ex.getMessage());
            return "";
        }
    }

    public List<String> availableTools() {
        try {
            McpSyncClient active = client == null ? ensureConnected() : client;
            return active.listTools().tools().stream().map(tool -> tool.name()).toList();
        } catch (Exception ex) { return List.of(); }
    }

    private synchronized McpSyncClient ensureConnected() {
        if (client != null) return client;
        try {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(url).build();
            client = McpClient.sync(transport).clientInfo(new Implementation("intelhub-app", "0.1.0")).build();
            client.initialize();
            log.info("Connected to MCP tools at {}: {}", url, client.listTools().tools().stream().map(tool -> tool.name()).toList());
            return client;
        } catch (Exception ex) {
            client = null;
            log.info("MCP server not ready at {}: {}", url, ex.getMessage());
            throw new IllegalStateException(ex);
        }
    }

    @PreDestroy
    void close() {
        if (client != null) {
            try { client.close(); } catch (Exception ex) { log.debug("Error closing MCP client", ex); }
        }
    }
}
