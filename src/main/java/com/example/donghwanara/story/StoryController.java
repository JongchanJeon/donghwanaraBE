package com.example.donghwanara.story;

import com.example.donghwanara.board.dto.BoardContentsCreateResponse;
import com.example.donghwanara.story.dto.StoryGenerateRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    private final StoryGenerateService storyGenerateService;

    @PostMapping("/generate")
    @ResponseStatus(HttpStatus.CREATED)
    public BoardContentsCreateResponse generate(@Valid @RequestBody StoryGenerateRequest request) {
        return storyGenerateService.generate(request);
    }
}
