package local.glasstv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.LruCache;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/** Durable receiver-side movie catalogue, publisher lease and native browse overlay. */
final class MovieLibraryController {
  static final int MAX_LIBRARY_BYTES = 512 * 1024;
  static final int MAX_POSTER_BYTES = 256 * 1024;
  static final int MAX_ITEMS = 1000;
  private static final long LEASE_MS = 15_000L;
  private static final long MAX_POSTERS_BYTES = 128L * 1024L * 1024L;
  private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern UUID_LOWER = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  static final class RequestException extends Exception {
    final int status; final String code;
    RequestException(int status, String code, String message) { super(message); this.status=status; this.code=code; }
  }
  static final class Item {
    final String id, title, posterId; final Integer year; final Long durationMs; final boolean favorite, watched, playable;
    Item(String id, String title, Integer year, Long durationMs, boolean favorite, boolean watched, boolean playable, String posterId) {
      this.id=id; this.title=title; this.year=year; this.durationMs=durationMs; this.favorite=favorite; this.watched=watched; this.playable=playable; this.posterId=posterId;
    }
  }
  private static final class Pending {
    final String id, movieId; String state; String message; final long createdAt;
    Pending(String id, String movieId) { this.id=id; this.movieId=movieId; this.state="queued"; this.createdAt=now(); }
  }

