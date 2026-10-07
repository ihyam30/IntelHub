package com.intelhub.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class DemoTools {
    @Bean
    McpServerFeatures.SyncToolSpecification ticketSummary() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("region", Map.of("type", "string", "description", "可选地区筛选"));
        Tool tool = Tool.builder().name("ticket_summary").description("查询演示工单系统的待处理、处理中和已解决工单汇总").inputSchema(new JsonSchema("object", properties, List.of(), null, null, null)).build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> ticketResult(request));
    }

    @Bean
    McpServerFeatures.SyncToolSpecification policyLookup() {
        Map<String, Object> properties = Map.of("topic", Map.of("type", "string", "description", "制度主题，例如报销、账号或数据安全"));
        Tool tool = Tool.builder().name("policy_lookup").description("从演示制度目录查询主题说明，适用于非知识库文档中的流程问答").inputSchema(new JsonSchema("object", properties, List.of("topic"), null, null, null)).build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> policyResult(request));
    }

    private CallToolResult ticketResult(CallToolRequest request) {
        String region = request.arguments() == null ? null : String.valueOf(request.arguments().getOrDefault("region", "全国"));
        String text = "演示工单汇总（" + region + "，" + LocalDate.now() + "）：待处理 12 单，处理中 8 单，已解决 36 单。此数据为 MCP 示例工具返回的模拟数据。";
        return CallToolResult.builder().content(List.of(new TextContent(text))).isError(false).build();
    }

    private CallToolResult policyResult(CallToolRequest request) {
        String topic = request.arguments() == null ? "" : String.valueOf(request.arguments().getOrDefault("topic", ""));
        String text = "演示制度目录未连接企业数据库。主题「" + topic + "」的真实制度内容请以已入库文档为准；此工具仅用于演示 MCP 工具发现、参数传递和结果融合。";
        return CallToolResult.builder().content(List.of(new TextContent(text))).isError(false).build();
    }
}
