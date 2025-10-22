package com.example.aiautocreate;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * SettingsActivity - Ultra-Full Literal (Dynamic Model Lists + Refined Parsing + Bootstrap)
 * - سبينات الموديلات: تعرض Model IDs فقط من قوائم ديناميكية (CSV) مع الحفاظ على التوافق مع القيم الأساسية.
 * - زر "تحديث قوائم النماذج":
 *   1) يجلب tags من HF API ويستخرج منها styles/voices بكشاف كلمات مضبوط
 *   2) إن فشل/لم يجد: يحاول قراءة README ويقتطع أقسام Styles/Voices فقط
 *   3) يحفظ النتائج في PrefManager ويجمع image_styles/cover_styles/video_styles
 * - يضمن أن selected_* دائماً Model IDs
 * - ملاحظة: القوائم الديناميكية تُهيّأ عبر PrefManager.ensureModelListsDefaults()
 * - تحسين: bootstrapDefaults + ensureSelectedModelsInLists قبل بناء الـ Spinners
 * - تحسين: تمرير Authorization عند قراءة API/README من HF إن توفر hf_token (يدعم الخاص/المقيد)
 */
public class SettingsActivity extends Activity {
    private static final int REQ_PICK_VOICE = 1234;

    private PrefManager prefs;
    private Spinner spSd, spImg2Vid, spTts;
    private TextView tvVoicePath;
    private Button btnPick, btnDeleteVoice, btnRefreshModels, btnSave;
    private CheckBox cbUseClone;

