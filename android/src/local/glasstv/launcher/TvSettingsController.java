package local.glasstv.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native TV settings navigation for launcher shortcuts and weather. It deliberately operates on
 * small copied preference documents; the host commits each edit through the receiver's atomic
 * settings mutation instead of writing preferences from the UI thread.
 */
final class TvSettingsController {
  interface Host {
    interface SaveResult { void complete(boolean saved); }
    JSONObject apps();
    JSONObject weather();
    void saveApps(JSONObject apps, SaveResult result);
    void saveWeather(JSONObject weather, SaveResult result);
    void refreshWeather();
    void previewNavigationSound(JSONObject apps);
    void showDialog(AlertDialog dialog, Runnable onDismiss);
    void pairMac();
    boolean companionReceiverEnabled();
    void setCompanionReceiverEnabled(boolean enabled);
    void chooseInput();
    void openPrivacyPolicy();
    void openSystemSettings();
  }

  private final Context context;
  private final Host host;
  private final WeatherCitySearch citySearch = new WeatherCitySearch();
  private final Handler main = new Handler(Looper.getMainLooper());
  private boolean destroyed;
  private AlertDialog visibleDialog;
  /** Changes whenever the visible page changes, so late saves cannot reopen an abandoned page. */
  private int pageGeneration;
  private WeatherWidget.ArtworkCatalog.LoadedCatalog artworkCatalog;
  private LinearLayout lastBuiltPage;
  private String lastSearchQuery = "";
  private String currentPage = "";
  private final java.util.Map<String, String> pageFocus = new java.util.HashMap<>();

  TvSettingsController(Context context, Host host) {
    this.context = context;
    this.host = host;
  }

  void showRoot() {
    show("Glass TV settings", buildRootPage(), null, null);
  }

  /** Package-private for the TV bitmap check; it builds the same content without showing a dialog. */
  LinearLayout buildRootPage() {
    LinearLayout content = content();
    lastBuiltPage = content;
    addHeading(content, "Glass TV settings", "");
    addAction(content, "Apps", "Shortcuts and navigation sound", this::showApps);
    addAction(content, "Weather", "Cities and appearance", this::showWeather);
    addAction(content, "Pair Mac", "Connect a Mac companion", host::pairMac);
    addAction(
        content,
        "Mac companion receiver",
        host.companionReceiverEnabled() ? "Running after restart" : "Stopped",
        this::showCompanionReceiver);
    addAction(content, "TV input", "Choose an HDMI input", host::chooseInput);
    addAction(content, "Privacy policy", "Open glasstv.xyz/privacy", host::openPrivacyPolicy);
    addAction(content, "System settings", "Open Android TV settings", host::openSystemSettings);
    return content;
  }

  private void showCompanionReceiver() {
    boolean enabled = host.companionReceiverEnabled();
    LinearLayout content = content();
    addHeading(content, "Mac companion receiver", enabled ? "Available to your paired Mac." : "Stopped until you start it.");
    addNote(
        content,
        enabled
            ? "It will start again after this TV restarts."
            : "Paired Macs and imported media stay on this TV.");
    addAction(
        content,
        enabled ? "Stop receiver" : "Start receiver",
        enabled ? "Stop companion connections and do not restart automatically" : "Allow your paired Mac to connect and start after restart",
        () -> {
          host.setCompanionReceiverEnabled(!enabled);
          showCompanionReceiver();
        });
    addBack(content, this::showRoot);
    show("Mac companion receiver", content, this::showRoot, null);
  }

  /** Latest in-memory settings page for headless TV rendering checks. */
  LinearLayout lastBuiltPage() { return lastBuiltPage; }

  void destroy() {
    destroyed = true;
    citySearch.destroy();
  }

  private void showApps() {
    show("Apps", buildAppsPage(), this::showRoot, null);
  }

  /** Shared page construction also permits headless rendering without creating a Window. */
  LinearLayout buildAppsPage() {
    JSONObject apps = AppsSettings.copy(host.apps());
    LinearLayout content = content();
    addHeading(content, "Apps", "Choose what appears on Home.");
    addAction(content, "Shortcuts", visibleShortcutSummary(apps), this::showShortcuts);
    addDivider(content);
    addAction(
        content,
        "Shortcut order",
        "Reorder shortcuts",
        this::showShortcutOrder);
    addAction(
        content,
        "Shortcut style",
        "cards".equals(apps.optString("shortcutStyle")) ? "Cards" : "Minimal",
        this::showShortcutStyle);
    addAction(
        content,
        "HDMI appearance",
        hdmiTitle(apps.optString("hdmiStyle", "switch")),
        this::showHdmiStyle);
    addAction(
        content,
        "Navigation sound",
        navigationSoundTitle(apps),
        this::showNavigationSound);
    addAction(
        content,
        "Navigation volume",
        Math.round(AppsSettings.navigationSoundVolume(apps) * 100) + "%",
        this::showNavigationVolume);
    addBack(content, this::showRoot);
    return content;
  }

