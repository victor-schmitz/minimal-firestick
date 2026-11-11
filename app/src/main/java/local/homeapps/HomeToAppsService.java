package local.homeapps;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Sends Home to Amazon's "Your apps & games" grid. When Amazon's home shows up, the service clicks the
 * launcher's own "all apps" button, so Amazon opens the grid itself without Fire OS's five-second delay on
 * activity starts after Home. If the button is not found in time, it starts the grid directly.
 *
 * Fire OS hands neither the Home key nor its "homekey" broadcast to third-party apps, and Home pressed while
 * Amazon's home is already in front changes no window, so that one press cannot be told from browsing.
 * HomeShortcutActivity opens Amazon's home on purpose; it stays until another app comes to the front.
 *
 * While a redirect runs, an overlay in the grid's background colour covers Amazon's home so it does not
 * flash. It is removed once the grid is in front, when the redirect is abandoned, or after a few seconds.
 * The overlay lives on its own thread: looking for the button blocks the main thread while Amazon's home is
 * still loading, and drawing the overlay there let the home show for up to half a second first.
 */
public class HomeToAppsService extends AccessibilityService {
    private static final String LAUNCHER = "com.amazon.tv.launcher";
    private static final String HOME_CLASS = LAUNCHER + ".ui.HomeActivity";
    private static final String APPS_BUTTON = "com.amazon.tv.launcher:id/nav_bar_app_overflow";
    /** Settings page inside Amazon's home (also reached from the Home long-press menu): stay on it. */
    private static final String SETTINGS_PAGE = "com.amazon.tv.launcher:id/settings_search_input";
    /** Settings screens (Network, Display...) live here; Back from them returns to Amazon's settings page. */
    private static final String SETTINGS_PACKAGE = "com.amazon.tv.settings";
    private static final ComponentName APPS_GRID =
            new ComponentName("com.amazon.venezia", "com.amazon.venezia.grid.AppsGridLauncherActivity");
    /** The grid's own background, so the cover blends into it. */
    private static final int COVER_COLOR = 0xFF1A1A1A;
    /** Fail-open: the cover never stays longer than this. On a busy stick the grid can take over 3 s. */
    private static final long COVER_MAX_MS = 4000;
    /** The grid reports itself about 60 ms before its first frame; leave room for a slower one. */
    private static final long COVER_GRACE_MS = 250;
    private static final long RETRY_MS = 100;
    private static final int MAX_TRIES = 20;
    /** Tries to wait before clicking, so a settings page that is still drawing gets the chance to show. */
    private static final int SETTLE_TRIES = 2;
    private static final long DEBOUNCE_MS = 1500;

    /** Set by HomeShortcutActivity: Amazon's home opening on request is not a Home press. */
    static volatile long suppressUntil;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastRedirect;
    private int tries;
    /** Package of the last window that came to the front, to tell Back-from-settings apart from Home. */
    private String lastPackage = "";
    /** Start the grid directly if the button never shows. */
    private boolean fallback;
    /** A check is scheduled; another trigger while it runs does not start a second one. */
    private boolean pending;

    private HandlerThread coverThread;
    private Handler coverHandler;
    /** Only touched on the cover thread. */
    private View cover;

    private final Runnable showCover = () -> {
        if (cover != null) return;
        View view = new View(this);
        view.setBackgroundColor(COVER_COLOR);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        try {
            getSystemService(WindowManager.class).addView(view, params);
            cover = view;
        } catch (RuntimeException ignored) {
            // No overlay: the redirect still works, Amazon's home just shows for a moment.
        }
    };

    private final Runnable removeCover = () -> {
        if (cover != null) {
            getSystemService(WindowManager.class).removeView(cover);
            cover = null;
        }
    };

    private final Runnable clickAppsButton = new Runnable() {
        @Override
        public void run() {
            if (visible(SETTINGS_PAGE)) {
                pending = false;
                uncoverAfter(0);
                return;
            }
            if (tries >= SETTLE_TRIES && clickNode()) {
                redirected();
                return;
            }
            if (++tries < MAX_TRIES) {
                handler.postDelayed(this, RETRY_MS);
                return;
            }
            pending = false;
            if (fallback) {
                startGrid();
                redirected();
            } else {
                uncoverAfter(0);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        coverThread = new HandlerThread("cover", Process.THREAD_PRIORITY_DISPLAY);
        coverThread.start();
        coverHandler = new Handler(coverThread.getLooper());
    }

    @Override
    public void onDestroy() {
        coverHandler.removeCallbacksAndMessages(null);
        coverHandler.post(removeCover);
        coverThread.quitSafely();
        super.onDestroy();
    }

    private void coverScreen() {
        coverHandler.removeCallbacks(removeCover);
        coverHandler.post(showCover);
        coverHandler.postDelayed(removeCover, COVER_MAX_MS);
    }

    private void uncoverAfter(long delayMs) {
        coverHandler.removeCallbacks(removeCover);
        coverHandler.postDelayed(removeCover, delayMs);
    }

    /** Only a redirect that happened starts the debounce; settings-page checks that stayed put do not. */
    private void redirected() {
        pending = false;
        lastRedirect = SystemClock.uptimeMillis();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgName = event.getPackageName();
        String pkg = pkgName == null ? "" : pkgName.toString();
        String previous = lastPackage;
        lastPackage = pkg;
        CharSequence cls = event.getClassName();
        if (APPS_GRID.getPackageName().equals(pkg)) {
            // The store's link screen comes first, with Amazon's home still showing behind it: wait for the
            // grid itself, then uncover once it has drawn.
            if (cls != null && APPS_GRID.getClassName().equals(cls.toString())) uncoverAfter(COVER_GRACE_MS);
            return;
        }
        if (!LAUNCHER.equals(pkg) || cls == null || !cls.toString().startsWith(HOME_CLASS)) return;
        // Back from a settings screen lands on the settings page; Amazon's home opened on request stays.
        if (SystemClock.uptimeMillis() < suppressUntil || previous.startsWith(SETTINGS_PACKAGE)) return;
        // Amazon's home can report itself again right after a redirect; that is not a new Home press, and
        // covering it would leave a dark screen with no redirect behind it.
        if (SystemClock.uptimeMillis() - lastRedirect < DEBOUNCE_MS) return;
        coverScreen();
        redirect(true);
    }

    private void redirect(boolean allowFallback) {
        if (pending) {
            fallback |= allowFallback;
            return;
        }
        pending = true;
        tries = 0;
        fallback = allowFallback;
        handler.removeCallbacks(clickAppsButton);
        handler.post(clickAppsButton);
    }

    private boolean visible(String viewId) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(viewId)) {
            if (node.isVisibleToUser()) return true;
        }
        return false;
    }

    private boolean clickNode() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(APPS_BUTTON);
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
        }
        return false;
    }

    private void startGrid() {
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(APPS_GRID)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            // Grid missing on this build: leave Amazon's home on screen.
        }
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacks(clickAppsButton);
        pending = false;
        uncoverAfter(0);
    }
}
