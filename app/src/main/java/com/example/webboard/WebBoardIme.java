package com.example.webboard;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import java.util.Locale;
import org.json.JSONObject;

/**
 * WebBoard: QWERTZ keyboard with an embedded browser.
 * The URL/search field is edited by WebBoard's own keys, so Android does not
 * need to open a second keyboard when the address field is selected.
 *
 * Visual appearance (colors, corner radius, spacing, key size, font size,
 * press effect) is fully configurable through {@link SettingsActivity} and
 * stored via {@link KeyboardTheme}. Changes are picked up live through a
 * SharedPreferences listener and are always re-applied when the keyboard
 * is shown.
 */
public class WebBoardIme extends InputMethodService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    /** Base height (dp) of the key rows area at the default size scale (1.0). */
    private static final int BASE_KEYS_HEIGHT_DP = 200;

    /**
     * Backspace long-press-to-repeat timing. BACKSPACE_HOLD_DELAY_MS is how
     * long the key must be held before auto-repeat kicks in; the repeat
     * interval itself then starts fast and accelerates further, down to a
     * near-instant floor, for a snappy "hold to clear the field" feel.
     */
    private static final long BACKSPACE_HOLD_DELAY_MS = 350;
    private static final long BACKSPACE_REPEAT_START_MS = 90;
    private static final long BACKSPACE_REPEAT_MIN_MS = 20;
    private static final long BACKSPACE_REPEAT_ACCEL_MS = 8;

    /** The browser always returns to this page whenever the keyboard is reopened. */
    private static final String DEFAULT_BROWSER_URL = "https://www.google.com/";

    private LinearLayout root, keys;
    private WebView web;
    private EditText url;
    private Button goButton, backButton, forwardButton, reloadButton, restartButton;
    private ImageButton settingsButton;
    private boolean shift = false;
    private boolean symbols = false;
    private boolean inputViewActive = false;
    private String lastWebUrl = DEFAULT_BROWSER_URL;

    private KeyboardTheme theme;

    /** Drives the backspace repeat-delete-on-hold behavior. Always cleared on hide/destroy. */
    private final Handler backspaceHandler = new Handler(Looper.getMainLooper());
    private boolean backspaceRepeating = false;
    private long backspaceRepeatDelay = BACKSPACE_REPEAT_START_MS;
    private final Runnable backspaceRunnable = new Runnable() {
        @Override public void run() {
            if (!backspaceRepeating) return;
            delete();
            backspaceRepeatDelay = Math.max(BACKSPACE_REPEAT_MIN_MS, backspaceRepeatDelay - BACKSPACE_REPEAT_ACCEL_MS);
            backspaceHandler.postDelayed(this, backspaceRepeatDelay);
        }
    };

    /**
     * Set by onRenderProcessGone() when the WebView's Chromium renderer dies
     * (e.g. killed by the OS under memory pressure). If that happens while
     * the keyboard is hidden, recovery can't run immediately, so this flag
     * makes ensureBrowserActive() repair the browser the next time the
     * keyboard is shown — instead of silently leaving a dead WebView behind,
     * which is what previously caused the browser to come back blank.
     */
    private boolean browserRendererDead = false;

    @Override public void onCreate() {
        super.onCreate();
        Window w = getWindow().getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        theme = KeyboardTheme.load(this);
        KeyboardTheme.prefs(this).registerOnSharedPreferenceChangeListener(this);
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        inputViewActive = false;
        stopBackspaceRepeat();
        // A quick pause, not a teardown: this fires very often for entirely
        // routine reasons (e.g. the user just tapping away from the text
        // field so the keyboard slides down), so anything more expensive
        // here would make that ordinary interaction feel laggy.
        if (web != null) {
            try { web.onPause(); } catch (Throwable ignored) {}
        }
        super.onFinishInputView(finishingInput);
    }

    @Override public void onWindowHidden() {
        stopBackspaceRepeat();
        inputViewActive = false;
        if (web != null) {
            try { web.onPause(); } catch (Throwable ignored) {}
        }
        super.onWindowHidden();
    }

    @Override public void onWindowShown() {
        super.onWindowShown();
        inputViewActive = true;
        ensureBrowserActive();
    }

    /** Tears down and detaches the current WebView, if any, without leaking it. Only used for a full service shutdown. */
    private void destroyBrowser() {
        if (web == null) return;
        WebView old = web;
        web = null;
        try {
            old.stopLoading();
            old.onPause();
            old.setWebViewClient(null);
            old.clearFocus();
        } catch (Throwable ignored) {}
        if (root != null) {
            try { root.removeView(old); } catch (Throwable ignored) {}
        }
        try {
            old.removeAllViews();
            old.destroy();
        } catch (Throwable ignored) {
            // Renderer/process may already have gone away.
        }
    }

    /**
     * Makes sure a working, attached, visible WebView exists. In the common
     * case (the keyboard was just briefly hidden, e.g. tapping away from a
     * text field) this is cheap: the existing WebView is simply resumed.
     * Only when the input view itself is genuinely missing, or the renderer
     * is known to have died (browserRendererDead, set by
     * onRenderProcessGone()), does it rebuild anything — so routine
     * show/hide cycles never pay for a full WebView recreation.
     */
    private void ensureBrowserActive() {
        if (!inputViewActive) return;
        if (root == null || web == null || web.getParent() == null) {
            root = null;
            web = null;
            url = null;
            keys = null;
            setInputView(onCreateInputView()); // createWebView() loads lastWebUrl
            browserRendererDead = false;
            return;
        }
        applyTheme();
        try {
            web.setVisibility(View.VISIBLE);
            web.onResume();
        } catch (Throwable ignored) {
            browserRendererDead = true;
        }
        if (browserRendererDead) {
            recoverDeadBrowser();
        }
    }

    /**
     * Recovers from a known-dead WebView renderer by fully detaching,
     * destroying, and recreating the WebView in place — unlike
     * {@link #restartBrowser()}, this never attempts a soft in-place reset
     * first, since that assumes a still-live renderer, which is exactly
     * what's missing here.
     */
    private void recoverDeadBrowser() {
        browserRendererDead = false;
        if (root == null) return;
        int insertIndex = -1;
        if (web != null) {
            try {
                insertIndex = root.indexOfChild(web);
                root.removeView(web);
            } catch (Throwable ignored) {}
        }
        createWebView();
        if (web != null) {
            int idx = insertIndex >= 0 ? insertIndex : Math.min(1, root.getChildCount());
            root.addView(web, idx, new LinearLayout.LayoutParams(-1, 0, 1f));
            web.setVisibility(View.VISIBLE);
            try { web.onResume(); } catch (Throwable ignored) {}
        }
        if (url != null && lastWebUrl != null) {
            url.setText(lastWebUrl);
        }
    }

    @Override public void onDestroy() {
        stopBackspaceRepeat();
        KeyboardTheme.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        destroyBrowser();
        url = null;
        keys = null;
        root = null;
        super.onDestroy();
    }

    /** Live-preview hook: settings changes are applied immediately while the keyboard is visible. */
    @Override public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        theme = KeyboardTheme.load(this);
        applyTheme();
    }

    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        inputViewActive = true;
        stopBackspaceRepeat();
        // Always reload in case the theme changed while the keyboard was hidden.
        theme = KeyboardTheme.load(this);
        ensureBrowserActive();
    }

    @Override public View onCreateInputView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, 0);

        root.addView(buildBrowserBar(), new LinearLayout.LayoutParams(-1, dp(40)));
        createWebView();
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1f));

        keys = new LinearLayout(this);
        keys.setOrientation(LinearLayout.VERTICAL);
        root.addView(keys, new LinearLayout.LayoutParams(-1, keysHeightPx()));

        applyTheme();
        return root;
    }

    /** Creates a fresh WebView and restores the last page without leaking the old instance. */
    private void createWebView() {
        if (web != null) {
            try {
                web.stopLoading();
                web.setWebViewClient(null);
                web.removeAllViews();
                web.destroy();
            } catch (Throwable ignored) {
                // WebView teardown can throw when its renderer already died.
            }
            web = null;
        }
        browserRendererDead = false;

        web = new WebView(this);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                v.requestFocus();
                v.requestFocusFromTouch();
            }
            return false;
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // Avoid retaining unnecessary renderer/cache state while the IME is hidden.
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANT while visible protects the renderer from routine
            // memory-pressure kills during normal use; waivedWhenNotVisible
            // lets Android automatically relax that while the keyboard is
            // hidden, without us having to track visibility manually.
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String pageUrl) {
                super.onPageFinished(view, pageUrl);
                if (pageUrl != null && !pageUrl.isEmpty()) {
                    lastWebUrl = pageUrl;
                    if (url != null && !url.hasFocus()) {
                        url.setText(pageUrl);
                    }
                }
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // Android may kill the Chromium renderer at any time under
                // memory pressure — including while the keyboard is hidden.
                // Always mark it as needing repair; if the keyboard happens
                // to be visible right now, repair immediately too. If it's
                // hidden, ensureBrowserActive() will repair it the next time
                // the keyboard is shown instead of silently leaving a dead
                // WebView behind (which previously showed up as a blank
                // browser pane that never recovered on its own).
                browserRendererDead = true;
                if (inputViewActive && root != null) {
                    root.post(() -> {
                        if (browserRendererDead) recoverDeadBrowser();
                    });
                }
                return true;
            }
        });
        web.setBackgroundColor(Color.WHITE);

        try {
            web.loadUrl(lastWebUrl);
        } catch (Throwable ignored) {
            // If the WebView renderer is unavailable, leave a valid WebView
            // instance so the keyboard itself remains usable.
        }
    }

    private int keysHeightPx() {
        return dp(Math.round(BASE_KEYS_HEIGHT_DP * theme.sizeScale));
    }

    /** Re-applies the current theme to every part of the UI without rebuilding the URL field. */
    private void applyTheme() {
        if (root == null) return;
        root.setBackgroundColor(KeyboardTheme.withAlpha(theme.backgroundColor, theme.backgroundAlpha));

        styleUrlField();
        styleSmallButton(goButton);
        styleSmallButton(backButton);
        styleSmallButton(forwardButton);
        styleSmallButton(reloadButton);
        styleSmallButton(restartButton);
        styleSettingsButton();

        if (keys != null) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) keys.getLayoutParams();
            if (lp != null) {
                lp.height = keysHeightPx();
                keys.setLayoutParams(lp);
            }
            buildKeys();
        }
    }

    private View buildBrowserBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(1), dp(1), dp(1), dp(1));

        url = new EditText(this);
        url.setSingleLine(true);
        url.setText(lastWebUrl);
        url.setTextSize(14);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setShowSoftInputOnFocus(false);
        url.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                int end = url.length();
                url.setSelection(Math.max(0, Math.min(end, url.getSelectionStart() < 0 ? end : url.getSelectionStart())));
            }
        });
        url.setOnEditorActionListener((v, actionId, event) -> { navigate(); return true; });
        url.setPadding(dp(12), 0, dp(8), 0);
        bar.addView(url, new LinearLayout.LayoutParams(0, -1, 1f));

        goButton = smallButton("GO");
        goButton.setContentDescription("Go");
        goButton.setOnClickListener(v -> navigate());
        bar.addView(goButton, new LinearLayout.LayoutParams(dp(44), -1));

        backButton = smallButton("‹");
        backButton.setContentDescription("Back");
        backButton.setOnClickListener(v -> { if (web != null && web.canGoBack()) web.goBack(); });
        bar.addView(backButton, new LinearLayout.LayoutParams(dp(34), -1));

        forwardButton = smallButton("›");
        forwardButton.setContentDescription("Forward");
        forwardButton.setOnClickListener(v -> { if (web != null && web.canGoForward()) web.goForward(); });
        bar.addView(forwardButton, new LinearLayout.LayoutParams(dp(34), -1));

        reloadButton = smallButton("↻");
        reloadButton.setContentDescription("Reload website");
        reloadButton.setOnClickListener(v -> {
            if (web != null) web.reload();
        });
        bar.addView(reloadButton, new LinearLayout.LayoutParams(dp(34), -1));

        restartButton = smallButton("⟳");
        restartButton.setContentDescription("Restart embedded browser");
        restartButton.setOnClickListener(v -> restartBrowser());
        bar.addView(restartButton, new LinearLayout.LayoutParams(dp(34), -1));

        settingsButton = new ImageButton(this);
        settingsButton.setImageResource(R.drawable.ic_settings);
        settingsButton.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        settingsButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        settingsButton.setContentDescription(getString(R.string.ime_settings));
        settingsButton.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(34), -1);
        settingsParams.setMarginStart(dp(4));
        bar.addView(settingsButton, settingsParams);

        return bar;
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private void styleUrlField() {
        if (url == null) return;
        url.setBackground(KeyboardTheme.roundedRect(this, Color.WHITE, Math.max(theme.cornerRadiusDp, 10f)));
        url.setTextColor(theme.textColor);
    }

    private void styleSmallButton(Button b) {
        if (b == null) return;
        b.setBackground(KeyboardTheme.keyBackground(this, theme.specialKeyColor, Math.max(theme.cornerRadiusDp - 2f, 4f)));
        b.setTextColor(theme.textColor);
        attachPressAnimation(b);
    }

    private void styleSettingsButton() {
        if (settingsButton == null) return;
        settingsButton.setBackground(KeyboardTheme.keyBackground(this, theme.specialKeyColor, Math.max(theme.cornerRadiusDp - 2f, 4f)));
        settingsButton.setImageTintList(android.content.res.ColorStateList.valueOf(theme.textColor));
        attachPressAnimation(settingsButton);
    }

    private void navigate() {
        if (url == null || web == null) return;
        String q = url.getText().toString().trim();
        if (q.isEmpty()) return;
        if (!q.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            q = "https://www.google.com/search?q=" + android.net.Uri.encode(q);
        }
        lastWebUrl = q;
        web.loadUrl(q);
        url.clearFocus();
        web.requestFocus();
    }

    /**
     * Resets the embedded browser ("Browser neu starten" key) without ever
     * removing the WebView from the keyboard's view tree. Earlier this method
     * detached the WebView, destroyed it, and re-inserted a freshly created
     * instance — but re-attaching a torn-down WebView to the IME's overlay
     * window is unreliable and could leave the browser pane permanently
     * blank/unresponsive. Instead, the same WebView instance is kept in
     * place and its state is reset in-place: any in-flight load is stopped,
     * the back/forward navigation stack is cleared, and the last-shown page
     * is loaded again, which gives it a completely fresh JS/DOM context
     * (a full navigation always creates one) while leaving cookies/login
     * state intact. Only if the existing WebView/renderer turns out to be in
     * a genuinely broken state does this fall back to a full
     * destroy-and-recreate, still swapping in exactly one replacement
     * instance at the same position (no duplicate WebViews/tabs).
     */
    private void restartBrowser() {
        if (root == null) return;

        String target = TextUtils.isEmpty(lastWebUrl) ? "https://www.google.com/" : lastWebUrl;

        if (web == null) {
            // No WebView currently exists: create and attach one.
            createWebView();
            if (web != null) {
                int index = Math.min(1, root.getChildCount());
                root.addView(web, index, new LinearLayout.LayoutParams(-1, 0, 1f));
            }
            if (url != null) url.setText(target);
            return;
        }

        boolean resetOk = true;
        try {
            web.stopLoading();
            web.clearHistory();
            web.setVisibility(View.VISIBLE);
            web.onResume();
            web.loadUrl(target);
        } catch (Throwable t) {
            resetOk = false;
        }

        if (!resetOk) {
            // The existing WebView/renderer is in a broken state: replace
            // just this one instance in place, without ever leaving the
            // browser pane empty for longer than the swap itself.
            int insertIndex = -1;
            try {
                insertIndex = root.indexOfChild(web);
                root.removeView(web);
            } catch (Throwable ignored) {}
            createWebView();
            if (web != null) {
                int index = insertIndex >= 0 ? insertIndex : Math.min(1, root.getChildCount());
                root.addView(web, index, new LinearLayout.LayoutParams(-1, 0, 1f));
                web.setVisibility(View.VISIBLE);
                try { web.onResume(); } catch (Throwable ignored) {}
            }
        }

        if (url != null) url.setText(target);
    }

    private void buildKeys() {
        keys.removeAllViews();
        if (symbols) {
            addRow("1234567890");
            addRow("@#$%&*+-=/");
            LinearLayout r = row();
            addKey(r, "ABC", 1.25f, v -> { symbols = false; buildKeys(); }, KeyboardTheme.KeyKind.SPECIAL);
            addKey(r, "()[]{}", 2.0f, v -> type(((Button) v).getText().toString()), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "!?;:'", 2.0f, v -> type(((Button) v).getText().toString()), KeyboardTheme.KeyKind.NORMAL);
            addBackspaceKey(r, 1.25f);
            keys.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            addRow("qwertzuiopü");
            addRow("asdfghjklöä");
            LinearLayout r = row();
            addKey(r, "⇧", 1.35f, v -> { shift = !shift; buildKeys(); }, KeyboardTheme.KeyKind.SPECIAL);
            addKey(r, "y", 1f, v -> type(keyText((Button) v)), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "x", 1f, v -> type(keyText((Button) v)), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "c", 1f, v -> type(keyText((Button) v)), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "v", 1f, v -> type(keyText((Button) v)), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "b", 1f, v -> type(keyText((Button) v)), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "n", 1f, v -> type(keyText((Button) v)), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, "m", 1f, v -> type(keyText((Button) v)), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, ",", 1f, v -> type(","), KeyboardTheme.KeyKind.NORMAL);
            addKey(r, ".", 1f, v -> type("."), KeyboardTheme.KeyKind.NORMAL);
            addBackspaceKey(r, 1.35f);
            keys.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
        }

        LinearLayout bottom = row();
        addKey(bottom, symbols ? "ABC" : "?123", 1.25f, v -> { symbols = !symbols; shift = false; buildKeys(); }, KeyboardTheme.KeyKind.SPECIAL);
        addKey(bottom, "🌐", 1f, v -> { if (url != null) { url.requestFocus(); url.setSelection(url.length()); } }, KeyboardTheme.KeyKind.SPECIAL);
        addKey(bottom, ",", 1f, v -> type(","), KeyboardTheme.KeyKind.NORMAL);
        addKey(bottom, "Leertaste", 4.2f, v -> type(" "), KeyboardTheme.KeyKind.NORMAL);
        addKey(bottom, ".", 1f, v -> type("."), KeyboardTheme.KeyKind.NORMAL);
        addKey(bottom, "↵", 1.25f, v -> enter(), KeyboardTheme.KeyKind.ENTER);
        keys.addView(bottom, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private String keyText(Button b) { return b.getText().toString(); }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private void addRow(String chars) {
        LinearLayout r = row();
        for (int i = 0; i < chars.length(); i++) {
            String c = String.valueOf(chars.charAt(i));
            addKey(r, c, 1f, v -> type(((Button) v).getText().toString()), KeyboardTheme.KeyKind.NORMAL);
        }
        keys.addView(r, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private void addKey(LinearLayout row, String label, float weight, View.OnClickListener listener, KeyboardTheme.KeyKind kind) {
        Button b = createStyledKey(label, kind);
        b.setOnClickListener(listener);
        attachPressAnimation(b);
        row.addView(b, keyLayoutParams(weight));
    }

    /**
     * Adds the backspace key with modern long-press-to-repeat behavior: a
     * short tap deletes exactly one character/selection, and holding the key
     * down starts continuous deletion after a brief delay, accelerating
     * slightly the longer it is held, stopping immediately on release.
     */
    private void addBackspaceKey(LinearLayout row, float weight) {
        Button b = createStyledKey("⌫", KeyboardTheme.KeyKind.BACKSPACE);
        b.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (theme.pressEffectEnabled) {
                        v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).start();
                    }
                    // Immediate single-character delete for a normal tap.
                    delete();
                    backspaceRepeating = true;
                    backspaceRepeatDelay = BACKSPACE_REPEAT_START_MS;
                    backspaceHandler.removeCallbacks(backspaceRunnable);
                    backspaceHandler.postDelayed(backspaceRunnable, BACKSPACE_HOLD_DELAY_MS);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (theme.pressEffectEnabled) {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                    }
                    stopBackspaceRepeat();
                    return true;
                default:
                    return true;
            }
        });
        row.addView(b, keyLayoutParams(weight));
    }

    /** Stops any in-progress backspace auto-repeat and clears pending callbacks. */
    private void stopBackspaceRepeat() {
        backspaceRepeating = false;
        backspaceHandler.removeCallbacks(backspaceRunnable);
        backspaceRepeatDelay = BACKSPACE_REPEAT_START_MS;
    }

    /** Builds and styles a key button per the current theme, without attaching any listener. */
    private Button createStyledKey(String label, KeyboardTheme.KeyKind kind) {
        Button b = new Button(this);
        String shown = label;
        if (shift && label.length() == 1 && Character.isLetter(label.charAt(0))) {
            shown = label.toUpperCase(Locale.GERMANY);
        }
        b.setText(shown);
        b.setTextSize(label.equals("Leertaste") ? theme.fontSizeSp * 0.75f : theme.fontSizeSp);
        b.setAllCaps(false);
        b.setPadding(0, 0, 0, 0);

        int baseColor = theme.colorForKind(kind);
        b.setBackground(KeyboardTheme.keyBackground(this, baseColor, theme.cornerRadiusDp));
        b.setTextColor(kind == KeyboardTheme.KeyKind.ENTER ? KeyboardTheme.contrastText(baseColor) : theme.textColor);
        return b;
    }

    private LinearLayout.LayoutParams keyLayoutParams(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        int spacing = dp(Math.round(theme.spacingDp));
        p.setMargins(spacing, spacing, spacing, spacing);
        return p;
    }

    /** Adds a quick, subtle scale-down effect on press for a more modern, responsive feel. */
    private void attachPressAnimation(View v) {
        v.setOnTouchListener((view, event) -> {
            if (!theme.pressEffectEnabled) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.93f).scaleY(0.93f).setDuration(60).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                    break;
            }
            return false;
        });
    }

    private boolean isUrlFocused() {
        return url != null && url.hasFocus();
    }

    private boolean isWebFocused() {
        return web != null && web.hasFocus() && !isUrlFocused();
    }

    private void evaluateWebScript(String script) {
        if (web == null || TextUtils.isEmpty(script)) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                web.evaluateJavascript(script, null);
            } else {
                web.loadUrl("javascript:" + script);
            }
        } catch (Throwable ignored) {
            // A dead WebView renderer must never take the keyboard down with it.
        }
    }

    private String jsString(String text) {
        try {
            return JSONObject.quote(text);
        } catch (Exception e) {
            return "\"\"";
        }
    }

    private void injectTextIntoWeb(String text) {
        if (TextUtils.isEmpty(text)) return;
        evaluateWebScript("(function(t){"
                + "var el=document.activeElement;"
                + "if(!el) return;"
                + "if(el.isContentEditable){document.execCommand('insertText',false,t);return;}"
                + "var tag=(el.tagName||'').toLowerCase();"
                + "if(tag==='input'||tag==='textarea'){"
                + "var start=typeof el.selectionStart==='number'?el.selectionStart:(el.value||'').length;"
                + "var end=typeof el.selectionEnd==='number'?el.selectionEnd:start;"
                + "var value=el.value||'';"
                + "el.value=value.slice(0,start)+t+value.slice(end);"
                + "var pos=start+t.length;"
                + "if(el.setSelectionRange){el.setSelectionRange(pos,pos);}else{el.selectionStart=el.selectionEnd=pos;}"
                + "el.dispatchEvent(new Event('input',{bubbles:true,cancelable:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "return;"
                + "}"
                + "})(%s);".formatted(jsString(text)));
    }

    private void injectBackspaceIntoWeb() {
        evaluateWebScript("(function(){"
                + "var el=document.activeElement;"
                + "if(!el) return;"
                + "if(el.isContentEditable){document.execCommand('delete');return;}"
                + "var tag=(el.tagName||'').toLowerCase();"
                + "if(tag==='input'||tag==='textarea'){"
                + "var value=el.value||'';"
                + "var start=typeof el.selectionStart==='number'?el.selectionStart:value.length;"
                + "var end=typeof el.selectionEnd==='number'?el.selectionEnd:start;"
                + "if(start!==end){"
                + "el.value=value.slice(0,start)+value.slice(end);"
                + "el.setSelectionRange(start,start);"
                + "}else if(start>0){"
                + "el.value=value.slice(0,start-1)+value.slice(end);"
                + "el.setSelectionRange(start-1,start-1);"
                + "}"
                + "el.dispatchEvent(new Event('input',{bubbles:true,cancelable:true}));"
                + "el.dispatchEvent(new Event('change',{bubbles:true}));"
                + "}"
                + "})() ;");
    }

    private void injectEnterIntoWeb() {
        evaluateWebScript("(function(){"
                + "var el=document.activeElement;"
                + "if(!el) return;"
                + "var evOpts={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};"
                + "el.dispatchEvent(new KeyboardEvent('keydown',evOpts));"
                + "el.dispatchEvent(new KeyboardEvent('keypress',evOpts));"
                + "el.dispatchEvent(new KeyboardEvent('keyup',evOpts));"
                + "if(el.form){"
                + "if(typeof el.form.requestSubmit==='function'){el.form.requestSubmit();}"
                + "else{el.form.submit();}"
                + "}"
                + "})() ;");
    }

    /** Sends text either to the browser's address/search field, a webpage input, or to the app using the IME. */
    private void type(String text) {
        if (TextUtils.isEmpty(text)) return;

        if (shift) {
            // Shift should affect letters, not arbitrary strings such as
            // punctuation groups or the space bar.
            if (text.length() == 1 && Character.isLetter(text.charAt(0))) {
                text = text.toUpperCase(Locale.GERMANY);
            }
            shift = false;
            buildKeys();
        }

        if (isUrlFocused()) {
            int start = Math.max(0, url.getSelectionStart());
            int end = Math.max(0, url.getSelectionEnd());
            url.getText().replace(Math.min(start, end), Math.max(start, end), text);
            url.setSelection(Math.min(start, end) + text.length());
            return;
        }

        if (isWebFocused()) {
            injectTextIntoWeb(text);
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void delete() {
        if (isUrlFocused()) {
            int start = Math.max(0, url.getSelectionStart());
            int end = Math.max(0, url.getSelectionEnd());
            int min = Math.min(start, end);
            int max = Math.max(start, end);
            if (min != max) {
                url.getText().delete(min, max);
                url.setSelection(min);
            } else if (start > 0) {
                // Delete one Unicode code point rather than half of a surrogate pair.
                int deleteStart = Character.offsetByCodePoints(url.getText(), start, -1);
                url.getText().delete(deleteStart, start);
                url.setSelection(deleteStart);
            }
            return;
        }

        if (isWebFocused()) {
            injectBackspaceIntoWeb();
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        CharSequence selected = null;
        try {
            selected = ic.getSelectedText(0);
        } catch (Throwable ignored) {
            // Some editors/apps do not support querying the selection.
        }
        if (!TextUtils.isEmpty(selected)) {
            // Text is marked/selected in the target app's field: remove the
            // selection first, like a standard Android keyboard would.
            try {
                ic.commitText("", 1);
            } catch (Throwable ignored) {}
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ic.deleteSurroundingTextInCodePoints(1, 0);
            } else {
                ic.deleteSurroundingText(1, 0);
            }
        } catch (Throwable ignored) {
            // A stale/invalid InputConnection (e.g. after switching apps
            // mid-repeat) must never crash the keyboard process.
        }
    }

    private void enter() {
        if (isUrlFocused()) {
            navigate();
            return;
        }

        if (isWebFocused()) {
            injectEnterIntoWeb();
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
        }
    }

    private int dp(int x) {
        return (int) (x * getResources().getDisplayMetrics().density + 0.5f);
    }
}
