package com.example.aiautocreate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * ReadmeFilter - Ultra-Full Literal (AIDE-safe, Java7)
 * - واجهة ثابتة: extractStyles / extractVoices / extractAll
 * - احترافي: يتعرّف على مجموعة النموذج (صور/فيديو/صوت) ويوفّر Defaults مناسبة
 * - يعزّز النتائج من README + HF Tags
 * - ينظّف ويطبع النتائج بالإنجليزية فقط (يستبعد أي عناصر بالعربية أو غير ASCII تقريباً)
 * - فلترة صارمة للأسماء الشائعة/الضوضاء والروابط والحقول غير الواقعية
 */
public final class ReadmeFilter {

    private static final int TYPE_IMAGE=0, TYPE_VIDEO=1, TYPE_TTS=2;
    private static final int MAX_OUT = 50;

    private ReadmeFilter(){}

    // ===== API =====
    public static List<String> extractStyles(String modelId, String readme, List<String> hfTags){
        List<String> out = new ArrayList<String>();
        String group = groupForModel(modelId);
        boolean isRefiner = "sdxl_refiner".equals(group);

        // 1) Defaults بحسب المجموعة
        addAllUnique(out, getDefaultStylesForGroup(group));

        // 2) Refiner: لا نحقن أنماط إضافية عادةً
        if(isRefiner){
            return trim(out, MAX_OUT);
        }

        // 3) تعزيز من README (Styles / Presets / Tokens)
        if(readme != null && readme.length() > 0){
            List<String> fromSections = extractSectionTokens(readme, new String[]{
                                                                 "## styles","### styles","## style","### style",
                                                                 "## presets","### presets","## prompt style","### prompt style",
                                                                 "## visual styles","### visual styles","## themes","### themes",
                                                                 "## tokens","### tokens","## style tokens","### style tokens"
                                                             });
            addStylesCandidateList(out, fromSections);

            // التقاط كلمات مكرسة داخل النص (حرة)
            List<String> inline = parseEnglishNamesFromReadme(readme);
            addStylesCandidateList(out, inline);
        }

        // 4) تعزيز من HF tags
        addStylesFromTags(out, hfTags);

        // 5) تنظيف نهائي (إنجليزي فقط + توحيد أسماء + إزالة الضوضاء)
        out = cleanupAndCanonicalizeStyles(out);

        return trim(out, MAX_OUT);
    }

    public static List<String> extractVoices(String modelId, String readme, List<String> hfTags){
        List<String> out = new ArrayList<String>();
        String group = groupForModel(modelId);
        int type = classify(modelId);

        // إذا ليس TTS: لا نستخرج أصوات (نُرجع فارغ)
        if(type != TYPE_TTS){
            return out;
        }

        // 1) Defaults بحسب مجموعة TTS
        addAllUnique(out, getDefaultVoicesForGroup(group));

        // 2) تعزيز من README (Speakers/Voices/Presets)
        if(readme != null && readme.length() > 0){
            List<String> fromSections = extractSectionTokens(readme, new String[]{
                                                                 "## voices","### voices","## speakers","### speakers",
                                                                 "## presets","### presets","## voice presets","### voice presets",
                                                                 "## voice list","### voice list","## voice names","### voice names"
                                                             });
            addVoicesCandidateList(out, fromSections);

            // أسماء ملفات ومسارات شائعة للأصوات
            List<String> parsed = parseEnglishNamesFromReadme(readme);
            addVoicesCandidateList(out, parsed);

            // استخراج JSON-like lists داخل README مثل ["voice1","voice2"]
            List<String> jsonish = extractJsonishList(readme);
            addVoicesCandidateList(out, jsonish);
        }

        // 3) تعزيز من HF tags (vctk/ljspeech/librispeech/libritts)
        addVoicesFromTags(out, hfTags);

        // 4) Fallback إن لزم (إنجليزية فقط)
        if(out.isEmpty()){
            addUnique(out, "Male");
            addUnique(out, "Female");
        }

        // 5) تنظيف نهائي (إنجليزي فقط + إزالة ضوضاء وتوحيد)
        out = cleanupAndCanonicalizeVoices(out);

        return trim(out, MAX_OUT);
    }

