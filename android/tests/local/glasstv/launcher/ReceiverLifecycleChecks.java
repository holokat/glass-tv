package local.glasstv.launcher;

/** Device-runnable checks for the receiver's permitted restart path and retry bounds. */
public final class ReceiverLifecycleChecks {
  public static void main(String[] args) {
    check(
        ReceiverStartupPolicy.startsReceiver("android.intent.action.BOOT_COMPLETED"),
        "boot starts the durable receiver");
    check(
        ReceiverStartupPolicy.startsReceiver("android.intent.action.MY_PACKAGE_REPLACED"),
        "package replacement restarts the receiver without launching the activity");
    check(
        !ReceiverStartupPolicy.startsReceiver("android.intent.action.SCREEN_ON"),
        "screen events do not start a foreground receiver from the background");
    check(
        !ReceiverStartupPolicy.startsReceiver("untrusted.action"),
        "unrelated broadcasts cannot start the receiver");
    check(
        !ReceiverStartupPolicy.startsReceiver(false, "android.intent.action.BOOT_COMPLETED"),
        "a receiver stopped from TV settings stays stopped across boot");
    check(
        ReceiverStartupPolicy.startsReceiver(true, "android.intent.action.BOOT_COMPLETED"),
        "an enabled receiver retains its permitted boot restart path");

    ReceiverAttachment<String> attachment = new ReceiverAttachment<>();
    attachment.attach("first activity");
    attachment.detach("different activity");
    check("first activity".equals(attachment.current()), "only the attached activity may detach itself");
    attachment.detach("first activity");
    check(attachment.current() == null, "stopped activity is released before its receiver callback can persist");
    attachment.attach("restarted activity");
    check("restarted activity".equals(attachment.current()), "a later launcher bind replaces the released callback");

    check(LocalSyncServer.retryDelayMillis(0) == 1_000L, "first listener retry is prompt");
    check(LocalSyncServer.retryDelayMillis(1) == 2_000L, "retry backoff doubles once");
    check(LocalSyncServer.retryDelayMillis(5) == 30_000L, "retry backoff reaches its cap");
    check(LocalSyncServer.retryDelayMillis(99) == 30_000L, "retry backoff remains bounded");
    System.out.println("ReceiverLifecycleChecks passed");
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }
}