  private final File directory, posters;
  private final AtomicFile catalogFile;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService posterDecode = Executors.newFixedThreadPool(2);
  private final LruCache<String, Bitmap> posterBitmaps = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
    @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
  };
  private final Runnable leaseTicker = new Runnable() {
    @Override public void run() {
      boolean changed;
      synchronized (MovieLibraryController.this) { changed = expirePending(); }
      if (changed) refreshUi(true); else refreshUi(false);
      if (browseOpen) main.postDelayed(this, 1000L);
    }
  };
  private String publisherId, libraryId;
  private final ArrayList<Item> items = new ArrayList<>();
  private long leaseExpiresAt;
  private Pending pending;
  private FrameLayout overlay;
  private TextView status;
  private GridLayout grid;
  private EditText search;
  private Button[] filterButtons;
  private String filter = "all";
  private boolean browseOpen;
  private boolean returnToBrowser;
  private int uiGeneration;
  private boolean enabled;
  private Runnable launcherFocus;

  MovieLibraryController(Context context) {
    directory = new File(context.getFilesDir(), "movie-library");
    posters = new File(directory, "posters");
    directory.mkdirs(); posters.mkdirs();
    catalogFile = new AtomicFile(new File(directory, "catalog.json"));
    load(); // A process restart retains metadata but never resumes a publisher lease.
  }

  synchronized JSONObject replaceLibrary(JSONObject source) throws RequestException {
    Catalog parsed = parseCatalog(source);
    boolean changedPublisher = !same(parsed.publisherId, publisherId) || !same(parsed.libraryId, libraryId);
    boolean pendingRemoved = pending != null && !contains(parsed.items, pending.movieId);
    // Persist the fully validated replacement first. A storage failure leaves the active,
    // requestable catalogue untouched.
    persist(parsed);
    publisherId = parsed.publisherId; libraryId = parsed.libraryId;
    items.clear(); items.addAll(parsed.items);
    if (changedPublisher || pendingRemoved) pending = null;
    evictPosters();
    refreshUi(true);
    return supported();
  }

  synchronized JSONObject poll(JSONObject source) throws RequestException {
    requireExactKeys(source, new String[] {"apiVersion", "publisherId", "libraryId", "ack"});
    if (source.optInt("apiVersion", 0) != 1 || !(source.opt("publisherId") instanceof String) || !(source.opt("libraryId") instanceof String))
      throw invalid("Invalid movie poll");
    String publisher = source.optString("publisherId", ""), library = source.optString("libraryId", "");
    if (!UUID_LOWER.matcher(publisher).matches() || !SHA.matcher(library).matches()) throw invalid("Invalid movie poll");
    if (!same(publisher, publisherId) || !same(library, libraryId))
      throw new RequestException(409, "catalog_mismatch", "Republish the movie library");
    // Evaluate the previous lease before this poll renews it. A reconnect must not revive a
    // preparation abandoned after its publisher disappeared.
    expirePending();
    if (source.has("ack")) acknowledge(source.opt("ack"));
    leaseExpiresAt = now() + LEASE_MS;
    JSONObject out = supported();
    try { out.put("request", pendingJson()); } catch (Exception impossible) { }
    refreshUi(false);
    return out;
  }

  synchronized void clearLease() { leaseExpiresAt = 0; if (pending != null && ("queued".equals(pending.state) || "preparing".equals(pending.state))) pending.state="cancelled"; }
  synchronized boolean hasLiveLease() { return leaseLive(leaseExpiresAt, now()); }

  synchronized void receivedPlaybackStart(String movieId) {
    if (pending != null && pending.movieId.equals(movieId)
        && ("queued".equals(pending.state) || "preparing".equals(pending.state))) {
      pending.state="started";
      browseOpen = false;
      main.post(() -> { if (overlay != null) overlay.setVisibility(View.GONE); });
    }
    refreshUi(false);
  }
  synchronized void playbackStopped() {
    if (!returnToBrowser) return;
    returnToBrowser = false;
    browseOpen = true;
    main.removeCallbacks(leaseTicker);
    main.post(leaseTicker);
    main.post(() -> { if (overlay != null) { overlay.setVisibility(View.VISIBLE); focusCollection(); } });
    refreshUi(true);
  }

  void attach(FrameLayout parent, Runnable launcherFocus) {
    this.launcherFocus = launcherFocus;
    overlay = new FrameLayout(parent.getContext()) {
      @Override public View focusSearch(View focused, int direction) {
        View next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction);
        return next == null ? focused : next;
      }
    };
    overlay.setBackgroundColor(0xf2000000); overlay.setVisibility(View.GONE); overlay.setFocusable(false); overlay.setDescendantFocusability(android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
    LinearLayout body = new LinearLayout(parent.getContext()); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(44), dp(28), dp(44), dp(32));
    LinearLayout heading = new LinearLayout(parent.getContext()); heading.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = text("Movies", 30); title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    Button close = button("Close"); close.setContentDescription("Close movies"); close.setOnClickListener(v -> closeBrowser());
    heading.addView(title, new LinearLayout.LayoutParams(0, dp(62), 1)); heading.addView(close, new LinearLayout.LayoutParams(dp(150), dp(56)));
    body.addView(heading);
    LinearLayout controls = new LinearLayout(parent.getContext()); controls.setGravity(Gravity.CENTER_VERTICAL);
    String[] filters = new String[] {"All", "Favorites", "Watched"};
    filterButtons = new Button[filters.length];
    for (int index = 0; index < filters.length; index++) {
      String entry = filters[index];
      Button b=button(entry); final String value=entry.toLowerCase(Locale.US);
      b.setOnClickListener(v -> { filter=value; refreshUi(true); });
      b.setOnFocusChangeListener((view, focused) -> updateFilterButtons());
      filterButtons[index] = b;
      controls.addView(b,new LinearLayout.LayoutParams(dp(142),dp(52)));
    }
    search = new EditText(parent.getContext()); search.setSingleLine(true); search.setHint("Search movies"); search.setTextColor(0xffffffff); search.setHintTextColor(0xffc5c5c5); search.setBackgroundColor(0x44222222); search.setPadding(dp(14),0,dp(14),0);
    search.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int b,int c){} public void onTextChanged(CharSequence s,int a,int b,int c){refreshUi(true);} public void afterTextChanged(android.text.Editable e){} });
    controls.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1)); body.addView(controls);
    status = text("Choose a movie folder in Glass TV on your Mac.", 17); status.setPadding(0,dp(15),0,dp(8)); body.addView(status);
    ScrollView scroll = new ScrollView(parent.getContext()); grid = new GridLayout(parent.getContext()); grid.setColumnCount(4); grid.setUseDefaultMargins(true); scroll.addView(grid); body.addView(scroll,new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,0,1));
    overlay.addView(body, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
    overlay.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
      if (right - left != oldRight - oldLeft) refreshUi(true);
    });
    parent.addView(overlay,new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
  }

  synchronized void setEnabled(boolean value) {
    boolean closing = enabled && !value && browseOpen;
    enabled = value;
    if (!enabled) {
      returnToBrowser = false;
      if (pending != null && ("queued".equals(pending.state) || "preparing".equals(pending.state))) pending.state = "cancelled";
    }
    if (closing) closeBrowser();
  }
  synchronized boolean isOpen() { return browseOpen; }

  void openBrowser() { if (overlay == null || !enabled) return; browseOpen=true; overlay.setVisibility(View.VISIBLE); focusCollection(); main.removeCallbacks(leaseTicker); main.post(leaseTicker); refreshUi(true); }
  private void focusCollection() {
    if (filterButtons == null) return;
    for (Button button : filterButtons) {
      if (button.getText().toString().toLowerCase(Locale.US).equals(filter)) { button.requestFocus(); return; }
    }
  }
  boolean handleKey(int keyCode) { if (!browseOpen) return false; if (keyCode == KeyEvent.KEYCODE_BACK) { closeBrowser(); return true; } return false; }
  void onHostStopped() {
    synchronized(this) {
      clearLease();
      returnToBrowser = false;
      browseOpen = false;
    }
    main.removeCallbacks(leaseTicker);
    final FrameLayout stoppedOverlay = overlay;
    final Runnable stoppedFocus = launcherFocus;
    main.post(() -> { if (stoppedOverlay != null) stoppedOverlay.setVisibility(View.GONE); if (stoppedFocus != null) stoppedFocus.run(); });
  }

  /** Drops activity-owned views while retaining the durable catalogue and receiver endpoints. */
  void detach() {
    synchronized (this) {
      clearLease();
      returnToBrowser = false;
      browseOpen = false;
    }
    main.removeCallbacks(leaseTicker);
    final FrameLayout detachedOverlay = overlay;
    overlay = null;
    status = null;
    grid = null;
    search = null;
    filterButtons = null;
    launcherFocus = null;
    main.post(() -> {
      if (detachedOverlay != null && detachedOverlay.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup) detachedOverlay.getParent()).removeView(detachedOverlay);
    });
  }
  void destroy() { main.removeCallbacks(leaseTicker); posterDecode.shutdownNow(); posterBitmaps.evictAll(); }

  synchronized File posterFile(String id) { return SHA.matcher(id == null ? "" : id).matches() ? new File(posters, id + ".jpg") : null; }

  synchronized void uploadPoster(String id, InputStream input, int length, String contentType) throws RequestException {
    if (!SHA.matcher(id).matches() || !"image/jpeg".equalsIgnoreCase(contentType)) throw invalid("Invalid poster upload");
    if (length < 1 || length > MAX_POSTER_BYTES) throw new RequestException(413,"poster_too_large","Poster exceeds 256 KiB");
    File temp = new File(directory, "poster-" + UUID.randomUUID() + ".tmp");
    try {
      MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buf=new byte[16384]; int remaining=length;
      try (FileOutputStream out = new FileOutputStream(temp)) { while (remaining > 0) { int n=input.read(buf,0,Math.min(buf.length,remaining)); if(n < 0) throw invalid("Truncated poster"); out.write(buf,0,n); digest.update(buf,0,n); remaining-=n; } out.getFD().sync(); }
      if (!id.equals(hex(digest.digest()))) throw invalid("Poster hash does not match content");
      BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true; BitmapFactory.decodeFile(temp.getAbsolutePath(),bounds);
      if (!"image/jpeg".equalsIgnoreCase(bounds.outMimeType) || bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 512 || bounds.outHeight > 512) throw new RequestException(415,"invalid_poster","Poster must be a JPEG up to 512 by 512");
      File target = new File(posters,id+".jpg");
      long replacing = target.isFile() ? target.length() : 0;
      if (!posterFitsCache(posterStorageBytes(), replacing, temp.length()))
        throw new RequestException(507,"storage_full","Movie poster cache is full");
      if (!temp.renameTo(target) && !target.isFile()) throw new RequestException(507,"storage_full","Could not store poster");
      // Posters are uploaded before their library record. Eviction happens only after the atomic
      // catalogue replacement so an in-flight publish cannot discard its own artwork.
      refreshUi(true);
    } catch (RequestException e) { throw e; }
    catch (Exception e) { throw new RequestException(415,"invalid_poster","Poster could not be read"); }
    finally { if (temp.exists()) temp.delete(); }
  }

  private synchronized void closeBrowser() {
    if (pending != null && ("queued".equals(pending.state) || "preparing".equals(pending.state))) pending.state="cancelled";
    returnToBrowser=false; browseOpen=false; main.removeCallbacks(leaseTicker);
    main.post(() -> { if (overlay != null) overlay.setVisibility(View.GONE); if (launcherFocus != null) launcherFocus.run(); });
    refreshUi(false);
  }
  private synchronized void queue(Item item) { expirePending(); if (!enabled) { refreshUi(false); return; } if (!hasLiveLease()) { refreshUi(false); return; } if (!item.playable) { refreshUi(false); return; } if (pending != null && ("queued".equals(pending.state) || "preparing".equals(pending.state))) return; pending=new Pending(UUID.randomUUID().toString(),item.id); returnToBrowser=true; refreshUi(true); }
  private synchronized boolean expirePending() {
    if (pending != null && requestExpired(pending.state, pending.createdAt, leaseExpiresAt, now())) {
      pending.state="cancelled";
      return true;
    }
    return false;
  }

  private void refreshUi(boolean rebuildGrid) {
    final int generation = rebuildGrid ? ++uiGeneration : uiGeneration;
    main.post(() -> render(generation, rebuildGrid));
  }
  private void render(int generation, boolean rebuildGrid) {
    if (overlay == null || !browseOpen || generation != uiGeneration) return;
    final ArrayList<Item> visible; final String query; final boolean live; final Pending current;
    synchronized(this) { expirePending(); visible=filtered(); query=search==null?"":search.getText().toString(); live=hasLiveLease(); current=pending; }
    boolean canSelect = live && (current == null || !("queued".equals(current.state) || "preparing".equals(current.state)));
    updateFilterButtons();
    if (rebuildGrid) {
      Object focused = overlay.findFocus() == null ? null : overlay.findFocus().getTag();
      grid.removeAllViews();
      int contentWidth = Math.max(1, overlay.getWidth() - dp(88));
      int columns = Math.max(2, Math.min(6, contentWidth / dp(225)));
      grid.setColumnCount(columns);
      for (Item item : visible) addMovieTile(item, generation, canSelect && item.playable);
      if (focused != null) {
        for (int i=0; i<grid.getChildCount(); i++) if (focused.equals(grid.getChildAt(i).getTag())) { grid.getChildAt(i).requestFocus(); break; }
      }
    } else {
      for (int i=0; i<grid.getChildCount(); i++) {
        View tile=grid.getChildAt(i);
        Item item=itemForId(String.valueOf(tile.getTag()));
        boolean enabled=item != null && item.playable && canSelect;
        tile.setEnabled(enabled);
        tile.setAlpha(enabled ? 1f : .52f);
      }
    }
    if (visible.isEmpty()) setStatus(items.isEmpty() ? "Choose a movie folder in Glass TV on your Mac." : "No movies match this collection.");
    else if (!live) setStatus("Open Glass TV on your Mac and connect to this TV.");
    else if (current != null && ("queued".equals(current.state) || "preparing".equals(current.state))) setStatus("Preparing movie on your Mac.");
    else if (current != null && "error".equals(current.state)) setStatus(current.message == null ? "Movie preparation failed." : current.message);
    else setStatus("Select a movie to play.");
  }
  private void setStatus(String message) {
    if (!message.contentEquals(status.getText())) status.setText(message);
  }
  private synchronized ArrayList<Item> filtered() {
    String query=search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.US); ArrayList<Item> result=new ArrayList<>();
    for(Item item:items) { if("all".equals(filter) && item.watched) continue; if("favorites".equals(filter) && !item.favorite) continue; if("watched".equals(filter) && !item.watched) continue; if(!query.isEmpty() && !item.title.toLowerCase(Locale.US).contains(query)) continue; result.add(item); }
    Collections.sort(result,new Comparator<Item>() { public int compare(Item a,Item b) { if(a.favorite!=b.favorite) return a.favorite?-1:1; return a.title.compareToIgnoreCase(b.title); }}); return result;
  }
  private void addMovieTile(final Item item, final int generation, boolean enabled) {
    LinearLayout tile=new LinearLayout(overlay.getContext()); tile.setOrientation(LinearLayout.VERTICAL); tile.setPadding(dp(8),dp(8),dp(8),dp(14)); tile.setFocusable(true); tile.setClickable(true); tile.setBackground(tileBackground(false)); tile.setContentDescription(item.title); tile.setTag(item.id); tile.setEnabled(enabled);
    tile.setAlpha(enabled ? 1f : .52f);
    tile.setOnFocusChangeListener((view, focused) -> { view.setBackground(tileBackground(focused)); view.setScaleX(focused ? 1.04f : 1f); view.setScaleY(focused ? 1.04f : 1f); });
    ImageView image=new ImageView(overlay.getContext()); image.setScaleType(ImageView.ScaleType.CENTER_CROP); int movieIcon=DrawableResources.id(overlay.getContext(),"movies"); if(movieIcon!=0) image.setImageResource(movieIcon); tile.addView(image,new LinearLayout.LayoutParams(dp(190),dp(220)));
    TextView label=text(item.title + details(item),16); label.setMaxLines(2); tile.addView(label,new LinearLayout.LayoutParams(dp(190),dp(54))); tile.setOnClickListener(v -> queue(item));
    GridLayout.LayoutParams lp=new GridLayout.LayoutParams(); lp.width=dp(210); lp.height=dp(310); grid.addView(tile,lp);
    File poster=posterFile(item.posterId); if(poster == null || !poster.isFile()) return;
    Bitmap cached=posterBitmaps.get(item.posterId); if(cached!=null) { image.setImageBitmap(cached); return; }
    posterDecode.execute(() -> { BitmapFactory.Options o=new BitmapFactory.Options(); o.inPreferredConfig=Bitmap.Config.RGB_565; final Bitmap bitmap=BitmapFactory.decodeFile(poster.getAbsolutePath(),o); if(bitmap==null)return; main.post(() -> { if(generation==uiGeneration && browseOpen) { posterBitmaps.put(item.posterId, bitmap); image.setImageBitmap(bitmap); } }); });
  }
  private void updateFilterButtons() {
    if (filterButtons == null) return;
    for (Button button : filterButtons) {
      boolean selected = button.getText().toString().toLowerCase(Locale.US).equals(filter);
      button.setSelected(selected);
      button.setAlpha(selected || button.hasFocus() ? 1f : .65f);
      GradientDrawable background = new GradientDrawable();
      background.setColor(selected ? 0xff5b5b5b : 0x44333333);
      background.setCornerRadius(dp(8));
      if (button.hasFocus()) background.setStroke(dp(3), 0xffffffff);
      else if (selected) background.setStroke(dp(1), 0xffaaaaaa);
      button.setBackground(background);
    }
  }
  private synchronized Item itemForId(String id) { for (Item item : items) if (item.id.equals(id)) return item; return null; }
  private static String details(Item item) { StringBuilder s=new StringBuilder(); if(item.year!=null)s.append(" · ").append(item.year); if(item.durationMs!=null)s.append(" · ").append(item.durationMs/60000).append(" min"); return s.toString(); }

  private synchronized void acknowledge(Object raw) throws RequestException {
    if (!(raw instanceof JSONObject)) throw invalid("Invalid movie acknowledgement"); JSONObject ack=(JSONObject)raw; requireExactKeys(ack,new String[]{"requestId","state","message"});
    if (!(ack.opt("requestId") instanceof String) || !(ack.opt("state") instanceof String)) throw invalid("Invalid movie acknowledgement"); String id=ack.optString("requestId",""), state=ack.optString("state",""); if(!UUID_LOWER.matcher(id).matches() || !("preparing".equals(state)||"started".equals(state)||"error".equals(state))) throw invalid("Invalid movie acknowledgement");
    if(ack.has("message") && (!(ack.opt("message") instanceof String)||ack.optString("message").length()>240)) throw invalid("Invalid movie acknowledgement");
    if(pending==null || !pending.id.equals(id) || !ackCanAdvance(pending.state, state)) return;
    if("preparing".equals(state)) pending.state="preparing";
    else if("started".equals(state)) { pending.state="started"; browseOpen=false; main.post(() -> { if (overlay != null) overlay.setVisibility(View.GONE); }); }
    else { pending.state="error"; pending.message=ack.optString("message",null); }
  }
  private JSONObject pendingJson() {
    if(pending==null) return null; try { JSONObject out=new JSONObject(); out.put("requestId",pending.id);out.put("movieId",pending.movieId);out.put("state",pending.state); if(pending.message!=null)out.put("message",pending.message);return out; }catch(Exception impossible){return null;}
  }
  private void load() { if(!catalogFile.getBaseFile().isFile())return; try(FileInputStream in=catalogFile.openRead()){byte[] data=new byte[(int)Math.min(catalogFile.getBaseFile().length(),MAX_LIBRARY_BYTES)];int offset=0,n;while(offset<data.length&&(n=in.read(data,offset,data.length-offset))>0)offset+=n;Catalog parsed=parseCatalog(new JSONObject(new String(data,0,offset,StandardCharsets.UTF_8)));publisherId=parsed.publisherId;libraryId=parsed.libraryId;items.addAll(parsed.items);}catch(Exception ignored){publisherId=null;libraryId=null;items.clear();} leaseExpiresAt=0; }
  private void persist(Catalog catalog) throws RequestException { FileOutputStream out=null;try {JSONObject json=new JSONObject();json.put("apiVersion",1);json.put("publisherId",catalog.publisherId);json.put("libraryId",catalog.libraryId);JSONArray array=new JSONArray();for(Item i:catalog.items){JSONObject v=new JSONObject().put("id",i.id).put("title",i.title).put("favorite",i.favorite).put("watched",i.watched).put("playable",i.playable);if(i.year!=null)v.put("year",i.year);if(i.durationMs!=null)v.put("durationMs",i.durationMs);if(i.posterId!=null)v.put("posterId",i.posterId);array.put(v);}json.put("items",array);out=catalogFile.startWrite();out.write(json.toString().getBytes(StandardCharsets.UTF_8));catalogFile.finishWrite(out);}catch(Exception e){if(out!=null)catalogFile.failWrite(out);throw new RequestException(507,"storage_full","Could not save movie library");} }
  private long posterStorageBytes() { long total=0; File[] files=posters.listFiles(); if(files!=null) for(File file:files) if(file.isFile()) total+=file.length(); return total; }
  private void evictPosters() { Set<String> keep=new HashSet<>();for(Item i:items)if(i.posterId!=null)keep.add(i.posterId);File[] files=posters.listFiles();long used=0;if(files!=null)for(File file:files){String n=file.getName();String id=n.endsWith(".jpg")?n.substring(0,n.length()-4):"";if(!file.isFile()||!SHA.matcher(id).matches()||!keep.contains(id)){file.delete();}else used+=file.length();}if(used<=MAX_POSTERS_BYTES)return;ArrayList<File> remaining=new ArrayList<>();files=posters.listFiles();if(files!=null)for(File f:files)if(f.isFile())remaining.add(f);Collections.sort(remaining,(a,b)->Long.compare(a.lastModified(),b.lastModified()));for(File f:remaining){if(used<=MAX_POSTERS_BYTES)break;used-=f.length();f.delete();} }

  private GradientDrawable tileBackground(boolean focused) { GradientDrawable background=new GradientDrawable(); background.setColor(focused ? 0xff3f3f3f : 0x33222222); background.setCornerRadius(dp(8)); if(focused) background.setStroke(dp(3), 0xffffffff); return background; }
  private static long now() { return SystemClock.elapsedRealtime(); }
  static boolean leaseLive(long expiresAt, long now) { return expiresAt > now; }
  static boolean queuedExpired(long createdAt, long now) { return now >= createdAt && now - createdAt > LEASE_MS; }
  static boolean requestExpired(String state, long createdAt, long leaseExpiresAt, long now) {
    if ("queued".equals(state)) return queuedExpired(createdAt, now);
    return "preparing".equals(state) && !leaseLive(leaseExpiresAt, now);
  }
  static boolean ackCanAdvance(String current, String next) {
    if ("cancelled".equals(current)) return false;
    if ("queued".equals(current)) return "preparing".equals(next) || "started".equals(next) || "error".equals(next);
    return "preparing".equals(current) && ("started".equals(next) || "error".equals(next));
  }
  static boolean posterFitsCache(long existing, long replacing, long incoming) {
    return existing >= replacing && incoming >= 0 && existing - replacing + incoming <= MAX_POSTERS_BYTES;
  }
  static void validateForChecks(JSONObject source) throws RequestException { parseCatalog(source); }
  private static Catalog parseCatalog(JSONObject source) throws RequestException { requireExactKeys(source,new String[]{"apiVersion","publisherId","libraryId","items"});if(source.optInt("apiVersion",0)!=1||!(source.opt("publisherId") instanceof String)||!(source.opt("libraryId") instanceof String)||!(source.opt("items") instanceof JSONArray))throw invalid("Invalid movie library");String publisher=source.optString("publisherId",""),library=source.optString("libraryId","");if(!UUID_LOWER.matcher(publisher).matches()||!SHA.matcher(library).matches())throw invalid("Invalid movie library");JSONArray raw=source.optJSONArray("items");if(raw.length()>MAX_ITEMS)throw invalid("Too many movies");ArrayList<Item> result=new ArrayList<>();Set<String> ids=new HashSet<>();for(int x=0;x<raw.length();x++){Object value=raw.opt(x);if(!(value instanceof JSONObject))throw invalid("Invalid movie item");JSONObject item=(JSONObject)value;requireExactKeys(item,new String[]{"id","title","year","durationMs","favorite","watched","playable","posterId"});if(!(item.opt("id") instanceof String)||!(item.opt("title") instanceof String)||!(item.opt("favorite") instanceof Boolean)||!(item.opt("watched") instanceof Boolean)||!(item.opt("playable") instanceof Boolean))throw invalid("Invalid movie item");String id=item.optString("id",""),title=item.optString("title","");if(!SHA.matcher(id).matches()||!ids.add(id)||title.length()<1||title.length()>240)throw invalid("Invalid movie item");Integer year=null;if(item.has("year")){Object y=item.opt("year");if(!(y instanceof Number)||((Number)y).doubleValue()!=((Number)y).longValue()||((Number)y).longValue()<0||((Number)y).longValue()>9999)throw invalid("Invalid movie item");year=(int)((Number)y).longValue();}Long duration=null;if(item.has("durationMs")){Object d=item.opt("durationMs");if(!(d instanceof Number)||((Number)d).doubleValue()!=((Number)d).longValue()||((Number)d).longValue()<0)throw invalid("Invalid movie item");duration=((Number)d).longValue();}String poster=null;if(item.has("posterId")){if(!(item.opt("posterId") instanceof String)||!SHA.matcher(item.optString("posterId","")).matches())throw invalid("Invalid movie item");poster=item.optString("posterId","");}result.add(new Item(id,title,year,duration,item.optBoolean("favorite"),item.optBoolean("watched"),item.optBoolean("playable"),poster));}return new Catalog(publisher,library,result); }
  private static void requireExactKeys(JSONObject value,String[] allowed)throws RequestException{Set<String> set=new HashSet<>();Collections.addAll(set,allowed);java.util.Iterator<String>keys=value.keys();while(keys.hasNext())if(!set.contains(keys.next()))throw invalid("Unknown movie field");}
  private static boolean contains(ArrayList<Item> list,String id){for(Item i:list)if(i.id.equals(id))return true;return false;}
  private static RequestException invalid(String message){return new RequestException(400,"invalid",message);} private static boolean same(String a,String b){return a==null?b==null:a.equals(b);} private static JSONObject supported(){try{return new JSONObject().put("supported",true);}catch(Exception e){return new JSONObject();}} private static String hex(byte[]b){StringBuilder s=new StringBuilder(b.length*2);for(byte v:b)s.append(String.format(Locale.US,"%02x",v&255));return s.toString();}
  private static final class Catalog { final String publisherId,libraryId;final ArrayList<Item>items;Catalog(String p,String l,ArrayList<Item>i){publisherId=p;libraryId=l;items=i;} }
  private TextView text(String value,int size){TextView v=new TextView(overlay==null?null:overlay.getContext());v.setText(value);v.setTextColor(0xffffffff);v.setTextSize(size);v.setGravity(Gravity.CENTER_VERTICAL);return v;} private Button button(String value){Button b=new Button(overlay==null?null:overlay.getContext());b.setText(value);b.setAllCaps(false);return b;} private int dp(int v){return Math.round(v*(overlay==null?1:overlay.getResources().getDisplayMetrics().density));}
}
