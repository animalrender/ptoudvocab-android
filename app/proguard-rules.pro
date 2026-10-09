# WebView bridge: keep every method exposed to JavaScript and the bridge class itself.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.proudvocab.android.PvNativeBridge { *; }
