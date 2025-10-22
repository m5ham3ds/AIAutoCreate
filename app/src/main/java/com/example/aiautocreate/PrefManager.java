package com.example.aiautocreate;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * PrefManager - Ultra-Full Literal Final Version (hardened + Dynamic Model Lists + Paths setters + Bootstrap)
 * - مدير شامل لإعدادات وتفضيلات التطبيق
 * - يحفظ كل الملفات في المسار الرئيسي: /storage/emulated/0/AIAutoCreate
 * - متوافق Android 14 + Java7 (AIDE-safe)
 *
 * ميزات:
 * - ensureModelId: يضمن أن selected_* هي Model IDs (تحتوي "/") وإلا يعود للـ default.
 * - ensureGeneralStylesDefaults: يضمن وجود قوائم عامة افتراضية للصورة/الغلاف/الفيديو/المونتاج عند الحاجة.
 * - Dynamic Model Lists (CSV) للنماذج (صور/فيديو/صوت) + ensureModelListsDefaults
 * - إدارة القوائم: add/remove لكل قائمة + ensureSelectedModelsInLists لظهور الموديلات المختارة في الـ Spinners.
 * - bootstrapDefaults: دالة واحدة لتهيئة كل الافتراضيات وإنشاء المجلدات.
 * - ضبط مسارات الموارد عبر setters اختيارية (setScriptsPath...etc).
 */
public class PrefManager {
    private static final String PREF_NAME = "aiautocreate_prefs";
    private final SharedPreferences prefs;
    private final Context appContext;

    public PrefManager(Context ctx) {
        this.appContext = (ctx != null) ? ctx.getApplicationContext() : null;
        if (appContext != null) {
            prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        } else {
            prefs = null;
        }
    }

    // ==================== KEYS ====================
    private static final String K_GEMINI_KEY      = "gemini_key";
    private static final String K_GEMINI_URL      = "gemini_url";
    private static final String K_HF_TOKEN        = "hf_token";
    private static final String K_HF_SD_URL       = "hf_sd_url";
    private static final String K_TTS_URL         = "tts_url";
    private static final String K_FFMPEG_PATH     = "ffmpeg_path";

    // Core Models (compat)
    private static final String K_SD_MODEL        = "sd_model";
    private static final String K_SD_ALT1         = "sd_alt1";
    private static final String K_SD_ALT2         = "sd_alt2";

    private static final String K_IMG2VID_MODEL   = "img2vid_model";
    private static final String K_IMG2VID_XT      = "img2vid_xt_model";
    private static final String K_WAN_IMG2VID     = "wan_img2vid_model";

    private static final String K_TTS_MODEL       = "tts_model";
    private static final String K_TTS_ALT1        = "tts_alt1";
    private static final String K_TTS_ALT2        = "tts_alt2";

    // HuggingFace Extra Models
    private static final String K_SMARTCOUNT      = "smartcount_model";
    private static final String K_REVIEWER        = "reviewer_model";
    private static final String K_ORCHESTRATOR    = "orchestrator_model";

    // NEW: Dynamic Lists (CSV)
    private static final String K_SD_LIST         = "sd_models_list_csv";
    private static final String K_IMG2VID_LIST    = "img2vid_models_list_csv";
    private static final String K_TTS_LIST        = "tts_models_list_csv";

    // Selections
    @SuppressWarnings("unused")
    private static final String K_SELECTED_SD      = "selected_sd";
    @SuppressWarnings("unused")
    private static final String K_SELECTED_IMG2VID = "selected_img2vid";
    @SuppressWarnings("unused")
    private static final String K_SELECTED_TTS     = "selected_tts";

    private static final String K_VOICE_SAMPLE     = "voice_sample_path";
    private static final String K_USE_VOICE_CLONE  = "use_voice_clone";

    // Paths override keys (optional)
    private static final String K_PATH_SCRIPTS   = "path_scripts";
    private static final String K_PATH_IMAGES    = "path_images";
    private static final String K_PATH_VIDEOS    = "path_videos";
    private static final String K_PATH_AUDIOS    = "path_audios";
    private static final String K_PATH_ERRORS    = "path_errors";
    private static final String K_PATH_VIDEO_END = "path_videoend";