    public static ExtractResult extractAll(String modelId, String readme, List<String> hfTags){
        ExtractResult r = new ExtractResult();
        r.styles = extractStyles(modelId, readme, hfTags);
        r.voices = extractVoices(modelId, readme, hfTags);

        r.stylesSource = (r.styles!=null && r.styles.size()>0) ? "COMBINED" : "NONE";
        r.voicesSource = (r.voices!=null && r.voices.size()>0) ? "COMBINED" : "NONE";
        r.stylesConfidence = calcConfidence(r.styles);
        r.voicesConfidence = calcConfidence(r.voices);
        return r;
    }

    public static final class ExtractResult{
        public List<String> styles;
        public List<String> voices;
        public String stylesSource;
        public String voicesSource;
        public int stylesConfidence;
        public int voicesConfidence;
    }

    // ======== Grouping / Classification ========
    private static int classify(String modelId){
        if(modelId==null) return TYPE_IMAGE;
        String id = modelId.toLowerCase();
        if(id.indexOf("img2vid")>=0 || id.indexOf("video")>=0 || id.indexOf("wan")>=0
           || id.indexOf("videocrafter")>=0 || id.indexOf("dynamicrafter")>=0
           || id.indexOf("easyanimate")>=0 || id.indexOf("cinemo")>=0 || id.indexOf("latte")>=0 || id.indexOf("seine")>=0) {
            return TYPE_VIDEO;
        }
        if(id.indexOf("xtts")>=0 || id.indexOf("higgs")>=0 || id.indexOf("vall")>=0 || id.indexOf("tts")>=0
           || id.indexOf("bark")>=0 || id.indexOf("mms-tts")>=0 || id.indexOf("yourtts")>=0
           || id.indexOf("vibevoice")>=0 || id.indexOf("vits")>=0 || id.indexOf("mozilla/tts")>=0) {
            return TYPE_TTS;
        }
        return TYPE_IMAGE;
    }

    /**
     * تصنيف الموديل إلى مجموعة اسمية للاستخدام الداخلي
     * صور: sdxl / sdxl_refiner / dreamlike / openjourney / dreamshaper / realistic_vision / anything / waifu / portraitplus / modi
     * فيديو: svd / svd_xt / wan / easyanimate / videocrafter2 / dynamicrafter / cinemo / latte / seine
     * TTS : xtts / higgs / valle / bark / tortoise / mms / yourtts / vibevoice / vits / mozilla_tts
     */
    private static String groupForModel(String modelId){
        if(modelId == null) return "sdxl";
        String m = modelId.toLowerCase();

        // صور
        if(indexOfAny(m, new String[]{"stable-diffusion-xl-refiner","sdxl-refiner","refiner"})) return "sdxl_refiner";
        if(indexOfAny(m, new String[]{"stable-diffusion-xl","sdxl"})) return "sdxl";
        if(indexOfAny(m, new String[]{"dreamlike-photoreal"})) return "dreamlike";
        if(indexOfAny(m, new String[]{"openjourney"})) return "openjourney";
        if(indexOfAny(m, new String[]{"dreamshaper"})) return "dreamshaper";
        if(indexOfAny(m, new String[]{"realistic_vision","realistic-vision"})) return "realistic_vision";
        if(indexOfAny(m, new String[]{"anything-v4","anythingv4","anything"})) return "anything";
        if(indexOfAny(m, new String[]{"waifu-diffusion"})) return "waifu";
        if(indexOfAny(m, new String[]{"portraitplus"})) return "portraitplus";
        if(indexOfAny(m, new String[]{"mo-di","mo_di","modi"})) return "modi";

        // فيديو
        if(indexOfAny(m, new String[]{"img2vid-xt","img2vid_xt"})) return "svd_xt";
        if(indexOfAny(m, new String[]{"stable-video-diffusion","img2vid"})) return "svd";
        if(indexOfAny(m, new String[]{"wan2.2","wan2.1","wan-ai","wan2","wan"})) return "wan";
        if(indexOfAny(m, new String[]{"easyanimate"})) return "easyanimate";
        if(indexOfAny(m, new String[]{"videocrafter2"})) return "videocrafter2";
        if(indexOfAny(m, new String[]{"dynamicrafter"})) return "dynamicrafter";
        if(indexOfAny(m, new String[]{"cinemo"})) return "cinemo";
        if(indexOfAny(m, new String[]{"latte-1","latte"})) return "latte";
        if(indexOfAny(m, new String[]{"seine"})) return "seine";

        // TTS
        if(indexOfAny(m, new String[]{"xtts"})) return "xtts";
        if(indexOfAny(m, new String[]{"higgs"})) return "higgs";
        if(indexOfAny(m, new String[]{"vall-e-x","valle"})) return "valle";
        if(indexOfAny(m, new String[]{"bark"})) return "bark";
        if(indexOfAny(m, new String[]{"tortoise-tts","tortoise_tts"})) return "tortoise";
        if(indexOfAny(m, new String[]{"mms-tts"})) return "mms";
        if(indexOfAny(m, new String[]{"yourtts"})) return "yourtts";
        if(indexOfAny(m, new String[]{"vibevoice"})) return "vibevoice";
        if(indexOfAny(m, new String[]{"vits","espnet","kan-bayashi"})) return "vits";
        if(indexOfAny(m, new String[]{"mozilla/tts","mozilla tts"})) return "mozilla_tts";

        return "sdxl";
    }

