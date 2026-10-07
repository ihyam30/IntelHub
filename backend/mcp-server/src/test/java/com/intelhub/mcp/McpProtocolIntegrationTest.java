package com.intelhub.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpProtocolIntegrationTest {
    @LocalServerPort
    private int port;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port + "/mcp").build();
        client = McpClient.sync(transport)
                .clientInfo(new Implementation("intelhub-mcp-integration-test", "0.1.0"))
                .build();
        client.initialize();
    }

    @AfterEach
    void close() {
        if (client != null) client.close();
    }

    @Test
    void discoversBothDemoToolsAndCallsThemThroughStreamableHttp() {
        List<String> names = client.listTools().tools().stream().map(tool -> tool.name()).toList();
        assertThat(names).containsExactlyInAnyOrder("ticket_summary", "policy_lookup");

        var ticket = client.callTool(new CallToolRequest("ticket_summary", Map.of("region", "华东")));
        assertThat(ticket.isError()).isFalse();
        assertThat(text(ticket)).contains("演示工单汇总（华东").contains("待处理 12 单");

        var policy = client.callTool(new CallToolRequest("policy_lookup", Map.of("topic", "报销")));
        assertThat(policy.isError()).isFalse();
        assertThat(text(policy)).contains("主题「报销」").contains("结果融合");
    }

    private String text(io.modelcontextprotocol.spec.McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce("", String::concat);
    }
}
