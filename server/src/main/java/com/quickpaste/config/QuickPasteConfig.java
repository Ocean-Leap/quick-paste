package com.quickpaste.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * All QP_* tunables (SPEC §11). Bound from application.yml; never hardcode these elsewhere.
 */
@ConfigurationProperties("quickpaste")
public class QuickPasteConfig {

    private String publicUrl = "http://localhost:8080";
    private String storageDir = "/var/lib/quickpaste/blobs";
    private String authMode = "google";
    private String localPassword = "";
    private int trustedProxyCount = 0;
    private String googleClientId = "";
    private String googleClientSecret = "";
    private String allowedEmails = "";
    private String sessionSecret = "";
    private int sessionTtlHours = 720;
    private int tokenTtlDays = 0;
    private int clipTtlHours = 24;
    private int fileTtlHours = 72;
    private int maxTextKb = 256;
    private int simpleUploadMb = 20;
    private int maxFileMb = 512;
    private int userQuotaMb = 2048;
    private int dedupWindowSec = 10;
    private int idempotencyTtlSec = 3600;
    private int channelMaxMembers = 25;
    private int maxWsConnPerUser = 10;
    private int rateLimitPerMin = 600;
    private int loginRateLimitPerMin = 10;
    private int sweepIntervalMin = 15;
    private int uploadIdleGcHours = 24;
    private int exportTtlHours = 24;
    private boolean thumbnailsEnabled = true;
    private int thumbnailMaxPx = 320;
    private int sinkDeliveryIntervalSec = 15;
    private int sinkMaxAttempts = 8;
    private String vapidPublicKey = "";
    private String vapidPrivateKey = "";
    private String vapidSubject = "";
    private boolean devAuthBypass = false;
    private String devUserEmail = "dev@localhost";

    public boolean pushEnabled() {
        return vapidPublicKey != null && !vapidPublicKey.isBlank()
                && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
    }

    public List<String> allowedEmailList() {
        return Arrays.stream(allowedEmails.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public boolean secureCookies() {
        return publicUrl.startsWith("https://");
    }

    public String getPublicUrl() { return publicUrl; }
    public void setPublicUrl(String v) { this.publicUrl = v; }
    public String getAuthMode() { return authMode; }
    public void setAuthMode(String v) { this.authMode = v; }
    public String getLocalPassword() { return localPassword; }
    public void setLocalPassword(String v) { this.localPassword = v; }
    public boolean isLocalMode() { return "local".equals(authMode); }
    public int getTrustedProxyCount() { return trustedProxyCount; }
    public void setTrustedProxyCount(int v) { this.trustedProxyCount = v; }
    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String v) { this.storageDir = v; }
    public String getGoogleClientId() { return googleClientId; }
    public void setGoogleClientId(String v) { this.googleClientId = v; }
    public String getGoogleClientSecret() { return googleClientSecret; }
    public void setGoogleClientSecret(String v) { this.googleClientSecret = v; }
    public String getAllowedEmails() { return allowedEmails; }
    public void setAllowedEmails(String v) { this.allowedEmails = v; }
    public String getSessionSecret() { return sessionSecret; }
    public void setSessionSecret(String v) { this.sessionSecret = v; }
    public int getSessionTtlHours() { return sessionTtlHours; }
    public void setSessionTtlHours(int v) { this.sessionTtlHours = v; }
    public int getTokenTtlDays() { return tokenTtlDays; }
    public void setTokenTtlDays(int v) { this.tokenTtlDays = v; }
    public int getClipTtlHours() { return clipTtlHours; }
    public void setClipTtlHours(int v) { this.clipTtlHours = v; }
    public int getFileTtlHours() { return fileTtlHours; }
    public void setFileTtlHours(int v) { this.fileTtlHours = v; }
    public int getMaxTextKb() { return maxTextKb; }
    public void setMaxTextKb(int v) { this.maxTextKb = v; }
    public int getSimpleUploadMb() { return simpleUploadMb; }
    public void setSimpleUploadMb(int v) { this.simpleUploadMb = v; }
    public int getMaxFileMb() { return maxFileMb; }
    public void setMaxFileMb(int v) { this.maxFileMb = v; }
    public int getUserQuotaMb() { return userQuotaMb; }
    public void setUserQuotaMb(int v) { this.userQuotaMb = v; }
    public int getDedupWindowSec() { return dedupWindowSec; }
    public void setDedupWindowSec(int v) { this.dedupWindowSec = v; }
    public int getIdempotencyTtlSec() { return idempotencyTtlSec; }
    public void setIdempotencyTtlSec(int v) { this.idempotencyTtlSec = v; }
    public int getChannelMaxMembers() { return channelMaxMembers; }
    public void setChannelMaxMembers(int v) { this.channelMaxMembers = v; }
    public int getMaxWsConnPerUser() { return maxWsConnPerUser; }
    public void setMaxWsConnPerUser(int v) { this.maxWsConnPerUser = v; }
    public int getRateLimitPerMin() { return rateLimitPerMin; }
    public void setRateLimitPerMin(int v) { this.rateLimitPerMin = v; }
    public int getLoginRateLimitPerMin() { return loginRateLimitPerMin; }
    public void setLoginRateLimitPerMin(int v) { this.loginRateLimitPerMin = v; }
    public int getSweepIntervalMin() { return sweepIntervalMin; }
    public void setSweepIntervalMin(int v) { this.sweepIntervalMin = v; }
    public int getUploadIdleGcHours() { return uploadIdleGcHours; }
    public void setUploadIdleGcHours(int v) { this.uploadIdleGcHours = v; }
    public int getExportTtlHours() { return exportTtlHours; }
    public void setExportTtlHours(int v) { this.exportTtlHours = v; }
    public boolean isThumbnailsEnabled() { return thumbnailsEnabled; }
    public void setThumbnailsEnabled(boolean v) { this.thumbnailsEnabled = v; }
    public int getThumbnailMaxPx() { return thumbnailMaxPx; }
    public void setThumbnailMaxPx(int v) { this.thumbnailMaxPx = v; }
    public int getSinkDeliveryIntervalSec() { return sinkDeliveryIntervalSec; }
    public void setSinkDeliveryIntervalSec(int v) { this.sinkDeliveryIntervalSec = v; }
    public int getSinkMaxAttempts() { return sinkMaxAttempts; }
    public void setSinkMaxAttempts(int v) { this.sinkMaxAttempts = v; }
    public String getVapidPublicKey() { return vapidPublicKey; }
    public void setVapidPublicKey(String v) { this.vapidPublicKey = v; }
    public String getVapidPrivateKey() { return vapidPrivateKey; }
    public void setVapidPrivateKey(String v) { this.vapidPrivateKey = v; }
    public String getVapidSubject() { return vapidSubject; }
    public void setVapidSubject(String v) { this.vapidSubject = v; }
    public boolean isDevAuthBypass() { return devAuthBypass; }
    public void setDevAuthBypass(boolean v) { this.devAuthBypass = v; }
    public String getDevUserEmail() { return devUserEmail; }
    public void setDevUserEmail(String v) { this.devUserEmail = v; }
}
