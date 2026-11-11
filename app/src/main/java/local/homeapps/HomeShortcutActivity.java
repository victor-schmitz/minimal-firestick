package local.homeapps;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Grid tile that opens Amazon's own home screen (rows, search, settings gear). HomeToAppsService leaves
 * Amazon's home opened this way alone; it stays until Home is pressed or another app comes to the front.
 */
public class HomeShortcutActivity extends Activity {
    private static final String LAUNCHER = "com.amazon.tv.launcher";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        HomeToAppsService.suppressUntil = SystemClock.uptimeMillis() + 4000;
        HomeToAppsService.skipHomePressed = true;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (RuntimeException e) {
            HomeToAppsService.suppressUntil = 0;
            HomeToAppsService.skipHomePressed = false;
        }
        finish();
    }
}
