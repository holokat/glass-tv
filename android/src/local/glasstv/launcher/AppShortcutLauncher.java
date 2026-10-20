package local.glasstv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;
import java.util.function.Consumer;

/** Opens official TV apps, or hands installation and enablement to the system UI. */
final class AppShortcutLauncher {
  static final String STORE = "com.android.vending";
  static final String HULU_US = "com.hulu.livingroomplus";
  static final String HULU_JAPAN = "jp.happyon.android";
  private final Activity activity;
  private final Consumer<AlertDialog> showDialog;

  AppShortcutLauncher(Activity activity, Consumer<AlertDialog> showDialog) {
    this.activity = activity;
    this.showDialog = showDialog;
  }

  /** Selects an installed official TV variant before offering the catalog's first Play listing. */
  void open(String[] packageNames, String label) {
    if (packageNames == null || packageNames.length == 0) {
      showDialog.accept(new AlertDialog.Builder(activity)
          .setTitle(label + " is unavailable")
          .setMessage("This shortcut has no official Google Play listing configured.")
          .setPositiveButton("OK", null).create());
      return;
    }
    for (String candidate : packageNames) {
      ApplicationInfo info = installedInfo(candidate);
      if (info != null && info.enabled
          && activity.getPackageManager().getLeanbackLaunchIntentForPackage(candidate) != null) {
        open(candidate, label);
        return;
      }
    }
    for (String candidate : packageNames) {
      if (installedInfo(candidate) != null) {
        open(candidate, label);
        return;
      }
    }
    open(packageNames[0], label);
  }

  void open(String packageName, String label) {
    if (HULU_US.equals(packageName)) {
      if (installedInfo(HULU_US) == null && installedInfo(HULU_JAPAN) == null) {
        showDialog.accept(new AlertDialog.Builder(activity)
            .setTitle("Install Hulu")
            .setItems(new String[] {"Hulu (Japan)", "Hulu (United States)"},
                (dialog, which) -> openStore(which == 0 ? HULU_JAPAN : HULU_US, "Hulu"))
            .setNegativeButton("Cancel", null).create());
        return;
      }
      packageName = huluPackage();
    }
    final String target = packageName;
    ApplicationInfo info = installedInfo(target);
    if (info == null) {
      showDialog.accept(new AlertDialog.Builder(activity)
          .setTitle("Install " + label + "?")
          .setMessage(label + " is not installed on this TV. Open its official Google Play page to install it. Availability depends on your TV and region.")
          .setPositiveButton("Install", (dialog, which) -> openStore(target, label))
          .setNegativeButton("Cancel", null).create());
      return;
    }
    if (!info.enabled) {
      showSettings(target, "Enable " + label, label + " is disabled. Enable it in Android app settings, then select this shortcut again.");
      return;
    }
    Intent launch = activity.getPackageManager().getLeanbackLaunchIntentForPackage(target);
    if (launch != null) {
      try { activity.startActivity(launch); return; }
      catch (RuntimeException unavailable) { /* Explain the failure with a useful next action. */ }
    }
    showDialog.accept(new AlertDialog.Builder(activity)
        .setTitle(label + " could not open")
        .setMessage("Check the official TV app for an update in Google Play.")
        .setPositiveButton("Open Google Play", (dialog, which) -> openStore(target, label))
        .setNegativeButton("Cancel", null).create());
  }

  private void openStore(String packageName, String label) {
    ApplicationInfo store = installedInfo(STORE);
    if (store != null && !store.enabled) {
      showSettings(STORE, "Enable Google Play Store", "Google Play Store is disabled. Enable it in Android app settings to install " + label + ".");
      return;
    }
    if (store != null) {
      try { activity.startActivity(storeIntent(packageName)); return; }
      catch (RuntimeException unavailable) { /* No browser or untrusted APK fallback. */ }
    }
    showDialog.accept(new AlertDialog.Builder(activity)
        .setTitle("Google Play Store is unavailable")
        .setMessage("Restore or enable Google Play Store on this TV to install " + label + ". Your shortcut will stay visible.")
        .setPositiveButton("Open settings", (dialog, which) -> openSettings(null))
        .setNegativeButton("Cancel", null).create());
  }

  static Intent storeIntent(String packageName) {
    Uri uri = Uri.parse("https://play.google.com/store/apps/details")
        .buildUpon().appendQueryParameter("id", packageName).build();
    return new Intent(Intent.ACTION_VIEW, uri).setPackage(STORE);
  }

  private ApplicationInfo installedInfo(String packageName) {
    try {
      ApplicationInfo info = activity.getPackageManager().getApplicationInfo(packageName, 0);
      return (info.flags & ApplicationInfo.FLAG_INSTALLED) != 0 ? info : null;
    } catch (PackageManager.NameNotFoundException unavailable) { return null; }
  }

  private String huluPackage() {
    // Prefer a launchable installed variant. Missing apps use an explicit region choice.
    for (String candidate : new String[] {HULU_JAPAN, HULU_US}) {
      ApplicationInfo info = installedInfo(candidate);
      if (info != null && info.enabled
          && activity.getPackageManager().getLeanbackLaunchIntentForPackage(candidate) != null) return candidate;
    }
    for (String candidate : new String[] {HULU_JAPAN, HULU_US}) {
      if (installedInfo(candidate) != null) return candidate;
    }
    return HULU_US;
  }

  private void showSettings(String packageName, String title, String message) {
    showDialog.accept(new AlertDialog.Builder(activity).setTitle(title).setMessage(message)
        .setPositiveButton("Open settings", (dialog, which) -> openSettings(packageName))
        .setNegativeButton("Cancel", null).create());
  }

  private void openSettings(String packageName) {
    Intent intent = packageName == null ? new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + packageName));
    try { activity.startActivity(intent); }
    catch (RuntimeException unavailable) {
      showDialog.accept(new AlertDialog.Builder(activity).setTitle("App settings unavailable")
          .setMessage("Open Apps in the TV's system settings to manage installed apps.")
          .setPositiveButton("OK", null).create());
    }
  }
}
