package com.shicheng.pantransfer.api;

import android.util.Log;

import com.shicheng.pantransfer.store.SecureStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Aliyun Drive personal web APIs (refresh_token based). Tokens stay on-device.
 */
public class AliyunApi {
    private static final String TAG = "AliyunApi";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String AUTH = "https://auth.aliyundrive.com/v2/account/token";
    private static final String API = "https://api.aliyundrive.com";
    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final long PART_SIZE = 10L * 1024 * 1024; // 10MB
    public static final String SAVE_FOLDER = "网盘转存";

    public interface ProgressListener {
        void onProgress(String stage, long done, long total, String detail);
    }

    public static class ShareFile {
        public String fileId;
        public String name;
        public String type; // file | folder
        public long size;
        public String parentFileId;
    }

    public static class UserInfo {
        public String nickName;
        public String userId;
        public String defaultDriveId;
        public String accessToken;
        public String refreshToken;
        public long expiresAtMs;
    }

    private final SecureStore store;
    private final OkHttpClient http;

    public AliyunApi(SecureStore store) {
        this.store = store;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    public static String parseShareId(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("(?:aliyundrive|alipan)\\.com/s/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    public UserInfo refreshAndLoadUser(String refreshToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("grant_type", "refresh_token");
        body.put("refresh_token", refreshToken);
        JSONObject tok = postJson(AUTH, body.toString(), null, null);
        String access = tok.optString("access_token", "");
        String refresh = tok.optString("refresh_token", refreshToken);
        if (access.isEmpty()) throw new IOException("刷新 token 失败：无 access_token");
        long expiresIn = tok.optLong("expires_in", 7200);
        long expiresAt = System.currentTimeMillis() + Math.max(60, expiresIn - 120) * 1000L;

        JSONObject user = postJson(API + "/v2/user/get", "{}", access, null);
        UserInfo info = new UserInfo();
        info.accessToken = access;
        info.refreshToken = refresh;
        info.expiresAtMs = expiresAt;
        info.nickName = firstNonEmpty(user.optString("nick_name"), user.optString("name"), user.optString("user_name"), "阿里云盘用户");
        info.userId = firstNonEmpty(user.optString("user_id"), user.optString("id"), "");
        info.defaultDriveId = firstNonEmpty(user.optString("default_drive_id"), user.optString("resource_drive_id"), "");
        if (info.defaultDriveId.isEmpty()) {
            // fallback newer endpoint
            try {
                JSONObject drive = postJson(API + "/adrive/v1.0/user/getDriveInfo", "{}", access, null);
                info.defaultDriveId = firstNonEmpty(drive.optString("default_drive_id"), drive.optString("resource_drive_id"), "");
            } catch (Exception ignored) {}
        }
        if (info.defaultDriveId.isEmpty()) throw new IOException("无法获取 default_drive_id");
        store.saveAliyunTokens(info.refreshToken, info.accessToken, info.expiresAtMs,
                info.nickName, info.userId, info.defaultDriveId);
        return info;
    }

    public synchronized String ensureAccessToken() throws Exception {
        if (!store.hasAliyun()) throw new IOException("未登录阿里云盘");
        String access = store.getAliAccess();
        long exp = store.getAliExpiresAt();
        if (access != null && !access.isEmpty() && System.currentTimeMillis() < exp) {
            return access;
        }
        UserInfo u = refreshAndLoadUser(store.getAliRefresh());
        return u.accessToken;
    }

    public String getShareToken(String shareId, String pwd) throws Exception {
        JSONObject body = new JSONObject();
        body.put("share_id", shareId);
        if (pwd != null && !pwd.isEmpty()) body.put("share_pwd", pwd);
        JSONObject resp = postJson(API + "/v2/share_link/get_share_token", body.toString(), null, null);
        String token = resp.optString("share_token", "");
        if (token.isEmpty()) throw new IOException("获取 share_token 失败（链接无效或提取码错误）");
        return token;
    }

    public List<ShareFile> listShareFiles(String shareId, String shareToken, String parentFileId) throws Exception {
        List<ShareFile> all = new ArrayList<>();
        String marker = "";
        do {
            JSONObject body = new JSONObject();
            body.put("share_id", shareId);
            body.put("parent_file_id", parentFileId == null ? "root" : parentFileId);
            body.put("limit", 100);
            body.put("order_by", "name");
            body.put("order_direction", "ASC");
            if (marker != null && !marker.isEmpty()) body.put("marker", marker);
            JSONObject resp = postJson(API + "/adrive/v2/file/list_by_share", body.toString(), null, shareToken);
            JSONArray items = resp.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject it = items.getJSONObject(i);
                    ShareFile f = new ShareFile();
                    f.fileId = it.optString("file_id");
                    f.name = it.optString("name");
                    f.type = it.optString("type");
                    f.size = it.optLong("size", 0);
                    f.parentFileId = it.optString("parent_file_id", parentFileId);
                    all.add(f);
                }
            }
            marker = resp.optString("next_marker", "");
        } while (marker != null && !marker.isEmpty());
        return all;
    }