    // ==================== DEFAULTS ====================
    private static final String DEFAULT_GEMINI_ENDPOINT =
    "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent";

    // SD base (compat)
    private static final String DEF_SD          = "stabilityai/stable-diffusion-xl-base-1.0";
    private static final String DEF_SD_ALT1     = "dreamlike-art/dreamlike-photoreal-2.0";
    private static final String DEF_SD_ALT2     = "prompthero/openjourney-v4";

    // Img2Vid base (compat)
    private static final String DEF_IMG2VID     = "stabilityai/stable-video-diffusion-img2vid";
    private static final String DEF_IMG2VID_XT  = "stabilityai/stable-video-diffusion-img2vid-xt";
    private static final String DEF_WAN_IMG2VID = "Wan-AI/Wan2.1-I2V-14B-720P";

    // TTS base (compat)
    private static final String DEF_TTS         = "coqui/XTTS-v2";
    private static final String DEF_TTS_ALT1    = "bosonai/higgs-audio-v2-generation-3B-base";
    private static final String DEF_TTS_ALT2    = "Plachta/VALL-E-X";

    // HuggingFace Extra
    private static final String DEF_SMARTCOUNT  = "huggingface/smartcount-base";
    private static final String DEF_REVIEWER    = "huggingface/reviewer-base";
    private static final String DEF_ORCH        = "huggingface/ffmpeg-orch-base";

    // NEW: Default CSV lists (HF models provided)
    private static final String DEFAULT_SD_LIST_CSV =
    "stabilityai/stable-diffusion-xl-base-1.0," +
    "dreamlike-art/dreamlike-photoreal-2.0," +
    "prompthero/openjourney," +
    "Lykon/dreamshaper-8," +
    "SG161222/Realistic_Vision_V6.0_B1_noVAE," +
    "xyn-ai/anything-v4.0," +
    "hakurei/waifu-diffusion-v1-4," +
    "stabilityai/stable-diffusion-xl-refiner-1.0," +
    "wavymulder/portraitplus," +
    "nitrosocke/mo-di-diffusion";

    private static final String DEFAULT_IMG2VID_LIST_CSV =
    "stabilityai/stable-video-diffusion-img2vid," +
    "stabilityai/stable-video-diffusion-img2vid-xt," +
    "Wan-AI/Wan2.1-I2V-14B-720P," +
    "Wan-AI/Wan2.2-Animate-14B," +
    "alibaba-pai/EasyAnimate," +
    "VideoCrafter/VideoCrafter2," +
    "Doubiiu/DynamiCrafter," +
    "maxin-cn/Cinemo," +
    "maxin-cn/Latte-1," +
    "Vchitect/SEINE";

    private static final String DEFAULT_TTS_LIST_CSV =
    "coqui/XTTS-v2," +
    "bosonai/higgs-audio-v2-generation-3B-base," +
    "Plachta/VALL-E-X," +
    "suno/bark," +
    "jbetker/tortoise-tts-v2," +
    "facebook/mms-tts," +
    "cshulby/YourTTS," +
    "microsoft/VibeVoice-1.5B," +
    "espnet/kan-bayashi_ljspeech_vits," +
    "mozilla/TTS";

    // ==================== Bootstrap (اختياري) ====================
    public void bootstrapDefaults() {
        ensureModelListsDefaults();
        ensureSelectedModelsInLists();
        ensureGeneralStylesDefaults();
        ensureDefaultDirsExist();
    }

    // ==================== Gemini & HuggingFace ====================
    public String getGeminiKey() { return clean(get(K_GEMINI_KEY, "")); }
    public void setGeminiKey(String v){ put(K_GEMINI_KEY,v); }

    public String getGeminiUrl() { return clean(get(K_GEMINI_URL,"")); }
    public void setGeminiUrl(String v){ put(K_GEMINI_URL,v); }

    public String getGeminiEndpointOrDefault() {
        String u=getGeminiUrl();
        return (u==null||u.trim().isEmpty())?DEFAULT_GEMINI_ENDPOINT:u;
    }

