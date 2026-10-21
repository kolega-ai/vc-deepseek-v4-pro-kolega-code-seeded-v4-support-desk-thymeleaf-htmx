package com.relaydesk.channel.dto;

import com.relaydesk.channel.domain.Priority;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SlaPolicyForm(
        @NotBlank @Size(max = 100) String name,
        @NotNull Priority priority,
        @NotNull @Min(5) @Max(43200) Integer responseMinutes,
        @NotNull @Min(10) @Max(525600) Integer resolutionMinutes,
        Boolean businessHoursOnly,
        Boolean active) {

    public SlaPolicyForm {
        businessHoursOnly = businessHoursOnly != null && businessHoursOnly;
        active = active != null && active;
    }
}