    private static boolean indexOfAny(String hay, String[] needles){
        if(hay == null || needles == null) return false;
        for(int i=0;i<needles.length;i++){
            String n = needles[i];
            if(n!=null && hay.indexOf(n)>=0) return true;
        }
        return false;
    }

    // ======== Defaults ========
    private static List<String> getDefaultStylesForGroup(String group){
        List<String> out = new ArrayList<String>();
        if(group == null) return out;

        // صور
        if("sdxl".equals(group)){
            addUnique(out,"Photorealistic");
            addUnique(out,"Cinematic");
            addUnique(out,"Digital Painting");
            addUnique(out,"Oil Painting");
            addUnique(out,"Portrait");
            addUnique(out,"Studio Lighting");
        } else if("sdxl_refiner".equals(group)){
            // refiner لا يضيف أنماط جديدة عادة
        } else if("dreamlike".equals(group)){
            addUnique(out,"Photorealistic");
            addUnique(out,"Studio Lighting");
            addUnique(out,"Portrait");
        } else if("openjourney".equals(group)){
            addUnique(out,"Midjourney-like");
            addUnique(out,"Fantasy Art");
            addUnique(out,"Digital Illustration");
        } else if("dreamshaper".equals(group)){
            addUnique(out,"Digital Painting");
            addUnique(out,"Concept Art");
            addUnique(out,"Portrait");
        } else if("realistic_vision".equals(group)){
            addUnique(out,"Photorealistic");
            addUnique(out,"Cinematic");
            addUnique(out,"Portrait");
        } else if("anything".equals(group) || "waifu".equals(group)){
            addUnique(out,"Anime");
            addUnique(out,"Manga");
            addUnique(out,"Cel Shading");
            addUnique(out,"Digital Illustration");
        } else if("portraitplus".equals(group)){
            addUnique(out,"Portrait");
            addUnique(out,"Studio Lighting");
        } else if("modi".equals(group)){
            addUnique(out,"Graphic Studio");
            addUnique(out,"Modern Minimal");
        }

        // فيديو
        else if("svd".equals(group)){
            addUnique(out,"Cinematic Motion");
            addUnique(out,"Smooth Camera Move");
            addUnique(out,"Parallax");
        } else if("svd_xt".equals(group)){
            addUnique(out,"Cinematic Motion");
            addUnique(out,"Motion Blur");
            addUnique(out,"Parallax");
        } else if("wan".equals(group)){
            addUnique(out,"CGI Motion");
            addUnique(out,"Smooth Pan");
            addUnique(out,"Temporal Consistency");
        } else if("easyanimate".equals(group) || "videocrafter2".equals(group) || "dynamicrafter".equals(group)
                  || "cinemo".equals(group) || "latte".equals(group) || "seine".equals(group)){
            addUnique(out,"Cinematic Motion");
            addUnique(out,"Smooth Camera Move");
            addUnique(out,"Temporal Consistency");
        }

        return out;
    }

