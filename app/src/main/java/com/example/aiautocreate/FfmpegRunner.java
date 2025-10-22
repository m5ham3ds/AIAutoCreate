package com.example.aiautocreate;

import android.util.Log;

import com.arthenica.ffmpegkit.Statistics;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * FfmpegRunner - Ultra Full Literal
 * - Wrapper لتشغيل أوامر FFmpegKit بمرونة عالية (Java7/AIDE)
 * - يستخدم Reflection للتوافق مع إصدارات متعددة من FFmpegKit
 * - يدعم:
 *   • executeSync(cmd) → int (0 نجاح)
 *   • executeAsync(cmd, listener) → مع تقدم وإكمال
 *   • cancelLastSafely() / cancelAllSafely()
 */
public class FfmpegRunner {
    private static final String TAG = "FfmpegRunner";

    // Listener interface للتقدم+الإنهاء
    public interface FfmpegListener {
        void onProgress(Statistics statistics);
        void onComplete(int returnCode);
    }

    // نحتفظ بآخر Session حتى نتمكن من إلغائها عند الحاجة
    private static volatile Object sLastSession = null;

    // ==================== فحوصات أساسية ====================
    public static boolean isAvailable(){
        try{
            Class.forName("com.arthenica.ffmpegkit.FFmpegKit");
            return true;
        }catch(Throwable t){ return false; }
    }

    // ==================== تنفيذ متزامن ====================
    public static int executeSync(final String cmd){
        if(cmd == null || cmd.trim().length()==0){
            return -1;
        }
        try{
            Class<?> ffkClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit");
            Method exec = null;

            // تفضيل execute(String)
            try{ exec = ffkClass.getMethod("execute", String.class); }catch(NoSuchMethodException ignored){}
            if(exec == null){
                // بعض الإصدارات قد تقدم executeAsync فقط - في هذه الحالة سنرجع -1 وننصح باستخدام async
                Log.w(TAG,"No sync execute(String) method on FFmpegKit");
                return -1;
            }
            Object session = exec.invoke(null, cmd);
            sLastSession = session; // سجل آخر جلسة
            return parseReturnCodeFromSession(session);
        }catch(Throwable t){
            Log.e(TAG,"executeSync error: "+getStack(t));
            return -1;
        }
    }