  private void showShortcuts() {
    JSONObject apps = AppsSettings.copy(host.apps());
    LinearLayout content = content();
    addHeading(content, "Shortcuts", "Choose visible apps.");
    for (AppEntry entry : appEntries())
      addAction(
          content,
          entry.title,
          isEnabled(apps, entry) ? "Visible on Home" : "Hidden from Home",
          () -> showAppEntry(entry));
    addBack(content, this::showApps);
    show("Shortcuts", content, this::showApps, null);
  }

  private void showAppEntry(AppEntry entry) {
    JSONObject apps = AppsSettings.copy(host.apps());
    boolean visible = isEnabled(apps, entry);
    String[] choices =
        entry.packages.length == 0
            ? new String[] {visible ? "Hide from Home" : "Show on Home"}
            : new String[] {visible ? "Hide from Home" : "Show on Home", "Open app or Google Play"};
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(entry.title)
            .setItems(
                choices,
                (ignored, which) -> {
                  if (which == 0) {
                    try {
                      JSONObject latest = AppsSettings.copy(host.apps());
                      setEnabled(latest, entry, !isEnabled(latest, entry));
                      saveApps(latest, this::showShortcuts);
                    } catch (Exception invalid) {
                      showMessage("Apps", "This change could not be saved.", this::showShortcuts);
                    }
                  } else if (entry.packages.length > 0) {
                    if (context instanceof Activity)
                      new AppShortcutLauncher(
                              (Activity) context,
                              dialogToShow -> host.showDialog(dialogToShow, null))
                          .open(entry.packages, entry.title);
                  }
                })
            .create();
    backTo(dialog, this::showApps);
    present(dialog, null);
  }

  private void showShortcutStyle() {
    choose(
        "Shortcut style",
        new String[] {"Minimal", "Cards"},
        new String[] {"minimal", "cards"},
        host.apps().optString("shortcutStyle", "minimal"),
        value -> mutateApps("shortcutStyle", value, this::showApps),
        this::showApps);
  }

  private void showHdmiStyle() {
    choose(
        "HDMI appearance",
        new String[] {"Nintendo Switch", "PS5", "HDMI"},
        new String[] {"switch", "ps5", "hdmi"},
        host.apps().optString("hdmiStyle", "switch"),
        value -> mutateApps("hdmiStyle", value, this::showApps),
        this::showApps);
  }

  private void showNavigationSound() {
    final String[] labels = new String[] {"Off", "Soft", "Glass", "Wood", "Bubble", "Chime"};
    final String[] values = new String[] {"off", "soft", "glass", "wood", "bubble", "chime"};
    choose(
        "Navigation sound",
        labels,
        values,
        AppsSettings.navigationSound(host.apps()),
        value -> {
          try {
            JSONObject apps = AppsSettings.copy(host.apps());
            apps.put("navigationSound", value);
            host.previewNavigationSound(AppsSettings.validate(apps));
            saveApps(apps, this::showApps);
          } catch (Exception invalid) {
            showMessage("Navigation sound", "This sound could not be saved.", this::showApps);
          }
        },
        this::showApps);
  }

  private void showNavigationVolume() {
    choose(
        "Navigation volume",
        new String[] {"25%", "50%", "75%", "100%"},
        new String[] {"0.25", "0.5", "0.75", "1.0"},
        String.valueOf(AppsSettings.navigationSoundVolume(host.apps())),
        value -> {
          try {
            JSONObject apps = AppsSettings.copy(host.apps());
            apps.put("navigationSoundVolume", Double.parseDouble(value));
            saveApps(apps, this::showApps);
          } catch (Exception invalid) {
            showMessage("Navigation volume", "This volume could not be saved.", this::showApps);
          }
        },
        this::showApps);
  }

  private void showShortcutOrder() {
    JSONObject apps = AppsSettings.copy(host.apps());
    List<String> ordered = shortcutOrder(apps);
    LinearLayout content = content();
    addHeading(content, "Shortcut order", "Select a shortcut to move it.");
    for (int index = 0; index < ordered.size(); index++) {
      final int position = index;
      String id = ordered.get(index);
      addAction(content, (index + 1) + ". " + titleForShortcut(id), "Move earlier or later", () -> showMoveShortcut(ordered, position));
    }
    addBack(content, this::showApps);
    show("Shortcut order", content, this::showApps, null);
  }

