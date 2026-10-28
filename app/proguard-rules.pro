# Keep WebView JS bridge
-keepclassmembers class com.shicheng.pantransfer.MainActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
