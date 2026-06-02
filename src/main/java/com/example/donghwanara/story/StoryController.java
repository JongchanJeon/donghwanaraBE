package com.example.donghwanara.story;

import com.example.donghwanara.board.dto.BoardContentsCreateResponse;
import com.example.donghwanara.story.dto.StoryGenerateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/stories")
public class StoryController {

    private static final Logger log = LoggerFactory.getLogger(StoryController.class);

    private final StoryGenerateService storyGenerateService;

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public BoardContentsCreateResponse generate(@Valid @RequestBody StoryGenerateRequest request) {
        log.info(
                "Story generation request received. title={}, heroName={}, promptLength={}",
                request.title(),
                request.heroName(),
                request.prompt() == null ? 0 : request.prompt().length()
        );
        return storyGenerateService.generate(request);
    }
}
