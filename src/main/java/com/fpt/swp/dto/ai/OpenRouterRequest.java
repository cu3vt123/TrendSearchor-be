package com.fpt.swp.dto.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request body gửi lên OpenRouter Chat Completions API.
 * Tham khảo: https://openrouter.ai/docs#chat-completion
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenRouterRequest {

    private String model;
    private List<Message> messages;
    private ResponseFormat response_format;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;   // "system" | "user" | "assistant"
        private String content;
    }

    /**
     * Khi cần AI trả về JSON thuần túy.
     * type = "json_object"
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseFormat {
        private String type;
    }
}
