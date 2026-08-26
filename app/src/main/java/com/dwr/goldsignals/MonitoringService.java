package com.dwr.goldsignals;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.media.RingtoneManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

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

/**
 * خدمة تعمل في المقدمة (Foreground Service) وتستمر في سحب الأسعار والشموع
 * وحساب الإشارة حتى لو المستخدم خرج من التطبيق أو قفل الشاشة.
 * - تبقي إشعار دائم (صامت) يعرض السعر الحالي وحالة المراقبة.
 * - عند ظهور إشارة جديدة (شراء/بيع) تُطلق إشعار منفصل بصوت واهتزاز.
 * - أثناء فتح التطبيق، تبث كل تحديث عبر LocalBroadcastManager حتى تتحدث الواجهة والشارت مباشرة.
 */
public class MonitoringService extends Service {

    public static final String ACTION_START = "com.dwr.goldsignals.action.START";
    public static final String ACTION_STOP = "com.dwr.goldsignals.action.STOP";

    public static final String ACTION_UPDATE = "com.dwr.goldsignals.UPDATE";
    public static final String EXTRA_PRICE_TEXT = "price_text";
    public static final String EXTRA_SIGNAL_TEXT = "signal_text";
    public static final String EXTRA_SIGNAL_TYPE = "signal_type";
    public static final String EXTRA_SL = "sl";
    public static final String EXTRA_TP1 = "tp1";
    public static final String EXTRA_TP2 = "tp2";
    public static final String EXTRA_ENTRY = "entry";
    public static final String EXTRA_CHART_JSON = "chart_json";
    public static final String EXTRA_HAS_CHART = "has_chart";
    public static final String EXTRA_ERROR_TEXT = "error_text";

    private static final String CHANNEL_MONITOR = "dwr_monitor_channel";
    private static final String CHANNEL_ALERT = "dwr_alert_channel";
    private static final int NOTIF_ID_MONITOR = 1001;
    private static final int NOTIF_ID_ALERT = 1002;

    public static volatile boolean isServiceRunning = false;

    // Gold API: لا يحتاج API Key للسعر اللحظي.
    // المصدر: https://gold-api.com/docs
    private static final String GOLD_PRICE_URL = "https://api.gold-api.com/price/XAU/USD";
    private static final long CANDLE_SECONDS = 300L; // شمعة 5 دقائق
    private static final int MAX_LOCAL_CANDLES = 120;
    private static final String PREFS_NAME = "dwr_market_data";
    private static final String PREF_CANDLES = "local_5m_candles";

    static final long PRICE_POLL_INTERVAL_MS = 10000;   // كل 10 ثواني
    static final long CANDLE_POLL_INTERVAL_MS = 60000;  // تحليل كل 60 ثانية
    static final long RETRY_INTERVAL_MS = 20000;

    private Handler handler;
    private ExecutorService executor;
    private boolean isRunning = false;
    private boolean hasLoadedOnce = false;
    private volatile double latestPrice = 0.0;
    private final Object candlesLock = new Object();
    private final ArrayList<Candle> localCandles = new ArrayList<>();

    // نتتبع آخر نوع إشارة "أُشعِر" عنها المستخدم حتى لا نكرر نفس الإشعار الصوتي
    // في كل دورة طالما الإشارة نفسها لسه قائمة
    private String lastNotifiedSignalType = "none";

