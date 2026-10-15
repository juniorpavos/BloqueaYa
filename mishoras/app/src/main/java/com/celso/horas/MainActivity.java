package com.celso.horas;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.content.Intent;
import android.net.Uri;
public class MainActivity extends Activity {
    private WebView w;
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        w.setWebViewClient(new WebViewClient());
        w.setWebChromeClient(new WebChromeClient());
        w.addJavascriptInterface(new Puente(), "Android");
        w.loadUrl("file:///android_asset/index.html");
        w.setOnApplyWindowInsetsListener((v, in) -> {
            int t = in.getSystemWindowInsetTop(), bo = in.getSystemWindowInsetBottom();
            v.setPadding(in.getSystemWindowInsetLeft(), t, in.getSystemWindowInsetRight(), bo);
            return in.consumeSystemWindowInsets();
        });
        setContentView(w);
    }
    class Puente { @JavascriptInterface public void abrir(String u) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u))); } }
    @Override public void onBackPressed() { if (w.canGoBack()) w.goBack(); else super.onBackPressed(); }
}
