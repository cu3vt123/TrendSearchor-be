package com.fpt.swp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.swp.dto.ai.OpenRouterRequest;
import com.fpt.swp.dto.ai.OpenRouterResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Low-level client để giao tiếp với OpenRouter Chat Completions API.
 * Cung cấp 2 method:
 * - {@link #chat} — trả về text thuần
 * - {@link #chatJson} — trả về object được parse từ JSON response
 */
@Service
@Slf4j
public class OpenRouterClient {

    /**
     * Đánh dấu (theo từng request/thread) rằng có ít nhất một lần gọi LLM trả về
     * nội dung hợp lệ. Dùng để chỉ trừ quota khi LLM thực sự chạy, không trừ khi
     * rơi vào fallback do model lỗi/offline. Reset ở đầu mỗi request AI.
     */
    private static final ThreadLocal<Boolean> LLM_SUCCEEDED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public static void resetCallFlag() { LLM_SUCCEEDED.set(Boolean.FALSE); }
    public static boolean wasLlmCallSuccessful() { return Boolean.TRUE.equals(LLM_SUCCEEDED.get()); }

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String model;

    public OpenRouterClient(
            @Qualifier("openRouterRestTemplate") RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${app.openrouter.base-url}") String baseUrl,
            @Value("${app.openrouter.model}") String model) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.model = model;
    }

    /**
     * Gửi một lượt chat và nhận về text thuần.
     *
     * @param systemPrompt  hướng dẫn hành vi của AI
     * @param userPrompt    nội dung câu hỏi/yêu cầu của người dùng
     * @return nội dung text từ AI, hoặc null nếu có lỗi
     */
    public String chat(String systemPrompt, String userPrompt) {
        OpenRouterRequest request = OpenRouterRequest.builder()
                .model(model)
                .messages(List.of(
                        OpenRouterRequest.Message.builder().role("system").content(systemPrompt).build(),
                        OpenRouterRequest.Message.builder().role("user").content(userPrompt).build()
                ))
                .build();

        return doCall(request);
    }

    /**
     * Gửi một lượt chat với chế độ JSON output và parse kết quả thành object.
     * Model được yêu cầu trả về JSON thuần theo schema.
     *
     * @param systemPrompt   hướng dẫn hành vi (phải nói rõ "respond in JSON")
     * @param userPrompt     nội dung câu hỏi/yêu cầu
     * @param responseType   class của object cần parse
     * @return object đã parse, hoặc null nếu có lỗi
     */
    public <T> T chatJson(String systemPrompt, String userPrompt, Class<T> responseType) {
        OpenRouterRequest request = OpenRouterRequest.builder()
                .model(model)
                .messages(List.of(
                        OpenRouterRequest.Message.builder().role("system").content(systemPrompt).build(),
                        OpenRouterRequest.Message.builder().role("user").content(userPrompt).build()
                ))
                .response_format(OpenRouterRequest.ResponseFormat.builder().type("json_object").build())
                .build();

        String rawContent = doCall(request);
        if (rawContent == null) return null;

        try {
            String jsonContent = rawContent.trim();
            int startIndex = jsonContent.indexOf("{");
            int endIndex = jsonContent.lastIndexOf("}");
            
            if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
                jsonContent = jsonContent.substring(startIndex, endIndex + 1);
            }

            return objectMapper.readValue(jsonContent, responseType);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse OpenRouter JSON response into {}: {}", responseType.getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Gọi chat với danh sách messages tùy ý (dùng cho proxy {@code /api/ai/chat} — để FE
     * gọi AI mà không lộ API key). Trả về text thuần từ AI, hoặc null nếu lỗi.
     *
     * @param messages danh sách message (role + content) do FE gửi lên
     * @param jsonMode true nếu cần OpenRouter trả JSON thuần (response_format = json_object)
     */
    public String chatRaw(List<OpenRouterRequest.Message> messages, boolean jsonMode, Double temperature) {
        OpenRouterRequest.OpenRouterRequestBuilder builder = OpenRouterRequest.builder()
                .model(model)
                .messages(messages)
                .temperature(temperature); // null -> OpenRouter dùng mặc định; 0 -> tất định
        if (jsonMode) {
            builder.response_format(OpenRouterRequest.ResponseFormat.builder().type("json_object").build());
        }
        return doCall(builder.build());
    }

    // -------------------------------------------------------------------------

    private String doCall(OpenRouterRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<OpenRouterRequest> entity = new HttpEntity<>(request, headers);

        try {
            ResponseEntity<OpenRouterResponse> response = restTemplate.exchange(
                    baseUrl + "/chat/completions",
                    HttpMethod.POST,
                    entity,
                    OpenRouterResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String content = response.getBody().getFirstContent();
                log.debug("OpenRouter response (model={}): {} chars", model,
                        content != null ? content.length() : 0);
                if (content != null && !content.isBlank()) {
                    LLM_SUCCEEDED.set(Boolean.TRUE);
                }
                return content;
            }

            log.warn("OpenRouter returned non-2xx status: {}", response.getStatusCode());
            return null;

        } catch (RestClientException e) {
            log.error("OpenRouter API call failed: {}", e.getMessage());
            return null;
        }
    }
}
