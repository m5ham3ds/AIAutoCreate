package com.example.aiautocreate;

import android.content.Context;
import android.util.Log;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

import java.net.URL;
import java.net.HttpURLConnection;
import java.net.URLEncoder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * PipelineManager - Ultra-Full Literal (Image/TTS/Video pipeline + Always-Finish + Enforced Dims + StylePacks + Robust HF + 402 Model-ID + JSON/Bytes handling)
 * - Java7 Compatible + AIDE-safe
 * - الصور: تم تعديل Accept إلى image/png + إصلاح ترميز URL للموديل (owner/repo) لتفادي 400/404
 * - الفيديو: محاولات متعددة + multipart fallback
 * - TTS: محاولات ذكية + fallback بسيط
 * - Logging موسع + Always-Finish
 */
public class PipelineManager {
    private static final String TAG="PipelineManager";
    private static final String VOICE_CLONE_OPTION = "استنساخ العينة (من الإعدادات)";

    // Negative prompt افتراضي للصور
    private static final String DEFAULT_NEGATIVE =
    "lowres, blurry, bad anatomy, deformed, watermark, text, jpeg artifacts, worst quality, low quality, noisy";

    public interface Callback{
        void onStageProgress(String stage,String msg);
        void onFinished(String out);
        void onError(String stage,String msg);
        void onCancelled();
    }

    private Context ctx;
    private Callback cb;
    private boolean cancelled=false;
    private PrefManager pref;
    private File logFile;

    // آخر مدة لغايات placeholder
    private int lastDurationMs = 0;

    // اختيار ستايل الصور لحقن باقات الستايل
    private String selectedImageStyle = null;

    // حالة نفاد الحصة (402) + معلومات الموديل الذي نفدت حصته
    private volatile boolean hfQuotaExceeded = false;
    private String hfQuotaModel = "";
    private String hfQuotaStage = "";

    public PipelineManager(Context c,Callback callback){
        ctx=c; cb=callback; pref=new PrefManager(c);
        try{
            File logDir=new File(pref.getErrorsPath());
            if(!logDir.exists()) logDir.mkdirs();
            logFile=new File(logDir,"pipeline_log.txt");
        }catch(Exception e){ logFile=null; }
    }

    public void cancel(){ cancelled=true; }
    public boolean isCancelled(){ return cancelled; }

    public void runPipeline(String prompt,
                            String imageStyle,
                            String coverStyle,
                            String voiceChoice,
                            String montageStyle,
                            int videoDurationMs,
                            String aspectRatio,
                            String videoQuality,
                            String audioFx,
                            String visualFx,
                            String transFx,
                            String smartSel,
                            String subsModel,
                            String musicBg,
                            String masterAgg,
                            String reviewer,
                            String orch){
        cancelled=false;
        hfQuotaExceeded=false;
        hfQuotaModel=""; hfQuotaStage="";
        lastDurationMs = videoDurationMs;

        // احفظ اختيار ستايل الصور لاستخدامه في حقن توكنز الباقات
        this.selectedImageStyle = imageStyle;

        try{
            // 0) مسارات أساسية
            File scriptsDir = new File(pref.getScriptsPath());
            File imagesDir  = new File(pref.getImagesPath());
            File videosDir  = new File(pref.getVideosPath());
            File audiosDir  = new File(pref.getAudiosPath());
            if(!scriptsDir.exists()) scriptsDir.mkdirs();
            if(!imagesDir.exists()) imagesDir.mkdirs();
            if(!videosDir.exists()) videosDir.mkdirs();
            if(!audiosDir.exists()) audiosDir.mkdirs();

            // حساب أبعاد الهدف من (aspect + quality)
            int[] size = computeTargetSize(aspectRatio, videoQuality);
            int targetW = size[0];
            int targetH = size[1];
            logAndCb("dims", "Target = "+targetW+"x"+targetH+" ("+aspectRatio+" | "+videoQuality+")");

            String pshort = shortText(prompt, 60);
            logAndCb("script","✅ السكربت جاهز: "+(pshort.length()>0? pshort : "no-prompt"));

            // 1) صور
            if(!hfQuotaExceeded) processImagesFromScripts(scriptsDir, imagesDir, aspectRatio, targetW, targetH);
            if(hfQuotaExceeded){
                logAndCb("pipeline","⛔ تم إيقاف باقي المراحل بسبب نفاد الحصة على HF (model="+hfQuotaModel+", stage="+hfQuotaStage+")");
            }

            // 2) TTS
            if(!hfQuotaExceeded) processTtsFromScripts(scriptsDir, audiosDir, voiceChoice);

            // 3) فيديو
            if(!hfQuotaExceeded) processImg2VidFromScripts(scriptsDir, imagesDir, videosDir, aspectRatio, videoDurationMs, targetW, targetH);

            // 4) مراحل إضافية (Logging فقط هنا)
            stage("audiofx",audioFx,null);
            stage("visualfx",visualFx,null);
            stage("transitions",transFx,null);
            stage("smart",smartSel,null);
            stage("subs",subsModel,null);
            stage("music",musicBg,null);
            stage("masteragg",masterAgg,null);
            stage("reviewer",reviewer,null);
            stage("orch",orch,null);

            if(checkCancel()) return;
            logAndCb("assemble","🔧 تجميع الفيديو النهائي…");
            assembleFinal();
        }catch(Exception e){
            safeCbError("pipeline","Exception: "+e.getMessage());
            writeToFile("ERROR pipeline: "+e.getMessage());
            Log.e(TAG,"runPipeline failed",e);
        }
    }

    private void stage(String stageName,String value,String aspect){
        if(checkCancel()) return;
        if(notEmpty(value)){
            if("image".equals(stageName)&&notEmpty(aspect)){
                try{
                    AspectRatioUtils.Dimension d=AspectRatioUtils.parseAspect(aspect);
                    logAndCb(stageName,"🖼 "+value+" | "+d.width+"x"+d.height+" ("+d.label+")");
                }catch(Exception e){
                    logAndCb(stageName,"🖼 "+value+" | aspect="+aspect);
                }
            }else{
                logAndCb(stageName,"▶ "+stageName+": "+value);
            }
            simulate();
        } else {
            logAndCb(stageName,"⏭ Skip "+stageName+" (empty/disabled)");
        }
    }

    private boolean notEmpty(String s){ return s!=null && s.trim().length()>0; }
    private boolean checkCancel(){
        if(cancelled){
            if(cb!=null) cb.onCancelled();
            writeToFile("⚠ CANCELLED");
            return true;
        }
        return false;
    }
    private void simulate(){ try{ Thread.sleep(500);}catch(Exception ignored){} }

    private void assembleFinal(){
        try{
            File vids=new File(pref.getVideosPath());
            File auds=new File(pref.getAudiosPath());
            boolean hasV = vids.exists() && vids.listFiles()!=null && vids.listFiles().length>0;
            boolean hasA = auds.exists() && auds.listFiles()!=null && auds.listFiles().length>0;

            if(!hasV) {
                logAndCb("assemble","⚠ لا توجد فيديوهات - سيتم إنشاء ملف نهائي تجريبي");
                writeToFile("assemble: no videos in "+vids.getAbsolutePath());
            }
            if(!hasA) {
                logAndCb("assemble","⚠ لا توجد أصوات - سيتم إنشاء ملف نهائي تجريبي");
                writeToFile("assemble: no audios in "+auds.getAbsolutePath());
            }

            String outPath=pref.getVideoEndPath()+"/final_"+System.currentTimeMillis()+".mp4";
            ensureParentDir(outPath);
            createPlaceholderMp4(outPath, lastDurationMs);

            logAndCb("assemble","✅ تم إنشاء المخرج: "+outPath);
            if(cb!=null) cb.onFinished(outPath);
            writeToFile("✅ Finished (placeholder): "+outPath);
        }catch(Exception e){
            safeCbError("assemble","Exception: "+e.getMessage());
            writeToFile("assemble exception: "+e.getMessage());
        }
    }

    private void ensureParentDir(String path){
        try{
            File f = new File(path);
            File parent = f.getParentFile();
            if(parent!=null && !parent.exists()) parent.mkdirs();
        }catch(Exception ignored){}
    }

    private void createPlaceholderMp4(String outPath, int durationMs){
        FileOutputStream fos = null;
        try{
            fos = new FileOutputStream(outPath);
            String header = "AI-AutoCreate Placeholder MP4\n";
            fos.write(header.getBytes("UTF-8"));

            int bytes = Math.max(1024, Math.min(256*1024, durationMs<=0? 32*1024 : durationMs/10));
            byte[] buf = new byte[1024];
            for(int i=0;i<buf.length;i++) buf[i] = (byte)(i & 0xFF);
            int written = 0;
            while(written < bytes){
                int toWrite = Math.min(1024, bytes - written);
                fos.write(buf, 0, toWrite);
                written += toWrite;
            }
            fos.flush();
        }catch(Exception e){
            writeToFile("createPlaceholderMp4 failed: "+e.getMessage());
        }finally{
            try{ if(fos!=null) fos.close(); }catch(Exception ignored){}
        }
    }

