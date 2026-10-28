package com.shicheng.pantransfer.transfer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.shicheng.pantransfer.api.AliyunApi;
import com.shicheng.pantransfer.api.QuarkApi;
import com.shicheng.pantransfer.store.SecureStore;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates same-pan and cross-pan transfers. Soft limit: warn >500MB, hard block >2GB.
 */
public class TransferManager {
    private static final String TAG = "TransferManager";
    public static final long WARN_BYTES = 500L * 1024 * 1024;
    public static final long BLOCK_BYTES = 2L * 1024 * 1024 * 1024;

    public interface Listener {
        void onProgress(String json);
        void onDone(String json);
        void onError(String json);
    }

    private final Context app;
    private final SecureStore store;
    private final AliyunApi aliyun;
    private final QuarkApi quark;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancel = new AtomicBoolean(false);

    public TransferManager(Context context) {
        this.app = context.getApplicationContext();
        this.store = new SecureStore(app);
        this.aliyun = new AliyunApi(store);
        this.quark = new QuarkApi(store);
    }

    public SecureStore getStore() { return store; }
    public AliyunApi getAliyun() { return aliyun; }
    public QuarkApi getQuark() { return quark; }
    public boolean isRunning() { return running.get(); }

    public void cancel() { cancel.set(true); }

