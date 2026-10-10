package com.intelhub.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class QueryTermMappingServiceTest {
    private final QueryTermMappingService service = new QueryTermMappingService(mock(JdbcTemplate.class), new ObjectMapper());

    @Test
    void addsConfiguredSynonymsForMatchingSourceTerm() {
        String expanded = service.expandRules("VPN 连不上", List.of(
                new QueryTermMappingService.MappingRule("VPN", List.of("虚拟专用网络", "远程接入"))));

        assertThat(expanded).contains("VPN 连不上", "虚拟专用网络", "远程接入");
    }

    @Test
    void leavesUnmatchedQuestionsUntouched() {
        assertThat(service.expandRules("报销流程是什么", List.of(
                new QueryTermMappingService.MappingRule("VPN", List.of("虚拟专用网络")))))
                .isEqualTo("报销流程是什么");
    }

    @Test
    void capsExpansionToEightTermsAndSkipsTermsAlreadyInQuestion() {
        List<String> targets = java.util.stream.IntStream.range(0, 12).mapToObj(index -> "term" + index).toList();
        String expanded = service.expandRules("source term0", List.of(new QueryTermMappingService.MappingRule("source", targets)));

        assertThat(expanded).contains("term8").doesNotContain("term9");
        assertThat(expanded.split(" ")).hasSize(10);
    }
}
