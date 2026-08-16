package cn.mhook.fragment;

import android.view.View;

public class GlassItem {

    public static final int TYPE_NAV = 0;     // 网格卡片 / 列表跳转项
    public static final int TYPE_TOGGLE = 1;  // 列表开关项

    public String name;
    public String subtitle;
    public int icon;
    public int accentColor;      // 图标/icon-box 主题色
    public String badge;         // 徽章文字，null 则无
    public int badgeColor;       // 徽章文字颜色
    public int type = TYPE_NAV;
    public View.OnClickListener onClick;

    public GlassItem(String name, String subtitle, int icon, int accentColor) {
        this(name, subtitle, icon, accentColor, null, 0, TYPE_NAV, null);
    }

    public GlassItem(String name, String subtitle, int icon, int accentColor,
                     String badge, int badgeColor, int type, View.OnClickListener onClick) {
        this.name = name;
        this.subtitle = subtitle;
        this.icon = icon;
        this.accentColor = accentColor;
        this.badge = badge;
        this.badgeColor = badgeColor;
        this.type = type;
        this.onClick = onClick;
    }
}