    private void logAndCb(String stage,String msg){
        if(cb!=null) cb.onStageProgress(stage,msg);
        writeToFile(stage+" => "+msg);
        Log.i(TAG,stage+" => "+msg);
    }

    private void writeToFile(String text){
        try{
            if(logFile==null) return;
            FileWriter fw=new FileWriter(logFile,true);
            PrintWriter pw=new PrintWriter(fw);
            pw.println(System.currentTimeMillis()+" | "+text);
            pw.flush();
            pw.close();
        }catch(Exception ignored){}
    }

    private void logReqPayload(String url, String payload){
        try{
            if(payload==null) payload="";
            String cut = payload;
            if(cut.length()>1000) cut = cut.substring(0,1000) + "...(truncated)";
            writeToFile("REQ to "+url+" | payload="+cut);
        }catch(Exception ignored){}
    }

    private void safeCbError(String stage,String msg){
        try{ if(cb!=null) cb.onError(stage,msg); }catch(Exception ignored){}
        Log.e(TAG,stage+" ERROR: "+msg);
    }

    private String shortText(String s,int max){
        if(s==null) return "";
        String t=s.trim();
        if(t.length()<=max) return t;
        return t.substring(0,max)+"...";
    }

    // ====== صور ======
    private void processImagesFromScripts(File scriptsDir, File imagesDir, String aspect, int targetW, int targetH){
        if(checkCancel()) return;
        if(hfQuotaExceeded){ logAndCb("image","⛔ تم تخطي الصور: نفاد الحصة (model="+hfQuotaModel+")"); return; }

        List<File> list = listNumberedFiles(scriptsDir, "MSHHD", ".txt");
        if(list.isEmpty()){
            logAndCb("image","⏭ لا توجد ملفات MSHHD في "+scriptsDir.getAbsolutePath());
            return;
        }
        String hfToken = pref.getHfToken();
        String selectedModelId = pref.getSelectedSdModel();
        String endpoint = pref.getHfSdUrl();

        boolean isRefiner = isSdxlRefinerModel(selectedModelId);
        String effectiveModelId = isRefiner ? "stabilityai/stable-diffusion-xl-base-1.0" : selectedModelId;

        for(int i=0;i<list.size();i++){
            if(checkCancel()) return;
            if(hfQuotaExceeded){ logAndCb("image","⛔ توقف الصور: نفاد الحصة"); return; }

            File f = list.get(i);
            int idx = extractIndex(f.getName(), "MSHHD");
            String promptRaw = readAll(f);

            String p1 = StylePacks.patchPromptForImage(this.selectedImageStyle, effectiveModelId, promptRaw);
            String finalPrompt = patchPromptForModel(effectiveModelId, p1);

            String outName = "MSHHD"+idx+"_MG.png";
            File outFile = new File(imagesDir, outName);

            int[] genSize = preferredGenSizeForModel(effectiveModelId, aspect);
            int genW = genSize[0], genH = genSize[1];
            ImagePreset preset = imagePresetForModel(effectiveModelId);

            logAndCb("image","🖼 "+outName+" | gen "+genW+"x"+genH+" → "+targetW+"x"+targetH+" ("+aspect+")");

            byte[] img = null;
            Exception lastEx = null;

            try{
                if(endpoint!=null && endpoint.trim().length()>0){
                    // endpoint مخصص → جرّب JSON variants مباشرة
                    try{
                        img = tryImageJsonVariants(endpoint, (isHfEndpoint(endpoint)? hfToken : null), finalPrompt, genW, genH, preset, true);
                        if(img!=null && img.length>0){
                            logAndCb("image","✅ endpoint (json-variants)");
                        }
                    }catch(Exception exEnd){
                        lastEx = exEnd;
                        if(handleIfQuotaExceeded(exEnd,"image", endpoint)) return;
                    }
                } else {
                    String[] candidates = imageModelCandidates(effectiveModelId);
                    for(int c=0;c<candidates.length;c++){
                        String mid = candidates[c];
                        String url = hfModelApiUrl(mid);
                        try{
                            img = tryImageJsonVariants(url, hfToken, finalPrompt, genW, genH, preset, true);
                            if(img!=null && img.length>0){
                                logAndCb("image","✅ model="+mid+" (json-variants)");
                                break;
                            }
                        }catch(Exception exTry){
                            lastEx = exTry;
                            if(handleIfQuotaExceeded(exTry,"image", mid)) return;
                            int code = parseHttpCodeFromMessage(exTry.getMessage());
                            if(code==404 || code==403){
                                writeToFile("image try alt "+mid+" => "+exTry.getMessage());
                                continue;
                            }
                            if(code>=500 && code<600){
                                int smallW = Math.max(512, (genW/2/8)*8);
                                int smallH = Math.max(512, (genH/2/8)*8);
                                try{
                                    img = tryImageJsonVariants(url, hfToken, finalPrompt, smallW, smallH, new ImagePreset(Math.max(1,preset.steps-4), Math.max(0.1,preset.guidance-0.5)), true);
                                    if(img!=null && img.length>0){
                                        logAndCb("image","✅ model="+mid+" (small + variants)");
                                        break;
                                    }
                                }catch(Exception exSmall){
                                    lastEx = exSmall;
                                    if(handleIfQuotaExceeded(exSmall,"image", mid)) return;
                                }
                            }
                        }
                    }
                }
            }catch(Exception e){ lastEx = e; }

            // Fallback إضافي (طريقة بسيطة: inputs + parameters فقط)
            if (img == null || img.length == 0) {
                try {
                    String paramsJson =
                        "{"
                        + "\"width\":" + genW + ","
                        + "\"height\":" + genH + ","
                        + "\"num_inference_steps\":" + preset.steps + ","
                        + "\"guidance_scale\":" + preset.guidance + ","
                        + "\"negative_prompt\":\"" + escapeJson(DEFAULT_NEGATIVE) + "\""
                        + "}";
                    img = hfImageSimpleJson(hfToken, effectiveModelId, finalPrompt, paramsJson);
                    if (img != null && img.length > 0) {
                        logAndCb("image","✅ legacy JSON route model="+effectiveModelId);
                    }
                } catch (Exception exLegacy) {
                    lastEx = exLegacy;
                    if (handleIfQuotaExceeded(exLegacy, "image", effectiveModelId)) return;
                }
            }

            if(img!=null && img.length>0){
                try{
                    byte[] toSave = resizeImageBytes(img, targetW, targetH);
                    FileOutputStream fos=new FileOutputStream(outFile);
                    fos.write(toSave); fos.close();
                    logAndCb("image","✅ تم إنشاء "+outFile.getAbsolutePath());
                }catch(Exception e){
                    logAndCb("image","⚠ فشل حفظ الصورة: "+e.getMessage());
                }
            } else {
                if(lastEx!=null) logAndCb("image","⚠ فشل "+outName+": "+lastEx.getMessage());
                else logAndCb("image","⚠ تعذر توليد الصورة لـ "+outName);
            }
            simulate();
        }
    }

    // ====== فيديو ======
    private void processImg2VidFromScripts(File scriptsDir, File imagesDir, File videosDir, String aspectRatio, int videoDurationMs, int targetW, int targetH){
        if(checkCancel()) return;
        if(hfQuotaExceeded){ logAndCb("img2vid","⛔ تم تخطي الفيديو: نفاد الحصة (model="+hfQuotaModel+")"); return; }

        List<File> list = listNumberedFiles(scriptsDir, "HAREKA", ".txt");
        if(list.isEmpty()){
            logAndCb("img2vid","⏭ لا توجد ملفات HAREKA في "+scriptsDir.getAbsolutePath());
            return;
        }
        String hfToken = pref.getHfToken();
        String modelId = pref.getSelectedImg2VidModel();

        int[] inSize = preferredVideoInputSizeForModel(modelId, aspectRatio);
        int inW = inSize[0], inH = inSize[1];

        int perClipMs = Math.max(1500, videoDurationMs / Math.max(1, list.size()));

        for(int i=0;i<list.size();i++){
            if(checkCancel()) return;
            if(hfQuotaExceeded){ logAndCb("img2vid","⛔ توقف الفيديو: نفاد الحصة"); return; }

            File f = list.get(i);
            int idx = extractIndex(f.getName(), "HAREKA");
            String motionPrompt = readAll(f);

            File baseImg = new File(imagesDir, "MSHHD"+idx+"_MG.png");
            if(!baseImg.exists()){
                logAndCb("img2vid","⚠ لم يتم العثور على الصورة الأساسية: "+("MSHHD"+idx+"_MG.png")+"، سيتم إنشاء فيديو تجريبي");
            }

            String outName = "HAREKA"+idx+"_VO.mp4";
            File outFile = new File(videosDir, outName);

            MotionParams mp = parseMotionPrompt(motionPrompt, perClipMs);
            VideoDefaults vd = videoDefaultsForModel(modelId, mp, perClipMs, inW, inH);

            logAndCb("img2vid","🎞 "+outName+" | in "+inW+"x"+inH+" | "+vd.fps+"fps, "+vd.frames+"f, steps="+vd.steps+", g="+vd.guidance+(vd.motionIntensity!=null?(", motion_intensity="+vd.motionIntensity):""));

            boolean ok=false;
            try{
                byte[] vid = null;
                if(baseImg.exists()){
                    byte[] imgBytes = readBytes(baseImg);
                    imgBytes = resizeImageBytes(imgBytes, inW, inH);
                    vid = callImg2Vid(modelId, imgBytes, motionPrompt, vd, mp, inW, inH, hfToken);
                }

                if(vid!=null && vid.length>0){
                    FileOutputStream fos=new FileOutputStream(outFile);
                    fos.write(vid); fos.close();
                    ok=true;
                    logAndCb("img2vid","✅ تم إنشاء "+outFile.getAbsolutePath());
                }
            }catch(Exception e){
                writeToFile("img2vid gen fail: "+e.getMessage());
                logAndCb("img2vid","⚠ فشل توليد الفيديو: "+e.getMessage());
            }

            if(!ok){
                createPlaceholderMp4(outFile.getAbsolutePath(), Math.max(2000, perClipMs));
                logAndCb("img2vid","⚠ فيديو تجريبي: "+outFile.getAbsolutePath());
            }
            simulate();
        }
    }

