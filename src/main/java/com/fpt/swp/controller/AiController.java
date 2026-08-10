package com.fpt.swp.controller;

import com.fpt.swp.dto.PaperDto;
import com.fpt.swp.dto.PaperSearchResponse;
import com.fpt.swp.dto.QuotaStatusDto;
import com.fpt.swp.dto.ai.*;
import java.util.List;
import com.fpt.swp.exception.RateLimitExceededException;
import com.fpt.swp.service.AiQuotaService;
import com.fpt.swp.service.AiRateLimiter;
import com.fpt.swp.service.AiService;
import com.fpt.swp.service.OpenRouterClient;
import com.fpt.swp.util.AuthUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

/**
 * REST Controller cho các tính năng AI. Mọi endpoint đều cần đăng nhập và chịu:
 *   1. rate limit chống burst (AiRateLimiter, 20/phút),
 *   2. hạn mức theo tier (AiQuotaService — FREE 3/24h, PRO 50/24h; ADMIN không giới hạn).
 * Quota chỉ bị trừ khi LLM thực sự trả kết quả (không trừ khi rơi vào fallback).
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final AuthUtils authUtils;
    private final AiRateLimiter aiRateLimiter;
    private final AiQuotaService aiQuotaService;

    // ─── Feature codes (khớp cột ai_usage_log.feature) ─────────────────────────
    private static final String F_SEARCH = "SEARCH";
    private static final String F_TREND_QA = "TREND_QA";
    private static final String F_SUMMARIZE = "SUMMARIZE";
    private static final String F_RERANK = "RERANK";
    private static final String F_ABSTRACT = "ABSTRACT";
    private static final String F_RECOMMENDATIONS = "RECOMMENDATIONS";

    private boolean isUnlimited(UserDetails userDetails) {
        return userDetails != null && userDetails.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /**
     * Chạy trước mỗi lượt gọi AI: rate-limit + quota + reset cờ LLM. Trả userId.
     */
    private Long beforeAiCall(UserDetails userDetails, HttpServletRequest httpRequest) {
        Long userId = authUtils.extractUserId(userDetails);
        String key = userId != null ? "user:" + userId
                : "ip:" + com.fpt.swp.util.RequestUtils.clientIp(httpRequest);
        if (!aiRateLimiter.tryAcquire(key)) {
            throw new RateLimitExceededException(
                    "Too many AI requests. Please wait a moment before trying again.");
        }
        if (userId != null && !isUnlimited(userDetails)) {
            aiQuotaService.checkQuota(userId);
        }
        OpenRouterClient.resetCallFlag();
        return userId;
    }

    /**
     * Chạy sau khi gọi AI: trừ 1 lượt quota nếu LLM thực sự chạy (không trừ ADMIN / fallback).
     */
    private void afterAiCall(UserDetails userDetails, Long userId, String feature) {
        if (userId != null && !isUnlimited(userDetails) && OpenRouterClient.wasLlmCallSuccessful()) {
            aiQuotaService.recordUsage(userId, feature);
        }
    }

    // ─── Proxy chat (FE gọi AI mà không lộ OpenRouter key) ──────────────────────

    /**
     * Proxy tới OpenRouter để FE gọi AI mà KHÔNG cần nhúng API key vào bundle trình duyệt.
     * Chỉ chịu rate-limit chống burst; KHÔNG trừ quota ngày vì một lượt tương tác chatbot
     * có thể gọi nhiều lần (trừ mỗi lần sẽ hết quota ngay). Vẫn yêu cầu đăng nhập.
     */
    @PostMapping("/chat")
    public ResponseEntity<java.util.Map<String, String>> aiChat(
            @Valid @RequestBody AiChatRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {
        Long userId = authUtils.extractUserId(userDetails);
        String key = userId != null ? "user:" + userId
                : "ip:" + com.fpt.swp.util.RequestUtils.clientIp(httpRequest);
        if (!aiRateLimiter.tryAcquire(key)) {
            throw new RateLimitExceededException(
                    "Too many AI requests. Please wait a moment before trying again.");
        }
        String content = aiService.proxyChat(request);
        return ResponseEntity.ok(java.util.Map.of("content", content));
    }

    // ─── Quota status ──────────────────────────────────────────────────────────

    /** FE gọi để hiển thị "còn X/limit lượt hôm nay". */
    @GetMapping("/quota")
    public ResponseEntity<QuotaStatusDto> getQuota(@AuthenticationPrincipal UserDetails userDetails) {
        Long userId = authUtils.extractUserId(userDetails);
        if (userId == null) return ResponseEntity.status(401).build();
        if (isUnlimited(userDetails)) {
            return ResponseEntity.ok(QuotaStatusDto.builder()
                    .tier("ADMIN").dailyLimit(-1).used(0).remaining(-1).unlimited(true).build());
        }
        return ResponseEntity.ok(aiQuotaService.getQuotaStatus(userId));
    }

    // ─── FR-10.6: Abstract Assistant ───────────────────────────────────────────

    @PostMapping("/abstract")
    public ResponseEntity<AbstractAssistResponse> processAbstract(
            @Valid @RequestBody AbstractAssistRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {
        Long userId = beforeAiCall(userDetails, httpRequest);
        AbstractAssistResponse response = aiService.processAbstract(request);
        afterAiCall(userDetails, userId, F_ABSTRACT);
        return ResponseEntity.ok(response);
    }

    // ─── R-10.4: Research Recommendations ──────────────────────────────────────

    @GetMapping("/recommendations")
    public ResponseEntity<ResearchRecommendationResponse> getRecommendations(
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {
        Long userId = beforeAiCall(userDetails, httpRequest);
        ResearchRecommendationResponse response = aiService.getRecommendations(userId);
        afterAiCall(userDetails, userId, F_RECOMMENDATIONS);
        return ResponseEntity.ok(response);
    }

    // ─── FR-10.1: Natural Language Search ──────────────────────────────────────

    @PostMapping("/search")
    public ResponseEntity<PaperSearchResponse> naturalLanguageSearch(
            @Valid @RequestBody NlSearchRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {
        Long userId = beforeAiCall(userDetails, httpRequest);
        PaperSearchResponse response = aiService.naturalLanguageSearch(request, userId);
        afterAiCall(userDetails, userId, F_SEARCH);
        return ResponseEntity.ok(response);
    }

    // ─── FR-10.2: Trend Q&A ────────────────────────────────────────────────────

    @PostMapping("/trend-qa")
    public ResponseEntity<TrendQaResponse> answerTrendQuestion(
            @Valid @RequestBody TrendQaRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {
        Long userId = beforeAiCall(userDetails, httpRequest);
        TrendQaResponse response = aiService.answerTrendQuestion(request);
        afterAiCall(userDetails, userId, F_TREND_QA);
        return ResponseEntity.ok(response);
    }

    // ─── Paper Summarize ───────────────────────────────────────────────────────

    @PostMapping("/summarize")
    public ResponseEntity<PaperSummaryResponse> summarizePaper(
            @Valid @RequestBody PaperSummaryRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {
        Long userId = beforeAiCall(userDetails, httpRequest);
        PaperSummaryResponse response = aiService.summarizePaper(request);
        afterAiCall(userDetails, userId, F_SUMMARIZE);
        return ResponseEntity.ok(response);
    }

    // ─── Paper Rerank ──────────────────────────────────────────────────────────

    @PostMapping("/rerank")
    public ResponseEntity<List<PaperDto>> rerankPapers(
            @Valid @RequestBody PaperRerankRequest request,
            @AuthenticationPrincipal UserDetails userDetails,
            HttpServletRequest httpRequest) {
        Long userId = beforeAiCall(userDetails, httpRequest);
        List<PaperDto> response = aiService.rerankPapers(request);
        afterAiCall(userDetails, userId, F_RERANK);
        return ResponseEntity.ok(response);
    }
}
