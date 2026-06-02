package com.example.donghwanara.story;

import com.example.donghwanara.board.Board;
import com.example.donghwanara.board.BoardRepository;
import com.example.donghwanara.board.dto.BoardContentsCreateResponse;
import com.example.donghwanara.board.dto.BoardResponse;
import com.example.donghwanara.content.Content;
import com.example.donghwanara.content.ContentRepository;
import com.example.donghwanara.content.dto.ContentResponse;
import com.example.donghwanara.story.dto.StoryGenerateRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StoryGenerateService {

    private final BoardRepository boardRepository;
    private final ContentRepository contentRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public StoryGenerateService(
            BoardRepository boardRepository,
            ContentRepository contentRepository,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:gpt-5.2}") String model,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl
    ) {
        this.boardRepository = boardRepository;
        this.contentRepository = contentRepository;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Transactional
    public BoardContentsCreateResponse generate(StoryGenerateRequest request) {
        OpenAiStoryDraft draft = createDraft(request);

        Board board = boardRepository.save(new Board(
                limit(firstNonBlank(request.title(), draft.title(), autoTitle(request.prompt())), 50),
                limit(request.prompt(), 256),
                limit(firstNonBlank(draft.summary(), request.prompt()), 256),
                1
        ));

        List<Content> contents = new ArrayList<>();
        for (int i = 0; i < draft.pages().size(); i++) {
            OpenAiStoryPage page = draft.pages().get(i);
            int seq = i + 1;
            contents.add(new Content(
                    board,
                    seq,
                    "/story-scenes/scene-" + seq + ".jpg",
                    limit(page.subtitleKo(), 256),
                    limit(page.subtitleEn(), 256),
                    limit(page.subtitleJp(), 256),
                    "/audio/pending/ko/page-" + seq + ".mp3",
                    "/audio/pending/en/page-" + seq + ".mp3",
                    "/audio/pending/jp/page-" + seq + ".mp3"
            ));
        }

        List<ContentResponse> contentResponses = contentRepository.saveAll(contents)
                .stream()
                .map(ContentResponse::from)
                .toList();

        return new BoardContentsCreateResponse(BoardResponse.from(board), contentResponses);
    }

    private OpenAiStoryDraft createDraft(StoryGenerateRequest request) {
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OPENAI_API_KEY is not configured"
            );
        }

        try {
            JsonNode response = restClient.post()
                    .uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(openAiRequestBody(request))
                    .retrieve()
                    .body(JsonNode.class);

            String outputText = extractOutputText(response);
            OpenAiStoryDraft draft = objectMapper.readValue(outputText, OpenAiStoryDraft.class);
            validateDraft(draft);
            return draft;
        } catch (RestClientResponseException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI API request failed: " + ex.getResponseBodyAsString(),
                    ex
            );
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI response was not valid story JSON",
                    ex
            );
        }
    }

    private Map<String, Object> openAiRequestBody(StoryGenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("instructions", """
                You write warm, age-appropriate fairy tales for children.
                Return only JSON that matches the provided schema.
                Write exactly four pages.
                Korean text should be natural and gentle for young children.
                English and Japanese text should be faithful translations of the Korean page.
                Avoid scary, violent, or unsafe content.
                """);
        body.put("input", """
                Title hint: %s
                Hero name: %s
                Story idea: %s
                """.formatted(
                blankToDefault(request.title(), "not provided"),
                blankToDefault(request.heroName(), "not provided"),
                request.prompt()
        ));
        body.put("max_output_tokens", 1800);
        body.put("text", Map.of("format", jsonSchemaFormat()));
        return body;
    }

    private Map<String, Object> jsonSchemaFormat() {
        Map<String, Object> pageProperties = new LinkedHashMap<>();
        pageProperties.put("subtitleKo", Map.of("type", "string", "maxLength", 180));
        pageProperties.put("subtitleEn", Map.of("type", "string", "maxLength", 220));
        pageProperties.put("subtitleJp", Map.of("type", "string", "maxLength", 220));

        Map<String, Object> pageSchema = new LinkedHashMap<>();
        pageSchema.put("type", "object");
        pageSchema.put("additionalProperties", false);
        pageSchema.put("required", List.of("subtitleKo", "subtitleEn", "subtitleJp"));
        pageSchema.put("properties", pageProperties);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("title", Map.of("type", "string", "maxLength", 50));
        properties.put("summary", Map.of("type", "string", "maxLength", 160));
        properties.put("pages", Map.of(
                "type", "array",
                "minItems", 4,
                "maxItems", 4,
                "items", pageSchema
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", List.of("title", "summary", "pages"));
        schema.put("properties", properties);

        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("name", "fairy_tale_story");
        format.put("strict", true);
        format.put("schema", schema);
        return format;
    }

    private String extractOutputText(JsonNode response) {
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI response was empty");
        }
        if (response.hasNonNull("output_text")) {
            return response.get("output_text").asText();
        }

        StringBuilder outputText = new StringBuilder();
        JsonNode output = response.path("output");
        if (output.isArray()) {
            for (JsonNode outputItem : output) {
                JsonNode content = outputItem.path("content");
                if (!content.isArray()) continue;

                for (JsonNode contentItem : content) {
                    String type = contentItem.path("type").asText();
                    if ("output_text".equals(type) || contentItem.has("text")) {
                        outputText.append(contentItem.path("text").asText());
                    }
                }
            }
        }

        if (!StringUtils.hasText(outputText)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI response had no output text");
        }

        return outputText.toString();
    }

    private void validateDraft(OpenAiStoryDraft draft) {
        if (draft == null || !StringUtils.hasText(draft.title()) || !StringUtils.hasText(draft.summary())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI story draft was incomplete");
        }
        if (draft.pages() == null || draft.pages().size() != 4) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI story draft must have four pages");
        }
        for (OpenAiStoryPage page : draft.pages()) {
            if (page == null
                    || !StringUtils.hasText(page.subtitleKo())
                    || !StringUtils.hasText(page.subtitleEn())
                    || !StringUtils.hasText(page.subtitleJp())) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI story page was incomplete");
            }
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    private String blankToDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String autoTitle(String prompt) {
        String compact = prompt.trim().replaceAll("\\s+", " ");
        return compact.length() > 18 ? compact.substring(0, 18) + "..." : compact;
    }

    private String limit(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private record OpenAiStoryDraft(
            String title,
            String summary,
            List<OpenAiStoryPage> pages
    ) {
    }

    private record OpenAiStoryPage(
            String subtitleKo,
            String subtitleEn,
            String subtitleJp
    ) {
    }
}