    // ==================== تنفيذ غير متزامن ====================
    public static void executeAsync(final String cmd, final FfmpegListener listener) {
        if (cmd == null || cmd.trim().length()==0) {
            if (listener != null) listener.onComplete(-1);
            return;
        }

        try {
            // 1) حاول التنفيذ المتزامن (اختياري) كـ fallback سريع
            try {
                Class<?> ffkClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit");
                Method syncExec;
                try { syncExec = ffkClass.getMethod("execute", String.class); }
                catch (NoSuchMethodException e) { syncExec = null; }
                if (syncExec != null) {
                    Object session = syncExec.invoke(null, cmd);
                    sLastSession = session;
                    int rc = parseReturnCodeFromSession(session);
                    if (listener != null) listener.onComplete(rc);
                    return;
                }
            } catch (ClassNotFoundException cnf) {
                Log.w(TAG, "FFmpegKit class not found for sync exec: " + cnf.getMessage());
            }

            // 2) جرّب executeAsync بتواقيع مختلفة:
            //    - executeAsync(String, ExecuteCallback)
            //    - executeAsync(String, ExecuteCallback, LogCallback, StatisticsCallback)
            //    - executeAsync(String, LogCallback, StatisticsCallback)
            //    - executeAsync(String, ... callbacks ...)
            try {
                Class<?> ffkClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit");
                Method[] methods = ffkClass.getMethods();
                Method execAsync = null;

                for (int i = 0; i < methods.length; i++) {
                    Method m = methods[i];
                    if ("executeAsync".equals(m.getName())) {
                        Class<?>[] pts = m.getParameterTypes();
                        if (pts != null && pts.length >= 1 && pts[0] == String.class) {
                            execAsync = m;
                            break;
                        }
                    }
                }

                if (execAsync != null) {
                    Class<?>[] params = execAsync.getParameterTypes();
                    Object[] args = new Object[params.length];
                    args[0] = cmd;

                    for (int i = 1; i < params.length; i++) {
                        Class<?> ptype = params[i];
                        if (ptype.isInterface()) {
                            // ننشئ Proxy لكل Interface (ExecuteCallback/LogCallback/StatisticsCallback...)
                            final Class<?> cbInterface = ptype;
                            Object proxy = Proxy.newProxyInstance(
                                cbInterface.getClassLoader(),
                                new Class<?>[]{cbInterface},
                                new InvocationHandler() {
                                    public Object invoke(Object proxyObj, Method method, Object[] methodArgs) {
                                        try {
                                            // نحاول التعرف على نوع الاستدعاء من اسم/نوع الوسيطات
                                            String mname = method.getName().toLowerCase();
                                            if (methodArgs != null && methodArgs.length >= 1 && methodArgs[0] != null) {
                                                Object a0 = methodArgs[0];
                                                String cls = a0.getClass().getName().toLowerCase();

                                                // (1) Progress: Statistics
                                                if (cls.indexOf("statistics") >= 0) {
                                                    try {
                                                        if (listener != null) {
                                                            listener.onProgress((Statistics) a0);
                                                        }
                                                    } catch (ClassCastException cce) {
                                                        Log.w(TAG, "Statistics cast failed: " + cce.getMessage());
                                                    }
                                                    return null;
                                                }

                                                // (2) Session complete callback: Session
                                                if (cls.indexOf("session") >= 0) {
                                                    sLastSession = a0;
                                                    int rc = parseReturnCodeFromSession(a0);
                                                    if (listener != null) listener.onComplete(rc);
                                                    return null;
                                                }

                                                // (3) ReturnCode callback
                                                if (cls.indexOf("returncode") >= 0) {
                                                    int rc = parseReturnCodeFromReturnCodeObj(a0);
                                                    if (listener != null) listener.onComplete(rc);
                                                    return null;
                                                }

                                                // (4) Log callback (اختياري) - لا نُبلغ المستمع هنا
                                                if (cls.indexOf("log") >= 0) {
                                                    return null;
                                                }
                                            }

                                            // بعض التواقيع قد ترسل الـ Session في الوسيط الثاني/الثالث
                                            if (methodArgs != null) {
                                                for (int k = 0; k < methodArgs.length; k++) {
                                                    Object ak = methodArgs[k];
                                                    if (ak == null) continue;
                                                    String name = ak.getClass().getName().toLowerCase();
                                                    if (name.indexOf("session") >= 0) {
                                                        sLastSession = ak;
                                                        int rc = parseReturnCodeFromSession(ak);
                                                        if (listener != null) listener.onComplete(rc);
                                                        break;
                                                    }
                                                    if (name.indexOf("statistics") >= 0 && listener != null) {
                                                        try { listener.onProgress((Statistics) ak); } catch (Throwable ignored){}
                                                    }
                                                }
                                            }
                                        } catch (Throwable t) {
                                            Log.w(TAG, "callback proxy error: " + getStack(t));
                                        }
                                        return null;
                                    }
                                });
                            args[i] = proxy;
                        } else {
                            // غير واجهة: مرر null بشكل آمن
                            args[i] = null;
                        }
                    }

                    Object ret = execAsync.invoke(null, args);

                    // بعض الإصدارات تُرجِع session من executeAsync → سجِّله إن وُجد
                    if (ret != null) {
                        try {
                            String n = ret.getClass().getName().toLowerCase();
                            if (n.indexOf("session") >= 0) sLastSession = ret;
                        } catch (Throwable ignored){}
                    }

                    return;
                } else {
                    Log.w(TAG, "executeAsync method not found on FFmpegKit");
                }
            } catch (ClassNotFoundException cnf2) {
                Log.w(TAG, "FFmpegKit not present for async attempt: " + cnf2.getMessage());
            }

            // لو فشلنا = Complete برمز خطأ
            if (listener != null) listener.onComplete(-1);
        } catch (Throwable t) {
            Log.e(TAG, "executeAsync error: " + getStack(t));
            if (listener != null) listener.onComplete(-1);
        }
    }

    // ==================== إلغاء الجلسات ====================
    public static void cancelAllSafely(){
        try{
            Class<?> ffkClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit");
            // توجد طريقتان شائعتان:
            // 1) FFmpegKit.cancel()
            // 2) FFmpegKit.cancel(long sessionId) أو cancel(Session)
            try{
                Method cancelAll = ffkClass.getMethod("cancel");
                cancelAll.invoke(null);
                Log.i(TAG,"FFmpegKit.cancel() invoked");
                return;
            }catch(NoSuchMethodException ignored){}

            // محاولة إلغاء آخر جلسة
            cancelLastSafely();
        }catch(Throwable t){
            Log.w(TAG,"cancelAllSafely failed: "+t.getMessage());
        }
    }