    // ====== TTS ======
    private void processTtsFromScripts(File scriptsDir, File audiosDir, String voiceChoice){
        if(checkCancel()) return;
        if(hfQuotaExceeded){ logAndCb("tts","⛔ تم تخطي TTS: نفاد الحصة (model="+hfQuotaModel+")"); return; }

        List<File> ordered = new ArrayList<File>();
        File narrator = new File(scriptsDir, "SCRIPTS_SSML.txt");
        if(narrator.exists()) ordered.add(narrator);
        List<File> chars = listNumberedFiles(scriptsDir, "SCRIPTS_SSML_V", ".txt");
        for(int i=0;i<chars.size();i++) ordered.add(chars.get(i));
        if(ordered.isEmpty()){
            logAndCb("tts","⏭ لا توجد ملفات SSML في "+scriptsDir.getAbsolutePath());
            return;
        }

        String ttsUrl  = pref.getTtsUrl();
        String hfToken = pref.getHfToken();
        String ttsModel= pref.getSelectedTtsModel();

        for(int i=0;i<ordered.size();i++){
            if(checkCancel()) return;
            if(hfQuotaExceeded){ logAndCb("tts","⛔ توقف TTS: نفاد الحصة"); return; }

            File f = ordered.get(i);
            String base = f.getName().replace(".txt","");
            String ssml = readAll(f);
            String text = stripSsml(ssml);
            String outName = base+"_AU.wav";
            File outFile = new File(audiosDir, outName);
            logAndCb("tts","🔊 تحويل "+base+" ...");

            byte[] audio=null;
            try{
                if(ttsUrl!=null && ttsUrl.trim().length()>0){
                    JSONObject jo = new JSONObject();
                    jo.put("ssml", ssml);
                    jo.put("voice", voiceChoice!=null? voiceChoice:"");

                    boolean useClone = VOICE_CLONE_OPTION.equals(voiceChoice)
                        && pref.getUseVoiceClone()
                        && pref.getVoiceSamplePath().length()>0;
                    jo.put("use_clone", useClone);

                    if(useClone){
                        String refPath = pref.getVoiceSamplePath();
                        String b64 = readFileB64(refPath);
                        jo.put("clone_b64", b64);
                        jo.put("clone_data_url", "data:audio/wav;base64,"+b64);
                        jo.put("clone_uri", refPath);
                        jo.put("sample_rate_hz", 24000);
                    }

                    try{
                        String payload = jo.toString();
                        logReqPayload(ttsUrl, payload);
                        HttpResp r = httpPostJsonResp(ttsUrl, payload, null, "audio/*,application/json", 70000, 120000);
                        audio = ensureAudioBytes(r);
                    }catch(Exception exTtsUrl){
                        if(handleIfQuotaExceeded(exTtsUrl,"tts", ttsUrl)) return;
                        throw exTtsUrl;
                    }
                } else {
                    String url = hfModelApiUrl(ttsModel);

                    boolean wantClone = VOICE_CLONE_OPTION.equals(voiceChoice)
                        && pref.getVoiceSamplePath().length()>0;

                    String dataUrl = null;
                    if(wantClone){
                        String refPath = pref.getVoiceSamplePath();
                        String b64 = readFileB64(refPath);
                        dataUrl = "data:audio/wav;base64,"+b64;
                    }

                    String group = ttsGroupForModel(ttsModel);
                    boolean got=false;

                    if(wantClone && (group.equals("xtts") || group.equals("valle") || group.equals("yourtts"))){
                        String payloadSpeaker =
                            "{"
                            + "\"inputs\":\""+escapeJson(text)+"\","
                            + "\"speaker_wav\":\""+escapeJson(dataUrl)+"\","
                            + "\"parameters\":{\"sample_rate\":24000},"
                            + "\"options\":{\"wait_for_model\":true,\"use_cache\":false}"
                            + "}";
                        try{
                            logReqPayload(url, payloadSpeaker);
                            HttpResp rA = httpPostJsonResp(url, payloadSpeaker, hfToken, "audio/*,application/json", 90000, 150000);
                            audio = ensureAudioBytes(rA);
                            got = (audio!=null && audio.length>0);
                        }catch(Exception exA){
                            if(handleIfQuotaExceeded(exA,"tts", ttsModel)) return;
                            writeToFile("hf tts speaker_wav failed: "+exA.getMessage());
                        }
                    }

                    if(!got){
                        String payloadTextOnly =
                            "{"
                            + "\"inputs\":\""+escapeJson(text)+"\","
                            + "\"options\":{\"wait_for_model\":true,\"use_cache\":false}"
                            + "}";
                        try{
                            logReqPayload(url, payloadTextOnly);
                            HttpResp r = httpPostJsonResp(url, payloadTextOnly, hfToken, "audio/*,application/json", 70000, 120000);
                            audio = ensureAudioBytes(r);
                            got = (audio!=null && audio.length>0);
                        }catch(Exception ex){
                            if(handleIfQuotaExceeded(ex,"tts", ttsModel)) return;
                            writeToFile("hf tts text-only failed: "+ex.getMessage());
                        }
                    }

                    if(wantClone && !got){
                        String payloadClone =
                            "{"
                            + "\"inputs\":{\"text\":\""+escapeJson(text)+"\",\"audio\":\""+escapeJson(dataUrl)+"\"},"
                            + "\"parameters\":{\"sample_rate\":24000},"
                            + "\"options\":{\"wait_for_model\":true,\"use_cache\":false}"
                            + "}";
                        try{
                            logReqPayload(url, payloadClone);
                            HttpResp r2 = httpPostJsonResp(url, payloadClone, hfToken, "audio/*,application/json", 90000, 150000);
                            audio = ensureAudioBytes(r2);
                        }catch(Exception ex2){
                            if(handleIfQuotaExceeded(ex2,"tts", ttsModel)) return;
                            writeToFile("hf tts clone failed: "+ex2.getMessage());
                        }
                    }
                }
            }catch(Exception e){
                writeToFile("tts fail: "+e.getMessage());
                logAndCb("tts","⚠ فشل "+base+": "+e.getMessage());
            }

            if (audio == null || audio.length == 0) {
                try{
                    String pjson = "{\"sample_rate\":24000}";
                    String url = hfModelApiUrl(ttsModel);
                    String payload = (pjson==null) ? "{\"inputs\":\""+escapeJson(text)+"\"}" : "{\"inputs\":\""+escapeJson(text)+"\",\"parameters\":"+pjson+"}";
                    logReqPayload(url, payload);
                    audio = hfTtsSimpleJson(hfToken, ttsModel, text, pjson);
                    if(audio != null && audio.length > 0){
                        logAndCb("tts","✅ HF simple JSON route");
                    }
                }catch(Exception exSimple){
                    if(handleIfQuotaExceeded(exSimple,"tts", ttsModel)) return;
                    writeToFile("hf tts simple failed: "+exSimple.getMessage());
                }
            }

            try{
                if(audio!=null && audio.length>0){
                    FileOutputStream fos=new FileOutputStream(outFile);
                    fos.write(audio); fos.close();
                    logAndCb("tts","✅ تم إنشاء "+outFile.getAbsolutePath());
                } else {
                    logAndCb("tts","⚠ تعذر توليد الصوت لـ "+base);
                }
            }catch(Exception e){
                logAndCb("tts","⚠ فشل حفظ الصوت: "+e.getMessage());
            }
            simulate();
        }
    }

    // ====== أدوات قراءة وترتيب ======
    private List<File> listNumberedFiles(File dir, final String prefix, final String ext){
        List<File> out = new ArrayList<File>();
        if(dir==null || !dir.exists()) return out;
        File[] files = dir.listFiles();
        if(files==null) return out;
        for(int i=0;i<files.length;i++){
            File f=files[i];
            String name=f.getName();
            if(name.startsWith(prefix) && name.endsWith(ext)){
                out.add(f);
            }
        }
        Collections.sort(out, new Comparator<File>(){
                public int compare(File a, File b){
                    int ia = extractIndex(a.getName(), prefix);
                    int ib = extractIndex(b.getName(), prefix);
                    return ia - ib;
                }
            });
        return out;
    }

