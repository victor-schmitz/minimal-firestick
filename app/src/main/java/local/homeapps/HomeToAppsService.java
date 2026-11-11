package local.homeapps;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
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
 * Amazon's home is already in front changes no window. Amazon's launcher, though, sends an unprotected
 * HOME_PRESSED broadcast for every Home intent it receives, so that press is caught there (idea from
 * FireFrame, github.com/dyascj/FireFrame). At boot the launcher is created rather than sent a Home intent, so
 * no broadcast comes: the service checks for Amazon's home itself once it connects.
 * HomeShortcutActivity opens Amazon's home on purpose; it stays until Home is pressed or another app comes up.
 *
 * While a redirect runs, an overlay in the grid's background colour covers Amazon's home so it does not
 * flash. It is removed once the grid is in front, when the redirect is abandoned, or after a few seconds.
 * The overlay lives on its own thread: looking for the button blocks the main thread while Amazon's home is
 * still loading, and drawing the overlay there let the home show for up to half a second first.
 */
public class HomeToAppsService extends AccessibilityService {
    private static final String TAG = "MinimalFireStick";
    private static final String LAUNCHER = "com.amazon.tv.launcher";
    /** Sent by Amazon's launcher, without a permission, whenever it receives a Home intent. */
    private static final String HOME_PRESSED = "com.amazon.tv.launcher.HOME_PRESSED";
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
    /**
     * A check running longer than this is stuck (window queries can block for seconds while the TV is in HDMI
     * standby), so a new trigger restarts it instead of joining it.
     */
    private static final long STALE_MS = 4000;
    /** Tries to wait before clicking, so a settings page that is still drawing gets the chance to show. */
    private static final int SETTLE_TRIES = 2;
    private static final long DEBOUNCE_MS = 1500;
    /** After clicking the apps button: long enough for the launcher to enlarge it, short of the grid opening. */
    private static final long FOCUS_BACK_MS = 250;
    /** A service connecting this soon after boot looks for Amazon's home itself. */
    private static final long BOOT_WINDOW_MS = 180_000;
    private static final long BOOT_POLL_MS = 500;
    /** How long to wait for Amazon's home to come up after boot. */
    private static final int BOOT_POLLS = 120;

    /** Set by HomeShortcutActivity: Amazon's home opening on request is not a Home press. */
    static volatile long suppressUntil;
    /** Set by HomeShortcutActivity: its own Home intent makes the launcher send one HOME_PRESSED to ignore. */
    static volatile boolean skipHomePressed;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastRedirect;
    private int tries;
    /** Package of the last window that came to the front, to tell Back-from-settings apart from Home. */
    private String lastPackage = "";
    /** Start the grid directly if the button never shows. */
    private boolean fallback;
    /** A check is scheduled; another trigger while it runs does not start a second one. */
    private boolean pending;
    private long pendingSince;
    /** The running check was started by a real Home press, so a settings page still showing does not stop it. */
    private boolean homeKey;
    private int bootPolls;
    /**
     * What had focus on Amazon's home before the apps button was clicked, while focus is still left on the
     * button; null once handed back.
     */
    private AccessibilityNodeInfo focusToRestore;

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
            if (!homeKey && visible(SETTINGS_PAGE)) {
                Log.i(TAG, "settings page: stay");
                pending = false;
                uncoverAfter(0);
                return;
            }
            if (tries >= SETTLE_TRIES && clickNode()) {
                Log.i(TAG, "clicked the apps button after " + tries + " tries");
                redirected();
                return;
            }
            if (++tries < MAX_TRIES) {
                handler.postDelayed(this, RETRY_MS);
                return;
            }
            pending = false;
            if (fallback) {
                Log.i(TAG, "apps button not found: starting the grid");
                startGrid();
                redirected();
            } else {
                uncoverAfter(0);
            }
        }
    };

    private final BroadcastReceiver homePressed = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SystemClock.uptimeMillis() >= suppressUntil) {
                skipHomePressed = false;
            } else if (skipHomePressed) {
                skipHomePressed = false;
                Log.i(TAG, "Home intent from the Amazon Home tile: stay");
                return;
            }
            Log.i(TAG, "Home pressed");
            coverScreen();
            redirect(true, true);
        }
    };

    /** After boot: wait for Amazon's home to come up, then redirect once. */
    private final Runnable bootCheck = new Runnable() {
        @Override
        public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            CharSequence pkg = root == null ? null : root.getPackageName();
            if (pkg != null && LAUNCHER.equals(pkg.toString())) {
                if (SystemClock.uptimeMillis() >= suppressUntil) {
                    Log.i(TAG, "boot: Amazon's home is up");
                    coverScreen();
                    redirect(true, false);
                }
                return;
            }
            if (++bootPolls < BOOT_POLLS) handler.postDelayed(this, BOOT_POLL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        coverThread = new HandlerThread("cover", Process.THREAD_PRIORITY_DISPLAY);
        coverThread.start();
        coverHandler = new Handler(coverThread.getLooper());
        registerReceiver(homePressed, new IntentFilter(HOME_PRESSED));
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            bootPolls = 0;
            handler.post(bootCheck);
        }
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(homePressed);
        handler.removeCallbacksAndMessages(null);
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
        if (SystemClock.uptimeMillis() < suppressUntil || previous.startsWith(SETTINGS_PACKAGE)) {
            Log.i(TAG, "Amazon's home after " + previous + ": stay");
            restoreFocus();
            return;
        }
        // Amazon's home can report itself again right after a redirect; that is not a new Home press, and
        // covering it would leave a dark screen with no redirect behind it.
        if (SystemClock.uptimeMillis() - lastRedirect < DEBOUNCE_MS) {
            Log.i(TAG, "Amazon's home again right after a redirect: ignored");
            return;
        }
        Log.i(TAG, "Amazon's home came up after " + previous);
        handler.removeCallbacks(bootCheck);
        coverScreen();
        redirect(true, false);
    }

    private void redirect(boolean allowFallback, boolean fromHomeKey) {
        long now = SystemClock.uptimeMillis();
        if (pending) {
            if (now - pendingSince < STALE_MS) {
                fallback |= allowFallback;
                homeKey |= fromHomeKey;
                return;
            }
            Log.i(TAG, "previous check stuck: restarting");
        }
        pending = true;
        pendingSince = now;
        tries = 0;
        fallback = allowFallback;
        homeKey = fromHomeKey;
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
        AccessibilityNodeInfo before = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        for (AccessibilityNodeInfo node : nodes) {
            if (!node.isVisibleToUser()) continue;
            // The launcher enlarges the button on click and shrinks it only when focus leaves it, so a bare
            // click left it enlarged on Amazon's home. Focus it as the remote would, click, and once the click
            // has enlarged it, hand focus back (behind the cover) so it shrinks and Amazon's home keeps its
            // own focus. Done right away, the hand-back sometimes beat the enlarging. It only reaches Amazon's
            // home while that is still the active window; otherwise restoreFocus() does it on the next visit.
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                if (before != null && !before.equals(node)) {
                    focusToRestore = before;
                    handler.postDelayed(() -> {
                        if (before.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) focusToRestore = null;
                    }, FOCUS_BACK_MS);
                }
                return true;
            }
        }
        return false;
    }

    /** Amazon's home is staying in front: take focus off the apps button if a redirect left it there. */
    private void restoreFocus() {
        AccessibilityNodeInfo target = focusToRestore;
        focusToRestore = null;
        if (target != null && target.refresh()) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
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
