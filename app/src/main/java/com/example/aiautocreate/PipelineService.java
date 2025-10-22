package com.example.aiautocreate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

/**
 * PipelineService - Ultra-Full Literal (AIDE-Safe, Background-Hardened)
 * - Foreground Service مع إشعار متجدد أثناء التنفيذ
 * - WakeLock + WifiLock للاستمرارية بالخلفية
 * - START_REDELIVER_INTENT لضمان استكمال المهمة لو قُتلت
 * - يبث التقدم/الإنهاء عبر قناتي: PipelineService.* و Constants.* للتوافق
 * - يدعم ACTION_CANCEL لإيقاف التشغيل (اختياري)
 */
public class PipelineService extends Service {
    private static final String TAG = "PipelineService";

    // Broadcasts (نسخ الخدمة)
    public static final String ACTION_PROGRESS = "com.example.aiautocreate.ACTION_PROGRESS";
    public static final String ACTION_FINISHED = "com.example.aiautocreate.ACTION_FINISHED";
    public static final String EXTRA_STAGE = "extra_stage";
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String EXTRA_OUTPUT = "extra_output";
    public static final String EXTRA_PROGRESS = "extra_progress";

    // إلغاء من الخارج (اختياري)
    public static final String ACTION_CANCEL = "com.example.aiautocreate.ACTION_CANCEL";

    private static final int NOTIF_ID = 101;
    private static final String CHANNEL_ID = "pipeline_fg_channel";

    private Thread worker;
    private PipelineManager manager;
    private volatile String lastStage = null;

    // Locks لضمان الاستمرارية بالخلفية
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // لإظهار معلومات أكثر في الإشعار
    private NotificationManager notifMgr;
    private String uiStyle = "";
    private String uiAspect = "";
    private String uiQuality = "";
    private int uiDurationMs = 0;
    private String uiPromptShort = "";

