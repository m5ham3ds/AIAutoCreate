package com.example.aiautocreate;

import java.util.ArrayList;
import java.util.List;

/**
 * AspectRatioUtils - Ultra-Full Literal
 * Java7 Compatible (AIDE Safe)
 *
 * - يحول Ratio مثل "16:9" أو "9:16" إلى عرض×ارتفاع مناسب
 * - يختار أقرب دقة قياسية من قائمة Presets
 * - يدعم دقات شائعة: HD / FHD / 2K / 4K / UltraWide / Square / Portrait
 * - يرجع Dimension (width,height,label) للاستخدام مع الصور والفيديوهات
 * - دوال مساعدة: getAllForAspect, getAllPresets, snapToMultipleOf8
 */
public class AspectRatioUtils {

    public static class Dimension {
        public int width;
        public int height;
        public String label;

        public Dimension(int w, int h, String l){
            this.width = w;
            this.height = h;
            this.label = l;
        }

        @Override
        public String toString(){
            return label + " ("+width+"x"+height+")";
        }
    }

    private static final List<Dimension> PRESETS = buildPresets();

    private static List<Dimension> buildPresets(){
        List<Dimension> list = new ArrayList<Dimension>();

        // Landscape (16:9)
        list.add(new Dimension(426,240,"240p 16:9"));
        list.add(new Dimension(640,360,"360p 16:9"));
        list.add(new Dimension(854,480,"480p SD 16:9"));
        list.add(new Dimension(1280,720,"720p HD 16:9"));
        list.add(new Dimension(1920,1080,"1080p FHD 16:9"));
        list.add(new Dimension(2560,1440,"1440p QHD 16:9"));
        list.add(new Dimension(3840,2160,"2160p 4K UHD 16:9"));
        list.add(new Dimension(7680,4320,"4320p 8K UHD 16:9"));

        // Portrait (9:16)
        list.add(new Dimension(240,426,"240p Portrait 9:16"));
        list.add(new Dimension(360,640,"360p Portrait 9:16"));
        list.add(new Dimension(480,854,"480p Portrait 9:16"));
        list.add(new Dimension(720,1280,"720p Portrait 9:16"));
        list.add(new Dimension(1080,1920,"1080p Portrait FHD 9:16"));
        list.add(new Dimension(2160,3840,"2160p Portrait 4K 9:16"));

        // Square (1:1)
        list.add(new Dimension(512,512,"512x512 Square 1:1"));
        list.add(new Dimension(1024,1024,"1024x1024 Square 1:1"));
        list.add(new Dimension(2048,2048,"2048x2048 HR Square 1:1"));

        // Classic 4:3
        list.add(new Dimension(640,480,"480p VGA 4:3"));
        list.add(new Dimension(1024,768,"XGA 4:3"));
        list.add(new Dimension(1440,1080,"1440×1080 4:3"));
        list.add(new Dimension(1600,1200,"UXGA 4:3"));

        // Cinematic 21:9 and UltraWide formats
        list.add(new Dimension(2560,1080,"UWHD 21:9"));
        list.add(new Dimension(3440,1440,"UWQHD 21:9"));
        list.add(new Dimension(5120,2160,"5K UltraWide 21:9"));

        return list;
    }

    public static Dimension parseAspect(String aspect){
        if(aspect == null || aspect.trim().length() == 0){
            return new Dimension(1280,720,"Default HD 16:9");
        }
        try{
            String[] parts = aspect.split(":");
            if(parts.length==2){
                int w = Integer.parseInt(parts[0].trim());
                int h = Integer.parseInt(parts[1].trim());
                if(w>0 && h>0){
                    double targetRatio = (double)w/(double)h;
                    return findClosestResolution(targetRatio);
                }
            }
        } catch(Exception e){
            // ignore & use default
        }
        return new Dimension(1280,720,"Default HD 16:9");
    }

    private static Dimension findClosestResolution(double targetRatio){
        Dimension best = PRESETS.get(0);
        double bestDiff = Math.abs(((double)best.width/(double)best.height)-targetRatio);

        for(int i=1;i<PRESETS.size();i++){
            Dimension d = PRESETS.get(i);
            double diff = Math.abs(((double)d.width/(double)d.height)-targetRatio);
            if(diff < bestDiff){
                bestDiff = diff;
                best = d;
            }
        }
        return best;
    }

    public static List<Dimension> getAllForAspect(String aspect){
        List<Dimension> list = new ArrayList<Dimension>();
        if(aspect==null || aspect.trim().length()==0) return list;

        try{
            String[] parts = aspect.split(":");
            if(parts.length==2){
                int w = Integer.parseInt(parts[0].trim());
                int h = Integer.parseInt(parts[1].trim());
                if(w>0 && h>0){
                    double targetRatio = (double)w/(double)h;
                    for(int i=0;i<PRESETS.size();i++){
                        Dimension d = PRESETS.get(i);
                        double ratio = (double)d.width/(double)d.height;
                        if(Math.abs(ratio-targetRatio)<0.01){
                            list.add(d);
                        }
                    }
                }
            }
        }catch(Exception e){
            // ignore
        }
        return list;
    }

    public static List<Dimension> getAllPresets(){
        return PRESETS;
    }

    // أداة مساعدة لضبط القيم على مضاعفات 8 (بعض الموديلات تتطلب ذلك)
    public static int snapToMultipleOf8(int v){
        if(v <= 0) return 8;
        return Math.max(8, (v/8)*8);
    }
}