    private static List<String> getDefaultVoicesForGroup(String group){
        List<String> out = new ArrayList<String>();
        if(group == null) return out;

        if("xtts".equals(group)){
            addUnique(out,"Voice Clone");
            addUnique(out,"Male");
            addUnique(out,"Female");
        } else if("higgs".equals(group)){
            addUnique(out,"Belinda");
            addUnique(out,"Broom_Salesman");
        } else if("valle".equals(group)){
            addArray(out, VALL_EX_PRESETS);
        } else if("bark".equals(group)){
            addUnique(out,"NARRATION");
            addUnique(out,"CASUAL");
        } else if("tortoise".equals(group)){
            addUnique(out,"Random");
            addUnique(out,"Soft");
        } else if("mms".equals(group)){
            addUnique(out,"LJSpeech");
            addUnique(out,"LibriTTS");
        } else if("yourtts".equals(group)){
            addUnique(out,"Zero-Shot Clone");
        } else if("vibevoice".equals(group)){
            addUnique(out,"Narration");
            addUnique(out,"Studio");
        } else if("vits".equals(group)){
            addUnique(out,"LJSpeech");
            addUnique(out,"JSUT");
        } else if("mozilla_tts".equals(group)){
            addUnique(out,"EN_Male");
            addUnique(out,"EN_Female");
        }

        return out;
    }

    // ======== README Parsing / Extraction ========
    private static List<String> extractSectionTokens(String readme, String[] headings){
        List<String> found = new ArrayList<String>();
        if(readme == null) return found;
        try{
            String low = readme.toLowerCase();
            int start = -1;
            for(int i=0;i<headings.length;i++){
                String h = headings[i];
                int idx = low.indexOf(h);
                if(idx>=0){ start = idx + h.length(); break; }
            }
            if(start<0) return found;

            // اجمع حتى عنوان جديد
            int end = low.indexOf("\n#", start);
            if(end<0) end = readme.length();

            String sec = readme.substring(start, end);

            // التقط عناصر بين backticks أو داخل قوائم
            addAllUnique(found, extractBacktickTokens(sec));
            addAllUnique(found, extractListBullets(sec));
            addAllUnique(found, extractJsonishList(sec));

            // fallback: التقط أسماء نظيفة سطرية
            String[] lines = sec.split("\n");
            for(int i=0;i<lines.length;i++){
                String t = lines[i].trim();
                if(isCleanEnglishToken(t) && !isNoiseWord(t)){
                    addUnique(found, t);
                }
            }
        }catch(Exception ignored){}
        return found;
    }

    private static List<String> extractBacktickTokens(String s){
        List<String> out = new ArrayList<String>();
        if(s==null) return out;
        try{
            Pattern p = Pattern.compile("`([^`]{1,60})`");
            Matcher m = p.matcher(s);
            while(m.find()){
                String t = m.group(1).trim();
                if(isCleanEnglishToken(t) && !isNoiseWord(t)) addUnique(out, t);
            }
        }catch(Exception ignored){}
        return out;
    }

    private static List<String> extractListBullets(String s){
        List<String> out = new ArrayList<String>();
        if(s==null) return out;
        String[] lines = s.split("\n");
        for(int i=0;i<lines.length;i++){
            String ln = lines[i].trim();
            if(ln.startsWith("- ") || ln.startsWith("* ")){
                String token = ln.substring(2).trim();
                if(isCleanEnglishToken(token) && !isNoiseWord(token)) addUnique(out, token);
            }
        }
        return out;
    }

    // JSON-like arrays سواء في النص أو داخل code fences ثلاثية
    private static List<String> extractJsonishList(String s){
        List<String> out = new ArrayList<String>();
        if(s == null) return out;
        try{
            // نمط القوائم الشبيهة بـ JSON: ["v1","v2", ...] داخل أي كتلة
            Pattern pFence = Pattern.compile("```[A-Za-z0-9_\\-]*\\s*([\\s\\S]*?)```", Pattern.DOTALL);
            Pattern pItem  = Pattern.compile("\"([^\"]{1,50})\"");

            // 1) التقط العناصر مباشرة من جميع الـ fences
            Matcher mArr = pFence.matcher(s);
            while(mArr.find()){
                String chunk = mArr.group(1); // داخل الـ fence
                Matcher mItem = pItem.matcher(chunk);
                while(mItem.find()){
                    String t = mItem.group(1).trim();
                    if(isCleanEnglishToken(t) && !isNoiseWord(t)) addUnique(out, t);
                }
            }

            // 2) التقط العناصر JSON-like من النص كله أيضًا (خارج الـ fences)
            Matcher mItem2 = pItem.matcher(s);
            while(mItem2.find()){
                String t = mItem2.group(1).trim();
                if(isCleanEnglishToken(t) && !isNoiseWord(t)) addUnique(out, t);
            }
        }catch(Exception ignored){}
        return out;
    }

