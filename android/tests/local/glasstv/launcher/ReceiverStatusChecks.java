package local.glasstv.launcher;

/** Device-independent checks for the authenticated receiver identity fields. */
public final class ReceiverStatusChecks {
  public static void main(String[] args) {
    check(
        "Living room TV".equals(ReceiverStatus.deviceName(" Living room TV ", "Sony", "BRAVIA")),
        "configured Android device name takes precedence");
    check(
        "Sony BRAVIA XR".equals(ReceiverStatus.deviceName(null, "Sony", "BRAVIA XR")),
        "build manufacturer and model form the fallback name");
    check(
        "Sony BRAVIA XR".equals(ReceiverStatus.deviceName("", "Sony", "Sony BRAVIA XR")),
        "manufacturer-prefixed model is not repeated");
    check(
        "BRAVIA XR".equals(ReceiverStatus.deviceName("", "", "BRAVIA XR")),
        "model remains useful when manufacturer is absent");
    check(
        ReceiverStatus.identityPart(" \n ") == null,
        "blank or control-only identity parts are omitted");
    check(
        ReceiverStatus.identityPart("Sony\u0000TV") == null,
        "control characters do not enter the status response");
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