    @Override
    public void onCreate() {
        super.onCreate();

        notifMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // إنشاء PipelineManager مع callbacks
        manager = new PipelineManager(this, new PipelineManager.Callback() {
                public void onStageProgress(String stage, String msg) {
                    lastStage = stage;
                    int percent = estimateProgressPercent(stage);
                    // بثّ التقدم (نسختان للتوافق)
                    sendProgressBroadcast(stage, msg, percent);
                    // تحديث الإشعار
                    updateForegroundNotification(composeBigText(stage, msg));
                }
                public void onFinished(String out) {
                    String stage = (lastStage != null) ? lastStage : "finished";
                    // إشعار الواجهة (نسختان)
                    sendFinishedBroadcast(stage, out);
                    // تحديث الإشعار
                    updateForegroundNotification(composeBigText("Finished", out));
                    stopSelf();
                }
                public void onError(String stage, String msg) {
                    // بث خطأ كتقدم (يتعامل معه الـ UI)
                    sendProgressBroadcast(stage, "ERROR: " + msg, 0, false);
                    updateForegroundNotification(composeBigText("ERROR: " + safe(stage), safe(msg)));
                    stopSelf();
                }
                public void onCancelled() {
                    updateForegroundNotification(composeBigText("Cancelled", "تم الإلغاء"));
                    stopSelf();
                }
            });

        // احجز WakeLock + WifiLock
        acquireLocks();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AIAutoCreate:PipelineWakelock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                try {
                    wifiLock = wm.createWifiLock(mode, "AIAutoCreate:WifiLock");
                } catch (Throwable t) {
                    // Fallback للأجهزة القديمة
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "AIAutoCreate:WifiLock");
                }
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) {}
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        "Pipeline",
                        NotificationManager.IMPORTANCE_LOW
                    );
                    ch.setDescription("AI AutoCreate pipeline");
                    nm.createNotificationChannel(ch);
                }
            } catch (Exception ignored) {}
        }
    }

    private Notification buildForegroundNotification(String bigText) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) { piFlags |= PendingIntent.FLAG_IMMUTABLE; }
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, piFlags);

        String title = "AI AutoCreate";
        String small = "الخدمة تعمل";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensureChannel();
            Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(small)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(true);

            try {
                Notification.BigTextStyle style = new Notification.BigTextStyle();
                style.bigText((bigText != null && bigText.length() > 0) ? bigText : small);
                style.setSummaryText("نمط: " + uiStyle + " | " + uiAspect + " / " + uiQuality);
                b.setStyle(style);
            } catch (Exception ignored) {}
            b.setPriority(Notification.PRIORITY_LOW);
            return b.build();
        } else {
            NotificationCompat.Builder b = new NotificationCompat.Builder(this);
            b.setContentTitle(title)
                .setContentText(small)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);
            try {
                NotificationCompat.BigTextStyle style = new NotificationCompat.BigTextStyle();
                style.bigText((bigText != null && bigText.length() > 0) ? bigText : small);
                style.setSummaryText("نمط: " + uiStyle + " | " + uiAspect + " / " + uiQuality);
                b.setStyle(style);
            } catch (Exception ignored) {}
            return b.build();
        }
    }

    private void updateForegroundNotification(String bigText) {
        try {
            if (notifMgr == null) notifMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Notification n = buildForegroundNotification(bigText);
            if (notifMgr != null) notifMgr.notify(NOTIF_ID, n);
        } catch (Exception ignored) {}
    }

    private String composeBigText(String stage, String msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("الحالة: ").append(stage != null ? stage : "-").append("\n");
        if (msg != null && msg.length() > 0) sb.append(msg).append("\n");
        if (uiPromptShort != null && uiPromptShort.length() > 0) sb.append("المطلوب: ").append(uiPromptShort).append("\n");
        if ((uiAspect != null && uiAspect.length() > 0) || (uiQuality != null && uiQuality.length() > 0)) {
            sb.append("الأبعاد/الجودة: ").append(uiAspect).append(" • ").append(uiQuality).append("\n");
        }
        if (uiDurationMs > 0) {
            int sec = uiDurationMs / 1000;
            sb.append("المدة: ").append(sec / 60).append("m ").append(sec % 60).append("s\n");
        }
        if (uiStyle != null && uiStyle.length() > 0) sb.append("النمط: ").append(uiStyle);
        return sb.toString();
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent == null) return START_REDELIVER_INTENT;

        // دعم ACTION_CANCEL لإيقاف التنفيذ
        String act = intent.getAction();
        if (ACTION_CANCEL.equals(act)) {
            try { if (manager != null) manager.cancel(); } catch (Exception ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }

        // قراءة البيانات من Intent (مع حمايات)
        final String prompt      = safe(intent.getStringExtra("prompt"));
        final String imageStyle  = safe(intent.getStringExtra("image_style"));
        final String coverStyle  = safe(intent.getStringExtra("cover_style"));
        final String voiceChoice = safe(intent.getStringExtra("voice_choice"));
        final String montage     = safe(intent.getStringExtra("montage_style"));
        final boolean skipGemini = intent.getBooleanExtra("skip_gemini", false);

        String minutesStr  = safe(intent.getStringExtra("video_minutes"));
        String secondsStr  = safe(intent.getStringExtra("video_seconds"));
        String aspect      = safe(intent.getStringExtra("aspect_ratio"));
        String quality     = safe(intent.getStringExtra("video_quality"));

        if (minutesStr.length() == 0) minutesStr = "00";
        if (secondsStr.length() == 0) secondsStr = "30";
        if (aspect.length() == 0) aspect = "16:9";
        if (quality.length() == 0) quality = "1080p";

        int durationMs = 30000;
        try {
            int m = Integer.parseInt(minutesStr);
            int s = Integer.parseInt(secondsStr);
            durationMs = (m * 60 + s) * 1000;
        } catch (Exception e) { /* keep default */ }

        // currentStyle: من extra أو من montage أو افتراضي
        String currentStyle = safe(intent.getStringExtra("current_style"));
        if (currentStyle.length() == 0) currentStyle = montage.length() > 0 ? montage : "قصص وروايات";
        String prefix = "profile_" + currentStyle + "_";

        Log.i(TAG, "▶ PipelineService start; style=" + currentStyle + ", aspect=" + aspect + ", quality=" + quality + ", durMs=" + durationMs);

        // خزّن للعرض في الإشعار
        this.uiStyle = currentStyle;
        this.uiAspect = aspect;
        this.uiQuality = quality;
        this.uiDurationMs = durationMs;
        this.uiPromptShort = (prompt != null && prompt.length() > 0) ? (prompt.length() > 64 ? (prompt.substring(0, 64) + "...") : prompt) : "";

        // استرجاع باقي الإعدادات من ffmpeg_prefs
        SharedPreferences prefs = getSharedPreferences("ffmpeg_prefs", Context.MODE_PRIVATE);

        final String audioFx   = prefs.getBoolean(prefix + "audio_on", false)   ? prefs.getString(prefix + "audio", "")     : null;
        final String visualFx  = prefs.getBoolean(prefix + "visual_on", false)  ? prefs.getString(prefix + "visual", "")    : null;
        final String transFx   = prefs.getBoolean(prefix + "trans_on", false)   ? prefs.getString(prefix + "trans", "")     : null;
        final String smartSel  = prefs.getBoolean(prefix + "smart_on", false)   ? prefs.getString(prefix + "smart", "")     : null;
        final String subsModel = prefs.getBoolean(prefix + "sub_on", false)     ? prefs.getString(prefix + "sub", "")       : null;
        final String musicBg   = prefs.getBoolean(prefix + "music_on", false)   ? prefs.getString(prefix + "music", "")     : null;
        final String masterAgg = prefs.getBoolean(prefix + "master_on", false)  ? prefs.getString(prefix + "master", "")    : null;
        final String reviewer  = prefs.getBoolean(prefix + "reviewer_on", false)? prefs.getString(prefix + "reviewer", "")  : null;
        final String orch      = prefs.getBoolean(prefix + "orch_on", false)    ? prefs.getString(prefix + "orch", "")      : null;

        // ابدأ كخدمة Foreground قبل بدء الخيط
        Notification n = buildForegroundNotification(composeBigText("Starting…", "تجهيز البايبلاين"));
        startForeground(NOTIF_ID, n);

        if (worker != null && worker.isAlive()) {
            Log.w(TAG, "Pipeline already running");
            updateForegroundNotification(composeBigText("Already running", "هناك عملية قيد التنفيذ"));
            return START_REDELIVER_INTENT;
        }

        final int finalDuration = durationMs;
        final String finalAspect = aspect;
        final String finalQuality = quality;

        // Thread تشغيل البايبلاين
        worker = new Thread(new Runnable() {
                public void run() {
                    try {
                        if (skipGemini) {
                            sendProgressBroadcast("Gemini", "Skip Gemini", 0, true);
                            updateForegroundNotification(composeBigText("Gemini", "Skip Gemini"));
                        }
                        manager.runPipeline(
                            prompt,
                            imageStyle,
                            coverStyle,
                            voiceChoice,
                            montage,
                            finalDuration,
                            finalAspect,     // aspect ratio
                            finalQuality,    // video quality (480p/720p/1080p/2k/4k)
                            audioFx, visualFx, transFx, smartSel, subsModel, musicBg, masterAgg, reviewer, orch
                        );
                    } catch (Exception e) {
                        Log.e(TAG, "Worker crash: " + e.getMessage(), e);
                        sendProgressBroadcast("pipeline", "ERROR: " + e.getMessage(), 0, false);
                        updateForegroundNotification(composeBigText("ERROR", e.getMessage()));
                        stopSelf();
                    }
                }
            });
        worker.start();

        // مهم: لضمان إعادة تسليم الـ Intent إذا قتل النظام الخدمة
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // أوقف بعناية عند إزالة المهمة من Recents
        try { if (manager != null) manager.cancel(); } catch (Exception ignored) {}
        try { if (worker != null && worker.isAlive()) worker.interrupt(); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
        releaseLocks();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        try { if (manager != null) manager.cancel(); } catch (Exception ignored) {}
        try { if (worker != null && worker.isAlive()) worker.interrupt(); } catch (Exception ignored) {}
        try { stopForeground(true); } catch (Exception ignored) {}
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    private String safe(String s) { return (s == null) ? "" : s.trim(); }

    // ===== Broadcasting helpers (ترسل نسختين للتوافق) =====
    private void sendProgressBroadcast(String stage, String msg, int percent) {
        sendProgressBroadcast(stage, msg, percent, true);
    }

    private void sendProgressBroadcast(String stage, String msg, int percent, boolean successUnknown) {
        try {
            Intent i1 = new Intent(ACTION_PROGRESS);
            i1.putExtra(EXTRA_STAGE, stage);
            i1.putExtra(EXTRA_MESSAGE, msg);
            i1.putExtra(EXTRA_PROGRESS, percent);
            if (!successUnknown) i1.putExtra("success", false);
            sendBroadcast(i1);

            Intent i2 = new Intent(Constants.ACTION_PROGRESS);
            i2.putExtra(Constants.EXTRA_STAGE, stage);
            i2.putExtra(Constants.EXTRA_MESSAGE, msg);
            i2.putExtra(Constants.EXTRA_PROGRESS, percent);
            if (!successUnknown) i2.putExtra("success", false);
            sendBroadcast(i2);
        } catch (Exception ignored) {}
    }

    private void sendFinishedBroadcast(String stage, String out) {
        try {
            Intent i1 = new Intent(ACTION_FINISHED);
            i1.putExtra(EXTRA_STAGE, stage);
            i1.putExtra(EXTRA_MESSAGE, "Finished");
            i1.putExtra(EXTRA_OUTPUT, out);
            i1.putExtra("success", true);
            sendBroadcast(i1);

            Intent i2 = new Intent(Constants.ACTION_FINISHED);
            i2.putExtra(Constants.EXTRA_STAGE, stage);
            i2.putExtra(Constants.EXTRA_MESSAGE, "Finished");
            i2.putExtra(Constants.EXTRA_OUTPUT, out);
            i2.putExtra("success", true);
            sendBroadcast(i2);
        } catch (Exception ignored) {}
    }

    // تقدير نسبة تقريبية حسب اسم المرحلة (اختياري للإشعار/UI)
    private int estimateProgressPercent(String stage) {
        if (stage == null) return 0;
        String s = stage.toLowerCase();
        if (indexOfAny(s, new String[]{"script","scenario","script_generated"})) return 15;
        if (indexOfAny(s, new String[]{"image","images","sd","stable-diffusion","sdxl"})) return 30;
        if (indexOfAny(s, new String[]{"check","verify","clip","embedding"})) return 45;
        if (indexOfAny(s, new String[]{"tts","audio","wav","coqui","tts_generated"})) return 60;
        if (indexOfAny(s, new String[]{"asr","silence","tts_check","audio_check"})) return 70;
        if (indexOfAny(s, new String[]{"video","assemble","ffmpeg","img2vid","assemble_video"})) return 90;
        if (indexOfAny(s, new String[]{"finished","done","complete","success"})) return 100;
        return 0;
    }

    private boolean indexOfAny(String hay, String[] needles){
        if (hay == null || needles == null) return false;
        for (int i=0;i<needles.length;i++){
            String n = needles[i];
            if (n != null && hay.indexOf(n) >= 0) return true;
        }
        return false;
    }
}