    private static List<String> parseEnglishNamesFromReadme(String readme){
        List<String> out = new ArrayList<String>();
        if(readme==null) return out;

        // أسماء ملفات بدون الامتدادات مع فلترة إنجليزية فقط
        try{
            Pattern pFile = Pattern.compile("([A-Za-z0-9_\\-]{3,40})\\.(npz|json|wav|mp3|webm|ogg)", Pattern.CASE_INSENSITIVE);
            Matcher mFile = pFile.matcher(readme);
            while(mFile.find()){
                String base = mFile.group(1);
                if(isCleanEnglishToken(base) && !isNoiseWord(base)) addUnique(out, base);
            }
        }catch(Exception ignored){}

        // مسارات presets/examples
        try{
            Pattern pPath = Pattern.compile("(?:presets/|examples/voice_prompts/|voice_prompts/)([A-Za-z0-9_\\-]{3,40})", Pattern.CASE_INSENSITIVE);
            Matcher mPath = pPath.matcher(readme);
            while(mPath.find()){
                String name = mPath.group(1);
                if(isCleanEnglishToken(name) && !isNoiseWord(name)) addUnique(out, name);
            }
        }catch(Exception ignored){}

        // كلمات نظيفة سطرية (fallback)
        try{
            String[] lines = readme.split("\n");
            for(int i=0;i<lines.length;i++){
                String t = lines[i].trim();
                if(isCleanEnglishToken(t) && !isNoiseWord(t)) addUnique(out, t);
            }
        }catch(Exception ignored){}

        return out;
    }

    // ======== Mappers / Canonicalization ========
    private static void addStylesFromTags(List<String> out, List<String> tags){
        if(tags==null) return;
        for(int i=0;i<tags.size();i++){
            String t = safe(tags.get(i)).toLowerCase();
            if(t.length()==0) continue;

            if(t.indexOf("photoreal")>=0 || t.indexOf("realistic")>=0) addUnique(out,"Photorealistic");
            if(t.indexOf("cinema")>=0 || t.indexOf("cinematic")>=0)   addUnique(out,"Cinematic");
            if(t.indexOf("anime")>=0 || t.indexOf("manga")>=0)        addUnique(out,"Anime");
            if(t.indexOf("pixel")>=0 || t.indexOf("8-bit")>=0)        addUnique(out,"Pixel Art");
            if(t.indexOf("cartoon")>=0 || t.indexOf("toon")>=0)       addUnique(out,"Cartoon");
            if(t.indexOf("watercolor")>=0)                             addUnique(out,"Watercolor");
            if(t.indexOf("oil")>=0 || t.indexOf("acrylic")>=0)        addUnique(out,"Oil Painting");
            if(t.indexOf("line art")>=0 || t.indexOf("sketch")>=0)    addUnique(out,"Line Art");
            if(t.indexOf("3d")>=0 || t.indexOf("isometric")>=0)       addUnique(out,"3D Render");
            if(t.indexOf("noir")>=0)                                   addUnique(out,"Noir");
            if(t.indexOf("hdr")>=0)                                    addUnique(out,"HDR");
            if(t.indexOf("portrait")>=0)                               addUnique(out,"Portrait");
            if(t.indexOf("concept")>=0)                                addUnique(out,"Concept Art");
            if(t.indexOf("studio")>=0)                                 addUnique(out,"Studio Lighting");
            if(t.indexOf("fantasy")>=0)                                addUnique(out,"Fantasy Art");
            if(t.indexOf("digital")>=0)                                addUnique(out,"Digital Painting");
        }
    }

    private static void addVoicesFromTags(List<String> out, List<String> tags){
        if(tags==null) return;
        for(int i=0;i<tags.size();i++){
            String t = safe(tags.get(i)).toLowerCase();
            if(t.indexOf("vctk")>=0){
                addUnique(out,"VCTK-1"); addUnique(out,"VCTK-2"); addUnique(out,"VCTK-3"); addUnique(out,"VCTK-4");
            }
            if(t.indexOf("librispeech")>=0){
                addUnique(out,"LibriSpeech"); addUnique(out,"LibriSpeech-2");
            }
            if(t.indexOf("libritts")>=0){
                addUnique(out,"LibriTTS"); addUnique(out,"LibriTTS-2");
            }
            if(t.indexOf("ljspeech")>=0){
                addUnique(out,"LJSpeech");
            }
        }
    }

