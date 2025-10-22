package com.example.aiautocreate;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.Fragment;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController; // note: from android.widget
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TestFragment - Ultra-Full Literal (AIDE/Java7 Compatible)
 * - يعرض نتائج التجربة كبطاقات (Script/Image/TTS/Video ...)
 * - إشعار داخل الصفحة In-App Notification مع تقدم كلي
 * - استقبال بثوث التقدم/الإنهاء من PipelineService و/أو Constants
 * - عرض الوسائط (صور/صوت/فيديو) داخل البطاقات عند الانتهاء
 * - حماية Nulls + إدارة MediaPlayer + توافق Java7/AIDE
 */
public class TestFragment extends Fragment {
    private static final String TAG = "TestFragment";

    private View rootView;
    private android.widget.FrameLayout notifContainer;
    private View currentInAppNotif = null;

    // Ops UI
    private ScrollView opsScroll;
    private LinearLayout opsContainer;

    // حفظ البطاقات حسب ترتيب الإدراج
    private Map<String, View> opCards = new LinkedHashMap<String, View>();
    private Map<String, Boolean> cardCompleted = new LinkedHashMap<String, Boolean>();

    // عداد مفاتيح صناعية للبطاقات
    private long syntheticCounter = 0L;

    // مولد IDs متوافق
    private static final AtomicInteger sNextGeneratedId = new AtomicInteger(1);

    // Media players لكل بطاقة
    private Map<String, MediaPlayer> mediaPlayers = new LinkedHashMap<String, MediaPlayer>();

    // Handler لتحديثات الواجهة
    private Handler uiHandler = new Handler();

    // فلتر موحد للبثوث
    private IntentFilter pipelineFilter = null;

    // ===== Receiver =====
    private BroadcastReceiver pipelineReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            try {
                if (intent == null) return;
                String action = intent.getAction();
                if (action == null) return;

                try { Log.d(TAG, "Received action=" + action + " extras=" + extrasSummary(intent)); } catch (Exception ignored) {}

                // دعم المسارات: PipelineService.* و Constants.*
                boolean isProgress =
                    equalsSafe(action, PipelineService.ACTION_PROGRESS) ||
                    equalsSafe(action, Constants.ACTION_PROGRESS);
                boolean isFinished =
                    equalsSafe(action, PipelineService.ACTION_FINISHED) ||
                    equalsSafe(action, Constants.ACTION_FINISHED);

                if (isProgress || isFinished) {
                    String stage = firstNonEmpty(
                        intent.getStringExtra(PipelineService.EXTRA_STAGE),
                        intent.getStringExtra(Constants.EXTRA_STAGE)
                    );
                    String msg = firstNonEmpty(
                        intent.getStringExtra(PipelineService.EXTRA_MESSAGE),
                        intent.getStringExtra(Constants.EXTRA_MESSAGE)
                    );

                    int progress = 0;
                    try {
                        progress = intent.getIntExtra("progress", 0);
                        if (progress <= 0) {
                            progress = intent.getIntExtra(Constants.EXTRA_PROGRESS, progress);
                        }
                    } catch (Exception ignored) {}

                    int total = 7;
                    int completedEst = estimateCompleted(stage);
                    String title = "يتم تنفيذ: " + (stage != null ? stage : "");
                    showInAppNotification(title, completedEst, total, Math.max(0, Math.min(100, progress)));

                    onStageProgress(stage, msg, progress, intent);

                    // النتيجة النهائية
                    String finalResult = extractFinalResult(intent);
                    if (finalResult != null && stage != null) {
                        String extracted = extractScriptIfJson(finalResult);
                        if (extracted == null) {
                            if (looksLikeJson(finalResult)) {
                                Log.d(TAG, "Final result looks like JSON but extraction failed. Showing placeholder.");
                            } else {
                                extracted = finalResult;
                            }
                        }
                        showFinalResultFor(stage, extracted != null ? extracted : "تم إنتاج نتيجة غير نصّية — راجع السجل.", intent);
                    } else if (isFinished) {
                        markCardCompletedForStage(stage, intent, null);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "pipelineReceiver error: " + t.getMessage());
            }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_test, container, false);

        // bind views
        try { notifContainer = (android.widget.FrameLayout) rootView.findViewById(R.id.inapp_notif_container); }
        catch (Exception e) { notifContainer = null; Log.d(TAG, "inapp_notif_container lookup failed: " + e.getMessage()); }

        try {
            opsScroll = (ScrollView) rootView.findViewById(R.id.ops_scroll);
            opsContainer = (LinearLayout) rootView.findViewById(R.id.ops_container);
        } catch (Exception e) {
            opsScroll = null; opsContainer = null;
            Log.d(TAG, "ops container lookup failed: " + e.getMessage());
        }

        // عنوان أعلى القائمة إن لم يوجد
        try {
            TextView tv = (TextView) rootView.findViewById(R.id.tv_test);
            if (tv != null) {
                tv.setText("نتائج التجربة");
            } else {
                if (opsContainer != null) {
                    TextView tvGen = new TextView(getActivity());
                    try { tvGen.setId(generateViewIdCompat()); } catch (Exception ignored) {}
                    tvGen.setText("نتائج التجربة");
                    tvGen.setTextSize(18);
                    int pad = dpToPx(8);
                    tvGen.setPadding(pad, pad, pad, pad);
                    opsContainer.addView(tvGen, 0);
                    Log.d(TAG, "tv_test missing — created programmatically inside ops_container");
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "Error ensuring tv_test: " + ex.getMessage());
        }

        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        // register receiver
        try {
            if (pipelineFilter == null) {
                pipelineFilter = new IntentFilter();
                pipelineFilter.addAction(PipelineService.ACTION_PROGRESS);
                pipelineFilter.addAction(PipelineService.ACTION_FINISHED);
                // أيضاً بثوث Constants
                pipelineFilter.addAction(Constants.ACTION_PROGRESS);
                pipelineFilter.addAction(Constants.ACTION_FINISHED);
            }
            Context ctx = getActivity();
            if (ctx != null) {
                try {
                    ctx.registerReceiver(pipelineReceiver, pipelineFilter);
                } catch (IllegalArgumentException ia) {
                    Log.w(TAG, "Receiver already registered or registration failed: " + ia.getMessage());
                }
            } else {
                Log.w(TAG, "onResume: getActivity() returned null");
            }
        } catch (Exception e) {
            Log.w(TAG, "registerReceiver failed: " + e.getMessage());
        }
    }

