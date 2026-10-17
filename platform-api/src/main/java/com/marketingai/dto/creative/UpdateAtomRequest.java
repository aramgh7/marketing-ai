package com.marketingai.dto.creative;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateAtomRequest {

    @NotBlank
    @Size(max = 50)
    private String atomType;

    @NotBlank
    @Size(max = 500)
    private String atomValue;

    /** Confidence [0,1]; null for manual edits */
    private Float confidence;
}