    private static void addStylesCandidateList(List<String> out, List<String> cands){
        for(int i=0;i<cands.size();i++){
            String v = mapStyleSynonym(cands.get(i));
            if(isCleanEnglishToken(v) && !isNoiseWord(v)) addUnique(out, toTitleCase(v));
        }
    }

    private static void addVoicesCandidateList(List<String> out, List<String> cands){
        for(int i=0;i<cands.size();i++){
            String v = cands.get(i);
            if(isCleanEnglishToken(v) && !isNoiseWord(v)) addUnique(out, toTitleCase(v));
        }
    }

    // توحيد أنماط شائعة إلى أسماء قياسية
    private static String mapStyleSynonym(String x){
        String s = safe(x).toLowerCase().trim();

        if(s.indexOf("mdjrny")>=0 || s.indexOf("midjourney")>=0) return "Midjourney-like";
        if(s.indexOf("photoreal")>=0 || s.indexOf("realistic")>=0) return "Photorealistic";
        if(s.indexOf("cinema")>=0 || s.indexOf("cinematic")>=0) return "Cinematic";
        if(s.indexOf("digital painting")>=0 || s.indexOf("digital art")>=0) return "Digital Painting";
        if(s.indexOf("concept")>=0) return "Concept Art";
        if(s.indexOf("oil")>=0 || s.indexOf("acrylic")>=0) return "Oil Painting";
        if(s.indexOf("watercolor")>=0) return "Watercolor";
        if(s.indexOf("line art")>=0 || s.indexOf("sketch")>=0 || s.indexOf("ink")>=0) return "Line Art";
        if(s.indexOf("pixel")>=0 || s.indexOf("8-bit")>=0 || s.indexOf("pixelart")>=0) return "Pixel Art";
        if(s.indexOf("anime")>=0 || s.indexOf("manga")>=0) return "Anime";
        if(s.indexOf("cartoon")>=0 || s.indexOf("toon")>=0) return "Cartoon";
        if(s.indexOf("3d")>=0 || s.indexOf("isometric")>=0 || s.indexOf("render")>=0) return "3D Render";
        if(s.indexOf("hdr")>=0) return "HDR";
        if(s.indexOf("noir")>=0) return "Noir";
        if(s.indexOf("portrait")>=0) return "Portrait";
        if(s.indexOf("studio")>=0) return "Studio Lighting";
        if(s.indexOf("fantasy")>=0 || s.indexOf("myth")>=0 || s.indexOf("dragon")>=0) return "Fantasy Art";

        // إذا كان نظيفاً أصلاً
        return s;
    }

    private static List<String> cleanupAndCanonicalizeStyles(List<String> in){
        List<String> out = new ArrayList<String>();
        for(int i=0;i<in.size();i++){
            String v = mapStyleSynonym(in.get(i));
            if(isCleanEnglishToken(v) && !isNoiseWord(v)){
                addUnique(out, toTitleCase(v));
            }
        }
        return out;
    }

    private static List<String> cleanupAndCanonicalizeVoices(List<String> in){
        List<String> out = new ArrayList<String>();
        for(int i=0;i<in.size();i++){
            String v = in.get(i);
            if(isCleanEnglishToken(v) && !isNoiseWord(v)){
                addUnique(out, toTitleCase(v));
            }
        }
        return out;
    }

    // ======== Utils / Filters ========
    private static boolean isCleanEnglishToken(String s){
        if(s==null) return false;
        String t = s.trim();
        if(t.length()<3 || t.length()>50) return false;

        // استبعاد أي نص عربي
        if(containsArabic(t)) return false;

        // لا روابط/مسارات/أوامر أو كلمات تقنية عامة
        String low = t.toLowerCase();
        if(low.indexOf("http")>=0 || low.indexOf("github")>=0 || low.indexOf("huggingface")>=0) return false;
        if(isNoiseWord(low)) return false;

        // اسم إنجليزي/لاتيني نظيف (أحرف/أرقام وبعض العلامات)
        for(int i=0;i<t.length();i++){
            char c = t.charAt(i);
            boolean ok = (c>='A'&&c<='Z') || (c>='a'&&c<='z') || (c>='0'&&c<='9')
                || c==' ' || c=='_' || c=='-' || c=='+' || c=='.' || c=='/';
            if(!ok) return false;
        }

        // لا يبدأ برقم صرف
        if(t.length()>0 && (t.charAt(0)>='0' && t.charAt(0)<='9')) return false;

        // إزالة الحشوات الشائعة
        if(low.equals("style") || low.equals("styles") || low.equals("preset") || low.equals("presets")) return false;

        return true;
    }

