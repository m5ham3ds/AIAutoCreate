package com.example.aiautocreate;

import java.util.ArrayList;
import java.util.List;

/**
 * StylePacks - Ultra-Full Literal
 * - Java7 / AIDE-safe
 * - باقات ثابتة من "style tokens" تضاف للـ prompt دون تغيير بنية التطبيق
 * - تُدمج أسماء الباقات ضمن قوائم image_styles و video_styles وتُحقن التوكنز عند توليد الصور/الفيديو
 */
public final class StylePacks {

    public static final String PRESET_PREFIX = "[Preset] ";

    private StylePacks(){}

    // الباقات (أسماء العرض)
    public static String[] getPackNames(){
        return new String[]{
            "Cinematic Photoreal",
            "Midjourney-like",
            "Studio Portrait",
            "Analog Film",
            "Digital Painting",
            "Sci-Fi Concept",
            // باقات إضافية
            "Anime",
            "Realistic",
            "Pixel Art",
            "Sci-Fi"
        };
    }

    public static String toDisplayName(String packName){
        return PRESET_PREFIX + packName;
    }

    public static boolean isPresetDisplayName(String s){
        return s != null && s.startsWith(PRESET_PREFIX);
    }

    public static String stripPrefix(String displayName){
        if(displayName == null) return "";
        return isPresetDisplayName(displayName) ? displayName.substring(PRESET_PREFIX.length()).trim()
            : displayName.trim();
    }

    /**
     * دمج أسماء الباقات ضمن القوائم في PrefManager (image_styles + video_styles)
     * - يضيفها دائمًا أعلى القائمة بدون تكرار.
     */
    public static void registerIntoPrefs(PrefManager pref){
        if(pref == null) return;

        String[] base = getPackNames();
        List<String> presetDisplayNames = new ArrayList<String>();
        for(int i=0;i<base.length;i++){
            presetDisplayNames.add(toDisplayName(base[i]));
        }

        // دمج في image_styles (تضاف في الأعلى مع الحفاظ على الترتيب)
        List<String> img = pref.csvToList(pref.getModelStyles("image_styles"));
        addAtTopIfMissing(img, presetDisplayNames);
        pref.setModelStyles("image_styles", joinCsv(img));

        // دمج في video_styles
        List<String> vid = pref.csvToList(pref.getModelStyles("video_styles"));
        addAtTopIfMissing(vid, presetDisplayNames);
        pref.setModelStyles("video_styles", joinCsv(vid));
    }

    // إضافة العناصر في أعلى القائمة بدون تكرار مع الحفاظ على ترتيبها
    private static void addAtTopIfMissing(List<String> list, List<String> items){
        if(list == null) return;
        if(items == null || items.size()==0) return;
        for(int i=items.size()-1; i>=0; i--){
            String it = items.get(i);
            if(it!=null && !list.contains(it)) list.add(0, it);
        }
    }

    /**
     * حقن توكنز الباقة ضمن prompt الصور (Stable Diffusion)
     */
    public static String patchPromptForImage(String selectedStyleDisplayName, String sdModelId, String originalPrompt){
        if(originalPrompt == null) originalPrompt = "";
        if(!isPresetDisplayName(selectedStyleDisplayName)) return originalPrompt;

        String pack = stripPrefix(selectedStyleDisplayName);
        String group = groupForModel(sdModelId);

        // SDXL Refiner عادة مرحلة تحسين فقط
        if("sdxl_refiner".equals(group)){
            return originalPrompt;
        }

        String tokens = tokensFor(pack, group, false); // false => image
        if(tokens.length() == 0) return originalPrompt;

        if(originalPrompt.trim().length() == 0) return tokens;
        return originalPrompt + ", " + tokens;
    }

    /**
     * اختياري: حقن توكنز الباقة ضمن prompt الفيديو
     */
    public static String patchPromptForVideo(String selectedVideoStyleDisplayName, String img2vidModelId, String originalPrompt){
        if(originalPrompt == null) originalPrompt = "";
        if(!isPresetDisplayName(selectedVideoStyleDisplayName)) return originalPrompt;

        String pack = stripPrefix(selectedVideoStyleDisplayName);
        String group = groupForModel(img2vidModelId);

        String tokens = tokensFor(pack, group, true); // true => video
        if(tokens.length() == 0) return originalPrompt;

        if(originalPrompt.trim().length() == 0) return tokens;
        return originalPrompt + ", " + tokens;
    }