  private void showMoveShortcut(List<String> order, int index) {
    String title = titleForShortcut(order.get(index));
    ArrayList<String> choices = new ArrayList<>();
    if (index > 0) choices.add("Move earlier");
    if (index < order.size() - 1) choices.add("Move later");
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(
                choices.toArray(new String[0]),
                (ignored, which) -> {
                  boolean earlier = index > 0 && which == 0;
                  int target = earlier ? index - 1 : index + 1;
                  Collections.swap(order, index, target);
                  try {
                    JSONObject apps = AppsSettings.copy(host.apps());
                    apps.put("shortcutOrder", new JSONArray(order));
                    saveApps(apps, this::showShortcutOrder);
                  } catch (Exception invalid) {
                    showMessage("Shortcut order", "This order could not be saved.", this::showShortcutOrder);
                  }
                })
            .create();
    backTo(dialog, this::showShortcutOrder);
    present(dialog, null);
  }

  private void showWeather() {
    show("Weather", buildWeatherPage(), this::showRoot, null);
  }

  LinearLayout buildWeatherPage() {
    JSONObject weather = WeatherSettings.copy(host.weather());
    LinearLayout content = content();
    addHeading(content, "Weather", "");
    addAction(
        content,
        "Enable weather on TV",
        weather.optBoolean("enabled", false) ? "On" : "Off",
        () -> toggleWeather("enabled", this::showWeather));
    int locations = weather.optJSONArray("locations") == null ? 0 : weather.optJSONArray("locations").length();
    addAction(content, "Locations", locations == 1 ? "1 saved location" : locations + " saved locations", this::showLocations);
    addAction(content, "Widget style", weatherStyleTitle(weather.optString("style", "minimal")), this::showWeatherStyle);
    if (!"icon".equals(weather.optString("style", "minimal"))) {
      addAction(content, "Use portrait cards", weather.optBoolean("portraitCards", false) ? "On" : "Off", () -> toggleWeather("portraitCards", this::showWeather));
      addAction(content, "Show locations side by side", weather.optBoolean("showAllLocations", false) ? "On" : "Off", () -> toggleWeather("showAllLocations", this::showWeather));
      addAction(content, "Content size", contentScaleTitle(weather.optDouble("contentScale", 1.25d)), this::showContentScale);
    }
    addAction(content, "Temperature units", "fahrenheit".equals(weather.optString("unit")) ? "Fahrenheit" : "Celsius", this::showUnit);
    addAction(content, "Show temperature", weather.optBoolean("showTemperature", true) ? "Shown" : "Hidden", () -> toggleWeather("showTemperature", true, this::showWeather));
    addAction(content, "Five-day forecast", weather.optBoolean("showDaily", true) ? "Shown" : "Hidden", () -> toggleWeather("showDaily", true, this::showWeather));
    addAction(content, "Location cycle", cycleTitle(weather.optInt("cycleSeconds", 0)), this::showCycle);
    addAction(content, "Refresh forecast", "Fetch current forecast data", () -> { host.refreshWeather(); showWeather(); });
    addNote(content, "Forecast data: Open-Meteo. Scenic images are AI-generated artwork, not live weather imagery.");
    addBack(content, this::showRoot);
    return content;
  }

  private void showWeatherStyle() {
    choose(
        "Widget style",
        new String[] {"Minimal", "Compact", "Scenic", "Icon & temperature"},
        new String[] {"minimal", "compact", "scenic", "icon"},
        host.weather().optString("style", "minimal"),
        value -> mutateWeather("style", value, this::showWeather),
        this::showWeather);
  }

  private void showUnit() {
    choose(
        "Temperature units",
        new String[] {"Celsius", "Fahrenheit"},
        new String[] {"celsius", "fahrenheit"},
        host.weather().optString("unit", "celsius"),
        value -> mutateWeather("unit", value, this::showWeather),
        this::showWeather);
  }

  private void showContentScale() {
    String[] labels = new String[13];
    String[] values = new String[13];
    for (int index = 0; index < labels.length; index++) {
      int percent = 100 + index * 5;
      labels[index] = percent + "%";
      values[index] = String.valueOf(percent / 100d);
    }
    choose(
        "Content size",
        labels,
        values,
        String.valueOf(WeatherSettings.contentScale(host.weather().opt("contentScale"))),
        value -> {
          try {
            JSONObject weather = WeatherSettings.copy(host.weather());
            weather.put("contentScale", Double.parseDouble(value));
            saveWeather(weather, this::showWeather);
          } catch (Exception invalid) {
            showMessage("Content size", "This size could not be saved.", this::showWeather);
          }
        },
        this::showWeather);
  }