    private static boolean containsArabic(String s){
        // تغطية نطاقات العربية الأساسية والموسعة وعرض التقديم
        for(int i=0;i<s.length();i++){
            char c = s.charAt(i);
            // Arabic blocks
            if( (c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)
               || (c >= 0x08A0 && c <= 0x08FF) || (c >= 0xFB50 && c <= 0xFDFF)
               || (c >= 0xFE70 && c <= 0xFEFF) ) return true;
        }
        return false;
    }

    private static boolean isNoiseWord(String s){
        if(s==null) return true;
        String low = s.trim().toLowerCase();
        String[] commons = new String[]{
            "usage","example","examples","presets","readme","model","audio","voice","voices","download","dataset",
            "license","inference","speaker","speakers","list","note","notes","config","install","installation",
            "requirements","troubleshooting","troubleshoot","cli","run","train","training","evaluation","eval","demo","space",
            "huggingface","github","paper","arxiv","citation","contributors","thanks","todo","roadmap","guide","how to",
            "parameters","options","arguments","argument","flag","flags","prompt","inputs","output","outputs","images","video",
            "generate","generation","step","steps","usage example","pipeline","dependencies","credits","acknowledgement",
            "sdxl","sd","sdu","sduxl","refiner","base","checkpoint","checkpoints","model card","card"
        };
        for(int i=0;i<commons.length;i++){
            if(low.equals(commons[i])) return true;
        }
        // جمل طويلة عامة
        if(low.indexOf("this model")>=0 || low.indexOf("how to use")>=0) return true;
        return false;
    }

    private static String toTitleCase(String s){
        if(s==null) return "";
        String t = s.trim();
        if(t.length()==0) return t;
        String[] parts = t.split("[_\\-\\s\\/]+");
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<parts.length;i++){
            String p = parts[i];
            if(p.length()==0) continue;
            char c = p.charAt(0);
            if(c>='a' && c<='z') c = (char)(c - 32);
            sb.append(c);
            if(p.length()>1) sb.append(p.substring(1));
            if(i<parts.length-1) sb.append(' ');
        }
        return sb.toString();
    }

    private static void addUnique(List<String> list,String v){
        if(list==null || v==null) return;
        String val = v.trim();
        if(val.length()==0) return;

        // منع العربية
        if(containsArabic(val)) return;

        // dedup case-insensitive
        for(int i=0;i<list.size();i++){
            if(list.get(i).equalsIgnoreCase(val)) return;
        }
        list.add(val);
    }

    private static void addAllUnique(List<String> list, List<String> src){
        if(list==null || src==null) return;
        for(int i=0;i<src.size();i++){
            addUnique(list, src.get(i));
        }
    }

    private static void addArray(List<String> list, String[] arr){
        if(list==null || arr==null) return;
        for(int i=0;i<arr.length;i++){
            addUnique(list, arr[i]);
        }
    }

    private static List<String> trim(List<String> in,int max){
        List<String> out=new ArrayList<String>();
        if(in==null) return out;
        for(int i=0;i<in.size() && i<max;i++){
            addUnique(out, in.get(i));
        }
        return out;
    }

    private static int calcConfidence(List<String> list){
        if(list==null) return 0;
        int n = list.size();
        if(n>=15) return 90;
        if(n>=8) return 75;
        if(n>=4) return 60;
        if(n>=1) return 50;
        return 0;
    }

    private static String safe(String s){ return (s==null)?"":s; }

    // ===== Static preset lists for TTS =====
    private static final String[] VALL_EX_PRESETS = new String[]{
        // emotions/styles (English only)
        "Neutral","Amused","Anger","Sleepiness","Disgust","Emo_Amused","Emo_Anger","Emo_Neutral","Emo_Sleepy",
        // voice sets / datasets
        "VCTK-1","VCTK-2","VCTK-3","VCTK-4",
        "LibriSpeech","LibriSpeech-2","LibriTTS","LibriTTS-2",
        // named voices (commonly seen in demos)
        "Alan","Babara","Bronya_1","Cafe","Dingzhen","Dingzhen_1","Esta","Fuxuan_2",
        "Paimon_1","Prompt_1","Rosalia","Seel","Seel_1","YaeSakura","YaeSakura_1",
        // acoustic presets
        "Acou_1","Acou_2","Acou_3","Acou_4"
    };
}
