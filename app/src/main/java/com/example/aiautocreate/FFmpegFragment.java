package com.example.aiautocreate;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.ScaleAnimation;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * FFmpegFragment - Ultra-Full Literal (Enhanced)
 * Java7/AIDE Compatible
 *
 * - إعدادات الوقت + الجودة + الأبعاد + نماذج إضافية
 * - كل إعداد محفوظ/مسترجع حسب Style
 * - SharedPreferences: ffmpeg_prefs
 * - دمج PrefManager لقيم النماذج الافتراضية + المسارات
 */
public class FFmpegFragment extends Fragment {

    private static final String TAG = "FFmpegFragment";
    private static final String PREFS_NAME = "ffmpeg_prefs";

    // UI Elements
    private Spinner spStyle, spMinutes, spSeconds, spAspect, spQuality;
    private Spinner spMaster, spReviewer, spSmart, spMasterOrch;
    private Spinner spAudio, spVisual, spTransitions, spSubtitle, spMusic;
    private Switch swMaster, swReviewer, swSmart, swMasterOrch;
    private Switch swAudio, swVisual, swTransitions, swSubtitles, swMusic;
    private Button btnSave;

    private PrefManager prefManager;

    public FFmpegFragment() {}
    public static FFmpegFragment newInstance() { return new FFmpegFragment(); }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_ffmpeg, container, false);

        // init PrefManager + تأكيد المسارات
        prefManager = new PrefManager(getContext());
        prefManager.ensureDefaultDirsExist();
        Log.i(TAG,"BaseDir = "+ new File(prefManager.getBaseDirPath()).getAbsolutePath());

        // find views
        spStyle   = safeFind(root,R.id.spinner_style);
        spMinutes = safeFind(root,R.id.spinner_minutes);
        spSeconds = safeFind(root,R.id.spinner_seconds);
        spAspect  = safeFind(root,R.id.spinner_aspect_ratio);
        spQuality = safeFind(root,R.id.spinner_video_quality);

        spMaster = safeFind(root,R.id.spinner_model_master);
        swMaster = safeFind(root,R.id.switch_model_master);

        spReviewer = safeFind(root,R.id.spinner_model_reviewer);
        swReviewer = safeFind(root,R.id.switch_reviewer_enabled);

        spSmart = safeFind(root,R.id.spinner_smart_count);
        swSmart = safeFind(root,R.id.switch_smartcount_enabled);

        spMasterOrch = safeFind(root,R.id.spinner_master_orchestrator);
        swMasterOrch = safeFind(root,R.id.switch_master_orchestrator_enabled);

        spAudio = safeFind(root,R.id.spinner_audio_effects);
        swAudio = safeFind(root,R.id.switch_audio_enabled);

        spVisual = safeFind(root,R.id.spinner_visual_effects);
        swVisual = safeFind(root,R.id.switch_visual_enabled);

        spTransitions = safeFind(root,R.id.spinner_transitions);
        swTransitions = safeFind(root,R.id.switch_transitions_enabled);

        spSubtitle = safeFind(root,R.id.spinner_model_subtitle);
        swSubtitles = safeFind(root,R.id.switch_subtitles);

        spMusic = safeFind(root,R.id.spinner_model_music_bg);
        swMusic = safeFind(root,R.id.switch_music);

        btnSave = safeFind(root, R.id.btn_save_ffmpeg);

        // setup spinners/adapters
        setupAdapters();
        initSwitchBehavior();

        // اضبط الـ Style حسب آخر اختيار محفوظ (إن وُجد) ثم حمّل البروفايل
        applyLastSelectedStyleOrDefault();
        loadProfile();

        // عند تغيير الـ Style → حمّل بروفايله مباشرة
        if(spStyle!=null){
            spStyle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        loadProfile();
                    }
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
        }

        if(btnSave!=null){
            btnSave.setOnClickListener(new View.OnClickListener(){
                    public void onClick(View v){
                        animateButtonPress(v);
                        saveProfile();
                        Toast.makeText(getContext(),"✔ تم حفظ إعدادات النمط الحالي",Toast.LENGTH_SHORT).show();
                        try{
                            // إعلام بقية الواجهة أن الإعدادات تغيّرت (اختياري)
                            getActivity().sendBroadcast(new android.content.Intent(Constants.ACTION_MODELS_UPDATED)
                                                        .putExtra(Constants.EXTRA_STAGE,"ffmpeg_profile")
                                                        .putExtra(Constants.EXTRA_MESSAGE,"تم حفظ ملف نمط FFmpeg"));
                        }catch(Exception ignored){}
                    }
                });
        }

        return root;
    }

    // ========== Setup Adapters ==========
    private void setupAdapters(){
        if(getActivity()==null) return;

        // Styles
        setAdapter(spStyle,new String[]{"قصص وروايات","حماسي وجذاب","احترافية وأنيق","مخصص"});

        // وقت الفيديو
        setAdapter(spMinutes,buildRange(0,10));
        setAdapter(spSeconds,buildRange(0,59));

        // نسب الأبعاد الشائعة
        setAdapter(spAspect,new String[]{"16:9","9:16","1:1","4:3","21:9"});

        // الجودة (حروف صغيرة 2k/4k لتتوافق مع PipelineManager.computeTargetSize)
        setAdapter(spQuality,new String[]{"480p","720p","1080p","2k","4k"});

        // نماذج إضافية: دمج قيم PrefManager الافتراضية + بدائل
        List<String> masterList = new ArrayList<String>();
        addUnique(masterList, prefManager.getOrchestrator());
        addUnique(masterList, "huggingface/ffmpeg-orch-small");
        addUnique(masterList, "huggingface/ffmpeg-orch-base");
        addUnique(masterList, "huggingface/ffmpeg-orch-pro");
        setAdapter(spMaster, masterList);

        List<String> reviewerList = new ArrayList<String>();
        addUnique(reviewerList, prefManager.getReviewer());
        addUnique(reviewerList, "huggingface/reviewer-small");
        addUnique(reviewerList, "huggingface/reviewer-base");
        addUnique(reviewerList, "huggingface/reviewer-large");
        setAdapter(spReviewer, reviewerList);

        List<String> smartList = new ArrayList<String>();
        addUnique(smartList, prefManager.getSmartCount());
        addUnique(smartList, "huggingface/smartcount-small");
        addUnique(smartList, "huggingface/smartcount-base");
        addUnique(smartList, "huggingface/smartcount-large");
        setAdapter(spSmart, smartList);

        List<String> orchList = new ArrayList<String>();
        addUnique(orchList, prefManager.getOrchestrator());
        addUnique(orchList, "orchestrator/v1-small");
        addUnique(orchList, "orchestrator/v1-balanced");
        addUnique(orchList, "orchestrator/v1-highquality");
        setAdapter(spMasterOrch, orchList);

        // تأثيرات (أمثلة عامة – يمكن تخصيصها لاحقًا)
        setAdapter(spAudio,new String[]{"audio-fx-small","audio-fx-base","audio-fx-pro"});
        setAdapter(spVisual,new String[]{"visual-fx-small","visual-fx-base","visual-fx-pro"});
        setAdapter(spTransitions,new String[]{"transition-fade","transition-cut","transition-zoom"});
        setAdapter(spSubtitle,new String[]{"asr-small","asr-base","asr-pro"});
        setAdapter(spMusic,new String[]{"music-bg-small","music-bg-base","music-bg-pro"});
    }

    private void setAdapter(Spinner sp,String[] arr){
        if(sp==null||getActivity()==null) return;
        ArrayAdapter<String> ad=new ArrayAdapter<String>(getActivity(),android.R.layout.simple_spinner_item,arr);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
    }

    private void setAdapter(Spinner sp,List<String> list){
        if(sp==null||getActivity()==null) return;
        ArrayAdapter<String> ad=new ArrayAdapter<String>(getActivity(),android.R.layout.simple_spinner_item,list);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
    }

    private List<String> buildRange(int from,int to){
        List<String> out=new ArrayList<String>();
        for(int i=from;i<=to;i++){
            if(i<10){ out.add("0"+i); } else { out.add(""+i); }
        }
        return out;
    }

    private void addUnique(List<String> list, String v){
        if(list==null||v==null) return;
        String t=v.trim();
        if(t.length()==0) return;
        if(!list.contains(t)) list.add(t);
    }

    // ========== Switch Binding ==========
    private void initSwitchBehavior(){
        bindSwitch(swMaster,spMaster);
        bindSwitch(swReviewer,spReviewer);
        bindSwitch(swSmart,spSmart);
        bindSwitch(swMasterOrch,spMasterOrch);
        bindSwitch(swAudio,spAudio);
        bindSwitch(swVisual,spVisual);
        bindSwitch(swTransitions,spTransitions);
        bindSwitch(swSubtitles,spSubtitle);
        bindSwitch(swMusic,spMusic);
    }

    private void bindSwitch(final Switch sw,final Spinner sp){
        if(sw==null||sp==null) return;
        sp.setEnabled(sw.isChecked());
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener(){
                public void onCheckedChanged(CompoundButton b,boolean c){ sp.setEnabled(c);}
            });
    }

    // ========== Save/Load Profiles ==========
    private void saveProfile(){
        Context ctx=getContext(); if(ctx==null) return;
        SharedPreferences prefs=ctx.getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE);
        SharedPreferences.Editor ed=prefs.edit();

        String style=getVal(spStyle,"قصص وروايات");
        String prefix="profile_"+style+"_";

        // حفظ آخر style لاستخدامه في HomeFragment
        ed.putString("last_selected_style", style);

        ed.putString(prefix+"minutes",getVal(spMinutes,"00"));
        ed.putString(prefix+"seconds",getVal(spSeconds,"30"));
        ed.putString(prefix+"aspect",getVal(spAspect,"16:9"));
        ed.putString(prefix+"quality",getVal(spQuality,"1080p"));

        saveSwitchSpinner(ed,prefix,"master",swMaster,spMaster);
        saveSwitchSpinner(ed,prefix,"reviewer",swReviewer,spReviewer);
        saveSwitchSpinner(ed,prefix,"smart",swSmart,spSmart);
        saveSwitchSpinner(ed,prefix,"orch",swMasterOrch,spMasterOrch);
        saveSwitchSpinner(ed,prefix,"audio",swAudio,spAudio);
        saveSwitchSpinner(ed,prefix,"visual",swVisual,spVisual);
        saveSwitchSpinner(ed,prefix,"trans",swTransitions,spTransitions);
        saveSwitchSpinner(ed,prefix,"sub",swSubtitles,spSubtitle);
        saveSwitchSpinner(ed,prefix,"music",swMusic,spMusic);

        ed.apply();

        Log.i(TAG,"✔ Profile saved for "+style+" | Quality="+getVal(spQuality,"1080p")+" | Aspect="+getVal(spAspect,"16:9"));
    }

    private void loadProfile(){
        Context ctx=getContext(); if(ctx==null) return;
        SharedPreferences prefs=ctx.getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE);

        String style=getVal(spStyle,"قصص وروايات");
        String prefix="profile_"+style+"_";

        selectVal(spMinutes,prefs.getString(prefix+"minutes","00"));
        selectVal(spSeconds,prefs.getString(prefix+"seconds","30"));
        selectVal(spAspect,prefs.getString(prefix+"aspect","16:9"));
        selectVal(spQuality,prefs.getString(prefix+"quality","1080p"));

        loadSwitchSpinner(prefs,prefix,"master",swMaster,spMaster);
        loadSwitchSpinner(prefs,prefix,"reviewer",swReviewer,spReviewer);
        loadSwitchSpinner(prefs,prefix,"smart",swSmart,spSmart);
        loadSwitchSpinner(prefs,prefix,"orch",swMasterOrch,spMasterOrch);
        loadSwitchSpinner(prefs,prefix,"audio",swAudio,spAudio);
        loadSwitchSpinner(prefs,prefix,"visual",swVisual,spVisual);
        loadSwitchSpinner(prefs,prefix,"trans",swTransitions,spTransitions);
        loadSwitchSpinner(prefs,prefix,"sub",swSubtitles,spSubtitle);
        loadSwitchSpinner(prefs,prefix,"music",swMusic,spMusic);
    }

    private void saveSwitchSpinner(SharedPreferences.Editor ed,String prefix,String key,Switch sw,Spinner sp){
        ed.putBoolean(prefix+key+"_on",sw!=null && sw.isChecked());
        if(sw!=null && sw.isChecked() && sp!=null){ ed.putString(prefix+key,getVal(sp,"")); }
    }

    private void loadSwitchSpinner(SharedPreferences prefs,String prefix,String key,Switch sw,Spinner sp){
        boolean on=prefs.getBoolean(prefix+key+"_on",false);
        if(sw!=null) sw.setChecked(on);
        String val=prefs.getString(prefix+key,"");
        selectVal(sp,val);
        if(sp!=null) sp.setEnabled(on);
    }

    // ========== Utils ==========
    private String getVal(Spinner sp,String def){
        if(sp==null||sp.getSelectedItem()==null) return def;
        String v=sp.getSelectedItem().toString();
        if(v==null||v.trim().length()==0) return def;
        return v.trim();
    }

    private void selectVal(Spinner sp,String v){
        if(sp==null||v==null) return;
        for(int i=0;i<sp.getCount();i++){
            Object o=sp.getItemAtPosition(i);
            if(o!=null && v.equals(o.toString())){ sp.setSelection(i); return; }
        }
    }

    private void applyLastSelectedStyleOrDefault(){
        try{
            Context ctx=getContext(); if(ctx==null) return;
            SharedPreferences prefs=ctx.getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE);
            String last = prefs.getString("last_selected_style","قصص وروايات");
            selectVal(spStyle,last);
        }catch(Exception ignored){}
    }

    private void animateButtonPress(View v){
        if(v==null) return;
        ScaleAnimation sa=new ScaleAnimation(1f,0.92f,1f,0.92f,
                                             ScaleAnimation.RELATIVE_TO_SELF,0.5f,
                                             ScaleAnimation.RELATIVE_TO_SELF,0.5f);
        sa.setDuration(120); sa.setRepeatCount(1); sa.setRepeatMode(ScaleAnimation.REVERSE);
        v.startAnimation(sa);
    }

    @SuppressWarnings("unchecked")
    private <T extends View> T safeFind(View root,int id){
        if(root==null) return null;
        View v=root.findViewById(id);
        return (v!=null)? (T)v : null;
    }
}
