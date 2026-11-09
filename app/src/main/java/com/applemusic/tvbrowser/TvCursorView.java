package com.applemusic.tvbrowser;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.FrameLayout;

/**
 * D-pad cursor for the WebView, built to never get in the web page's way:
 *
 * - The cursor is a small (72dp) hardware-layer sprite that is MOVED with translationX/Y.
 *   Moving it never re-records a display list and never invalidates the full screen, so the
 *   WebView underneath is not redrawn because of the cursor.
 * - Movement is time based (px/second), so it is identical at 60 fps and at 20 fps.
 * - Scrolling is sent as real mouse-wheel events (vertical AND horizontal) once per frame.
 *   Chromium handles those on its compositor thread: no JavaScript, no forced layout,
 *   no double scrolling.
 *
 * Controls
 *   Arrow keys (tap / hold)   move the cursor
 *   Tap an arrow, then press the same arrow again and HOLD   scroll that way
 *                             (up / down / left / right, diagonals too)
 *   OK / Enter                click (hold OK + arrows = drag, e.g. seek bar)
 *   Page Up/Down, Channel Up/Down   scroll vertically
 */
public class TvCursorView extends View implements Choreographer.FrameCallback {

    // Cursor speed, as a fraction of the screen width per second.
    private static final float START_SPEED = 0.16f;
    private static final float MAX_SPEED = 1.05f;
    private static final float ACCEL_PER_SEC = 14f;
    private static final float NUDGE_DP = 4f;

    // Scroll speed in page pixels per second.
    private static final float SCROLL_START_PX = 450f;
    private static final float SCROLL_MAX_PX = 2400f;
    private static final float SCROLL_ACCEL_PER_SEC = 4f;

    private static final long DOUBLE_PRESS_WINDOW_MS = 450L;
    private static final long SCROLL_HOLD_MS = 140L;
    private static final long AUTO_HIDE_DELAY_MS = 4000L;
    private static final long HOVER_SETTLE_MS = 90L;
    private static final float SPRITE_DP = 72f;

    private final WebView targetWebView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint contrastPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rippleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float density;
    private final int spriteSizePx;
    private final float baseRadiusPx;
    private final float nudgePx;