  private void showCycle() {
    choose(
        "Location cycle",
        new String[] {"Off", "Every 30 seconds", "Every minute", "Every 5 minutes"},
        new String[] {"0", "30", "60", "300"},
        String.valueOf(host.weather().optInt("cycleSeconds", 0)),
        value -> {
          try {
            JSONObject weather = WeatherSettings.copy(host.weather());
            weather.put("cycleSeconds", Integer.parseInt(value));
            saveWeather(weather, this::showWeather);
          } catch (Exception invalid) {
            showMessage("Location cycle", "This cycle could not be saved.", this::showWeather);
          }
        },
        this::showWeather);
  }

  private void showLocations() {
    show("Locations", buildLocationsPage(), this::showWeather, null);
  }

  LinearLayout buildLocationsPage() {
    JSONObject weather = WeatherSettings.copy(host.weather());
    JSONArray locations = weather.optJSONArray("locations");
    LinearLayout content = content();
    addHeading(content, "Locations", "Up to 6 cities.");
    int count = locations == null ? 0 : locations.length();
    if (count == 0) addNote(content, "No locations saved.");
    for (int index = 0; index < count; index++) {
      JSONObject location = locations.optJSONObject(index);
      if (location == null) continue;
      final String id = location.optString("id");
      String label = location.optString("name", "Location");
      String country = location.optString("country", "");
      if (!country.isEmpty()) label += ", " + country;
      addAction(content, label, location.optBoolean("enabled", true) ? "Enabled" : "Disabled", () -> showLocation(id));
    }
    if (count < 6) addAction(content, "Add location", "Search for a city", this::showLocationSearch);
    else addNote(content, "Six locations are already saved. Remove one before adding another.");
    addBack(content, this::showWeather);
    return content;
  }

  private void showLocation(String id) {
    JSONObject weather = WeatherSettings.copy(host.weather());
    JSONArray locations = weather.optJSONArray("locations");
    int index = locationIndex(locations, id);
    if (index < 0) { showLocations(); return; }
    JSONObject location = locations.optJSONObject(index);
    String title = location.optString("name", "Location");
    ArrayList<String> options = new ArrayList<>();
    if (!id.equals(weather.optString("selectedLocationId", ""))) options.add("Use for widget");
    options.add(location.optBoolean("enabled", true) ? "Disable on TV" : "Enable on TV");
    if (availableArtworkVariants(location).size() > 1) options.add("Choose scenic artwork");
    if (index > 0) options.add("Move earlier");
    if (index < locations.length() - 1) options.add("Move later");
    options.add("Remove location");
    AlertDialog dialog =
        new AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(
                options.toArray(new String[0]),
                (ignored, selected) -> updateLocationOption(id, options.get(selected)))
            .create();
    backTo(dialog, this::showLocations);
    present(dialog, null);
  }

  private void updateLocationOption(String id, String action) {
    try {
      JSONObject weather = WeatherSettings.copy(host.weather());
      JSONArray locations = weather.getJSONArray("locations");
      int current = locationIndex(locations, id);
      if (current < 0) { showLocations(); return; }
      JSONObject location = locations.getJSONObject(current);
      if ("Use for widget".equals(action)) {
        location.put("enabled", true);
        weather.put("selectedLocationId", id);
      } else if ("Disable on TV".equals(action) || "Enable on TV".equals(action)) {
        location.put("enabled", !location.optBoolean("enabled", true));
      } else if ("Choose scenic artwork".equals(action)) {
        showArtworkVariant(id);
        return;
      } else if ("Move earlier".equals(action)) swapJson(locations, current, current - 1);
      else if ("Move later".equals(action)) swapJson(locations, current, current + 1);
      else removeJson(locations, current);
      saveWeather(weather, this::showLocations);
    } catch (Exception invalid) {
      showMessage("Locations", "This location change could not be saved.", this::showLocations);
    }
  }

