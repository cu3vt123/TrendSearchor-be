package com.fpt.swp.dto.ai;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request cho endpoint proxy {@code POST /api/ai/chat}.
 *
 * <p>FE gửi danh sách messages + tùy chọn để BE gọi OpenRouter hộ — nhờ đó API key
 * OpenRouter nằm hoàn toàn ở server, KHÔNG lộ trong bundle trình duyệt.
 */
@Data
@NoArgsConstructor
public class AiChatRequest {

    @NotEmpty(message = "messages must not be empty")
    private List<ChatMessage> messages;

    /** FE có thể gửi nhưng BE hiện dùng model mặc định (temperature chưa áp dụng). */
    private Double temperature;

    /** Nếu {@code {type:"json_object"}} thì yêu cầu OpenRouter trả JSON thuần. */
    private ResponseFormat responseFormat;

    @Data
    @NoArgsConstructor
    public static class ChatMessage {
        private String role;     // "system" | "user" | "assistant"
        private String content;
    }

    @Data
    @NoArgsConstructor
    public static class ResponseFormat {
        private String type;
    }
}
