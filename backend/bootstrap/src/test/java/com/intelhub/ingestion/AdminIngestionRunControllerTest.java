package com.intelhub.ingestion;

import com.intelhub.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class AdminIngestionRunControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final AdminIngestionRunController controller = new AdminIngestionRunController(jdbc, currentUser);

    @Test
    void rejectsNonAdminBeforeReadingTasks() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "需要管理员权限"))
                .when(currentUser).requireAdmin();

        assertThatThrownBy(() -> controller.list(null, "", 100))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("需要管理员权限");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsUnknownStatusBeforeQueryingDatabase() {
        assertThatThrownBy(() -> controller.list("UNKNOWN", "", 100))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("无效的任务状态");
        verify(currentUser).requireAdmin();
        verifyNoInteractions(jdbc);
    }
}
