package local.glasstv.launcher;

import android.content.Context;
import android.content.res.Resources;

/** Resolves a drawable from the resource table, which can retain a different namespace than the APK. */
final class DrawableResources {
  private DrawableResources() {}

  static int id(Context context, String name) {
    if (name == null || name.length() == 0) return 0;
    Resources resources = context.getResources();
    return resources.getIdentifier(name, "drawable", resourcePackage(resources));
  }

  static String resourcePackage(Resources resources) {
    return resources.getResourcePackageName(R.drawable.app_icon);
  }
}