    public String getHfToken(){ return clean(get(K_HF_TOKEN,"")); }
    public void setHfToken(String v){ put(K_HF_TOKEN,v); }

    public String getHfSdUrl(){ return clean(get(K_HF_SD_URL,"")); }
    public void setHfSdUrl(String v){ put(K_HF_SD_URL,v); }

    public String getTtsUrl(){ return clean(get(K_TTS_URL,"")); }
    public void setTtsUrl(String v){ put(K_TTS_URL,v); }

    public String getFfmpegPath(){ return clean(get(K_FFMPEG_PATH,"")); }
    public void setFfmpegPath(String v){ put(K_FFMPEG_PATH,v); }

    // ==================== Stable Diffusion (compat) ====================
    public String getSdModelId(){ return fallback(get(K_SD_MODEL,DEF_SD),DEF_SD); }
    public void setSdModelId(String v){ put(K_SD_MODEL,v); }

    public String getSdAlt1(){ return fallback(get(K_SD_ALT1,DEF_SD_ALT1),DEF_SD_ALT1); }
    public void setSdAlt1(String v){ put(K_SD_ALT1,v); }

    public String getSdAlt2(){ return fallback(get(K_SD_ALT2,DEF_SD_ALT2),DEF_SD_ALT2); }
    public void setSdAlt2(String v){ put(K_SD_ALT2,v); }

    // ==================== Img2Vid (compat) ====================
    public String getImg2VidModelId(){ return fallback(get(K_IMG2VID_MODEL,DEF_IMG2VID), DEF_IMG2VID); }
    public void setImg2VidModelId(String v){ put(K_IMG2VID_MODEL,v); }

    public String getImg2VidXTModelId(){ return fallback(get(K_IMG2VID_XT,DEF_IMG2VID_XT), DEF_IMG2VID_XT); }
    public void setImg2VidXTModelId(String v){ put(K_IMG2VID_XT,v); }

    public String getWanImg2VidModelId(){ return fallback(get(K_WAN_IMG2VID,DEF_WAN_IMG2VID), DEF_WAN_IMG2VID); }
    public void setWanImg2VidModelId(String v){ put(K_WAN_IMG2VID,v); }

    // ==================== TTS (compat) ====================
    public String getTtsModelId(){ return fallback(get(K_TTS_MODEL,DEF_TTS), DEF_TTS); }
    public void setTtsModelId(String v){ put(K_TTS_MODEL,v); }

    public String getTtsAlt1(){ return fallback(get(K_TTS_ALT1,DEF_TTS_ALT1), DEF_TTS_ALT1); }
    public void setTtsAlt1(String v){ put(K_TTS_ALT1,v); }

    public String getTtsAlt2(){ return fallback(get(K_TTS_ALT2,DEF_TTS_ALT2), DEF_TTS_ALT2); }
    public void setTtsAlt2(String v){ put(K_TTS_ALT2,v); }

    // ==================== Extra HuggingFace Models ====================
    public String getSmartCount(){ return fallback(get(K_SMARTCOUNT,DEF_SMARTCOUNT),DEF_SMARTCOUNT); }
    public void setSmartCount(String v){ put(K_SMARTCOUNT,v); }

    public String getReviewer(){ return fallback(get(K_REVIEWER,DEF_REVIEWER),DEF_REVIEWER); }
    public void setReviewer(String v){ put(K_REVIEWER,v); }

    public String getOrchestrator(){ return fallback(get(K_ORCHESTRATOR,DEF_ORCH),DEF_ORCH); }
    public void setOrchestrator(String v){ put(K_ORCHESTRATOR,v); }

    // ==================== Styles & Voices ====================
    public void setModelStyles(String modelId,String csv){ put("model_styles_"+safe(modelId),safe(csv)); }
    public String getModelStyles(String modelId){ return get("model_styles_"+safe(modelId),""); }

    public void setModelVoices(String modelId,String csv){ put("model_voices_"+safe(modelId),safe(csv)); }
    public String getModelVoices(String modelId){ return get("model_voices_"+safe(modelId),""); }

