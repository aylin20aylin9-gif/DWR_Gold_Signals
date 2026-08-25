package com.dwr.goldsignals;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    TextView tvPrice, tvSignal, tvSL, tvTP1, tvTP2;
    Button btnStart;
    WebView webViewChart;
    boolean isChartReady = false;

    private BroadcastReceiver updateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvPrice = findViewById(R.id.tvPrice);
        tvSignal = findViewById(R.id.tvSignal);
        tvSL = findViewById(R.id.tvSL);
        tvTP1 = findViewById(R.id.tvTP1);
        tvTP2 = findViewById(R.id.tvTP2);
        btnStart = findViewById(R.id.btnStart);
        webViewChart = findViewById(R.id.webViewChart);

        setupChart();
        requestNotificationPermissionIfNeeded();
        maybeAskDisableBatteryOptimization();

        updateButtonLabel();

        btnStart.setOnClickListener(v -> {
            if (!MonitoringService.isServiceRunning) {
                Intent svc = new Intent(this, MonitoringService.class);
                svc.setAction(MonitoringService.ACTION_START);
                ContextCompat.startForegroundService(this, svc);
            } else {
                Intent svc = new Intent(this, MonitoringService.class);
                svc.setAction(MonitoringService.ACTION_STOP);
                startService(svc);
            }
            // نأخر تحديث النص لحظة بسيطة حتى تنطلق الخدمة فعلياً
            btnStart.postDelayed(this::updateButtonLabel, 300);
        });
    }

    private void updateButtonLabel() {
        btnStart.setText(MonitoringService.isServiceRunning ? "إيقاف المراقبة" : "بدء المراقبة");
    }

    private void setupChart() {
        WebSettings settings = webViewChart.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webViewChart.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                isChartReady = true;
            }
        });
        webViewChart.setBackgroundColor(0xFF1a1a1a);
        webViewChart.loadUrl("file:///android_asset/chart.html");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    // بعض الأجهزة (شاومي، هواوي، أوبو..) توقف التطبيقات في الخلفية لتوفير البطارية،
    // مما يمنع وصول التنبيهات وقت حدوث الإشارة. نطلب استثناء التطبيق من هذا التحسين.
    private void maybeAskDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        String pkg = getPackageName();
        if (pm != null && !pm.isIgnoringBatteryOptimizations(pkg)) {
            new AlertDialog.Builder(this)
                    .setTitle("السماح بالعمل في الخلفية")
                    .setMessage("حتى تستمر المراقبة والتنبيهات وأنت خارج التطبيق، فعّل خيار \"عدم تقييد استهلاك البطارية\" لهذا التطبيق من إعدادات هاتفك.")
                    .setPositiveButton("فتح الإعدادات", (d, w) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                            intent.setData(Uri.parse("package:" + pkg));
                            startActivity(intent);
                        } catch (Exception e) {
                            Toast.makeText(this, "افتح إعدادات البطارية يدوياً من إعدادات الهاتف", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("لاحقاً", null)
                    .show();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleUpdate(intent);
            }
        };
        IntentFilter filter = new IntentFilter(MonitoringService.ACTION_UPDATE);
        LocalBroadcastManager.getInstance(this).registerReceiver(updateReceiver, filter);
        updateButtonLabel();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (updateReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver);
            updateReceiver = null;
        }
    }

    private void handleUpdate(Intent intent) {
        if (intent.hasExtra(MonitoringService.EXTRA_ERROR_TEXT)) {
            String err = intent.getStringExtra(MonitoringService.EXTRA_ERROR_TEXT);
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            return;
        }

        if (intent.hasExtra(MonitoringService.EXTRA_PRICE_TEXT)) {
            tvPrice.setText(intent.getStringExtra(MonitoringService.EXTRA_PRICE_TEXT));
        }

        boolean hasChart = intent.getBooleanExtra(MonitoringService.EXTRA_HAS_CHART, false);
        if (!hasChart) return; // تحديث سعر فقط (تيكر سريع)، لا يوجد تحديث للإشارة أو الشارت

        String signalText = intent.getStringExtra(MonitoringService.EXTRA_SIGNAL_TEXT);
        String signalType = intent.getStringExtra(MonitoringService.EXTRA_SIGNAL_TYPE);
        double sl = intent.getDoubleExtra(MonitoringService.EXTRA_SL, 0);
        double tp1 = intent.getDoubleExtra(MonitoringService.EXTRA_TP1, 0);
        double tp2 = intent.getDoubleExtra(MonitoringService.EXTRA_TP2, 0);
        double entry = intent.getDoubleExtra(MonitoringService.EXTRA_ENTRY, 0);
        String chartJson = intent.getStringExtra(MonitoringService.EXTRA_CHART_JSON);

        if (signalText != null) tvSignal.setText(signalText);
        tvSL.setText("وقف الخسارة: " + fmt(sl));
        tvTP1.setText("الهدف 1: " + fmt(tp1));
        tvTP2.setText("الهدف 2: " + fmt(tp2));

        if (isChartReady && chartJson != null) {
            webViewChart.evaluateJavascript("updateCandles(" + JSONObject.quote(chartJson) + ")", null);
            webViewChart.evaluateJavascript(
                    "setSignal('" + signalType + "', " + entry + ", " + sl + ", " + tp1 + ", " + tp2 + ")",
                    null);
        }
    }

    private String fmt(double v) {
        return new java.text.DecimalFormat("0.00").format(v);
    }
}