    private int extractIndex(String name, String prefix){
        try{
            String s = name.substring(prefix.length());
            int dot = s.indexOf('.');
            if(dot>=0) s = s.substring(0,dot);
            return Integer.parseInt(s.replaceAll("[^0-9]",""));
        }catch(Exception e){ return 0; }
    }

    private String readAll(File f){
        BufferedReader br=null;
        try{
            br=new BufferedReader(new InputStreamReader(new FileInputStream(f),"UTF-8"));
            StringBuilder sb=new StringBuilder();
            String line;
            while((line=br.readLine())!=null){ sb.append(line).append("\n"); }
            return sb.toString().trim();
        }catch(Exception e){ return ""; }
        finally{ try{ if(br!=null) br.close(); }catch(Exception ignored){} }
    }

    private byte[] readBytes(File f){
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        InputStream in=null;
        try{
            in=new FileInputStream(f);
            byte[] buf=new byte[8192]; int n;
            while((n=in.read(buf))>0){ bos.write(buf,0,n); }
            return bos.toByteArray();
        }catch(Exception e){ return null; }
        finally{ try{ if(in!=null) in.close(); }catch(Exception ignored){} }
    }

    // ====== أدوات نصية وشبكية ======
    private String stripSsml(String ssml){
        if(ssml==null) return "";
        return ssml.replaceAll("<[^>]+>"," ").replace("&quot;","\"").replace("&amp;","&").trim();
    }

