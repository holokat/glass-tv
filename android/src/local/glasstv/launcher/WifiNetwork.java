package local.glasstv.launcher;

/** A real Wi-Fi network as reported by Android. It deliberately has no saved-network settings. */
final class WifiNetwork {
  final String key;
  final String ssid;
  final String security;
  final String state;
  final int rssi;
  final int frequencyMhz;
  final boolean current;

  WifiNetwork(
      String key,
      String ssid,
      String security,
      String state,
      int rssi,
      int frequencyMhz,
      boolean current) {
    this.key = key;
    this.ssid = ssid;
    this.security = security;
    this.state = state;
    this.rssi = rssi;
    this.frequencyMhz = frequencyMhz;
    this.current = current;
  }

  String signalLabel() {
    if (rssi == Integer.MIN_VALUE) return null;
    if (rssi >= -55) return "Excellent signal";
    if (rssi >= -67) return "Strong signal";
    if (rssi >= -75) return "Fair signal";
    return "Weak signal";
  }

  static String visibleSsid(String rawSsid, boolean fineLocationGranted, boolean locationEnabled) {
    if (!fineLocationGranted || !locationEnabled || rawSsid == null || rawSsid.length() == 0)
      return null;
    if ("<unknown ssid>".equals(rawSsid)) return null;
    if (rawSsid.length() > 1 && rawSsid.startsWith("\"") && rawSsid.endsWith("\""))
      return rawSsid.substring(1, rawSsid.length() - 1);
    return rawSsid;
  }

  static String activeConnectionState(
      boolean ethernet, boolean wifi, boolean validated, boolean captivePortal) {
    if (ethernet) return "This TV is using Ethernet.";
    if (!wifi) return null;
    if (validated) return "Wi-Fi is connected. Internet access is verified by Android.";
    if (captivePortal) return "Wi-Fi is connected. Sign-in is required.";
    return "Wi-Fi is connected locally. Internet access is not verified.";
  }

  static String ipv4Mask(int prefix) {
    if (prefix < 0 || prefix > 32) return null;
    int mask = prefix == 0 ? 0 : -1 << (32 - prefix);
    return ((mask >>> 24) & 0xff)
        + "."
        + ((mask >>> 16) & 0xff)
        + "."
        + ((mask >>> 8) & 0xff)
        + "."
        + (mask & 0xff);
  }
}
