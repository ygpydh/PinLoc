package com.pinloc.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 现代风格 UI 工具（Material 化：圆角卡片 / 胶囊按钮 / 统一主题色 / 波纹反馈）。
 * 全部纯代码 + 少量 drawable，保持模块零依赖、体积最小。
 */
public final class UiKit {

    public static final int PRIMARY = 0xFF1A73E8;
    public static final int GREEN = 0xFF34A853;
    public static final int RED = 0xFFE05353;
    public static final int BG = 0xFFF4F6FA;
    public static final int CARD = 0xFFFFFFFF;
    public static final int TEXT = 0xFF202124;
    public static final int SUBTEXT = 0xFF5F6368;
    public static final int DIVIDER = 0xFFE8EAED;

    private static final int RIPPLE_TINT = 0x33FFFFFF;

    private UiKit() {
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 圆角矩形背景 */
    public static GradientDrawable shape(Context c, int radiusDp, int color, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, radiusDp));
        g.setColor(color);
        if (strokeDp > 0) {
            g.setStroke(dp(c, strokeDp), strokeColor);
        }
        return g;
    }

    /** 圆角 + 波纹反馈（可点击控件背景） */
    public static Drawable rippleBg(Context c, int radiusDp, int color) {
        GradientDrawable content = shape(c, radiusDp, color, 0, Color.TRANSPARENT);
        GradientDrawable mask = shape(c, radiusDp, Color.WHITE, 0, Color.TRANSPARENT);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE_TINT), content, mask);
    }

    /** 输入框背景：常态浅灰圆角，聚焦蓝色描边 */
    public static Drawable inputBg(Context c) {
        GradientDrawable normal = shape(c, 12, 0xFFF1F3F4, 1, DIVIDER);
        GradientDrawable focus = shape(c, 12, CARD, 2, PRIMARY);
        StateListDrawable sld = new StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_focused}, focus);
        sld.addState(new int[]{}, normal);
        return sld;
    }

    /** 胶囊按钮（主色填充 / 幽灵白底，文字 + 波纹） */
    public static Button btn(Context c, String text, int bgColor, int textColor) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextColor(textColor);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        b.setBackground(rippleBg(c, 22, bgColor));
        return b;
    }

    /** 主色胶囊按钮 */
    public static Button primaryBtn(Context c, String text) {
        return btn(c, text, PRIMARY, Color.WHITE);
    }

    /** 幽灵胶囊按钮（白底 + 主题色文字 + 细边） */
    public static Button ghostBtn(Context c, String text) {
        Button b = btn(c, text, CARD, PRIMARY);
        b.setBackground(rippleBg(c, 22, CARD));
        b.setElevation(dp(c, 2));
        return b;
    }

    /** 危险胶囊按钮（红底白字） */
    public static Button dangerBtn(Context c, String text) {
        return btn(c, text, RED, Color.WHITE);
    }

    /** 状态芯片：半透明深色圆角小标签 */
    public static TextView chip(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Color.WHITE);
        t.setPadding(dp(c, 10), dp(c, 5), dp(c, 10), dp(c, 5));
        t.setBackground(shape(c, 16, 0x99000000, 0, Color.TRANSPARENT));
        t.setElevation(dp(c, 1));
        return t;
    }

    /** 圆角输入框 */
    public static EditText input(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        e.setBackground(inputBg(c));
        return e;
    }

    /** 卡片容器（白底圆角 + 细边 + 阴影） */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(shape(c, 16, CARD, 1, DIVIDER));
        l.setElevation(dp(c, 2));
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return l;
    }

    /** 设置页分组标题 */
    public static TextView groupLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(SUBTEXT);
        t.setPadding(dp(c, 4), dp(c, 14), dp(c, 4), dp(c, 6));
        return t;
    }

    /** 页面大标题 */
    public static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(20);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setTextColor(TEXT);
        return t;
    }

    /** 底部导航按钮（图标 + 文字） */
    public static Button tab(Context c, String text, int iconRes) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(0, dp(c, 5), 0, dp(c, 3));
        b.setCompoundDrawablePadding(dp(c, 2));
        b.setBackground(null);
        Drawable icon = c.getDrawable(iconRes);
        if (icon != null) {
            b.setCompoundDrawablesRelative(null, icon, null, null);
        }
        setTabState(b, false);
        return b;
    }

    /** 切换底部导航选中态（图标 + 文字同色） */
    public static void setTabState(Button b, boolean selected) {
        b.setTextColor(selected ? PRIMARY : SUBTEXT);
        b.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        Drawable[] ds = b.getCompoundDrawables();
        if (ds[1] != null) {
            ds[1].setTint(selected ? PRIMARY : 0xFF9AA0A6);
        }
    }

    /** 圆形图标按钮（地图页右侧操作列用） */
    public static Button iconBtn(Context c, String icon, int bgColor, int textColor) {
        Button b = new Button(c);
        b.setText(icon);
        b.setTextSize(16);
        b.setTextColor(textColor);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(0, 0, 0, 0);
        b.setBackground(rippleBg(c, 22, bgColor));
        return b;
    }

    /** 通用波纹前景（FAB 等圆形按钮的点击反馈） */
    public static Drawable rippleOverlay() {
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null);
    }
}
