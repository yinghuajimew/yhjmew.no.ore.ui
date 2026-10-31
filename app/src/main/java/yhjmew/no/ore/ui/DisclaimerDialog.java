package yhjmew.no.ore.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.CountDownTimer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 一键打包前的免责说明。
 *
 * <p>为什么要拦这一下：这东西会改写并重新签名一个几百 MB 的安装包，
 * 输出直接覆盖同目录下的同名文件；换了签名还必须先卸载原版，而卸载会删掉应用数据。
 * 存档丢了是找不回来的 —— 事后无论怎么解释都说不清。
 *
 * <p>所以：完整正文可见、必须读满 {@link #READ_SECONDS} 秒才能点确定，
 * 并且给定一个「不再展示」。倒计时是为了防止一路狂点把提示点过去，
 * 不是为了罚站 —— 10 秒刚好够把正文扫一遍。
 */
final class DisclaimerDialog {

    /** 必须停留的秒数 */
    private static final int READ_SECONDS = 10;

    /** 「不再展示」记在这个偏好里 */
    private static final String PREFS = "DisclaimerConfig";
    private static final String KEY_ACCEPTED = "accepted";

    private DisclaimerDialog() {
    }

    /** 用户之前勾过「不再展示」吗 */
    static boolean isAccepted(Activity activity) {
        return prefs(activity).getBoolean(KEY_ACCEPTED, false);
    }

    private static SharedPreferences prefs(Activity activity) {
        return activity.getApplicationContext()
                .getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    /**
     * 需要时弹一次。
     *
     * @param onAccepted 读满、点了确定之后才执行；用户取消或返回则不执行
     */
    static void showIfNeeded(final Activity activity, final Runnable onAccepted) {
        if (isAccepted(activity)) {
            onAccepted.run();
            return;
        }
        show(activity, onAccepted);
    }

    /** 无条件弹（设置里「重新查看」用） */
    static void show(final Activity activity, final Runnable onAccepted) {
        final float dp = activity.getResources().getDisplayMetrics().density;
        final int pad = (int) (20 * dp);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // ---- 标题 ----
        TextView tvTitle = new TextView(activity);
        tvTitle.setText(activity.getString(R.string.title_disclaimer));
        tvTitle.setTextSize(17f);
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, (int) (10 * dp));
        root.addView(tvTitle);

        // ---- 正文（可滚动，保证不会被截断）----
        TextView body = new TextView(activity);
        body.setText(activity.getString(R.string.msg_disclaimer_body));
        body.setTextSize(13f);
        body.setTextColor(Color.parseColor("#333333"));
        body.setLineSpacing((int) (3 * dp), 1f);

        ScrollView scroll = new ScrollView(activity);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.addView(body);
        root.addView(scroll);

        // ---- 倒计时提示 ----
        final TextView tvCountdown = new TextView(activity);
        tvCountdown.setTextSize(12f);
        tvCountdown.setTextColor(Color.parseColor("#C62828"));
        tvCountdown.setGravity(Gravity.CENTER);
        tvCountdown.setPadding(0, (int) (10 * dp), 0, 0);
        root.addView(tvCountdown);

        // ---- 「不再展示」----
        final android.widget.CheckBox cbDontShow = new android.widget.CheckBox(activity);
        cbDontShow.setText(activity.getString(R.string.btn_dont_show_again));
        cbDontShow.setTextSize(13f);
        cbDontShow.setTextColor(Color.parseColor("#333333"));
        cbDontShow.setPadding(0, (int) (8 * dp), 0, 0);
        root.addView(cbDontShow);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(root)
                .setPositiveButton(activity.getString(R.string.btn_agree_continue), null)
                .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                .setCancelable(false)
                .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                final Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positive.setEnabled(false);
                updateCountdown(activity, tvCountdown, positive, READ_SECONDS);

                // 先亮一次全秒数，再逐秒往下走
                final CountDownTimer timer = new CountDownTimer(READ_SECONDS * 1000L, 1000L) {
                    @Override
                    public void onTick(long millisUntilFinished) {
                        int left = (int) Math.ceil(millisUntilFinished / 1000.0);
                        updateCountdown(activity, tvCountdown, positive, left);
                    }

                    @Override
                    public void onFinish() {
                        tvCountdown.setText(activity.getString(R.string.msg_disclaimer_ready));
                        tvCountdown.setTextColor(Color.parseColor("#2E7D32"));
                        positive.setEnabled(true);
                    }
                };
                timer.start();
                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface di) {
                        // 别让计时器在对话框消失后还往已回收的 View 上写字
                        timer.cancel();
                    }
                });
            }
        });

        dialog.show();

        // show() 之后才能拿到按钮实例。这里替换掉 AlertDialog 给的默认监听，
        // 否则点一下就直接 dismiss 了，"必须读满 10 秒"等于没有。
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (cbDontShow.isChecked()) {
                            prefs(activity).edit().putBoolean(KEY_ACCEPTED, true).apply();
                        }
                        dialog.dismiss();
                        if (onAccepted != null) {
                            onAccepted.run();
                        }
                    }
                });
    }

    private static void updateCountdown(Activity activity, TextView tv, Button positive, int left) {
        if (left > 0) {
            positive.setEnabled(false);
            tv.setText(activity.getString(R.string.msg_disclaimer_countdown, left));
            tv.setTextColor(Color.parseColor("#C62828"));
        }
    }
}