    // ==================== NEW: Dynamic Lists (with defaults) ====================
    public void ensureModelListsDefaults(){
        if(clean(get(K_SD_LIST,"")).length()==0)      put(K_SD_LIST, DEFAULT_SD_LIST_CSV);
        if(clean(get(K_IMG2VID_LIST,"")).length()==0) put(K_IMG2VID_LIST, DEFAULT_IMG2VID_LIST_CSV);
        if(clean(get(K_TTS_LIST,"")).length()==0)     put(K_TTS_LIST, DEFAULT_TTS_LIST_CSV);
    }

    public void resetModelListsToDefaults(){
        put(K_SD_LIST, DEFAULT_SD_LIST_CSV);
        put(K_IMG2VID_LIST, DEFAULT_IMG2VID_LIST_CSV);
        put(K_TTS_LIST, DEFAULT_TTS_LIST_CSV);
    }

    public String getSdModelsCsv(){
        String v = get(K_SD_LIST,"");
        if(clean(v).length()==0){ put(K_SD_LIST, DEFAULT_SD_LIST_CSV); return DEFAULT_SD_LIST_CSV; }
        return v;
    }
    public void setSdModelsCsv(String csv){ put(K_SD_LIST, csv); }
    public List<String> getSdModelsList(){ return csvToList(getSdModelsCsv()); }

    public String getImg2VidModelsCsv(){
        String v = get(K_IMG2VID_LIST,"");
        if(clean(v).length()==0){ put(K_IMG2VID_LIST, DEFAULT_IMG2VID_LIST_CSV); return DEFAULT_IMG2VID_LIST_CSV; }
        return v;
    }
    public void setImg2VidModelsCsv(String csv){ put(K_IMG2VID_LIST, csv); }
    public List<String> getImg2VidModelsList(){ return csvToList(getImg2VidModelsCsv()); }

    public String getTtsModelsCsv(){
        String v = get(K_TTS_LIST,"");
        if(clean(v).length()==0){ put(K_TTS_LIST, DEFAULT_TTS_LIST_CSV); return DEFAULT_TTS_LIST_CSV; }
        return v;
    }
    public void setTtsModelsCsv(String csv){ put(K_TTS_LIST, csv); }
    public List<String> getTtsModelsList(){ return csvToList(getTtsModelsCsv()); }

    // إدارة القوائم الديناميكية (إضافة/حذف بدون تكرار)
    public boolean addModelToList(String listKey, String modelId) {
        String id = normalizeModelId(modelId);
        if (id.length()==0) return false; // يلزم owner/repo
        String csv = clean(get(listKey, ""));
        List<String> list = csvToList(csv);
        if (!list.contains(id)) {
            list.add(id);
            put(listKey, joinListToCsv(list));
            return true;
        }
        return false;
    }
    public boolean removeModelFromList(String listKey, String modelId) {
        String id = normalizeModelId(modelId);
        String csv = clean(get(listKey, ""));
        List<String> list = csvToList(csv);
        if (list.contains(id)) {
            list.remove(id);
            put(listKey, joinListToCsv(list));
            return true;
        }
        return false;
    }
    public boolean addSdModel(String modelId){ return addModelToList(K_SD_LIST, modelId); }
    public boolean addImg2VidModel(String modelId){ return addModelToList(K_IMG2VID_LIST, modelId); }
    public boolean addTtsModel(String modelId){ return addModelToList(K_TTS_LIST, modelId); }
    public boolean removeSdModel(String modelId){ return removeModelFromList(K_SD_LIST, modelId); }
    public boolean removeImg2VidModel(String modelId){ return removeModelFromList(K_IMG2VID_LIST, modelId); }
    public boolean removeTtsModel(String modelId){ return removeModelFromList(K_TTS_LIST, modelId); }

