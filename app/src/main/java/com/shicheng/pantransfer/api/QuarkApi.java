package com.shicheng.pantransfer.api;

import com.shicheng.pantransfer.store.SecureStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Quark pan cookie-based APIs: share resolve, save-to-own, download URL.
 */
public class QuarkApi {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String BASE = "https://drive-pc.quark.cn/1/clouddrive";
    private static final String SHARE_BASE = "https://drive.quark.cn/1/clouddrive";
    private static final String UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String UA_ELECTRON = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.56 Chrome/100.0.4896.160 Electron/18.3.5.12 Safari/537.36 Channel/pckk_other_ch";
    public static final String TEMP_FOLDER = "网盘转存临时";

    public interface ProgressListener {
        void onProgress(String stage, long done, long total, String detail);
    }

    public static class ShareItem {
        public String fid;
        public String shareFidToken;
        public String name;
        public long size;
        public boolean dir;
    }

    private final SecureStore store;
    private final OkHttpClient http;

    public QuarkApi(SecureStore store) {
        this.store = store;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    public static String parsePwdId(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("(?:pan\\.)?quark\\.cn/s/([A-Za-z0-9]+)", Pattern.CASE_INSENSITIVE).matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    public JSONObject checkLogin() throws Exception {
        ensureCookie();
        HttpUrl url = HttpUrl.parse(BASE + "/member").newBuilder()
                .addQueryParameter("pr", "ucpro")
                .addQueryParameter("fr", "pc")
                .build();
        Request req = baseReq(url.toString(), null, UA_WEB).get().build();
        try (Response resp = http.newCall(req).execute()) {
            String text = body(resp);
            JSONObject obj = new JSONObject(text);
            if (obj.optInt("status", -1) != 200 && obj.optInt("code", -1) != 0) {
                throw new IOException("夸克 Cookie 无效或已过期，请重新登录");
            }
            JSONObject data = obj.optJSONObject("data");
            String nick = "";
            if (data != null) {
                nick = data.optString("nickname", data.optString("nick_name", ""));
            }
            if (!nick.isEmpty()) store.saveQuark(store.getQuarkCookie(), nick);
            return obj;
        }
    }

    public String getShareToken(String pwdId, String passcode) throws Exception {
        ensureCookie();
        JSONObject body = new JSONObject();
        body.put("pwd_id", pwdId);
        body.put("passcode", passcode == null ? "" : passcode);
        HttpUrl url = HttpUrl.parse(SHARE_BASE + "/share/sharepage/token").newBuilder()
                .addQueryParameter("pr", "ucpro")
                .addQueryParameter("fr", "pc")
                .build();
        JSONObject resp = post(url.toString(), body.toString(), UA_WEB);
        JSONObject data = resp.optJSONObject("data");
        if (data == null) throw new IOException("获取夸克分享令牌失败");
        String stoken = data.optString("stoken", "");
        if (stoken.isEmpty()) throw new IOException("分享无效或提取码错误");
        return stoken;
    }

    public List<ShareItem> listShare(String pwdId, String stoken, String pdirFid) throws Exception {
        ensureCookie();
        List<ShareItem> all = new ArrayList<>();
        int page = 1;
        while (true) {
            HttpUrl url = HttpUrl.parse(SHARE_BASE + "/share/sharepage/detail").newBuilder()
                    .addQueryParameter("pr", "ucpro")
                    .addQueryParameter("fr", "pc")
                    .addQueryParameter("pwd_id", pwdId)
                    .addQueryParameter("stoken", stoken)
                    .addQueryParameter("pdir_fid", pdirFid == null ? "0" : pdirFid)
                    .addQueryParameter("_page", String.valueOf(page))
                    .addQueryParameter("_size", "50")
                    .addQueryParameter("_sort", "file_type:asc,file_name:asc")
                    .build();
            Request req = baseReq(url.toString(), null, UA_WEB).get().build();
            try (Response resp = http.newCall(req).execute()) {
                JSONObject obj = new JSONObject(body(resp));
                if (obj.optInt("status", -1) != 200 && obj.optInt("code", -1) != 0) {
                    throw new IOException(obj.optString("message", "列举分享失败"));
                }
                JSONObject data = obj.optJSONObject("data");
                JSONArray list = data != null ? data.optJSONArray("list") : null;
                if (list == null || list.length() == 0) break;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.getJSONObject(i);
                    ShareItem s = new ShareItem();
                    s.fid = it.optString("fid");
                    s.shareFidToken = it.optString("share_fid_token", "");
                    s.name = it.optString("file_name", it.optString("title", "file"));
                    s.size = it.optLong("size", 0);
                    s.dir = it.optInt("dir", 0) == 1 || "1".equals(it.optString("dir"));
                    all.add(s);
                }
                int total = data.optInt("total", list.length());
                if (all.size() >= total || list.length() < 50) break;
                page++;
            }
        }
        return all;
    }

    /** Save share into own Quark (root or given folder). Returns task wait. */
    public void saveShare(String pwdId, String stoken, List<ShareItem> items, String toPdirFid,
                          ProgressListener progress) throws Exception {
        ensureCookie();
        if (items == null || items.isEmpty()) throw new IOException("分享无文件可转存");
        JSONArray fids = new JSONArray();
        JSONArray tokens = new JSONArray();
        for (ShareItem it : items) {
            fids.put(it.fid);
            tokens.put(it.shareFidToken == null ? "" : it.shareFidToken);
        }
        JSONObject body = new JSONObject();
        body.put("fid_list", fids);
        body.put("fid_token_list", tokens);
        body.put("to_pdir_fid", toPdirFid == null ? "0" : toPdirFid);
        body.put("pwd_id", pwdId);
        body.put("stoken", stoken);
        body.put("pdir_fid", "0");
        body.put("scene", "link");
        if (progress != null) progress.onProgress("quark_save", 0, 0, "转存到自己的夸克…");
        HttpUrl url = HttpUrl.parse(SHARE_BASE + "/share/sharepage/save").newBuilder()
                .addQueryParameter("pr", "ucpro")
                .addQueryParameter("fr", "pc")
                .build();
        JSONObject resp = post(url.toString(), body.toString(), UA_WEB);
        JSONObject data = resp.optJSONObject("data");
        String taskId = data != null ? data.optString("task_id", "") : "";
        if (!taskId.isEmpty()) waitTask(taskId, progress);
    }

    public String ensureTempFolder(ProgressListener progress) throws Exception {
        ensureCookie();
        // list root
        HttpUrl listUrl = HttpUrl.parse(BASE + "/file/sort").newBuilder()
                .addQueryParameter("pr", "ucpro")
                .addQueryParameter("fr", "pc")
                .addQueryParameter("pdir_fid", "0")
                .addQueryParameter("_page", "1")
                .addQueryParameter("_size", "100")
                .addQueryParameter("_sort", "file_type:asc,file_name:asc")
                .build();
        Request req = baseReq(listUrl.toString(), null, UA_WEB).get().build();
        try (Response resp = http.newCall(req).execute()) {
            JSONObject obj = new JSONObject(body(resp));
            JSONObject data = obj.optJSONObject("data");
            JSONArray list = data != null ? data.optJSONArray("list") : null;
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.getJSONObject(i);
                    boolean dir = it.optInt("dir", 0) == 1;
                    if (dir && TEMP_FOLDER.equals(it.optString("file_name"))) {
                        return it.optString("fid");
                    }
                }
            }
        }
        if (progress != null) progress.onProgress("prepare", 0, 0, "在夸克创建临时文件夹…");
        JSONObject body = new JSONObject();
        body.put("pdir_fid", "0");
        body.put("file_name", TEMP_FOLDER);
        body.put("dir_path", "");
        body.put("dir_init_lock", false);
        HttpUrl mk = HttpUrl.parse(BASE + "/file").newBuilder()
                .addQueryParameter("pr", "ucpro")
                .addQueryParameter("fr", "pc")
                .build();
        JSONObject created = post(mk.toString(), body.toString(), UA_WEB);
        JSONObject data = created.optJSONObject("data");
        if (data == null) throw new IOException("创建夸克临时文件夹失败");
        return data.optString("fid", data.optString("pdir_fid", ""));
    }

    public List<ShareItem> listFolder(String pdirFid) throws Exception {
        ensureCookie();
        List<ShareItem> all = new ArrayList<>();
        int page = 1;
        while (true) {
            HttpUrl listUrl = HttpUrl.parse(BASE + "/file/sort").newBuilder()
                    .addQueryParameter("pr", "ucpro")
                    .addQueryParameter("fr", "pc")
                    .addQueryParameter("pdir_fid", pdirFid)
                    .addQueryParameter("_page", String.valueOf(page))
                    .addQueryParameter("_size", "50")
                    .addQueryParameter("_sort", "file_type:asc,updated_at:desc")
                    .build();
            Request req = baseReq(listUrl.toString(), null, UA_WEB).get().build();
            try (Response resp = http.newCall(req).execute()) {
                JSONObject obj = new JSONObject(body(resp));
                JSONObject data = obj.optJSONObject("data");
                JSONArray list = data != null ? data.optJSONArray("list") : null;
                if (list == null || list.length() == 0) break;
                for (int i = 0; i < list.length(); i++) {
                    JSONObject it = list.getJSONObject(i);
                    ShareItem s = new ShareItem();
                    s.fid = it.optString("fid");
                    s.name = it.optString("file_name");
                    s.size = it.optLong("size", 0);
                    s.dir = it.optInt("dir", 0) == 1;
                    all.add(s);
                }
                if (list.length() < 50) break;
                page++;
            }
        }
        return all;
    }

    public String getDownloadUrl(String fid) throws Exception {
        ensureCookie();
        JSONObject body = new JSONObject();
        JSONArray fids = new JSONArray();
        fids.put(fid);
        body.put("fids", fids);
        HttpUrl url = HttpUrl.parse(BASE + "/file/download").newBuilder()
                .addQueryParameter("pr", "ucpro")
                .addQueryParameter("fr", "pc")
                .addQueryParameter("sys", "win32")
                .addQueryParameter("ve", "2.5.56")
                .build();
        String ua = UA_WEB;
        for (int attempt = 0; attempt < 2; attempt++) {
            JSONObject resp = post(url.toString(), body.toString(), ua);
            if (resp.optInt("code") == 23018 && attempt == 0) {
                ua = UA_ELECTRON;
                continue;
            }
            if (resp.optInt("status", -1) != 200) {
                throw new IOException(resp.optString("message", "获取下载地址失败"));
            }
            JSONArray data = resp.optJSONArray("data");
            if (data == null || data.length() == 0) throw new IOException("无下载地址");
            String durl = data.getJSONObject(0).optString("download_url", "");
            if (durl.isEmpty()) throw new IOException("下载地址为空");
            return durl;
        }
        throw new IOException("获取下载地址失败");
    }

    public File downloadTo(String downloadUrl, File dest, ProgressListener progress) throws Exception {
        Request req = new Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", UA_WEB)
                .header("Referer", "https://pan.quark.cn/")
                .header("Cookie", store.getQuarkCookie())
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("下载失败 HTTP " + resp.code());
            long total = resp.body() != null ? resp.body().contentLength() : -1;
            File parent = dest.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (InputStream in = resp.body().byteStream();
                 FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null) progress.onProgress("download", done, total > 0 ? total : done, "下载中…");
                }
            }
        }
        return dest;
    }

    private void waitTask(String taskId, ProgressListener progress) throws Exception {
        for (int i = 0; i < 60; i++) {
            HttpUrl url = HttpUrl.parse(BASE + "/task").newBuilder()
                    .addQueryParameter("pr", "ucpro")
                    .addQueryParameter("fr", "pc")
                    .addQueryParameter("task_id", taskId)
                    .addQueryParameter("retry_index", String.valueOf(i))
                    .build();
            Request req = baseReq(url.toString(), null, UA_WEB).get().build();
            try (Response resp = http.newCall(req).execute()) {
                JSONObject obj = new JSONObject(body(resp));
                JSONObject data = obj.optJSONObject("data");
                int status = data != null ? data.optInt("status", 0) : 0;
                // 2 = done typically
                if (status == 2 || status == 3) {
                    if (status == 3) throw new IOException("夸克转存任务失败");
                    return;
                }
                if (progress != null) progress.onProgress("quark_save", i, 60, "等待夸克转存任务…");
            }
            Thread.sleep(1000);
        }
        throw new IOException("夸克转存任务超时");
    }

    private void ensureCookie() throws IOException {
        if (!store.hasQuark()) throw new IOException("未登录夸克网盘");
    }

    private Request.Builder baseReq(String url, String jsonBody, String ua) {
        Request.Builder b = new Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", "https://pan.quark.cn")
                .header("Referer", "https://pan.quark.cn/")
                .header("Cookie", store.getQuarkCookie());
        if (jsonBody != null) {
            b.header("Content-Type", "application/json");
        }
        return b;
    }

    private JSONObject post(String url, String jsonBody, String ua) throws Exception {
        Request req = baseReq(url, jsonBody, ua)
                .post(RequestBody.create(jsonBody, JSON))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            String text = body(resp);
            JSONObject obj = new JSONObject(text);
            int status = obj.optInt("status", -1);
            int code = obj.optInt("code", -1);
            if (status != 200 && code != 0 && code != 23018) {
                throw new IOException(obj.optString("message", "夸克 API 错误 status=" + status));
            }
            return obj;
        }
    }

    private static String body(Response resp) throws IOException {
        String text = resp.body() != null ? resp.body().string() : "";
        if (!resp.isSuccessful() && (text == null || text.isEmpty())) {
            throw new IOException("HTTP " + resp.code());
        }
        return text == null ? "" : text;
    }
}
