package com.marketingai.service;

/** Classes of Meta Marketing API writes for {@link MetaWritePolicy} (roadmap 9-19). */
public enum MetaWriteAction {
    PAUSE("PROTECTIVE"),            // pause ad set / campaign, stop-loss, autopause (never gated)
    RESUME("RESUME"),               // set ACTIVE after an incident / recovery
    BUDGET_CHANGE("BUDGET"),        // daily budget up or down
    LAUNCH("LAUNCH"),               // test batch / creative testing tool ad sets
    CREATE_AD_SET("CREATE"),        // single ad set outside a batch
    CREATE_CAMPAIGN("CREATE"),      // new campaign
    AUDIENCE_UPDATE("CREATE");      // custom audience sync

    private final String actionClass;

    MetaWriteAction(String actionClass) { this.actionClass = actionClass; }

    public String actionClass() { return actionClass; }

    public boolean protective() { return this == PAUSE; }

    /** Map the free-text action names used by MetaApiClient.enqueue / AutonomyService callers. */
    public static MetaWriteAction fromLegacyName(String name) {
        if (name == null) return LAUNCH;
        String n = name.toUpperCase();
        if (n.contains("PAUSE") || n.contains("STOP") || n.startsWith("L1") || n.startsWith("L3")) return PAUSE;
        if (n.contains("RESUME")) return RESUME;
        if (n.contains("BUDGET") || n.contains("BID") || n.contains("SCALE")) return BUDGET_CHANGE;
        if (n.contains("AUDIENCE")) return AUDIENCE_UPDATE;
        if (n.contains("CAMPAIGN_CREATE") || n.contains("CREATE_CAMPAIGN")) return CREATE_CAMPAIGN;
        if (n.contains("AD_SET") || n.contains("ADSET")) return CREATE_AD_SET;
        return LAUNCH;
    }
}
