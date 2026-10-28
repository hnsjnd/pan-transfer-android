package com.shicheng.pantransfer.store;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * On-device encrypted persistence for tokens/cookies. Never sent to third-party servers.
 */
public class SecureStore {
    private static final String PREFS = "pan_transfer_secure_v1";
    private static final String K_ALI_REFRESH = "ali_refresh_token";
    private static final String K_ALI_ACCESS = "ali_access_token";
    private static final String K_ALI_EXPIRES = "ali_access_expires_at";
    private static final String K_ALI_NICK = "ali_nick_name";
    private static final String K_ALI_USER = "ali_user_id";
    private static final String K_ALI_DRIVE = "ali_default_drive_id";
    private static final String K_QUARK_COOKIE = "quark_cookie";
    private static final String K_QUARK_NICK = "quark_nick_name";

    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        SharedPreferences p;
        try {
            MasterKey key = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    context,
                    PREFS,
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fallback for rare crypto init failures; still private MODE_PRIVATE
            p = context.getSharedPreferences(PREFS + "_fallback", Context.MODE_PRIVATE);
        }
        this.prefs = p;
    }

    public void saveAliyunTokens(String refresh, String access, long expiresAtMs,
                                 String nick, String userId, String driveId) {
        prefs.edit()
                .putString(K_ALI_REFRESH, refresh == null ? "" : refresh)
                .putString(K_ALI_ACCESS, access == null ? "" : access)
                .putLong(K_ALI_EXPIRES, expiresAtMs)
                .putString(K_ALI_NICK, nick == null ? "" : nick)
                .putString(K_ALI_USER, userId == null ? "" : userId)
                .putString(K_ALI_DRIVE, driveId == null ? "" : driveId)
                .apply();
    }

    public void updateAliyunAccess(String access, long expiresAtMs) {
        prefs.edit()
                .putString(K_ALI_ACCESS, access == null ? "" : access)
                .putLong(K_ALI_EXPIRES, expiresAtMs)
                .apply();
    }

    public void updateAliyunRefresh(String refresh) {
        prefs.edit().putString(K_ALI_REFRESH, refresh == null ? "" : refresh).apply();
    }

    public void clearAliyun() {
        prefs.edit()
                .remove(K_ALI_REFRESH).remove(K_ALI_ACCESS).remove(K_ALI_EXPIRES)
                .remove(K_ALI_NICK).remove(K_ALI_USER).remove(K_ALI_DRIVE)
                .apply();
    }

    public boolean hasAliyun() {
        String r = prefs.getString(K_ALI_REFRESH, "");
        return r != null && !r.isEmpty();
    }

    public String getAliRefresh() { return prefs.getString(K_ALI_REFRESH, ""); }
    public String getAliAccess() { return prefs.getString(K_ALI_ACCESS, ""); }
    public long getAliExpiresAt() { return prefs.getLong(K_ALI_EXPIRES, 0); }
    public String getAliNick() { return prefs.getString(K_ALI_NICK, ""); }
    public String getAliUserId() { return prefs.getString(K_ALI_USER, ""); }
    public String getAliDriveId() { return prefs.getString(K_ALI_DRIVE, ""); }

    public void saveQuark(String cookie, String nick) {
        prefs.edit()
                .putString(K_QUARK_COOKIE, cookie == null ? "" : cookie)
                .putString(K_QUARK_NICK, nick == null ? "" : nick)
                .apply();
    }

    public void clearQuark() {
        prefs.edit().remove(K_QUARK_COOKIE).remove(K_QUARK_NICK).apply();
    }

    public boolean hasQuark() {
        String c = prefs.getString(K_QUARK_COOKIE, "");
        return c != null && !c.isEmpty();
    }

    public String getQuarkCookie() { return prefs.getString(K_QUARK_COOKIE, ""); }
    public String getQuarkNick() { return prefs.getString(K_QUARK_NICK, ""); }
}
