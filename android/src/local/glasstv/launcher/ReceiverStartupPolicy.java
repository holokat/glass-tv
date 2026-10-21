package local.glasstv.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the user's receiver choice and limits automatic startup to permitted lifecycle broadcasts. */
final class ReceiverStartupPolicy {
  private static final String PREFERENCES = "glass-tv-private";
  private static final String ENABLED = "receiverEnabled";

  private ReceiverStartupPolicy() {}

  /** Existing installations retain their receiver until the person using the TV explicitly stops it. */
  static boolean isReceiverEnabled(Context context) {
    return preferences(context).getBoolean(ENABLED, true);
  }

  static void setReceiverEnabled(Context context, boolean enabled) {
    preferences(context).edit().putBoolean(ENABLED, enabled).apply();
  }

  static boolean startsReceiver(Context context, String action) {
    return startsReceiver(isReceiverEnabled(context), action);
  }

  static boolean startsReceiver(String action) {
    return startsReceiver(true, action);
  }

  static boolean startsReceiver(boolean receiverEnabled, String action) {
    return receiverEnabled
        && ("android.intent.action.BOOT_COMPLETED".equals(action)
            || "android.intent.action.MY_PACKAGE_REPLACED".equals(action));
  }

  private static SharedPreferences preferences(Context context) {
    return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
  }
}
