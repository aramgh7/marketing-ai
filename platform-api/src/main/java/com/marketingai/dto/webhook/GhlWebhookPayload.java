package com.marketingai.dto.webhook;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * GHL webhook payload — real event names (arch v1.1 §13.1):
 *   ContactCreate, ContactUpdate, AppointmentCreate, AppointmentUpdate,
 *   OpportunityStatusUpdate, conversation/message webhooks for call outcome.
 *
 * Unknown fields captured in rawData for JSONB audit log + replay.
 */
@Data
public class GhlWebhookPayload {

    /** Real GHL event name: ContactCreate, AppointmentUpdate, OpportunityStatusUpdate, etc. */
    private String type;

    private String contactId;
    private String opportunityId;
    private String locationId;
    private String email;
    private String phone;
    private String fullName;

    // OpportunityStatusUpdate fields
    private String status;             // "won" | "lost" | pipeline stage name
    private Double dealValue;          // monetary value on won/lost
    private String pipelineStage;

    // AppointmentCreate / AppointmentUpdate fields
    private String appointmentId;
    private String startTime;
    /** arch v1.1 §13.1: "showed" | "noshow" — set manually by the sales rep in GHL. */
    private String appointmentStatus;

    /** Captures any unknown fields for JSONB storage (audit + replay). */
    private Map<String, Object> rawData = new HashMap<>();

    @JsonAnySetter
    public void setRawData(String key, Object value) {
        rawData.put(key, value);
    }
}
