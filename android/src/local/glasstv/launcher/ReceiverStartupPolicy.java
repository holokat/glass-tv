package local.glasstv.launcher;

/** Limits background receiver startup to system lifecycle broadcasts permitted for this receiver. */
final class ReceiverStartupPolicy {
  private ReceiverStartupPolicy() {}

  static boolean startsReceiver(String action) {
    return "android.intent.action.BOOT_COMPLETED".equals(action)
        || "android.intent.action.MY_PACKAGE_REPLACED".equals(action);
  }
}
