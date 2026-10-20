package local.glasstv.launcher;

/** Small dependency-free check for Wi-Fi data that Android may redact before the UI receives it. */
public final class WifiNetworkTest {
  public static void main(String[] args) {
    expect("Excellent signal", new WifiNetwork("a", "a", null, null, -39, 5200, false).signalLabel());
    expect(null, WifiNetwork.visibleSsid("Home", false, true));
    expect(null, WifiNetwork.visibleSsid("Home", true, false));
    expect("Home", WifiNetwork.visibleSsid("\"Home\"", true, true));
    expect(null, WifiNetwork.visibleSsid("<unknown ssid>", true, true));
    expect(
        "Wi-Fi is connected locally. Internet access is not verified.",
        WifiNetwork.activeConnectionState(false, true, false, false));
    expect(
        "Wi-Fi is connected. Sign-in is required.",
        WifiNetwork.activeConnectionState(false, true, false, true));
    expect("This TV is using Ethernet.", WifiNetwork.activeConnectionState(true, true, true, false));
    expect("255.255.255.0", WifiNetwork.ipv4Mask(24));
  }

  private static void expect(String expected, String actual) {
    if (expected == null ? actual != null : !expected.equals(actual))
      throw new AssertionError("Expected " + expected + " but got " + actual);
  }
}
