package com.example.aiautocreate;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

public class DialogUtil {

    public interface DialogCallback {
        void onPositive();
        void onNegative();
    }

    public static AlertDialog showCustomDialog(final Activity activity, String title, String message,
                                               final DialogCallback callback, float dialogWidthRatio) {
        LayoutInflater li = activity.getLayoutInflater();
        View view = li.inflate(R.layout.dialog_custom, null);

        TextView tvTitle = (TextView) view.findViewById(R.id.dialog_title);
        TextView tvMsg = (TextView) view.findViewById(R.id.dialog_message);
        Button bPos = (Button) view.findViewById(R.id.dialog_positive);
        Button bNeg = (Button) view.findViewById(R.id.dialog_negative);

        if (tvTitle != null) tvTitle.setText(title != null ? title : "");
        if (tvMsg != null) tvMsg.setText(message != null ? message : "");

        final AlertDialog dlg = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(view)
            .create();

        dlg.setCanceledOnTouchOutside(true);

        dlg.setOnKeyListener(new DialogInterface.OnKeyListener() {
                public boolean onKey(android.content.DialogInterface dialogInterface, int keyCode, KeyEvent keyEvent) {
                    if (keyCode == KeyEvent.KEYCODE_BACK && keyEvent.getAction() == KeyEvent.ACTION_UP) {
                        try {
                            if (callback != null) callback.onNegative();
                        } catch (Exception e) { }
                        dlg.dismiss();
                        return true;
                    }
                    return false;
                }
            });

        bPos.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try { if (callback != null) callback.onPositive(); } catch (Exception e) {}
                    dlg.dismiss();
                }
            });

        bNeg.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    try { if (callback != null) callback.onNegative(); } catch (Exception e) {}
                    dlg.dismiss();
                }
            });

        dlg.show();
        if (dialogWidthRatio > 0f && dialogWidthRatio <= 1.0f) {
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int targetW = (int) (screenW * dialogWidthRatio);
            if (dlg.getWindow() != null) {
                dlg.getWindow().setLayout(targetW, WindowManager.LayoutParams.WRAP_CONTENT);
            }
        } else {
            if (dlg.getWindow() != null) {
                dlg.getWindow().setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            }
        }

        return dlg;
    }
}
