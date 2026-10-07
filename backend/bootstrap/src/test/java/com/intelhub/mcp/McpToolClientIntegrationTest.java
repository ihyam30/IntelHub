package com.intelhub.mcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpToolClientIntegrationTest {
    private ConfigurableApplicationContext serverContext;
    private McpToolClient client;

    @BeforeAll
    void startMcpServer() {
        serverContext = new SpringApplicationBuilder(McpTestServerConfiguration.class)
                .properties("server.port=0", "spring.config.name=intelhub-mcp-integration-test")
                .run();
        int port = ((WebServerApplicationContext) serverContext).getWebServer().getPort();
        client = new McpToolClient("http://127.0.0.1:" + port + "/mcp");
    }

    @AfterAll
    void stopMcpServer() {
        if (client != null) client.close();
        if (serverContext != null) serverContext.close();
    }

    @Test
    void discoversToolsAndRoutesQuestionsToLiveMcpServer() {
        assertThat(client.availableTools()).containsExactlyInAnyOrder("ticket_summary", "policy_lookup");
        assertThat(client.invokeForQuestion("请查询华东工单当前情况"))
                .contains("演示工单汇总（全国").contains("待处理 12 单");
        assertThat(client.invokeForQuestion("请查询制度目录中的报销流程"))
                .contains("主题「请查询制度目录中的报销流程」").contains("结果融合");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({McpServerApplication.McpConfiguration.class, DemoTools.class})
    static class McpTestServerConfiguration {}
}
