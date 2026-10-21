package com.marketingai.dto.meta;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Getter;
import lombok.Setter;

/**
 * PUT /tenants/me/meta-credentials. Roadmap Phase 11: every field is optional — a body with only
 * page / instagram ids updates just those; when {@code accessToken} is sent, {@code adAccountId} is required
 * and the token lifecycle restarts (TenantService validates).
 */
@Getter
@Setter
public class MetaCredentialsRequest {

    @JsonAlias({"access_token", "meta_access_token"})
    private String accessToken;

    @JsonAlias({"ad_account_id", "meta_ad_account_id"})
    private String adAccountId;

    @JsonAlias({"app_id", "meta_app_id"})
    private String appId;

    /** Roadmap Phase 11 (optional) */
    @JsonAlias({"page_id", "meta_page_id"})
    private String pageId;

    @JsonAlias({"instagram_actor_id", "meta_instagram_actor_id"})
    private String instagramActorId;

    public boolean hasToken() { return accessToken != null && !accessToken.isBlank(); }
}