    public static void cancelLastSafely(){
        Object sess = sLastSession;
        if(sess == null){
            Log.w(TAG,"No last session to cancel");
            return;
        }
        try{
            // 1) الجرّب session.cancel()
            try{
                Method m = sess.getClass().getMethod("cancel");
                m.invoke(sess);
                Log.i(TAG,"session.cancel() invoked");
                return;
            }catch(NoSuchMethodException ignored){}

            // 2) FFmpegKit.cancel(session) أو cancel(long sessionId)
            try{
                Class<?> ffkClass = Class.forName("com.arthenica.ffmpegkit.FFmpegKit");

                // cancel(Session)
                try{
                    Method cancelWithSession = ffkClass.getMethod("cancel", sess.getClass());
                    cancelWithSession.invoke(null, sess);
                    Log.i(TAG,"FFmpegKit.cancel(session) invoked");
                    return;
                }catch(NoSuchMethodException ignored){}

                // cancel(long sessionId) إذا وُجد getSessionId()
                try{
                    Method getId = sess.getClass().getMethod("getSessionId");
                    Object idObj = getId.invoke(sess);
                    if(idObj instanceof Long){
                        Method cancelWithId = ffkClass.getMethod("cancel", long.class);
                        cancelWithId.invoke(null, ((Long)idObj).longValue());
                        Log.i(TAG,"FFmpegKit.cancel(sessionId) invoked");
                        return;
                    }
                }catch(NoSuchMethodException ignored){}
            }catch(Throwable t2){
                Log.w(TAG,"FFmpegKit.cancel with reflection failed: "+t2.getMessage());
            }
        }catch(Throwable t){
            Log.w(TAG,"cancelLastSafely error: "+t.getMessage());
        }
    }

    // ===================== Helpers =====================
    private static int parseReturnCodeFromSession(Object session) {
        if (session == null) return -1;
        try {
            // try session.getReturnCode()
            Method getReturnCode = null;
            try { getReturnCode = session.getClass().getMethod("getReturnCode"); }
            catch (NoSuchMethodException e) { getReturnCode = null; }

            if (getReturnCode != null) {
                Object rcObj = getReturnCode.invoke(session);
                int fromRc = parseReturnCodeFromReturnCodeObj(rcObj);
                if(fromRc != Integer.MIN_VALUE) return fromRc;
            }

            // fallback: session.getState() == COMPLETED && session.getFailStackTrace()==null ?
            try{
                Method getFail = session.getClass().getMethod("getFailStackTrace");
                Object fs = getFail.invoke(session);
                if(fs == null) return 0;
                return -1;
            }catch(Throwable ignored){}

        } catch (Throwable t) {
            Log.w(TAG, "parseReturnCodeFromSession failed: " + t.getMessage());
        }
        return -1;
    }

    private static int parseReturnCodeFromReturnCodeObj(Object rcObj){
        if (rcObj == null) return Integer.MIN_VALUE;
        try {
            // new API: isValueSuccess() + getValue()
            try{
                Method isValueSuccess = rcObj.getClass().getMethod("isValueSuccess");
                Object success = isValueSuccess.invoke(rcObj);
                if (success instanceof Boolean && ((Boolean)success).booleanValue()) return 0;
            }catch(NoSuchMethodException ignored){}

            try{
                Method getValue = rcObj.getClass().getMethod("getValue");
                Object val = getValue.invoke(rcObj);
                if (val instanceof Integer) return ((Integer)val).intValue();
                if (val instanceof Long)    return (int)((Long)val).longValue();
            }catch(NoSuchMethodException ignored){}

            // old API: toString() → رقم
            try {
                Method toStr = rcObj.getClass().getMethod("toString");
                Object ts = toStr.invoke(rcObj);
                if (ts != null) {
                    try { return Integer.parseInt(ts.toString()); }catch(Exception ignore){}
                }
            } catch (Exception ignore) {}
        } catch (Throwable t){
            Log.w(TAG,"parseReturnCodeFromReturnCodeObj failed: "+t.getMessage());
        }
        return -1;
    }

    private static String getStack(Throwable t) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            t.printStackTrace(pw);
            return sw.toString();
        } catch (Throwable ignore) {
            return (t!=null)? t.getMessage() : "";
        }
    }
}
