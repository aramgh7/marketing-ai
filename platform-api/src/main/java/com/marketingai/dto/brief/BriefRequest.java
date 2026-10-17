package com.marketingai.dto.brief;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BriefRequest {

    @NotBlank
    @Size(max = 500)
    private String title;

    /** VIDEO, IMAGE, CAROUSEL */
    @NotBlank
    private String creativeType;

    @Size(max = 2000)
    private String targetAudience;

    @Size(max = 2000)
    private String productInfo;

    @Size(max = 2000)
    private String notes;

    /** Optional: DRAFT or FINAL */
    private String status;

    /** GENERAL | VIDEO | LANDING | STATIC_IMAGE (vision §5.5.3 paired brief) */
    private String briefType;
}
