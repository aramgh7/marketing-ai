package com.marketingai.dto.event;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class EventBatchResponse {
    private int accepted;
    private int skipped;
    private String status;
}
