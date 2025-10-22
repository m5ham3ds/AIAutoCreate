package com.example.aiautocreate;

import java.util.ArrayList;
import java.util.List;

/**
 * ModelReadmeLinks - Ultra-Full Literal
 * Java7 Compatible + AIDE Safe
 *
 * - يحتوي على جميع روابط الـ README من HuggingFace و GitHub
 * - يستخدمه SettingsActivity (refreshModelsAndCache)
 * - يسهل إضافة موديلات جديدة أو روابط Fallback لاحقاً
 */
public class ModelReadmeLinks {

    /**
     * رابط README الرئيسي دائماً من HuggingFace.
     * مثال: https://huggingface.co/{MODEL_ID}/raw/main/README.md
     */
    public static String getHuggingFaceReadme(String modelId) {
        if (modelId == null) return "";
        return "https://huggingface.co/" + modelId + "/raw/main/README.md";
    }

    /**
     * روابط Fallback GitHub المحتملة لكل Model
     */
    public static List<String> getFallbackGithubUrls(String modelId) {
        List<String> out = new ArrayList<String>();
        if (modelId == null) return out;
        String id = modelId.toLowerCase();

        // ========= Stable Diffusion XL (SDXL) + Refiner =========
        if (id.contains("stable-diffusion-xl") || id.contains("sdxl") || id.contains("sdxl-refiner") || id.contains("refiner")) {
            out.add("https://raw.githubusercontent.com/Stability-AI/generative-models/main/README.md");
            out.add("https://raw.githubusercontent.com/Stability-AI/stability-sdk/main/README.md");
        }

        // ========= Dreamlike Photoreal =========
        if (id.contains("dreamlike-photoreal") || id.contains("dreamlike-art")) {
            // قد لا يتوفر ريبو رسمي، نضيف مستودع المشروع العام إن وجد
            out.add("https://raw.githubusercontent.com/dreamlike-art/dreamlike-photoreal/main/README.md");
        }

        // ========= PromptHero / OpenJourney =========
        if (id.contains("prompthero") || id.contains("openjourney")) {
            out.add("https://raw.githubusercontent.com/prompthero/openjourney/main/README.md");
            out.add("https://raw.githubusercontent.com/prompthero/openjourney-v4/main/README.md");
        }

        // ========= Lykon / DreamShaper =========
        if (id.contains("dreamshaper") || id.contains("lykon")) {
            out.add("https://raw.githubusercontent.com/Lykon/dreamshaper/main/README.md");
        }

        // ========= Realistic Vision (SG161222) =========
        if (id.contains("sg161222") || id.contains("realistic_vision")) {
            out.add("https://raw.githubusercontent.com/SG161222/Realistic_Vision_V6.0_B1_noVAE/main/README.md");
            out.add("https://raw.githubusercontent.com/SG161222/Realistic_Vision_V5.0/main/README.md");
        }

        // ========= Anything v4 =========
        if (id.contains("anything-v4") || id.contains("anythingv4") || id.contains("anything-v4.0") || id.contains("xyn-ai/anything-v4.0")) {
            out.add("https://raw.githubusercontent.com/andite/Anything-V4.0/main/README.md");
        }

        // ========= Waifu Diffusion =========
        if (id.contains("waifu-diffusion") || id.contains("hakurei/waifu")) {
            out.add("https://raw.githubusercontent.com/harubaru/waifu-diffusion/main/README.md");
        }

        // ========= Portrait+ =========
        if (id.contains("portraitplus") || id.contains("wavymulder")) {
            out.add("https://raw.githubusercontent.com/wavymulder/portraitplus/main/README.md");
        }

        // ========= Mo-Di Diffusion =========
        if (id.contains("mo-di") || id.contains("mo_di") || id.contains("nitrosocke/mo-di-diffusion")) {
            out.add("https://raw.githubusercontent.com/nitrosocke/mo-di-diffusion/main/README.md");
        }

        // ========= Stable Video Diffusion (Img2Vid) =========
        if (id.contains("stable-video")) {
            out.add("https://raw.githubusercontent.com/DavidLanz/stable-video-diffusion-img2vid/main/README.md");
            out.add("https://raw.githubusercontent.com/sagiodev/stable-video-diffusion-img2vid/main/README.md");
            out.add("https://raw.githubusercontent.com/garystafford/svd-examples/main/README.md");
            out.add("https://raw.githubusercontent.com/Stability-AI/generative-models/main/README.md");
        }

        // ========= Wan (Img2Vid / Animate) =========
        if (id.contains("wan2.2") || id.contains("wan2.1") || id.contains("wan-ai") || id.contains("wan2") || id.contains("wan")) {
            out.add("https://raw.githubusercontent.com/Wan-Video/Wan2.2/main/README.md");
            out.add("https://raw.githubusercontent.com/Wan-Video/Wan2.1/main/README.md");
        }

        // ========= EasyAnimate (Alibaba) =========
        if (id.contains("easyanimate") || id.contains("alibaba-pai")) {
            out.add("https://raw.githubusercontent.com/alibaba-pai/EasyAnimate/main/README.md");
            out.add("https://raw.githubusercontent.com/ali-vilab/EasyAnimate/main/README.md");
        }

        // ========= VideoCrafter2 =========
        if (id.contains("videocrafter2") || id.contains("videocrafter")) {
            out.add("https://raw.githubusercontent.com/AILab-CVC/VideoCrafter/main/README.md");
        }

        // ========= DynamiCrafter =========
        if (id.contains("dynamicrafter") || id.contains("doubiiu/dynamicrafter")) {
            out.add("https://raw.githubusercontent.com/Doubiiu/DynamiCrafter/main/README.md");
        }

        // ========= Cinemo =========
        if (id.contains("cinemo") || id.contains("maxin-cn/cinemo")) {
            out.add("https://raw.githubusercontent.com/maxin-cn/Cinemo/main/README.md");
        }

        // ========= Latte-1 =========
        if (id.contains("latte-1") || id.contains("latte") || id.contains("maxin-cn/latte-1")) {
            out.add("https://raw.githubusercontent.com/maxin-cn/Latte/main/README.md");
        }

        // ========= SEINE =========
        if (id.contains("seine") || id.contains("vchitect/seine")) {
            out.add("https://raw.githubusercontent.com/Vchitect/SEINE/main/README.md");
        }

        // ========= Coqui TTS / XTTS =========
        if (id.contains("coqui") || id.contains("xtts")) {
            out.add("https://raw.githubusercontent.com/coqui-ai/TTS/main/README.md");
        }

        // ========= Boson / Higgs Audio =========
        if (id.contains("boson") || id.contains("higgs")) {
            out.add("https://raw.githubusercontent.com/boson-ai/higgs-audio/main/README.md");
        }

        // ========= VALL-E-X =========
        if (id.contains("vall-e-x") || id.contains("valle") || id.contains("plachta/") || id.contains("plachtaa/")) {
            out.add("https://raw.githubusercontent.com/Plachtaa/VALL-E-X/main/README.md");
        }

        // ========= Bark (Suno) =========
        if (id.contains("bark") || id.contains("suno/bark")) {
            out.add("https://raw.githubusercontent.com/suno-ai/bark/main/README.md");
        }

        // ========= Tortoise-TTS =========
        if (id.contains("tortoise-tts") || id.contains("tortoise_tts") || id.contains("jbetker/tortoise-tts") || id.contains("neonbjb/tortoise-tts")) {
            out.add("https://raw.githubusercontent.com/neonbjb/tortoise-tts/main/README.md");
        }

        // ========= MMS-TTS (Facebook) =========
        if (id.contains("mms-tts") || id.contains("facebook/mms-tts") || id.contains("mms_tts")) {
            out.add("https://raw.githubusercontent.com/facebookresearch/fairseq/main/examples/mms/README.md");
        }

        // ========= YourTTS =========
        if (id.contains("yourtts") || id.contains("cshulby/yourtts")) {
            out.add("https://raw.githubusercontent.com/Edresson/YourTTS/main/README.md");
        }

        // ========= VibeVoice (Microsoft) =========
        if (id.contains("vibevoice") || id.contains("microsoft/vibevoice")) {
            // قد لا يتوفر ريبو رسمي بهذا الاسم، نضيف رابطًا متوقعًا + فالباك إلى VALL-E-X
            out.add("https://raw.githubusercontent.com/microsoft/VibeVoice/main/README.md");
            out.add("https://raw.githubusercontent.com/microsoft/LM-Voice/main/README.md");
            out.add("https://raw.githubusercontent.com/microsoft/VALL-E-X/main/README.md");
        }

        // ========= VITS / ESPnet =========
        if (id.contains("vits") || id.contains("espnet") || id.contains("kan-bayashi")) {
            out.add("https://raw.githubusercontent.com/espnet/espnet/main/README.md");
        }

        // ========= Mozilla TTS =========
        if (id.contains("mozilla/tts") || id.contains("mozilla tts")) {
            out.add("https://raw.githubusercontent.com/mozilla/TTS/main/README.md");
        }

        return out;
    }

    /**
     * Helper Method
     * يدمج رابط الـ HF + روابط GitHub
     */
    public static List<String> getAllReadmeLinks(String modelId) {
        List<String> all = new ArrayList<String>();
        if (modelId == null) return all;

        // رئيسي من HuggingFace
        all.add(getHuggingFaceReadme(modelId));

        // الإضافي من GitHub
        all.addAll(getFallbackGithubUrls(modelId));
        return all;
    }
}
