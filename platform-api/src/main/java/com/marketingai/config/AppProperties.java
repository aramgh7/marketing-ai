package com.marketingai.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final Telegram telegram = new Telegram();
    private final Storage storage = new Storage();
    private final Report report = new Report();
    private final Meta meta = new Meta();
    private final Landing landing = new Landing();
    private String mlServiceUrl;
    private String internalApiKey;
    private String ghlWebhookSecret = "";
    private String slackWebhookUrl = "";
    /** TELEGRAM | SLACK | BOTH */
    private String alertChannel = "BOTH";

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expiryMs = 86400000L;
    }

    @Getter
    @Setter
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:3000");
    }

    @Getter
    @Setter
    public static class Storage {
        /** "local" (dev) or "r2" (Phase 4 production) */
        private String type = "local";
        private String localPath = System.getProperty("java.io.tmpdir") + "/marketing-ai/files";
        private final R2 r2 = new R2();

        @Getter
        @Setter
        public static class R2 {
            private String accountId = "";
            private String accessKeyId = "";
            private String secretAccessKey = "";
            private String bucket = "marketing-ai-assets";
            /** Public base URL, e.g. "https://assets.example.com". Falls back to r2.dev subdomain if blank. */
            private String publicUrl = "";
        }
    }

    @Getter
    @Setter
    public static class Report {
        private String emailTo = "";
        private String emailFrom = "noreply@marketing-ai.local";
        private boolean enabled = true;

        public boolean isEmailConfigured() {
            return emailTo != null && !emailTo.isBlank();
        }
    }

    /** Roadmap Phase 10: client domains for landings and the self-hosted landing runner. */
    @Getter
    @Setter
    public static class Landing {
        /** Where a client's CNAME must point (our landing edge). */
        private String cnameTarget = "landings.example.com";
        /** Hosts that serve the admin cabinet; any other host is resolved via tenant_domains. */
        private List<String> platformHosts = List.of("localhost:3000");
    }

    @Getter
    @Setter
    public static class Meta {
        private String appId;
        private String appSecret;
    }

    @Getter
    @Setter
    public static class Telegram {
        private String botToken = "";
        private String chatId = "";

        public boolean isConfigured() {
            return botToken != null && !botToken.isBlank()
                    && chatId != null && !chatId.isBlank();
        }
    }

    /** D-21: external-signals configuration. */
    private final Signals signals = new Signals();

    @Getter
    @Setter
    public static class Signals {
        /**
         * Semicolon-separated "lat,lon" pairs for the weather fetch (Open-Meteo).
         * Example: "51.5,-0.1;40.7,-74.0" for London + New York.
         * Defaults to "51.5,-0.1" (London) when blank.
         */
        private String weatherGeos = "";
    }
}
