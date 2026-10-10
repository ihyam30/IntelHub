package com.intelhub.rag;

import com.intelhub.common.CurrentUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageFeedbackControllerTest {
    private static final UUID USER_ID = UUID.fromString("5b9fe97b-a053-474e-8ded-176f9c2e1041");
    private static final UUID MESSAGE_ID = UUID.fromString("700e0d87-0abd-4243-9fbe-0015641fa880");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final MessageFeedbackController controller = new MessageFeedbackController(jdbc, currentUser);

    @Test
    void rejectsVoteOutsideSupportedValues() {
        when(currentUser.id()).thenReturn(USER_ID);

        assertThatThrownBy(() -> controller.submit(MESSAGE_ID, new MessageFeedbackController.FeedbackRequest(0)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("反馈值必须为 1、-1 或 null");
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsMessagesNotOwnedByTheCurrentUser() {
        when(currentUser.id()).thenReturn(USER_ID);
        stubMessageLookup(List.of());

        assertThatThrownBy(() -> controller.submit(MESSAGE_ID, new MessageFeedbackController.FeedbackRequest(1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("无权评价");
    }

    @Test
    void rejectsClarificationAssistantMessages() {
        when(currentUser.id()).thenReturn(USER_ID);
        AtomicReference<String> query = new AtomicReference<>();
        doAnswer(invocation -> {
            query.set(invocation.getArgument(0));
            return List.of();
        }).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(() -> controller.submit(MESSAGE_ID, new MessageFeedbackController.FeedbackRequest(1)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("无权评价");
        assertThat(query.get()).contains("lower(COALESCE(m.model_id,'')) <> 'clarification'");
        verifyNoInteractionsAfterLookup();
    }

    @Test
    void upsertsFeedbackForAnOwnedAssistantMessage() {
        when(currentUser.id()).thenReturn(USER_ID);
        stubMessageLookup(List.of(MESSAGE_ID));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var result = controller.submit(MESSAGE_ID, new MessageFeedbackController.FeedbackRequest(-1));

        assertThat(result.data().vote()).isEqualTo(-1);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("ON CONFLICT(message_id,user_id)"), any(Object[].class));
    }

    @Test
    void nullVoteRemovesTheUsersPreviousFeedback() {
        when(currentUser.id()).thenReturn(USER_ID);
        stubMessageLookup(List.of(MESSAGE_ID));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        var result = controller.submit(MESSAGE_ID, new MessageFeedbackController.FeedbackRequest(null));

        assertThat(result.data().vote()).isNull();
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("DELETE FROM message_feedback"), any(Object[].class));
    }

    private void stubMessageLookup(List<UUID> result) {
        doReturn(result).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    private void verifyNoInteractionsAfterLookup() {
        verify(jdbc, org.mockito.Mockito.times(1)).query(anyString(), any(RowMapper.class), any(Object[].class));
        org.mockito.Mockito.verifyNoMoreInteractions(jdbc);
    }
}