  private void showArtworkVariant(String id) {
    try {
      JSONObject weather = WeatherSettings.copy(host.weather());
      JSONObject location = weather.getJSONArray("locations").optJSONObject(locationIndex(weather.getJSONArray("locations"), id));
      if (location == null) { showLocations(); return; }
      List<Integer> variants = availableArtworkVariants(location);
      if (variants.size() < 2) { showLocation(id); return; }
      String[] labels = new String[variants.size()];
      String[] values = new String[variants.size()];
      for (int index = 0; index < variants.size(); index++) {
        labels[index] = "Scene " + (variants.get(index) + 1);
        values[index] = String.valueOf(variants.get(index));
      }
      choose(
          "Scenic artwork",
          labels,
          values,
          String.valueOf(location.optInt("artworkVariant", 0)),
          value -> {
            try {
              JSONObject latest = WeatherSettings.copy(host.weather());
              JSONArray locations = latest.getJSONArray("locations");
              int locationIndex = locationIndex(locations, id);
              if (locationIndex < 0) { showLocations(); return; }
              locations.getJSONObject(locationIndex).put("artworkVariant", Integer.parseInt(value));
              saveWeather(latest, () -> showLocation(id));
            } catch (Exception invalid) {
              showMessage("Scenic artwork", "This artwork could not be saved.", () -> showLocation(id));
            }
          },
          () -> showLocation(id));
    } catch (Exception invalid) {
      showLocations();
    }
  }

  /** Resolves only scenes that physically exist for this exact catalog destination. */
  private List<Integer> availableArtworkVariants(JSONObject location) {
    ArrayList<Integer> variants = new ArrayList<>();
    try {
      if (artworkCatalog == null) artworkCatalog = WeatherWidget.ArtworkCatalog.load(context.getAssets());
      for (int variant = 0; variant <= 4; variant++) {
        JSONObject candidate = new JSONObject(location.toString()).put("artworkVariant", variant);
        WeatherWidget.ArtworkReference reference = artworkCatalog.referenceFor(new WeatherController.Location(candidate));
        if (reference != null && reference.assetPath != null) variants.add(variant);
      }
    } catch (Exception ignored) {
      // A missing catalog intentionally leaves the neutral widget fallback without a scene picker.
    }
    return variants;
  }

  private void showLocationSearch() {
    LinearLayout content = buildLocationSearchPage();
    lastBuiltPage = content;
    EditText query = (EditText) content.findViewWithTag("location-query");
    TextView status = (TextView) content.findViewWithTag("location-status");
    Button search = (Button) content.findViewWithTag("location-search");
    AlertDialog dialog = new AlertDialog.Builder(context).setView(scroll(content)).create();
    search.setOnClickListener(
        ignored -> {
          String value = query.getText().toString();
          search.setEnabled(false);
          status.setText("Searching Open-Meteo…");
          citySearch.search(
              value,
              (results, error) -> {
                if (destroyed || !dialog.isShowing()) return;
                search.setEnabled(true);
                if (error != null) {
                  status.setText(error);
                } else if (results.isEmpty()) {
                  status.setText("No matching locations found.");
                } else {
                  dialog.dismiss();
                  showSearchResults(results, value);
                }
              });
        });
    backTo(dialog, this::showLocations);
    present(dialog, citySearch::cancel);
    main.post(query::requestFocus);
  }

