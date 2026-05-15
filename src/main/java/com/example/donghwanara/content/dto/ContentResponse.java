package com.example.donghwanara.content.dto;

import com.example.donghwanara.content.Content;

public record ContentResponse(
        Integer id,
        Integer bookId,
        Integer seq,
        String photoPath,
        String subtitleKo,
        String subtitleEn,
        String subtitleJp,
        String audioKoPath,
        String audioEnPath,
        String audioJpPath
) {
    public static ContentResponse from(Content content) {
        return new ContentResponse(
                content.getId(),
                content.getBoard().getId(),
                content.getSeq(),
                content.getPhotoPath(),
                content.getSubtitleKo(),
                content.getSubtitleEn(),
                content.getSubtitleJp(),
                content.getAudioKoPath(),
                content.getAudioEnPath(),
                content.getAudioJpPath()
        );
    }
}
