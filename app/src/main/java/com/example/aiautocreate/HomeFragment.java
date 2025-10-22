package com.example.aiautocreate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.ScaleAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.ArrayList;

/**
 * HomeFragment - Ultra-Full Literal Final
 * AIDE-friendly / Java7
 *
 * - يرسل النص + الوقت + الجودة + الأبعاد كنص واضح مع البرومبت لـ Gemini
 * - يحفظ الناتج كاملاً (SCRIPTS + MSHHD/HAREKA + SSML + SSH)
 * - Parsing مرن يدعم أشكال الـ JSON المختلفة
 * - حفظ واسترجاع اختيارات الواجهة + آخر برومبت
 * - خيار صوت ثابت "استنساخ العينة (من الإعدادات)"
 * - Bootstrap افتراضيات + دمج الموديلات المختارة في القوائم الديناميكية قبل بناء القوائم
 */
public class HomeFragment extends Fragment implements PipelineManager.Callback {

    private static final String TAG = "HomeFragment";

    // مفاتيح حفظ آخر اختيار للواجهة
    private static final String K_UI_IMAGE_STYLE  = "ui_image_style";
    private static final String K_UI_COVER_STYLE  = "ui_cover_style";
    private static final String K_UI_VOICE_CHOICE = "ui_voice_choice";
    private static final String K_UI_VIDEO_STYLE  = "ui_video_style";
    private static final String K_UI_MONTAGE      = "ui_montage_style";
    private static final String K_UI_LAST_PROMPT  = "ui_last_prompt";

    // خيار ثابت في قائمة الأصوات لاستنساخ العينة
    public static final String VOICE_CLONE_OPTION = "استنساخ العينة (من الإعدادات)";

    private EditText etPrompt;
    private Button btnGo;
    private TextView tvLogs, tvInlineStatus, tvInlineStatusSmall;
    private VideoView videoPreview;
    private LinearLayout llStatus;
    private ProgressBar pbSmall;

    private Spinner spImageStyle, spCoverStyle, spVoice, spVideoStyle, spMontage;
    private boolean isProcessing = false;
    private PrefManager prefs;

