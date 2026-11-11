# AGENTS.md

This file is for AI coding agents (Claude Code, Codex, Cursor, Copilot and others) and for humans who want the whole picture fast. Read [README.md](README.md) for what the project does for users. This file covers how the project works, why it is built this way, and how to change or port it safely.

## Project in one paragraph

This is an Android app (Java, no dependencies) for Amazon Fire TV. The app ID is `local.inicio` and the Java package is `local.homeapps`. When Amazon's home screen comes to the front, an accessibility service clicks the launcher's own "all apps" button, so the user lands on **Your Apps & Games** (`com.amazon.venezia/.grid.AppsGridLauncherActivity`) instead. A dark overlay hides Amazon's home while this happens. A launcher tile ("Amazon Home") opens Amazon's real home on purpose, and the service leaves that alone.

## Layout

```
app/src/main/java/local/homeapps/
  HomeToAppsService.java     # the accessibility service: detection, click, overlay, fallback
  HomeShortcutActivity.java  # "Amazon Home" tile: opens Amazon's home and sets suppressUntil
app/src/main/res/
  xml/home_to_apps_service.xml  # service config: typeWindowStateChanged, flagReportViewIds, window content
  values/strings.xml            # English (default)
  values-pt/strings.xml         # Portuguese
scripts/
  install.sh                # adb install + turns the service on (keeps other enabled services)
  debloat.sh / restore.sh   # pm disable-user / pm enable over debloat-packages.txt
  debloat-packages.txt      # 36 Amazon packages, grouped and commented
```

## Build

Requirements:

- JDK 17.
- Android SDK with platform 34. Set `ANDROID_HOME` or `sdk.dir` in `local.properties`, which is gitignored.
- AGP 8.5.2, through the Gradle wrapper.

```sh
./gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the debug key (`signingConfig signingConfigs.debug`), so anyone can build and sideload it. Bump `versionCode` in `app/build.gradle` on every change you install, because `adb install -r` needs it to be greater than or equal to the installed one.

There are no unit tests. The behaviour depends on Amazon's launcher, so verification happens on a real device. See [Verify on a device](#verify-on-a-device).

## Fire OS facts the design depends on

Each of these was found the hard way. Do not "simplify" them away without re-testing on a device.

1. **Third-party apps cannot see Home.** Fire OS does not deliver `KEYCODE_HOME` to accessibility services, does not send `ACTION_CLOSE_SYSTEM_DIALOGS` "homekey" to third-party apps, and ignores third-party `CATEGORY_HOME` launchers. The only usable signal is `TYPE_WINDOW_STATE_CHANGED` for `com.amazon.tv.launcher` with a class name that starts with `com.amazon.tv.launcher.ui.HomeActivity`. On Fire OS 7 the class is `HomeActivity_vNext`, which is why the code uses `startsWith`.
2. **Starting an activity right after Home is delayed about 5 seconds.** This is Android's app-switch protection (`stopAppSwitches`, `APP_SWITCH_DELAY_TIME`). A plain `startActivity` from the service lands about 5 seconds late. Clicking the launcher's own button (`com.amazon.tv.launcher:id/nav_bar_app_overflow`) through `AccessibilityNodeInfo.ACTION_CLICK` runs inside the foreground launcher, so the grid opens at once. `startGrid()` exists only as a fallback.
3. **Home pressed while Amazon's home is already in front changes no window.** That press cannot be told apart from browsing. This is a known limit, and there is no event to use.
4. **The launcher enlarges the apps button on click and shrinks it only when focus leaves it.** `clickNode()` focuses the button, clicks it, then hands focus back to the previous node after `FOCUS_BACK_MS` (250 ms). Without that, Amazon's home showed a stuck, enlarged button the next time. `restoreFocus()` finishes the job if Amazon's home is shown again later.
5. **Amazon's settings page lives inside the launcher.** Its marker is `id/settings_search_input`. Back from a settings screen (package `com.amazon.tv.settings*`) also lands on `HomeActivity`, so the service skips the redirect when the previous window was settings or the settings page is visible.
6. **Amazon's home can report itself twice right after a redirect.** `DEBOUNCE_MS` (1.5 s) ignores the echo. Without it, the overlay would cover a screen with no redirect behind it.
7. **The grid's store link screen comes first.** When `com.amazon.venezia` comes up, the service waits for `AppsGridLauncherActivity` itself, then removes the overlay after `COVER_GRACE_MS`.
8. **Sleep.** About 10 minutes after the screen turns off, Fire OS (`HomeLauncherCallback` in system_server) launches Home while the screen is still off. The service redirects then too, so the stick wakes on the grid.
9. **The overlay runs on its own `HandlerThread`.** Looking for the button blocks the main thread while Amazon's home is still loading. When the overlay was drawn from the main thread, Amazon's home showed for up to half a second first. `COVER_MAX_MS` (4 s) makes the overlay fail open.

## Invariants

- No `INTERNET` permission, no analytics and no third-party libraries.
- The service must fail open. Whatever goes wrong, the user ends up on Amazon's home, never on a black screen.
- `minSdk 25`, Java 17 source level, plain Android framework APIs only.
- Do not add a `packageNames` filter to the service config. Events from every app are needed to know what was in front before Amazon's home, for the Back-from-settings case.

## Verify on a device

```sh
adb connect <ip>:5555
./gradlew assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk

