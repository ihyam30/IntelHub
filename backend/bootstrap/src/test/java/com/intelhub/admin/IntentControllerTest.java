package com.intelhub.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final IntentController controller = new IntentController(jdbc, currentUser, new ObjectMapper());

    @Test
    void intentManagementRejectsNonAdminBeforeReadingDatabase() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "需要管理员权限"))
                .when(currentUser).requireAdmin();

        assertThatThrownBy(controller::list).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void nonAdminCannotCreateIntentKnowledgeBaseMappings() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "需要管理员权限"))
                .when(currentUser).requireAdmin();

        assertThatThrownBy(() -> controller.create(new IntentController.IntentRequest(
                "人事制度", "员工制度", List.of("请假"), "", true, null, List.of(UUID.randomUUID()))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("需要管理员权限");
        verifyNoInteractions(jdbc);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rootWithChildrenCannotBeMovedUnderAnotherRoot() {
        UUID rootId = UUID.randomUUID();
        UUID targetParentId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("count(*) FROM intent_node WHERE parent_id=?"), eq(Integer.class), eq(rootId))).thenReturn(1);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(targetParentId))).thenReturn(Collections.singletonList(null));

        assertThatThrownBy(() -> controller.update(rootId, new IntentController.IntentRequest(
                "Root", "", List.of("root"), "", true, targetParentId)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("不能移动到子级");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void createPersistsIntentKnowledgeBaseMappings() throws Exception {
        UUID knowledgeBaseId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("FROM knowledge_base WHERE id IN"), eq(Integer.class), any(Object[].class))).thenReturn(1);

        IntentController.IntentView created = controller.create(new IntentController.IntentRequest(
                "人事制度", "员工相关制度", List.of("请假"), "解释适用条件", true, null, List.of(knowledgeBaseId))).data();

        org.assertj.core.api.Assertions.assertThat(created.knowledgeBaseIds()).containsExactly(knowledgeBaseId);
        verify(jdbc).update(contains("INSERT INTO intent_knowledge_base"), any(Object[].class));
    }

    @Test
    void mappingRejectsKnowledgeBasesThatDoNotExist() {
        UUID knowledgeBaseId = UUID.randomUUID();
        when(jdbc.queryForObject(contains("FROM knowledge_base WHERE id IN"), eq(Integer.class), any(Object[].class))).thenReturn(0);

        assertThatThrownBy(() -> controller.create(new IntentController.IntentRequest(
                "人事制度", "员工相关制度", List.of("请假"), "", true, null, List.of(knowledgeBaseId))))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("定向知识库不存在");
        verify(jdbc, never()).update(contains("INSERT INTO intent_node"), any(Object[].class));
    }
}
