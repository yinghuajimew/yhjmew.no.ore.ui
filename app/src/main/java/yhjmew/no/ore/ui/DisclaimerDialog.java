package yhjmew.no.ore.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.CountDownTimer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
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
 * <p>三道门槛，缺一不可：
 * <ol>
 *   <li>正文可见，必须停留 {@link #READ_SECONDS} 秒；</li>
 *   <li>手打一遍确认文字，逐字比对，不许粘贴；</li>
 *   <li>主动勾「不再展示」才豁免以后。</li>
 * </ol>
 *
 * <p>确认文字为什么画成图片：只要它以普通文本出现在界面上，
 * Android 10+ 的「万能复制」就能把它抠走，长按也能选。
 * 画进 Bitmap 之后，用户只能照着屏幕上看到的逐字敲，或者用语音输入 ——
 * 这正是这一关想要的效果：让人真的动一次手，而不是无脑点过去。
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
     * @param onAccepted 读满、打对确认文字、点了确定之后才执行；取消则不执行
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

        // 要求逐字输入的短语。只用于比对和画图，**绝不**放进可显示的 TextView
        final String phrase = activity.getString(R.string.msg_disclaimer_phrase).trim();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        // ---- 标题 ----
        TextView tvTitle = new TextView(activity);
        tvTitle.setText(activity.getString(R.string.title_disclaimer));
        tvTitle.setTextSize(17f);
        tvTitle.setTextColor(Color.BLACK);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, (int) (10 * dp));
        root.addView(tvTitle);

        // ---- 正文 ----
        TextView body = new TextView(activity);
        body.setText(activity.getString(R.string.msg_disclaimer_body));
        body.setTextSize(13f);
        body.setTextColor(Color.parseColor("#333333"));
        body.setLineSpacing((int) (3 * dp), 1f);
        root.addView(body);

        // ---- 确认区 ----
        TextView tvConfirmTitle = new TextView(activity);
        tvConfirmTitle.setText(activity.getString(R.string.title_disclaimer_confirm));
        tvConfirmTitle.setTextSize(14f);
        tvConfirmTitle.setTextColor(Color.BLACK);
        tvConfirmTitle.setTypeface(null, Typeface.BOLD);
        tvConfirmTitle.setPadding(0, (int) (16 * dp), 0, (int) (4 * dp));
        root.addView(tvConfirmTitle);

        TextView tvConfirmHint = new TextView(activity);
        tvConfirmHint.setText(activity.getString(R.string.msg_disclaimer_confirm_hint));
        tvConfirmHint.setTextSize(11f);
        tvConfirmHint.setTextColor(Color.parseColor("#888888"));
        tvConfirmHint.setPadding(0, 0, 0, (int) (6 * dp));
        root.addView(tvConfirmHint);

        // 要求输入的短语：画成图片，界面上没有可复制/可选的文本
        root.addView(buildPhraseImage(activity, phrase, dp));

        final EditText etConfirm = buildConfirmField(activity, dp);
        root.addView(etConfirm);

        final TextView tvError = new TextView(activity);
        tvError.setTextSize(11f);
        tvError.setTextColor(Color.parseColor("#C62828"));
        tvError.setPadding(0, (int) (6 * dp), 0, 0);
        tvError.setVisibility(View.GONE);
        root.addView(tvError);

        // ---- 倒计时提示 ----
        final TextView tvCountdown = new TextView(activity);
        tvCountdown.setTextSize(12f);
        tvCountdown.setTextColor(Color.parseColor("#C62828"));
        tvCountdown.setGravity(Gravity.CENTER);
        tvCountdown.setPadding(0, (int) (12 * dp), 0, 0);
        root.addView(tvCountdown);

        // ---- 「不再展示」----
        final android.widget.CheckBox cbDontShow = new android.widget.CheckBox(activity);
        cbDontShow.setText(activity.getString(R.string.btn_dont_show_again));
        cbDontShow.setTextSize(13f);
        cbDontShow.setTextColor(Color.parseColor("#333333"));
        cbDontShow.setPadding(0, (int) (8 * dp), 0, 0);
        root.addView(cbDontShow);

        // 整块内容放进一个可滚动容器：正文加确认区在小屏上会超出预算，
        // 让用户能滚到输入框，而不是被截断。
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(scroll)
                .setPositiveButton(activity.getString(R.string.btn_agree_continue), null)
                .setNegativeButton(activity.getString(R.string.btn_cancel), null)
                .setCancelable(false)
                .create();

        // 按钮是否已解除倒计时锁定。输入内容单独校验，见下面 onClick。
        final boolean[] countdownDone = {false};

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                final Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                positive.setEnabled(false);
                showCountdown(activity, tvCountdown, READ_SECONDS);

                final CountDownTimer timer = new CountDownTimer(READ_SECONDS * 1000L, 1000L) {
                    @Override
                    public void onTick(long millisUntilFinished) {
                        int left = (int) Math.ceil(millisUntilFinished / 1000.0);
                        showCountdown(activity, tvCountdown, left);
                    }

                    @Override
                    public void onFinish() {
                        countdownDone[0] = true;
                        tvCountdown.setText(activity.getString(R.string.msg_disclaimer_ready));
                        tvCountdown.setTextColor(Color.parseColor("#2E7D32"));
                        positive.setEnabled(true);
                    }
                };
                timer.start();

                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface di) {
                        // 别让计时器在对话框消失后还往已经回收的 View 上写字
                        timer.cancel();
                    }
                });
            }
        });

        // 边输边给反馈：输对了就把红字收起来，但按钮仍要等倒计时
        etConfirm.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (matchesPhrase(s.toString().trim(), phrase)) {
                    tvError.setVisibility(View.GONE);
                }
            }
        });

        dialog.show();

        // show() 之后才能拿到按钮实例。替换掉默认监听，
        // 否则点一下直接 dismiss，"读满 10 秒 + 手打确认" 等于没有。
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!countdownDone[0]) {
                            return;
                        }

                        String typed = etConfirm.getText().toString().trim();
                        if (typed.length() == 0) {
                            tvError.setText(activity.getString(R.string.err_disclaimer_need_input));
                            tvError.setVisibility(View.VISIBLE);
                            return;
                        }
                        if (!matchesPhrase(typed, phrase)) {
                            // 逐字比对，全角半角、空格、标点都算错
                            tvError.setText(activity.getString(R.string.err_disclaimer_mismatch));
                            tvError.setVisibility(View.VISIBLE);
                            return;
                        }
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

    /**
     * 输入是否和要求逐字一致。
     *
     * <p>只去掉首尾空白（{@link String#trim()} 只管 U+0020 及以下，
     * 全角空格照样算错），中间一个字符都不容忍：多一个字、少一个字、
     * 全角半角不同、标点不同，全部判为不通过。
     *
     * <p>单独抽出来是为了能脱离 Android 跑测试 —— 这条规则一旦写歪，
     * 用户要么永远过不去，要么随便糊一片也能过，两种都很糟。
     */
    static boolean matchesPhrase(String typed, String phrase) {
        if (typed == null || phrase == null) {
            return false;
        }
        return typed.trim().equals(phrase.trim());
    }

    /**
     * 确认文字的输入框，贴/剪贴板一律不认。
     *
     * <p>两道拦截，都作用在「用户主动粘贴」这个动作上：
     * <ol>
     *   <li>长按不弹选择菜单（{@code setLongClickable(false)}）；</li>
     *   <li>{@link android.widget.EditText#onTextContextMenuItem} 是所有上下文菜单动作的
     *       唯一入口，粘贴在这里被直接吃掉；选择菜单里的「粘贴」项也一并摘掉。</li>
     * </ol>
     *
     * <p>**不做**「提交时比对剪贴板」这种检查：剪贴板里存的是内容，不是来源。
     * 用户手打、语音输入、手写之后，只要剪贴板里恰好也是这句话（之前复制过、
     * 输入法缓存过），就会被误判成粘贴 —— 正常人怎么都过不去。
     */
    private static EditText buildConfirmField(Activity activity, float dp) {
        EditText et = new EditText(activity) {
            @Override
            public boolean onTextContextMenuItem(int id) {
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                    // 返回 true 表示"已处理"，实际什么都不做
                    return true;
                }
                return super.onTextContextMenuItem(id);
            }
        };

        et.setTextSize(15f);
        et.setSingleLine(true);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        et.setHint(activity.getString(R.string.hint_disclaimer_confirm));
        et.setLongClickable(false);
        et.setPadding((int) (10 * dp), (int) (8 * dp), (int) (10 * dp), (int) (8 * dp));
        et.setBackgroundResource(R.drawable.bg_black_border);
        et.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 保险：从选择菜单里也把「粘贴」摘掉
        et.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                removePaste(menu);
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }
        });
        return et;
    }

    private static void removePaste(Menu menu) {
        for (int i = menu.size() - 1; i >= 0; i--) {
            int id = menu.getItem(i).getItemId();
            if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                menu.removeItem(id);
            }
        }
    }

    /**
     * 把需要手打的短语画成位图。
     *
     * <p>不用 TextView：Android 10+ 的「万能复制」能直接从屏幕上抠文字，
     * 画成图之后就没法一键复制了，只能照着敲或语音输入。
     */
    private static ImageView buildPhraseImage(Activity activity, String phrase, float dp) {
        float textSize = 24 * dp;
        float margin = 10 * dp;

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(textSize);
        paint.setColor(Color.BLACK);

        Paint.FontMetrics fm = paint.getFontMetrics();
        int w = Math.max(1, (int) Math.ceil(paint.measureText(phrase) + margin * 2));
        int h = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent + margin * 2));

        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        canvas.drawText(phrase, margin, margin - fm.ascent, paint);

        ImageView iv = new ImageView(activity);
        iv.setImageBitmap(bm);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        // 别把短语本身写进 contentDescription —— 无障碍服务能把它读出来，
        // 等于又把文本交回去了
        iv.setContentDescription(activity.getString(R.string.msg_disclaimer_phrase_desc));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = (int) (4 * dp);
        lp.bottomMargin = (int) (10 * dp);
        iv.setLayoutParams(lp);
        return iv;
    }

    private static void showCountdown(Activity activity, TextView tv, int left) {
        if (left > 0) {
            tv.setText(activity.getString(R.string.msg_disclaimer_countdown, left));
            tv.setTextColor(Color.parseColor("#C62828"));
        }
    }
}