# Is the service on and bound?
adb shell settings get secure enabled_accessibility_services   # must contain local.inicio/local.homeapps.HomeToAppsService
adb shell dumpsys accessibility | grep -A3 local.inicio

# Main scenario: open any app, press Home, the grid must come up
adb shell monkey -p <any.installed.app> 1                      # list them: adb shell pm list packages -3
adb shell input keyevent KEYCODE_HOME
adb shell dumpsys window | grep mCurrentFocus                  # expect com.amazon.venezia/...AppsGridLauncherActivity

# What is on screen, with view IDs (how the IDs in the code were found)
adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml
adb exec-out screencap -p > screen.png

# Window and activity history
adb logcat -b events | grep -E "am_new_intent|am_resume_activity|wm_task_moved"
```

Scenarios to check after any change to `HomeToAppsService`:

1. From any app, Home opens the grid with no flash of Amazon's home.
2. Home again from the grid opens the grid.
3. The "Amazon Home" tile opens Amazon's home and stays there.
4. Settings, then a settings screen, then Back stays in Settings.
5. The apps button is not left enlarged on Amazon's home afterwards.
6. Turning off the service (`settings put secure enabled_accessibility_services ""`) returns the stick to stock behaviour.

## Porting to another Fire OS version or model

Most breakage comes from changed IDs or class names. To port:

1. Dump Amazon's home with `uiautomator dump`.
2. Find the "all apps" button's `resource-id` and the settings page marker, and update `APPS_BUTTON` and `SETTINGS_PAGE`.
3. Check the home activity class name in `adb shell dumpsys window | grep mCurrentFocus` and update `HOME_CLASS`.
4. Check that `com.amazon.venezia/.grid.AppsGridLauncherActivity` still exists (`adb shell am start -n com.amazon.venezia/.grid.AppsGridLauncherActivity` should open it), and update `APPS_GRID`.
5. If the grid background differs, update `COVER_COLOR` so the overlay blends in.

Put version-specific values side by side instead of replacing them, so that one APK keeps working on both versions.

## Known gaps (good first contributions)

- After a reboot, the service can bind after Amazon's home is already in front, so there is no redirect until the next Home.
- Fire OS 8 and Fire TV Cube or 4K models are untested.
- There are no prebuilt APKs in Releases yet.

## Debloat list

`scripts/debloat-packages.txt` is the list one Fire TV Stick Lite has run with daily, without problems. Treat any addition as risky. Disabling the wrong system package can break setup, Wi-Fi or the launcher. Additions must stay reversible with `restore.sh`, which means `pm disable-user` only, never `pm uninstall`.