  LinearLayout buildLocationSearchPage() {
    LinearLayout content = content();
    addHeading(content, "Add location", "Search Open-Meteo for a city.");
    EditText query = new EditText(context);
    query.setTag("location-query");
    query.setSingleLine(true);
    query.setTextColor(Color.WHITE);
    query.setHintTextColor(0xffb8b8b8);
    query.setHint("City name");
    query.setText(lastSearchQuery);
    query.setTextSize(20);
    query.setPadding(dp(18), 0, dp(18), 0);
    content.addView(query, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
    TextView status = note("Enter a city name, then select Search.");
    status.setTag("location-status");
    content.addView(status);
    Button search = button("Search", "Search city");
    search.setTag("location-search");
    content.addView(search, actionParams());
    addBack(content, this::showLocations);
    return content;
  }

  private void showSearchResults(List<WeatherCitySearch.Result> results, String query) {
    LinearLayout content = content();
    addHeading(content, "Search results", "Select a location to add.");
    for (WeatherCitySearch.Result result : results) {
      addAction(content, result.label(), "Add location", () -> addLocation(result));
    }
    addBack(content, () -> showLocationSearchWithQuery(query));
    show("Search results", content, () -> showLocationSearchWithQuery(query), null);
  }

  private void showLocationSearchWithQuery(String query) {
    lastSearchQuery = query;
    showLocationSearch();
  }

  private void addLocation(WeatherCitySearch.Result result) {
    try {
      JSONObject weather = WeatherSettings.copy(host.weather());
      JSONArray locations = weather.getJSONArray("locations");
      if (locations.length() >= 6) {
        showMessage("Locations", "Six locations are already saved.", this::showLocations);
        return;
      }
      for (int index = 0; index < locations.length(); index++)
        if (result.id.equals(locations.getJSONObject(index).optString("id"))) {
          showMessage("Locations", "This location is already saved.", this::showLocations);
          return;
        }
      locations.put(result.asLocation());
      if (weather.isNull("selectedLocationId")) weather.put("selectedLocationId", result.id);
      saveWeather(weather, this::showLocations);
    } catch (Exception invalid) {
      showMessage("Locations", "This location could not be saved.", this::showLocations);
    }
  }

  private void toggleWeather(String name, Runnable next) { toggleWeather(name, false, next); }

  private void toggleWeather(String name, boolean fallback, Runnable next) {
    try {
      JSONObject weather = WeatherSettings.copy(host.weather());
      weather.put(name, !weather.optBoolean(name, fallback));
      saveWeather(weather, next);
    } catch (Exception invalid) {
      showMessage("Weather", "This setting could not be saved.", next);
    }
  }

  private void mutateWeather(String name, String value, Runnable next) {
    try {
      JSONObject weather = WeatherSettings.copy(host.weather());
      weather.put(name, value);
      saveWeather(weather, next);
    } catch (Exception invalid) {
      showMessage("Weather", "This setting could not be saved.", next);
    }
  }

  private void mutateApps(String name, String value, Runnable next) {
    try {
      JSONObject apps = AppsSettings.copy(host.apps());
      apps.put(name, value);
      saveApps(apps, next);
    } catch (Exception invalid) {
      showMessage("Apps", "This setting could not be saved.", next);
    }
  }

  private void saveApps(JSONObject apps, Runnable next) {
    try {
      final int pageAtRequest = pageGeneration;
      host.saveApps(
          AppsSettings.validate(apps),
          saved -> {
            if (destroyed || pageAtRequest != pageGeneration) return;
            if (saved) next.run();
            else showMessage("Apps", "This setting could not be saved.", next);
          });
    }
    catch (Exception invalid) { showMessage("Apps", "This setting could not be saved.", next); }
  }

  private void saveWeather(JSONObject weather, Runnable next) {
    try {
      final int pageAtRequest = pageGeneration;
      host.saveWeather(
          WeatherSettings.validate(weather),
          saved -> {
            if (destroyed || pageAtRequest != pageGeneration) return;
            if (saved) next.run();
            else showMessage("Weather", "This setting could not be saved.", next);
          });
    }
    catch (Exception invalid) { showMessage("Weather", "This setting could not be saved.", next); }
  }

  private interface Choice { void select(String value); }

  private void choose(String title, String[] labels, String[] values, String selected, Choice choice, Runnable back) {
    int checked = Arrays.asList(values).indexOf(selected);
    AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title)
        .setSingleChoiceItems(labels, checked, (ignored, which) -> choice.select(values[which])).create();
    backTo(dialog, back);
    present(dialog, null);
  }

