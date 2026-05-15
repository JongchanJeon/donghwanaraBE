package com.example.donghwanara.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ContentRequest(
        @NotNull Integer seq,
        @NotBlank @Size(max = 256) String photoPath,
        @NotBlank @Size(max = 256) String subtitleKo,
        @NotBlank @Size(max = 256) String subtitleEn,
        @NotBlank @Size(max = 256) String subtitleJp,
        @NotBlank @Size(max = 256) String audioKoPath,
        @NotBlank @Size(max = 256) String audioEnPath,
        @NotBlank @Size(max = 256) String audioJpPath
) {
}