    private EditText etGeminiKey, etGeminiUrl, etHfToken, etHfSdUrl, etTtsUrl, etFfmpegPath;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_settings);

        prefs = new PrefManager(this);

        // Bootstrap افتراضيات + قوائم + مجلدات
        try { prefs.bootstrapDefaults(); } catch (Throwable ignored) {
            // إذا لم تتوفر bootstrapDefaults في PrefManager الحالي، نفذ خطواتها يدويًا:
            prefs.ensureModelListsDefaults();
            prefs.ensureGeneralStylesDefaults();
            prefs.ensureDefaultDirsExist();
        }
        // دمج الموديلات المختارة والأساسية داخل القوائم الديناميكية
        prefs.ensureSelectedModelsInLists();

        spSd        = (Spinner) findViewById(R.id.sp_settings_sd_model);
        spImg2Vid   = (Spinner) findViewById(R.id.sp_settings_img2vid_model);
        spTts       = (Spinner) findViewById(R.id.sp_settings_tts_model);
        tvVoicePath = (TextView) findViewById(R.id.tv_voice_sample_path);

        btnPick          = (Button) findViewById(R.id.btn_pick_voice_sample);
        btnDeleteVoice   = (Button) findViewById(R.id.btn_delete_voice_sample);
        btnRefreshModels = (Button) findViewById(R.id.btn_refresh_models);
        cbUseClone       = (CheckBox) findViewById(R.id.cb_use_voice_clone);
        btnSave          = (Button) findViewById(R.id.btn_save_settings);

        etGeminiKey   = (EditText) findViewById(R.id.et_gemini_key);
        etGeminiUrl   = (EditText) findViewById(R.id.et_gemini_url);
        etHfToken     = (EditText) findViewById(R.id.et_hf_key);
        etHfSdUrl     = (EditText) findViewById(R.id.et_hf_sd_url);
        etTtsUrl      = (EditText) findViewById(R.id.et_tts_url);
        etFfmpegPath  = (EditText) findViewById(R.id.et_ffmpeg_path);

        buildSpinners();
        restoreTextFields();
        showSavedVoiceSample();

        // Pick Voice
        btnPick.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.setType("audio/*");
                    startActivityForResult(Intent.createChooser(i, "اختر ملف صوتي (3-6s)"), REQ_PICK_VOICE);
                }
            });

        // Delete voice
        btnDeleteVoice.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    prefs.setVoiceSamplePath("");
                    prefs.setUseVoiceClone(false);
                    tvVoicePath.setText("");
                    cbUseClone.setChecked(false);
                    Toast.makeText(SettingsActivity.this,"تم حذف عينة الصوت",Toast.LENGTH_SHORT).show();
                }
            });

        // Refresh models (تحديث الستايلات/الأصوات المشتقة من نماذج HF)
        btnRefreshModels.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!isNetworkConnected()) {
                        Toast.makeText(SettingsActivity.this,"غير متصل بالإنترنت",Toast.LENGTH_LONG).show();
                        return;
                    }
                    Toast.makeText(SettingsActivity.this,"جارٍ تحديث قوائم النماذج...",Toast.LENGTH_SHORT).show();
                    new Thread(new Runnable() {
                            public void run() {
                                refreshModelsAndCache();
                                runOnUiThread(new Runnable() {
                                        public void run() {
                                            // تأكيد الدمج ضمن القوائم قبل بناء الـ Spinners
                                            prefs.ensureSelectedModelsInLists();
                                            buildSpinners();
                                            Toast.makeText(SettingsActivity.this,"تم تحديث القوائم",Toast.LENGTH_SHORT).show();
                                            try{
                                                sendBroadcast(new Intent(Constants.ACTION_MODELS_UPDATED)
                                                              .putExtra(Constants.EXTRA_STAGE,"models")
                                                              .putExtra(Constants.EXTRA_MESSAGE,"قوائم النماذج تم تحديثها"));
                                            }catch(Throwable ignored){}
                                        }
                                    });
                            }
                        }).start();
                }
            });

        // Save
        btnSave.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { saveSettings(); }
            });
    }

    // ================= Spinners =================
    private void buildSpinners() {
        // SD (Text→Image) models
        List<String> sdList = new ArrayList<String>();
        List<String> dynSd = prefs.getSdModelsList();
        if (dynSd != null) {
            for (int i = 0; i < dynSd.size(); i++) {
                addUnique(sdList, dynSd.get(i));
            }
        }
        addUnique(sdList, prefs.getSdModelId());
        addUnique(sdList, prefs.getSdAlt1());
        addUnique(sdList, prefs.getSdAlt2());

        ArrayAdapter<String> aSd = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, sdList);
        aSd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSd.setAdapter(aSd);
        selectSpinnerValue(spSd, ensureModelId(prefs.getSelectedSdModel(), prefs.getSdModelId()));

        // Img2Vid models
        List<String> vidList = new ArrayList<String>();
        List<String> dynVid = prefs.getImg2VidModelsList();
        if (dynVid != null) {
            for (int i = 0; i < dynVid.size(); i++) {
                addUnique(vidList, dynVid.get(i));
            }
        }
        addUnique(vidList, prefs.getImg2VidModelId());
        addUnique(vidList, prefs.getImg2VidXTModelId());
        addUnique(vidList, prefs.getWanImg2VidModelId());

        ArrayAdapter<String> aImg = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, vidList);
        aImg.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spImg2Vid.setAdapter(aImg);
        selectSpinnerValue(spImg2Vid, ensureModelId(prefs.getSelectedImg2VidModel(), prefs.getImg2VidModelId()));

        // TTS models
        List<String> ttsList = new ArrayList<String>();
        List<String> dynTts = prefs.getTtsModelsList();
        if (dynTts != null) {
            for (int i = 0; i < dynTts.size(); i++) {
                addUnique(ttsList, dynTts.get(i));
            }
        }
        addUnique(ttsList, prefs.getTtsModelId());
        addUnique(ttsList, prefs.getTtsAlt1());
        addUnique(ttsList, prefs.getTtsAlt2());

        ArrayAdapter<String> aTts = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, ttsList);
        aTts.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTts.setAdapter(aTts);
        selectSpinnerValue(spTts, ensureModelId(prefs.getSelectedTtsModel(), prefs.getTtsModelId()));
    }

    private void addUnique(List<String> list, String v){
        if(v!=null){
            String t=v.trim();
            if(t.length()>0 && !list.contains(t)) list.add(t);
        }
    }

    private void restoreTextFields() {
        etGeminiKey.setText(prefs.getGeminiKey());
        etGeminiUrl.setText(prefs.getGeminiUrl());
        etHfToken.setText(prefs.getHfToken());
        etHfSdUrl.setText(prefs.getHfSdUrl());
        etTtsUrl.setText(prefs.getTtsUrl());
        etFfmpegPath.setText(prefs.getFfmpegPath());
    }

    private void showSavedVoiceSample() {
        String sample = prefs.getVoiceSamplePath();
        if(sample!=null && sample.length()>0){
            tvVoicePath.setText(getFileDisplayName(Uri.parse(sample)));
        }
        cbUseClone.setChecked(prefs.getUseVoiceClone());
    }

    private void saveSettings(){
        String selSd      = getSpinnerVal(spSd);
        String selImg2Vid = getSpinnerVal(spImg2Vid);
        String selTts     = getSpinnerVal(spTts);

        prefs.setSelectedSdModel( ensureModelId(selSd,      prefs.getSdModelId()) );
        prefs.setSelectedImg2VidModel( ensureModelId(selImg2Vid, prefs.getImg2VidModelId()) );
        prefs.setSelectedTtsModel( ensureModelId(selTts,    prefs.getTtsModelId()) );
        prefs.setUseVoiceClone(cbUseClone.isChecked());

        prefs.setGeminiKey(etGeminiKey.getText().toString());
        prefs.setGeminiUrl(etGeminiUrl.getText().toString());
        prefs.setHfToken(etHfToken.getText().toString());
        prefs.setHfSdUrl(etHfSdUrl.getText().toString());
        prefs.setTtsUrl(etTtsUrl.getText().toString());
        prefs.setFfmpegPath(etFfmpegPath.getText().toString());

        // دمج الاختيارات ضمن القوائم الديناميكية
        prefs.ensureModelListsDefaults();
        prefs.ensureSelectedModelsInLists();

        // أبلغ الواجهة الأخرى
        try{
            sendBroadcast(new Intent(Constants.ACTION_MODELS_UPDATED)
                          .putExtra(Constants.EXTRA_STAGE,"settings")
                          .putExtra(Constants.EXTRA_MESSAGE,"تم حفظ الإعدادات وتحديث القوائم"));
        }catch(Throwable ignored){}

        Toast.makeText(this,"✔ تم حفظ الإعدادات",Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    // ================= Refresh (Refined) =================
    private void refreshModelsAndCache(){
        // إبقاء القيم الأساسية محفوظة كما هي
        prefs.setSdModelId(prefs.getSdModelId());
        prefs.setSdAlt1(prefs.getSdAlt1());
        prefs.setSdAlt2(prefs.getSdAlt2());
        prefs.setImg2VidModelId(prefs.getImg2VidModelId());
        prefs.setImg2VidXTModelId(prefs.getImg2VidXTModelId());
        prefs.setWanImg2VidModelId(prefs.getWanImg2VidModelId());
        prefs.setTtsModelId(prefs.getTtsModelId());
        prefs.setTtsAlt1(prefs.getTtsAlt1());
        prefs.setTtsAlt2(prefs.getTtsAlt2());

        // Styles من موديلات الصور
        refineAndSaveStyles(prefs.getSdModelId());
        refineAndSaveStyles(prefs.getSdAlt1());
        refineAndSaveStyles(prefs.getSdAlt2());

        // Styles من موديلات الفيديو
        refineAndSaveStyles(prefs.getImg2VidModelId());
        refineAndSaveStyles(prefs.getImg2VidXTModelId());
        refineAndSaveStyles(prefs.getWanImg2VidModelId());

        // Voices من موديلات TTS فقط
        refineAndSaveVoices(prefs.getTtsModelId());
        refineAndSaveVoices(prefs.getTtsAlt1());
        refineAndSaveVoices(prefs.getTtsAlt2());

        // تجميع القوائم العامة
        List<String> aggImage = unionCsv(
            prefs.getModelStyles(prefs.getSdModelId()),
            prefs.getModelStyles(prefs.getSdAlt1()),
            prefs.getModelStyles(prefs.getSdAlt2())
        );
        if(aggImage.isEmpty()){
            addUnique(aggImage, "واقعي"); addUnique(aggImage, "كرتوني");
            addUnique(aggImage, "خيالي"); addUnique(aggImage, "Anime");
            addUnique(aggImage, "سينمائي");
        }
        prefs.setModelStyles("image_styles", prefs.joinListToCsv(aggImage));
        prefs.setModelStyles("cover_styles", prefs.joinListToCsv(aggImage)); // يمكن تخصيصها لاحقاً

        List<String> aggVideo = unionCsv(
            prefs.getModelStyles(prefs.getImg2VidModelId()),
            prefs.getModelStyles(prefs.getImg2VidXTModelId()),
            prefs.getModelStyles(prefs.getWanImg2VidModelId())
        );
        if(aggVideo.isEmpty()){
            addUnique(aggVideo, "درامي"); addUnique(aggVideo, "موسيقي");
            addUnique(aggVideo, "اكشن"); addUnique(aggVideo, "وثائقي");
        }
        prefs.setModelStyles("video_styles", prefs.joinListToCsv(aggVideo));

        // أساليب المونتاج ثابتة
        prefs.setModelStyles("montage_styles","قصص وروايات,حماسي وجذاب,احترافية وأنيق,مخصص");
    }

    // ================= التعديل: الاعتماد على ReadmeFilter + تمرير Authorization عند الفetch =================
    private void refineAndSaveStyles(String modelId){
        if(modelId==null) return;

        // القيم الحالية
        List<String> existing = prefs.csvToList(prefs.getModelStyles(modelId));

        // مصادر الفلترة
        List<String> hfTags = fetchHfTags(modelId);
        String readme = fetchReadmeWithFallback(modelId);

        // فلترة ذكية عبر ReadmeFilter
        List<String> filtered = ReadmeFilter.extractStyles(modelId, readme, hfTags);

        // دمج + حفظ
        for(int i=0;i<filtered.size();i++){
            String s = filtered.get(i);
            if(s!=null && s.trim().length()>0 && !existing.contains(s)) existing.add(s);
        }
        if(existing.size()>50){
            List<String> cut = new ArrayList<String>();
            for(int i=0;i<50 && i<existing.size();i++){ cut.add(existing.get(i)); }
            existing = cut;
        }
        prefs.setModelStyles(modelId, prefs.joinListToCsv(existing));
    }

    private void refineAndSaveVoices(String modelId){
        if(modelId==null) return;

        // القيم الحالية
        List<String> existing = prefs.csvToList(prefs.getModelVoices(modelId));

        // مصادر الفلترة
        List<String> hfTags = fetchHfTags(modelId);
        String readme = fetchReadmeWithFallback(modelId);

        // فلترة ذكية عبر ReadmeFilter
        List<String> filtered = ReadmeFilter.extractVoices(modelId, readme, hfTags);

        // افتراضي بسيط جداً إذا لم يخرج شيء
        if(filtered.isEmpty()){
            addUnique(filtered, "ذكر");
            addUnique(filtered, "أنثى");
        }

        // دمج + حفظ
        for(int i=0;i<filtered.size();i++){
            String v = filtered.get(i);
            if(v!=null && v.trim().length()>0 && !existing.contains(v)) existing.add(v);
        }
        if(existing.size()>50){
            List<String> cut = new ArrayList<String>();
            for(int i=0;i<50 && i<existing.size();i++){ cut.add(existing.get(i)); }
            existing = cut;
        }
        prefs.setModelVoices(modelId, prefs.joinListToCsv(existing));
    }
    // ================= نهاية =================

    // ================= HF API =================
    private List<String> fetchHfTags(String modelId){
        List<String> tags = new ArrayList<String>();
        if(modelId==null) return tags;
        try{
            String url = "https://huggingface.co/api/models/" + modelId;
            String json = fetchUrl(url);
            if(json==null || json.length()==0) return tags;
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.optJSONArray("tags");
            if(arr!=null){
                for(int i=0;i<arr.length();i++){
                    String t = arr.optString(i,null);
                    if(t!=null && t.trim().length()>0 && !tags.contains(t)) tags.add(t.trim());
                }
            }
        }catch(Exception ignored){}
        return tags;
    }

    private String fetchReadmeWithFallback(String id){
        if(id==null)return "";
        StringBuilder sb=new StringBuilder();
        List<String> links=ModelReadmeLinks.getAllReadmeLinks(id);
        for(int i=0;i<links.size();i++){
            sb.append(fetchUrl(links.get(i)));
        }
        return sb.toString();
    }

    private String fetchUrl(String urlStr){
        BufferedReader br=null;
        try{
            HttpURLConnection c=(HttpURLConnection)new URL(urlStr).openConnection();
            c.setConnectTimeout(7000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent","Mozilla/5.0 (AI-AutoCreate)");
            // إذا كنا نطلب من HF ولدينا توكن، مرره (يدعم النماذج الخاصة)
            try{
                if(urlStr.indexOf("huggingface.co")>=0){
                    String tok = prefs.getHfToken();
                    if(tok!=null && tok.trim().length()>0){
                        c.setRequestProperty("Authorization","Bearer "+tok.trim());
                    }
                }
            }catch(Throwable ignored){}
            int code=c.getResponseCode();
            if(code==200){
                br=new BufferedReader(new InputStreamReader(c.getInputStream(),"UTF-8"));
                StringBuilder sb=new StringBuilder(); String line;
                while((line=br.readLine())!=null){ sb.append(line).append("\n"); }
                return sb.toString();
            }
        }catch(Exception ignored){
        }finally{
            try{ if(br!=null) br.close(); }catch(Exception ignored){}
        }
        return "";
    }

    // ================= Utils for parsing / UI =================
    private List<String> unionCsv(String... csvs){
        List<String> out = new ArrayList<String>();
        if(csvs==null) return out;
        for(int i=0;i<csvs.length;i++){
            String csv = csvs[i];
            List<String> tokens = prefs.csvToList(csv);
            for(int j=0;j<tokens.size();j++){
                String t = tokens.get(j);
                if(!out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    private void selectSpinnerValue(Spinner sp,String val){
        if(sp==null||val==null)return;
        for(int i=0;i<sp.getCount();i++){
            Object o=sp.getItemAtPosition(i);
            if(o!=null && val.equals(o.toString())){ sp.setSelection(i); return;}
        }
    }

    private String getSpinnerVal(Spinner sp){
        if(sp==null || sp.getSelectedItem()==null) return "";
        return sp.getSelectedItem().toString().trim();
    }

    private String ensureModelId(String val, String fallback){
        if(val!=null && val.indexOf('/') >= 0) return val; // Model IDs تحتوي '/'
        return fallback;
    }

    private String getFileDisplayName(Uri uri){
        String name=uri.toString();
        Cursor c=null;
        try{
            c=getContentResolver().query(uri,null,null,null,null);
            if(c!=null && c.moveToFirst()){
                int idx=c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if(idx>=0) name=c.getString(idx);
            }
        }catch(Exception e){} finally{ if(c!=null)c.close();}
        return name;
    }

    private boolean isNetworkConnected(){
        try{
            ConnectivityManager cm=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni=cm.getActiveNetworkInfo();
            return ni!=null && ni.isConnected();
        }catch(Exception e){ return false; }
    }

    @Override
    protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==REQ_PICK_VOICE && res==RESULT_OK && data!=null){
            Uri uri=data.getData();
            if(uri!=null){
                tvVoicePath.setText(getFileDisplayName(uri));
                prefs.setVoiceSamplePath(uri.toString());
                prefs.setUseVoiceClone(true);
                cbUseClone.setChecked(true);
                prefs.ensureModelListsDefaults();
                prefs.ensureSelectedModelsInLists();
                try{
                    sendBroadcast(new Intent(Constants.ACTION_MODELS_UPDATED)
                                  .putExtra(Constants.EXTRA_STAGE,"voice")
                                  .putExtra(Constants.EXTRA_MESSAGE,"تم اختيار عينة الصوت"));
                }catch(Throwable ignored){}
            }
        }
    }
}