    private final BroadcastReceiver modelsUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constants.ACTION_MODELS_UPDATED.equals(intent.getAction())) {
                if (tvLogs != null) tvLogs.append("🔄 تحديث القوائم من الإعدادات\n");
                buildSpinners();
            }
        }
    };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        prefs = new PrefManager(getActivity());
        // Bootstrap افتراضيات + مجلدات
        try { prefs.bootstrapDefaults(); } catch (Throwable ignored) {
            prefs.ensureModelListsDefaults();
            prefs.ensureGeneralStylesDefaults();
            prefs.ensureDefaultDirsExist();
        }
        checkAndRequestAllFilesAccess();

        // ربط عناصر الواجهة
        etPrompt        = (EditText) v.findViewById(R.id.et_prompt);
        btnGo           = (Button) v.findViewById(R.id.btn_go);
        tvLogs          = (TextView) v.findViewById(R.id.tv_logs);
        videoPreview    = (VideoView) v.findViewById(R.id.video_preview);
        llStatus        = (LinearLayout) v.findViewById(R.id.ll_status);
        pbSmall         = (ProgressBar) v.findViewById(R.id.pb_small);
        tvInlineStatus  = (TextView) v.findViewById(R.id.tv_inline_status);
        tvInlineStatusSmall = (TextView) v.findViewById(R.id.tv_inline_status_small);

        spImageStyle    = (Spinner) v.findViewById(R.id.sp_image_style);
        spCoverStyle    = (Spinner) v.findViewById(R.id.sp_cover_style);
        spVoice         = (Spinner) v.findViewById(R.id.sp_voice);
        spVideoStyle    = (Spinner) v.findViewById(R.id.sp_video_style);
        spMontage       = (Spinner) v.findViewById(R.id.sp_montage);

        buildSpinners();

        if (btnGo != null) {
            btnGo.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        animateButtonPress(btnGo);
                        if (!isProcessing) {
                            String text = (etPrompt != null) ? etPrompt.getText().toString().trim() : "";
                            if (text.length() == 0) {
                                Toast.makeText(getActivity(), "أدخل نص قبل البدء", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (getActivity() instanceof MainActivity) {
                                if (!((MainActivity) getActivity()).ensureNetworkBeforeStart()) return;
                            }

                            // احفظ آخر اختيارات الواجهة + البرومبت
                            persistUiSelections();
                            hideKeyboard();

                            // جلب إعدادات الوقت + الجودة + الأبعاد من FFmpegFragment
                            SharedPreferences ffmpegPrefs = getActivity().getSharedPreferences("ffmpeg_prefs", Context.MODE_PRIVATE);
                            String style = ffmpegPrefs.getString("last_selected_style", "قصص وروايات");
                            String prefix = "profile_" + style + "_";

                            String minutes = ffmpegPrefs.getString(prefix + "minutes", "01");
                            String seconds = ffmpegPrefs.getString(prefix + "seconds", "00");
                            String aspect  = ffmpegPrefs.getString(prefix + "aspect",  "16:9");
                            String quality = ffmpegPrefs.getString(prefix + "quality", "1080p");

                            String durationDesc = minutes + " دقيقة و " + seconds + " ثانية";

                            // ===== بناء برومبت شامل ومنظم مع الرموز =====
                            StringBuilder sb = new StringBuilder();

// طلب المستخدم الأساسي
                            sb.append("المطلوب: ").append(text).append("\n\n");

// الشروط العامة
                            sb.append("مدة الفيديو: ").append(durationDesc).append("\n");
                            sb.append("الجودة: ").append(quality).append("\n");
                            sb.append("الأبعاد: ").append(aspect).append("\n");

// قواعد اللغة
                            sb.append("- لغة كتابة القصة أو الاسكربت تعتمد على اللغة المكتوب بها نص الطلب.\n");

// قواعد SSML
                            sb.append("الآن بعد كتابة القصة كاملة، يجب أن تُخرِج نسخة أخرى بصيغة SSML قياسية للتحويل إلى صوت.\n");
                            sb.append("- يجب أن يبدأ النص بـ <speak> وينتهي بـ </speak>.\n");
                            sb.append("- استخدم <voice name=\"Narrator\"> ... </voice> للراوي.\n");
                            sb.append("- لكل شخصية أخرى استخدم <voice name=\"Character1\"> ... </voice>, <voice name=\"Character2\"> ... </voice> وهكذا.\n");
                            sb.append("- ضع النص بين الرموز 🎵 في البداية والنهاية ليُسهّل تحديد مكان SSML.\n");
                            sb.append("- إذا كان هناك راوي فقط → الملف اسمه SCRIPTS_SSML.\n");
                            sb.append("- إذا كان هناك راوي + شخصيات:\n");
                            sb.append("   * الراوي → SCRIPTS_SSML (🎵<speak><voice name=\"Narrator\">...</voice></speak>🎵).\n");
                            sb.append("   * الشخصية الأولى → SCRIPTS_SSML_V1 (🎵<speak><voice name=\"Character1\">...</voice></speak>🎵).\n");
                            sb.append("   * الثانية → SCRIPTS_SSML_V2، وهكذا.\n");
                            sb.append("- إذا وجد راوي وشخصية واحدة → الراوي يوضع في SCRIPTS_SSML، والشخصية في SCRIPTS_SSML_V1.\n");
                            sb.append("- راعِ أن طول النص الصوتي الكلي يجب أن يناسب مدة الفيديو المطلوبة: "+durationDesc+".\n");

// مثال صغير على SSML الصحيح
                            sb.append("مثال SSML:\n");
                            sb.append("🎵\n");
                            sb.append("<speak xml:lang=\"ar-SA\">\n");
                            sb.append("  <voice name=\"Narrator\">في مختبر يعج بالفوضى...</voice>\n");
                            sb.append("  <break time=\"300ms\"/>\n");
                            sb.append("  <voice name=\"Adham\">لن أستسلم! هذا اختراعي سيعمل.</voice>\n");
                            sb.append("</speak>\n");
                            sb.append("🎵\n");

// قواعد النصوص الكاملة
                            sb.append("- يجب كتابة نص القصة بالكامل وصافياً باسم SCRIPTS_SSH.\n");
                            sb.append("- ملف SCRIPTS_SSH يوضع فوق نصوص SCRIPTS_SSML مباشرة.\n");

// قواعد تسمية المشاهد والحركات
                            sb.append("- برومبتات المشاهد تسمى بالتسلسل: MSHHD1، MSHHD2 ... إلخ.\n");
                            sb.append("- كل برومبت مخصص لإنشاء صورة مشهد يجب أن يكون محصوراً بين الرمزين 😶 في البداية والنهاية.\n");
                            sb.append("- برومبتات تحريك المشاهد تسمى بالتسلسل: HAREKA1، HAREKA2 ... إلخ.\n");
                            sb.append("- كل برومبت مخصص لتحريك مشهد يجب أن يكون محصوراً بين الرمزين 🥱 في البداية والنهاية.\n");

// متطلبات اللغة للبرومبتات
                            sb.append("- جميع البرومبتات الخاصة بإنشاء الصور وتحريك المشاهد يجب أن تكون مكتوبة باللغة الإنجليزية.\n");

// الشخصيات
                            sb.append("- إذا وُجدت شخصية رئيسية: يجب أن تُعطى برومبت ثابت ومتسق يُستخدم في جميع المشاهد لضمان الحفاظ على ملامحها وسلوكها. يجب أن يشمل الوصف عناصر مثل المظهر الخارجي، السمات الشخصية الأساسية، نبرة الحديث، وحركات أو عبارات مميزة تُساعد النموذج على الاتساق عبر المشاهد.\n");
                            sb.append("- إذا وُجدت أكثر من شخصية: لكل شخصية أعد برومبت منفصل باللغة الإنجليزية أيضاً، يوضح هوية الشخصية وخلفيتها، لهجتها أو نبرتها، سماتها النفسية وسلوكها النموذجي، بالإضافة إلى ملاحظات حول الاتساق عبر المشاهد.\n");

// تقسيم القصة إلى مشاهد
                            sb.append("- قسّم القصة إلى مشاهد.\n");
                            sb.append("- كل مشهد يتضمن:\n");
                            sb.append("   * نص المشهد (بلغة النص الأصلية للطلب).\n");
                            sb.append("   * برومبت صورة المشهد محصوراً بين 😶...😶 وبالإنجليزية ويجب ان يكون البرومبت مفصل بشكل احترافي ويجب ان يكون عدد الاحرف المستعملة فيه ما بين 1200 الى 1500 حرف ولا يقل عن ذالك ابدآ.\n");
                            sb.append("   * برومبت حركة المشهد محصوراً بين 🥱...🥱 وبالإنجليزية ويجب ان يكون البرومبت مفصل بشكل احترافي.\n");

// ===== تحويل النتيجة إلى نص نهائي =====
                            String finalPrompt = sb.toString();
                            
                            String gemKey = prefs.getGeminiKey();
                            if (gemKey != null && gemKey.length() > 0) {
                                callGeminiGenerate(finalPrompt);
                            } else {
                                if (tvLogs != null) tvLogs.append("⏭ Gemini: لا مفتاح.\n");
                                startProcessing(finalPrompt, false);
                            }
                        } else {
                            cancelProcessing();
                        }
                    }
                });
        }
        return v;
    }

    // إذن الملفات Android11+
    private void checkAndRequestAllFilesAccess(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            try {
                if(!android.os.Environment.isExternalStorageManager()){
                    Toast.makeText(getActivity(),"⚠ امنح إذن إدارة الملفات",Toast.LENGTH_LONG).show();
                    Intent i=new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(Uri.parse("package:" + getActivity().getPackageName()));
                    startActivity(i);
                }
            }catch(Exception ex){
                try{
                    Intent i=new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(i);
                }catch(Exception ignored){}
            }
        }
    }

    // بناء Spinners (مع إضافة خيار الاستنساخ + استرجاع آخر اختيارات)
    private void buildSpinners() {
        if (getActivity() == null) return;

        // تأكيد تهيئة القوائم الديناميكية + تسجيل الباقات
        prefs.ensureModelListsDefaults();
        prefs.ensureSelectedModelsInLists();
        StylePacks.registerIntoPrefs(prefs);

        List<String> imgStyles = prefs.csvToList(prefs.getModelStyles("image_styles"));
        if(imgStyles.isEmpty()){ imgStyles.add("واقعي"); imgStyles.add("كرتوني"); }
        setSpinnerAdapter(spImageStyle,imgStyles);

        List<String> coverStyles = prefs.csvToList(prefs.getModelStyles("cover_styles"));
        if(coverStyles.isEmpty()){ coverStyles.add("غلاف بسيط"); coverStyles.add("غلاف ملون"); }
        setSpinnerAdapter(spCoverStyle,coverStyles);

        String ttsModel = prefs.getSelectedTtsModel();
        if(ttsModel == null || ttsModel.indexOf('/') < 0){ ttsModel = prefs.getTtsModelId(); }
        List<String> voiceStyles = prefs.csvToList(prefs.getModelVoices(ttsModel));
        if(!voiceStyles.contains(VOICE_CLONE_OPTION)) {
            List<String> tmp = new ArrayList<String>();
            tmp.add(VOICE_CLONE_OPTION);
            for(int i=0;i<voiceStyles.size();i++) tmp.add(voiceStyles.get(i));
            voiceStyles = tmp;
        }
        if(voiceStyles.isEmpty()){ voiceStyles.add("صوت1"); voiceStyles.add("صوت2"); }
        setSpinnerAdapter(spVoice,voiceStyles);

        List<String> videoStyles = prefs.csvToList(prefs.getModelStyles("video_styles"));
        if(videoStyles.isEmpty()){ videoStyles.add("درامي"); videoStyles.add("اكشن"); }
        setSpinnerAdapter(spVideoStyle,videoStyles);

        List<String> montageStyles = prefs.csvToList(prefs.getModelStyles("montage_styles"));
        if(montageStyles.isEmpty()){
            montageStyles=new ArrayList<String>();
            montageStyles.add("قصص وروايات");
            montageStyles.add("حماسي وجذاب");
            montageStyles.add("احترافية وأنيق");
            montageStyles.add("مخصص");
        }
        setSpinnerAdapter(spMontage,montageStyles);

        // استرجاع آخر اختيارات محفوظة
        selectSpinnerValue(spImageStyle,  prefs.getSelection(K_UI_IMAGE_STYLE,  imgStyles.size()>0? imgStyles.get(0):""));
        selectSpinnerValue(spCoverStyle,  prefs.getSelection(K_UI_COVER_STYLE,  coverStyles.size()>0? coverStyles.get(0):""));
        selectSpinnerValue(spVoice,       prefs.getSelection(K_UI_VOICE_CHOICE, voiceStyles.size()>0? voiceStyles.get(0):""));
        selectSpinnerValue(spVideoStyle,  prefs.getSelection(K_UI_VIDEO_STYLE,  videoStyles.size()>0? videoStyles.get(0):""));
        selectSpinnerValue(spMontage,     prefs.getSelection(K_UI_MONTAGE,      montageStyles.size()>0? montageStyles.get(0):""));

        if(etPrompt != null){
            String lastPrompt = prefs.getSelection(K_UI_LAST_PROMPT, "");
            if(lastPrompt.length()>0) etPrompt.setText(lastPrompt);
        }
    }

    private void setSpinnerAdapter(Spinner sp, List<String> items){
        if(getActivity()==null || sp==null) return;
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(getActivity(),
                                                                android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
    }

    private void selectSpinnerValue(Spinner sp,String val){
        if(sp==null||val==null)return;
        for(int i=0;i<sp.getCount();i++){
            Object o=sp.getItemAtPosition(i);
            if(o!=null && val.equals(o.toString())){ sp.setSelection(i); return;}
        }
    }

    private void persistUiSelections() {
        if(spImageStyle!=null && spImageStyle.getSelectedItem()!=null)
            prefs.saveSelection(K_UI_IMAGE_STYLE, spImageStyle.getSelectedItem().toString());
        if(spCoverStyle!=null && spCoverStyle.getSelectedItem()!=null)
            prefs.saveSelection(K_UI_COVER_STYLE, spCoverStyle.getSelectedItem().toString());
        if(spVoice!=null && spVoice.getSelectedItem()!=null)
            prefs.saveSelection(K_UI_VOICE_CHOICE, spVoice.getSelectedItem().toString());
        if(spVideoStyle!=null && spVideoStyle.getSelectedItem()!=null)
            prefs.saveSelection(K_UI_VIDEO_STYLE, spVideoStyle.getSelectedItem().toString());
        if(spMontage!=null && spMontage.getSelectedItem()!=null)
            prefs.saveSelection(K_UI_MONTAGE, spMontage.getSelectedItem().toString());
        if(etPrompt != null)
            prefs.saveSelection(K_UI_LAST_PROMPT, etPrompt.getText().toString());
    }

    // 🔥 Gemini API Call
    private void callGeminiGenerate(final String promptText) {
        new Thread(new Runnable() {
                public void run() {
                    try {
                        String endpoint = prefs.getGeminiEndpointOrDefault();
                        String apiKey   = prefs.getGeminiKey();

                        // بناء JSON
                        JSONObject payload = new JSONObject();
                        JSONArray contents = new JSONArray();
                        JSONObject contentObj = new JSONObject();
                        JSONArray parts = new JSONArray();
                        JSONObject part = new JSONObject();
                        part.put("text", promptText);
                        parts.put(part);
                        contentObj.put("parts", parts);
                        contents.put(contentObj);
                        payload.put("contents", contents);

                        // استدعاء API
                        String response = ApiClient.postJson(endpoint, apiKey, payload.toString());
                        Log.i(TAG, "Gemini RAW Response = " + response);

                        final String generated = parseGeminiResponse(response);

                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(new Runnable() {
                                public void run() {
                                    if (generated != null && generated.length() > 0) {
                                        saveAndBroadcastScript(generated);
                                        startProcessing(promptText, true);
                                    } else {
                                        if (tvLogs != null) tvLogs.append("⚠ Gemini لم يرجع نص.\n");
                                        startProcessing(promptText, false);
                                    }
                                }
                            });
                    } catch (final Exception e) {
                        if (getActivity() == null) return;
                        getActivity().runOnUiThread(new Runnable() {
                                public void run() {
                                    if (tvLogs != null) tvLogs.append("⚠ خطأ Gemini: " + e.getMessage()+"\n");
                                    startProcessing(promptText,false);
                                }
                            });
                    }
                }
            }).start();
    }

    // 📦 Parsing مرن للاستجابات المختلفة
    private String parseGeminiResponse(String response) {
        try {
            JSONObject root = new JSONObject(response);

            // ابحث مباشرة عن candidates
            if (root.has("candidates")) {
                JSONArray cands = root.optJSONArray("candidates");
                if (cands != null && cands.length()>0) {
                    JSONObject cand0 = cands.optJSONObject(0);

                    // 1) الشكل الرسمي: content.parts[].text
                    if (cand0.has("content")) {
                        JSONObject content = cand0.optJSONObject("content");
                        JSONArray partsArr = content.optJSONArray("parts");
                        if (partsArr != null && partsArr.length() > 0) {
                            for (int i=0;i<partsArr.length();i++) {
                                JSONObject part = partsArr.optJSONObject(i);
                                if (part != null && part.has("text")) {
                                    return part.optString("text");
                                }
                            }
                        }
                    }

                    // 2) field باسم text مباشرة
                    if (cand0.has("text")) {
                        return cand0.optString("text");
                    }

                    // 3) field باسم output
                    if (cand0.has("output")) {
                        return cand0.optString("output");
                    }

                    // 4) fallback: نحاول نرجع كل الكائن كسطر نص
                    return cand0.toString();
                }
            }

            // fallback: النص بأكمله
            return response;

        } catch(Exception e) {
            Log.e(TAG,"parseGeminiResponse error:"+e.getMessage());
            return null;
        }
    }

    // ===== حفظ الاسكربت وملفاته (باستخراج النصوص من داخل العلامات 😶 / 🥱 / 🎵) =====
    private void saveAndBroadcastScript(String text){
        try{
            File dir = new File(prefs.getScriptsPath());
            if(!dir.exists()) dir.mkdirs();

            // 1) حفظ النص الخام كامل كمرجع
            File fullScript=new File(dir,"script_"+System.currentTimeMillis()+".txt");
            FileOutputStream fos=new FileOutputStream(fullScript);
            fos.write(text.getBytes("UTF-8")); fos.close();

            // 2) حفظ نص القصة الصافي (SCRIPTS_SSH) كما هو (إن وُجد)
            if(text.contains("SCRIPTS_SSH")){
                int start = text.indexOf("SCRIPTS_SSH");
                String sshContent = text.substring(start);
                File ssh=new File(dir,"SCRIPTS_SSH.txt");
                FileOutputStream fos2=new FileOutputStream(ssh);
                fos2.write(sshContent.getBytes("UTF-8"));
                fos2.close();
            }

            // 3) استخراج برومبتات الصور (MSHHDn) من بين 😶 … 😶
            for(int i=1;;i++){
                String marker = "MSHHD"+i;
                if(text.contains(marker)){
                    String snippet = extractBetween(text, "😶", "😶", marker);
                    if(snippet != null && snippet.length() > 0){
                        File f=new File(dir,marker+".txt");
                        if(!f.exists()){
                            FileOutputStream fo=new FileOutputStream(f);
                            fo.write(snippet.getBytes("UTF-8"));
                            fo.close();
                        }
                    }
                } else break;
            }

            // 4) استخراج برومبتات الحركة (HAREKAn) من بين 🥱 … 🥱
            for(int i=1;;i++){
                String marker = "HAREKA"+i;
                if(text.contains(marker)){
                    String snippet = extractBetween(text, "🥱", "🥱", marker);
                    if(snippet != null && snippet.length() > 0){
                        File f=new File(dir,marker+".txt");
                        if(!f.exists()){
                            FileOutputStream fo=new FileOutputStream(f);
                            fo.write(snippet.getBytes("UTF-8"));
                            fo.close();
                        }
                    }
                } else break;
            }

            // 5) استخراج نصوص SSML (🎵 … 🎵)
            if(text.contains("SCRIPTS_SSML")){
                String snippet = extractBetween(text,"🎵","🎵","SCRIPTS_SSML");
                if(snippet != null && snippet.length() > 0){
                    File f=new File(dir,"SCRIPTS_SSML.txt");
                    FileOutputStream fo=new FileOutputStream(f);
                    fo.write(snippet.getBytes("UTF-8"));
                    fo.close();
                }
            }
            // SSML للشخصيات
            for(int i=1;;i++){
                String marker="SCRIPTS_SSML_V"+i;
                if(text.contains(marker)){
                    String snippet = extractBetween(text,"🎵","🎵",marker);
                    if(snippet != null && snippet.length() > 0){
                        File f=new File(dir,marker+".txt");
                        FileOutputStream fo=new FileOutputStream(f);
                        fo.write(snippet.getBytes("UTF-8"));
                        fo.close();
                    }
                } else break;
            }

            if (tvLogs != null) tvLogs.append("✔ تم حفظ الاسكربت + الملفات المستخرجة من داخل الرموز.\n");
        }catch(Exception e){
            Log.e(TAG,"save script fail:"+e.getMessage());
        }
    }

    /**
     * تبحث عن marker ثم تستخرج النص الموجود بين رمزي startSymbol و endSymbol الأقرب له.
     * مثال: extractBetween(text,"😶","😶","MSHHD1") → يرجع محتوى برومبت الصورة فقط.
     */
    private String extractBetween(String text, String startSymbol, String endSymbol, String marker){
        try{
            int mIdx = text.indexOf(marker);
            if(mIdx < 0) return null;
            int start = text.indexOf(startSymbol, mIdx);
            int end   = text.indexOf(endSymbol, start+startSymbol.length());
            if(start >= 0 && end > start){
                return text.substring(start+startSymbol.length(), end).trim();
            }
        }catch(Exception e){
            Log.e(TAG,"extractBetween error:"+e.getMessage());
        }
        return null;
    }

    // بدء البايبلاين
    private void startProcessing(String prompt, boolean skipGemini){
        isProcessing=true;
        if(llStatus!=null) llStatus.setVisibility(View.VISIBLE);
        if(tvInlineStatus!=null) tvInlineStatus.setVisibility(View.VISIBLE);
        if(tvInlineStatusSmall!=null) tvInlineStatusSmall.setVisibility(View.VISIBLE);
        if(pbSmall!=null) pbSmall.setVisibility(View.VISIBLE);
        if(btnGo!=null) btnGo.setText("X");
        if(etPrompt!=null) etPrompt.setEnabled(false);

        Context ctx=getActivity(); if(ctx==null) return;
        Intent i=new Intent(ctx,PipelineService.class);
        i.putExtra("prompt",prompt);
        i.putExtra("skip_gemini",skipGemini);

        if(spImageStyle!=null && spImageStyle.getSelectedItem()!=null)
            i.putExtra("image_style", spImageStyle.getSelectedItem().toString());
        if(spCoverStyle!=null && spCoverStyle.getSelectedItem()!=null)
            i.putExtra("cover_style", spCoverStyle.getSelectedItem().toString());
        if(spVoice!=null && spVoice.getSelectedItem()!=null)
            i.putExtra("voice_choice", spVoice.getSelectedItem().toString());
        if(spVideoStyle!=null && spVideoStyle.getSelectedItem()!=null)
            i.putExtra("video_style", spVideoStyle.getSelectedItem().toString());

        SharedPreferences ffmpegPrefs=ctx.getSharedPreferences("ffmpeg_prefs",Context.MODE_PRIVATE);
        String selectedStyle = (spMontage!=null && spMontage.getSelectedItem()!=null)
            ? spMontage.getSelectedItem().toString()
            : ffmpegPrefs.getString("last_selected_style","قصص وروايات");

        ffmpegPrefs.edit().putString("last_selected_style", selectedStyle).apply();
        i.putExtra("current_style", selectedStyle);
        i.putExtra("montage_style", selectedStyle);

        String prefix = "profile_" + selectedStyle + "_";
        String minutes = ffmpegPrefs.getString(prefix + "minutes", "00");
        String seconds = ffmpegPrefs.getString(prefix + "seconds", "30");
        String aspect  = ffmpegPrefs.getString(prefix + "aspect",  "16:9");
        String quality = ffmpegPrefs.getString(prefix + "quality", "1080p");

        i.putExtra("video_minutes",minutes);
        i.putExtra("video_seconds",seconds);
        i.putExtra("aspect_ratio",aspect);
        i.putExtra("video_quality",quality);

        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    private void cancelProcessing(){
        Context ctx=getActivity();
        if(ctx!=null){
            try { ctx.stopService(new Intent(ctx,PipelineService.class)); } catch(Exception ignored){}
            if (tvLogs != null) tvLogs.append("تم الإلغاء.\n");
        }
        restoreUi();
    }

    private void restoreUi(){
        isProcessing=false;
        if(llStatus!=null) llStatus.setVisibility(View.GONE);
        if(tvInlineStatus!=null) tvInlineStatus.setVisibility(View.GONE);
        if(tvInlineStatusSmall!=null) tvInlineStatusSmall.setVisibility(View.GONE);
        if(pbSmall!=null) pbSmall.setVisibility(View.GONE);
        if(btnGo!=null) btnGo.setText("Go");
        if(etPrompt!=null) etPrompt.setEnabled(true);
    }

    private void animateButtonPress(View v){
        if(v==null)return;
        ScaleAnimation sa=new ScaleAnimation(1f,0.92f,1f,0.92f,
                                             ScaleAnimation.RELATIVE_TO_SELF,0.5f,ScaleAnimation.RELATIVE_TO_SELF,0.5f);
        sa.setDuration(120); sa.setRepeatCount(1); sa.setRepeatMode(ScaleAnimation.REVERSE);
        v.startAnimation(sa);
    }

    // ===== Pipeline Callbacks =====
    public void onStageProgress(final String stage, final String msg){
        if(getActivity()==null)return;
        getActivity().runOnUiThread(new Runnable(){ public void run(){
                    if (tvLogs != null) tvLogs.append(stage+": "+msg+"\n");
                }});
    }

    public void onFinished(final String outPath){
        if(getActivity()==null)return;
        getActivity().runOnUiThread(new Runnable(){ public void run(){
                    if (tvLogs != null) tvLogs.append("✔ انتهى:"+outPath+"\n");
                    // احفظ آخر اختيارات قبل إعادة الواجهة
                    persistUiSelections();
                    restoreUi();
                    try{
                        if(videoPreview!=null){
                            videoPreview.setVideoPath(outPath);
                            videoPreview.start();
                        }
                    }catch(Exception ignored){}
                }});
    }

    public void onError(final String stage, final String msg){
        if(getActivity()==null)return;
        getActivity().runOnUiThread(new Runnable(){ public void run(){
                    if (tvLogs != null) tvLogs.append("⚠ خطأ "+stage+": "+msg+"\n");
                    persistUiSelections();
                    restoreUi();
                }});
    }

    public void onCancelled(){
        if(getActivity()==null)return;
        getActivity().runOnUiThread(new Runnable(){ public void run(){
                    if (tvLogs != null) tvLogs.append("تم إلغاء العملية\n");
                    persistUiSelections();
                    restoreUi();
                }});
    }

    private void hideKeyboard(){
        try{
            if(getActivity()==null || etPrompt==null) return;
            InputMethodManager imm=(InputMethodManager)getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if(imm!=null) imm.hideSoftInputFromWindow(etPrompt.getWindowToken(),0);
        }catch(Exception ignored){}
    }

    @Override public void onResume(){
        super.onResume();
        if(getActivity()!=null){
            getActivity().registerReceiver(modelsUpdatedReceiver,new IntentFilter(Constants.ACTION_MODELS_UPDATED));
        }
        buildSpinners();
    }

    @Override public void onPause(){
        super.onPause();
        if(getActivity()!=null){
            try{ getActivity().unregisterReceiver(modelsUpdatedReceiver); }catch(Exception ignored){}
        }
    }

    @Override
    public void onDestroyView(){
        super.onDestroyView();
        try{
            if(videoPreview!=null){ videoPreview.stopPlayback(); }
        }catch(Exception ignored){}
    }
}