    private final DecimalFormat df = new DecimalFormat("0.00");
    private final SimpleDateFormat apiDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        apiDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        createNotificationChannels();
        loadLocalCandles();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopMonitoring();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // ACTION_START أو إعادة تشغيل من النظام
        if (!isRunning) {
            isRunning = true;
            isServiceRunning = true;
            startForeground(NOTIF_ID_MONITOR, buildMonitorNotification("السعر الحالي: جاري التحديث..."));
            handler.postDelayed(priceRunnable, 0);
            handler.postDelayed(candleRunnable, 0);
        }
        // START_STICKY: لو النظام قتل الخدمة بسبب ضغط الذاكرة، يعيد تشغيلها تلقائياً
        return START_STICKY;
    }

    private void stopMonitoring() {
        isRunning = false;
        isServiceRunning = false;
        handler.removeCallbacks(priceRunnable);
        handler.removeCallbacks(candleRunnable);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopMonitoring();
        executor.shutdown();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);

            NotificationChannel monitorChannel = new NotificationChannel(
                    CHANNEL_MONITOR, "حالة المراقبة", NotificationManager.IMPORTANCE_LOW);
            monitorChannel.setDescription("إشعار دائم يعرض أن التطبيق يراقب السعر في الخلفية");
            monitorChannel.setSound(null, null);
            monitorChannel.enableVibration(false);
            nm.createNotificationChannel(monitorChannel);

            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ALERT, "تنبيهات الإشارات", NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription("تنبيه صوتي فوري عند ظهور إشارة شراء أو بيع");
            Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            alertChannel.setSound(soundUri, attrs);
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{0, 500, 250, 500});
            nm.createNotificationChannel(alertChannel);
        }
    }

    private PendingIntent openAppPendingIntent() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, openIntent, flags);
    }

    private Notification buildMonitorNotification(String contentText) {
        return new NotificationCompat.Builder(this, CHANNEL_MONITOR)
                .setContentTitle("DWR Gold Signals - المراقبة تعمل")
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppPendingIntent())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateMonitorNotification(String contentText) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID_MONITOR, buildMonitorNotification(contentText));
    }

    private void fireSignalAlert(String title, String bodyText) {
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setContentTitle(title)
                .setContentText(bodyText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(openAppPendingIntent())
                .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
                .build();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID_ALERT, n);
    }

    private void broadcastUpdate(Intent data) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(data);
    }

    // ---------- حلقة السعر السريعة (Gold API) ----------
    private final Runnable priceRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            executor.execute(() -> {
                try {
                    String json = fetchData(GOLD_PRICE_URL);
                    JSONObject obj = new JSONObject(json);

                    if (obj.has("error")) {
                        String msg = obj.optString("error", "تعذر جلب السعر");
                        broadcastError("Gold API: " + msg);
                        handler.postDelayed(this, RETRY_INTERVAL_MS);
                        return;
                    }

                    double price = obj.optDouble("price", Double.NaN);
                    if (Double.isNaN(price) || price <= 0) {
                        throw new IllegalStateException("لم يصل سعر XAU/USD من Gold API");
                    }

                    latestPrice = price;
                    updateLocalCandle(price, System.currentTimeMillis() / 1000L);
                    hasLoadedOnce = true;

                    String priceText = "السعر الحالي: " + df.format(price);
                    updateMonitorNotification(priceText);

                    Intent data = new Intent(ACTION_UPDATE);
                    data.putExtra(EXTRA_PRICE_TEXT, priceText);
                    data.putExtra(EXTRA_HAS_CHART, false);
                    broadcastUpdate(data);

                } catch (Exception e) {
                    broadcastError("تعذر جلب سعر الذهب، ستتم إعادة المحاولة تلقائياً");
                    handler.postDelayed(this, RETRY_INTERVAL_MS);
                    return;
                }
                handler.postDelayed(this, PRICE_POLL_INTERVAL_MS);
            });
        }
    };

    // ---------- حلقة الشموع + الإشارة (محلياً، بدون طلب API إضافي) ----------
    private final Runnable candleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            executor.execute(() -> {
                try {
                    ArrayList<Candle> ascending;
                    synchronized (candlesLock) {
                        ascending = new ArrayList<>(localCandles);
                    }

                    long currentBucket = (System.currentTimeMillis() / 1000L) / CANDLE_SECONDS * CANDLE_SECONDS;
                    ArrayList<Candle> completed = new ArrayList<>();
                    for (Candle c : ascending) {
                        if (c.time < currentBucket) completed.add(c);
                    }

                    if (completed.size() > MAX_LOCAL_CANDLES) {
                        completed = new ArrayList<>(completed.subList(completed.size() - MAX_LOCAL_CANDLES, completed.size()));
                    }

                    String chartJson = buildChartJson(ascending);
                    if (completed.size() < 6) {
                        String msg = "جاري جمع بيانات الشموع المحلية: " + completed.size() + "/6";
                        Intent data = new Intent(ACTION_UPDATE);
                        data.putExtra(EXTRA_PRICE_TEXT, latestPrice > 0 ? "السعر الحالي: " + df.format(latestPrice) : "السعر الحالي: --");
                        data.putExtra(EXTRA_SIGNAL_TEXT, msg);
                        data.putExtra(EXTRA_SIGNAL_TYPE, "none");
                        data.putExtra(EXTRA_CHART_JSON, chartJson);
                        data.putExtra(EXTRA_HAS_CHART, !ascending.isEmpty());
                        broadcastUpdate(data);
                        persistLocalCandles();
                        handler.postDelayed(this, CANDLE_POLL_INTERVAL_MS);
                        return;
                    }

                    ArrayList<Candle> descending = new ArrayList<>(completed);
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

                    int maxLookback = Math.min(2, closes.size() - 6);

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

                    double currentPrice = latestPrice > 0 ? latestPrice : closes.get(0);
                    hasLoadedOnce = true;
                    String priceText = "السعر الحالي: " + df.format(currentPrice);
                    updateMonitorNotification(priceText + " | " + signal);

                    if (!signalType.equals("none") && !signalType.equals(lastNotifiedSignalType)) {
                        String alertTitle = signalType.equals("buy")
                                ? "🔴 إشارة شراء جديدة - XAU/USD"
                                : "🟢 إشارة بيع جديدة - XAU/USD";
                        String alertBody = "الدخول: " + df.format(entryPrice)
                                + " | وقف: " + df.format(sl)
                                + " | هدف1: " + df.format(tp1)
                                + " | هدف2: " + df.format(tp2);
                        fireSignalAlert(alertTitle, alertBody);
                        lastNotifiedSignalType = signalType;
                    } else if (signalType.equals("none")) {
                        lastNotifiedSignalType = "none";
                    }

                    Intent data = new Intent(ACTION_UPDATE);
                    data.putExtra(EXTRA_PRICE_TEXT, priceText);
                    data.putExtra(EXTRA_SIGNAL_TEXT, signal);
                    data.putExtra(EXTRA_SIGNAL_TYPE, signalType);
                    data.putExtra(EXTRA_SL, sl);
                    data.putExtra(EXTRA_TP1, tp1);
                    data.putExtra(EXTRA_TP2, tp2);
                    data.putExtra(EXTRA_ENTRY, entryPrice);
                    data.putExtra(EXTRA_CHART_JSON, chartJson);
                    data.putExtra(EXTRA_HAS_CHART, true);
                    broadcastUpdate(data);
                    persistLocalCandles();

                } catch (Exception e) {
                    broadcastError("خطأ في تحليل الشموع: " + e.getClass().getSimpleName());
                }
                handler.postDelayed(this, CANDLE_POLL_INTERVAL_MS);
            });
        }
    };

    private void updateLocalCandle(double price, long epochSeconds) {
        long bucket = (epochSeconds / CANDLE_SECONDS) * CANDLE_SECONDS;
        synchronized (candlesLock) {
            if (!localCandles.isEmpty()) {
                Candle last = localCandles.get(localCandles.size() - 1);
                if (last.time == bucket) {
                    localCandles.set(localCandles.size() - 1,
                            new Candle(last.time, last.open, Math.max(last.high, price), Math.min(last.low, price), price));
                } else if (bucket > last.time) {
                    localCandles.add(new Candle(bucket, price, price, price, price));
                } else {
                    return;
                }
            } else {
                localCandles.add(new Candle(bucket, price, price, price, price));
            }
            while (localCandles.size() > MAX_LOCAL_CANDLES) localCandles.remove(0);
        }
    }

    private void persistLocalCandles() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (candlesLock) {
                for (Candle c : localCandles) {
                    JSONObject o = new JSONObject();
                    o.put("time", c.time);
                    o.put("open", c.open);
                    o.put("high", c.high);
                    o.put("low", c.low);
                    o.put("close", c.close);
                    arr.put(o);
                }
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_CANDLES, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void loadLocalCandles() {
        try {
            String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_CANDLES, "[]");
            JSONArray arr = new JSONArray(saved);
            synchronized (candlesLock) {
                localCandles.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    localCandles.add(new Candle(o.getLong("time"), o.getDouble("open"), o.getDouble("high"), o.getDouble("low"), o.getDouble("close")));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void broadcastError(String message) {
        Intent err = new Intent(ACTION_UPDATE);
        err.putExtra(EXTRA_ERROR_TEXT, message);
        broadcastUpdate(err);
    }

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
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int code = conn.getResponseCode();
        java.io.InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) stream = conn.getInputStream();

        BufferedReader in = new BufferedReader(new InputStreamReader(stream));
        String line;
        StringBuilder response = new StringBuilder();
        while ((line = in.readLine()) != null) response.append(line);
        in.close();
        return response.toString();
    }
}
