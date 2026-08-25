package com.dwr.goldsignals;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    TextView tvPrice, tvSignal, tvSL, tvTP1, tvTP2;
    Button btnStart;
    WebView webViewChart;
    boolean isChartReady = false;

    Handler handler = new Handler();
    ExecutorService executor = Executors.newSingleThreadExecutor();
    boolean isRunning = false;

    // ضع مفتاحك من Twelve Data هنا (لا ترفعه على git بمشروع عام)
    String apiKey = "42859d9e50214ae1ab61d571498d44fe";

    // نفصل "تحديث السعر الحالي" عن "تحديث الشارت/الإشارة" لأنهم مختلفين تماماً في الطبيعة:
    // - السعر: نداء خفيف (/price) نكرره بسرعة عشان يحس المستخدم إنه "حي"
    // - الشموع: نداء أثقل (/time_series) مش محتاج يتكرر بنفس السرعة، شمعة الـ5 دقايق أصلاً
    //   مش هتتغير غير كل 5 دقايق
    // الخطة المجانية من Twelve Data = 8 طلبات/دقيقة. الجمع: 6 (سعر) + 1 (شموع) = 7/دقيقة، بهامش أمان.
    static final long PRICE_POLL_INTERVAL_MS = 10000;   // كل 10 ثواني -> 6 طلبات/دقيقة
    static final long CANDLE_POLL_INTERVAL_MS = 60000;  // كل 60 ثانية -> 1 طلب/دقيقة
    // عند حدوث خطأ (تجاوز الحد أو انقطاع الشبكة) ننتظر أطول قليلاً قبل إعادة المحاولة
    static final long RETRY_INTERVAL_MS = 20000;

    boolean hasLoadedOnce = false;

    DecimalFormat df = new DecimalFormat("0.00");
    SimpleDateFormat apiDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        apiDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

        tvPrice = findViewById(R.id.tvPrice);
        tvSignal = findViewById(R.id.tvSignal);
        tvSL = findViewById(R.id.tvSL);
        tvTP1 = findViewById(R.id.tvTP1);
        tvTP2 = findViewById(R.id.tvTP2);
        btnStart = findViewById(R.id.btnStart);
        webViewChart = findViewById(R.id.webViewChart);

        setupChart();
        requestNotificationPermissionIfNeeded();

        btnStart.setOnClickListener(v -> {
            if (!isRunning) {
                isRunning = true;
                btnStart.setText("إيقاف المراقبة");
                startMonitoring();
            } else {
                isRunning = false;
                btnStart.setText("بدء المراقبة");
                handler.removeCallbacks(priceRunnable);
                handler.removeCallbacks(candleRunnable);
            }
        });
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
            }
        }
    }

    private void startMonitoring() {
        handler.postDelayed(priceRunnable, 0);
        handler.postDelayed(candleRunnable, 0);
    }

    // ---------- حلقة السعر السريعة (تيكر) ----------
    private Runnable priceRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            executor.execute(() -> {
                try {
                    if (apiKey == null || apiKey.equals("YOUR_API_KEY")) return;

                    String json = fetchData("https://api.twelvedata.com/price?symbol=XAU/USD&apikey=" + apiKey);
                    JSONObject obj = new JSONObject(json);

                    if (obj.has("status") && obj.getString("status").equals("error")) {
                        // نتجاهل أخطاء التيكر السريع بصمت (الحلقة البطيئة هتظهر الخطأ لو استمر)
                        handler.postDelayed(this, RETRY_INTERVAL_MS);
                        return;
                    }

                    double price = Double.parseDouble(obj.getString("price"));
                    hasLoadedOnce = true;
                    runOnUiThread(() -> tvPrice.setText("السعر الحالي: " + df.format(price)));

                } catch (Exception e) {
                    handler.postDelayed(this, RETRY_INTERVAL_MS);
                    return;
                }
                handler.postDelayed(this, PRICE_POLL_INTERVAL_MS);
            });
        }
    };

    // ---------- حلقة الشموع + الإشارة (أبطأ) ----------
    private Runnable candleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            // نظهر مؤشر تحميل عند أول محاولة فقط حتى لا تبدو الشاشة معلّقة بلا رد فعل
            if (!hasLoadedOnce) {
                runOnUiThread(() -> tvPrice.setText("السعر الحالي: جاري التحديث..."));
            }

            executor.execute(() -> {
                try {
                    if (apiKey == null || apiKey.equals("YOUR_API_KEY")) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "الرجاء إدخال مفتاح API صحيح", Toast.LENGTH_LONG).show());
                        isRunning = false;
                        runOnUiThread(() -> btnStart.setText("بدء المراقبة"));
                        return;
                    }

                    String json = fetchData("https://api.twelvedata.com/time_series?symbol=XAU/USD&interval=5min&outputsize=100&timezone=UTC&apikey=" + apiKey);
                    JSONObject obj = new JSONObject(json);

                    if (obj.has("status") && obj.getString("status").equals("error")) {
                        String msg = obj.optString("message", "خطأ غير معروف من المصدر");
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "خطأ API: " + msg, Toast.LENGTH_LONG).show();
                            tvSignal.setText("⚠️ تعذر جلب البيانات (راجع مفتاح API أو حد الطلبات)");
                        });
                        handler.postDelayed(this, RETRY_INTERVAL_MS);
                        return;
                    }

                    JSONArray values = obj.getJSONArray("values");
                    ArrayList<Candle> candles = new ArrayList<>();

                    for (int i = 0; i < values.length(); i++) {
                        JSONObject v = values.getJSONObject(i);
                        long epochSeconds;
                        try {
                            Date d = apiDateFormat.parse(v.getString("datetime"));
                            epochSeconds = d.getTime() / 1000L;
                        } catch (Exception parseEx) {
                            epochSeconds = System.currentTimeMillis() / 1000L;
                        }
                        candles.add(new Candle(
                                epochSeconds,
                                Double.parseDouble(v.getString("open")),
                                Double.parseDouble(v.getString("high")),
                                Double.parseDouble(v.getString("low")),
                                Double.parseDouble(v.getString("close"))
                        ));
                    }

                    // نرتّب صراحةً بدل الاعتماد على ترتيب الرد من Twelve Data (اللي بيرجع
                    // الأحدث أولاً افتراضياً). هذا الترتيب الصريح هو اللي كان ناقص وسبب
                    // اختفاء الشموع من الشارت: lightweight-charts بيرفض بيانات مش تصاعدية
                    // بالوقت ويرمي خطأ كان بيتبلع بصمت.
                    ArrayList<Candle> ascending = new ArrayList<>(candles);
                    Collections.sort(ascending, (a, b) -> Long.compare(a.time, b.time));
                    String chartJson = buildChartJson(ascending);

                    // نسخة تنازلية (الأحدث أولاً) لمنطق كشف الإشارة، مبنية من نفس النسخة
                    // المرتّبة فعلاً بدل الاعتماد على شكل الرد
                    ArrayList<Candle> descending = new ArrayList<>(ascending);
                    Collections.reverse(descending);
                    ArrayList<Double> opens = new ArrayList<>();
                    ArrayList<Double> highs = new ArrayList<>();
                    ArrayList<Double> lows = new ArrayList<>();
                    ArrayList<Double> closes = new ArrayList<>();
                    for (Candle c : descending) {
                        opens.add(c.open);
                        highs.add(c.high);
                        lows.add(c.low);
                        closes.add(c.close);
                    }

                    String signal = "لا توجد إشارة";
                    double sl = 0, tp1 = 0, tp2 = 0, entryPrice = 0;
                    String signalType = "none";

                    // فقط آخر شمعتين مسموح تكون فيهم الإشارة (بدل فحص كامل التاريخ)
                    int maxLookback = Math.min(2, closes.size() - 6);

                    // كشف CISD صاعد
                    outerBuy:
                    for (int i = 5; i <= 5 + maxLookback && i < closes.size(); i++) {
                        boolean bearSeries = true;
                        for (int j = 1; j <= 5; j++) {
                            if (closes.get(i - j) > opens.get(i - j)) bearSeries = false;
                        }
                        if (bearSeries) {
                            double openFirst = opens.get(i - 5);
                            if (closes.get(i) > openFirst && closes.get(i) > opens.get(i)) {
                                signal = "🔴 إشارة شراء (CISD صاعد)";
                                signalType = "buy";
                                double entry = closes.get(i);
                                entryPrice = entry;
                                double minLow = Double.MAX_VALUE;
                                for (int j = i - 5; j <= i; j++) minLow = Math.min(minLow, lows.get(j));
                                sl = minLow - 0.50;
                                tp1 = entry + (entry - sl);
                                tp2 = entry + 3 * (entry - sl);
                                break outerBuy;
                            }
                        }
                    }

                    // كشف CISD هابط
                    outerSell:
                    for (int i = 5; i <= 5 + maxLookback && i < closes.size(); i++) {
                        boolean bullSeries = true;
                        for (int j = 1; j <= 5; j++) {
                            if (closes.get(i - j) < opens.get(i - j)) bullSeries = false;
                        }
                        if (bullSeries) {
                            double openFirst = opens.get(i - 5);
                            if (closes.get(i) < openFirst && closes.get(i) < opens.get(i)) {
                                signal = "🟢 إشارة بيع (CISD هابط)";
                                signalType = "sell";
                                double entry = closes.get(i);
                                entryPrice = entry;
                                double maxHigh = 0;
                                for (int j = i - 5; j <= i; j++) maxHigh = Math.max(maxHigh, highs.get(j));
                                sl = maxHigh + 0.50;
                                tp1 = entry - (sl - entry);
                                tp2 = entry - 3 * (sl - entry);
                                break outerSell;
                            }
                        }
                    }

                    double currentPrice = closes.get(0);
                    String finalSignal = signal;
                    double finalSl = sl, finalTp1 = tp1, finalTp2 = tp2, finalEntry = entryPrice;
                    String finalSignalType = signalType;

                    hasLoadedOnce = true;
                    runOnUiThread(() -> {
                        tvPrice.setText("السعر الحالي: " + df.format(currentPrice));
                        tvSignal.setText(finalSignal);
                        tvSL.setText("وقف الخسارة: " + df.format(finalSl));
                        tvTP1.setText("الهدف 1: " + df.format(finalTp1));
                        tvTP2.setText("الهدف 2: " + df.format(finalTp2));

                        if (isChartReady) {
                            webViewChart.evaluateJavascript("updateCandles(" + JSONObject.quote(chartJson) + ")", null);
                            webViewChart.evaluateJavascript(
                                    "setSignal('" + finalSignalType + "', " + finalEntry + ", " + finalSl + ", " + finalTp1 + ", " + finalTp2 + ")",
                                    null);
                        }
                    });

                } catch (java.net.SocketTimeoutException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "انتهت مهلة الاتصال بالخادم، سيُعاد المحاولة تلقائياً", Toast.LENGTH_LONG).show();
                        if (!hasLoadedOnce) tvPrice.setText("السعر الحالي: تعذر الاتصال، إعادة محاولة...");
                    });
                    handler.postDelayed(this, RETRY_INTERVAL_MS);
                    return;
                } catch (java.io.IOException e) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "تحقق من اتصال الإنترنت في جهازك", Toast.LENGTH_LONG).show();
                        if (!hasLoadedOnce) tvPrice.setText("السعر الحالي: لا يوجد اتصال بالإنترنت");
                    });
                    handler.postDelayed(this, RETRY_INTERVAL_MS);
                    return;
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "خطأ: " + e.getClass().getSimpleName() + " - " + e.getMessage(), Toast.LENGTH_LONG).show());
                    handler.postDelayed(this, RETRY_INTERVAL_MS);
                    return;
                }
                handler.postDelayed(this, CANDLE_POLL_INTERVAL_MS);
            });
        }
    };

    private String buildChartJson(ArrayList<Candle> sortedAscending) throws Exception {
        JSONArray arr = new JSONArray();
        for (Candle c : sortedAscending) {
            JSONObject candle = new JSONObject();
            candle.put("time", c.time);
            candle.put("open", c.open);
            candle.put("high", c.high);
            candle.put("low", c.low);
            candle.put("close", c.close);
            arr.put(candle);
        }
        return arr.toString();
    }

    // حاوية بسيطة لشمعة واحدة (وقت + OHLC) عشان نقدر نرتب بأمان بدل مصفوفات متوازية
    private static class Candle {
        final long time;
        final double open, high, low, close;
        Candle(long time, double open, double high, double low, double close) {
            this.time = time; this.open = open; this.high = high; this.low = low; this.close = close;
        }
    }

    private String fetchData(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        // مهلة أقصر حتى لا يبقى التطبيق "معلّقاً" طويلاً عند ضعف الشبكة
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        // Twelve Data يُرجع أحياناً رمز خطأ HTTP (مثل 429 تجاوز الحد أو 401 مفتاح غير صالح)
        // مع جسم JSON يحتوي تفاصيل الخطأ، لذلك نقرأ errorStream في هذه الحالة بدل رمي استثناء عام
        java.io.InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) stream = conn.getInputStream();

        BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        String line;
        StringBuilder response = new StringBuilder();
        while ((line = in.readLine()) != null) response.append(line);
        in.close();
        return response.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isRunning = false;
        handler.removeCallbacks(priceRunnable);
        handler.removeCallbacks(candleRunnable);
        executor.shutdown();
    }
}
