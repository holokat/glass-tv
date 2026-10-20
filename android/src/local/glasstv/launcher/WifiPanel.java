package local.glasstv.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.ProxyInfo;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only Wi-Fi information panel. Network selection and saved-network changes stay in Android
 * system settings, where the platform can apply the user's existing policy and credentials.
 */
final class WifiPanel {
  interface Host {
    void onWifiPanelVisibilityChanged();

    void openSystemWifiSettings();

    void openSystemLocationSettings();

    void openAppWifiPermissionSettings();
  }

  private static final int LOCATION_PERMISSION_REQUEST = 4102;
  private final Activity activity;
  private final Context context;
  private final Host host;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final WifiManager wifi;
  private final ConnectivityManager connectivity;
  private final LocationManager location;
  private final FrameLayout overlay;
  private final FrameLayout card;
  private final LinearLayout page;
  private final TextView systemSettings;
  private ScrollView scroll;
  private boolean observing;
  private boolean nearbyRequested;
  private boolean showNearbyAfterPermission;
  private boolean permissionPermanentlyDenied;
  private boolean detailScreen;
  private String requestedFocus = "wifi-current";
  private Snapshot snapshot = Snapshot.empty();

  private final BroadcastReceiver wifiEvents =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context ignored, Intent intent) {
          refreshFromSystem();
        }
      };

  private final ConnectivityManager.NetworkCallback networkEvents =
      new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
          refreshFromSystem();
        }

        @Override
        public void onLost(Network network) {
          refreshFromSystem();
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
          refreshFromSystem();
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
          refreshFromSystem();
        }
      };

  WifiPanel(Activity activity, Host host, FrameLayout parent) {
    this.activity = activity;
    this.context = activity;
    this.host = host;
    wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    location = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

    overlay =
        new FrameLayout(context) {
          @Override
          public View focusSearch(View focused, int direction) {
            View next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction);
            return next == null ? focused : next;
          }
        };
    overlay.setBackgroundColor(0x8f000000);
    overlay.setVisibility(View.GONE);
    overlay.setFocusable(true);
    overlay.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    card = new FrameLayout(context);
    card.setBackground(new PanelBackground());
    overlay.addView(card, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
    page = new LinearLayout(context);
    page.setOrientation(LinearLayout.VERTICAL);
    page.setPadding(dp(30), dp(22), dp(30), dp(22));
    card.addView(
        page,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    systemSettings = button("Wi-Fi settings", "Open TV Wi-Fi settings");
    systemSettings.setTag("wifi-settings");
    systemSettings.setOnClickListener(ignored -> host.openSystemWifiSettings());
    overlay.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          int width = right - left;
          int height = bottom - top;
          if (width <= 0 || height <= 0) return;
          FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) card.getLayoutParams();
          params.width = Math.min(dp(1240), Math.round(width * .76f));
          params.height = Math.min(dp(840), Math.round(height * .88f));
          params.gravity = Gravity.CENTER;
          card.setLayoutParams(params);
        });
    parent.addView(
        overlay,
        new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
  }

  void open() {
    if (isOpen()) {
      restoreFocus();
      return;
    }
    detailScreen = false;
    requestedFocus = "wifi-current";
    overlay.setVisibility(View.VISIBLE);
    startObserving();
    if (hasNetworkNamesPermission() && locationEnabled()) {
      nearbyRequested = true;
      requestWifiScan();
    }
    refresh(true);
    host.onWifiPanelVisibilityChanged();
  }

  boolean isOpen() {
    return overlay.getVisibility() == View.VISIBLE;
  }

  void onHostStarted() {
    if (!isOpen()) return;
    startObserving();
    if (nearbyRequested && hasNetworkNamesPermission() && locationEnabled()) requestWifiScan();
    refresh(true);
  }

  void onHostStopped() {
    stopObserving();
  }

  void destroy() {
    stopObserving();
  }

  boolean handleKey(int keyCode) {
    if (!isOpen() || keyCode != KeyEvent.KEYCODE_BACK) return false;
    if (detailScreen) {
      detailScreen = false;
      requestedFocus = "wifi-current";
      render();
    } else {
      close();
    }
    return true;
  }

  void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode != LOCATION_PERMISSION_REQUEST) return;
    permissionPermanentlyDenied =
        !hasNetworkNamesPermission()
            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && !activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION);
    nearbyRequested = nearbyRequested || showNearbyAfterPermission;
    showNearbyAfterPermission = false;
    if (nearbyRequested && hasNetworkNamesPermission() && locationEnabled()) requestWifiScan();
    refresh(true);
  }

  private void close() {
    if (!isOpen()) return;
    stopObserving();
    overlay.setVisibility(View.GONE);
    host.onWifiPanelVisibilityChanged();
  }

  private void startObserving() {
    if (observing) return;
    observing = true;
    IntentFilter filter = new IntentFilter();
    filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
    filter.addAction(LocationManager.MODE_CHANGED_ACTION);
    filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
    try {
      context.registerReceiver(wifiEvents, filter);
    } catch (Exception ignored) {
    }
    if (connectivity != null) {
      try {
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().build(), networkEvents);
      } catch (Exception ignored) {
      }
    }
  }

  private void stopObserving() {
    if (!observing) return;
    observing = false;
    try {
      context.unregisterReceiver(wifiEvents);
    } catch (Exception ignored) {
    }
    if (connectivity != null) {
      try {
        connectivity.unregisterNetworkCallback(networkEvents);
      } catch (Exception ignored) {
      }
    }
  }

  private void refreshFromSystem() {
    main.post(
        () -> {
          if (observing && isOpen()) refresh(false);
        });
  }

  private void refresh() {
    refresh(false);
  }

  private void refresh(boolean forceRender) {
    if (!isOpen()) return;
    Snapshot updated = readSnapshot();
    if (!forceRender && snapshot.sameContent(updated)) return;
    snapshot = updated;
    if (detailScreen && snapshot.current == null) {
      detailScreen = false;
      requestedFocus = "wifi-current";
    }
    render();
  }

  private Snapshot readSnapshot() {
    if (wifi == null || connectivity == null) return Snapshot.unavailable();
    Network active = connectivity.getActiveNetwork();
    NetworkCapabilities capabilities = active == null ? null : connectivity.getNetworkCapabilities(active);
    boolean ethernet = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
    boolean connectedWifi = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    String state =
        WifiNetwork.activeConnectionState(
            ethernet,
            connectedWifi,
            capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
    if (state == null)
      state =
          wifi.getWifiState() == WifiManager.WIFI_STATE_DISABLED
              ? "Wi-Fi is turned off."
              : "Wi-Fi is not connected.";

    WifiInfo info = null;
    try {
      info = wifi.getConnectionInfo();
    } catch (SecurityException ignored) {
    }
    String ssid =
        WifiNetwork.visibleSsid(
            info == null ? null : info.getSSID(), hasNetworkNamesPermission(), locationEnabled());
    int rssi = info == null ? Integer.MIN_VALUE : usefulRssi(info.getRssi());
    int frequency = info == null ? 0 : info.getFrequency();
    ArrayList<WifiNetwork> nearby = new ArrayList<>();
    String currentSecurity = null;
    if (nearbyRequested && hasNetworkNamesPermission() && locationEnabled()) {
      try {
        Map<String, WifiNetwork> unique = new LinkedHashMap<>();
        for (ScanResult result : wifi.getScanResults()) {
          String name = cleanSsid(result.SSID);
          String key = name == null ? "hidden:" + result.BSSID : name + ":" + security(result.capabilities);
          String label = name == null ? "Hidden network" : name;
          WifiNetwork candidate =
              new WifiNetwork(
                  "nearby:" + key,
                  label,
                  security(result.capabilities),
                  "Available nearby",
                  usefulRssi(result.level),
                  result.frequency,
                  false);
          if (ssid != null && ssid.equals(name)) {
            currentSecurity = candidate.security;
            continue;
          }
          WifiNetwork existing = unique.get(key);
          if (existing == null || candidate.rssi > existing.rssi) unique.put(key, candidate);
        }
        nearby.addAll(unique.values());
      } catch (SecurityException ignored) {
      }
    }
    Collections.sort(
        nearby,
        new Comparator<WifiNetwork>() {
          @Override
          public int compare(WifiNetwork left, WifiNetwork right) {
            return Integer.compare(right.rssi, left.rssi);
          }
        });
    WifiNetwork current =
        connectedWifi
            ? new WifiNetwork(
                "wifi-current",
                ssid,
                currentSecurity,
                currentState(capabilities),
                rssi,
                frequency,
                true)
            : null;
    LinkProperties properties = active == null ? null : connectivity.getLinkProperties(active);
    return new Snapshot(
        active,
        capabilities,
        info,
        current,
        nearby,
        state,
        ethernet,
        String.valueOf(properties));
  }

  private void render() {
    String priorFocus = captureFocus();
    final int previousScroll = scroll == null ? 0 : scroll.getScrollY();
    page.removeAllViews();
    addHeader(detailScreen ? "Network details" : "Wi-Fi", detailScreen ? "wifi-back" : "wifi-close");
    scroll = new ScrollView(context);
    final ScrollView visibleScroll = scroll;
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(0, dp(4), 0, dp(12));
    if (detailScreen) addDetails(content);
    else addNetworkList(content);
    visibleScroll.addView(
        content,
        new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    page.addView(
        visibleScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    LinearLayout.LayoutParams footer =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
    footer.setMargins(0, dp(10), 0, 0);
    page.addView(systemSettings, footer);
    if (priorFocus != null) requestedFocus = priorFocus;
    restoreFocus();
    if (priorFocus == null) visibleScroll.post(() -> visibleScroll.scrollTo(0, previousScroll));
  }

  private void addHeader(String title, String closeTag) {
    LinearLayout header = new LinearLayout(context);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading = text(title, 30, Color.WHITE);
    heading.setTypeface(android.graphics.Typeface.DEFAULT, 1);
    header.addView(heading, new LinearLayout.LayoutParams(0, dp(62), 1f));
    TextView close = button(detailScreen ? "Back" : "Close", detailScreen ? "Back to Wi-Fi" : "Close Wi-Fi");
    close.setTag(closeTag);
    close.setOnClickListener(
        ignored -> {
          if (detailScreen) {
            detailScreen = false;
            requestedFocus = "wifi-current";
            render();
          } else close();
        });
    header.addView(close, new LinearLayout.LayoutParams(dp(150), dp(54)));
    page.addView(header);
  }

  private void addNetworkList(LinearLayout content) {
    addState(content, snapshot.state);
    if (snapshot.ethernet) {
      addSectionLabel(content, "Active connection");
      addStaticRow(content, "Ethernet", "Wi-Fi details are unavailable while Ethernet is active.");
    } else if (snapshot.current != null) {
      addSectionLabel(content, "Current network");
      addCurrentRow(content, snapshot.current);
    } else {
      addSectionLabel(content, "Current network");
      addStaticRow(content, "No Wi-Fi network connected", "Choose a network in Android system settings.");
    }

    if (!hasNetworkNamesPermission()) {
      addSectionLabel(content, "Network names");
      if (permissionPermanentlyDenied)
        addActionRow(
            content,
            "Open app permissions",
            "Allow precise location for Glass TV in Android app settings to reveal Wi-Fi names.",
            "wifi-app-permissions",
            ignored -> host.openAppWifiPermissionSettings());
      else
        addActionRow(
            content,
            "Allow network names",
            "Android requires precise location permission before it shares Wi-Fi names.",
            "wifi-allow-names",
            ignored -> requestNetworkNames(true));
    } else if (!locationEnabled()) {
      addSectionLabel(content, "Nearby networks");
      addActionRow(
          content,
          "Enable location to show nearby networks",
          "Connection details remain available. Android needs Location turned on to share Wi-Fi names and scan results.",
          "wifi-enable-location",
          ignored -> host.openSystemLocationSettings());
    } else {
      addSectionLabel(content, "Nearby networks");
      if (!nearbyRequested) {
        addActionRow(
            content,
            "Show nearby networks",
            "Use Android's latest Wi-Fi scan results.",
            "wifi-show-nearby",
            ignored -> showNearbyNetworks());
      } else if (snapshot.nearby.isEmpty()) {
        addActionRow(
            content,
            "Refresh nearby networks",
            "No Wi-Fi networks are available in Android's latest scan results.",
            "wifi-refresh-nearby",
            ignored -> showNearbyNetworks());
      } else {
        for (WifiNetwork network : snapshot.nearby) addNearbyRow(content, network);
        addActionRow(
            content,
            "Refresh nearby networks",
            "Request an updated scan from Android.",
            "wifi-refresh-nearby",
            ignored -> showNearbyNetworks());
      }
    }
  }

  private void addDetails(LinearLayout content) {
    WifiNetwork current = snapshot.current;
    if (current == null) return;
    addState(content, current.state);
    addSectionLabel(content, "Current network");
    if (current.ssid == null) {
      addStaticRow(content, "Network name unavailable", nameAvailabilityExplanation());
      if (!hasNetworkNamesPermission()) {
        if (permissionPermanentlyDenied)
          addActionRow(
              content,
              "Open app permissions",
              "Allow precise location for Glass TV in Android app settings to reveal this Wi-Fi name.",
              "wifi-app-permissions",
              ignored -> host.openAppWifiPermissionSettings());
        else
          addActionRow(
              content,
              "Allow network names",
              "Allow precise location so Android can provide this Wi-Fi name.",
              "wifi-allow-names",
              ignored -> requestNetworkNames(true));
      } else if (!locationEnabled()) {
        addActionRow(
            content,
            "Enable location",
            "Turn on Android Location to reveal the Wi-Fi name.",
            "wifi-enable-location",
            ignored -> host.openSystemLocationSettings());
      }
    } else addValue(content, "Network name", current.ssid);
    addIfPresent(content, "Security", current.security);
    addIfPresent(content, "Signal strength", signalDetail(current));
    addIfPresent(content, "Band", bandDetail(current.frequencyMhz));
    addIfPresent(content, "Frequency", current.frequencyMhz > 0 ? current.frequencyMhz + " MHz" : null);
    if (snapshot.info != null) {
      addIfPresent(content, "Link speed", linkSpeedDetail(snapshot.info));
    }

    LinkProperties properties =
        snapshot.active == null ? null : connectivity.getLinkProperties(snapshot.active);
    if (properties == null) return;
    addSectionLabel(content, "Network address");
    LinkAddress address = ipv4Address(properties);
    if (address != null) {
      addValue(content, "IP address", address.getAddress().getHostAddress());
      addValue(content, "Subnet", WifiNetwork.ipv4Mask(address.getPrefixLength()));
    }
    addIfPresent(content, "Gateway", gateway(properties));
    addIfPresent(content, "DNS", dns(properties));
    String configuredProxy = proxy(properties);
    addValue(content, "Proxy", configuredProxy == null ? "Off" : configuredProxy);
  }

  private void addCurrentRow(LinearLayout content, WifiNetwork network) {
    String name = network.ssid == null ? "Current Wi-Fi network" : network.ssid;
    String summary = network.security == null ? network.state : network.security + " · " + network.state;
    String signal = network.signalLabel();
    if (signal != null) summary += " · " + signal;
    addNetworkRow(
        content,
        name,
        summary,
        network.key,
        ignored -> {
          detailScreen = true;
          requestedFocus = "wifi-back";
          render();
        });
  }

  private void addNearbyRow(LinearLayout content, WifiNetwork network) {
    String summary = network.security;
    String signal = network.signalLabel();
    if (signal != null) summary += " · " + signal;
    addNetworkRow(
        content,
        network.ssid,
        summary,
        network.key,
        ignored -> host.openSystemWifiSettings());
  }

  private void addState(LinearLayout content, String value) {
    TextView state = text(value, 16, 0xd9ffffff);
    state.setPadding(dp(2), dp(2), dp(2), dp(12));
    content.addView(
        state,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
  }

  private void addSectionLabel(LinearLayout content, String value) {
    TextView label = text(value, 15, 0xbfffffff);
    label.setTypeface(android.graphics.Typeface.DEFAULT, 1);
    label.setPadding(dp(2), dp(14), dp(2), dp(6));
    content.addView(
        label,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
  }

  private void addStaticRow(LinearLayout content, String title, String subtitle) {
    LinearLayout row = row(title, subtitle);
    row.setBackground(new StaticRowBackground());
    content.addView(row, rowLayoutParams());
  }

  private void addActionRow(
      LinearLayout content,
      String title,
      String subtitle,
      String tag,
      View.OnClickListener listener) {
    LinearLayout row = row(title, subtitle);
    configureActionRow(row, tag, listener);
    content.addView(row, rowLayoutParams());
  }

  private void addNetworkRow(
      LinearLayout content,
      String title,
      String subtitle,
      String tag,
      View.OnClickListener listener) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(14), dp(10), dp(12), dp(10));
    TextView wifiIcon = centralIcon("wifi", 24);
    row.addView(wifiIcon, new LinearLayout.LayoutParams(dp(38), dp(50)));
    LinearLayout labels = new LinearLayout(context);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading = text(title, 18, Color.WHITE);
    heading.setSingleLine(true);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    labels.addView(
        heading,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    TextView description = text(subtitle, 14, 0xc9ffffff);
    description.setMaxLines(2);
    description.setPadding(0, dp(3), 0, 0);
    labels.addView(
        description,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    TextView arrow = centralIcon("arrow_right", 18);
    row.addView(arrow, new LinearLayout.LayoutParams(dp(30), dp(50)));
    configureActionRow(row, tag, listener);
    content.addView(row, rowLayoutParams());
  }

  private void configureActionRow(LinearLayout row, String tag, View.OnClickListener listener) {
    row.setTag(tag);
    row.setFocusable(true);
    row.setFocusableInTouchMode(true);
    row.setBackground(new FocusRowBackground());
    row.setOnClickListener(listener);
    row.setOnFocusChangeListener(
        (view, focused) -> {
          view.setScaleX(1f);
          view.setScaleY(1f);
          ((FocusRowBackground) view.getBackground()).setFocused(focused);
        });
  }

  private TextView centralIcon(String name, int sizeDp) {
    TextView icon = new TextView(context);
    icon.setGravity(Gravity.CENTER);
    int id = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
    if (id != 0) {
      Drawable drawable = context.getResources().getDrawable(id);
      int size = dp(sizeDp);
      drawable.setBounds(0, 0, size, size);
      icon.setCompoundDrawables(null, drawable, null, null);
    }
    return icon;
  }

  private LinearLayout row(String title, String subtitle) {
    LinearLayout row = new LinearLayout(context);
    row.setOrientation(LinearLayout.VERTICAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(18), dp(10), dp(18), dp(10));
    TextView heading = text(title, 18, Color.WHITE);
    heading.setSingleLine(true);
    heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
    row.addView(
        heading,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    TextView description = text(subtitle, 14, 0xc9ffffff);
    description.setMaxLines(2);
    description.setPadding(0, dp(3), 0, 0);
    row.addView(
        description,
        new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return row;
  }

  private LinearLayout.LayoutParams rowLayoutParams() {
    LinearLayout.LayoutParams params =
        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.setMargins(0, 0, 0, dp(8));
    return params;
  }

  private void addValue(LinearLayout content, String label, String value) {
    LinearLayout row = new LinearLayout(context);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(18), dp(8), dp(18), dp(8));
    row.setTag("wifi-detail:" + label);
    row.setFocusable(true);
    row.setFocusableInTouchMode(true);
    row.setBackground(new FocusRowBackground());
    row.setOnFocusChangeListener(
        (view, focused) -> ((FocusRowBackground) view.getBackground()).setFocused(focused));
    TextView left = text(label, 15, 0xc9ffffff);
    left.setGravity(Gravity.CENTER_VERTICAL);
    TextView right = text(value, 16, Color.WHITE);
    right.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    row.addView(
        left,
        new LinearLayout.LayoutParams(dp(185), ViewGroup.LayoutParams.WRAP_CONTENT));
    row.addView(
        right,
        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    LinearLayout.LayoutParams params = rowLayoutParams();
    params.setMargins(0, 0, 0, dp(5));
    content.addView(row, params);
  }

  private void addIfPresent(LinearLayout content, String label, String value) {
    if (value != null && value.length() > 0) addValue(content, label, value);
  }

  private TextView button(String value, String description) {
    TextView button = text(value, 16, Color.WHITE);
    button.setGravity(Gravity.CENTER);
    button.setContentDescription(description);
    button.setFocusable(true);
    button.setFocusableInTouchMode(true);
    button.setBackground(new FocusRowBackground());
    button.setOnFocusChangeListener(
        (view, focused) -> {
          view.setScaleX(1f);
          view.setScaleY(1f);
          ((FocusRowBackground) view.getBackground()).setFocused(focused);
        });
    return button;
  }

  private void requestNetworkNames(boolean showNearby) {
    showNearbyAfterPermission = showNearby;
    if (hasNetworkNamesPermission()) {
      nearbyRequested = nearbyRequested || showNearby;
      if (nearbyRequested && locationEnabled()) requestWifiScan();
      refresh(true);
      return;
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      refresh(true);
      return;
    }
    activity.requestPermissions(
        new String[] {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
        LOCATION_PERMISSION_REQUEST);
  }

  private void showNearbyNetworks() {
    nearbyRequested = true;
    if (!hasNetworkNamesPermission()) {
      requestNetworkNames(true);
      return;
    }
    if (!locationEnabled()) {
      render();
      return;
    }
    requestWifiScan();
    refresh(true);
  }

  private void requestWifiScan() {
    if (wifi == null) return;
    try {
      wifi.startScan();
    } catch (SecurityException ignored) {
    }
  }

  private boolean hasNetworkNamesPermission() {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  private boolean locationEnabled() {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.P || location == null || location.isLocationEnabled();
  }

  private String captureFocus() {
    View focus = overlay.findFocus();
    Object tag = focus == null ? null : focus.getTag();
    return tag instanceof String ? (String) tag : null;
  }

  private void restoreFocus() {
    final String wanted = requestedFocus;
    page.post(
        () -> {
          View target = findTagged(page, wanted);
          if (target == null) target = findTagged(page, detailScreen ? "wifi-back" : "wifi-current");
          if (target == null) target = findTagged(page, "wifi-settings");
          if (target != null) target.requestFocus();
        });
  }

  private static View findTagged(View root, String wanted) {
    if (wanted == null) return null;
    if (wanted.equals(root.getTag())) return root;
    if (!(root instanceof ViewGroup)) return null;
    ViewGroup group = (ViewGroup) root;
    for (int i = 0; i < group.getChildCount(); i++) {
      View found = findTagged(group.getChildAt(i), wanted);
      if (found != null) return found;
    }
    return null;
  }

  private String nameAvailabilityExplanation() {
    if (!hasNetworkNamesPermission())
      return "Allow network names to let Android share the connected Wi-Fi name.";
    if (!locationEnabled())
      return "Turn on Android Location to let Android share the connected Wi-Fi name.";
    return "Android did not provide a name for this network.";
  }

  private static String cleanSsid(String value) {
    if (value == null || value.length() == 0 || WifiManager.UNKNOWN_SSID.equals(value)) return null;
    if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\""))
      return value.substring(1, value.length() - 1);
    return value;
  }

  private static int usefulRssi(int value) {
    return value <= -127 ? Integer.MIN_VALUE : value;
  }

  private static String currentState(NetworkCapabilities caps) {
    if (caps == null) return "Connected";
    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return "Internet access verified";
    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) return "Sign-in required";
    return "Local network only";
  }

  private static String security(String capabilities) {
    String value = capabilities == null ? "" : capabilities;
    if (value.contains("SAE")) return "Secured · WPA3";
    if (value.contains("PSK")) return "Secured · WPA2/WPA";
    if (value.contains("EAP")) return "Secured · Enterprise";
    if (value.contains("WEP")) return "Secured · WEP";
    if (value.contains("OWE")) return "Enhanced open";
    return "Open";
  }

  private static String signalDetail(WifiNetwork network) {
    String label = network.signalLabel();
    if (label == null) return null;
    return label + " (" + network.rssi + " dBm)";
  }

  private static String bandDetail(int frequency) {
    if (frequency >= 5925) return "6 GHz";
    if (frequency >= 4900) return "5 GHz";
    if (frequency >= 2400) return "2.4 GHz";
    return null;
  }

  private static String linkSpeedDetail(WifiInfo info) {
    int speed = info.getLinkSpeed();
    if (speed <= 0) return null;
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return speed + " Mbps";
    int tx = info.getTxLinkSpeedMbps();
    int rx = info.getRxLinkSpeedMbps();
    if (tx > 0 && rx > 0) return tx + " Mbps up · " + rx + " Mbps down";
    return speed + " Mbps";
  }

  private static LinkAddress ipv4Address(LinkProperties properties) {
    for (LinkAddress address : properties.getLinkAddresses())
      if (address.getAddress() instanceof Inet4Address) return address;
    return null;
  }

  private static String gateway(LinkProperties properties) {
    ArrayList<String> ipv4 = new ArrayList<>();
    ArrayList<String> other = new ArrayList<>();
    for (RouteInfo route : properties.getRoutes())
      if (route.isDefaultRoute() && route.hasGateway()) {
        String value = route.getGateway().getHostAddress();
        if (route.getGateway() instanceof Inet4Address) ipv4.add(value);
        else other.add(value);
      }
    ipv4.addAll(other);
    return ipv4.isEmpty() ? null : android.text.TextUtils.join("\n", ipv4);
  }

  private static String dns(LinkProperties properties) {
    ArrayList<String> ipv4 = new ArrayList<>();
    ArrayList<String> other = new ArrayList<>();
    for (InetAddress address : properties.getDnsServers()) {
      if (address instanceof Inet4Address) ipv4.add(address.getHostAddress());
      else other.add(address.getHostAddress());
    }
    ipv4.addAll(other);
    return ipv4.isEmpty() ? null : android.text.TextUtils.join("\n", ipv4);
  }

  private static String proxy(LinkProperties properties) {
    ProxyInfo proxy = properties.getHttpProxy();
    if (proxy == null) return null;
    String host = proxy.getHost();
    if (host != null && host.length() > 0) return host + (proxy.getPort() > 0 ? ":" + proxy.getPort() : "");
    return String.valueOf(proxy);
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setGravity(Gravity.CENTER_VERTICAL);
    view.setIncludeFontPadding(false);
    return view;
  }

  private static final class Snapshot {
    final Network active;
    final NetworkCapabilities capabilities;
    final WifiInfo info;
    final WifiNetwork current;
    final List<WifiNetwork> nearby;
    final String state;
    final boolean ethernet;
    final String linkProperties;

    Snapshot(
        Network active,
        NetworkCapabilities capabilities,
        WifiInfo info,
        WifiNetwork current,
        List<WifiNetwork> nearby,
        String state,
        boolean ethernet,
        String linkProperties) {
      this.active = active;
      this.capabilities = capabilities;
      this.info = info;
      this.current = current;
      this.nearby = nearby;
      this.state = state;
      this.ethernet = ethernet;
      this.linkProperties = linkProperties;
    }

    boolean sameContent(Snapshot other) {
      return other != null && contentSignature().equals(other.contentSignature());
    }

    private String contentSignature() {
      StringBuilder values = new StringBuilder();
      values.append(state).append('|').append(ethernet).append('|').append(linkProperties).append('|');
      if (current != null)
        values
            .append(current.ssid)
            .append('|')
            .append(current.security)
            .append('|')
            .append(current.state)
            .append('|')
            .append(current.rssi)
            .append('|')
            .append(current.frequencyMhz);
      if (info != null) {
        values.append('|').append(info.getLinkSpeed());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
          values.append('|').append(info.getTxLinkSpeedMbps()).append('|').append(info.getRxLinkSpeedMbps());
      }
      values.append('|');
      for (WifiNetwork network : nearby)
        values
            .append(network.key)
            .append('|')
            .append(network.ssid)
            .append('|')
            .append(network.security)
            .append('|')
            .append(network.rssi)
            .append('|')
            .append(network.frequencyMhz)
            .append(';');
      return values.toString();
    }

    static Snapshot empty() {
      return new Snapshot(
          null, null, null, null, Collections.<WifiNetwork>emptyList(), "", false, "");
    }

    static Snapshot unavailable() {
      return new Snapshot(
          null,
          null,
          null,
          null,
          Collections.<WifiNetwork>emptyList(),
          "Wi-Fi information is unavailable on this TV.",
          false,
          "");
    }
  }

  private static final class PanelBackground extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public void draw(android.graphics.Canvas canvas) {
      Rect bounds = getBounds();
      RectF surface = new RectF(bounds);
      float radius = Math.min(bounds.width(), bounds.height()) * .04f;
      paint.setColor(Color.WHITE);
      paint.setShader(
          new LinearGradient(
              0,
              bounds.top,
              0,
              bounds.bottom,
              new int[] {0xee34414f, 0xf01a2029},
              null,
              Shader.TileMode.CLAMP));
      canvas.drawRoundRect(surface, radius, radius, paint);
      paint.setShader(null);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.5f);
      paint.setColor(0x79ffffff);
      canvas.drawRoundRect(
          new RectF(surface.left + 1, surface.top + 1, surface.right - 1, surface.bottom - 1),
          radius,
          radius,
          paint);
      paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(android.graphics.ColorFilter filter) {}

    @Override
    public int getOpacity() {
      return android.graphics.PixelFormat.TRANSLUCENT;
    }
  }

  private static class StaticRowBackground extends Drawable {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    public void draw(android.graphics.Canvas canvas) {
      Rect bounds = getBounds();
      paint.setColor(0x2dffffff);
      canvas.drawRoundRect(new RectF(bounds), dpRadius(bounds), dpRadius(bounds), paint);
    }

    float dpRadius(Rect bounds) {
      return Math.min(bounds.height() / 2f, 18f);
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(android.graphics.ColorFilter filter) {}

    @Override
    public int getOpacity() {
      return android.graphics.PixelFormat.TRANSLUCENT;
    }
  }

  private static final class FocusRowBackground extends StaticRowBackground {
    private boolean focused;

    void setFocused(boolean value) {
      focused = value;
      invalidateSelf();
    }

    @Override
    public void draw(android.graphics.Canvas canvas) {
      super.draw(canvas);
      Rect bounds = getBounds();
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(focused ? 3f : 1.25f);
      paint.setColor(focused ? 0xff77adff : 0x73ffffff);
      RectF edge = new RectF(bounds.left + 1, bounds.top + 1, bounds.right - 1, bounds.bottom - 1);
      canvas.drawRoundRect(edge, dpRadius(bounds), dpRadius(bounds), paint);
      paint.setStyle(Paint.Style.FILL);
    }
  }
}
