package com.intelhub.admin;

import com.intelhub.ai.ChatModelRouter;
import com.intelhub.ai.EmbeddingService;
import com.intelhub.ai.RerankService;
import com.intelhub.common.CurrentUser;
import com.intelhub.mcp.McpToolClient;
import com.intelhub.rag.FairQueueLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final ChatModelRouter modelRouter = mock(ChatModelRouter.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final FairQueueLimiter limiter = mock(FairQueueLimiter.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final McpToolClient mcpToolClient = mock(McpToolClient.class);
    private final RerankService rerankService = mock(RerankService.class);
    private final AdminController controller = new AdminController(jdbc, currentUser, modelRouter, embeddingService,
            limiter, redis, mcpToolClient, rerankService);

    @Test
    void dashboardReturnsServiceAndTraceCounts() {
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(4L, 3L, 8L, 6L, 11L, 2L);

        var dashboard = controller.dashboard().data();

        assertThat(dashboard.users()).isEqualTo(4);
        assertThat(dashboard.knowledgeBases()).isEqualTo(3);
        assertThat(dashboard.documents()).isEqualTo(8);
        assertThat(dashboard.conversations()).isEqualTo(6);
        assertThat(dashboard.traces24h()).isEqualTo(11);
        assertThat(dashboard.failedDocuments()).isEqualTo(2);
        verify(currentUser).requireAdmin();
    }

    @Test
    void settingsExposeLimitsAndPersistUpdatedConcurrency() {
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("intelhub:settings:max-concurrent")).thenReturn(null);
        when(limiter.maxConcurrent()).thenReturn(4);

        var current = controller.settings().data();
        assertThat(current.maxConcurrent()).isEqualTo(4);
        assertThat(current.perUserRequestsPerMinute()).isEqualTo(20);
        assertThat(current.maxWaitSeconds()).isEqualTo(30);

        var updated = controller.updateSettings(new AdminController.UpdateSettings(8)).data();
        assertThat(updated.maxConcurrent()).isEqualTo(8);
        verify(limiter).updateMaxConcurrent(8);
    }

    @Test
    void adminEndpointsRejectNonAdminUsersBeforeReadingData() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "需要管理员权限"))
                .when(currentUser).requireAdmin();

        assertThatThrownBy(controller::dashboard).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void preventsDisablingTheCurrentAdministrator() {
        UUID currentId = UUID.fromString("8f5013e6-93a8-4291-9d64-a230f30ac4d7");
        when(currentUser.id()).thenReturn(currentId);

        assertThatThrownBy(() -> controller.setUserEnabled(currentId, new AdminController.EnabledRequest(false)))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }
}