    private String escapeJson(String s){
        if(s==null) return "";
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            switch(c){
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    // ========== HTTP (قديمة ترجع بايت) ==========
    private byte[] httpPostJsonExpectBytes(String url, String json, String bearer, String accept, int connTimeout, int readTimeout) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(connTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent","AI-AutoCreate");
        c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
        if(accept!=null && accept.length()>0) c.setRequestProperty("Accept", accept);
        if(bearer!=null && bearer.trim().length()>0) c.setRequestProperty("Authorization", normalizeBearer(bearer));
        OutputStream os=c.getOutputStream();
        os.write(json.getBytes("UTF-8")); os.flush(); os.close();
        int code=c.getResponseCode();
        InputStream is = (code>=200 && code<300)? c.getInputStream(): c.getErrorStream();
        byte[] data = readAllBytes(is);
        if(code<200 || code>=300){
            writeToFile("HTTP "+code+" from "+url+" => "+(data!=null? new String(data,"UTF-8"):""));
            throw new Exception("HTTP "+code);
        }
        return data;
    }

    private byte[] httpPostBytesExpectBytes(String url, byte[] payload, String bearer, String contentType, String accept, int connTimeout, int readTimeout) throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(connTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent","AI-AutoCreate");
        if(contentType!=null) c.setRequestProperty("Content-Type", contentType);
        if(accept!=null) c.setRequestProperty("Accept", accept);
        if(bearer!=null && bearer.trim().length()>0) c.setRequestProperty("Authorization", normalizeBearer(bearer));
        OutputStream os=c.getOutputStream();
        os.write(payload); os.flush(); os.close();
        int code=c.getResponseCode();
        InputStream is = (code>=200 && code<300)? c.getInputStream(): c.getErrorStream();
        byte[] data = readAllBytes(is);
        if(code<200 || code>=300){
            writeToFile("HTTP "+code+" from "+url+" => "+(data!=null? new String(data,"UTF-8"):""));
            throw new Exception("HTTP "+code);
        }
        return data;
    }

    private String normalizeBearer(String tok){
        if(tok==null) return "";
        String t = tok.trim();
        if(t.length()==0) return "";
        if(t.startsWith("Bearer ")) return t;
        return "Bearer "+t;
    }

    private byte[] readAllBytes(InputStream in) throws Exception{
        if(in==null) return new byte[0];
        ByteArrayOutputStream bos=new ByteArrayOutputStream();
        byte[] buf=new byte[8192]; int n;
        while((n=in.read(buf))>0){ bos.write(buf,0,n); }
        in.close();
        return bos.toByteArray();
    }

    // ========== HTTP (جديدة: ترجع الكود + نوع المحتوى + البيانات) ==========
    private static final class HttpResp {
        int code;
        String contentType;
        byte[] data;
    }

    private HttpResp httpPostJsonResp(String url, String json, String bearer, String accept,
                                      int connTimeout, int readTimeout) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new java.net.URL(url).openConnection();
        c.setConnectTimeout(connTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent","AI-AutoCreate");
        c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
        c.setRequestProperty("x-use-cache","false");
        if(accept!=null && accept.length()>0) c.setRequestProperty("Accept", accept);
        if(bearer!=null && bearer.trim().length()>0) c.setRequestProperty("Authorization", normalizeBearer(bearer));
        OutputStream os=c.getOutputStream();
        os.write(json.getBytes("UTF-8")); os.flush(); os.close();

        HttpResp r = new HttpResp();
        r.code = c.getResponseCode();
        r.contentType = c.getContentType();
        InputStream is = (r.code>=200 && r.code<300)? c.getInputStream(): c.getErrorStream();
        r.data = readAllBytes(is);
        if(r.code<200 || r.code>=300){
            writeToFile("HTTP "+r.code+" from "+url+" => "+(r.data!=null? new String(r.data,"UTF-8"):""));
            throw new Exception("HTTP "+r.code);
        }
        return r;
    }

    private HttpResp httpPostBytesResp(String url, byte[] payload, String bearer, String contentType,
                                       String accept, int connTimeout, int readTimeout) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new java.net.URL(url).openConnection();
        c.setConnectTimeout(connTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent","AI-AutoCreate");
        c.setRequestProperty("x-use-cache","false");
        if(contentType!=null) c.setRequestProperty("Content-Type", contentType);
        if(accept!=null) c.setRequestProperty("Accept", accept);
        if(bearer!=null && bearer.trim().length()>0) c.setRequestProperty("Authorization", normalizeBearer(bearer));
        OutputStream os=c.getOutputStream();
        os.write(payload); os.flush(); os.close();

        HttpResp r = new HttpResp();
        r.code = c.getResponseCode();
        r.contentType = c.getContentType();
        InputStream is = (r.code>=200 && r.code<300)? c.getInputStream(): c.getErrorStream();
        r.data = readAllBytes(is);
        if(r.code<200 || r.code>=300){
            writeToFile("HTTP "+r.code+" from "+url+" => "+(r.data!=null? new String(r.data,"UTF-8"):""));
            throw new Exception("HTTP "+r.code);
        }
        return r;
    }

    // ====== الطرق المضافة ======

    // إصلاح: بناء رابط HF مع ترميز owner و repo بدون ترميز '/'
    private String hfModelApiUrl(String modelId){
        if (modelId == null) return "";
        if (modelId.startsWith("http://") || modelId.startsWith("https://")) return modelId;
        try {
            String[] parts = modelId.split("/");
            if (parts != null && parts.length == 2) {
                String owner = URLEncoder.encode(parts[0], "UTF-8");
                String repo  = URLEncoder.encode(parts[1], "UTF-8");
                return "https://api-inference.huggingface.co/models/" + owner + "/" + repo;
            }
            return "https://api-inference.huggingface.co/models/" + modelId.replace(" ", "%20");
        } catch (Exception e) {
            return "https://api-inference.huggingface.co/models/" + modelId;
        }
    }

    // تجربة أشكال JSON متعددة لتفادي 400 (مع Accept=image/png)
    private byte[] tryImageJsonVariants(String url, String hfToken, String prompt, int w, int h, ImagePreset preset, boolean withOptions) throws Exception {
        String opts = withOptions ? ",\"options\":{\"wait_for_model\":true,\"use_cache\":false}" : "";
        String params = "\"parameters\":{\"width\":"+w+",\"height\":"+h+",\"num_inference_steps\":"+preset.steps+",\"guidance_scale\":"+preset.guidance+",\"negative_prompt\":\""+escapeJson(DEFAULT_NEGATIVE)+"\"}";

        // A) inputs كسلسلة + parameters + options
        String pA = "{\"inputs\":\""+escapeJson(prompt)+"\","+params+opts+"}";
        try {
            logReqPayload(url, pA);
            HttpResp r = httpPostJsonResp(url, pA, hfToken, "image/png", 90000, 150000);
            byte[] img = ensureImageBytes(r);
            if (img!=null && img.length>0) return img;
        } catch (Exception eA) {
            int code = parseHttpCodeFromMessage(eA.getMessage());
            if (code != 400) throw eA;
        }

        // B) inputs كسلسلة + parameters (بدون options)
        String pB = "{\"inputs\":\""+escapeJson(prompt)+"\","+params+"}";
        try {
            logReqPayload(url, pB);
            HttpResp r = httpPostJsonResp(url, pB, hfToken, "image/png", 90000, 150000);
            byte[] img = ensureImageBytes(r);
            if (img!=null && img.length>0) return img;
        } catch (Exception eB) {
            int code = parseHttpCodeFromMessage(eB.getMessage());
            if (code != 400) throw eB;
        }

        // C) inputs ككائن {prompt:""} + parameters
        String pC = "{\"inputs\":{\"prompt\":\""+escapeJson(prompt)+"\"},"+params+"}";
        try {
            logReqPayload(url, pC);
            HttpResp r = httpPostJsonResp(url, pC, hfToken, "image/png", 90000, 150000);
            byte[] img = ensureImageBytes(r);
            if (img!=null && img.length>0) return img;
        } catch (Exception eC) {
            int code = parseHttpCodeFromMessage(eC.getMessage());
            if (code != 400) throw eC;
        }

        // D) (اختياري) top-level بدون inputs — غالبًا سيرفضه SDXL، نحتفظ به فقط كـ fallback
        String pD = "{\"prompt\":\""+escapeJson(prompt)+"\",\"width\":"+w+",\"height\":"+h+",\"num_inference_steps\":"+preset.steps+",\"guidance_scale\":"+preset.guidance+",\"negative_prompt\":\""+escapeJson(DEFAULT_NEGATIVE)+"\"}";
        try {
            logReqPayload(url, pD);
            HttpResp r = httpPostJsonResp(url, pD, hfToken, "image/png", 90000, 150000);
            byte[] img = ensureImageBytes(r);
            if (img!=null && img.length>0) return img;
        } catch (Exception eD) {
            int code = parseHttpCodeFromMessage(eD.getMessage());
            if (code != 400) throw eD;
        }

        // E) inputs فقط
        String pE = "{\"inputs\":\""+escapeJson(prompt)+"\"}";
        try {
            logReqPayload(url, pE);
            HttpResp r = httpPostJsonResp(url, pE, hfToken, "image/png", 90000, 150000);
            byte[] img = ensureImageBytes(r);
            if (img!=null && img.length>0) return img;
        } catch (Exception eE) {
            int code = parseHttpCodeFromMessage(eE.getMessage());
            if (code != 400) throw eE;
        }

        return null;
    }

    // multipart (inputs + parameters + fileBytes)
    private HttpResp httpPostMultipartResp(String url, String hfToken, String prompt, String parametersJson,
                                           byte[] fileBytes, String fileName, String fileContentType,
                                           int connTimeout, int readTimeout) throws Exception {
        String boundary = "----HFBoundary" + System.currentTimeMillis();
        HttpURLConnection c = (HttpURLConnection)new java.net.URL(url).openConnection();
        c.setConnectTimeout(connTimeout);
        c.setReadTimeout(readTimeout);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("User-Agent", "AI-AutoCreate");
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        if(hfToken!=null && hfToken.trim().length()>0) c.setRequestProperty("Authorization", normalizeBearer(hfToken));

        OutputStream out = c.getOutputStream();
        PrintWriter w = new PrintWriter(new OutputStreamWriter(out,"UTF-8"), true);

        // inputs
        w.append("--").append(boundary).append("\r\n");
        w.append("Content-Disposition: form-data; name=\"inputs\"\r\n\r\n");
        w.append(prompt==null?"":prompt).append("\r\n");

        // parameters
        String pj = (parametersJson==null || parametersJson.trim().length()==0) ? "{}" : parametersJson;
        w.append("--").append(boundary).append("\r\n");
        w.append("Content-Disposition: form-data; name=\"parameters\"\r\n\r\n");
        w.append(pj).append("\r\n");

        // file
        if(fileBytes!=null && fileBytes.length>0){
            String fname = (fileName!=null && fileName.length()>0)? fileName : "image.png";
            String ctype = (fileContentType!=null && fileContentType.length()>0)? fileContentType : "application/octet-stream";
            w.append("--").append(boundary).append("\r\n");
            w.append("Content-Disposition: form-data; name=\"file\"; filename=\"").append(fname).append("\"\r\n");
            w.append("Content-Type: ").append(ctype).append("\r\n\r\n");
            w.flush();
            out.write(fileBytes);
            out.flush();
            w.append("\r\n");
        }

        w.append("--").append(boundary).append("--").append("\r\n");
        w.flush(); w.close(); out.close();

        HttpResp r = new HttpResp();
        r.code = c.getResponseCode();
        r.contentType = c.getContentType();
        InputStream is = (r.code>=200 && r.code<300)? c.getInputStream(): c.getErrorStream();
        r.data = readAllBytes(is);
        if(r.code<200 || r.code>=300){
            writeToFile("HTTP "+r.code+" multipart => "+(r.data!=null? new String(r.data,"UTF-8"):""));
            throw new Exception("HTTP "+r.code);
        }
        return r;
    }

    // فالباك صورة: JSON inputs + parameters (Accept=image/png)
    private byte[] hfImageSimpleJson(String hfToken, String modelId, String prompt, String paramsJson) throws Exception {
        String url = hfModelApiUrl(modelId);
        String payload = (paramsJson==null || paramsJson.trim().length()==0)
            ? "{\"inputs\":\""+escapeJson(prompt)+"\"}"
            : "{\"inputs\":\""+escapeJson(prompt)+"\",\"parameters\":"+paramsJson+"}";
        logReqPayload(url, payload);
        HttpResp r = httpPostJsonResp(url, payload, hfToken, "image/png", 90000, 150000);
        return ensureImageBytes(r);
    }

    // فالباك TTS: JSON inputs (+ parameters)
    private byte[] hfTtsSimpleJson(String hfToken, String modelId, String text, String paramsJson) throws Exception {
        String url = hfModelApiUrl(modelId);
        String payload = (paramsJson==null || paramsJson.trim().length()==0)
            ? "{\"inputs\":\""+escapeJson(text)+"\"}"
            : "{\"inputs\":\""+escapeJson(text)+"\",\"parameters\":"+paramsJson+"}";
        logReqPayload(url, payload);
        HttpResp r = httpPostJsonResp(url, payload, hfToken, "audio/*,application/json", 90000, 150000);
        return ensureAudioBytes(r);
    }

    // multipart للفيديو
    private byte[] callImg2VidMultipart(String modelId, byte[] imgBytes, String motionPrompt,
                                        VideoDefaults vd, MotionParams mp, int inW, int inH, String hfToken) throws Exception {
        String url = hfModelApiUrl(modelId);
        StringBuilder sb = new StringBuilder();
        sb.append("{")
            .append("\"num_frames\":").append(vd.frames).append(",\"frames\":").append(vd.frames).append(",")
            .append("\"fps\":").append(vd.fps).append(",")
            .append("\"width\":").append(inW).append(",\"height\":").append(inH).append(",")
            .append("\"steps\":").append(vd.steps).append(",")
            .append("\"guidance_scale\":").append(vd.guidance);
        if (vd.includeSvdMicro) {
            sb.append(",\"motion_bucket_id\":").append(mp.motionBucketId)
                .append(",\"noise_aug_strength\":").append(mp.noiseAugStrength);
        }
        if (vd.motionIntensity!=null) sb.append(",\"motion_intensity\":").append(vd.motionIntensity.floatValue());
        if (vd.extraKV!=null && vd.extraKV.length()>0) sb.append(",").append(vd.extraKV);
        sb.append("}");
        String paramsJson = sb.toString();

        HttpResp r = httpPostMultipartResp(url, hfToken, motionPrompt, paramsJson, imgBytes, "frame.png", "image/png",
                                           150000, 300000);
        return ensureVideoBytes(r);
    }

    private boolean isJsonContentType(String ct){
        if(ct==null) return false;
        String s=ct.toLowerCase();
        return s.indexOf("application/json")>=0 || s.indexOf("text/json")>=0 || s.indexOf("+json")>=0;
    }
    private boolean looksLikeJson(byte[] data){
        if(data==null || data.length<1) return false;
        char c1=(char)data[0];
        return c1=='{' || c1=='[';
    }
    private String strUtf8(byte[] b){
        try{ return (b==null)?"":new String(b,"UTF-8"); }catch(Exception e){ return ""; }
    }
    private byte[] decodeB64(String b64){
        try{ return android.util.Base64.decode(b64, android.util.Base64.DEFAULT); }catch(Throwable t){ return null; }
    }
    private int indexOfAny(String s, String[] needles){
        if(s==null||needles==null) return -1;
        for(int i=0;i<needles.length;i++){
            int p = s.indexOf(needles[i]);
            if(p>=0) return p;
        }
        return -1;
    }

    private String extractBase64FromJson(String json){
        if(json==null) return null;
        int i = indexOfAny(json, new String[]{"data:image", "data:video", "data:audio"});
        if(i>=0){
            int comma = json.indexOf(',', i);
            if(comma>0){
                int end = json.indexOf('"', comma+1);
                if(end<0) end = json.indexOf('}', comma+1);
                if(end<0) end = json.length();
                String b64 = json.substring(comma+1, end).trim();
                if(b64.length()>16) return b64;
            }
        }
        i = json.indexOf("\"b64_json\"");
        if(i>=0){
            int colon = json.indexOf(':', i);
            int q1 = json.indexOf('"', colon+1);
            int q2 = (q1>0)? json.indexOf('"', q1+1) : -1;
            if(q1>0 && q2>q1) return json.substring(q1+1,q2);
        }
        String[] keys = new String[]{"\"image\"", "\"audio\"", "\"video\""};
        for(int k=0;k<keys.length;k++){
            int j=json.indexOf(keys[k]);
            if(j>=0){
                int colon=json.indexOf(':', j);
                int q1=json.indexOf('"', colon+1);
                int q2=(q1>0)? json.indexOf('"', q1+1) : -1;
                if(q1>0 && q2>q1){
                    String v=json.substring(q1+1,q2);
                    if(v.startsWith("data:") && v.indexOf("base64,")>0){
                        return v.substring(v.indexOf("base64,")+7);
                    }
                }
            }
        }
        return null;
    }

    private byte[] ensureImageBytes(HttpResp r){
        if(r==null || r.data==null || r.data.length==0) return null;
        if(r.contentType!=null && r.contentType.toLowerCase().startsWith("image/")) return r.data;
        if(isJsonContentType(r.contentType) || looksLikeJson(r.data)){
            String b64 = extractBase64FromJson(strUtf8(r.data));
            return (b64!=null)? decodeB64(b64) : null;
        }
        return r.data;
    }
    private byte[] ensureVideoBytes(HttpResp r){
        if(r==null || r.data==null || r.data.length==0) return null;
        String ct = (r.contentType==null)?"":r.contentType.toLowerCase();
        if(ct.startsWith("video/") || ct.indexOf("mp4")>=0 || ct.indexOf("octet-stream")>=0 || ct.indexOf("application/zip")>=0){
            return r.data;
        }
        if(isJsonContentType(ct) || looksLikeJson(r.data)){
            String b64 = extractBase64FromJson(strUtf8(r.data));
            return (b64!=null)? decodeB64(b64) : null;
        }
        return r.data;
    }
    private byte[] ensureAudioBytes(HttpResp r){
        if(r==null || r.data==null || r.data.length==0) return null;
        String ct = (r.contentType==null)?"":r.contentType.toLowerCase();
        if(ct.startsWith("audio/") || ct.indexOf("wav")>=0 || ct.indexOf("flac")>=0 || ct.indexOf("mpeg")>=0) return r.data;
        if(isJsonContentType(ct) || looksLikeJson(r.data)){
            String b64 = extractBase64FromJson(strUtf8(r.data));
            return (b64!=null)? decodeB64(b64) : null;
        }
        return r.data;
    }

    // ====== حساب الأبعاد ======
    private int[] computeTargetSize(String aspect, String quality){
        String a = (aspect==null||aspect.trim().length()==0) ? "16:9" : aspect.trim();
        String q = (quality==null||quality.trim().length()==0) ? "1080p" : quality.trim().toLowerCase();

        int w=1920, h=1080;

        if ("16:9".equals(a)) {
            if ("480p".equals(q))      { w=848;  h=480;  }
            else if ("720p".equals(q)) { w=1280; h=720;  }
            else if ("1080p".equals(q)){ w=1920; h=1080; }
            else if ("2k".equals(q))   { w=2560; h=1440; }
            else if ("4k".equals(q))   { w=3840; h=2160; }
        } else if ("9:16".equals(a)) {
            if ("480p".equals(q))      { w=480;  h=848;  }
            else if ("720p".equals(q)) { w=720;  h=1280; }
            else if ("1080p".equals(q)){ w=1080; h=1920; }
            else if ("2k".equals(q))   { w=1440; h=2560; }
            else if ("4k".equals(q))   { w=2160; h=3840; }
        } else if ("1:1".equals(a)) {
            if ("480p".equals(q))      { w=480;  h=480;  }
            else if ("720p".equals(q)) { w=720;  h=720;  }
            else if ("1080p".equals(q)){ w=1080; h=1080; }
            else if ("2k".equals(q))   { w=1440; h=1440; }
            else if ("4k".equals(q))   { w=2160; h=2160; }
        } else {
            try{
                String[] parts = a.split(":");
                int aw = Integer.parseInt(parts[0].trim());
                int ah = Integer.parseInt(parts[1].trim());
                int base = "480p".equals(q)?480 : "720p".equals(q)?720 : "2k".equals(q)?1440 : "4k".equals(q)?2160 : 1080;
                h = base;
                w = (int)Math.round((double)base * aw / (double)ah);
            }catch(Exception ignored){}
        }
        w = Math.max(8, (w/8)*8);
        h = Math.max(8, (h/8)*8);
        return new int[]{w,h};
    }

    // ====== تحجيم الصورة ======
    private byte[] resizeImageBytes(byte[] data, int targetW, int targetH){
        try{
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
            if(bmp==null) return data;
            if(bmp.getWidth()==targetW && bmp.getHeight()==targetH) return data;
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, targetW, targetH, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.PNG, 100, bos);
            byte[] out = bos.toByteArray();
            try{ bos.close(); }catch(Exception ignored){}
            try{ scaled.recycle(); }catch(Exception ignored){}
            try{ bmp.recycle(); }catch(Exception ignored){}
            return out;
        }catch(Exception e){
            writeToFile("resizeImageBytes fail: "+e.getMessage());
            return data;
        }
    }

    // ====== مرشحات + مرشحين ======
    private String[] imageModelCandidates(String modelId){
        if(modelId==null) return new String[]{"stabilityai/stable-diffusion-xl-base-1.0"};
        String m = modelId.toLowerCase();

        if(m.indexOf("openjourney")>=0){
            return new String[]{
                "prompthero/openjourney-v4",
                "prompthero/openjourney",
                "runwayml/stable-diffusion-v1-5"
            };
        }
        if(m.indexOf("dreamlike-photoreal")>=0){
            return new String[]{
                "dreamlike-art/dreamlike-photoreal-2.0",
                "runwayml/stable-diffusion-v1-5"
            };
        }
        if(m.indexOf("dreamshaper")>=0){
            return new String[]{
                "Lykon/dreamshaper-8",
                "runwayml/stable-diffusion-v1-5"
            };
        }
        if(m.indexOf("realistic_vision")>=0 || m.indexOf("realistic-vision")>=0){
            return new String[]{
                "SG161222/Realistic_Vision_V6.0_B1_noVAE",
                "runwayml/stable-diffusion-v1-5"
            };
        }
        if(m.indexOf("anything")>=0){
            return new String[]{
                "xyn-ai/anything-v4.0",
                "hakurei/waifu-diffusion-v1-4",
                "runwayml/stable-diffusion-v1-5"
            };
        }
        if(m.indexOf("waifu-diffusion")>=0){
            return new String[]{
                "hakurei/waifu-diffusion-v1-4",
                "xyn-ai/anything-v4.0",
                "runwayml/stable-diffusion-v1-5"
            };
        }
        if(m.indexOf("portraitplus")>=0){
            return new String[]{
                "wavymulder/portraitplus",
                "stabilityai/stable-diffusion-xl-base-1.0"
            };
        }
        if(m.indexOf("mo-di")>=0 || m.indexOf("mo_di")>=0 || m.indexOf("modi")>=0){
            return new String[]{
                "nitrosocke/mo-di-diffusion",
                "runwayml/stable-diffusion-v1-5"
            };
        }
        if(m.indexOf("stable-diffusion-xl")>=0 || m.indexOf("sdxl")>=0){
            return new String[]{
                "stabilityai/stable-diffusion-xl-base-1.0"
            };
        }
        return new String[]{ modelId, "runwayml/stable-diffusion-v1-5" };
    }

    private int[] preferredGenSizeForModel(String modelId, String aspect){
        String m = (modelId==null)?"":modelId.toLowerCase();
        int longSide = 768;

        if(m.indexOf("stable-diffusion-xl")>=0 || m.indexOf("sdxl")>=0) longSide = 1024;
        else if(m.indexOf("dreamlike-photoreal")>=0) longSide = 768;
        else if(m.indexOf("openjourney")>=0) longSide = 768;
        else if(m.indexOf("dreamshaper")>=0) longSide = 768;
        else if(m.indexOf("realistic_vision")>=0 || m.indexOf("realistic-vision")>=0) longSide = 896;
        else if(m.indexOf("any")>=0 || m.indexOf("waifu")>=0) longSide = 768;
        else if(m.indexOf("portraitplus")>=0) longSide = 1024;
        else if(m.indexOf("mo-di")>=0 || m.indexOf("mo_di")>=0 || m.indexOf("modi")>=0) longSide = 768;

        int w = 1, h = 1;
        try{
            String a = (aspect==null||aspect.trim().length()==0) ? "1:1" : aspect.trim();
            String[] parts = a.split(":");
            int aw = Integer.parseInt(parts[0].trim());
            int ah = Integer.parseInt(parts[1].trim());
            boolean vertical = aw < ah;
            if(vertical){ h = longSide; w = (int)Math.round((double)longSide*aw/ah); }
            else { w = longSide; h = (int)Math.round((double)longSide*ah/aw); }
        }catch(Exception e){ w = longSide; h = longSide; }
        w = Math.max(8,(w/8)*8); h = Math.max(8,(h/8)*8);
        return new int[]{w,h};
    }

    private int[] preferredVideoInputSizeForModel(String modelId, String aspect){
        String m = (modelId==null)?"":modelId.toLowerCase();
        boolean wan = (m.indexOf("wan")>=0);
        boolean xt  = (m.indexOf("img2vid-xt")>=0);

        int longSide = xt? 1024 : 768;
        if(wan) longSide = 1280;

        int aw=16, ah=9;
        try{
            String a = (aspect==null||aspect.trim().length()==0)? "16:9":aspect.trim();
            String[] parts = a.split(":");
            aw = Integer.parseInt(parts[0].trim());
            ah = Integer.parseInt(parts[1].trim());
        }catch(Exception ignored){}

        boolean vertical = aw < ah;
        int w, h;
        if(wan){
            if(vertical){ w = 720; h = 1280; }
            else if(aw==ah){ w=h=720; }
            else { w=1280; h=720; }
        } else {
            if(vertical){ h = longSide; w = (int)Math.round((double)longSide*aw/ah); }
            else { w = longSide; h = (int)Math.round((double)longSide*ah/aw); }
        }
        w = Math.max(8,(w/8)*8);
        h = Math.max(8,(h/8)*8);
        return new int[]{w,h};
    }

    private String patchPromptForModel(String modelId, String prompt){
        if(prompt==null) prompt="";
        String m = (modelId==null)?"":modelId.toLowerCase();
        if(m.indexOf("openjourney")>=0){
            if(prompt.toLowerCase().indexOf("mdjrny")<0){
                prompt = "mdjrny-v4 style, " + prompt;
            }
        }
        return prompt;
    }

    private int parseHttpCodeFromMessage(String msg){
        try{
            if(msg==null) return -1;
            int idx = msg.indexOf("HTTP ");
            if(idx>=0){
                String s = msg.substring(idx+5).trim();
                int sp = s.indexOf(' ');
                String codeStr = (sp>0)? s.substring(0,sp) : s;
                return Integer.parseInt(codeStr.trim());
            }
        }catch(Exception ignored){}
        return -1;
    }

    private boolean handleIfQuotaExceeded(Exception ex, String stage, String modelId){
        int code = parseHttpCodeFromMessage(ex!=null? ex.getMessage(): null);
        if(code == 402){
            hfQuotaExceeded = true;
            hfQuotaModel = (modelId!=null? modelId : "");
            hfQuotaStage = (stage!=null? stage : "");
            String msg = "⛔ نفدت حصتك على Hugging Face للموديل: "+hfQuotaModel+" (المرحلة: "+hfQuotaStage+"). حدّث حسابك أو استخدم Endpoint مخصص.";
            logAndCb(stage, msg);
            writeToFile(stage+" => HTTP 402 quota exceeded | model="+hfQuotaModel);
            return true;
        }
        return false;
    }

    private static class MotionParams {
        int fps;
        int numFrames;
        int motionBucketId;
        float noiseAugStrength;
    }

    private MotionParams parseMotionPrompt(String txt, int clipMs){
        MotionParams mp = new MotionParams();
        String s = (txt==null)? "" : txt.toLowerCase();

        mp.fps = 12;
        mp.motionBucketId = 120;
        mp.noiseAugStrength = 0.08f;

        if(s.indexOf("subtle")>=0 || s.indexOf("gentle")>=0 || s.indexOf("slow")>=0) {
            mp.motionBucketId = 80; mp.fps = 10; mp.noiseAugStrength = 0.06f;
        }
        if(s.indexOf("fast")>=0 || s.indexOf("quick")>=0 || s.indexOf("dynamic")>=0){
            mp.motionBucketId = 160; mp.fps = 14; mp.noiseAugStrength = 0.10f;
        }
        if(s.indexOf("handheld")>=0 || s.indexOf("shake")>=0){
            mp.motionBucketId = 180;
        }

        if(s.indexOf("dolly in")>=0 || s.indexOf("zoom in")>=0){ mp.motionBucketId += 10; }
        if(s.indexOf("dolly out")>=0 || s.indexOf("zoom out")>=0){ mp.motionBucketId += 5; }
        if(s.indexOf("pan left")>=0 || s.indexOf("pan right")>=0){ mp.motionBucketId += 8; }
        if(s.indexOf("tilt up")>=0 || s.indexOf("tilt down")>=0){ mp.motionBucketId += 6; }
        if(s.indexOf("parallax")>=0){ mp.motionBucketId += 12; }
        if(s.indexOf("time-lapse")>=0){ mp.fps = 8; }

        int frames = (int)Math.max(8, Math.round((mp.fps * (clipMs/1000.0))));
        mp.numFrames = frames;

        if(mp.motionBucketId < 1) mp.motionBucketId = 1;
        if(mp.motionBucketId > 240) mp.motionBucketId = 240;
        if(mp.noiseAugStrength < 0f) mp.noiseAugStrength = 0f;
        if(mp.noiseAugStrength > 0.2f) mp.noiseAugStrength = 0.2f;

        return mp;
    }

    private String b64(byte[] data){
        try{
            return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        }catch(Throwable t){ return ""; }
    }

    private String readFileB64(String path){
        try{
            if(path==null || path.trim().length()==0) return "";
            File f = new File(path.trim());
            byte[] b = readBytes(f);
            return (b!=null && b.length>0)? b64(b):"";
        }catch(Exception e){ return ""; }
    }

    // ينصح بوضع هذا الكلاس مرة واحدة فقط داخل PipelineManager
    private static class ImagePreset {
        int steps;
        double guidance;
        ImagePreset(int s, double g){
            this.steps = s;
            this.guidance = g;
        }
    }

    // يحدد steps/guidance حسب الموديل
    private ImagePreset imagePresetForModel(String modelId){
        String m = (modelId == null) ? "" : modelId.trim().toLowerCase();

        if (m.indexOf("stable-diffusion-xl-refiner") >= 0 ||
            m.indexOf("sdxl-refiner") >= 0 ||
            m.indexOf("refiner") >= 0) {
            return new ImagePreset(20, 6.0);
        }

        if (m.indexOf("stable-diffusion-xl") >= 0 || m.indexOf("sdxl") >= 0) {
            return new ImagePreset(28, 8.0);
        }

        if (m.indexOf("dreamlike-photoreal") >= 0) {
            return new ImagePreset(30, 7.0);
        }

        if (m.indexOf("openjourney") >= 0) {
            return new ImagePreset(28, 7.5);
        }

        if (m.indexOf("dreamshaper") >= 0) {
            return new ImagePreset(30, 8.0);
        }

        if (m.indexOf("realistic_vision") >= 0 || m.indexOf("realistic-vision") >= 0) {
            return new ImagePreset(30, 7.5);
        }

        if (m.indexOf("anything") >= 0) {
            return new ImagePreset(28, 7.0);
        }
        if (m.indexOf("waifu-diffusion") >= 0 || m.indexOf("waifu") >= 0) {
            return new ImagePreset(25, 6.5);
        }

        if (m.indexOf("portraitplus") >= 0) {
            return new ImagePreset(30, 8.0);
        }

        if (m.indexOf("mo-di") >= 0 || m.indexOf("mo_di") >= 0 || m.indexOf("modi") >= 0) {
            return new ImagePreset(28, 7.0);
        }

        return new ImagePreset(28, 7.5);
    }

    private static class VideoDefaults {
        int fps;
        int frames;
        int steps;
        double guidance;
        boolean includeSvdMicro;
        Float motionIntensity;
        String extraKV;
    }

    private VideoDefaults videoDefaultsForModel(String modelId, MotionParams mp, int perClipMs, int inW, int inH){
        VideoDefaults vd = new VideoDefaults();
        String m = (modelId==null)?"":modelId.toLowerCase();

        vd.fps = mp.fps;
        vd.frames = mp.numFrames;
        vd.steps = 24;
        vd.guidance = 6.0;
        vd.includeSvdMicro = false;
        vd.motionIntensity = null;
        vd.extraKV = null;

        if(m.indexOf("img2vid-xt")>=0 || m.indexOf("img2vid_xt")>=0){
            vd.fps = 6;
            vd.frames = Math.max(12, Math.min(36, (int)Math.round((perClipMs/1000.0)*vd.fps)));
            vd.steps = 28;
            vd.guidance = 6.5;
            vd.includeSvdMicro = true;
            return vd;
        }
        if(m.indexOf("stable-video-diffusion")>=0 || m.indexOf("img2vid")>=0){
            vd.fps = Math.max(6, Math.min(12, mp.fps));
            vd.frames = Math.max(16, Math.min(48, (int)Math.round((perClipMs/1000.0)*vd.fps)));
            vd.steps = 30;
            vd.guidance = 6.0;
            vd.includeSvdMicro = true;
            return vd;
        }
        if(m.indexOf("wan2.2")>=0 || m.indexOf("wan2.1")>=0 || m.indexOf("wan-ai")>=0 || m.indexOf("wan2")>=0 || m.indexOf("wan")>=0){
            vd.fps = 24;
            vd.frames = Math.max(24, Math.min(120, (int)Math.round((perClipMs/1000.0)*vd.fps)));
            vd.steps = 25;
            vd.guidance = 6.0;
            vd.includeSvdMicro = false;
            vd.extraKV = "\"size\":\""+inW+"x"+inH+"\"";
            return vd;
        }
        if(m.indexOf("easyanimate")>=0){
            vd.fps = 8;
            vd.frames = Math.max(8, Math.min(49, (int)Math.round((perClipMs/1000.0)*vd.fps)));
            vd.steps = 20;
            vd.guidance = 5.0;
            return vd;
        }
        if(m.indexOf("videocrafter2")>=0){
            vd.fps = 12;
            vd.frames = Math.max(16, Math.min(48, (int)Math.round((perClipMs/1000.0)*vd.fps)));
            vd.steps = 25;
            vd.guidance = 6.0;
            return vd;
        }
        if(m.indexOf("dynamicrafter")>=0){
            vd.fps = 10;
            vd.frames = Math.max(16, Math.min(64, (int)Math.round((perClipMs/1000.0)*vd.fps)));
            vd.steps = 20;
            vd.guidance = 5.5;
            return vd;
        }
        if(m.indexOf("cinemo")>=0){
            vd.fps = 10;
            vd.frames = Math.max(16, Math.min(40, (int)Math.round((perClipMs/1000.0)*vd.fps)));
            vd.steps = 22;
            vd.guidance = 5.5;
            vd.motionIntensity = new Float(0.6f);
            return vd;
        }
        if(m.indexOf("latte-1")>=0 || m.indexOf("latte")>=0){
            vd.fps = 12;
            vd.frames = Math.max(12, Math.min(36, (int)Math.round((perClipMs/1000.0)*vd.fps)));
            vd.steps = 25;
            vd.guidance = 6.0;
            return vd;
        }
        if(m.indexOf("seine")>=0){
            vd.fps = 24;
            vd.frames = Math.max(24, Math.min(144, (int)Math.round((perClipMs/1000.0)*vd.fps)));
            vd.steps = 24;
            vd.guidance = 6.0;
            return vd;
        }
        vd.fps = mp.fps;
        vd.frames = mp.numFrames;
        vd.steps = 24;
        vd.guidance = 6.0;
        return vd;
    }

    private boolean isSdxlRefinerModel(String modelId){
        if(modelId==null) return false;
        String m = modelId.toLowerCase();
        return (m.indexOf("stable-diffusion-xl-refiner")>=0 || m.indexOf("sdxl-refiner")>=0 || m.indexOf("refiner")>=0);
    }

    private String ttsGroupForModel(String modelId){
        if(modelId==null) return "";
        String m = modelId.toLowerCase();
        if(indexOfAny(m,new String[]{"xtts","coqui/xtts"})>=0) return "xtts";
        if(indexOfAny(m,new String[]{"vall-e-x","valle"})>=0) return "valle";
        if(indexOfAny(m,new String[]{"yourtts"})>=0) return "yourtts";
        if(indexOfAny(m,new String[]{"bark"})>=0) return "bark";
        if(indexOfAny(m,new String[]{"tortoise-tts","tortoise_tts"})>=0) return "tortoise";
        if(indexOfAny(m,new String[]{"mms-tts"})>=0) return "mms";
        if(indexOfAny(m,new String[]{"vibevoice"})>=0) return "vibevoice";
        if(indexOfAny(m,new String[]{"vits","espnet","kan-bayashi"})>=0) return "vits";
        if(indexOfAny(m,new String[]{"mozilla/tts"})>=0) return "mozilla_tts";
        return "";
    }

    private boolean isHfEndpoint(String endpoint){
        try{
            if(endpoint==null) return false;
            java.net.URL u = new java.net.URL(endpoint);
            String host = (u.getHost()!=null)? u.getHost().toLowerCase() : "";
            return host.indexOf("huggingface.co")>=0;
        }catch(Exception e){ return false; }
    }

    // ====== Img2Vid (محاولات متعددة) ======
    private byte[] callImg2Vid(String modelId, byte[] imgBytes, String motionPrompt, VideoDefaults vd, MotionParams mp, int inW, int inH, String hfToken){
        String url = hfModelApiUrl(modelId);
        String imgDataUrl = "data:image/png;base64,"+b64(imgBytes);

        String p1 = "{"
            + "\"inputs\":\""+escapeJson(imgDataUrl)+"\","
            + "\"parameters\":{"
            + "\"num_frames\":"+vd.frames+",\"frames\":"+vd.frames+","
            + "\"fps\":"+vd.fps+","
            + "\"width\":"+inW+",\"height\":"+inH+","
            + "\"steps\":"+vd.steps+","
            + "\"guidance_scale\":"+vd.guidance
            + (vd.includeSvdMicro? ",\"motion_bucket_id\":"+mp.motionBucketId+",\"noise_aug_strength\":"+mp.noiseAugStrength : "")
            + (vd.motionIntensity!=null? ",\"motion_intensity\":"+vd.motionIntensity.floatValue():"")
            + (vd.extraKV!=null && vd.extraKV.length()>0? ","+vd.extraKV:"")
            + "},"
            + "\"options\":{\"wait_for_model\":true,\"use_cache\":false}"
            + "}";
        try {
            logReqPayload(url, p1);
            HttpResp r = httpPostJsonResp(url, p1, hfToken, "video/*,application/json", 150000, 300000);
            byte[] vid = ensureVideoBytes(r);
            if(vid!=null && vid.length>0) return vid;
        } catch(Exception e){ if(handleIfQuotaExceeded(e,"img2vid", modelId)) return null; }

        String p2 = "{"
            + "\"inputs\":{"
            + "\"image\":\""+escapeJson(imgDataUrl)+"\","
            + "\"prompt\":\""+escapeJson(motionPrompt)+"\""
            + "},"
            + "\"parameters\":{"
            + "\"num_frames\":"+vd.frames+",\"frames\":"+vd.frames+","
            + "\"fps\":"+vd.fps+","
            + "\"width\":"+inW+",\"height\":"+inH+","
            + "\"steps\":"+vd.steps+","
            + "\"guidance_scale\":"+vd.guidance
            + (vd.includeSvdMicro? ",\"motion_bucket_id\":"+mp.motionBucketId+",\"noise_aug_strength\":"+mp.noiseAugStrength : "")
            + (vd.motionIntensity!=null? ",\"motion_intensity\":"+vd.motionIntensity.floatValue():"")
            + (vd.extraKV!=null && vd.extraKV.length()>0? ","+vd.extraKV:"")
            + "},"
            + "\"options\":{\"wait_for_model\":true,\"use_cache\":false}"
            + "}";
        try {
            logReqPayload(url, p2);
            HttpResp r = httpPostJsonResp(url, p2, hfToken, "video/*,application/json", 150000, 300000);
            byte[] vid = ensureVideoBytes(r);
            if(vid!=null && vid.length>0) return vid;
        } catch(Exception e){ if(handleIfQuotaExceeded(e,"img2vid", modelId)) return null; }

        String p3 = "{"
            + "\"image\":\""+escapeJson(imgDataUrl)+"\","
            + "\"parameters\":{"
            + "\"num_frames\":"+vd.frames+",\"frames\":"+vd.frames+","
            + "\"fps\":"+vd.fps+","
            + "\"width\":"+inW+",\"height\":"+inH+","
            + "\"steps\":"+vd.steps+","
            + "\"guidance_scale\":"+vd.guidance
            + (vd.includeSvdMicro? ",\"motion_bucket_id\":"+mp.motionBucketId+",\"noise_aug_strength\":"+mp.noiseAugStrength : "")
            + (vd.motionIntensity!=null? ",\"motion_intensity\":"+vd.motionIntensity.floatValue():"")
            + (vd.extraKV!=null && vd.extraKV.length()>0? ","+vd.extraKV:"")
            + "},"
            + "\"options\":{\"wait_for_model\":true,\"use_cache\":false}"
            + "}";
        try {
            logReqPayload(url, p3);
            HttpResp r = httpPostJsonResp(url, p3, hfToken, "video/*,application/json", 150000, 300000);
            byte[] vid = ensureVideoBytes(r);
            if(vid!=null && vid.length>0) return vid;
        } catch(Exception e){ if(handleIfQuotaExceeded(e,"img2vid", modelId)) return null; }

        try{
            HttpResp r2 = httpPostBytesResp(url, imgBytes, hfToken, "application/octet-stream", "video/*,application/json", 150000, 300000);
            byte[] vid = ensureVideoBytes(r2);
            if(vid!=null && vid.length>0) return vid;
        }catch(Exception e){ if(handleIfQuotaExceeded(e,"img2vid", modelId)) return null; }

        try{
            byte[] vid = callImg2VidMultipart(modelId, imgBytes, motionPrompt, vd, mp, inW, inH, hfToken);
            if(vid != null && vid.length > 0) return vid;
        }catch(Exception exM){
            if(handleIfQuotaExceeded(exM,"img2vid", modelId)) return null;
            writeToFile("img2vid multipart failed: "+exM.getMessage());
        }

        return null;
    }
}
