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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class StoryGenerateService {

    private static final Logger log = LoggerFactory.getLogger(StoryGenerateService.class);
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final BoardRepository boardRepository;
    private final ContentRepository contentRepository;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final TransactionTemplate transactionTemplate;
    private final String apiKey;
    private final String storyModel;
    private final String imageModel;
    private final String ttsModel;
    private final String ttsVoice;
    private final Path generatedMediaDir;

    public StoryGenerateService(
            BoardRepository boardRepository,
            ContentRepository contentRepository,
            ObjectMapper objectMapper,
            RestClient.Builder restClientBuilder,
            PlatformTransactionManager transactionManager,
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.model:gpt-5.2}") String storyModel,
            @Value("${openai.image-model:gpt-image-1}") String imageModel,
            @Value("${openai.tts-model:gpt-4o-mini-tts}") String ttsModel,
            @Value("${openai.tts-voice:shimmer}") String ttsVoice,
            @Value("${openai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${story.generated-media-dir:generated-media}") String generatedMediaDir
    ) {
        this.boardRepository = boardRepository;
        this.contentRepository = contentRepository;
        this.objectMapper = objectMapper;
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.apiKey = apiKey;
        this.storyModel = storyModel;
        this.imageModel = imageModel;
        this.ttsModel = ttsModel;
        this.ttsVoice = ttsVoice;
        this.generatedMediaDir = Path.of(generatedMediaDir).toAbsolutePath().normalize();
    }

    public BoardContentsCreateResponse generate(StoryGenerateRequest request) {
        log.info("Story generation started.");
        ensureApiKeyConfigured();

        OpenAiStoryDraft draft = createDraft(request);
        log.info("Story content generated. title={}, pageCount={}", draft.title().ko(), draft.pages().size());

        String storyKey = UUID.randomUUID().toString();
        Path storyDir = generatedMediaDir.resolve(storyKey);

        writeStoryJson(storyDir, draft);
        log.info("Story JSON saved. storyKey={}, path={}", storyKey, storyDir.resolve("story.json"));

        List<GeneratedPageAssets> generatedPageAssets = draft.pages()
                .stream()
                .sorted(Comparator.comparing(OpenAiStoryPage::pageNumber))
                .map(page -> generatePageAssets(storyKey, storyDir, draft.mainCharacters(), page))
                .toList();

        BoardContentsCreateResponse response = transactionTemplate.execute(
                status -> saveGeneratedStory(request, draft, generatedPageAssets)
        );
        log.info("Story generation finished. boardId={}, storyKey={}", response.board().id(), storyKey);
        return response;
    }

    private OpenAiStoryDraft createDraft(StoryGenerateRequest request) {
        try {
            log.info("OpenAI story content request started. model={}", storyModel);
            String outputText = retry("story content generation", () -> {
                JsonNode response = restClient.post()
                        .uri("/responses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(storyRequestBody(request))
                        .retrieve()
                        .body(JsonNode.class);
                return extractOutputText(response);
            });

            OpenAiStoryDraft draft = objectMapper.readValue(outputText, OpenAiStoryDraft.class);
            validateDraft(draft);
            log.info("OpenAI story content request finished.");
            return draft;
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI response was not valid story JSON",
                    ex
            );
        }
    }

    private GeneratedPageAssets generatePageAssets(
            String storyKey,
            Path storyDir,
            List<OpenAiMainCharacter> mainCharacters,
            OpenAiStoryPage page
    ) {
        log.info("Page asset generation started. pageNumber={}", page.pageNumber());
        String imagePath = generateImage(storyKey, storyDir, mainCharacters, page);
        String audioKoPath = generateSpeech(storyKey, storyDir, page, "ko");
        String audioEnPath = generateSpeech(storyKey, storyDir, page, "en");
        String audioJpPath = generateSpeech(storyKey, storyDir, page, "ja");
        log.info("Page asset generation finished. pageNumber={}", page.pageNumber());
        return new GeneratedPageAssets(page, imagePath, audioKoPath, audioEnPath, audioJpPath);
    }

    private String generateImage(
            String storyKey,
            Path storyDir,
            List<OpenAiMainCharacter> mainCharacters,
            OpenAiStoryPage page
    ) {
        int pageNumber = page.pageNumber();
        try {
            log.info("OpenAI image request started. pageNumber={}, model={}", pageNumber, imageModel);
            String b64Image = retry("image generation page " + pageNumber, () -> {
                JsonNode response = restClient.post()
                        .uri("/images/generations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(imageRequestBody(mainCharacters, page))
                        .retrieve()
                        .body(JsonNode.class);
                return extractImageBase64(response);
            });

            Path imagePath = storyDir.resolve("images").resolve("page-%02d.png".formatted(pageNumber));
            Files.createDirectories(imagePath.getParent());
            Files.write(imagePath, Base64.getDecoder().decode(b64Image));
            log.info("OpenAI image saved. pageNumber={}, path={}", pageNumber, imagePath);
            return "/generated/%s/images/%s".formatted(storyKey, imagePath.getFileName());
        } catch (Exception ex) {
            log.warn("Image generation failed. pageNumber={}", pageNumber, ex);
            return writeImageFallback(storyKey, storyDir, pageNumber);
        }
    }

    private String generateSpeech(String storyKey, Path storyDir, OpenAiStoryPage page, String languageCode) {
        int pageNumber = page.pageNumber();
        try {
            log.info(
                    "OpenAI speech request started. pageNumber={}, languageCode={}, model={}",
                    pageNumber,
                    languageCode,
                    ttsModel
            );
            byte[] audio = retry("speech generation page " + pageNumber + " language " + languageCode, () ->
                    restClient.post()
                            .uri("/audio/speech")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(speechRequestBody(page, languageCode))
                            .retrieve()
                            .body(byte[].class)
            );

            Path audioPath = storyDir.resolve("audio")
                    .resolve(languageCode)
                    .resolve("page-%02d.mp3".formatted(pageNumber));
            Files.createDirectories(audioPath.getParent());
            Files.write(audioPath, audio);
            log.info(
                    "OpenAI speech saved. pageNumber={}, languageCode={}, path={}",
                    pageNumber,
                    languageCode,
                    audioPath
            );
            return "/generated/%s/audio/%s/%s".formatted(storyKey, languageCode, audioPath.getFileName());
        } catch (Exception ex) {
            log.warn("Speech generation failed. pageNumber={}, languageCode={}", pageNumber, languageCode, ex);
            return "/generated/%s/audio/%s/page-%02d-unavailable.mp3".formatted(storyKey, languageCode, pageNumber);
        }
    }

    private BoardContentsCreateResponse saveGeneratedStory(
            StoryGenerateRequest request,
            OpenAiStoryDraft draft,
            List<GeneratedPageAssets> generatedPageAssets
    ) {
        log.info("Saving generated story to database. pageCount={}", generatedPageAssets.size());
        Board board = boardRepository.save(new Board(
                limit(firstNonBlank(draft.title().ko(), request.title(), autoTitle(request.prompt())), 50),
                limit(request.prompt(), 256),
                limit(firstNonBlank(firstPageText(draft), request.prompt()), 256),
                1
        ));

        List<Content> contents = new ArrayList<>();
        for (GeneratedPageAssets assets : generatedPageAssets) {
            OpenAiStoryPage page = assets.page();
            contents.add(new Content(
                    board,
                    page.pageNumber(),
                    assets.imagePath(),
                    limit(page.text().ko(), 1000),
                    limit(page.text().en(), 1000),
                    limit(page.text().ja(), 1000),
                    limit(page.sceneDescription().ko(), 1000),
                    limit(page.sceneDescription().en(), 1000),
                    limit(page.sceneDescription().ja(), 1000),
                    assets.audioKoPath(),
                    assets.audioEnPath(),
                    assets.audioJpPath()
            ));
        }

        List<ContentResponse> contentResponses = contentRepository.saveAll(contents)
                .stream()
                .map(ContentResponse::from)
                .toList();

        log.info("Generated story saved to database. boardId={}, contentCount={}", board.getId(), contentResponses.size());
        return new BoardContentsCreateResponse(BoardResponse.from(board), contentResponses);
    }

    private Map<String, Object> storyRequestBody(StoryGenerateRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", storyModel);
        body.put("instructions", """
                You create safe, warm, age-appropriate children's storybooks.
                Return only JSON that matches the provided schema.
                Create 5 to 8 pages.
                Every page must include pageNumber, text, and sceneDescription.
                The story must follow this structure across the pages: beginning, development, conflict, resolution, ending.
                Support Korean, English, and Japanese in every localized field.
                Keep page text gentle, clear, and suitable for children aged 3 to 8.
                Avoid scary, violent, horror, dark, disturbing, unsafe, or adult themes.
                Keep character designs specific and consistent so image prompts can reuse them.
                """);
        body.put("input", """
                User title hint: %s
                Hero name hint: %s
                Story request: %s
                """.formatted(
                blankToDefault(request.title(), "not provided"),
                blankToDefault(request.heroName(), "not provided"),
                request.prompt()
        ));
        body.put("max_output_tokens", 5000);
        body.put("text", Map.of("format", storyJsonSchemaFormat()));
        return body;
    }

    private Map<String, Object> imageRequestBody(
            List<OpenAiMainCharacter> mainCharacters,
            OpenAiStoryPage page
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", imageModel);
        body.put("prompt", """
                Create a warm and charming children's storybook illustration.
                Use soft pastel colors, gentle lighting, cute and friendly characters, cozy atmosphere, and consistent character design.
                The illustration must be suitable for children aged 3 to 8.
                Do not include scary, violent, horror, dark, or disturbing elements.
                Use the character descriptions and the page scene description below.

                Character descriptions:
                %s

                Scene:
                %s
                """.formatted(characterDescriptions(mainCharacters), page.sceneDescription().en()));
        body.put("size", "1024x1024");
        body.put("n", 1);
        return body;
    }

    private Map<String, Object> speechRequestBody(OpenAiStoryPage page, String languageCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", ttsModel);
        body.put("voice", ttsVoice);
        body.put("input", page.text().value(languageCode));
        body.put("instructions", """
                Read the following children's storybook page in a warm, gentle, and expressive storytelling voice.
                Use clear pronunciation, calm tone, slightly slow reading speed, and natural rhythm.
                Do not use scary, dramatic, or exaggerated acting.

                Language:
                %s
                """.formatted(languageCode));
        body.put("response_format", "mp3");
        return body;
    }

    private Map<String, Object> storyJsonSchemaFormat() {
        Map<String, Object> localizedTextSchema = localizedTextSchema();
        Map<String, Object> characterSchema = objectSchema(
                List.of("name", "description"),
                Map.of(
                        "name", localizedTextSchema,
                        "description", localizedTextSchema
                )
        );
        Map<String, Object> pageSchema = objectSchema(
                List.of("pageNumber", "text", "sceneDescription"),
                Map.of(
                        "pageNumber", Map.of("type", "integer"),
                        "text", localizedTextSchema,
                        "sceneDescription", localizedTextSchema
                )
        );
        Map<String, Object> schema = objectSchema(
                List.of("title", "mainCharacters", "pages"),
                Map.of(
                        "title", localizedTextSchema,
                        "mainCharacters", Map.of("type", "array", "items", characterSchema),
                        "pages", Map.of("type", "array", "items", pageSchema)
                )
        );

        Map<String, Object> format = new LinkedHashMap<>();
        format.put("type", "json_schema");
        format.put("name", "storybook_generation");
        format.put("strict", true);
        format.put("schema", schema);
        return format;
    }

    private Map<String, Object> localizedTextSchema() {
        return objectSchema(
                List.of("ko", "en", "ja"),
                Map.of(
                        "ko", Map.of("type", "string"),
                        "en", Map.of("type", "string"),
                        "ja", Map.of("type", "string")
                )
        );
    }

    private Map<String, Object> objectSchema(List<String> required, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", required);
        schema.put("properties", properties);
        return schema;
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

    private String extractImageBase64(JsonNode response) {
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI image response was empty");
        }
        JsonNode data = response.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI image response had no data");
        }
        String b64Json = data.get(0).path("b64_json").asText();
        if (!StringUtils.hasText(b64Json)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI image response had no b64_json");
        }
        return b64Json;
    }

    private void validateDraft(OpenAiStoryDraft draft) {
        if (draft == null || draft.title() == null || draft.mainCharacters() == null || draft.pages() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI story draft was incomplete");
        }
        validateLocalizedText(draft.title(), "title");
        if (draft.mainCharacters().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI story draft must include mainCharacters");
        }
        for (OpenAiMainCharacter character : draft.mainCharacters()) {
            if (character == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI story draft had an empty character");
            }
            validateLocalizedText(character.name(), "mainCharacters.name");
            validateLocalizedText(character.description(), "mainCharacters.description");
        }
        if (draft.pages().size() < 5 || draft.pages().size() > 8) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI story draft must have 5 to 8 pages");
        }
        for (int i = 0; i < draft.pages().size(); i++) {
            OpenAiStoryPage page = draft.pages().get(i);
            int expectedPageNumber = i + 1;
            if (page == null || page.pageNumber() == null || page.pageNumber() != expectedPageNumber) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "OpenAI story pages must be sequential from 1"
                );
            }
            validateLocalizedText(page.text(), "pages.text");
            validateLocalizedText(page.sceneDescription(), "pages.sceneDescription");
        }
    }

    private void validateLocalizedText(LocalizedText localizedText, String fieldName) {
        if (localizedText == null
                || !StringUtils.hasText(localizedText.ko())
                || !StringUtils.hasText(localizedText.en())
                || !StringUtils.hasText(localizedText.ja())) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI story draft missing " + fieldName);
        }
    }

    private void writeStoryJson(Path storyDir, OpenAiStoryDraft draft) {
        try {
            Files.createDirectories(storyDir);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(storyDir.resolve("story.json").toFile(), draft);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save story JSON", ex);
        }
    }

    private String writeImageFallback(String storyKey, Path storyDir, int pageNumber) {
        try {
            Path fallbackPath = storyDir.resolve("images").resolve("page-%02d-unavailable.svg".formatted(pageNumber));
            Files.createDirectories(fallbackPath.getParent());
            Files.writeString(fallbackPath, """
                    <svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="0 0 1024 1024">
                      <rect width="1024" height="1024" fill="#f8eadf"/>
                      <circle cx="512" cy="430" r="170" fill="#f5c7b8"/>
                      <text x="512" y="660" text-anchor="middle" font-family="sans-serif" font-size="44" fill="#7a5b4f">Image unavailable</text>
                    </svg>
                    """);
            return "/generated/%s/images/%s".formatted(storyKey, fallbackPath.getFileName());
        } catch (IOException fallbackException) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save image fallback for page " + pageNumber,
                    fallbackException
            );
        }
    }

    private String characterDescriptions(List<OpenAiMainCharacter> mainCharacters) {
        List<String> descriptions = mainCharacters.stream()
                .map(character -> "- %s: %s".formatted(character.name().en(), character.description().en()))
                .toList();
        return String.join("\n", descriptions);
    }

    private <T> T retry(String operationName, RetryableOperation<T> operation) {
        RuntimeException lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return operation.run();
            } catch (RestClientResponseException ex) {
                lastException = new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "OpenAI API request failed: " + ex.getResponseBodyAsString(),
                        ex
                );
            } catch (ResponseStatusException ex) {
                lastException = ex;
            } catch (RuntimeException ex) {
                lastException = ex;
            }

            if (attempt < MAX_RETRY_ATTEMPTS) {
                log.warn("Retrying OpenAI operation. operation={}, attempt={}", operationName, attempt + 1, lastException);
                sleepBeforeRetry(attempt);
            }
        }
        throw lastException;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(500L * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "OpenAI retry was interrupted", ex);
        }
    }

    private void ensureApiKeyConfigured() {
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OPENAI_API_KEY is not configured"
            );
        }
    }

    private String firstPageText(OpenAiStoryDraft draft) {
        if (draft.pages().isEmpty() || draft.pages().get(0).text() == null) {
            return "";
        }
        return draft.pages().get(0).text().ko();
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

    @FunctionalInterface
    private interface RetryableOperation<T> {
        T run();
    }

    private record GeneratedPageAssets(
            OpenAiStoryPage page,
            String imagePath,
            String audioKoPath,
            String audioEnPath,
            String audioJpPath
    ) {
    }

    private record OpenAiStoryDraft(
            LocalizedText title,
            List<OpenAiMainCharacter> mainCharacters,
            List<OpenAiStoryPage> pages
    ) {
    }

    private record OpenAiMainCharacter(
            LocalizedText name,
            LocalizedText description
    ) {
    }

    private record OpenAiStoryPage(
            Integer pageNumber,
            LocalizedText text,
            LocalizedText sceneDescription
    ) {
    }

    private record LocalizedText(
            String ko,
            String en,
            String ja
    ) {
        private String value(String languageCode) {
            return switch (languageCode) {
                case "ko" -> ko;
                case "en" -> en;
                case "ja" -> ja;
                default -> throw new IllegalArgumentException("Unsupported languageCode: " + languageCode);
            };
        }
    }
}
