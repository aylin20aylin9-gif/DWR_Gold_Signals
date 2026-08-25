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

    // ضع مفتاحك من Twelve Data هنا (لا ترفعه على git بمشروع عام)
    private final String apiKey = "42859d9e50214ae1ab61d571498d44fe";

    static final long PRICE_POLL_INTERVAL_MS = 10000;   // كل 10 ثواني -> 6 طلبات/دقيقة
    static final long CANDLE_POLL_INTERVAL_MS = 60000;  // كل 60 ثانية -> 1 طلب/دقيقة
    static final long RETRY_INTERVAL_MS = 20000;

    private Handler handler;
    private ExecutorService executor;
    private boolean isRunning = false;
    private boolean hasLoadedOnce = false;

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

    // ---------- حلقة السعر السريعة (تيكر) ----------
    private final Runnable priceRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            executor.execute(() -> {
                try {
                    if (apiKey == null || apiKey.equals("YOUR_API_KEY")) return;

                    String json = fetchData("https://api.twelvedata.com/price?symbol=XAU/USD&apikey=" + apiKey);
                    JSONObject obj = new JSONObject(json);

                    if (obj.has("status") && obj.getString("status").equals("error")) {
                        handler.postDelayed(this, RETRY_INTERVAL_MS);
                        return;
                    }

                    double price = Double.parseDouble(obj.getString("price"));
                    hasLoadedOnce = true;
                    String priceText = "السعر الحالي: " + df.format(price);

                    updateMonitorNotification(priceText);

                    Intent data = new Intent(ACTION_UPDATE);
                    data.putExtra(EXTRA_PRICE_TEXT, priceText);
                    data.putExtra(EXTRA_HAS_CHART, false);
                    broadcastUpdate(data);

                } catch (Exception e) {
                    handler.postDelayed(this, RETRY_INTERVAL_MS);
                    return;
                }
                handler.postDelayed(this, PRICE_POLL_INTERVAL_MS);
            });
        }
    };

    // ---------- حلقة الشموع + الإشارة (أبطأ) ----------
    private final Runnable candleRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            executor.execute(() -> {
                try {
                    if (apiKey == null || apiKey.equals("YOUR_API_KEY")) {
                        Intent err = new Intent(ACTION_UPDATE);
                        err.putExtra(EXTRA_ERROR_TEXT, "الرجاء إدخال مفتاح API صحيح");
                        broadcastUpdate(err);
                        isRunning = false;
                        return;
                    }

                    String json = fetchData("https://api.twelvedata.com/time_series?symbol=XAU/USD&interval=5min&outputsize=100&timezone=UTC&apikey=" + apiKey);
                    JSONObject obj = new JSONObject(json);

                    if (obj.has("status") && obj.getString("status").equals("error")) {
                        String msg = obj.optString("message", "خطأ غير معروف من المصدر");
                        Intent err = new Intent(ACTION_UPDATE);
                        err.putExtra(EXTRA_ERROR_TEXT, "خطأ API: " + msg);
                        broadcastUpdate(err);
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

                    ArrayList<Candle> ascending = new ArrayList<>(candles);
                    Collections.sort(ascending, (a, b) -> Long.compare(a.time, b.time));
                    String chartJson = buildChartJson(ascending);

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

                    double currentPrice = closes.get(0);
                    hasLoadedOnce = true;

                    String priceText = "السعر الحالي: " + df.format(currentPrice);
                    updateMonitorNotification(priceText + " | " + signal);

                    // نطلق إشعار صوتي فقط عند "دخول" إشارة جديدة مختلفة عن آخر إشارة أُشعِر عنها،
                    // حتى لا يتكرر التنبيه كل دقيقة طالما نفس الإشارة قائمة
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
                        // رجعنا لحالة "لا توجد إشارة" -> نسمح بإشعار جديد لو ظهرت إشارة تانية بعدين
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

                } catch (java.net.SocketTimeoutException e) {
                    Intent err = new Intent(ACTION_UPDATE);
                    err.putExtra(EXTRA_ERROR_TEXT, "انتهت مهلة الاتصال بالخادم، سيُعاد المحاولة تلقائياً");
                    broadcastUpdate(err);
                    handler.postDelayed(this, RETRY_INTERVAL_MS);
                    return;
                } catch (java.io.IOException e) {
                    Intent err = new Intent(ACTION_UPDATE);
                    err.putExtra(EXTRA_ERROR_TEXT, "تحقق من اتصال الإنترنت في جهازك");
                    broadcastUpdate(err);
                    handler.postDelayed(this, RETRY_INTERVAL_MS);
                    return;
                } catch (Exception e) {
                    Intent err = new Intent(ACTION_UPDATE);
                    err.putExtra(EXTRA_ERROR_TEXT, "خطأ: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    broadcastUpdate(err);
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
