package local.glasstv.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import java.net.InetAddress;
import org.json.JSONObject;

/** Application-owned foreground lifetime for the authenticated local TLS receiver. */
public final class ReceiverService extends Service implements LocalSyncServer.DeviceModel, LocalSyncServer.Listener {
  private static final String CHANNEL_ID = "glass-tv-receiver";
  private static final int NOTIFICATION_ID = 8743;
  private final IBinder binder = new LocalBinder();
  private final ReceiverAttachment<LocalSyncServer.DeviceModel> attachment = new ReceiverAttachment<>();
  private LocalSyncServer server;
  private MovieLibraryController movieLibrary;

  public final class LocalBinder extends Binder {
    ReceiverService service() { return ReceiverService.this; }
  }

  static void start(Context context) {
    try {
      context.startForegroundService(new Intent(context, ReceiverService.class));
    } catch (IllegalStateException | SecurityException unavailable) {
      android.util.Log.w("GlassTV", "Local receiver service could not start", unavailable);
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();
    showForeground("Starting local receiver");
    movieLibrary = new MovieLibraryController(this);
    server = new LocalSyncServer(this, this, movieLibrary, this);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    server.start();
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) { return binder; }

  LocalSyncServer server() { return server; }
  MovieLibraryController movieLibrary() { return movieLibrary; }

  void attach(LocalSyncServer.DeviceModel model) { attachment.attach(model); }

  void detach(LocalSyncServer.DeviceModel model) {
    attachment.detach(model);
  }

  @Override
  public void onDestroy() {
    attachment.detach(attachment.current());
    if (server != null) server.destroy();
    if (movieLibrary != null) movieLibrary.destroy();
    super.onDestroy();
  }

  @Override
  public void onListenerStateChanged(boolean listening, String errorClass) {
    showForeground(
        listening
            ? "Local receiver is available"
            : errorClass == null ? "Local receiver stopped" : "Retrying local receiver");
  }

  @Override
  public JSONObject status() throws Exception {
    LocalSyncServer.DeviceModel model = attachment.current();
    if (model != null) return model.status();
    return ReceiverStatus.base(this, server);
  }

  @Override
  public boolean isPassthroughInput(String id) {
    LocalSyncServer.DeviceModel model = attachment.current();
    if (model != null) return model.isPassthroughInput(id);
    return ReceiverStatus.isPassthroughInput(this, id);
  }

  @Override
  public void onPlaylistChanged(PlaylistStore.Playlist playlist) {
    LocalSyncServer.DeviceModel model = attachment.current();
    if (model != null) model.onPlaylistChanged(playlist);
  }

  @Override
  public JSONObject movieStatus() throws MovieController.MovieRequestException {
    LocalSyncServer.DeviceModel model = attachment.current();
    if (model != null) return model.movieStatus();
    throw unavailable();
  }

  @Override
  public JSONObject playMovie(JSONObject request, InetAddress peer) throws MovieController.MovieRequestException {
    LocalSyncServer.DeviceModel model = attachment.current();
    if (model != null) return model.playMovie(request, peer);
    throw unavailable();
  }

  @Override
  public JSONObject controlMovie(JSONObject request) throws MovieController.MovieRequestException {
    LocalSyncServer.DeviceModel model = attachment.current();
    if (model != null) return model.controlMovie(request);
    throw unavailable();
  }

  private MovieController.MovieRequestException unavailable() {
    return new MovieController.MovieRequestException(503, "unsupported", "Open Glass TV to control movie playback");
  }

  private void showForeground(String message) {
    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (manager != null) {
      NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Glass TV receiver", NotificationManager.IMPORTANCE_LOW);
      channel.setDescription("Keeps local Mac sync available while another TV app is open.");
      manager.createNotificationChannel(channel);
    }
    Notification notification = new Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.app_icon)
        .setContentTitle("Glass TV")
        .setContentText(message)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setOngoing(true)
        .build();
    startForeground(NOTIFICATION_ID, notification);
  }
}
