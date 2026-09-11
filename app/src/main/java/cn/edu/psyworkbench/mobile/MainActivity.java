package cn.edu.psyworkbench.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.biometrics.BiometricPrompt;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.net.URI;

public class MainActivity extends Activity {
    private static final String DEFAULT_SERVER = "http://111.230.150.161";
    private static final int FILE_CHOOSER_REQUEST = 9001;
    private static final int DEVICE_CREDENTIAL_REQUEST = 9002;

    private WebView webView;
    private ProgressBar progress;
    private ValueCallback<Uri[]> fileCallback;
    private SharedPreferences prefs;
    private CancellationSignal biometricCancel;
    private boolean loaded = false;
    private boolean credentialPromptOpen = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("psy_mobile", MODE_PRIVATE);
        webView = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        configureWebView();
        authenticateThenLoad();
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " PsyWorkbenchAndroid/3.6.1");
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidApp");
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progress.setVisibility(View.VISIBLE);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (isAllowedHost(u)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); }
                catch (Exception ignored) { Toast.makeText(MainActivity.this, "无法打开外部链接", Toast.LENGTH_SHORT).show(); }
                return true;
            }
            @Override public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                progress.setVisibility(View.GONE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try { startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST); }
                catch (Exception e) {
                    fileCallback = null;
                    Toast.makeText(MainActivity.this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });

        webView.setDownloadListener((url, userAgent, disposition, mime, length) -> download(url, userAgent, disposition, mime));
    }

    private boolean isAllowedHost(Uri uri) {
        try {
            URI base = new URI(serverBase());
            return uri.getHost() != null
                    && uri.getHost().equalsIgnoreCase(base.getHost())
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception e) { return false; }
    }

    private void authenticateThenLoad() {
        if (!prefs.getBoolean("biometric_enabled", true) || android.os.Build.VERSION.SDK_INT < 28) {
            loadMobile();
            return;
        }
        try {
            BiometricPrompt prompt = new BiometricPrompt.Builder(this)
                    .setTitle("解锁心理老师工作台")
                    .setSubtitle("请验证指纹/面容后进入学生心理数据")
                    .setNegativeButton("使用系统解锁", getMainExecutor(), (dialog, which) -> deviceCredentialOrLoad())
                    .build();
            biometricCancel = new CancellationSignal();
            prompt.authenticate(biometricCancel, getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) { loadMobile(); }
                @Override public void onAuthenticationError(int errorCode, CharSequence errString) { deviceCredentialOrLoad(); }
            });
        } catch (Exception e) { deviceCredentialOrLoad(); }
    }

    private void deviceCredentialOrLoad() {
        if (loaded || credentialPromptOpen) return;
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isDeviceSecure()) {
            Intent i = km.createConfirmDeviceCredentialIntent("解锁心理老师工作台", "验证手机锁屏密码后继续");
            if (i != null) {
                credentialPromptOpen = true;
                startActivityForResult(i, DEVICE_CREDENTIAL_REQUEST);
                return;
            }
        }
        loadMobile();
    }

    private String serverBase() {
        String v = prefs.getString("server_url", DEFAULT_SERVER);
        if (v == null || v.trim().isEmpty()) v = DEFAULT_SERVER;
        v = v.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private void loadMobile() {
        if (loaded) return;
        loaded = true;
        credentialPromptOpen = false;
        runOnUiThread(() -> webView.loadUrl(serverBase() + "/mobile/"));
    }

    private void download(String url, String userAgent, String disposition, String mime) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) req.addRequestHeader("Cookie", cookie);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            if (mime != null && !mime.isEmpty()) req.setMimeType(mime);
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            String name = android.webkit.URLUtil.guessFileName(url, disposition, mime);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            dm.enqueue(req);
            Toast.makeText(this, "文件正在下载到“下载”目录", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showServerSettings() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(serverBase());
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("服务器地址")
                .setMessage("当前连接学校心理工作台服务器。以后配置域名 + HTTPS 后，可在这里切换到新地址。")
                .setView(input)
                .setPositiveButton("保存并重连", (dialog, which) -> {
                    String v = input.getText().toString().trim();
                    if (!(v.startsWith("http://") || v.startsWith("https://"))) {
                        Toast.makeText(this, "请输入以 http:// 或 https:// 开头的地址", Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs.edit().putString("server_url", v).apply();
                    CookieManager.getInstance().removeAllCookies(null);
                    loaded = false;
                    loadMobile();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public class AndroidBridge {
        @JavascriptInterface public void openServerSettings() { runOnUiThread(() -> showServerSettings()); }
        @JavascriptInterface public void reloadApp() { runOnUiThread(() -> webView.reload()); }
        @JavascriptInterface public String appVersion() { return "3.6.1"; }
        @JavascriptInterface public boolean biometricEnabled() { return prefs.getBoolean("biometric_enabled", true); }
        @JavascriptInterface public void setBiometricEnabled(boolean enabled) { prefs.edit().putBoolean("biometric_enabled", enabled).apply(); }
        @JavascriptInterface public boolean isNativeApp() { return true; }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
        } else if (requestCode == DEVICE_CREDENTIAL_REQUEST) {
            credentialPromptOpen = false;
            if (resultCode == RESULT_OK) loadMobile(); else finish();
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (biometricCancel != null) biometricCancel.cancel();
        if (webView != null) { webView.stopLoading(); webView.destroy(); }
        super.onDestroy();
    }
}
