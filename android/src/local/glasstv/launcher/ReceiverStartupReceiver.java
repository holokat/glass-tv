package local.glasstv.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts an enabled local receiver after boot or package replacement without opening UI. */
public final class ReceiverStartupReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context context, Intent intent) {
    if (intent != null && ReceiverStartupPolicy.startsReceiver(context, intent.getAction())) {
      ReceiverService.start(context);
    }
  }
}
