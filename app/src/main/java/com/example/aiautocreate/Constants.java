package com.example.aiautocreate;

/**
 * Constants - Ultra-Full Literal
 * بثوث وإضافات Intent قياسية للتطبيق
 */
public class Constants {

    // ==== Broadcast Actions ====
    public static final String ACTION_PROGRESS       = "com.example.aiautocreate.ACTION_PROGRESS";
    public static final String ACTION_FINISHED       = "com.example.aiautocreate.ACTION_FINISHED";
    public static final String ACTION_ERROR          = "com.example.aiautocreate.ACTION_ERROR";
    public static final String ACTION_MODELS_UPDATED = "com.example.aiautocreate.ACTION_MODELS_UPDATED";

    // ==== Intent Extras ====
    public static final String EXTRA_STAGE    = "extra_stage";    // اسم المرحلة
    public static final String EXTRA_MESSAGE  = "extra_message";  // رسالة
    public static final String EXTRA_OUTPUT   = "extra_output";   // مسار/نتيجة
    public static final String EXTRA_PROGRESS = "extra_progress"; // نسبة تقدم (int)

    private Constants(){}
}
