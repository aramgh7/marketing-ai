package com.marketingai.dto.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class EventBatchRequest {

    @NotEmpty
    @Size(max = 100)
    @Valid
    private List<EventDto> events;
}
