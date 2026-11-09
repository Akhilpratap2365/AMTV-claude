package com.applemusic.tvbrowser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    public static final String DEFAULT_URL = "https://music.apple.com/in";

    /** The page is ALWAYS laid out at exactly this many CSS pixels wide (Lyrics button visible). */
    public static final int TARGET_CSS_WIDTH = 1600;

    /**
     * Rendering-resolution cap, as "physical pixels per CSS pixel x 100".
     * 120 -> the page is rendered 1920px wide (1080p) and the GPU scales it up to the screen.
     * That is what keeps a 4K TV with 2 GB RAM smooth (4x fewer pixels to paint & store).
     * Set CAP_RENDER_RESOLUTION = false to render at the full native screen resolution (sharper, heavier).
     */
    private static final boolean CAP_RENDER_RESOLUTION = true;
    private static final int MAX_RENDER_WIDTH = 1920;
    private static final int MAX_CALIBRATION_ATTEMPTS = 6;

    private static final String INJECT_ASSET = "am_inject.js";
    private static final String STATE_WEBVIEW = "webview_state";
    private static final String MEASURE_JS =
            "(window.__applyGuaranteed1600pxViewport ? window.__applyGuaranteed1600pxViewport() : window.innerWidth)";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout rootLayout;
    private WebView webView;
    private TvCursorView cursorView;

    private String injectScript = "";
    private Bundle pendingState;

    private int rootW = 0;
    private int rootH = 0;
    private boolean contentRequested = false;
    private int appliedRenderW = -1;
    private int appliedRenderH = -1;
    private int calibrationAttempts = 0;
    private long lastBackPressTime = 0L;

    private final Runnable cookieFlushRunnable = new Runnable() {
        @Override
        public void run() {
            CookieManager.getInstance().flush();
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "Deprecation"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enableImmersiveFullscreen();

        injectScript = loadAsset(INJECT_ASSET);
        if (savedInstanceState != null) {
            pendingState = savedInstanceState.getBundle(STATE_WEBVIEW);
        }

        int bg = themeBackgroundColor();

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(bg);

        webView = new WebView(this);
        webView.setBackgroundColor(bg);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        configureWebView();

        cursorView = new TvCursorView(this, webView);

        rootLayout.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        rootLayout.addView(cursorView, cursorView.createLayoutParams());

        // "Layout changes": every time the screen / window size changes the 1600px guarantee is re-applied.
        rootLayout.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                int w = r - l;
                int h = b - t;
                if (w > 0 && h > 0 && (w != rootW || h != rootH)) {
                    onRootSizeChanged(w, h);
                }
            }
        });

        setContentView(rootLayout);
        // The root draws the (night-aware) background itself: one less full-screen overdraw.
        getWindow().setBackgroundDrawable(null);
    }

    @SuppressLint({"SetJavaScriptEnabled", "Deprecation"})
    private void configureWebView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            webView.setDefaultFocusHighlightEnabled(false);
        }

        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUserAgentString(buildDesktopUserAgent());
        settings.setTextZoom(100);

        // 1600 CSS px lock: wide viewport ON so the page's <meta viewport width=1600> (injected before the
        // page's own, see am_inject.js) is honoured. user-scalable=no there also stops focus auto-zoom.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }

        configureNativeDarkModeOnly(settings);

        // Run the viewport lock BEFORE the page's own scripts/meta, so the very first layout is already 1600px.
        try {
            if (!injectScript.isEmpty() && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, injectScript,
                        java.util.Collections.singleton("*"));
            }
        } catch (Throwable ignored) {
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                calibrationAttempts = 0;
                view.evaluateJavascript(injectScript, null); // fallback when document-start scripts are unsupported
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                injectAndGuaranteeViewport();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectAndGuaranteeViewport();
                // Coalesced and delayed: CookieManager.flush() blocks on disk I/O.
                handler.removeCallbacks(cookieFlushRunnable);
                handler.postDelayed(cookieFlushRunnable, 4000L);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return shouldBlock(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return shouldBlock(url);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // The renderer was killed (usually out-of-memory on a 2 GB TV). Restart cleanly instead of crashing.
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        recreate();
                    }
                });
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });
    }

    /**
     * Dark mode is NATIVE ONLY: the page follows the system theme through its own prefers-color-scheme CSS.
     * WebView never darkens a page by itself (no algorithmic / forced darkening anywhere).
     */
    @SuppressLint("NewApi")
    private void configureNativeDarkModeOnly(WebSettings settings) {
        boolean webThemeOnly = false;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_AUTO);
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                WebSettingsCompat.setForceDarkStrategy(settings,
                        WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY);
                webThemeOnly = true;
            }
        } catch (Throwable ignored) {
        }

        if (Build.VERSION.SDK_INT >= 33) {
            settings.setAlgorithmicDarkeningAllowed(false);
        } else if (!webThemeOnly && Build.VERSION.SDK_INT >= 29) {
            // Cannot restrict to the page's own theme on this WebView: guarantee nothing is forced.
            settings.setForceDark(WebSettings.FORCE_DARK_OFF);
        }
    }

    private static boolean shouldBlock(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.startsWith("itmss://") || u.startsWith("musics://") || u.startsWith("music://")
                || u.startsWith("intent://") || u.startsWith("itms-apps://");
    }

    private String buildDesktopUserAgent() {
        String version = "124";
        try {
            Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(WebSettings.getDefaultUserAgent(this));
            if (m.find()) version = m.group(1);
        } catch (Throwable ignored) {
        }
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/" + version + ".0.0.0 Safari/537.36";
    }

    private int themeBackgroundColor() {
        try {
            return Build.VERSION.SDK_INT >= 23
                    ? getColor(R.color.app_bg)
                    : getResources().getColor(R.color.app_bg);
        } catch (Throwable t) {
            return Color.BLACK;
        }
    }

    private String loadAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------- 1600px viewport guarantee

    private void onRootSizeChanged(int w, int h) {
        rootW = w;
        rootH = h;
        cursorView.setViewport(w, h);
        applyGeometry();

        if (!contentRequested) {
            contentRequested = true;
            boolean restored = false;
            if (pendingState != null) {
                try {
                    restored = webView.restoreState(pendingState) != null;
                } catch (Throwable ignored) {
                }
                pendingState = null;
            }
            if (!restored) {
                webView.loadUrl(DEFAULT_URL);
            }
        } else {
            applyGuaranteed1600pxViewport();
        }
    }

    /**
     * Sizes the WebView; the page layout is exactly
     * 1600 css px wide on phones, 1080p TVs and 4K TVs (the viewport meta does that; this only picks the
     * render resolution and scales it up on screen).
     */
    private void applyGeometry() {
        if (rootW <= 0 || rootH <= 0) return;

        // Render width: 1920 (1080p) when capped, otherwise the real screen width. The GPU scales it to the screen.
        int renderW = CAP_RENDER_RESOLUTION ? Math.min(rootW, MAX_RENDER_WIDTH) : rootW;
        float scale = rootW / (float) renderW;
        int renderH = Math.max(1, Math.round(rootH / scale));

        if (renderW != appliedRenderW || renderH != appliedRenderH) {
            appliedRenderW = renderW;
            appliedRenderH = renderH;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) webView.getLayoutParams();
            lp.width = renderW;
            lp.height = renderH;
            webView.setLayoutParams(lp);
        }
        webView.setPivotX(0f);
        webView.setPivotY(0f);
        webView.setScaleX(scale);
        webView.setScaleY(scale);
        cursorView.setWebScale(scale);
    }

    private void injectAndGuaranteeViewport() {
        if (webView == null) return;
        if (!injectScript.isEmpty()) {
            webView.evaluateJavascript(injectScript, null);
        }
        applyGuaranteed1600pxViewport();
    }

    /**
     * Guaranteed 1600px desktop width (Lyrics button always available). Called on every layout change
     * and on every page load. Re-applies the native geometry, makes the page agree, then MEASURES the
     * real window.innerWidth and self-corrects if it is not 1600.
     */
    private void applyGuaranteed1600pxViewport() {
        if (webView == null || rootW <= 0 || rootH <= 0) return;
        applyGeometry();
        webView.evaluateJavascript(MEASURE_JS, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                onLayoutWidthMeasured(value);
            }
        });
    }

    private void onLayoutWidthMeasured(String raw) {
        if (raw == null) return;
        int w;
        try {
            w = (int) Math.round(Double.parseDouble(raw.trim()));
        } catch (Exception e) {
            return;
        }
        if (w <= 0 || Math.abs(w - TARGET_CSS_WIDTH) <= 1) return;
        if (calibrationAttempts >= MAX_CALIBRATION_ATTEMPTS) return;
        calibrationAttempts++;
        // Not 1600 yet: re-assert the viewport meta and measure again.
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                applyGuaranteed1600pxViewport();
            }
        }, 300L);
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();

        if (cursorView != null && cursorView.handleKeyEvent(event)) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastBackPressTime < 2000) {
                        finish();
                    } else {
                        lastBackPressTime = now;
                        Toast.makeText(this, "Press Back again to exit Apple Music", Toast.LENGTH_SHORT).show();
                    }
                }
            }
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                triggerAppleMusicMediaAction("playPause");
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                triggerAppleMusicMediaAction("next");
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                triggerAppleMusicMediaAction("previous");
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void triggerAppleMusicMediaAction(String action) {
        if (webView == null) return;
        String js = "(function(){try{var mk=window.MusicKit&&window.MusicKit.getInstance?window.MusicKit.getInstance():null;"
                + "if(!mk)return;"
                + "if('" + action + "'==='playPause'){mk.isPlaying?mk.pause():mk.play();return;}"
                + "if('" + action + "'==='next'){mk.skipToNextItem();return;}"
                + "if('" + action + "'==='previous'){mk.skipToPreviousItem();}"
                + "}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        try {
            Bundle state = new Bundle();
            if (webView != null && webView.saveState(state) != null) {
                outState.putBundle(STATE_WEBVIEW, state);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enableImmersiveFullscreen();
        } else if (cursorView != null) {
            cursorView.resetKeys();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enableImmersiveFullscreen();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (cursorView != null) cursorView.resetKeys();
        if (webView != null) {
            handler.removeCallbacks(cookieFlushRunnable);
            CookieManager.getInstance().flush();
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (cursorView != null) cursorView.release();
        if (webView != null) {
            if (rootLayout != null) rootLayout.removeView(webView);
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void enableImmersiveFullscreen() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
    }
}