  private void showMessage(String title, String message, Runnable next) {
    AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title).setMessage(message)
        .setPositiveButton("OK", (ignored, which) -> next.run()).create();
    present(dialog, null);
  }

  private void show(String title, LinearLayout content, Runnable back, Runnable dismiss) {
    lastBuiltPage = content;
    currentPage = title;
    AlertDialog dialog = new AlertDialog.Builder(context).setView(scroll(content)).create();
    if (back != null) backTo(dialog, back);
    present(dialog, dismiss);
    String remembered = pageFocus.get(title);
    if (remembered != null) content.post(() -> {
      if (!destroyed && visibleDialog == dialog) restoreFocus(content, remembered);
    });
  }

  private boolean restoreFocus(View view, String description) {
    if (view.isFocusable() && description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) {
      return view.requestFocus();
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++)
        if (restoreFocus(group.getChildAt(index), description)) return true;
    }
    return false;
  }

  /** Keep settings modal navigation single-layered so Back returns predictably to its parent. */
  private void present(AlertDialog dialog, Runnable onDismiss) {
    AlertDialog previous = visibleDialog;
    visibleDialog = dialog;
    pageGeneration++;
    if (previous != null && previous.isShowing()) previous.dismiss();
    host.showDialog(
        dialog,
        () -> {
          if (visibleDialog == dialog) visibleDialog = null;
          if (onDismiss != null) onDismiss.run();
        });
    if (dialog.getWindow() != null) {
      int width = context.getResources().getDisplayMetrics().widthPixels;
      dialog.getWindow().setLayout(Math.round(width * .80f), ViewGroup.LayoutParams.WRAP_CONTENT);
    }
  }

  private void backTo(AlertDialog dialog, Runnable destination) {
    dialog.setOnKeyListener(
        (ignored, keyCode, event) -> {
          if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
              dialog.dismiss();
              destination.run();
            }
            return true;
          }
          return false;
        });
  }

  private LinearLayout content() {
    LinearLayout content = new LinearLayout(context);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(34), dp(20), dp(34), dp(30));
    return content;
  }

  private ScrollView scroll(LinearLayout content) {
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(true);
    scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    return scroll;
  }

  private void addHeading(LinearLayout content, String title, String detail) {
    TextView heading = new TextView(context);
    heading.setText(title);
    heading.setTextColor(Color.WHITE);
    heading.setTextSize(28);
    heading.setGravity(Gravity.START);
    content.addView(heading, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    if (!detail.isEmpty()) addNote(content, detail);
    else heading.setPadding(0, 0, 0, dp(16));
  }

  private void addAction(LinearLayout content, String title, String detail, Runnable action) {
    Button button = button(title, title);
    android.text.SpannableString text = new android.text.SpannableString(title + "\n" + detail);
    text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.NORMAL),
        title.length() + 1, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    text.setSpan(new android.text.style.RelativeSizeSpan(15f / 19f),
        title.length() + 1, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    text.setSpan(new android.text.style.ForegroundColorSpan(0xffbbc0c6),
        title.length() + 1, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    button.setTypeface(android.graphics.Typeface.DEFAULT);
    button.setText(text);
    button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    button.setPadding(dp(22), dp(4), dp(22), dp(4));
    button.setOnClickListener(ignored -> {
      if (!"Back".equals(title)) pageFocus.put(currentPage, title);
      action.run();
    });
    content.addView(button, actionParams());
  }

  private void addBack(LinearLayout content, Runnable action) { addAction(content, "Back", "Return to settings", action); }

  private void addDivider(LinearLayout content) {
    View divider = new View(context);
    divider.setBackgroundColor(0x337f8790);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
    params.setMargins(0, dp(16), 0, dp(8));
    content.addView(divider, params);
  }

  private void addNote(LinearLayout content, String value) { content.addView(note(value)); }

  private TextView note(String value) {
    TextView note = new TextView(context);
    note.setText(value);
    note.setTextColor(0xffc7cbd0);
    note.setTextSize(16);
    note.setLineSpacing(dp(3), 1f);
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    params.setMargins(0, dp(4), 0, dp(14));
    note.setLayoutParams(params);
    return note;
  }

  private Button button(String value, String description) {
    Button button = new Button(context);
    button.setAllCaps(false);
    button.setText(value);
    button.setTextSize(19);
    button.setTextColor(Color.WHITE);
    button.setContentDescription(description);
    button.setMinHeight(dp(56));
    button.setFocusable(true);
    button.setBackground(settingsSurface(false));
    button.setOnFocusChangeListener((view, focused) -> view.setBackground(settingsSurface(focused)));
    return button;
  }

  private LinearLayout.LayoutParams actionParams() {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
    params.setMargins(0, dp(4), 0, dp(4));
    return params;
  }

  private GradientDrawable settingsSurface(boolean focused) {
    GradientDrawable surface = new GradientDrawable();
    surface.setColor(focused ? 0x663b4650 : 0xcc161b21);
    surface.setCornerRadius(dp(12));
    surface.setStroke(dp(focused ? 2 : 1), focused ? Color.WHITE : 0x557f8790);
    return surface;
  }

  private int dp(int value) { return (int) (value * context.getResources().getDisplayMetrics().density + .5f); }

  private static final class AppEntry {
    final String id, title;
    final String[] packages;
    AppEntry(String id, String title, String... packages) { this.id = id; this.title = title; this.packages = packages; }
  }

  private List<AppEntry> appEntries() {
    ArrayList<AppEntry> values = new ArrayList<>();
    values.add(new AppEntry("netflix", "Netflix", "com.netflix.ninja"));
    values.add(new AppEntry("radio", "Radio"));
    values.add(new AppEntry("prime_video", "Prime Video", "com.amazon.amazonvideo.livingroom"));
    values.add(new AppEntry("spotify", "Spotify", "com.spotify.tv.android"));
    values.add(new AppEntry("twitch", "Twitch", "tv.twitch.android.app"));
    values.add(new AppEntry("hulu", "Hulu", AppShortcutLauncher.HULU_US));
    values.add(new AppEntry("movies", "Movies"));
    values.add(new AppEntry("hdmi", "HDMI"));
    for (AppShortcutCatalog.Service service : AppShortcutCatalog.load(context))
      values.add(new AppEntry(service.id, service.title, service.packages));
    return values;
  }

  private boolean isEnabled(JSONObject apps, AppEntry entry) {
    if (AppShortcutCatalog.NEW_SERVICE_IDS.contains(entry.id)) {
      JSONArray enabled = apps.optJSONArray("enabledServices");
      if (enabled == null) return false;
      for (int index = 0; index < enabled.length(); index++) if (entry.id.equals(enabled.optString(index))) return true;
      return false;
    }
    if ("netflix".equals(entry.id) || "radio".equals(entry.id) || "hdmi".equals(entry.id) || "movies".equals(entry.id))
      return apps.optBoolean(entry.id + "Enabled", "movies".equals(entry.id) ? false : true);
    if ("prime_video".equals(entry.id)) return apps.optBoolean("primeVideoEnabled", false);
    return apps.optBoolean(entry.id + "Enabled", false);
  }

  private void setEnabled(JSONObject apps, AppEntry entry, boolean enabled) throws Exception {
    if (AppShortcutCatalog.NEW_SERVICE_IDS.contains(entry.id)) {
      JSONArray ids = apps.optJSONArray("enabledServices");
      JSONArray next = new JSONArray();
      if (ids != null) for (int index = 0; index < ids.length(); index++) if (!entry.id.equals(ids.optString(index))) next.put(ids.optString(index));
      if (enabled) next.put(entry.id);
      apps.put("enabledServices", next);
    } else if ("prime_video".equals(entry.id)) apps.put("primeVideoEnabled", enabled);
    else apps.put(entry.id + "Enabled", enabled);
  }

  private List<String> shortcutOrder(JSONObject apps) {
    JSONArray stored = apps.optJSONArray("shortcutOrder");
    ArrayList<String> order = new ArrayList<>();
    if (stored != null) for (int index = 0; index < stored.length(); index++) order.add(stored.optString(index));
    if (order.isEmpty()) order.addAll(AppsSettings.canonicalShortcutOrder());
    return order;
  }

  private String visibleShortcutSummary(JSONObject apps) {
    int visible = 0;
    for (AppEntry entry : appEntries()) if (isEnabled(apps, entry)) visible++;
    return visible == 1 ? "1 visible shortcut" : visible + " visible shortcuts";
  }

  private String titleForShortcut(String id) {
    for (AppEntry entry : appEntries()) if (entry.id.equals(id)) return entry.title;
    return id;
  }

  private static String hdmiTitle(String style) {
    return "ps5".equals(style) ? "PS5" : "hdmi".equals(style) ? "HDMI" : "Nintendo Switch";
  }

  private static String navigationSoundTitle(JSONObject apps) {
    String sound = AppsSettings.navigationSound(apps);
    return sound.length() == 0 ? "Off" : Character.toUpperCase(sound.charAt(0)) + sound.substring(1);
  }

  private static String weatherStyleTitle(String style) {
    if ("compact".equals(style)) return "Compact";
    if ("scenic".equals(style)) return "Scenic";
    if ("icon".equals(style)) return "Icon & temperature";
    return "Minimal";
  }

  private static String contentScaleTitle(double scale) { return Math.round(WeatherSettings.contentScale(scale) * 100) + "%"; }
  private static String cycleTitle(int seconds) { return seconds == 30 ? "Every 30 seconds" : seconds == 60 ? "Every minute" : seconds == 300 ? "Every 5 minutes" : "Off"; }
  private static int locationIndex(JSONArray locations, String id) {
    if (locations == null) return -1;
    for (int index = 0; index < locations.length(); index++) {
      JSONObject location = locations.optJSONObject(index);
      if (location != null && id.equals(location.optString("id"))) return index;
    }
    return -1;
  }
  private static void swapJson(JSONArray values, int first, int second) throws Exception {
    Object a = values.get(first), b = values.get(second); values.put(first, b); values.put(second, a);
  }
  private static void removeJson(JSONArray values, int remove) throws Exception {
    JSONArray next = new JSONArray();
    for (int index = 0; index < values.length(); index++) if (index != remove) next.put(values.get(index));
    while (values.length() > 0) values.remove(values.length() - 1);
    for (int index = 0; index < next.length(); index++) values.put(next.get(index));
  }
}
