package com.shicheng.pantransfer;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.shicheng.pantransfer.api.AliyunApi;
import com.shicheng.pantransfer.api.QuarkApi;
import com.shicheng.pantransfer.store.SecureStore;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-app login:
 * - Aliyun: WebView (poll localStorage token) OR paste refresh_token
 * - Quark: WebView cookie capture for pan.quark.cn
 */
public class LoginActivity extends AppCompatActivity {
    public static final String EXTRA_PROVIDER = "provider"; // ali | quark
    public static final String RESULT_OK_PROVIDER = "provider";

    private String provider;
    private WebView webView;
    private TextView status;
    private EditText tokenInput;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private SecureStore store;
    private boolean finished;
    private final Runnable pollAliToken = new Runnable() {
        @Override
        public void run() {
            if (finished || webView == null) return;
            webView.evaluateJavascript(
                    "(function(){try{"
                            + "var t=localStorage.getItem('token')||localStorage.getItem('aliyundrive_token')||'';"
                            + "if(!t){var k=Object.keys(localStorage);for(var i=0;i<k.length;i++){var v=localStorage.getItem(k[i])||'';"
                            + "if(v.indexOf('refresh_token')>=0&&v.indexOf('access_token')>=0){t=v;break;}}}"
                            + "return t;"
                            + "}catch(e){return '';}})();",
                    value -> {
                        if (finished) return;
                        String raw = unwrapJs(value);
                        if (raw != null && raw.contains("refresh_token")) {
                            tryExtractAliToken(raw);
                        } else {
                            handler.postDelayed(this, 1500);
                        }
                    }
            );
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SecureStore(this);
        provider = getIntent().getStringExtra(EXTRA_PROVIDER);
        if (provider == null) provider = "ali";

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0F172A"));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ali".equals(provider) ? "登录阿里云盘" : "登录夸克网盘");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        status = new TextView(this);
        status.setTextColor(Color.parseColor("#94A3B8"));
        status.setTextSize(13);
        status.setText("ali".equals(provider)
                ? "请在下方完成网页登录（扫码/短信）。登录成功后会自动抓取 refresh_token；也可粘贴 token。"
                : "请在下方登录 pan.quark.cn，登录完成后点「完成并保存 Cookie」。");
        status.setPadding(0, 0, 0, dp(8));
        root.addView(status);

        if ("ali".equals(provider)) {
            tokenInput = new EditText(this);
            tokenInput.setHint("或粘贴 refresh_token（一串字符）");
            tokenInput.setHintTextColor(Color.parseColor("#64748B"));
            tokenInput.setTextColor(Color.WHITE);
            tokenInput.setBackgroundColor(Color.parseColor("#1E293B"));
            tokenInput.setPadding(dp(12), dp(10), dp(12), dp(10));
            tokenInput.setMinHeight(dp(48));
            root.addView(tokenInput);

            Button useToken = btn("使用粘贴的 refresh_token 登录");
            useToken.setOnClickListener(v -> {
                String t = tokenInput.getText() != null ? tokenInput.getText().toString().trim() : "";
                if (t.isEmpty()) {
                    toast("请粘贴 refresh_token");
                    return;
                }
                // allow pasted JSON
                if (t.startsWith("{")) {
                    tryExtractAliToken(t);
                } else {
                    loginAliWithRefresh(t);
                }
            });
            root.addView(useToken);
        }

        FrameLayout webWrap = new FrameLayout(this);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        wp.topMargin = dp(8);
        webWrap.setLayoutParams(wp);
        webWrap.setBackgroundColor(Color.WHITE);

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if ("ali".equals(provider)) {
                    handler.removeCallbacks(pollAliToken);
                    handler.postDelayed(pollAliToken, 800);
                }
            }
        });
        webWrap.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(webWrap);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);

        if ("quark".equals(provider)) {
            Button done = btn("完成并保存 Cookie");
            done.setOnClickListener(v -> captureQuarkCookie());
            actions.addView(done, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        Button cancel = btn("取消");
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        actions.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions);

        setContentView(root);

        if ("ali".equals(provider)) {
            webView.loadUrl("https://www.alipan.com/");
        } else {
            webView.loadUrl("https://pan.quark.cn/");
        }
    }

    private void tryExtractAliToken(String raw) {
        try {
            String refresh = null;
            String trimmed = raw.trim();
            if (trimmed.startsWith("{")) {
                JSONObject o = new JSONObject(trimmed);
                refresh = o.optString("refresh_token", null);
                if (refresh == null || refresh.isEmpty()) {
                    // nested
                    if (o.has("data") && o.optJSONObject("data") != null) {
                        refresh = o.getJSONObject("data").optString("refresh_token", null);
                    }
                }
            } else if (trimmed.length() > 20) {
                refresh = trimmed;
            }
            if (refresh != null && refresh.length() > 20) {
                loginAliWithRefresh(refresh);
            }
        } catch (Exception e) {
            // keep polling
            handler.postDelayed(pollAliToken, 1500);
        }
    }

    private void loginAliWithRefresh(String refresh) {
        if (finished) return;
        status.setText("正在校验 refresh_token…");
        exec.execute(() -> {
            try {
                AliyunApi api = new AliyunApi(store);
                AliyunApi.UserInfo info = api.refreshAndLoadUser(refresh);
                handler.post(() -> {
                    finished = true;
                    toast("阿里云盘已登录：" + info.nickName);
                    Intent data = new Intent();
                    data.putExtra(RESULT_OK_PROVIDER, "ali");
                    setResult(RESULT_OK, data);
                    finish();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    status.setText("登录失败：" + e.getMessage());
                    toast("登录失败：" + e.getMessage());
                    handler.postDelayed(pollAliToken, 2000);
                });
            }
        });
    }

    private void captureQuarkCookie() {
        CookieManager cm = CookieManager.getInstance();
        cm.flush();
        String c1 = cm.getCookie("https://pan.quark.cn");
        String c2 = cm.getCookie("https://drive-pc.quark.cn");
        String c3 = cm.getCookie("https://drive.quark.cn");
        String merged = mergeCookies(c1, c2, c3);
        if (merged == null || merged.isEmpty() || !merged.contains("__")) {
            // still try if has any cookie
            if (merged == null || merged.length() < 10) {
                toast("未捕获到 Cookie，请确认已登录");
                return;
            }
        }
        status.setText("正在校验夸克 Cookie…");
        final String cookie = merged;
        exec.execute(() -> {
            try {
                store.saveQuark(cookie, "");
                QuarkApi api = new QuarkApi(store);
                api.checkLogin();
                handler.post(() -> {
                    finished = true;
                    toast("夸克已登录");
                    Intent data = new Intent();
                    data.putExtra(RESULT_OK_PROVIDER, "quark");
                    setResult(RESULT_OK, data);
                    finish();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    status.setText("Cookie 无效：" + e.getMessage());
                    toast("校验失败：" + e.getMessage());
                });
            }
        });
    }

    private static String mergeCookies(String... parts) {
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        for (String part : parts) {
            if (part == null || part.isEmpty()) continue;
            for (String seg : part.split(";")) {
                String s = seg.trim();
                int eq = s.indexOf('=');
                if (eq > 0) {
                    map.put(s.substring(0, eq).trim(), s.substring(eq + 1).trim());
                }
            }
        }
        if (map.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private static String unwrapJs(String value) {
        if (value == null || "null".equals(value)) return null;
        String v = value;
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n");
        }
        return v;
    }

    private Button btn(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.parseColor("#2563EB"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(pollAliToken);
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        exec.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
