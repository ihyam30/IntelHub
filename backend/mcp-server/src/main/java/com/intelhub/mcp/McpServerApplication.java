package com.intelhub.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@SpringBootApplication
public class McpServerApplication {
    public static void main(String[] args) { SpringApplication.run(McpServerApplication.class, args); }

    @Configuration
    static class McpConfiguration {
        @Bean
        HttpServletStreamableServerTransportProvider transportProvider() {
            return HttpServletStreamableServerTransportProvider.builder().build();
        }

        @Bean
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(HttpServletStreamableServerTransportProvider provider) {
            return new ServletRegistrationBean<>(provider, "/mcp");
        }

        @Bean
        McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider provider, List<McpServerFeatures.SyncToolSpecification> tools) {
            return McpServer.sync(provider).serverInfo("intelhub-demo-tools", "0.1.0").tools(tools).build();
        }
    }
}
