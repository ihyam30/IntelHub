package com.intelhub.rag;

import com.intelhub.common.CurrentUser;
import com.intelhub.framework.web.ApiResult;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/conversations/messages")
public class MessageFeedbackController {
    private final JdbcTemplate jdbc;
    private final CurrentUser currentUser;

    public MessageFeedbackController(JdbcTemplate jdbc, CurrentUser currentUser) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
    }

    @PostMapping("/{messageId}/feedback")
    @Transactional
    public ApiResult<FeedbackView> submit(@PathVariable UUID messageId, @RequestBody FeedbackRequest request) {
        UUID userId = currentUser.id();
        Integer vote = request == null ? null : request.vote();
        if (vote != null && vote != 1 && vote != -1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈值必须为 1、-1 或 null");
        }

        var ownedAssistantMessages = jdbc.query("SELECT m.id FROM conversation_message m " +
                        "JOIN conversation c ON c.id=m.conversation_id " +
                        "WHERE m.id=? AND m.role='assistant' AND lower(COALESCE(m.model_id,'')) <> 'clarification' " +
                        "AND c.owner_id=? FOR KEY SHARE OF m,c",
                (rs, row) -> rs.getObject("id", UUID.class), messageId, userId);
        if (ownedAssistantMessages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "助手消息不存在或无权评价");
        }

        if (vote == null) {
            jdbc.update("DELETE FROM message_feedback WHERE message_id=? AND user_id=?", messageId, userId);
        } else {
            jdbc.update("INSERT INTO message_feedback(id,message_id,user_id,vote) VALUES (?,?,?,?) " +
                            "ON CONFLICT(message_id,user_id) DO UPDATE SET vote=EXCLUDED.vote,updated_at=now()",
                    UUID.randomUUID(), messageId, userId, vote);
        }
        return ApiResult.ok(new FeedbackView(vote));
    }

    public record FeedbackRequest(Integer vote) {}
    public record FeedbackView(Integer vote) {}
}
