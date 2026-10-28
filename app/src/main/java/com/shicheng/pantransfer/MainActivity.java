package com.shicheng.pantransfer;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.shicheng.pantransfer.api.AliyunApi;
import com.shicheng.pantransfer.api.QuarkApi;
import com.shicheng.pantransfer.store.SecureStore;
import com.shicheng.pantransfer.transfer.TransferManager;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * WebView SPA shell + native bridge for account login and real transfers.
 */
public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private String pendingSharedText;
    private TransferManager transferManager;
    private SecureStore store;
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<Intent> loginLauncher;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new SecureStore(this);
        transferManager = new TransferManager(this);

        loginLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    notifyAccountsChanged();
                    if (result.getResultCode() == RESULT_OK) {
                        Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show();
                    }
                });

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setTextZoom(100);

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return false;
                String scheme = uri.getScheme();
                if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
                    openExternal(uri.toString());
                    return true;
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                notifyAccountsChanged();
            }
        });

        webView.addJavascriptInterface(new JsBridge(), "AndroidBridge");

        pendingSharedText = extractSharedText(getIntent());
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String text = extractSharedText(intent);
        if (text != null && webView != null) {
            injectSharedText(text);
        }
    }

    private String extractSharedText(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_SEND.equals(intent.getAction())
                && intent.getType() != null
                && intent.getType().startsWith("text/")) {
            return intent.getStringExtra(Intent.EXTRA_TEXT);
        }
        return null;
    }

    private void injectSharedText(String text) {
        if (text == null) return;
        String escaped = jsonEscape(text);
        webView.evaluateJavascript("window.__onSharedText && window.__onSharedText(" + escaped + ")", null);
    }

    private void openExternal(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接，请确认已安装对应网盘 App", Toast.LENGTH_LONG).show();
        }
    }

    private void notifyAccountsChanged() {
        if (webView == null) return;
        String json = buildAccountStatusJson();
        webView.evaluateJavascript(
                "window.__onAccountsChanged && window.__onAccountsChanged(" + jsonEscape(json) + ")", null);
    }

    private String buildAccountStatusJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("aliLoggedIn", store.hasAliyun());
            o.put("aliNick", store.getAliNick());
            o.put("aliDriveId", store.getAliDriveId());
            o.put("quarkLoggedIn", store.hasQuark());
            o.put("quarkNick", store.getQuarkNick());
            o.put("transferRunning", transferManager.isRunning());
            o.put("version", "1.2.0");
            o.put("warnBytes", TransferManager.WARN_BYTES);
            o.put("blockBytes", TransferManager.BLOCK_BYTES);
            return o.toString();
        } catch (Exception e) {
            return "{\"aliLoggedIn\":false,\"quarkLoggedIn\":false}";
        }
    }

    private void emitJs(String fn, String jsonPayload) {
        runOnUiThread(() -> {
            if (webView == null) return;
            webView.evaluateJavascript(
                    "window." + fn + " && window." + fn + "(" + jsonEscape(jsonPayload) + ")", null);
        });
    }

    private static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        try {
            return JSONObject.quote(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    public class JsBridge {
        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(url));
        }

        @JavascriptInterface
        public void copyText(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("pan-transfer", text == null ? "" : text));
                    Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void toast(String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg == null ? "" : msg, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public String getPendingShare() {
            String t = pendingSharedText;
            pendingSharedText = null;
            return t == null ? "" : t;
        }

        @JavascriptInterface
        public String getAppVersion() {
            return "1.2.0";
        }

        @JavascriptInterface
        public String getAccountStatus() {
            return buildAccountStatusJson();
        }

        @JavascriptInterface
        public void loginAliyun() {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, LoginActivity.class);
                i.putExtra(LoginActivity.EXTRA_PROVIDER, "ali");
                loginLauncher.launch(i);
            });
        }

        @JavascriptInterface
        public void loginAliyunToken(String refreshToken) {
            bg.execute(() -> {
                try {
                    String t = refreshToken == null ? "" : refreshToken.trim();
                    if (t.isEmpty()) {
                        emitJs("__onTransferError", "{\"ok\":false,\"message\":\"refresh_token 为空\"}");
                        return;
                    }
                    if (t.startsWith("{")) {
                        JSONObject o = new JSONObject(t);
                        t = o.optString("refresh_token", t);
                    }
                    AliyunApi api = new AliyunApi(store);
                    AliyunApi.UserInfo info = api.refreshAndLoadUser(t);
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "阿里云盘已登录：" + info.nickName, Toast.LENGTH_SHORT).show();
                        notifyAccountsChanged();
                    });
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "登录失败" : e.getMessage();
                    emitJs("__onTransferError", "{\"ok\":false,\"message\":" + jsonEscape(msg) + "}");
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
                }
            });
        }

        @JavascriptInterface
        public void logoutAliyun() {
            store.clearAliyun();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "已退出阿里云盘", Toast.LENGTH_SHORT).show();
                notifyAccountsChanged();
            });
        }

        @JavascriptInterface
        public void loginQuark() {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, LoginActivity.class);
                i.putExtra(LoginActivity.EXTRA_PROVIDER, "quark");
                loginLauncher.launch(i);
            });
        }

        @JavascriptInterface
        public void logoutQuark() {
            store.clearQuark();
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, "已退出夸克", Toast.LENGTH_SHORT).show();
                notifyAccountsChanged();
            });
        }

        @JavascriptInterface
        public void startTransfer(String provider, String link, String code, boolean forceLarge) {
            transferManager.start(provider, link, code == null ? "" : code, forceLarge,
                    new TransferManager.Listener() {
                        @Override
                        public void onProgress(String json) {
                            emitJs("__onTransferProgress", json);
                        }

                        @Override
                        public void onDone(String json) {
                            emitJs("__onTransferDone", json);
                            runOnUiThread(MainActivity.this::notifyAccountsChanged);
                        }

                        @Override
                        public void onError(String json) {
                            emitJs("__onTransferError", json);
                            runOnUiThread(MainActivity.this::notifyAccountsChanged);
                        }
                    });
        }

        @JavascriptInterface
        public void cancelTransfer() {
            transferManager.cancel();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        bg.shutdownNow();
        super.onDestroy();
    }
}
