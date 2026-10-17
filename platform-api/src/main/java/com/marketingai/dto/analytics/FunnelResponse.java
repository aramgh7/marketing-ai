package com.marketingai.dto.analytics;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FunnelResponse {

    int periodDays;
    List<FunnelStage> stages;
    DiagnosticCard diagnostics;
    String disclaimer;

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunnelStage {
        String stage;
        long sessions;
        Double dropOffPct;
    }

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DiagnosticCard {
        int symptomsDetected;
        List<Symptom> symptoms;
        String note;
    }

    @Value
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Symptom {
        String code;
        boolean detected;
        String value;
        String diagnosis;
        String action;
        String dataSource;
    }
}