    @Override
    public void onPause() {
        try {
            Context ctx = getActivity();
            if (ctx != null) {
                try { ctx.unregisterReceiver(pipelineReceiver); }
                catch (IllegalArgumentException ia) { Log.d(TAG, "unregisterReceiver ignored: " + ia.getMessage()); }
            }
        } catch (Exception e) {
            Log.d(TAG, "onPause unregister error: " + e.getMessage());
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        try { hideInAppNotification(); } catch (Exception ignored) {}
        releaseAllMediaPlayers();
        super.onDestroyView();
    }

    // ===== In-app notification =====
    public void showInAppNotification(String title, int completed, int total, int progressPercent) {
        if (notifContainer == null) return;

        if (currentInAppNotif != null) {
            updateInAppNotification(title, completed, total, progressPercent);
            return;
        }

        LayoutInflater li = LayoutInflater.from(getActivity());
        final View v = li.inflate(R.layout.inapp_notification, notifContainer, false);
        TextView tvTitle = (TextView) v.findViewById(R.id.notif_title);
        TextView tvCounter = (TextView) v.findViewById(R.id.notif_counter);
        ProgressBar pb = (ProgressBar) v.findViewById(R.id.notif_progress);
        if (tvTitle != null) tvTitle.setText(title != null ? title : "");
        if (tvCounter != null) tvCounter.setText(String.valueOf(completed) + "/" + String.valueOf(total));
        if (pb != null) { pb.setMax(100); pb.setProgress(Math.max(0, Math.min(100, progressPercent))); }

        try {
            v.setTranslationY(-200f);
            notifContainer.addView(v);
            v.animate().translationY(0f).setDuration(220).start();
        } catch (Exception e) {
            try { notifContainer.addView(v); } catch (Exception ignored) {}
        }
        currentInAppNotif = v;
    }

    public void updateInAppNotification(String title, int completed, int total, int progressPercent) {
        if (currentInAppNotif == null) return;
        try {
            TextView tvTitle = (TextView) currentInAppNotif.findViewById(R.id.notif_title);
            TextView tvCounter = (TextView) currentInAppNotif.findViewById(R.id.notif_counter);
            ProgressBar pb = (ProgressBar) currentInAppNotif.findViewById(R.id.notif_progress);
            if (tvTitle != null && title != null) tvTitle.setText(title);
            if (tvCounter != null) tvCounter.setText(String.valueOf(completed) + "/" + String.valueOf(total));
            if (pb != null) pb.setProgress(Math.max(0, Math.min(100, progressPercent)));
        } catch (Exception e) { Log.w(TAG, "updateInAppNotification failed: " + e.getMessage()); }
    }

    public void hideInAppNotification() {
        if (currentInAppNotif == null) return;
        final View v = currentInAppNotif;
        currentInAppNotif = null;
        try {
            v.animate().translationY(-200f).setDuration(180).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (notifContainer != null) notifContainer.removeView(v);
                            else {
                                android.view.ViewParent parent = v.getParent();
                                if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(v);
                            }
                        } catch (Exception ignored) {}
                    }
                }).start();
        } catch (Exception e) {
            try { if (notifContainer != null) notifContainer.removeView(v); } catch (Exception ignored) {}
        }
    }

    // ===== Stage progress =====
    public void onStageProgress(final String stage, final String message, final int progress, final Intent intent) {
        if (!isAdded() || opsContainer == null) return;

        final Runnable uiTask = new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = getActivity();
                    if (ctx == null && rootView != null) ctx = rootView.getContext();
                    if (ctx == null) {
                        try { Toast.makeText(getActivity(), "خطأ: لا يوجد سياق لعرض البطاقة", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                        return;
                    }

                    String group = getOperationGroup(stage);
                    String activeKey = findLatestActiveCardKeyForGroup(group);
                    if (activeKey != null && opCards.containsKey(activeKey)) {
                        View card = opCards.get(activeKey);
                        Boolean completed = cardCompleted.get(activeKey);
                        if (completed == null || !completed) {
                            String msgToShow = shouldIgnoreProgressMessage(message) ? null : message;
                            updateCardView(card, msgToShow, progress);
                            if ("script".equals(group) && message != null && message.length() > 0 && !shouldIgnoreProgressMessage(message)) {
                                String toAppend = message;
                                if (looksLikeJson(message)) {
                                    String ext = extractScriptIfJson(message);
                                    toAppend = (ext != null) ? ext : "[JSON output streaming — سيتم عرض النص النهائي عند الانتهاء]";
                                }
                                appendToCardContent(card, toAppend);
                            }
                        }
                        scrollToCard(card);
                        return;
                    }

                    String newKey = createNewCardKey(group);
                    String displayTitle = displayTitleForGroup(group);
                    String initialMsg = shouldIgnoreProgressMessage(message) ? null : message;

                    View card = createOperationCard(ctx, displayTitle, initialMsg, progress);
                    try { card.setTag(R.id.op_title, group != null ? group.toLowerCase() : ""); } catch (Exception ignored) {}
                    try { card.setTag(R.id.op_status, newKey); } catch (Exception ignored) {}

                    if ("script".equals(group) && message != null && message.length() > 0 && !shouldIgnoreProgressMessage(message)) {
                        String toAppend = message;
                        if (looksLikeJson(message)) {
                            String ext = extractScriptIfJson(message);
                            toAppend = (ext != null) ? ext : "[JSON output streaming — سيتم عرض النص النهائي عند الانتهاء]";
                        }
                        appendToCardContent(card, toAppend);
                    }

                    opCards.put(newKey, card);
                    cardCompleted.put(newKey, false);
                    opsContainer.addView(card);
                    card.setVisibility(View.VISIBLE);

                    card.postDelayed(new Runnable() {
                            @Override public void run() {
                                try { if (opsScroll != null) opsScroll.fullScroll(View.FOCUS_DOWN); } catch (Exception ignored) {}
                            }
                        }, 120);

                } catch (Exception e) {
                    try { Toast.makeText(getActivity(), "خطأ عرض بطاقة العملية", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                }
            }
        };

        try {
            Activity act = getActivity();
            if (act != null) act.runOnUiThread(uiTask);
            else uiHandler.post(uiTask);
        } catch (Exception e) {
            uiHandler.post(uiTask);
        }
    }

    // overload (بدون intent)
    public void onStageProgress(final String stage, final String message, final int progress) {
        onStageProgress(stage, message, progress, null);
    }

    // عرض النتيجة النهائية داخل البطاقة
    public void showFinalResultFor(final String stage, final String finalText, final Intent intent) {
        if (!isAdded() || opsContainer == null) return;

        final Runnable uiTask = new Runnable() {
            @Override public void run() {
                try {
                    Context ctx = getActivity();
                    if (ctx == null && rootView != null) ctx = rootView.getContext();
                    if (ctx == null) return;

                    String group = getOperationGroup(stage);
                    String activeKey = findLatestActiveCardKeyForGroup(group);
                    View card = (activeKey != null) ? opCards.get(activeKey) : null;

                    if (card == null) {
                        String newKey = createNewCardKey(group);
                        card = createOperationCard(ctx, displayTitleForGroup(group), "جاري عرض النتيجة...", 100);
                        try { card.setTag(R.id.op_title, group != null ? group.toLowerCase() : ""); } catch (Exception ignored) {}
                        try { card.setTag(R.id.op_status, newKey); } catch (Exception ignored) {}
                        opCards.put(newKey, card);
                        cardCompleted.put(newKey, false);
                        opsContainer.addView(card);
                        card.setVisibility(View.VISIBLE);
                        activeKey = newKey;
                    }

                    TextView content = (TextView) card.findViewById(R.id.op_content);
                    TextView status = (TextView) card.findViewById(R.id.op_status);
                    ProgressBar pb = (ProgressBar) card.findViewById(R.id.op_progress);
                    ImageView icon = (ImageView) card.findViewById(R.id.op_icon);

                    String displayText = finalText;
                    if (displayText != null && looksLikeJson(displayText)) {
                        String ext = extractScriptIfJson(displayText);
                        if (ext != null) displayText = ext;
                        else {
                            Log.d(TAG, "showFinalResultFor: JSON but extraction failed.");
                            displayText = "نتيجة موجودة لكن ليست نصاً صريحاً — راجع السجل للمخرجات الخام.";
                        }
                    }

                    boolean failed = detectFailure(intent, finalText);

                    // عرض وسائط مناسبة حسب المجموعة
                    String groupCanonical = getOperationGroup(stage);
                    if ("image".equals(groupCanonical)) {
                        displayImagesInCard(card, displayText, activeKey);
                    } else if ("tts".equals(groupCanonical)) {
                        displayAudioInCard(card, displayText, activeKey);
                    } else if ("video".equals(groupCanonical)) {
                        displayVideoInCard(card, displayText, activeKey);
                    } else {
                        if (content != null) {
                            content.setText(displayText != null ? displayText : (failed ? "حدث خطأ — راجع السجل." : "لا توجد نتيجة نصية."));
                            content.setMinLines(1);
                            content.setMaxLines(Integer.MAX_VALUE);
                            content.setEllipsize(null);
                            content.setHorizontallyScrolling(false);
                            content.setTextIsSelectable(true);
                            content.setMovementMethod(new ScrollingMovementMethod());
                            ViewGroup.LayoutParams lp = content.getLayoutParams();
                            if (lp != null) { lp.height = LayoutParams.WRAP_CONTENT; content.setLayoutParams(lp); }
                        }
                    }

                    if (status != null) status.setText(failed ? "❌ فشلت العملية" : "✅ اكتملت العملية");
                    if (pb != null) pb.setVisibility(View.GONE);

                    if (icon != null) {
                        try {
                            int rid = getDrawableIdSafely(failed ? "ic_error" : "ic_check_circle");
                            if (rid == 0) rid = failed ? android.R.drawable.ic_dialog_alert : android.R.drawable.checkbox_on_background;
                            icon.setImageResource(rid);
                            icon.setVisibility(View.VISIBLE);
                        } catch (Exception ignored) {}
                    }

                    if (activeKey != null) {
                        cardCompleted.put(activeKey, true);
                        try { card.setTag(R.id.op_content, true); } catch (Exception ignored) {}
                        try {
                            TextView c = (TextView) card.findViewById(R.id.op_content);
                            if (c != null) { c.setMaxLines(Integer.MAX_VALUE); c.setEllipsize(null); }
                        } catch (Exception ignored) {}
                    }

                    scrollToCard(card);
                } catch (Exception e) {
                    Log.d(TAG, "showFinalResultFor error: " + e.getMessage());
                }
            }
        };

        try {
            Activity act = getActivity();
            if (act != null) act.runOnUiThread(uiTask);
            else uiHandler.post(uiTask);
        } catch (Exception e) {
            uiHandler.post(uiTask);
        }
    }

    public void showFinalResultFor(final String opKey, final String finalText) {
        showFinalResultFor(opKey, finalText, null);
    }

    // تحديث البطاقة
    private void updateCardView(View card, String message, int progress) {
        try {
            TextView status = (TextView) card.findViewById(R.id.op_status);
            if (status != null) status.setText((message != null && message.length() > 0) ? message : ("التقدم: " + progress + "%"));
            try {
                View perProgress = card.findViewById(R.id.op_progress);
                if (perProgress instanceof ProgressBar) {
                    ((ProgressBar) perProgress).setMax(100);
                    ((ProgressBar) perProgress).setProgress(Math.max(0, Math.min(100, progress)));
                    perProgress.setVisibility((progress > 0 && progress < 100) ? View.VISIBLE : View.GONE);
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    // إلحاق نص داخل op_content (لبث السكربت)
    private void appendToCardContent(View card, String message) {
        try {
            if (card == null || message == null) return;
            TextView content = (TextView) card.findViewById(R.id.op_content);
            if (content == null) return;
            String prev = content.getText() != null ? content.getText().toString() : "";
            if (TextUtils.isEmpty(prev)) {
                content.setText(message);
            } else {
                if (!prev.endsWith(message)) {
                    content.setText(prev + "\n" + message);
                }
            }
            content.setMaxLines(6);
            content.setEllipsize(android.text.TextUtils.TruncateAt.END);
        } catch (Exception ignored) {}
    }

    // إنشاء بطاقة عملية جديدة
    private View createOperationCard(Context ctx, String title, String message, int progress) {
        LayoutInflater li = LayoutInflater.from(ctx);
        View card = li.inflate(R.layout.operation_card, opsContainer, false);
        try {
            TextView tvTitle = (TextView) card.findViewById(R.id.op_title);
            TextView tvStatus = (TextView) card.findViewById(R.id.op_status);
            TextView tvContent = (TextView) card.findViewById(R.id.op_content);
            ImageView iv = (ImageView) card.findViewById(R.id.op_icon);
            ProgressBar pb = (ProgressBar) card.findViewById(R.id.op_progress);

            if (tvTitle != null) tvTitle.setText((title != null) ? title : "عملية جديدة");
            if (tvStatus != null) tvStatus.setText((message != null && message.length() > 0) ? message : ("التقدم: " + progress + "%"));
            if (tvContent != null) {
                tvContent.setText("");
                tvContent.setMinLines(1);
                tvContent.setMaxLines(3);
                tvContent.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tvContent.setHorizontallyScrolling(false);
                tvContent.setTextIsSelectable(true);
                tvContent.setMovementMethod(new ScrollingMovementMethod());
                ViewGroup.LayoutParams lp = tvContent.getLayoutParams();
                if (lp != null) { lp.height = LayoutParams.WRAP_CONTENT; tvContent.setLayoutParams(lp); }
            }

            if (iv != null) iv.setVisibility(View.GONE);
            if (pb != null) {
                if (progress > 0 && progress < 100) {
                    pb.setVisibility(View.VISIBLE);
                    pb.setMax(100);
                    pb.setProgress(Math.max(0, Math.min(100, progress)));
                } else {
                    pb.setVisibility(View.GONE);
                }
            }

            try { card.setTag(R.id.op_content, false); } catch (Exception ignored) {}

            card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            TextView c = (TextView) v.findViewById(R.id.op_content);
                            Object t = null;
                            try { t = v.getTag(R.id.op_content); } catch (Exception ignored) {}
                            boolean expanded = (t instanceof Boolean) && ((Boolean) t).booleanValue();
                            if (!expanded) {
                                if (c != null) { c.setMaxLines(Integer.MAX_VALUE); c.setEllipsize(null); }
                                try { v.setTag(R.id.op_content, true); } catch (Exception ignored) {}
                            } else {
                                if (c != null) { c.setMaxLines(3); c.setEllipsize(android.text.TextUtils.TruncateAt.END); }
                                try { v.setTag(R.id.op_content, false); } catch (Exception ignored) {}
                            }
                        } catch (Exception ignored) {}
                    }
                });

        } catch (Exception ignored) {}

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.setMargins(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6));
        card.setLayoutParams(lp);
        card.setVisibility(View.VISIBLE);
        return card;
    }

    private void scrollToCard(final View card) {
        try {
            if (opsScroll == null || card == null) return;
            card.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                    @Override public boolean onPreDraw() {
                        card.getViewTreeObserver().removeOnPreDrawListener(this);
                        try { opsScroll.smoothScrollTo(0, card.getTop()); } catch (Exception ignored) {}
                        return true;
                    }
                });
        } catch (Exception ignored) {}
    }

    private String findLatestActiveCardKeyForGroup(String group) {
        if (group == null) return null;
        try {
            List<String> keys = new ArrayList<String>(opCards.keySet());
            for (int i = keys.size() - 1; i >= 0; i--) {
                String k = keys.get(i);
                View v = opCards.get(k);
                if (v == null) continue;
                try {
                    Object tag = v.getTag(R.id.op_title);
                    if (tag instanceof String) {
                        if (group.equals(((String) tag).toLowerCase())) {
                            Boolean completed = cardCompleted.get(k);
                            if (completed == null || !completed) return k;
                        }
                        continue;
                    }
                } catch (Exception ignored) {}

                TextView tvTitle = (TextView) v.findViewById(R.id.op_title);
                if (tvTitle == null) continue;
                String t = (tvTitle.getText() != null) ? tvTitle.getText().toString().toLowerCase() : "";
                if (t.equals(group) || t.equals(displayTitleForGroup(group).toLowerCase())) {
                    Boolean completed = cardCompleted.get(k);
                    if (completed == null || !completed) return k;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void markCardCompletedForStage(String stage, Intent intent, String finalText) {
        String group = getOperationGroup(stage);
        String activeKey = findLatestActiveCardKeyForGroup(group);
        if (activeKey != null) {
            cardCompleted.put(activeKey, true);
            View card = opCards.get(activeKey);
            if (card != null) {
                TextView status = (TextView) card.findViewById(R.id.op_status);
                View perProgress = card.findViewById(R.id.op_progress);
                ImageView icon = (ImageView) card.findViewById(R.id.op_icon);

                boolean failed = detectFailure(intent, finalText);

                if (status != null) status.setText(failed ? "❌ فشلت العملية" : "✅ اكتملت العملية");
                if (perProgress != null) perProgress.setVisibility(View.GONE);

                if (icon != null) {
                    try {
                        int rid = getDrawableIdSafely(failed ? "ic_error" : "ic_check_circle");
                        if (rid == 0) rid = failed ? android.R.drawable.ic_dialog_alert : android.R.drawable.checkbox_on_background;
                        icon.setImageResource(rid);
                        icon.setVisibility(View.VISIBLE);
                    } catch (Exception ignored) {}
                }

                try {
                    TextView ctv = (TextView) card.findViewById(R.id.op_content);
                    if (ctv != null) { ctv.setMaxLines(Integer.MAX_VALUE); ctv.setEllipsize(null); }
                    card.setTag(R.id.op_content, true);
                } catch (Exception ignored) {}
            }
        }
    }

    private String createNewCardKey(String stageOrGroup) {
        syntheticCounter++;
        String safeStage = (stageOrGroup == null) ? "stage" : stageOrGroup.replaceAll("\\s+","_");
        return safeStage + "_" + System.currentTimeMillis() + "_" + syntheticCounter;
    }

    private String getOperationGroup(String stage) {
        if (stage == null) return "unknown";
        String s = stage.toLowerCase();
        if (s.contains("script") || s.contains("scenario") || s.contains("script_generated") || s.contains("script_eval")) return "script";
        if (s.contains("image") || s.contains("images") || s.contains("sd") || s.contains("stable-diffusion") || s.contains("sdxl")) return "image";
        if (s.contains("check") || s.contains("verify") || s.contains("clip") || s.contains("embedding")) return "check";
        if (s.contains("tts") || s.contains("audio") || s.contains("wav") || s.contains("coqui") || s.contains("tts_generated")) return "tts";
        if (s.contains("asr") || s.contains("silence") || s.contains("tts_check") || s.contains("audio_check")) return "asr";
        if (s.contains("video") || s.contains("assemble") || s.contains("ffmpeg") || s.contains("img2vid") || s.contains("assemble_video")) return "video";
        return stage.replaceAll("\\s+","_").toLowerCase();
    }

    private String displayTitleForGroup(String group) {
        if (group == null) return "Operation";
        if ("script".equals(group)) return "Script";
        if ("image".equals(group))  return "Image";
        if ("check".equals(group))  return "Check";
        if ("tts".equals(group))    return "TTS";
        if ("asr".equals(group))    return "ASR";
        if ("video".equals(group))  return "Video";
        if (group.length() > 0) return group.substring(0,1).toUpperCase() + group.substring(1);
        return "Operation";
    }

    private boolean detectFailure(Intent it, String finalText) {
        try {
            if (it != null) {
                Bundle extras = it.getExtras();
                if (extras != null) {
                    if (extras.containsKey("success")) {
                        try {
                            Object o = extras.get("success");
                            if (o instanceof Boolean) return !((Boolean) o).booleanValue();
                            int v = extras.getInt("success", 1);
                            if (v == 0) return true;
                        } catch (Exception ignored) {}
                    }
                    for (String k : extras.keySet()) {
                        String kl = k.toLowerCase();
                        if (kl.contains("error") || kl.contains("fail") || kl.contains("exception")) return true;
                    }
                }
            }
            if (finalText != null) {
                String l = finalText.toLowerCase();
                if (l.contains("error") || l.contains("failed") || l.contains("exception") || l.contains("traceback") || l.contains("not found")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ===== MEDIA DISPLAY =====

    private List<String> parsePathsFromText(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String[] parts = text.split("[\\r?\\n,;]+");
        for (int i=0;i<parts.length;i++) {
            String p = parts[i];
            if (p == null) continue;
            String t = p.trim();
            if (t.length() == 0) continue;
            out.add(t);
        }
        return out;
    }

    private void displayImagesInCard(final View card, final String displayText, final String cardKey) {
        try {
            if (card == null) return;
            final Context ctx = getActivity();
            if (ctx == null) return;

            View mediaDivider = card.findViewById(R.id.op_media_divider);
            View mediaContainer = card.findViewById(R.id.op_media_container);
            View imagesScroll = card.findViewById(R.id.op_media_images_scroll);
            LinearLayout imagesRow = (LinearLayout) card.findViewById(R.id.op_media_images);

            final TextView content = (TextView) card.findViewById(R.id.op_content);
            if (content != null) content.setVisibility(View.GONE);

            List<String> paths = parsePathsFromText(displayText);

            if (mediaDivider != null) mediaDivider.setVisibility(View.VISIBLE);
            if (mediaContainer != null) mediaContainer.setVisibility(View.VISIBLE);

            if (imagesRow != null) {
                imagesRow.removeAllViews();
                if (paths.size() == 0) {
                    TextView tv = new TextView(ctx);
                    tv.setText(displayText != null ? displayText : "لا توجد صور للعرض");
                    imagesRow.addView(tv);
                } else {
                    for (int i=0;i<paths.size();i++) {
                        final String p = paths.get(i);
                        final ImageView iv = new ImageView(ctx);
                        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dpToPx(160), dpToPx(120));
                        ivLp.setMargins(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));
                        iv.setLayoutParams(ivLp);
                        iv.setAdjustViewBounds(true);
                        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        iv.setImageResource(android.R.drawable.progress_indeterminate_horizontal);
                        imagesRow.addView(iv);

                        if (p == null) continue;

                        if (p.startsWith("http://") || p.startsWith("https://")) {
                            new Thread(new Runnable() {
                                    @Override public void run() {
                                        final Bitmap bmp = downloadBitmapFromUrl(p);
                                        uiHandler.post(new Runnable() {
                                                @Override public void run() {
                                                    try { if (bmp != null) iv.setImageBitmap(bmp); else iv.setImageResource(android.R.drawable.ic_menu_report_image); }
                                                    catch (Exception ignored) {}
                                                }
                                            });
                                    }
                                }).start();
                        } else {
                            try {
                                Bitmap b = null;
                                String pathNormalized = p;
                                if (p.startsWith("file://")) pathNormalized = p.substring(7);
                                if (pathNormalized.startsWith("/")) b = BitmapFactory.decodeFile(pathNormalized);

                                if (b != null) {
                                    iv.setImageBitmap(b);
                                } else {
                                    try {
                                        Uri u = Uri.parse(p);
                                        InputStream is = null;
                                        try {
                                            if (ctx.getContentResolver() != null) {
                                                is = ctx.getContentResolver().openInputStream(u);
                                            }
                                            if (is != null) {
                                                Bitmap bm = BitmapFactory.decodeStream(is);
                                                if (bm != null) iv.setImageBitmap(bm);
                                                is.close();
                                            } else {
                                                iv.setImageResource(android.R.drawable.ic_menu_report_image);
                                            }
                                        } catch (Exception e) {
                                            iv.setImageResource(android.R.drawable.ic_menu_report_image);
                                        } finally {
                                            try { if (is != null) is.close(); } catch (Exception ignored) {}
                                        }
                                    } catch (Exception e) {
                                        iv.setImageResource(android.R.drawable.ic_menu_report_image);
                                    }
                                }
                            } catch (Exception e) {
                                try { iv.setImageResource(android.R.drawable.ic_menu_report_image); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
                if (imagesScroll != null) imagesScroll.setVisibility(View.VISIBLE);
                return;
            }

            if (mediaContainer instanceof ViewGroup) {
                ViewGroup cont = (ViewGroup) mediaContainer;
                cont.removeAllViews();
                if (paths.size() == 0) {
                    TextView tv = new TextView(ctx);
                    tv.setText(displayText != null ? displayText : "لا توجد صور للعرض");
                    cont.addView(tv);
                } else {
                    for (int i=0;i<paths.size();i++) {
                        final String p = paths.get(i);
                        final ImageView iv = new ImageView(ctx);
                        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(200));
                        ivLp.setMargins(0, dpToPx(6), 0, dpToPx(6));
                        iv.setLayoutParams(ivLp);
                        iv.setAdjustViewBounds(true);
                        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                        iv.setImageResource(android.R.drawable.progress_indeterminate_horizontal);
                        cont.addView(iv);

                        if (p == null) continue;

                        if (p.startsWith("http://") || p.startsWith("https://")) {
                            new Thread(new Runnable() {
                                    @Override public void run() {
                                        final Bitmap bmp = downloadBitmapFromUrl(p);
                                        uiHandler.post(new Runnable() {
                                                @Override public void run() {
                                                    try { if (bmp != null) iv.setImageBitmap(bmp); else iv.setImageResource(android.R.drawable.ic_menu_report_image); }
                                                    catch (Exception ignored) {}
                                                }
                                            });
                                    }
                                }).start();
                        } else {
                            try {
                                Bitmap b = null;
                                String pathNormalized = p;
                                if (p.startsWith("file://")) pathNormalized = p.substring(7);
                                if (pathNormalized.startsWith("/")) b = BitmapFactory.decodeFile(pathNormalized);

                                if (b != null) {
                                    iv.setImageBitmap(b);
                                } else {
                                    try {
                                        Uri u = Uri.parse(p);
                                        InputStream is = null;
                                        try {
                                            if (ctx.getContentResolver() != null) {
                                                is = ctx.getContentResolver().openInputStream(u);
                                            }
                                            if (is != null) {
                                                Bitmap bm = BitmapFactory.decodeStream(is);
                                                if (bm != null) iv.setImageBitmap(bm);
                                                is.close();
                                            } else {
                                                iv.setImageResource(android.R.drawable.ic_menu_report_image);
                                            }
                                        } catch (Exception e) {
                                            iv.setImageResource(android.R.drawable.ic_menu_report_image);
                                        } finally {
                                            try { if (is != null) is.close(); } catch (Exception ignored) {}
                                        }
                                    } catch (Exception e) {
                                        iv.setImageResource(android.R.drawable.ic_menu_report_image);
                                    }
                                }
                            } catch (Exception e) {
                                try { iv.setImageResource(android.R.drawable.ic_menu_report_image); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
                return;
            }

            // fallback نصي
            try {
                TextView tv = new TextView(ctx);
                tv.setText(displayText != null ? displayText : "لا توجد وسائط للعرض");
                ViewGroup parent = null;
                int idx = -1;
                if (content != null && content.getParent() instanceof ViewGroup) {
                    parent = (ViewGroup) content.getParent();
                    idx = parent.indexOfChild(content);
                }
                if (parent != null && idx >= 0) parent.addView(tv, idx + 1);
                else if (card instanceof ViewGroup) ((ViewGroup) card).addView(tv);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            Log.d(TAG, "displayImagesInCard failed: " + e.getMessage());
        }
    }

    private void displayAudioInCard(final View card, final String displayText, final String cardKey) {
        try {
            if (card == null) return;
            final Context ctx = getActivity();
            if (ctx == null) return;

            View mediaDivider = card.findViewById(R.id.op_media_divider);
            View mediaContainer = card.findViewById(R.id.op_media_container);
            final TextView content = (TextView) card.findViewById(R.id.op_content);
            if (content != null) content.setVisibility(View.GONE);

            List<String> paths = parsePathsFromText(displayText);
            final String audioPath = paths.size() > 0 ? paths.get(0) : null;

            LinearLayout container = new LinearLayout(ctx);
            container.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            container.setLayoutParams(clp);
            container.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));

            final ImageButton playBtn = new ImageButton(ctx);
            playBtn.setImageResource(android.R.drawable.ic_media_play);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(dpToPx(48), dpToPx(48));
            playBtn.setLayoutParams(btnLp);
            container.addView(playBtn);

            final TextView label = new TextView(ctx);
            label.setText(audioPath != null ? audioPath : "لا يوجد ملف صوتي");
            LinearLayout.LayoutParams labLp = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            labLp.setMargins(dpToPx(8), 0, 0, 0);
            label.setLayoutParams(labLp);
            container.addView(label);

            if (mediaDivider != null) mediaDivider.setVisibility(View.VISIBLE);
            if (mediaContainer != null) mediaContainer.setVisibility(View.VISIBLE);

            if (mediaContainer instanceof ViewGroup) {
                try { ((ViewGroup) mediaContainer).addView(container); }
                catch (Exception ignored) { if (card instanceof ViewGroup) ((ViewGroup) card).addView(container); }
            } else {
                try {
                    if (content != null && content.getParent() instanceof ViewGroup) {
                        ViewGroup parent = (ViewGroup) content.getParent();
                        int idx = parent.indexOfChild(content);
                        parent.addView(container, idx + 1);
                    } else if (card instanceof ViewGroup) ((ViewGroup) card).addView(container);
                } catch (Exception e) {
                    try { if (card instanceof ViewGroup) ((ViewGroup) card).addView(container); } catch (Exception ignored) {}
                }
            }

            if (audioPath == null) {
                playBtn.setEnabled(false);
                return;
            }

            playBtn.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            MediaPlayer mp = mediaPlayers.get(cardKey);
                            if (mp == null) {
                                mp = new MediaPlayer();
                                mediaPlayers.put(cardKey, mp);
                                try {
                                    if (audioPath.startsWith("http://") || audioPath.startsWith("https://")) {
                                        mp.setDataSource(audioPath);
                                    } else {
                                        if (audioPath.startsWith("content://") || audioPath.startsWith("file://")) {
                                            mp.setDataSource(ctx, Uri.parse(audioPath));
                                        } else {
                                            mp.setDataSource(audioPath);
                                        }
                                    }
                                    mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                                            @Override public void onPrepared(MediaPlayer m) {
                                                try { m.start(); playBtn.setImageResource(android.R.drawable.ic_media_pause); } catch (Exception ignored) {}
                                            }
                                        });
                                    mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                                            @Override public void onCompletion(MediaPlayer m) {
                                                try { playBtn.setImageResource(android.R.drawable.ic_media_play); m.seekTo(0); } catch (Exception ignored) {}
                                            }
                                        });
                                    mp.prepareAsync();
                                } catch (Exception ex) {
                                    Log.d(TAG, "audio setDataSource failed: " + ex.getMessage());
                                    try { mp.release(); } catch (Exception ignored) {}
                                    mediaPlayers.remove(cardKey);
                                    playBtn.setEnabled(false);
                                }
                            } else {
                                if (mp.isPlaying()) {
                                    mp.pause();
                                    playBtn.setImageResource(android.R.drawable.ic_media_play);
                                } else {
                                    mp.start();
                                    playBtn.setImageResource(android.R.drawable.ic_media_pause);
                                }
                            }
                        } catch (Exception ex) {
                            Log.d(TAG, "audio play error: " + ex.getMessage());
                        }
                    }
                });

        } catch (Exception e) {
            Log.d(TAG, "displayAudioInCard failed: " + e.getMessage());
        }
    }

    private void displayVideoInCard(final View card, final String displayText, final String cardKey) {
        try {
            if (card == null) return;
            final Context ctx = getActivity();
            if (ctx == null) return;

            final View mediaDivider = card.findViewById(R.id.op_media_divider);
            final View mediaContainer = card.findViewById(R.id.op_media_container);
            final ViewGroup mediaParent = (mediaContainer instanceof ViewGroup) ? (ViewGroup) mediaContainer : null;
            final TextView content = (TextView) card.findViewById(R.id.op_content);
            if (content != null) content.setVisibility(View.GONE);

            List<String> paths = parsePathsFromText(displayText);
            final String vidPath = paths.size() > 0 ? paths.get(0) : null;

            if (mediaDivider != null) mediaDivider.setVisibility(View.VISIBLE);
            if (mediaContainer != null) mediaContainer.setVisibility(View.VISIBLE);

            if (vidPath == null) {
                TextView tv = new TextView(ctx);
                tv.setText("لا يوجد فيديو للعرض");
                try {
                    if (mediaParent != null) {
                        mediaParent.addView(tv);
                    } else if (content != null && content.getParent() instanceof ViewGroup) {
                        ViewGroup parent = (ViewGroup) content.getParent();
                        int idx = parent.indexOfChild(content);
                        parent.addView(tv, idx + 1);
                    } else if (card instanceof ViewGroup) {
                        ((ViewGroup) card).addView(tv);
                    }
                } catch (Exception ignored) {}
                return;
            }

            final VideoView vv = new VideoView(ctx);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpToPx(240));
            vv.setLayoutParams(vlp);

            // استخدم android.widget.MediaController لتفادي مشاكل الاستيراد في AIDE
            final android.widget.MediaController mc = new android.widget.MediaController(ctx);
            vv.setMediaController(mc);
            mc.setAnchorView(vv);

            try {
                if (mediaParent != null) {
                    mediaParent.addView(vv);
                } else {
                    if (content != null && content.getParent() instanceof ViewGroup) {
                        ViewGroup parent = (ViewGroup) content.getParent();
                        int idx = parent.indexOfChild(content);
                        parent.addView(vv, idx + 1);
                    } else if (card instanceof ViewGroup) {
                        ((ViewGroup) card).addView(vv);
                    }
                }
            } catch (Exception ignored) {}

            try {
                Uri u = Uri.parse(vidPath);
                vv.setVideoURI(u);
                vv.requestFocus();
                vv.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override public void onPrepared(MediaPlayer mp) {
                            // لا تشغل تلقائياً
                        }
                    });
                vv.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override public boolean onError(MediaPlayer mp, int what, int extra) {
                            try {
                                TextView tv = new TextView(ctx);
                                tv.setText("فشل تحميل الفيديو");
                                if (mediaParent != null) {
                                    mediaParent.addView(tv);
                                } else if (content != null && content.getParent() instanceof ViewGroup) {
                                    ViewGroup parent = (ViewGroup) content.getParent();
                                    int idx = parent.indexOfChild(content);
                                    parent.addView(tv, idx + 1);
                                } else if (card instanceof ViewGroup) {
                                    ((ViewGroup) card).addView(tv);
                                }
                            } catch (Exception ignored) {}
                            return true; // تم التعامل مع الخطأ
                        }
                    });
            } catch (Exception e) {
                Log.d(TAG, "video set uri failed: " + e.getMessage());
                TextView tv = new TextView(ctx);
                tv.setText("فشل تحميل الفيديو");
                try {
                    if (mediaParent != null) {
                        mediaParent.addView(tv);
                    } else if (content != null && content.getParent() instanceof ViewGroup) {
                        ViewGroup parent = (ViewGroup) content.getParent();
                        parent.addView(tv);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.d(TAG, "displayVideoInCard failed: " + e.getMessage());
        }
    }

    private Bitmap downloadBitmapFromUrl(String urlStr) {
        InputStream is = null;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            is = new BufferedInputStream(conn.getInputStream());
            Bitmap b = BitmapFactory.decodeStream(is);
            try { is.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
            return b;
        } catch (Exception e) {
            Log.d(TAG, "downloadBitmapFromUrl failed: " + e.getMessage());
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Exception ignored) {}
            return null;
        }
    }

    private void releaseAllMediaPlayers() {
        try {
            List<String> keys = new ArrayList<String>(mediaPlayers.keySet());
            for (int i=0;i<keys.size();i++) {
                String k = keys.get(i);
                try {
                    MediaPlayer mp = mediaPlayers.get(k);
                    if (mp != null) {
                        try { if (mp.isPlaying()) mp.stop(); } catch (Exception ignored) {}
                        try { mp.release(); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
                mediaPlayers.remove(k);
            }
        } catch (Exception ignored) {}
    }

    // ===== JSON/script extraction =====
    private String extractScriptIfJson(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.length() == 0) return null;
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) return null;

        try {
            JSONObject j = null;
            if (trimmed.startsWith("{")) {
                j = new JSONObject(trimmed);
            } else {
                JSONArray a = new JSONArray(trimmed);
                if (a.length() > 0) {
                    Object first = a.opt(0);
                    if (first instanceof JSONObject) j = (JSONObject) first;
                    else {
                        try { j = new JSONObject(String.valueOf(first)); } catch (Exception ignored) {}
                    }
                }
            }
            if (j == null) return null;

            String[] directKeys = new String[] {"script","final_script","script_text","content","result","output","final_result","text"};
            for (int i=0;i<directKeys.length;i++) {
                String k = directKeys[i];
                if (j.has(k)) {
                    Object o = j.opt(k);
                    if (o instanceof String) {
                        String s = (String) o;
                        if (!TextUtils.isEmpty(s)) return cleanExtractedText(s);
                    } else if (o instanceof JSONArray) {
                        JSONArray arr = (JSONArray) o;
                        StringBuilder sb = new StringBuilder();
                        for (int idx=0; idx<arr.length(); idx++) {
                            Object el = arr.opt(idx);
                            if (el != null) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(el.toString());
                            }
                        }
                        if (sb.length() > 0) return cleanExtractedText(sb.toString());
                    } else {
                        String s = (o != null) ? o.toString() : null;
                        if (!TextUtils.isEmpty(s)) return cleanExtractedText(s);
                    }
                }
            }

            if (j.has("candidates")) {
                try {
                    JSONArray cand = j.optJSONArray("candidates");
                    if (cand != null && cand.length() > 0) {
                        StringBuilder out = new StringBuilder();
                        for (int ci=0; ci<cand.length(); ci++) {
                            try {
                                JSONObject candObj = cand.optJSONObject(ci);
                                if (candObj == null) continue;
                                Object contentObj = candObj.opt("content");
                                if (contentObj instanceof JSONObject) {
                                    JSONObject content = (JSONObject) contentObj;
                                    if (content.has("parts")) {
                                        JSONArray parts = content.optJSONArray("parts");
                                        if (parts != null) {
                                            for (int pi=0; pi<parts.length(); pi++) {
                                                Object part = parts.opt(pi);
                                                if (part instanceof JSONObject) {
                                                    JSONObject p = (JSONObject) part;
                                                    String txt = p.optString("text", null);
                                                    if (!TextUtils.isEmpty(txt) && !shouldIgnoreProgressMessage(txt)) {
                                                        if (out.length() > 0) out.append('\n');
                                                        out.append(txt);
                                                    }
                                                } else if (part instanceof String) {
                                                    String txt = (String) part;
                                                    if (!TextUtils.isEmpty(txt) && !shouldIgnoreProgressMessage(txt)) {
                                                        if (out.length() > 0) out.append('\n');
                                                        out.append(txt);
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (content.has("text")) {
                                        String txt = content.optString("text", null);
                                        if (!TextUtils.isEmpty(txt) && !shouldIgnoreProgressMessage(txt)) {
                                            if (out.length() > 0) out.append('\n');
                                            out.append(txt);
                                        }
                                    }
                                } else if (contentObj instanceof String) {
                                    String txt = (String) contentObj;
                                    if (!TextUtils.isEmpty(txt) && !shouldIgnoreProgressMessage(txt)) {
                                        if (out.length() > 0) out.append('\n');
                                        out.append(txt);
                                    }
                                }
                            } catch (Exception ignoreCand) {}
                        }
                        if (out.length() > 0) return cleanExtractedText(out.toString());
                    }
                } catch (Exception je) {
                    Log.d(TAG, "candidates parse failed: " + je.getMessage());
                }
            }

            String[] nested = new String[] {"data","outputs","choices","items"};
            for (int i=0;i<nested.length;i++) {
                String key = nested[i];
                if (j.has(key)) {
                    try {
                        Object o = j.opt(key);
                        if (o instanceof JSONObject) {
                            JSONObject sub = (JSONObject) o;
                            String[] subKeys = new String[]{"script","content","text"};
                            for (int k=0;k<subKeys.length;k++) {
                                String k2 = subKeys[k];
                                if (sub.has(k2)) {
                                    String s = sub.optString(k2, null);
                                    if (!TextUtils.isEmpty(s)) return cleanExtractedText(s);
                                }
                            }
                        } else if (o instanceof JSONArray) {
                            JSONArray arr = (JSONArray) o;
                            StringBuilder sb = new StringBuilder();
                            for (int ii=0; ii<arr.length(); ii++) {
                                Object el = arr.opt(ii);
                                if (el instanceof JSONObject) {
                                    JSONObject jo = (JSONObject) el;
                                    String[] subKeys = new String[]{"script","content","text"};
                                    for (int k=0;k<subKeys.length;k++) {
                                        String k2 = subKeys[k];
                                        if (jo.has(k2)) {
                                            String s = jo.optString(k2, null);
                                            if (!TextUtils.isEmpty(s)) {
                                                if (sb.length() > 0) sb.append('\n');
                                                sb.append(s);
                                            }
                                        }
                                    }
                                } else if (el instanceof String) {
                                    if (sb.length() > 0) sb.append('\n');
                                    sb.append((String) el);
                                }
                            }
                            if (sb.length() > 0) return cleanExtractedText(sb.toString());
                        }
                    } catch (Exception ignored) {}
                }
            }

            try {
                StringBuilder collected = new StringBuilder();
                collectTextFromJson(j, collected);
                if (collected.length() > 0) {
                    String cleaned = cleanExtractedText(collected.toString());
                    if (!TextUtils.isEmpty(cleaned)) return cleaned;
                }
            } catch (Exception ignored) {}

            String longest = extractLongestTextBlock(trimmed);
            if (!TextUtils.isEmpty(longest)) return cleanExtractedText(longest);

        } catch (JSONException je) {
            Log.d(TAG, "extractScriptIfJson: json parse failed: " + je.getMessage());
        } catch (Throwable t) {
            Log.d(TAG, "extractScriptIfJson error: " + t.getMessage());
        }
        return null;
    }

    private void collectTextFromJson(Object node, StringBuilder out) {
        try {
            if (node == null) return;
            if (node instanceof JSONObject) {
                JSONObject jo = (JSONObject) node;
                JSONArray names = jo.names();
                if (names == null) return;
                for (int i=0;i<names.length();i++) {
                    String key = names.optString(i);
                    Object val = jo.opt(key);
                    collectTextFromJson(val, out);
                }
            } else if (node instanceof JSONArray) {
                JSONArray ja = (JSONArray) node;
                for (int i=0;i<ja.length();i++) collectTextFromJson(ja.opt(i), out);
            } else if (node instanceof String) {
                String s = ((String) node).trim();
                if (s.length() < 20) return;
                if (shouldIgnoreProgressMessage(s)) return;
                if (looksLikeJson(s)) return;
                if (out.length() > 0) out.append('\n');
                out.append(s);
            } else {
                String s = node.toString();
                if (s != null && s.length() >= 40) {
                    if (!shouldIgnoreProgressMessage(s) && !looksLikeJson(s)) {
                        if (out.length() > 0) out.append('\n');
                        out.append(s);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private String extractLongestTextBlock(String s) {
        if (s == null) return null;
        try {
            // تمت إزالة UNICODE_CHARACTER_CLASS لتوافق أوسع
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("([\\p{L}\\p{N}\\s\\p{P}]{40,})");
            java.util.regex.Matcher m = p.matcher(s);
            String best = null;
            while (m.find()) {
                String cand = m.group(1);
                if (cand == null) continue;
                cand = cand.trim();
                int braces = 0;
                for (int i=0;i<cand.length();i++) {
                    char ch = cand.charAt(i);
                    if (ch == '{' || ch == '}' || ch == '[' || ch == ']') braces++;
                }
                if (braces > 6 && cand.length() < 400) continue;
                if (shouldIgnoreProgressMessage(cand)) continue;
                if (best == null || cand.length() > best.length()) best = cand;
            }
            if (best != null) return best.replace("\\n", "\n").replace("\\\"", "\"").trim();
        } catch (Exception ignored) {}
        return null;
    }

    private boolean looksLikeJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    private String cleanExtractedText(String s) {
        if (s == null) return null;
        String out = s.replace("\\n", "\n").replace("\\r", "\r").replace("\\\"", "\"").replace("\\'", "'").trim();
        try {
            String[] lines = out.split("\\r?\\n");
            StringBuilder sb = new StringBuilder();
            for (int i=0;i<lines.length;i++) {
                String line = lines[i];
                if (line == null) continue;
                String trimmed = line.trim();
                if (trimmed.length() == 0) continue;
                if (shouldIgnoreProgressMessage(trimmed)) continue;
                if (trimmed.length() < 3) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(trimmed);
            }
            out = sb.toString().trim();
        } catch (Exception ignored) {}
        while (out.startsWith("\n")) out = out.substring(1);
        while (out.endsWith("\n")) out = out.substring(0, out.length()-1);
        return out.length() > 0 ? out : null;
    }

    private String truncateForLog(String s) {
        if (s == null) return "";
        if (s.length() > 800) return s.substring(0, 800) + "...(truncated)";
        return s;
    }

    private String extractFinalResult(Intent intent) {
        if (intent == null) return null;
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return null;

            String[] commonKeys = new String[] {"final_result","script","result","output","extra_result","EXTRA_RESULT"};
            for (int i=0;i<commonKeys.length;i++) {
                String k = commonKeys[i];
                if (extras.containsKey(k)) {
                    Object o = extras.get(k);
                    if (o instanceof String) {
                        String v = (String) o;
                        if (!TextUtils.isEmpty(v)) return v;
                    } else if (o != null) {
                        String v = o.toString();
                        if (!TextUtils.isEmpty(v)) return v;
                    }
                }
            }

            for (String k : extras.keySet()) {
                try {
                    String kl = k.toLowerCase();
                    if (kl.contains("result") || kl.contains("final") || kl.contains("output") || kl.contains("script")) {
                        Object o = extras.get(k);
                        if (o instanceof String) {
                            String v = (String) o;
                            if (!TextUtils.isEmpty(v)) return v;
                        } else if (o != null) {
                            String v = o.toString();
                            if (!TextUtils.isEmpty(v)) return v;
                        }
                    }
                } catch (Exception ignored) {}
            }
            Log.d(TAG, "extractFinalResult: no textual result key found. Extras keys: " + extras.keySet().toString());
        } catch (Throwable t) {
            Log.w(TAG, "extractFinalResult failed: " + t.getMessage());
        }
        return null;
    }

    private int getDrawableIdSafely(String name) {
        try {
            Context ctx = getContext();
            if (ctx == null) ctx = getActivity();
            if (ctx == null) return 0;
            if (name == null || name.length() == 0) return 0;
            int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
            return id;
        } catch (Exception e) {
            Log.d(TAG, "getDrawableIdSafely error: " + e.getMessage());
            return 0;
        }
    }

    private int estimateCompleted(String stage) {
        if (stage == null) return 0;
        String s = stage.toLowerCase();
        if (s.contains("script") || s.contains("scenario") || s.contains("script_generated")) return 1;
        if (s.contains("image") || s.contains("images") || s.contains("sd") || s.contains("stable-diffusion") || s.contains("sdxl")) return 2;
        if (s.contains("check") || s.contains("verify") || s.contains("clip") || s.contains("embedding")) return 3;
        if (s.contains("tts") || s.contains("audio") || s.contains("wav") || s.contains("coqui") || s.contains("tts_generated")) return 4;
        if (s.contains("asr") || s.contains("silence") || s.contains("tts_check") || s.contains("audio_check")) return 5;
        if (s.contains("video") || s.contains("assemble") || s.contains("ffmpeg") || s.contains("img2vid") || s.contains("assemble_video")) return 6;
        if (s.contains("finished") || s.contains("done") || s.contains("complete") || s.contains("success")) return 7;
        return 0;
    }

    private int dpToPx(int dp) {
        try {
            final float scale = getResources().getDisplayMetrics().density;
            return (int) (dp * scale + 0.5f);
        } catch (Exception e) {
            return dp;
        }
    }

    private int generateViewIdCompat() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 17) {
                try { return View.generateViewId(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        // fallback Java7/AIDE-safe
        for (;;) {
            final int result = sNextGeneratedId.get();
            int newValue = result + 1;
            if (newValue > 0x00FFFFFF) newValue = 1;
            if (sNextGeneratedId.compareAndSet(result, newValue)) return result;
        }
    }

    // utils
    private boolean equalsSafe(String a, String b){ return a != null && b != null && a.equals(b); }
    private String firstNonEmpty(String a, String b){
        if (a != null && a.trim().length() > 0) return a.trim();
        if (b != null && b.trim().length() > 0) return b.trim();
        return null;
    }

    private String extrasSummary(Intent it) {
        if (it == null) return "{}";
        try {
            Bundle extras = it.getExtras();
            if (extras == null) return "{}";
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (String k : extras.keySet()) {
                if (!first) sb.append(", ");
                first = false;
                Object o = extras.get(k);
                String v = (o == null) ? "null" : o.toString();
                if (v.length() > 120) v = v.substring(0, 120) + "...";
                sb.append(k).append("=").append(v);
            }
            sb.append("}");
            return sb.toString();
        } catch (Throwable t) {
            return "{error summarizing extras}";
        }
    }

    // ignore noisy/placeholder progress messages (AIDE-safe)
    private boolean shouldIgnoreProgressMessage(String msg) {
        if (msg == null) return true;
        String m = msg.trim();
        if (m.length() == 0) return true;
        String ml = m.toLowerCase();

        if ("ok".equals(ml) || "done".equals(ml) || "working".equals(ml) ||
            "loading".equals(ml) || "processing".equals(ml) || "...".equals(ml)) return true;

        if (ml.startsWith("progress") || ml.startsWith("uploading") || ml.startsWith("downloading")) return true;

        // ignore pure percentage like "50%"
        try { if (m.matches("^\\s*\\d{1,3}%\\s*$")) return true; } catch (Exception ignored) {}

        // ignore only dots/commas/dashes
        try { if (m.matches("^[\\.|,\\-–—]+$")) return true; } catch (Exception ignored) {}

        return false;
    }
}
