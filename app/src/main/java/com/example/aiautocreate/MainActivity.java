package com.example.aiautocreate;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.widget.DrawerLayout;
import android.support.v4.content.ContextCompat;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.ScaleAnimation;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MainActivity - Ultra-Full Literal
 * Java7 / AIDE Compatible
 *
 * - متكامل مع PrefManager لمسارات Android 14
 * - يطلب صلاحيات MANAGE_EXTERNAL_STORAGE على Android R+
 * - يعرض حالة الشبكة
 * - يدير التنقّل بين الصفحات (Fragments)
 * - يتعامل مع البثوث القادمة من Pipeline (Progress/Finished/Error/ModelsUpdated)
 */
public class MainActivity extends FragmentActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_WRITE_STORAGE = 1001;

    private DrawerLayout drawer;
    private TextView tvConnStatus, tvTitle;
    private AlertDialog activeDialog = null;
    private Fragment homeFragment, testFragment, ffmpegFragment, currentFragment;
    private ImageButton bHomeBtn, bTestBtn, bFfmpegBtn, btnHamb;
    private BroadcastReceiver pipelineReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final PrefManager pm = new PrefManager(this);
        // Bootstrap/تهيئة الافتراضيات والمجلدات
        try { pm.bootstrapDefaults(); } catch (Throwable t) {
            pm.ensureDefaultDirsExist();
        }
        Log.i(TAG, "BaseDir = " + pm.getScriptsPath());

        // Crash handler: سجل الستاك في ملف داخل /ERRORS
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        String trace = Log.getStackTraceString(e);
                        String time = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
                        File errDir = new File(pm.getErrorsPath());
                        if (!errDir.exists()) errDir.mkdirs();
                        File outFile = new File(errDir, "crash_" + time + ".txt");
                        FileOutputStream fos = new FileOutputStream(outFile, true);
                        fos.write(("Crash time: " + time + "\nThread: " + t.getName() + "\n\n").getBytes("UTF-8"));
                        fos.write(trace.getBytes("UTF-8"));
                        fos.close();
                        Log.e(TAG, "Crash written to " + outFile.getAbsolutePath());
                    } catch (Throwable ignored) {}
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(2);
                }
            });

        try {
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Exception ignored) {}

        setContentView(R.layout.activity_main);

        // طلب صلاحيات التخزين بناءً على نسخة النظام
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
            }
        } else {
            checkManageAllFilesPermission();
        }

        // وصل عناصر الواجهة
        drawer       = (DrawerLayout) findViewById(R.id.drawer_layout);
        tvConnStatus = (TextView) findViewById(R.id.tv_conn_status);
        tvTitle      = (TextView) findViewById(R.id.tv_title);
        bHomeBtn     = (ImageButton) findViewById(R.id.nav_home);
        bTestBtn     = (ImageButton) findViewById(R.id.nav_test);
        bFfmpegBtn   = (ImageButton) findViewById(R.id.nav_ffmpeg);
        btnHamb      = (ImageButton) findViewById(R.id.btn_hamburger);

        // إعداد الـ Fragments
        final FragmentManager fm = getSupportFragmentManager();
        homeFragment   = initFragment(fm, "FRAG_HOME", new HomeFragment());
        testFragment   = initFragment(fm, "FRAG_TEST", new TestFragment());
        ffmpegFragment = initFragment(fm, "FRAG_FFMPEG", new FFmpegFragment());
        fm.executePendingTransactions();
        showFragment("FRAG_HOME");

        setupNavButtons();

        // زر Settings من القائمة الجانبية
        View drawerBtn = findViewById(R.id.drawer_settings_btn);
        if (drawerBtn != null) {
            drawerBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        if (drawer != null) drawer.closeDrawer(Gravity.RIGHT);
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                });
        }

        // زر الهامبورجر لفتح/إغلاق القائمة الجانبية
        if (btnHamb != null) {
            btnHamb.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        toggleDrawer();
                    }
                });
        }

        updateConnectionStatusUI();

        // Receiver للبثوث (Progress/Finished/Error/ModelsUpdated)
        pipelineReceiver = new PipelineBroadcastReceiver();
    }

    // ✨ طلب Manage All Files Access (Android 11+)
    private void checkManageAllFilesPermission(){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if(!android.os.Environment.isExternalStorageManager()){
                    Toast.makeText(this,"⚠ يلزم منح إذن الوصول الكامل للملفات",Toast.LENGTH_LONG).show();
                    try {
                        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    } catch (Exception e) {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivity(i);
                        } catch (Exception ignored){}
                    }
                }
            } catch(Exception ignored){}
        }
    }

    private void toggleDrawer(){
        if (drawer == null) return;
        if (drawer.isDrawerOpen(Gravity.RIGHT)) drawer.closeDrawer(Gravity.RIGHT);
        else drawer.openDrawer(Gravity.RIGHT);
    }

    // =================== BroadcastReceiver ===================
    private class PipelineBroadcastReceiver extends BroadcastReceiver {
        public void onReceive(Context c, Intent it) {
            try {
                String action = it.getAction();
                // ندعم كلا المسارين: PipelineService.* و Constants.*
                boolean isProgress = "com.example.aiautocreate.ACTION_PROGRESS".equals(action) ||
                    equalsSafe(action, safeAction("ACTION_PROGRESS"));
                boolean isFinished = "com.example.aiautocreate.ACTION_FINISHED".equals(action) ||
                    equalsSafe(action, safeAction("ACTION_FINISHED"));
                boolean isError    = "com.example.aiautocreate.ACTION_ERROR".equals(action) ||
                    equalsSafe(action, safeAction("ACTION_ERROR"));
                boolean isModels   = Constants.ACTION_MODELS_UPDATED.equals(action);

                if (isProgress || isFinished || isError) {
                    String stage = it.getStringExtra(safeExtra("EXTRA_STAGE"));
                    if (stage == null) stage = it.getStringExtra(Constants.EXTRA_STAGE);
                    String msg   = it.getStringExtra(safeExtra("EXTRA_MESSAGE"));
                    if (msg == null) msg = it.getStringExtra(Constants.EXTRA_MESSAGE);

                    if (currentFragment instanceof HomeFragment) {
                        if (isError) ((HomeFragment) currentFragment).onError(stage, msg);
                        else ((HomeFragment) currentFragment).onStageProgress(stage, msg);
                    }
                    if (currentFragment instanceof TestFragment) {
                        ((TestFragment) currentFragment).showFinalResultFor(stage, msg, it);
                    }
                }

                if (isFinished) {
                    String out = it.getStringExtra(Constants.EXTRA_OUTPUT);
                    Log.i(TAG, "Pipeline finished => " + out);
                }

                if (isModels) {
                    Toast.makeText(MainActivity.this, "تم تحديث قوائم النماذج", Toast.LENGTH_SHORT).show();
                }

            } catch (Exception ex) {
                Log.e(TAG,"Broadcast handle error: "+ex.getMessage());
            }
        }

        private boolean equalsSafe(String a, String b){ return a!=null && b!=null && a.equals(b); }
        private String safeAction(String name){
            try{
                // محاولة الوصول إلى PipelineService.ACTION_*
                Class cls = Class.forName("com.example.aiautocreate.PipelineService");
                return (String) cls.getField(name).get(null);
            }catch(Throwable t){ return null; }
        }
        private String safeExtra(String name){
            try{
                Class cls = Class.forName("com.example.aiautocreate.PipelineService");
                return (String) cls.getField(name).get(null);
            }catch(Throwable t){ return null; }
        }
    }

    // =================== Helpers ===================
    private Fragment initFragment(FragmentManager fm, String tag, Fragment frag) {
        Fragment existing = fm.findFragmentByTag(tag);
        if (existing == null) {
            fm.beginTransaction().add(R.id.fragment_container, frag, tag)
                .hide(frag).commit();
            return frag;
        }
        return existing;
    }

    private void setupNavButtons() {
        if (bHomeBtn != null) bHomeBtn.setOnClickListener(new View.OnClickListener(){
                    public void onClick(View v){ animateNavButton(v); showFragment("FRAG_HOME"); }});
        if (bTestBtn != null) bTestBtn.setOnClickListener(new View.OnClickListener(){
                    public void onClick(View v){ animateNavButton(v); showFragment("FRAG_TEST"); }});
        if (bFfmpegBtn != null) bFfmpegBtn.setOnClickListener(new View.OnClickListener(){
                    public void onClick(View v){ animateNavButton(v); showFragment("FRAG_FFMPEG"); }});
    }

    private void showFragment(String tag) {
        FragmentManager fm = getSupportFragmentManager();
        Fragment fHome   = fm.findFragmentByTag("FRAG_HOME");
        Fragment fTest   = fm.findFragmentByTag("FRAG_TEST");
        Fragment fFfmpeg = fm.findFragmentByTag("FRAG_FFMPEG");
        Fragment toShow = null;

        if ("FRAG_HOME".equals(tag)) toShow = fHome;
        else if ("FRAG_TEST".equals(tag)) toShow = fTest;
        else if ("FRAG_FFMPEG".equals(tag)) toShow = fFfmpeg;

        if(toShow==null) return;

        android.support.v4.app.FragmentTransaction tx = fm.beginTransaction();
        tx.setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out);
        if (fHome != null) tx.hide(fHome);
        if (fTest != null) tx.hide(fTest);
        if (fFfmpeg != null) tx.hide(fFfmpeg);
        tx.show(toShow).commit();
        currentFragment = toShow;

        try {
            if (toShow instanceof HomeFragment) tvTitle.setText("الصفحة الرئيسية");
            else if (toShow instanceof TestFragment) tvTitle.setText("صفحة التجربة");
            else if (toShow instanceof FFmpegFragment) tvTitle.setText("إعدادات المونتاج");
        } catch (Exception ignored){}

        updateConnectionStatusUI();
    }

    private void animateNavButton(View v){
        if(v==null)return;
        ScaleAnimation sa=new ScaleAnimation(1f,0.9f,1f,0.9f,
                                             ScaleAnimation.RELATIVE_TO_SELF,0.5f,ScaleAnimation.RELATIVE_TO_SELF,0.5f);
        sa.setDuration(120); sa.setRepeatCount(1); sa.setRepeatMode(ScaleAnimation.REVERSE);
        v.startAnimation(sa);
    }

    // =================== Network ===================
    public boolean isNetworkConnected(){
        try {
            ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if(cm==null)return false;
            NetworkInfo ni=cm.getActiveNetworkInfo();
            return ni!=null && ni.isConnected();
        } catch(Exception e){ return false; }
    }

    public void updateConnectionStatusUI(){
        if(tvConnStatus==null)return;
        if(isNetworkConnected()){
            tvConnStatus.setText("متصل بالإنترنت");
            tvConnStatus.setBackgroundColor(getResources().getColor(R.color.connection_connected_color));
        } else {
            tvConnStatus.setText("غير متصل بالإنترنت");
            tvConnStatus.setBackgroundColor(getResources().getColor(R.color.connection_disconnected_color));
        }
        tvConnStatus.setVisibility(View.VISIBLE);
    }

    public boolean ensureNetworkBeforeStart(){
        if(isNetworkConnected()) return true;
        Toast.makeText(this,"⚠ غير متصل بالإنترنت",Toast.LENGTH_LONG).show();
        updateConnectionStatusUI();
        return false;
    }

    // =================== Lifecycle ===================
    @Override
    protected void onResume(){
        super.onResume();
        updateConnectionStatusUI();
        try{
            // سجّل لكل من Constants.* وأي PipelineService.* إن وُجد
            registerReceiver(pipelineReceiver,new IntentFilter(Constants.ACTION_PROGRESS));
            registerReceiver(pipelineReceiver,new IntentFilter(Constants.ACTION_FINISHED));
            registerReceiver(pipelineReceiver,new IntentFilter(Constants.ACTION_ERROR));
            registerReceiver(pipelineReceiver,new IntentFilter(Constants.ACTION_MODELS_UPDATED));

            // أيضاً جرّب السلاسل المباشرة (لو البث يصدر بها)
            registerReceiver(pipelineReceiver,new IntentFilter("com.example.aiautocreate.ACTION_PROGRESS"));
            registerReceiver(pipelineReceiver,new IntentFilter("com.example.aiautocreate.ACTION_FINISHED"));
            registerReceiver(pipelineReceiver,new IntentFilter("com.example.aiautocreate.ACTION_ERROR"));
        }catch(Exception e){}
    }

    @Override
    protected void onPause(){
        try{ unregisterReceiver(pipelineReceiver);}catch(Exception e){}
        super.onPause();
    }

    @Override
    public void onBackPressed(){
        if(drawer!=null && drawer.isDrawerOpen(Gravity.RIGHT)){ drawer.closeDrawer(Gravity.RIGHT); return;}
        if(activeDialog!=null && activeDialog.isShowing()){ activeDialog.dismiss(); return;}
        if(currentFragment!=null && !(currentFragment instanceof HomeFragment)){ showFragment("FRAG_HOME"); return; }
        showExitConfirmation();
    }

    private void showExitConfirmation(){
        AlertDialog.Builder b=new AlertDialog.Builder(this);
        b.setTitle("تأكيد الخروج");
        b.setMessage("هل تريد الخروج من التطبيق؟");
        b.setPositiveButton("نعم", new DialogInterface.OnClickListener(){
                public void onClick(DialogInterface d,int w){ finish(); }});
        b.setNegativeButton("لا", new DialogInterface.OnClickListener(){
                public void onClick(DialogInterface d,int w){ d.dismiss(); }});
        activeDialog=b.create(); activeDialog.show();
    }

    // ===== Permissions callback =====
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults){
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQ_WRITE_STORAGE){
            if(grantResults != null && grantResults.length>0 && grantResults[0] == PackageManager.PERMISSION_GRANTED){
                Toast.makeText(this,"تم منح إذن التخزين",Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,"⚠ لم يتم منح إذن التخزين - قد لا تعمل الحفظ/القراءة",Toast.LENGTH_LONG).show();
            }
        }
    }
}
