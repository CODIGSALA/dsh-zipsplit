public class DumpIds {
    static void p(String n, int v) { System.out.println(n + "=0x" + Integer.toHexString(v)); }
    public static void main(String[] a) {
        p("name", android.R.attr.name);
        p("label", android.R.attr.label);
        p("icon", android.R.attr.icon);
        p("theme", android.R.attr.theme);
        p("permission", android.R.attr.permission);
        p("versionCode", android.R.attr.versionCode);
        p("versionName", android.R.attr.versionName);
        p("minSdkVersion", android.R.attr.minSdkVersion);
        p("targetSdkVersion", android.R.attr.targetSdkVersion);
        p("exported", android.R.attr.exported);
        p("allowBackup", android.R.attr.allowBackup);
        p("requestLegacyExternalStorage", android.R.attr.requestLegacyExternalStorage);
        p("extractNativeLibs", android.R.attr.extractNativeLibs);
        p("hardwareAccelerated", android.R.attr.hardwareAccelerated);
        p("largeHeap", android.R.attr.largeHeap);
        p("screenOrientation", android.R.attr.screenOrientation);
        p("configChanges", android.R.attr.configChanges);
        p("debuggable", android.R.attr.debuggable);
        p("STYLE_ThemeMaterialLightNoActionBar", android.R.style.Theme_Material_Light_NoActionBar);
        p("STYLE_ThemeDeviceDefaultLight", android.R.style.Theme_DeviceDefault_Light);
        p("STYLE_ThemeDeviceDefaultLightNoActionBar", android.R.style.Theme_DeviceDefault_Light_NoActionBar);
        p("ATTR_progressBarStyle", android.R.attr.progressBarStyle);
        p("ATTR_textColor", android.R.attr.textColor);
        p("ATTR_layout_width", android.R.attr.layout_width);
        p("ATTR_orientation", android.R.attr.orientation);
    }
}
