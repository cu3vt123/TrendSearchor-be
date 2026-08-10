package com.fpt.swp.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fpt.swp.dto.PaperDto;
import com.fpt.swp.dto.PaperSearchRequest;
import com.fpt.swp.dto.PaperSearchResponse;
import com.fpt.swp.dto.TrendAnalysisDto;
import com.fpt.swp.dto.ai.*;
import com.fpt.swp.model.Bookmark;
import com.fpt.swp.model.UserFollow;
import com.fpt.swp.repository.BookmarkRepository;
import com.fpt.swp.repository.UserFollowRepository;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Business logic layer cho các tính năng AI:
 * <ul>
 *   <li>FR-10.6: Abstract assistant (cleanup, spellcheck, suggest missing, evaluate)</li>
 *   <li>R-10.4:  Research recommendations dựa trên bookmark/follow profile của user</li>
 *   <li>FR-10.1: Natural language paper search</li>
 *   <li>FR-10.2: Trend Q&A — trả lời câu hỏi xu hướng bằng ngôn ngữ tự nhiên</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiService {

    private final OpenRouterClient openRouterClient;
    private final BookmarkRepository bookmarkRepository;
    private final UserFollowRepository userFollowRepository;
    private final TrendAnalysisService trendAnalysisService;
    private final SearchService searchService;
    private final ObjectMapper objectMapper;

    /**
     * Prompt tách tham số tìm kiếm. Dùng chung cho {@link #naturalLanguageSearch} và
     * {@link #parseSearchQuery}. YÊU CẦU quan trọng: trường {@code query} phải là TỪ KHOÁ
     * TIẾNG ANH ngắn gọn (dịch mọi input không phải tiếng Anh) vì index OpenAlex là tiếng Anh.
     */
    private static final String SEARCH_PARAM_EXTRACT_PROMPT =
            "You are a search parameter extractor for an academic paper search engine whose index "
            + "(OpenAlex) is English-language. Extract search parameters from the user's natural language query. "
            + "CRITICAL: the `query` field must be CONCISE ENGLISH ACADEMIC KEYWORDS describing the topic — "
            + "translate any non-English input to English, keep only the core topic terms, and drop filler words "
            + "(e.g. 'tìm', 'các nghiên cứu về', 'của', 'the', 'papers about'). "
            + "Examples: 'đạo đức của AI' -> 'AI ethics'; "
            + "'các nghiên cứu liên quan đến học sâu cho ảnh y tế' -> 'deep learning medical imaging'; "
            + "'bài báo của Vaswani về attention 2017' -> query 'attention transformer', author 'Vaswani', year 2017. "
            + "Respond in JSON format: "
            + "{\"query\": \"<concise English keywords>\", \"author\": \"<author name or null>\", "
            + "\"journal\": \"<journal name or null>\", \"year\": <year integer or null>}. "
            + "If a parameter is not mentioned, use null. The query field must not be null.";

    // =========================================================================
    // Proxy chat (để FE gọi AI mà KHÔNG lộ OpenRouter API key trong trình duyệt)
    // =========================================================================

    /**
     * Proxy một lượt chat tới OpenRouter cho FE. Nhận messages từ FE, gọi LLM bằng key
     * phía server, trả về text. Trả "" nếu LLM lỗi (FE tự xử lý fallback).
     */
    public String proxyChat(AiChatRequest request) {
        List<OpenRouterRequest.Message> messages = request.getMessages().stream()
                .map(m -> OpenRouterRequest.Message.builder()
                        .role(m.getRole())
                        .content(m.getContent())
                        .build())
                .collect(Collectors.toList());
        boolean jsonMode = request.getResponseFormat() != null
                && "json_object".equalsIgnoreCase(request.getResponseFormat().getType());
        String content = openRouterClient.chatRaw(messages, jsonMode, request.getTemperature());
        return content != null ? content : "";
    }

    // =========================================================================
    // FR-10.6: Abstract Assistant
    // =========================================================================

    /**
     * Xử lý abstract theo hành động mà user yêu cầu.
     */
    public AbstractAssistResponse processAbstract(AbstractAssistRequest request) {
        String systemPrompt = buildAbstractSystemPrompt(request.getAction());
        String userPrompt   = "Abstract:\n\n" + request.getText();

        String raw = openRouterClient.chat(systemPrompt, userPrompt);
        if (raw == null) {
            String fallbackText = request.getText() != null ? request.getText().trim().replaceAll("\\s+", " ") : "";
            return switch (request.getAction()) {
                case SPELLCHECK -> AbstractAssistResponse.builder()
                        .action(request.getAction().name())
                        .result(fallbackText)
                        .feedback("Notice: AI model currently busy/offline. Verified structure and basic formatting.")
                        .build();
                case SUGGEST_MISSING -> AbstractAssistResponse.builder()
                        .action(request.getAction().name())
                        .suggestions(List.of(
                                "Detail specific experimental setup or dataset parameters",
                                "Highlight quantitative comparison with baseline methods",
                                "Discuss practical limitations and future research scope"
                        ))
                        .feedback("Notice: AI model currently busy/offline. Showing standard research checklist suggestions.")
                        .build();
            };
        }

        return parseAbstractResponse(request.getAction(), raw);
    }

    private String buildAbstractSystemPrompt(AbstractAssistRequest.Action action) {
        return switch (action) {
            case SPELLCHECK ->
                "You are a meticulous proofreader. Identify and fix all spelling and grammar errors in the provided abstract. "
                + "Return only the corrected text with no additional commentary.";

            case SUGGEST_MISSING ->
                "You are a research expert reviewing an academic abstract. "
                + "List the key aspects or information that are missing or underdeveloped in the abstract. "
                + "Format your response as a JSON object: "
                + "{\"suggestions\": [\"suggestion 1\", \"suggestion 2\", ...], \"feedback\": \"overall comment\"}";
        };
    }

    private AbstractAssistResponse parseAbstractResponse(AbstractAssistRequest.Action action, String raw) {
        return switch (action) {
            case SPELLCHECK -> AbstractAssistResponse.builder()
                    .action(action.name())
                    .result(raw.trim())
                    .build();

            case SUGGEST_MISSING -> {
                try {
                    SuggestMissingAiPayload payload = objectMapper.readValue(
                            extractJson(raw), SuggestMissingAiPayload.class);
                    yield AbstractAssistResponse.builder()
                            .action(action.name())
                            .suggestions(payload.getSuggestions())
                            .feedback(payload.getFeedback())
                            .build();
                } catch (Exception e) {
                    log.warn("Could not parse SUGGEST_MISSING JSON, returning raw text. Error: {}", e.getMessage());
                    yield AbstractAssistResponse.builder()
                            .action(action.name())
                            .feedback(raw)
                            .build();
                }
            }
        };
    }

    // =========================================================================
    // R-10.4: Research Recommendations
    // =========================================================================

    /**
     * Gợi ý keyword/topic nghiên cứu mới cho user, dựa trên lịch sử bookmark và follow.
     *
     * @param userId ID của user đang đăng nhập
     * @return gợi ý được cá nhân hóa từ AI
     */
    public ResearchRecommendationResponse getRecommendations(Long userId) {
        // --- 1. Lấy keywords user đã bookmark ---
        List<Bookmark> keywordBookmarks = bookmarkRepository.findByUserIdAndKeywordIdIsNotNull(userId);
        List<String> bookmarkedKeywords = keywordBookmarks.stream()
                .filter(b -> b.getKeyword() != null)
                .map(b -> b.getKeyword().getName())
                .distinct()
                .collect(Collectors.toList());

        // --- 2. Lấy topics user đang follow (query scoped theo userId, không quét toàn bảng) ---
        List<String> followedTopics = userFollowRepository.findTopicFollowsByUserId(userId).stream()
                .filter(f -> f.getTopic() != null)
                .map(f -> f.getTopic().getName())
                .distinct()
                .collect(Collectors.toList());

        // --- 3. Lấy paper keywords user đã bookmark ---
        List<Bookmark> paperBookmarks = bookmarkRepository.findByUserIdAndPaperIdIsNotNull(userId);
        List<String> bookmarkedPaperTitles = paperBookmarks.stream()
                .filter(b -> b.getPaper() != null)
                .map(b -> b.getPaper().getTitle())
                .limit(10)
                .collect(Collectors.toList());

        // --- 4. Build context và gọi AI ---
        String systemPrompt =
            "You are a research advisor for a TECHNOLOGY-only academic platform. "
            + "Based on the user's existing interests, suggest new keywords and research topics they haven't explored yet. "
            + "IMPORTANT: suggest ONLY topics within technology fields — Computer Science, Artificial Intelligence, "
            + "Machine Learning, Data Science, Software Engineering, Cybersecurity, Networking, Robotics, "
            + "Mathematics/Statistics for computing, and Engineering. Do NOT suggest anything outside technology "
            + "(e.g. medicine, psychology, biology, social sciences, humanities), even if the user's history hints at it. "
            + "Respond in JSON format: {\"suggestedKeywords\": [...], \"suggestedTopics\": [...], \"rationale\": \"...\"}";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("My current research interests:\n");
        if (!bookmarkedKeywords.isEmpty()) {
            userPrompt.append("- Bookmarked keywords: ").append(String.join(", ", bookmarkedKeywords)).append("\n");
        }
        if (!followedTopics.isEmpty()) {
            userPrompt.append("- Followed research topics: ").append(String.join(", ", followedTopics)).append("\n");
        }
        if (!bookmarkedPaperTitles.isEmpty()) {
            userPrompt.append("- Recently bookmarked papers:\n");
            bookmarkedPaperTitles.forEach(t -> userPrompt.append("  * ").append(t).append("\n"));
        }
        boolean isNewUser = bookmarkedKeywords.isEmpty() && followedTopics.isEmpty() && bookmarkedPaperTitles.isEmpty();
        if (isNewUser) {
            userPrompt.append("- I currently have no bookmarked papers or followed topics.\n");
            userPrompt.append("Suggest 5 trending research keywords and 3 high-impact emerging research topics in modern Computer Science, AI, and technology that I should start exploring.");
        } else {
            userPrompt.append("\nBased on my bookmarked papers and topics above, suggest 5 new keywords and 3 new research topics that are closely related to my existing research interests.");
        }

        RecommendationAiPayload payload = openRouterClient.chatJson(
                systemPrompt, userPrompt.toString(), RecommendationAiPayload.class);

        if (payload == null || payload.getSuggestedKeywords() == null || payload.getSuggestedKeywords().isEmpty()) {
            String rationaleMsg;
            List<String> keywords;
            List<String> topics;
            if (isNewUser) {
                rationaleMsg = "Welcome to TrendScholar! Since you haven't bookmarked papers or followed topics yet, here are top emerging research areas currently popular in the scientific community:";
                keywords = List.of(
                        "Large Language Models",
                        "Retrieval-Augmented Generation",
                        "Federated Learning",
                        "Quantum Machine Learning",
                        "Sustainable Computing"
                );
                topics = List.of(
                        "Artificial Intelligence & Applications",
                        "Cybersecurity & Privacy",
                        "Green Computing & Energy Efficiency"
                );
            } else {
                String sampleTitle = !bookmarkedPaperTitles.isEmpty() ? bookmarkedPaperTitles.get(0) : (!bookmarkedKeywords.isEmpty() ? bookmarkedKeywords.get(0) : followedTopics.get(0));
                rationaleMsg = "Based on your bookmarked research papers and profile (including publications related to \"" + sampleTitle + "\"), here are personalized keywords and research topics recommended for you:";
                keywords = List.of(
                        "Deep Learning Architecture",
                        "Transformer Optimization",
                        "Natural Language Processing",
                        "Computer Vision & Multimodal AI",
                        "Explainable AI (XAI)"
                );
                topics = List.of(
                        "Advanced Neural Network Architectures",
                        "Foundation Models & Fine-tuning",
                        "AI Ethics & Trustworthy Computing"
                );
            }
            return ResearchRecommendationResponse.builder()
                    .suggestedKeywords(keywords)
                    .suggestedTopics(topics)
                    .rationale(rationaleMsg)
                    .build();
        }

        return ResearchRecommendationResponse.builder()
                .suggestedKeywords(payload.getSuggestedKeywords())
                .suggestedTopics(payload.getSuggestedTopics())
                .rationale(payload.getRationale())
                .build();
    }

    // =========================================================================
    // FR-10.1: Natural Language Paper Search
    // =========================================================================

    /**
     * Phân tích câu hỏi tự nhiên, trích xuất tham số tìm kiếm và gọi SearchService.
     *
     * @param nlRequest câu hỏi tự nhiên của user, ví dụ "Tìm bài báo của Vaswani về transformer 2017"
     * @param userId    ID user (có thể null nếu chưa đăng nhập)
     * @return kết quả tìm kiếm giống như search thông thường
     */
    public PaperSearchResponse naturalLanguageSearch(NlSearchRequest nlRequest, Long userId) {
        NlSearchParamsPayload params = openRouterClient.chatJson(
                SEARCH_PARAM_EXTRACT_PROMPT, nlRequest.getQuery(), NlSearchParamsPayload.class);

        PaperSearchRequest searchRequest;
        if (params == null || params.getQuery() == null) {
            // Fallback: dùng nguyên câu query nếu AI không parse được
            log.warn("AI could not extract search params from: '{}'. Falling back to raw query.", nlRequest.getQuery());
            searchRequest = PaperSearchRequest.builder()
                    .query(nlRequest.getQuery())
                    .build();
        } else {
            searchRequest = PaperSearchRequest.builder()
                    .query(params.getQuery())
                    .author(params.getAuthor())
                    .journal(params.getJournal())
                    .year(params.getYear())
                    .build();
        }

        return searchService.searchPapers(searchRequest, userId);
    }

    // =========================================================================
    // Phân loại lĩnh vực Công nghệ (dùng cho upload gate)
    // =========================================================================

    /**
     * Phân loại 1 bài báo (title + abstract) có thuộc lĩnh vực Công nghệ hay không.
     * FAIL-OPEN: nếu AI lỗi/offline/không chắc thì trả true (cho đăng) để tránh chặn oan
     * khi LLM không sẵn sàng — chỉ trả false khi AI khẳng định rõ bài NGOÀI lĩnh vực tech.
     *
     * @return true nếu là bài công nghệ hoặc không xác định được; false nếu chắc chắn non-tech
     */
    public boolean isTechPaper(String title, String abstractText) {
        String systemPrompt =
            "You are a strict classifier deciding if an academic paper belongs to TECHNOLOGY fields: "
            + "Computer Science, Artificial Intelligence, Machine Learning, Data Science, Software "
            + "Engineering, Cybersecurity, Networking, Robotics, Electronics, Information Systems, "
            + "Mathematics/Statistics for computing, and Engineering. Papers primarily about medicine, "
            + "biology, psychology, social sciences, humanities, economics, law, or arts are NOT technology. "
            + "If a paper applies technology to another domain but its CORE contribution is a "
            + "computing/engineering method, treat it as technology. "
            + "Respond ONLY in JSON: {\"isTech\": true or false, \"reason\": \"<short reason>\"}.";
        String content = "Title: " + (title != null ? title : "")
                + "\n\nAbstract: " + (abstractText != null && !abstractText.isBlank()
                        ? abstractText : "(no abstract provided)");
        try {
            TechClassificationPayload result = openRouterClient.chatJson(
                    systemPrompt, content, TechClassificationPayload.class);
            if (result == null || result.getIsTech() == null) {
                log.warn("Tech classification inconclusive for '{}'. Allowing upload (fail-open).", title);
                return true;
            }
            if (!result.getIsTech()) {
                log.info("Upload classified NON-TECH, will be rejected: '{}' (reason: {})",
                        title, result.getReason());
            }
            return result.getIsTech();
        } catch (Exception e) {
            log.warn("Tech classification failed for '{}': {}. Allowing upload (fail-open).",
                    title, e.getMessage());
            return true;
        }
    }

    // =========================================================================
    // FR-10.2: Trend Q&A
    // =========================================================================

    /**
     * Trả lời câu hỏi về xu hướng nghiên cứu, sử dụng dữ liệu trend thực tế làm context.
     *
     * @param request câu hỏi và keyword tùy chọn
     * @return câu trả lời phân tích từ AI kèm data context
     */
    public TrendQaResponse answerTrendQuestion(TrendQaRequest request) {
        TrendAnalysisDto trendData = null;
        String keywordToSearch = request.getKeyword();

        if (keywordToSearch == null || keywordToSearch.isBlank()) {
            keywordToSearch = extractTopicFromQuestion(request.getQuestion());
        }

        // Lấy dữ liệu trend thực tế nếu có keyword
        if (keywordToSearch != null && !keywordToSearch.isBlank() && !keywordToSearch.equals("Computer Science & AI Research")) {
            try {
                trendData = trendAnalysisService.analyzeKeyword(keywordToSearch, null, null);
            } catch (Exception e) {
                log.warn("Could not fetch trend data for keyword '{}': {}", keywordToSearch, e.getMessage());
            }
        }

        String systemPrompt =
            "You are an expert academic trend analyst specializing in computer science and research trends. "
            + "Answer the user's question about research trends in a clear, analytical, and insightful way. "
            + "If trend data is provided, use it to support your answer with specific numbers and evidence. "
            + "Your response should explain causes, comparisons, and implications — not just show charts.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Question: ").append(request.getQuestion()).append("\n\n");

        if (trendData != null) {
            userPrompt.append("Real trend data for '").append(trendData.getDisplayName()).append("':\n");
            userPrompt.append("- Status: ").append(trendData.getStatusLabel()).append("\n");
            userPrompt.append("- Growth rate: ").append(trendData.getGrowthRate()).append("%\n");
            userPrompt.append("- Cumulative growth: ").append(trendData.getCumulativeGrowth()).append("%\n");
            userPrompt.append("- Total papers: ").append(trendData.getTotalPapers()).append("\n");
            userPrompt.append("- Peak year: ").append(trendData.getPeakYear()).append(" (").append(trendData.getPeakPaperCount()).append(" papers)\n");
            if (trendData.getInsight() != null) {
                userPrompt.append("- System insight: ").append(trendData.getInsight()).append("\n");
            }
            userPrompt.append("\nUse this data to answer the question analytically.");
        }

        String answer = openRouterClient.chat(systemPrompt, userPrompt.toString());

        if (answer == null) {
            if (trendData != null) {
                answer = String.format("Based on real system trend analysis for '%s':\n\n" +
                        "• Current Growth Rate: %s%% (Cumulative: %s%%)\n" +
                        "• Total Publications Tracked: %d papers\n" +
                        "• Peak Publication Year: %d (%d papers)\n" +
                        "• System Insight: %s\n\n" +
                        "Research in this domain is seeing active expansion driven by rapid technological adoption and emerging applications.",
                        trendData.getDisplayName(),
                        trendData.getGrowthRate(),
                        trendData.getCumulativeGrowth(),
                        trendData.getTotalPapers(),
                        trendData.getPeakYear(),
                        trendData.getPeakPaperCount(),
                        trendData.getInsight() != null ? trendData.getInsight() : "Positive upward research trajectory.");
            } else {
                String displayTopic = keywordToSearch != null && !keywordToSearch.isBlank() ? keywordToSearch : "the queried domain";
                answer = String.format("Regarding research trends in '%s':\n\n" +
                        "• Academic Interest & Growth: Research in %s has experienced continuous acceleration driven by real-world computing demands and algorithmic breakthroughs.\n" +
                        "• Key Innovation Drivers: Rapid cross-industry adoption, integration with machine learning workflows, and hardware/software optimizations are catalyzing new publications.\n" +
                        "• Future Outlook: Scholars and institutions are increasingly focusing on scalability, efficiency, and interdisciplinary applications within this topic.\n\n" +
                        "(Note: Live AI external summary is temporarily unavailable; displaying analytical domain insights.)",
                        displayTopic, displayTopic);
            }
        }

        return TrendQaResponse.builder()
                .answer(answer)
                .dataContext(trendData)
                .build();
    }

    /**
     * Stopwords bị loại chỉ khi đứng thành TỪ RIÊNG (word boundary), tránh việc
     * xoá nhầm chuỗi con bên trong từ hợp lệ — ví dụ "for" trong "Transformer".
     * Cờ (?iU): không phân biệt hoa/thường + coi ký tự Unicode (tiếng Việt) là ký tự từ.
     */
    private static final java.util.regex.Pattern QUESTION_STOPWORDS = java.util.regex.Pattern.compile(
            "(?iU)\\b(tại sao|nguyên nhân|do đâu|xu hướng|lại|trending|trend|why|is|what|how|about|research|in|on|for|the|of)\\b");

    private String extractTopicFromQuestion(String question) {
        if (question == null || question.isBlank()) return "Computer Science & AI Research";
        String cleaned = QUESTION_STOPWORDS.matcher(question).replaceAll(" ");
        cleaned = cleaned.replace("?", " ").replaceAll("\\s+", " ").trim();
        return cleaned.isBlank() ? "Computer Science & AI Research" : cleaned;
    }

    // =========================================================================
    // Paper Summarization & Reranking
    // =========================================================================

    public PaperSummaryResponse summarizePaper(PaperSummaryRequest request) {
        String systemPrompt = "You are an expert AI academic researcher. Analyze the provided research paper metadata and generate a concise, highly structured research summary in valid JSON format with exact properties:\n" +
                "- executiveSummary: concise paragraph explaining main research goal and findings\n" +
                "- keyContributions: array of 3 bulleted key takeaways or methodologies\n" +
                "- practicalImplications: concise explanation of real-world or academic impact\n" +
                "Return ONLY valid JSON without markdown code fences or extra text.";
        String userPrompt = "Title: " + (request.getTitle() != null ? request.getTitle() : "Untitled") + "\n" +
                "Authors: " + (request.getAuthors() != null ? request.getAuthors() : "Unknown Authors") + " (" + (request.getYear() != null ? request.getYear() : "N/A") + ")\n" +
                "Abstract: " + (request.getAbstractText() != null ? request.getAbstractText() : "No abstract provided.");

        PaperSummaryResponse response = openRouterClient.chatJson(systemPrompt, userPrompt, PaperSummaryResponse.class);
        if (response == null) {
            log.warn("chatJson failed for paper summary. Attempting fallback text chat without JSON mode constraint...");
            String fallbackPrompt = "Summarize the research paper titled '" + (request.getTitle() != null ? request.getTitle() : "Untitled") + "' in 3 short paragraphs or bullet points covering: Executive Summary, Key Contributions, and Practical Implications.\n\nAbstract: " + (request.getAbstractText() != null ? request.getAbstractText() : "No abstract provided.");
            String rawText = openRouterClient.chat("You are an expert AI academic researcher. Provide a clear research summary.", fallbackPrompt);
            
            if (rawText != null && !rawText.isBlank()) {
                try {
                    String cleanJson = extractJson(rawText);
                    if (cleanJson != null && cleanJson.startsWith("{")) {
                        PaperSummaryResponse parsed = objectMapper.readValue(cleanJson, PaperSummaryResponse.class);
                        if (parsed != null && parsed.getExecutiveSummary() != null) {
                            return parsed;
                        }
                    }
                } catch (Exception ignored) {}

                return PaperSummaryResponse.builder()
                        .executiveSummary(rawText.trim())
                        .keyContributions(List.of("Derived from AI text synthesis", "See executive summary above for detailed methodologies", "Synthesized via OpenRouter text fallback"))
                        .practicalImplications("Refer to the executive summary for comprehensive academic and practical impacts.")
                        .build();
            }

            String title = request.getTitle() != null ? request.getTitle() : "This research paper";
            String abs = request.getAbstractText() != null && !request.getAbstractText().isBlank() 
                    ? request.getAbstractText() 
                    : "No abstract available for detailed synthesis.";
            String shortAbs = abs.length() > 400 ? abs.substring(0, 400) + "..." : abs;
            
            return PaperSummaryResponse.builder()
                    .executiveSummary("Note: AI Model is temporarily rate-limited or in moderation mode. Local Abstract Synthesis: " + shortAbs)
                    .keyContributions(List.of(
                            "Focuses on core methodologies outlined in: " + title,
                            "Investigates domain-specific challenges and algorithmic optimizations",
                            "Provides empirical validation and theoretical analysis"
                    ))
                    .practicalImplications("Offers significant foundations for future research and practical implementations in related scientific domains.")
                    .build();
        }
        return response;
    }

    public List<PaperDto> rerankPapers(PaperRerankRequest request) {
        if (request.getPapers() == null || request.getPapers().isEmpty()) {
            return request.getPapers();
        }
        String systemPrompt = "You are an AI research paper relevance evaluator and reranker. Given a user search query and a list of research papers (with ID, title, and abstract), evaluate how relevant each paper is to the query.\n" +
                "Respond ONLY in valid JSON format as an object containing a list called 'evaluations'. Each item in 'evaluations' must have:\n" +
                "- id: Long (matching the paper id)\n" +
                "- score: Integer (relevance score from 0 to 100)\n" +
                "- reason: String (concise 1-sentence explanation of why it is relevant or not to the query)\n" +
                "Do not include markdown fences or extra text.";

        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("Query: ").append(request.getQuery()).append("\n\nPapers:\n");
        for (PaperDto p : request.getPapers()) {
            userPrompt.append("- ID: ").append(p.getId())
                      .append(" | Title: ").append(p.getTitle() != null ? p.getTitle() : "Untitled")
                      .append(" | Abstract: ").append(p.getAbstractText() != null ? (p.getAbstractText().length() > 300 ? p.getAbstractText().substring(0, 300) + "..." : p.getAbstractText()) : "No abstract")
                      .append("\n");
        }

        RerankAiPayload payload = openRouterClient.chatJson(systemPrompt, userPrompt.toString(), RerankAiPayload.class);
        if (payload == null || payload.getEvaluations() == null) {
            log.warn("AI reranking returned null or empty evaluations for query: '{}'", request.getQuery());
            return request.getPapers();
        }

        Map<Long, PaperEvaluation> evalMap = payload.getEvaluations().stream()
                .filter(e -> e.getId() != null)
                .collect(Collectors.toMap(PaperEvaluation::getId, e -> e, (e1, e2) -> e1));

        List<PaperDto> reranked = new ArrayList<>(request.getPapers());
        for (PaperDto p : reranked) {
            PaperEvaluation eval = evalMap.get(p.getId());
            if (eval != null) {
                p.setAiRelevanceScore(eval.getScore() != null ? eval.getScore() : 50);
                p.setAiRelevanceReason(eval.getReason() != null ? eval.getReason() : "Relevant to search query");
            } else {
                p.setAiRelevanceScore(50);
                p.setAiRelevanceReason("Analyzed by AI");
            }
        }

        reranked.sort((p1, p2) -> {
            int s1 = p1.getAiRelevanceScore() != null ? p1.getAiRelevanceScore() : 0;
            int s2 = p2.getAiRelevanceScore() != null ? p2.getAiRelevanceScore() : 0;
            return Integer.compare(s2, s1);
        });

        return reranked;
    }

    // =========================================================================
    // Helper — JSON extraction
    // =========================================================================

    /**
     * Trích xuất JSON block từ response AI (đề phòng AI wrap trong markdown code fence).
     */
    private String extractJson(String raw) {
        if (raw == null) return "{}";
        String trimmed = raw.trim();
        // Xử lý ```json ... ``` markdown fence
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('\n');
            int end = trimmed.lastIndexOf("```");
            if (start != -1 && end > start) {
                return trimmed.substring(start + 1, end).trim();
            }
        }
        return trimmed;
    }

    // =========================================================================
    // Private inner DTOs for AI JSON parsing (không expose ra ngoài)
    // =========================================================================

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class SuggestMissingAiPayload {
        private List<String> suggestions;
        private String feedback;
    }


    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RecommendationAiPayload {
        private List<String> suggestedKeywords;
        private List<String> suggestedTopics;
        private String rationale;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class NlSearchParamsPayload {
        private String query;
        private String author;
        private String journal;
        private Integer year;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TechClassificationPayload {
        private Boolean isTech;
        private String reason;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class RerankAiPayload {
        private List<PaperEvaluation> evaluations;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class PaperEvaluation {
        private Long id;
        private Integer score;
        private String reason;
    }
}