    /**
     * يضمن أن الموديلات المختارة والأساسية موجودة داخل القوائم الديناميكية
     * - استدعِها بعد ensureModelListsDefaults وعند بدء الواجهة (قبل بناء الـ Spinners).
     */
    public void ensureSelectedModelsInLists(){
        ensureModelListsDefaults();
        // SD
        String selSd = getSelectedSdModel();
        if (selSd.length()>0) addSdModel(selSd);
        addSdModel(getSdModelId());
        addSdModel(getSdAlt1());
        addSdModel(getSdAlt2());
        // Img2Vid
        String selVid = getSelectedImg2VidModel();
        if (selVid.length()>0) addImg2VidModel(selVid);
        addImg2VidModel(getImg2VidModelId());
        addImg2VidModel(getImg2VidXTModelId());
        addImg2VidModel(getWanImg2VidModelId());
        // TTS
        String selTts = getSelectedTtsModel();
        if (selTts.length()>0) addTtsModel(selTts);
        addTtsModel(getTtsModelId());
        addTtsModel(getTtsAlt1());
        addTtsModel(getTtsAlt2());
    }

    // ==================== Selections (hardened: Model IDs only) ====================
    public void saveSelection(String key, String value){
        put("sel_"+safe(key), safe(value));
    }

    public String getSelection(String key,String def){
        return clean(get("sel_"+safe(key),def));
    }

    // تأكد أن القيمة المرجعة دائماً Model ID (تحتوي "/")
    public String getSelectedSdModel(){
        String v = getSelection("selected_sd", getSdModelId());
        return ensureModelId(v, getSdModelId());
    }
    public void setSelectedSdModel(String v){ saveSelection("selected_sd",v); }

    public String getSelectedImg2VidModel(){
        String v = getSelection("selected_img2vid", getImg2VidModelId());
        return ensureModelId(v, getImg2VidModelId());
    }
    public void setSelectedImg2VidModel(String v){ saveSelection("selected_img2vid",v); }

    public String getSelectedTtsModel(){
        String v = getSelection("selected_tts", getTtsModelId());
        return ensureModelId(v, getTtsModelId());
    }
    public void setSelectedTtsModel(String v){ saveSelection("selected_tts",v); }

    // ==================== Voice Clone ====================
    public String getVoiceSamplePath(){ return clean(get(K_VOICE_SAMPLE,"")); }
    public void setVoiceSamplePath(String v){ put(K_VOICE_SAMPLE,v); }

    public boolean getUseVoiceClone(){ return getBool(K_USE_VOICE_CLONE,false); }
    public void setUseVoiceClone(boolean v){ putBool(K_USE_VOICE_CLONE,v); }

    // ==================== Profiles by Style ====================
    private String PREF_KEY_PREFIX(String style){ return "profile_"+safe(style)+"_"; }
    public void saveStyleProfile(String style,String key,String value){ put(PREF_KEY_PREFIX(style)+key,value); }
    public String getStyleProfile(String style,String key,String def){ return get(PREF_KEY_PREFIX(style)+key,def); }

    // ==================== Resource Paths ====================
    public String getScriptsPath(){ return resolvePath(K_PATH_SCRIPTS,"SCRIPTS"); }
    public String getImagesPath() { return resolvePath(K_PATH_IMAGES,"IMAGES"); }
    public String getVideosPath() { return resolvePath(K_PATH_VIDEOS,"VIDEOS"); }
    public String getAudiosPath() { return resolvePath(K_PATH_AUDIOS,"AUDIOS"); }
    public String getErrorsPath() { return resolvePath(K_PATH_ERRORS,"ERRORS"); }
    public String getVideoEndPath(){ return resolvePath(K_PATH_VIDEO_END,"VIDEOEND"); }

    // اختياري: setters لمسارات الموارد (يمكن ضبطها من الإعدادات إن رغبت)
    public void setScriptsPath(String path){ put(K_PATH_SCRIPTS, safe(path)); }
    public void setImagesPath(String path){ put(K_PATH_IMAGES, safe(path)); }
    public void setVideosPath(String path){ put(K_PATH_VIDEOS, safe(path)); }
    public void setAudiosPath(String path){ put(K_PATH_AUDIOS, safe(path)); }
    public void setErrorsPath(String path){ put(K_PATH_ERRORS, safe(path)); }
    public void setVideoEndPath(String path){ put(K_PATH_VIDEO_END, safe(path)); }

