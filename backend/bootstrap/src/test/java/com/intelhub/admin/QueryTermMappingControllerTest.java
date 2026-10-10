package com.intelhub.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intelhub.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class QueryTermMappingControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final QueryTermMappingController controller = new QueryTermMappingController(jdbc, currentUser, new ObjectMapper());

    @Test
    void mappingManagementRejectsNonAdminBeforeReadingDatabase() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "需要管理员权限"))
                .when(currentUser).requireAdmin();

        assertThatThrownBy(controller::list).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(jdbc);
    }
}