    public void start(String provider, String link, String code, boolean forceLarge, Listener listener) {
        if (!running.compareAndSet(false, true)) {
            emitError(listener, "已有转存任务进行中");
            return;
        }
        cancel.set(false);
        exec.execute(() -> {
            try {
                String result;
                if ("ali".equals(provider)) {
                    result = runAliSame(link, code, listener);
                } else if ("quark".equals(provider)) {
                    result = runQuarkToAli(link, code, forceLarge, listener);
                } else {
                    throw new Exception("暂不支持该来源盘的自动转存（v1.2 优先夸克→阿里、阿里同盘）");
                }
                final String r = result;
                main.post(() -> {
                    running.set(false);
                    if (listener != null) listener.onDone(r);
                });
            } catch (Exception e) {
                Log.e(TAG, "transfer failed", e);
                running.set(false);
                emitError(listener, e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    private String runAliSame(String link, String code, Listener listener) throws Exception {
        if (!store.hasAliyun()) throw new Exception("请先登录阿里云盘");
        progress(listener, "ali_same", 0, 0, "开始阿里同盘转存…");
        int n = aliyun.saveShareToDrive(link, code, (stage, done, total, detail) ->
                progress(listener, stage, done, total, detail));
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("mode", "ali_same");
        out.put("count", n);
        out.put("message", "已转存 " + n + " 项到阿里云盘「" + AliyunApi.SAVE_FOLDER + "」");
        return out.toString();
    }

    private String runQuarkToAli(String link, String code, boolean forceLarge, Listener listener) throws Exception {
        if (!store.hasQuark()) throw new Exception("请先登录夸克");
        if (!store.hasAliyun()) throw new Exception("请先登录阿里云盘（目标盘）");
        checkCancel();
        String pwdId = QuarkApi.parsePwdId(link);
        if (pwdId == null) throw new Exception("无法解析夸克分享链接");

        progress(listener, "quark_token", 0, 0, "解析夸克分享…");
        String stoken = quark.getShareToken(pwdId, code);
        checkCancel();
        List<QuarkApi.ShareItem> items = quark.listShare(pwdId, stoken, "0");
        if (items.isEmpty()) throw new Exception("夸克分享为空或无法访问");

        // Flatten: only top-level files for v1; folders saved wholly then listed
        long totalSize = 0;
        for (QuarkApi.ShareItem it : items) {
            if (!it.dir) totalSize += Math.max(0, it.size);
        }
        if (totalSize > BLOCK_BYTES) {
            throw new Exception("文件约 " + formatSize(totalSize) + "，超过手机端硬限制 2GB。请在电脑端处理或缩小范围。");
        }
        if (totalSize > WARN_BYTES && !forceLarge) {
            JSONObject warn = new JSONObject();
            warn.put("ok", false);
            warn.put("needConfirm", true);
            warn.put("size", totalSize);
            warn.put("message", "合计约 " + formatSize(totalSize) + "，手机下载+上传会较慢且耗流量。确认继续？");
            // Signal as error with needConfirm for UI to re-call with force
            throw new NeedConfirmException(warn.toString());
        }

        progress(listener, "quark_save", 0, items.size(), "先转存到自己的夸克（以获取下载权限）…");
        String tempFid = quark.ensureTempFolder((s, d, t, det) -> progress(listener, s, d, t, det));
        checkCancel();
        // Snapshot existing fids in temp
        List<QuarkApi.ShareItem> before = quark.listFolder(tempFid);
        java.util.HashSet<String> beforeIds = new java.util.HashSet<>();
        for (QuarkApi.ShareItem b : before) beforeIds.add(b.fid);

        quark.saveShare(pwdId, stoken, items, tempFid, (s, d, t, det) -> progress(listener, s, d, t, det));
        checkCancel();
        Thread.sleep(800);
        List<QuarkApi.ShareItem> after = quark.listFolder(tempFid);
        List<QuarkApi.ShareItem> saved = new ArrayList<>();
        for (QuarkApi.ShareItem a : after) {
            if (!beforeIds.contains(a.fid)) saved.add(a);
        }
        if (saved.isEmpty()) {
            // fallback: use all non-dir in temp (best effort)
            for (QuarkApi.ShareItem a : after) if (!a.dir) saved.add(a);
        }
        if (saved.isEmpty()) throw new Exception("夸克转存后未找到文件，请检查分享或 Cookie");

        String aliParent = aliyun.ensureSaveFolder((s, d, t, det) -> progress(listener, s, d, t, det));
        File cacheDir = new File(app.getCacheDir(), "xfer");
        if (!cacheDir.exists()) cacheDir.mkdirs();

        int uploaded = 0;
        List<String> names = new ArrayList<>();
        for (QuarkApi.ShareItem file : saved) {
            checkCancel();
            if (file.dir) {
                // Recurse one level of files inside folder
                List<QuarkApi.ShareItem> children = quark.listFolder(file.fid);
                for (QuarkApi.ShareItem c : children) {
                    if (c.dir) continue;
                    uploaded += uploadOne(c, aliParent, cacheDir, listener);
                    names.add(c.name);
                }
            } else {
                uploaded += uploadOne(file, aliParent, cacheDir, listener);
                names.add(file.name);
            }
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("mode", "quark_to_ali");
        out.put("count", uploaded);
        out.put("files", names.size());
        out.put("message", "已跨盘上传 " + uploaded + " 个文件到阿里云盘「" + AliyunApi.SAVE_FOLDER + "」（经夸克下载，非秒传）");
        progress(listener, "done", uploaded, uploaded, out.optString("message"));
        return out.toString();
    }

    private int uploadOne(QuarkApi.ShareItem file, String aliParent, File cacheDir, Listener listener) throws Exception {
        if (file.size > BLOCK_BYTES) {
            progress(listener, "skip", 0, 0, "跳过过大文件 " + file.name);
            return 0;
        }
        progress(listener, "download", 0, file.size, "下载 " + file.name);
        String durl = quark.getDownloadUrl(file.fid);
        checkCancel();
        File local = new File(cacheDir, sanitize(file.name));
        try {
            quark.downloadTo(durl, local, (s, d, t, det) -> progress(listener, s, d, t, det));
            checkCancel();
            progress(listener, "upload", 0, local.length(), "上传到阿里 " + file.name);
            aliyun.uploadFile(local, aliParent, (s, d, t, det) -> progress(listener, s, d, t, det));
            return 1;
        } finally {
            if (local.exists()) {
                //noinspection ResultOfMethodCallIgnored
                local.delete();
            }
        }
    }

    private void checkCancel() throws Exception {
        if (cancel.get()) throw new Exception("已取消");
    }

    private void progress(Listener listener, String stage, long done, long total, String detail) {
        try {
            JSONObject o = new JSONObject();
            o.put("stage", stage);
            o.put("done", done);
            o.put("total", total);
            o.put("detail", detail == null ? "" : detail);
            o.put("percent", total > 0 ? Math.min(100, (int) (done * 100 / total)) : 0);
            String json = o.toString();
            main.post(() -> {
                if (listener != null) listener.onProgress(json);
            });
        } catch (Exception ignored) {}
    }

    private void emitError(Listener listener, String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("message", msg == null ? "未知错误" : msg);
            if (msg != null && msg.startsWith("{") && msg.contains("needConfirm")) {
                // already json from NeedConfirmException
                final String m = msg;
                main.post(() -> { if (listener != null) listener.onError(m); });
                return;
            }
            String json = o.toString();
            main.post(() -> { if (listener != null) listener.onError(json); });
        } catch (Exception e) {
            main.post(() -> { if (listener != null) listener.onError("{\"ok\":false,\"message\":\"error\"}"); });
        }
    }

    public static class NeedConfirmException extends Exception {
        public NeedConfirmException(String json) { super(json); }
    }

    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) return "file.bin";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
