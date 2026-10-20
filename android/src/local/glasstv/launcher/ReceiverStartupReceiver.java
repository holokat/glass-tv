package local.glasstv.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the durable local receiver after system boot or a package replacement without opening UI. */
public final class ReceiverStartupReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent != null && ReceiverStartupPolicy.startsReceiver(intent.getAction())) {
      ReceiverService.start(context);
    }
  }
}