    private String resolvePath(String key,String folder){
        String p = get(key,null);
        if(p==null||p.isEmpty()){
            File d=new File(getBaseDir(),folder);
            if(!d.exists()) d.mkdirs();
            return d.getAbsolutePath();
        }
        return p;
    }

    /** 👇 المسار الأساسي: /storage/emulated/0/AIAutoCreate
     * ملاحظة: على Android 11+ (Scoped Storage) قد تحتاج SAF أو getExternalFilesDir لاستخدامه في الإصدارات الحديثة.
     */
    private File getBaseDir(){
        File base=new File("/storage/emulated/0/AIAutoCreate");
        if(!base.exists()) base.mkdirs();
        return base;
    }

    public String getBaseDirPath(){
        try { return getBaseDir().getAbsolutePath(); } catch(Exception e){ return "/storage/emulated/0/AIAutoCreate"; }
    }

    public boolean ensureDefaultDirsExist(){
        try {
            new File(getScriptsPath()).mkdirs();
            new File(getImagesPath()).mkdirs();
            new File(getVideosPath()).mkdirs();
            new File(getAudiosPath()).mkdirs();
            new File(getErrorsPath()).mkdirs();
            new File(getVideoEndPath()).mkdirs();
            return true;
        } catch(Exception e){ e.printStackTrace(); return false; }
    }

    // ==================== CSV Utils ====================
    public String joinListToCsv(List<String> list){
        if(list==null||list.isEmpty()) return "";
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<list.size();i++){
            if(i>0) sb.append(",");
            String it = list.get(i);
            if(it!=null) sb.append(it.trim());
        }
        return sb.toString();
    }

    public List<String> csvToList(String csv){
        List<String> out=new ArrayList<String>();
        if(csv==null||csv.isEmpty()) return out;
        String[] parts=csv.split(",");
        for(int i=0;i<parts.length;i++){
            String p=parts[i];
            if(p!=null){
                String t=p.trim();
                if(t.length()>0 && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    // ==================== Helpers ====================
    private String get(String k,String def){ return prefs!=null? prefs.getString(k,def):def; }
    private void put(String k,String v){ if(prefs!=null) prefs.edit().putString(k,safe(v)).apply(); }
    private boolean getBool(String k,boolean def){ return prefs!=null? prefs.getBoolean(k,def):def; }
    private void putBool(String k,boolean v){ if(prefs!=null) prefs.edit().putBoolean(k,v).apply(); }

    private String safe(String s){ return (s==null)?"":s; }
    private String clean(String s){ return (s==null||s.trim().isEmpty())?"":s.trim(); }
    private String fallback(String val,String def){ return (val==null||val.trim().isEmpty())?def:val.trim(); }

    // يعيد Model ID فقط وإلا يرجع الافتراضي
    private String ensureModelId(String val, String def){
        if(val!=null){
            String t = val.trim();
            if(t.length()>0 && t.indexOf('/')>=0) return t;
        }
        return def;
    }

    // تطبيع بسيط للـ Model ID (owner/repo) وإزالة المسافات
    private String normalizeModelId(String id){
        String s = clean(id);
        if (s.indexOf('/') < 0) return "";
        return s.replace(" ", "");
    }

    // يضمن وجود قوائم عامة افتراضية للواجهة
    public void ensureGeneralStylesDefaults(){
        // image_styles
        if(clean(getModelStyles("image_styles")).length()==0){
            setModelStyles("image_styles","واقعي,كرتوني,خيالي,Anime");
        }
        // cover_styles
        if(clean(getModelStyles("cover_styles")).length()==0){
            setModelStyles("cover_styles","غلاف بسيط,غلاف فني,غلاف ملون");
        }
        // video_styles
        if(clean(getModelStyles("video_styles")).length()==0){
            setModelStyles("video_styles","درامي,موسيقي,اكشن,وثائقي");
        }
        // montage_styles
        if(clean(getModelStyles("montage_styles")).length()==0){
            setModelStyles("montage_styles","قصص وروايات,حماسي وجذاب,احترافية وأنيق,مخصص");
        }
    }
}
