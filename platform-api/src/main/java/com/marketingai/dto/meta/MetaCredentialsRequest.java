package com.marketingai.dto.meta;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MetaCredentialsRequest {

    @NotBlank
    private String accessToken;

    @NotBlank
    private String adAccountId;

    private String appId;
}