    /** Ensure folder 「网盘转存」 under root; return its file_id. */
    public String ensureSaveFolder(ProgressListener progress) throws Exception {
        String access = ensureAccessToken();
        String driveId = store.getAliDriveId();
        // list root for existing folder
        JSONObject listBody = new JSONObject();
        listBody.put("drive_id", driveId);
        listBody.put("parent_file_id", "root");
        listBody.put("limit", 100);
        JSONObject listed = postJson(API + "/adrive/v3/file/list", listBody.toString(), access, null);
        JSONArray items = listed.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                if ("folder".equals(it.optString("type")) && SAVE_FOLDER.equals(it.optString("name"))) {
                    return it.optString("file_id");
                }
            }
        }
        if (progress != null) progress.onProgress("prepare", 0, 0, "创建文件夹「" + SAVE_FOLDER + "」");
        JSONObject create = new JSONObject();
        create.put("drive_id", driveId);
        create.put("parent_file_id", "root");
        create.put("name", SAVE_FOLDER);
        create.put("type", "folder");
        create.put("check_name_mode", "refuse");
        JSONObject created = postJson(API + "/adrive/v2/file/createWithFolders", create.toString(), access, null);
        String id = created.optString("file_id", "");
        if (id.isEmpty()) throw new IOException("创建转存文件夹失败");
        return id;
    }

    /** Same-pan save: copy share files into user's drive. */
    public int saveShareToDrive(String shareUrl, String pwd, ProgressListener progress) throws Exception {
        String shareId = parseShareId(shareUrl);
        if (shareId == null) throw new IOException("无法解析阿里云盘 share_id");
        if (progress != null) progress.onProgress("share", 0, 0, "获取分享令牌…");
        String shareToken = getShareToken(shareId, pwd);
        if (progress != null) progress.onProgress("list", 0, 0, "列举分享文件…");
        List<ShareFile> files = listShareFiles(shareId, shareToken, "root");
        if (files.isEmpty()) throw new IOException("分享为空或无法访问");
        String destParent = ensureSaveFolder(progress);
        String access = ensureAccessToken();
        String driveId = store.getAliDriveId();

        // Prefer batch save endpoint
        JSONArray fileIds = new JSONArray();
        for (ShareFile f : files) fileIds.put(f.fileId);

        try {
            JSONObject batchReq = new JSONObject();
            JSONArray requests = new JSONArray();
            JSONObject one = new JSONObject();
            one.put("body", new JSONObject()
                    .put("share_id", shareId)
                    .put("to_drive_id", driveId)
                    .put("to_parent_file_id", destParent)
                    .put("auto_rename", true)
                    .put("file_id_list", fileIds));
            one.put("headers", new JSONObject().put("Content-Type", "application/json"));
            one.put("id", "0");
            one.put("method", "POST");
            one.put("url", "/file/copy");
            requests.put(one);
            batchReq.put("requests", requests);
            batchReq.put("resource", "file");
            if (progress != null) progress.onProgress("save", 0, files.size(), "同盘转存中…");
            postJson(API + "/adrive/v2/batch", batchReq.toString(), access, shareToken);
            if (progress != null) progress.onProgress("done", files.size(), files.size(), "已转存 " + files.size() + " 项到「" + SAVE_FOLDER + "」");
            return files.size();
        } catch (Exception batchErr) {
            Log.w(TAG, "batch save failed, fallback copy: " + batchErr.getMessage());
        }

        int ok = 0;
        for (int i = 0; i < files.size(); i++) {
            ShareFile f = files.get(i);
            if (progress != null) progress.onProgress("save", i, files.size(), "转存 " + f.name);
            JSONObject copy = new JSONObject();
            copy.put("share_id", shareId);
            copy.put("file_id", f.fileId);
            copy.put("to_drive_id", driveId);
            copy.put("to_parent_file_id", destParent);
            copy.put("auto_rename", true);
            postJson(API + "/v2/file/copy", copy.toString(), access, shareToken);
            ok++;
        }
        if (progress != null) progress.onProgress("done", ok, files.size(), "已转存 " + ok + " 项到「" + SAVE_FOLDER + "」");
        return ok;
    }

    /** Upload a local file into 「网盘转存」 (or given parent). */
    public String uploadFile(File file, String parentFileId, ProgressListener progress) throws Exception {
        if (file == null || !file.exists()) throw new IOException("上传文件不存在");
        String access = ensureAccessToken();
        String driveId = store.getAliDriveId();
        if (parentFileId == null || parentFileId.isEmpty()) {
            parentFileId = ensureSaveFolder(progress);
        }
        long size = file.length();
        int parts = (int) Math.max(1, (size + PART_SIZE - 1) / PART_SIZE);
        JSONArray partInfoList = new JSONArray();
        for (int i = 1; i <= parts; i++) {
            partInfoList.put(new JSONObject().put("part_number", i));
        }
        JSONObject create = new JSONObject();
        create.put("drive_id", driveId);
        create.put("parent_file_id", parentFileId);
        create.put("name", file.getName());
        create.put("type", "file");
        create.put("check_name_mode", "auto_rename");
        create.put("size", size);
        create.put("part_info_list", partInfoList);
        if (progress != null) progress.onProgress("upload_create", 0, size, "创建上传任务…");
        JSONObject created = postJson(API + "/adrive/v2/file/createWithFolders", create.toString(), access, null);
        if (created.optBoolean("rapid_upload", false)) {
            if (progress != null) progress.onProgress("done", size, size, "秒传成功（内容哈希命中）");
            return created.optString("file_id");
        }
        String fileId = created.optString("file_id");
        String uploadId = created.optString("upload_id");
        JSONArray urls = created.optJSONArray("part_info_list");
        if (urls == null || urls.length() == 0) throw new IOException("未返回上传地址");

        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(PART_SIZE, Integer.MAX_VALUE / 4)];
            long uploaded = 0;
            for (int i = 0; i < urls.length(); i++) {
                JSONObject part = urls.getJSONObject(i);
                String uploadUrl = part.optString("upload_url");
                int toRead = (int) Math.min(PART_SIZE, size - uploaded);
                int off = 0;
                while (off < toRead) {
                    int n = in.read(buf, off, toRead - off);
                    if (n < 0) break;
                    off += n;
                }
                Request put = new Request.Builder()
                        .url(uploadUrl)
                        .put(RequestBody.create(java.util.Arrays.copyOf(buf, off), MediaType.parse("application/octet-stream")))
                        .build();
                try (Response resp = http.newCall(put).execute()) {
                    if (!resp.isSuccessful()) {
                        throw new IOException("分片上传失败 HTTP " + resp.code());
                    }
                }
                uploaded += off;
                if (progress != null) progress.onProgress("upload", uploaded, size, "上传 " + file.getName());
            }
        }
        JSONObject complete = new JSONObject();
        complete.put("drive_id", driveId);
        complete.put("file_id", fileId);
        complete.put("upload_id", uploadId);
        postJson(API + "/v2/file/complete", complete.toString(), access, null);
        if (progress != null) progress.onProgress("done", size, size, "上传完成 " + file.getName());
        return fileId;
    }

    private JSONObject postJson(String url, String jsonBody, String accessToken, String shareToken) throws Exception {
        Request.Builder b = new Request.Builder()
                .url(url)
                .post(RequestBody.create(jsonBody, JSON))
                .header("User-Agent", UA)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (accessToken != null && !accessToken.isEmpty()) {
            b.header("Authorization", "Bearer " + accessToken);
        }
        if (shareToken != null && !shareToken.isEmpty()) {
            b.header("x-share-token", shareToken);
        }
        b.header("X-Canary", "client=web,app=adrive,version=v4.0.0");
        try (Response resp = http.newCall(b.build()).execute()) {
            String text = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + truncate(text, 300));
            }
            if (text == null || text.isEmpty()) return new JSONObject();
            JSONObject obj = new JSONObject(text);
            String code = obj.optString("code", "");
            if (!code.isEmpty() && !"OK".equalsIgnoreCase(code) && !"Success".equalsIgnoreCase(code)) {
                // Some endpoints return business errors with 200
                String msg = firstNonEmpty(obj.optString("message"), obj.optString("msg"), code);
                if (!"AccessTokenInvalid".equals(code) && !"ShareLinkTokenInvalid".equals(code)
                        && !"PreHashMatched".equals(code)) {
                    // AccessTokenInvalid handled by caller via refresh; PreHashMatched for rapid
                    if ("AccessTokenInvalid".equals(code) || "AccessTokenExpired".equals(code)) {
                        throw new IOException("AccessTokenInvalid");
                    }
                    throw new IOException(msg);
                }
                if ("AccessTokenInvalid".equals(code) || "AccessTokenExpired".equals(code)) {
                    throw new IOException("AccessTokenInvalid");
                }
            }
            return obj;
        }
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return "";
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return "";
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