    /**
     * تصنيف الموديل لمطابقة المفردات
     * صور: sdxl / sdxl_refiner / dreamlike / openjourney / dreamshaper / realistic_vision / anything / waifu / portraitplus / modi
     * فيديو: svd / svd_xt / wan / easyanimate / videocrafter2 / dynamicrafter / cinemo / latte / seine
     */
    public static String groupForModel(String modelId){
        if(modelId == null) return "sdxl";
        String m = modelId.toLowerCase();

        // صور (Text2Image)
        if(indexOfAny(m, new String[]{"stable-diffusion-xl-refiner", "sdxl-refiner", "refiner"})) return "sdxl_refiner";
        if(indexOfAny(m, new String[]{"stable-diffusion-xl", "sdxl"})) return "sdxl";
        if(indexOfAny(m, new String[]{"dreamlike-photoreal"}))       return "dreamlike";
        if(indexOfAny(m, new String[]{"openjourney"}))               return "openjourney";
        if(indexOfAny(m, new String[]{"dreamshaper"}))               return "dreamshaper";
        if(indexOfAny(m, new String[]{"realistic_vision", "realistic-vision"})) return "realistic_vision";
        if(indexOfAny(m, new String[]{"anything-v4", "anythingv4", "anything"})) return "anything";
        if(indexOfAny(m, new String[]{"waifu-diffusion"}))           return "waifu";
        if(indexOfAny(m, new String[]{"portraitplus"}))              return "portraitplus";
        if(indexOfAny(m, new String[]{"mo-di", "mo_di", "modi"}))    return "modi";

        // فيديو (Img2Vid)
        if(indexOfAny(m, new String[]{"img2vid-xt", "img2vid_xt"}))  return "svd_xt";
        if(indexOfAny(m, new String[]{"stable-video-diffusion", "img2vid"})) return "svd";
        if(indexOfAny(m, new String[]{"wan2.2", "wan2.1", "wan-ai", "wan2", "wan"})) return "wan";
        if(indexOfAny(m, new String[]{"easyanimate"}))               return "easyanimate";
        if(indexOfAny(m, new String[]{"videocrafter2"}))             return "videocrafter2";
        if(indexOfAny(m, new String[]{"dynamicrafter"}))             return "dynamicrafter";
        if(indexOfAny(m, new String[]{"cinemo"}))                    return "cinemo";
        if(indexOfAny(m, new String[]{"latte-1", "latte"}))          return "latte";
        if(indexOfAny(m, new String[]{"seine"}))                     return "seine";

        // fallback قد يكون SD عادي
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

    /**
     * يعيد التوكنز وفق اسم الباقة والمجموعة ونوع الهدف (فيديو/صورة)
     * ملاحظة: SDXL Refiner يعاد بلا توكنز لأنه عادة مرحلة تحسين فقط.
     */
    private static String tokensFor(String pack, String group, boolean forVideo){
        if(pack == null) return "";

        boolean isRefiner  = "sdxl_refiner".equals(group);
        boolean isOpenJ    = "openjourney".equals(group);
        boolean isDreamlk  = "dreamlike".equals(group);
        boolean isSDXL     = "sdxl".equals(group);
        boolean isDreamsh  = "dreamshaper".equals(group);
        boolean isRVision  = "realistic_vision".equals(group);
        boolean isAnything = "anything".equals(group) || "waifu".equals(group);
        boolean isWaifu    = "waifu".equals(group);
        boolean isPPlus    = "portraitplus".equals(group);
        boolean isMoDi     = "modi".equals(group);

        boolean isSVD      = "svd".equals(group) || "img2vid".equals(group);
        boolean isSVDXT    = "svd_xt".equals(group);
        boolean isWAN      = "wan".equals(group);
        boolean isEAnim    = "easyanimate".equals(group);
        boolean isVC2      = "videocrafter2".equals(group);
        boolean isDynC     = "dynamicrafter".equals(group);
        boolean isCinemo   = "cinemo".equals(group);
        boolean isLatte    = "latte".equals(group);
        boolean isSeine    = "seine".equals(group);

        if(isRefiner) return "";

        if("Cinematic Photoreal".equals(pack)){
            if(forVideo){
                if(isWAN)    return "cinematic, CGI, smooth pan, camera dolly, slow zoom, motion blur, temporal coherence, vfx";
                if(isSVDXT)  return "cinematic, smooth camera movement, parallax, slow zoom, motion blur, depth of field, lens flares";
                if(isSVD)    return "cinematic, film grain, color graded, subtle camera push, parallax, motion blur, depth of field";
                if(isEAnim || isVC2 || isDynC || isCinemo || isLatte || isSeine)
                    return "cinematic, smooth camera movement, temporal consistency, subtle parallax, graded";
                return "cinematic, smooth camera movement";
            } else {
                if(isOpenJ)  return "mdjrny-v4 style, epic, dramatic lighting, cinematic composition, ultra-detailed, 4k, vibrant colors";
                if(isDreamlk||isSDXL||isDreamsh||isRVision)
                    return "photorealistic, cinematic lighting, ultra-detailed, 8k, RAW photo, DSLR, 50mm, rim light, volumetric lighting, bokeh, film grain";
                if(isAnything||isWaifu||isMoDi)
                    return "cinematic, detailed shading, dramatic lighting, clean composition, sharp focus";
                if(isPPlus)  return "portrait, photorealistic, studio light, realistic skin texture, shallow depth of field, 85mm, sharp focus";
                return "photorealistic, cinematic lighting, film grain";
            }
        }

        if("Midjourney-like".equals(pack)){
            if(forVideo){
                if(isWAN)   return "stylized cinematic motion, neon accents, smooth pan, motion blur";
                if(isSVDXT) return "epic, cinematic, painterly motion, dramatic lighting, smooth camera movement";
                if(isSVD)   return "cinematic, painterly, dramatic lighting, parallax, slow zoom";
                return "cinematic, painterly motion";
            } else {
                if(isOpenJ) return "mdjrny-v4 style, epic, cinematic, concept art, highly detailed, 4k, surreal";
                return "epic, fantasy, dramatic lighting, painterly, concept art, highly detailed, vibrant colors";
            }
        }

        if("Studio Portrait".equals(pack)){
            if(forVideo){
                return "portrait, studio lighting, soft light, shallow depth of field, smooth camera push";
            } else {
                if(isPPlus) return "portrait, photorealistic, studio lighting, soft light, 85mm, sharp focus, realistic skin texture";
                if(isSDXL || isDreamlk || isRVision)
                    return "portrait, photorealistic, studio lighting, soft light, rim light, beauty dish, 85mm, shallow depth of field, clean background, realistic skin texture, sharp focus";
                return "portrait, studio lighting, soft light, 85mm, sharp focus";
            }
        }

        if("Analog Film".equals(pack)){
            if(forVideo){
                return "analog film look, film grain, vintage color grade, handheld camera, light leak";
            } else {
                return "analog film, film grain, Kodak Portra, 35mm, Leica, light leak, faded colors, vintage, soft contrast, cinematic";
            }
        }

        if("Digital Painting".equals(pack)){
            if(forVideo){
                return "digital painting style, painterly motion, smooth camera movement";
            } else {
                if(isOpenJ || isDreamsh || isMoDi)
                    return "digital painting, concept art, brush strokes, painterly, high detail, illustration, trending on ArtStation, sharp lines, vibrant colors";
                return "digital painting, painterly, concept art, high detail";
            }
        }

        if("Sci-Fi Concept".equals(pack)){
            if(forVideo){
                if(isWAN) return "futuristic CGI, neon, volumetric fog, smooth pan, vfx, motion blur";
                return "futuristic, sci-fi, neon, volumetric fog, parallax, smooth camera movement, motion blur";
            } else {
                return "futuristic, sci-fi, hard surface, volumetric fog, neon, cyberpunk, highly detailed, octane render, unreal engine";
            }
        }

        if("Anime".equals(pack)){
            if(forVideo){
                if(isSVD || isSVDXT || isWAN || isEAnim)
                    return "anime motion, clean lineart, parallax, smooth pan, subtle camera push";
                return "anime cinematic motion, parallax";
            } else {
                if(isAnything || isWaifu)
                    return "anime, cel-shaded, clean lineart, large expressive eyes, soft gradients, toon shading, 2D illustration, vibrant colors";
                if(isOpenJ)
                    return "anime-style, vibrant, concept art, clean linework, 4k, soft lighting";
                return "anime, cel shading, 2D, clean lines";
            }
        }

        if("Realistic".equals(pack)){
            if(forVideo){
                return "realistic motion, subtle camera push, depth of field, motion blur, natural color grade";
            } else {
                if(isRVision || isSDXL || isDreamlk)
                    return "ultra-photorealistic, natural lighting, realistic textures, high fidelity skin, lens imperfections, RAW, sharp focus";
                return "photorealistic, natural lighting, realistic textures, sharp focus";
            }
        }

        if("Pixel Art".equals(pack)){
            if(forVideo){
                return "pixel art animation, sprite motion, strict pixel grid, retro parallax";
            } else {
                return "pixel art, 8-bit, 16-bit, sprite, limited palette, retro game style, pixel-perfect";
            }
        }

        if("Sci-Fi".equals(pack)){
            if(forVideo){
                if(isWAN) return "sci-fi CGI, smooth pan, motion blur, vfx, neon";
                return "sci-fi, neon, parallax, smooth camera push, motion blur, temporal coherence";
            } else {
                return "sci-fi, futuristic, neon lights, volumetric fog, hard-surface, high detail";
            }
        }

        // Fallback
        return "";
    }

    // Join CSV بسيط
    private static String joinCsv(List<String> list){
        if(list == null || list.size()==0) return "";
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<list.size();i++){
            if(i>0) sb.append(",");
            String it = list.get(i);
            if(it != null) sb.append(it.trim());
        }
        return sb.toString();
    }
}