    private final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
    private final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];

    // Geometry (screen pixels). webScale = how much the WebView is scaled up on screen.
    private int viewportW = 0;
    private int viewportH = 0;
    private float webScale = 1f;
    private float cursorX = -1f;
    private float cursorY = -1f;

    private boolean up, down, left, right;
    private boolean loopRunning = false;
    private long lastFrameNanos = 0L;
    private float speed = 0f;
    private float scrollSpeed = SCROLL_START_PX;

    private int lastDirKey = -1;
    private long lastDirDownTime = 0L;
    private int pendingScrollKey = -1;
    private long pendingScrollSince = 0L;
    private boolean scrollMode = false;
    private int pageScrollDir = 0; // -1 up, +1 down (Page / Channel keys)

    private boolean clickPressed = false;
    private boolean touchDown = false;
    private long touchDownTime = 0L;

    private boolean cursorShown = true;
    private boolean hideScheduled = false;
    private long lastActiveUptime = 0L;

    private float lastHoverX = -1f;
    private float lastHoverY = -1f;

    private float rippleProgress = 0f;
    private boolean rippleActive = false;
    private final ValueAnimator rippleAnimator = ValueAnimator.ofFloat(0f, 1f);

    private final Runnable hideRunnable = new Runnable() {
        @Override
        public void run() {
            hideScheduled = false;
            long remaining = lastActiveUptime + AUTO_HIDE_DELAY_MS - SystemClock.uptimeMillis();
            if (anyKeyDown() || clickPressed || remaining > 0) {
                hideScheduled = true;
                handler.postDelayed(this, Math.max(remaining, 300L));
                return;
            }
            cursorShown = false;
            animate().alpha(0f).setDuration(220L).start();
        }
    };

    private final Runnable hoverRunnable = new Runnable() {
        @Override
        public void run() {
            dispatchHover();
        }
    };

    public TvCursorView(Context context, WebView webView) {
        super(context);
        this.targetWebView = webView;
        setClickable(false);
        setFocusable(false);
        setLayerType(View.LAYER_TYPE_NONE, null);

        density = getResources().getDisplayMetrics().density;
        spriteSizePx = Math.round(SPRITE_DP * density);
        baseRadiusPx = 13f * density;
        nudgePx = NUDGE_DP * density;

        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        coords[0] = new MotionEvent.PointerCoords();

        haloPaint.setStyle(Paint.Style.FILL);
        bodyPaint.setStyle(Paint.Style.FILL);
        highlightPaint.setStyle(Paint.Style.FILL);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(Math.max(1.6f, 1.3f * density));
        contrastPaint.setStyle(Paint.Style.STROKE);
        contrastPaint.setStrokeWidth(Math.max(1f, 0.7f * density));
        contrastPaint.setColor(Color.argb(46, 20, 22, 30));
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeWidth(Math.max(2f, 1.4f * density));
        rippleFillPaint.setStyle(Paint.Style.FILL);

        rippleAnimator.setDuration(280L);
        rippleAnimator.setInterpolator(new DecelerateInterpolator());
        rippleAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                rippleProgress = (Float) a.getAnimatedValue();
                if (rippleProgress >= 1f) rippleActive = false;
                invalidate();
            }
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f;
        float r = baseRadiusPx;
        // Frosted body: bright at the top-left, clear glass towards the bottom-right.
        bodyPaint.setShader(new RadialGradient(cx - r * 0.35f, cy - r * 0.45f, r * 1.7f,
                new int[]{Color.argb(120, 255, 255, 255), Color.argb(62, 238, 244, 255), Color.argb(40, 220, 230, 250)},
                new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        // Soft specular sheen on the upper half.
        highlightPaint.setShader(new RadialGradient(cx - r * 0.2f, cy - r * 0.55f, r * 0.85f,
                new int[]{Color.argb(95, 255, 255, 255), Color.argb(0, 255, 255, 255)},
                null, Shader.TileMode.CLAMP));
        // Rim light: glints at top-left and bottom-right, like liquid glass edges.
        SweepGradient rim = new SweepGradient(cx, cy,
                new int[]{Color.argb(235, 255, 255, 255), Color.argb(70, 255, 255, 255),
                        Color.argb(200, 255, 255, 255), Color.argb(70, 255, 255, 255),
                        Color.argb(235, 255, 255, 255)},
                new float[]{0f, 0.25f, 0.5f, 0.75f, 1f});
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(135f, cx, cy);
        rim.setLocalMatrix(m);
        borderPaint.setShader(rim);
        // Gentle shadow so the glass reads on light and dark pages.
        haloPaint.setShader(new RadialGradient(cx, cy + 2f * density, r + 6f * density,
                new int[]{Color.argb(60, 0, 0, 0), Color.argb(34, 0, 0, 0), Color.argb(0, 0, 0, 0)},
                new float[]{0.62f, 0.8f, 1f}, Shader.TileMode.CLAMP));
    }

    public FrameLayout.LayoutParams createLayoutParams() {
        return new FrameLayout.LayoutParams(spriteSizePx, spriteSizePx, Gravity.TOP | Gravity.START);
    }

    /** Screen size in pixels (the cursor moves inside this). */
    public void setViewport(int w, int h) {
        viewportW = w;
        viewportH = h;
        if (cursorX < 0f || cursorY < 0f) {
            cursorX = w / 2f;
            cursorY = h / 2f;
        } else {
            cursorX = clamp(cursorX, 4f, w - 4f);
            cursorY = clamp(cursorY, 4f, h - 4f);
        }
        applyPosition();
        wakeCursor();
    }

    /** On-screen scale of the WebView (the WebView may be rendered smaller and scaled up). */
    public void setWebScale(float scale) {
        webScale = scale > 0f ? scale : 1f;
    }

    public void release() {
        resetKeys();
        handler.removeCallbacksAndMessages(null);
        rippleAnimator.cancel();
    }

    /** Call when the window pauses / loses focus so no key can get "stuck". */
    public void resetKeys() {
        up = down = left = right = false;
        pageScrollDir = 0;
        pendingScrollKey = -1;
        scrollMode = false;
        endScrollGesture();
        stopLoop();
        if (touchDown) {
            touchDown = false;
            dispatchTouch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
        }
        clickPressed = false;
    }

    // ------------------------------------------------------------------ keys

    public boolean handleKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        if (isDirection(keyCode)) {
            wakeCursor();
            return handleDirection(keyCode, action, event.getRepeatCount());
        }
        if (isPageScrollKey(keyCode)) {
            wakeCursor();
            return handlePageScroll(keyCode, action);
        }
        if (isCenter(keyCode)) {
            // A real text field is open: let Enter go to it instead of clicking.
            if ((keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                    && isTextInputActive()) {
                return false;
            }
            wakeCursor();
            return handleCenter(action, event.getRepeatCount());
        }
        return false;
    }

    private boolean handleDirection(int keyCode, int action, int repeat) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (repeat == 0) {
                long now = SystemClock.uptimeMillis();
                setDirection(keyCode, true);
                if (!scrollMode && keyCode == lastDirKey && now - lastDirDownTime <= DOUBLE_PRESS_WINDOW_MS) {
                    // Second press of the same arrow: HELD = scroll, quick tap = just a nudge.
                    pendingScrollKey = keyCode;
                    pendingScrollSince = now;
                    lastDirKey = -1;
                    lastDirDownTime = 0L;
                } else {
                    lastDirKey = keyCode;
                    lastDirDownTime = now;
                    if (!scrollMode && pendingScrollKey == -1) nudge(keyCode);
                }
                startLoop();
            }
            return true;
        }
        if (action == KeyEvent.ACTION_UP) {
            setDirection(keyCode, false);
            if (pendingScrollKey == keyCode) {
                pendingScrollKey = -1;
                nudge(keyCode);
            }
            if (!anyDirectionDown()) {
                pendingScrollKey = -1;
                boolean wasScroll = scrollMode;
                scrollMode = false;
                if (pageScrollDir == 0) {
                    endScrollGesture();
                    stopLoop();
                }
                speed = START_SPEED * Math.max(1, viewportW);
                scrollSpeed = SCROLL_START_PX;
                if (!wasScroll) scheduleHover();
                invalidate();
            }
            return true;
        }
        return true;
    }

    private boolean handlePageScroll(int keyCode, int action) {
        boolean pageUp = keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP;
        if (action == KeyEvent.ACTION_DOWN) {
            pageScrollDir = pageUp ? -1 : 1;
            startLoop();
        } else if (action == KeyEvent.ACTION_UP) {
            pageScrollDir = 0;
            if (!anyDirectionDown()) {
                endScrollGesture();
                stopLoop();
                scrollSpeed = SCROLL_START_PX;
            }
        }
        return true;
    }

    private boolean handleCenter(int action, int repeat) {
        if (action == KeyEvent.ACTION_DOWN) {
            if (repeat == 0 && !clickPressed) {
                clickPressed = true;
                startRipple();
                invalidate();
                long now = SystemClock.uptimeMillis();
                handler.removeCallbacks(hoverRunnable);
                dispatchHover(); // make sure :hover is under the cursor before the click
                touchDownTime = now;
                dispatchTouch(MotionEvent.ACTION_DOWN, now);
                touchDown = true;
            }
        } else if (action == KeyEvent.ACTION_UP) {
            if (clickPressed) {
                clickPressed = false;
                invalidate();
                if (touchDown) {
                    touchDown = false;
                    dispatchTouch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------ frame loop

    private void startLoop() {
        if (loopRunning) return;
        loopRunning = true;
        lastFrameNanos = 0L;
        speed = START_SPEED * Math.max(1, viewportW);
        scrollSpeed = SCROLL_START_PX;
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void stopLoop() {
        if (!loopRunning) return;
        loopRunning = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!loopRunning) return;
        float dt = lastFrameNanos == 0L ? (1f / 60f) : (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = frameTimeNanos;
        if (dt < 0f) dt = 0f;
        if (dt > 0.05f) dt = 0.05f;
        step(dt);
        if (loopRunning) Choreographer.getInstance().postFrameCallback(this);
    }

    private void step(float dt) {
        if (viewportW <= 0 || viewportH <= 0) return;
        long now = SystemClock.uptimeMillis();
        lastActiveUptime = now;

        if (pendingScrollKey != -1) {
            if (now - pendingScrollSince >= SCROLL_HOLD_MS) {
                pendingScrollKey = -1;
                scrollMode = true;
                invalidate();
            } else {
                return; // cursor stays still while we find out if this is a tap or a hold
            }
        }

        if (scrollMode || pageScrollDir != 0) {
            scrollStep(dt, now);
            return;
        }

        float maxSpeed = MAX_SPEED * viewportW;
        speed = Math.min(maxSpeed, speed * (float) Math.pow(ACCEL_PER_SEC, dt));
        float dx = (right ? 1f : 0f) - (left ? 1f : 0f);
        float dy = (down ? 1f : 0f) - (up ? 1f : 0f);
        if (dx == 0f && dy == 0f) return;
        if (dx != 0f && dy != 0f) {
            dx *= 0.7071f;
            dy *= 0.7071f;
        }
        cursorX = clamp(cursorX + dx * speed * dt, 4f, viewportW - 4f);
        cursorY = clamp(cursorY + dy * speed * dt, 4f, viewportH - 4f);
        applyPosition();
        if (touchDown) dispatchTouch(MotionEvent.ACTION_MOVE, now);
    }

    // Scrolling = a synthesized finger drag (same path as touch scrolling on a phone).
    private boolean scrollTouchActive = false;
    private long scrollDownTime = 0L;
    private float scrollPosX, scrollPosY;

    private void scrollStep(float dt, long now) {
        if (clickPressed) return;
        scrollSpeed = Math.min(SCROLL_MAX_PX, scrollSpeed * (float) Math.pow(SCROLL_ACCEL_PER_SEC, dt));
        float px = scrollSpeed * dt;
        float sx = 0f; // page scroll direction: +1 = right / down
        float sy = 0f;
        if (pageScrollDir != 0) {
            sy = pageScrollDir;
        } else {
            sy = (down ? 1f : 0f) - (up ? 1f : 0f);
            sx = (right ? 1f : 0f) - (left ? 1f : 0f);
        }
        if (sx == 0f && sy == 0f) return;

        float webW = viewportW / webScale;
        float webH = viewportH / webScale;
        float margin = 40f;
        if (scrollTouchActive) {
            // Near an edge: lift and re-grab at the cursor so the drag never runs out of screen.
            if (scrollPosX < margin || scrollPosX > webW - margin || scrollPosY < margin || scrollPosY > webH - margin) {
                endScrollGesture();
            }
        }
        if (!scrollTouchActive) {
            scrollPosX = cursorX / webScale;
            scrollPosY = cursorY / webScale;
            scrollDownTime = now;
            scrollTouch(MotionEvent.ACTION_DOWN, now);
            scrollTouchActive = true;
        }
        // Finger moves opposite to the content: scroll down = finger up.
        scrollPosX -= sx * px;
        scrollPosY -= sy * px;
        scrollTouch(MotionEvent.ACTION_MOVE, now);
    }

    private void scrollTouch(int action, long now) {
        MotionEvent e = MotionEvent.obtain(scrollDownTime, now, action, scrollPosX, scrollPosY, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        targetWebView.dispatchTouchEvent(e);
        e.recycle();
    }

    /** CANCEL (not UP) so the page does not fling after we stop. */
    private void endScrollGesture() {
        if (!scrollTouchActive) return;
        scrollTouchActive = false;
        scrollTouch(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis());
    }

    // ------------------------------------------------------------- dispatch

    private MotionEvent obtainEvent(long downTime, long eventTime, int action, int source, int toolType,
                                    float pressure, float hscroll, float vscroll) {
        props[0].toolType = toolType;
        coords[0].clear();
        coords[0].x = cursorX / webScale;
        coords[0].y = cursorY / webScale;
        coords[0].pressure = pressure;
        coords[0].size = 1f;
        if (hscroll != 0f) coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        if (vscroll != 0f) coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        return MotionEvent.obtain(downTime, eventTime, action, 1, props, coords,
                0, 0, 1f, 1f, 0, 0, source, 0);
    }

    private void dispatchHover() {
        if (cursorX < 0f || (cursorX == lastHoverX && cursorY == lastHoverY)) return;
        lastHoverX = cursorX;
        lastHoverY = cursorY;
        long now = SystemClock.uptimeMillis();
        MotionEvent e = obtainEvent(now, now, MotionEvent.ACTION_HOVER_MOVE, InputDevice.SOURCE_MOUSE,
                MotionEvent.TOOL_TYPE_MOUSE, 0f, 0f, 0f);
        targetWebView.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    private void dispatchTouch(int action, long now) {
        MotionEvent e = obtainEvent(touchDownTime, now, action, InputDevice.SOURCE_TOUCHSCREEN,
                MotionEvent.TOOL_TYPE_FINGER, 1f, 0f, 0f);
        targetWebView.dispatchTouchEvent(e);
        e.recycle();
    }

    private void scheduleHover() {
        handler.removeCallbacks(hoverRunnable);
        handler.postDelayed(hoverRunnable, HOVER_SETTLE_MS);
    }

    // -------------------------------------------------------------- helpers

    private void nudge(int keyCode) {
        if (viewportW <= 0) return;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) cursorX -= nudgePx;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) cursorX += nudgePx;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) cursorY -= nudgePx;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) cursorY += nudgePx;
        cursorX = clamp(cursorX, 4f, viewportW - 4f);
        cursorY = clamp(cursorY, 4f, viewportH - 4f);
        applyPosition();
        if (touchDown) dispatchTouch(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis());
        scheduleHover();
    }

    private float shownX = -1f;
    private float shownY = -1f;

    private void applyPosition() {
        float half = spriteSizePx / 2f;
        setTranslationX(cursorX - half);
        setTranslationY(cursorY - half);

        // Make the WebView repaint just the small area the cursor left and entered. Without this the
        // translucent cursor is painted again and again over stale pixels = a translucent square trail.
        float minX = cursorX, maxX = cursorX, minY = cursorY, maxY = cursorY;
        if (shownX >= 0f) {
            minX = Math.min(minX, shownX);
            maxX = Math.max(maxX, shownX);
            minY = Math.min(minY, shownY);
            maxY = Math.max(maxY, shownY);
        }
        shownX = cursorX;
        shownY = cursorY;
        float pad = half + 2f;
        targetWebView.invalidate(
                (int) Math.floor((minX - pad) / webScale), (int) Math.floor((minY - pad) / webScale),
                (int) Math.ceil((maxX + pad) / webScale), (int) Math.ceil((maxY + pad) / webScale));
    }

    private void setDirection(int keyCode, boolean pressed) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) up = pressed;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) down = pressed;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) left = pressed;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) right = pressed;
    }

    private boolean anyDirectionDown() {
        return up || down || left || right;
    }

    private boolean anyKeyDown() {
        return anyDirectionDown() || pageScrollDir != 0;
    }

    private static boolean isDirection(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static boolean isPageScrollKey(int k) {
        return k == KeyEvent.KEYCODE_PAGE_UP || k == KeyEvent.KEYCODE_PAGE_DOWN
                || k == KeyEvent.KEYCODE_CHANNEL_UP || k == KeyEvent.KEYCODE_CHANNEL_DOWN;
    }

    private static boolean isCenter(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    private boolean isTextInputActive() {
        try {
            InputMethodManager imm =
                    (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            return imm != null && imm.isAcceptingText();
        } catch (Throwable t) {
            return false;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private void startRipple() {
        rippleAnimator.cancel();
        rippleActive = true;
        rippleProgress = 0f;
        rippleAnimator.start();
    }

    private void wakeCursor() {
        lastActiveUptime = SystemClock.uptimeMillis();
        if (!cursorShown) {
            cursorShown = true;
            animate().cancel();
            setAlpha(1f);
        }
        if (!hideScheduled) {
            hideScheduled = true;
            handler.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        if (rippleActive && rippleProgress > 0f && rippleProgress < 1f) {
            float maxR = baseRadiusPx * 2.4f;
            float r = baseRadiusPx + (maxR - baseRadiusPx) * rippleProgress;
            rippleFillPaint.setColor(Color.argb(Math.round(65 * (1f - rippleProgress)), 255, 255, 255));
            ripplePaint.setColor(Color.argb(Math.round(185 * (1f - rippleProgress)), 255, 255, 255));
            canvas.drawCircle(cx, cy, r, rippleFillPaint);
            canvas.drawCircle(cx, cy, r, ripplePaint);
        }

        boolean active = clickPressed || scrollMode || pageScrollDir != 0;
        float r = baseRadiusPx;

        canvas.save();
        if (active) canvas.scale(0.9f, 0.9f, cx, cy); // glass presses in slightly

        canvas.drawCircle(cx, cy, r + 6f * density, haloPaint);
        canvas.drawCircle(cx, cy, r, bodyPaint);
        canvas.drawCircle(cx, cy, r, highlightPaint);
        canvas.drawCircle(cx, cy, r - borderPaint.getStrokeWidth() / 2f, borderPaint);
        canvas.drawCircle(cx, cy, r + 0.6f * density, contrastPaint);
        canvas.restore();
    }
}
