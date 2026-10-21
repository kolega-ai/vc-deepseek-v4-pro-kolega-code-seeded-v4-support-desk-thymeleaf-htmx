package com.relaydesk.channel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record KbArticleForm(
        @NotBlank @Size(max = 200) String title,
        UUID categoryId,
        @Size(max = 500) String tags,
        @NotBlank @Size(max = 20000) String body,
        Boolean internalOnly) {

    public KbArticleForm {
        internalOnly = internalOnly != null && internalOnly;
    }
}
