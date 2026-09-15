package com.pinloc.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * PinLoc 主界面：osmdroid 地图 + 搜索 + 收藏 + 模拟开关。
 */
public class MainActivity extends Activity {

    private static final String APP_VERSION = "1.0.0";
    private static final String PREFS = "pinloc_prefs";
    private static final String PREF_AMAP_KEY = "amap_web_key";
    private static final String PREF_FAVS = "fav_list";
    private static final String PREF_COORD_ORDER = "coord_order"; // "lonlat" or "latlon"
    private static final String PREF_ALTITUDE = "camouflage_altitude";
    private static final String PREF_ACCURACY = "camouflage_accuracy";
    private static final String PREF_SPEED = "camouflage_speed";
    private static final String PREF_BEARING = "camouflage_bearing";
    private static final String PREF_SATELLITES = "camouflage_satellites";
    private static final String PREF_MAX_CN0 = "camouflage_max_cn0";
    private static final String PREF_MEAN_CN0 = "camouflage_mean_cn0";

    private static final double DEFAULT_LAT = 39.9042;
    private static final double DEFAULT_LON = 116.4074;

    // 浅色浮层 + 黑字（浅色底图上夜间也清晰）
    private static final int PANEL_BG = 0xF2FFFFFF;
    private static final int PANEL_CARD = 0xFFF4F5F7;
    private static final int INPUT_BG = 0xFFE8EAED;
    private static final int ACCENT = 0xFF007C91;
    private static final int TEXT_PRIMARY = 0xFF1A1A1A;
    private static final int TEXT_SECONDARY = 0xFF5C5C5C;

    // ---- UI 字段 ----
    private MapView mapView;
    private Marker mapMarker;
    private EditText searchInput;
    private TextView searchClearBtn;
    private int searchSeq = 0;
    private LinearLayout searchResultBox;
    private View searchResultLayer;
    private ImageView locateBtn;
    private View bottomPanel;
    private TextView moduleStatusChip;
    private TextView mapStatusChip;
    private TextView coordText;
    private TextView statusText;
    private ImageView playBtn;
    private TextView coordOrderBtn;
    private boolean coordOrderLonLat = true; // true=经度在前, false=纬度在前

    // ---- 状态 ----
    private double currentLat = DEFAULT_LAT;
    private double currentLon = DEFAULT_LON;
    private boolean uiSimulating = false;
    private String cartoApiKey = "";
    private Dialog favDialog;
    private LinearLayout favDialogList;
    private final Timer timer = new Timer();

    /** 搜索结果（名称+地址+坐标） */
    private static final class SearchResult {
        final String name;
        final String address;
        final double lat;
        final double lon;
        SearchResult(String name, String address, double lat, double lon) {
            this.name = name; this.address = address; this.lat = lat; this.lon = lon;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().hide();
        applyImmersiveBars();
        // 先读取 Carto key（必须在 setupMapView 之前）
        cartoApiKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString("carto_api_key", "");
        // 初始化 osmdroid 配置：User-Agent + 私有缓存目录（不需要存储权限）
        try {
            org.osmdroid.config.Configuration.getInstance().setUserAgentValue("PinLoc/1.0");
            java.io.File tileCacheDir = new java.io.File(getCacheDir(), "osmdroid_tiles");
            if (!tileCacheDir.exists()) tileCacheDir.mkdirs();
            org.osmdroid.config.Configuration.getInstance().setOsmdroidTileCache(tileCacheDir);
        } catch (Exception ignored) {}
        setContentView(buildUi());
        setupMapView();
        coordOrderLonLat = !"latlon".equals(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_COORD_ORDER, "lonlat"));
        updateCoordText();
        refreshState();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> refreshState());
            }
        }, 2000L, 2000L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) {
            try { mapView.onResume(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onPause() {
        if (mapView != null) {
            try { mapView.onPause(); } catch (Exception ignored) {}
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        timer.cancel();
        if (mapView != null) {
            try { mapView.onDetach(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    // ================= UI 构建 =================

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);

        // 1. 地图（全屏底层）
        mapView = new MapView(this);
        mapView.setMultiTouchControls(true);
        mapView.setBuiltInZoomControls(false);
        root.addView(mapView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // 2. 顶部列：搜索胶囊 + 结果 + 状态芯片（结果展开时芯片下移，不互相覆盖）
        LinearLayout topCol = new LinearLayout(this);
        topCol.setOrientation(LinearLayout.VERTICAL);

        View searchBar = buildSearchBar();
        searchBar.setClickable(true);
        topCol.addView(searchBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, searchBarHeight()));

        searchResultBox = new LinearLayout(this);
        searchResultBox.setOrientation(LinearLayout.VERTICAL);
        searchResultBox.setBackground(darkRounded(16, PANEL_CARD));
        searchResultBox.setElevation(dp(8));
        blockTouches(searchResultBox);
        searchResultLayer = wrapWithMaxHeight(searchResultBox,
                Math.min(dp(280), Math.max(dp(72), (int) (screenH() * 0.32f))));
        searchResultLayer.setVisibility(View.GONE);
        LinearLayout.LayoutParams resultLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        resultLp.topMargin = dp(8);
        topCol.addView(searchResultLayer, resultLp);

        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.VERTICAL);
        chips.setClickable(true);
        moduleStatusChip = darkChip("● 模块检测中", 0xFFFFC107);
        mapStatusChip = darkChip("● 地图服务可用", 0xFF4CAF50);
        chips.addView(moduleStatusChip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.topMargin = dp(8);
        chips.addView(mapStatusChip, cLp);
        LinearLayout.LayoutParams chipsInCol = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chipsInCol.topMargin = dp(8);
        topCol.addView(chips, chipsInCol);

        root.addView(topCol, searchBarLp());

        // 5. 定位按钮：圆形 + 矢量十字，贴屏幕垂直居中
        locateBtn = new ImageView(this);
        locateBtn.setImageResource(R.drawable.ic_my_location);
        locateBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        locateBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        locateBtn.setBackground(darkCircle(48, PANEL_BG));
        locateBtn.setElevation(dp(6));
        locateBtn.setContentDescription("回到当前位置");
        locateBtn.setOnClickListener(v -> {
            // 回到真实位置
            try {
                android.location.LocationManager lm = (android.location.LocationManager)
                        getSystemService(LOCATION_SERVICE);
                boolean hasFine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                boolean hasCoarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (!hasFine && !hasCoarse) {
                    // 请求定位权限
                    requestPermissions(new String[]{
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                    }, 1001);
                    statusText.setText("请授予定位权限后重试");
                    return;
                }
                // 先尝试缓存位置
                android.location.Location loc = null;
                for (String provider : new String[]{
                        android.location.LocationManager.GPS_PROVIDER,
                        android.location.LocationManager.NETWORK_PROVIDER,
                        android.location.LocationManager.PASSIVE_PROVIDER}) {
                    try {
                        loc = lm.getLastKnownLocation(provider);
                        if (loc != null) break;
                    } catch (Exception ignored) {}
                }
                if (loc != null) {
                    currentLat = loc.getLatitude();
                    currentLon = loc.getLongitude();
                    flyTo(currentLat, currentLon);
                    statusText.setText("已回到真实位置");
                } else {
                    // 缓存为空，主动请求一次位置更新
                    statusText.setText("正在获取真实位置...");
                    final android.location.LocationListener listener = new android.location.LocationListener() {
                        @Override
                        public void onLocationChanged(android.location.Location location) {
                            currentLat = location.getLatitude();
                            currentLon = location.getLongitude();
                            flyTo(currentLat, currentLon);
                            statusText.setText("已回到真实位置");
                            try { lm.removeUpdates(this); } catch (Exception ignored) {}
                        }
                        @Override public void onProviderDisabled(String provider) {}
                        @Override public void onProviderEnabled(String provider) {}
                        @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}
                    };
                    String provider = hasFine ? android.location.LocationManager.GPS_PROVIDER : android.location.LocationManager.NETWORK_PROVIDER;
                    try {
                        lm.requestSingleUpdate(provider, listener, getMainLooper());
                    } catch (Exception e) {
                        // GPS 不可用时换 network
                        try {
                            lm.requestSingleUpdate(android.location.LocationManager.NETWORK_PROVIDER, listener, getMainLooper());
                        } catch (Exception e2) {
                            statusText.setText("无法获取真实位置: " + e2.getMessage());
                        }
                    }
                    // 10秒超时
                    new android.os.Handler(getMainLooper()).postDelayed(() -> {
                        try { lm.removeUpdates(listener); } catch (Exception ignored) {}
                        if (statusText.getText().toString().contains("正在获取")) {
                            statusText.setText("获取位置超时，请检查定位设置");
                        }
                    }, 10000);
                }
            } catch (Exception e) {
                statusText.setText("定位失败: " + e.getMessage());
            }
        });
        int locateSize = dp(48);
        FrameLayout.LayoutParams locateLp = new FrameLayout.LayoutParams(
                locateSize, locateSize, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        locateLp.rightMargin = dp(16);
        root.addView(locateBtn, locateLp);

        // 6. 底部大圆角控制面板（整块吃触摸，空白不穿透到地图）
        bottomPanel = buildBottomPanel();
        root.addView(bottomPanel, bottomPanelLp());

        return root;
    }

    private View buildSearchBar() {
        int barH = searchBarHeight();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(16), 0);
        bar.setBackground(darkRounded(28, PANEL_BG));
        bar.setElevation(dp(4));

        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setMaxLines(1);
        searchInput.setHint("搜索地点");
        searchInput.setHintTextColor(TEXT_SECONDARY);
        searchInput.setTextColor(TEXT_PRIMARY);
        searchInput.setTextSize(compactUi() ? 14 : 16);
        searchInput.setBackground(null);
        searchInput.setPadding(0, 0, 0, 0);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER)) {
                doSearch();
                return true;
            }
            return false;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchClearBtn != null) {
                    searchClearBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        bar.addView(searchInput, new LinearLayout.LayoutParams(0, barH, 1f));

        searchClearBtn = iconLabel("×", 18);
        searchClearBtn.setTextColor(TEXT_SECONDARY);
        searchClearBtn.setVisibility(View.GONE);
        searchClearBtn.setOnClickListener(v -> {
            searchInput.setText("");
            searchInput.requestFocus();
            showSearchLayer(false);
        });
        bar.addView(searchClearBtn, new LinearLayout.LayoutParams(dp(28), barH));
        return bar;
    }

    private View buildBottomPanel() {
        int icon = dp(28);
        int play = dp(52);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(16), dp(10), dp(16), dp(10));
        panel.setBackground(darkRoundedTop(24, PANEL_BG));
        panel.setElevation(dp(12));
        blockTouches(panel);

        // 第一行：书签 | 完整坐标 | 星星 | 经/纬（全部 wrap，垂直居中，不互挤）
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setPadding(0, 0, 0, 0);

        TextView favListBtn = iconLabel("📖", 16);
        favListBtn.setOnClickListener(v -> showFavoritesDialog());
        row1.addView(favListBtn, new LinearLayout.LayoutParams(icon, icon));

        coordText = new TextView(this);
        coordText.setText("116.407400, 39.904200");
        coordText.setTextSize(13);
        coordText.setTextColor(TEXT_PRIMARY);
        coordText.setTypeface(Typeface.MONOSPACE);
        coordText.setGravity(Gravity.CENTER);
        coordText.setSingleLine(true);
        coordText.setIncludeFontPadding(false);
        coordText.setOnClickListener(v -> showInputCoordinateDialog());
        LinearLayout.LayoutParams coordLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        coordLp.leftMargin = dp(8);
        coordLp.rightMargin = dp(8);
        row1.addView(coordText, coordLp);

        TextView addFavBtn = iconLabel("☆", 16);
        addFavBtn.setOnClickListener(v -> showAddFavoriteDialog());
        row1.addView(addFavBtn, new LinearLayout.LayoutParams(icon, icon));

        coordOrderBtn = iconLabel("经,纬", 10);
        coordOrderBtn.setTextColor(ACCENT);
        coordOrderBtn.setBackground(darkRounded(12, 0x22007C91));
        coordOrderBtn.setPadding(dp(8), 0, dp(8), 0);
        coordOrderBtn.setGravity(Gravity.CENTER);
        coordOrderBtn.setOnClickListener(v -> toggleCoordOrder());
        LinearLayout.LayoutParams orderLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(24));
        orderLp.leftMargin = dp(8);
        row1.addView(coordOrderBtn, orderLp);

        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row1Lp.gravity = Gravity.CENTER_HORIZONTAL;
        panel.addView(row1, row1Lp);

        // 播放按钮：矢量图标居中，不要用系统 Button 文字
        playBtn = new ImageView(this);
        playBtn.setScaleType(ImageView.ScaleType.CENTER);
        playBtn.setPadding(dp(14), dp(14), dp(14), dp(14));
        playBtn.setOnClickListener(v -> onToggle());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(play, play);
        playLp.topMargin = dp(8);
        playLp.bottomMargin = dp(4);
        panel.addView(playBtn, playLp);
        updatePlayVisual();

        // 状态
        statusText = new TextView(this);
        statusText.setText("待机");
        statusText.setTextSize(12);
        statusText.setTextColor(TEXT_SECONDARY);
        statusText.setGravity(Gravity.CENTER);
        statusText.setIncludeFontPadding(false);
        panel.addView(statusText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 设置入口（小字，不用系统 Button）
        TextView settingsBtn = iconLabel("⚙ 设置", 12);
        settingsBtn.setTextColor(ACCENT);
        settingsBtn.setPadding(dp(8), dp(6), dp(8), dp(2));
        settingsBtn.setOnClickListener(v -> showSettingsDialog());
        LinearLayout.LayoutParams settingsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        settingsLp.topMargin = dp(2);
        panel.addView(settingsBtn, settingsLp);

        return panel;
    }

    // ---- 布局参数 ----
    private FrameLayout.LayoutParams searchBarLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        lp.topMargin = dp(12) + statusBarInset();
        return lp;
    }

    private FrameLayout.LayoutParams bottomPanelLp() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
    }

    // ---- 深色样式工具 ----
    private GradientDrawable darkRounded(int radius, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable darkRoundedTop(int radius, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float[] radii = {dp(radius), dp(radius), dp(radius), dp(radius), 0, 0, 0, 0};
        d.setCornerRadii(radii);
        return d;
    }

    private GradientDrawable darkCircle(int size, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setSize(dp(size), dp(size));
        return d;
    }

    private TextView darkChip(String text, int dotColor) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextSize(13);
        chip.setTextColor(TEXT_PRIMARY);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setBackground(darkRounded(20, PANEL_BG));
        chip.setElevation(dp(2));
        return chip;
    }

    private TextView iconLabel(String text, float sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(TEXT_PRIMARY);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** 单行 Key 输入：不换行，超长左右滑。 */
    private EditText keyInput(String hint, String value) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(value);
        et.setTextColor(TEXT_PRIMARY);
        et.setHintTextColor(TEXT_SECONDARY);
        et.setBackground(darkRounded(10, INPUT_BG));
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        et.setSingleLine(true);
        et.setMaxLines(1);
        et.setHorizontallyScrolling(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 23) {
            et.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
            et.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        }
        return et;
    }

    // ================= osmdroid 地图 =================

    private void setupMapView() {
        // 底图：Carto Voyager
        TileSourceFactory.addTileSource(createCartoSource());
        mapView.setTileSource(TileSourceFactory.getTileSource("CartoVoyager"));
        mapView.getController().setZoom(15.0);
        mapView.getController().setCenter(new GeoPoint(DEFAULT_LAT, DEFAULT_LON));
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        try { mapView.setMinZoomLevel(3.0); } catch (Exception ignored) {}
        try { mapView.setMaxZoomLevel(20.0); } catch (Exception ignored) {}

        // 单击确认 / 长按选点，不占用 onSingleTapUp，避免抢缩放和拖拽
        MapEventsOverlay clickOverlay = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (p != null) onMapPick(p.getLatitude(), p.getLongitude());
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                if (p != null) onMapPick(p.getLatitude(), p.getLongitude());
                return true;
            }
        });
        mapView.getOverlays().add(clickOverlay);

        mapMarker = new Marker(mapView);
        mapMarker.setPosition(new GeoPoint(DEFAULT_LAT, DEFAULT_LON));
        mapMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        mapView.getOverlays().add(mapMarker);
    }

    /** 更新 Carto key（key 变化后调用，只改变量+清缓存，不换 TileSource） */
    private void updateCartoTileSource(String key) {
        cartoApiKey = key;
        // 强制清除所有瓦片缓存（内存+磁盘），确保带 key 的新请求被发出
        try {
            mapView.getTileProvider().clearTileCache();
            // 清除磁盘缓存
            java.io.File cacheDir = new java.io.File(getCacheDir(), "osmdroid_tiles");
            if (cacheDir.exists()) {
                java.io.File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (java.io.File f : files) {
                        try { f.delete(); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        // 重新设置 TileSource 触发瓦片重新加载
        mapView.setTileSource(TileSourceFactory.getTileSource("CartoVoyager"));
        mapView.invalidate();
        // 强制重新加载当前可见区域
        mapView.getController().setCenter(mapView.getMapCenter());
    }

    private void onMapPick(double lat, double lon) {
        // 边界保护：忽略超出范围的坐标
        if (!CoordinateUtils.isValidLat(lat) || !CoordinateUtils.isValidLon(lon)) return;
        if (Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat) || Double.isInfinite(lon)) return;
        currentLat = lat;
        currentLon = lon;
        if (mapMarker != null) {
            mapMarker.setPosition(new GeoPoint(lat, lon));
        }
        if (mapView != null) mapView.invalidate();
        showSearchLayer(false);
        updateCoordText();
        // 实时查询模拟状态，避免 uiSimulating 不同步导致误判
        boolean started = false;
        try {
            started = CommandClient.isStarted(this);
        } catch (Exception ignored) {}
        if (started) {
            CommandClient.move(MainActivity.this, lat, lon);
            statusText.setText("已移动至: " + formatCoord(lat, lon));
        } else {
            statusText.setText("已选点: " + formatCoord(lat, lon));
        }
    }

    private void updateCoordText() {
        if (coordOrderLonLat) {
            coordText.setText(String.format(Locale.US, "%.6f, %.6f", currentLon, currentLat));
            coordOrderBtn.setText("经,纬");
        } else {
            coordText.setText(String.format(Locale.US, "%.6f, %.6f", currentLat, currentLon));
            coordOrderBtn.setText("纬,经");
        }
    }

    private void toggleCoordOrder() {
        coordOrderLonLat = !coordOrderLonLat;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_COORD_ORDER, coordOrderLonLat ? "lonlat" : "latlon").apply();
        updateCoordText();
    }

    private String formatCoord(double lat, double lon) {
        if (coordOrderLonLat) return String.format(Locale.US, "%.6f, %.6f", lon, lat);
        return String.format(Locale.US, "%.6f, %.6f", lat, lon);
    }

    // ---- Carto Voyager 瓦片源（支持 API key 去水印） ----
    // TileSource 名称固定为 "CartoVoyager"，key 从成员变量 cartoApiKey 动态读取
    // 这样更新 key 时不需要换 TileSource，只改变量+清缓存即可
    private OnlineTileSourceBase createCartoSource() {
        return new XYTileSource("CartoVoyager", 1, 20, 256, ".png",
                new String[]{
                        "https://a.basemaps.cartocdn.com/rastertiles/voyager/",
                        "https://b.basemaps.cartocdn.com/rastertiles/voyager/",
                        "https://c.basemaps.cartocdn.com/rastertiles/voyager/",
                        "https://d.basemaps.cartocdn.com/rastertiles/voyager/"
                },
                "© OpenStreetMap contributors © CARTO") {
            @Override
            public String getTileURLString(long pMapTileIndex) {
                String url = super.getTileURLString(pMapTileIndex);
                if (cartoApiKey != null && !cartoApiKey.isEmpty()) {
                    url += "?key=" + cartoApiKey;
                }
                return url;
            }
        };
    }

    private void flyTo(double lat, double lon) {
        if (mapView != null) {
            mapView.getController().animateTo(new GeoPoint(lat, lon));
            if (mapMarker != null) {
                mapMarker.setPosition(new GeoPoint(lat, lon));
            }
            updateCoordText();
        }
    }

    // ================= 搜索 =================

    private void doSearch() {
        final String keyword = searchInput.getText().toString().trim();
        final String key = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_AMAP_KEY, "");
        if (keyword.isEmpty()) {
            showSearchError("请输入搜索关键词");
            return;
        }
        if (key.isEmpty()) {
            showSearchError("请先在设置中填写高德 Web 服务 Key");
            return;
        }
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && searchInput.getWindowToken() != null) {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}
        showSearchStatus("搜索中...", TEXT_SECONDARY);
        final int seq = ++searchSeq;
        new Thread(() -> {
            try {
                final List<SearchResult> results = doSearchHttp(keyword, key);
                runOnUiThread(() -> {
                    if (seq != searchSeq) return;
                    renderSearchResults(results);
                });
            } catch (final Exception e) {
                runOnUiThread(() -> {
                    if (seq != searchSeq) return;
                    showSearchError("搜索失败：" + e.getMessage());
                });
            }
        }).start();
    }

    private List<SearchResult> doSearchHttp(String keyword, String key) throws Exception {
        String urlStr = "https://restapi.amap.com/v3/place/text"
                + "?key=" + URLEncoder.encode(key, "UTF-8")
                + "&keywords=" + URLEncoder.encode(keyword, "UTF-8")
                + "&offset=10&page=1&extensions=base";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        try {
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            String body = readAll(conn.getInputStream());
            JSONObject json = new JSONObject(body);
            if (!"1".equals(json.optString("status"))) {
                throw new IOException("高德错误：" + json.optString("info", "") + "(" + json.optString("infocode", "") + ")");
            }
            JSONArray pois = json.optJSONArray("pois");
            List<SearchResult> results = new ArrayList<>();
            if (pois != null) {
                for (int i = 0; i < pois.length(); i++) {
                    JSONObject p = pois.optJSONObject(i);
                    if (p == null) continue;
                String[] parts = p.optString("location", "").split(",");
                if (parts.length != 2) continue;
                try {
                    double gcjLon = Double.parseDouble(parts[0]);
                    double gcjLat = Double.parseDouble(parts[1]);
                    double[] wgs = CoordinateUtils.gcj02ToWgs84(gcjLat, gcjLon);
                    String name = p.optString("name", "未知地点");
                    String address = p.optString("address", "");
                    if (address.isEmpty()) {
                        String province = p.optString("pname", "");
                        String city = p.optString("cityname", "");
                        String district = p.optString("adname", "");
                        address = (province + city + district).trim();
                    }
                    results.add(new SearchResult(name, address, wgs[0], wgs[1]));
                } catch (NumberFormatException ignored) {}
            }
        }
        return results;
        } finally {
            conn.disconnect();
        }
    }

    private void renderSearchResults(List<SearchResult> results) {
        searchResultBox.removeAllViews();
        if (results.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("未找到相关地点");
            empty.setTextSize(14);
            empty.setTextColor(TEXT_SECONDARY);
            empty.setPadding(dp(16), dp(12), dp(16), dp(12));
            searchResultBox.addView(empty);
            showSearchLayer(true);
            return;
        }
        for (SearchResult r : results) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(16), dp(10), dp(16), dp(10));
            item.setOnClickListener(v -> {
                // 隐藏键盘
                try {
                    android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
                    if (imm != null && getCurrentFocus() != null) {
                        imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
                    }
                } catch (Exception ignored) {}
                currentLat = r.lat;
                currentLon = r.lon;
                flyTo(r.lat, r.lon);
                showSearchLayer(false);
                if (uiSimulating) {
                    CommandClient.move(this, r.lat, r.lon);
                    statusText.setText("已移动至: " + r.name);
                } else {
                    statusText.setText("已选点: " + r.name);
                }
            });

            TextView name = new TextView(this);
            name.setText(r.name);
            name.setTextSize(15);
            name.setTextColor(TEXT_PRIMARY);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            item.addView(name);

            if (r.address != null && !r.address.isEmpty()) {
                TextView addr = new TextView(this);
                addr.setText(r.address);
                addr.setTextSize(12);
                addr.setTextColor(TEXT_SECONDARY);
                addr.setPadding(0, dp(2), 0, 0);
                item.addView(addr);
            }

            searchResultBox.addView(item);
            View divider = new View(this);
            divider.setBackgroundColor(INPUT_BG);
            searchResultBox.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        showSearchLayer(true);
    }

    private void showSearchError(String msg) {
        showSearchStatus(msg, 0xFFFF5252);
    }

    private void showSearchStatus(String msg, int color) {
        searchResultBox.removeAllViews();
        TextView t = new TextView(this);
        t.setText(msg);
        t.setTextSize(14);
        t.setTextColor(color);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        searchResultBox.addView(t);
        showSearchLayer(true);
    }

    private void showSearchLayer(boolean show) {
        if (searchResultLayer == null) return;
        searchResultLayer.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    // ================= 模拟控制 =================

    private void onToggle() {
        if (uiSimulating) {
            boolean ok = CommandClient.stop(this);
            if (ok) {
                uiSimulating = false;
                updatePlayVisual();
                statusText.setText("待机");
            } else {
                statusText.setText("停止失败");
            }
        } else {
            boolean ok = CommandClient.start(this, currentLat, currentLon, camouflageBundle());
            if (ok) {
                uiSimulating = true;
                updatePlayVisual();
                statusText.setText("模拟中 (" + formatCoord(currentLat, currentLon) + ")");
            } else {
                statusText.setText("启动失败：请确认模块已启用并重启框架");
            }
        }
    }

    private void updatePlayVisual() {
        if (playBtn == null) return;
        if (uiSimulating) {
            playBtn.setImageResource(R.drawable.ic_stop);
            playBtn.setBackgroundResource(R.drawable.bg_fab_stop);
            playBtn.setContentDescription("停止模拟");
        } else {
            playBtn.setImageResource(R.drawable.ic_play);
            playBtn.setBackgroundResource(R.drawable.bg_fab_play);
            playBtn.setContentDescription("开始模拟");
        }
    }

    private void refreshState() {
        // 只刷新模块芯片。模拟开关只跟用户点播放键走，轮询不得自行开启。
        boolean moduleActive = MockState.isModuleActive();
        moduleStatusChip.setText(moduleActive ? "● 模块已激活" : "○ 模块未激活");
        moduleStatusChip.setTextColor(moduleActive ? 0xFF4CAF50 : 0xFFFFC107);
    }

    // ================= 收藏弹窗 =================

    private void showAddFavoriteDialog() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(16), dp(20), dp(8));
        final EditText nameEt = new EditText(this);
        nameEt.setHint("名称（如：公司 / 家）");
        nameEt.setTextColor(TEXT_PRIMARY);
        nameEt.setHintTextColor(TEXT_SECONDARY);
        nameEt.setBackground(darkRounded(10, INPUT_BG));
        nameEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        final EditText noteEt = new EditText(this);
        noteEt.setHint("备注（可选）");
        noteEt.setTextColor(TEXT_PRIMARY);
        noteEt.setHintTextColor(TEXT_SECONDARY);
        noteEt.setBackground(darkRounded(10, INPUT_BG));
        noteEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        inputLp.bottomMargin = dp(8);
        v.addView(nameEt, inputLp);
        v.addView(noteEt);

        new AlertDialog.Builder(this)
                .setTitle("收藏当前位置")
                .setMessage(formatCoord(currentLat, currentLon))
                .setView(v)
                .setPositiveButton("保存", (d, w) -> {
                    String name = nameEt.getText().toString().trim();
                    if (name.isEmpty()) name = "未命名";
                    String note = noteEt.getText().toString().trim();
                    addFavorite(name, note, currentLat, currentLon);
                    statusText.setText("已收藏: " + name);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showFavoritesDialog() {
        JSONArray arr = getFavorites();
        if (arr.length() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("收藏")
                    .setMessage("暂无收藏\n在地图页点「☆」添加当前位置")
                    .setPositiveButton("确定", null)
                    .show();
            return;
        }

        favDialogList = new LinearLayout(this);
        favDialogList.setOrientation(LinearLayout.VERTICAL);
        favDialogList.setPadding(dp(8), dp(8), dp(8), dp(8));
        renderFavList();

        ScrollView scroll = safeScrollView();
        if (scroll != null) {
            scroll.addView(favDialogList);
            favDialog = new AlertDialog.Builder(this)
                    .setTitle("收藏")
                    .setView(scroll)
                    .setPositiveButton("关闭", null)
                    .show();
        }
    }

    /** 重新渲染收藏列表（删除后调用，不重开弹窗） */
    private void renderFavList() {
        if (favDialogList == null) return;
        favDialogList.removeAllViews();
        JSONArray arr = getFavorites();
        if (arr.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无收藏");
            empty.setTextSize(14);
            empty.setTextColor(TEXT_SECONDARY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(20), 0, dp(20));
            favDialogList.addView(empty);
            return;
        }
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), dp(10), dp(8), dp(10));
            item.setBackground(darkRounded(12, 0x22FFFFFF));
            item.setOnClickListener(v -> {
                double lat = o.optDouble("lat", DEFAULT_LAT);
                double lon = o.optDouble("lon", DEFAULT_LON);
                currentLat = lat;
                currentLon = lon;
                flyTo(lat, lon);
                statusText.setText("已选点: " + o.optString("name", ""));
                if (favDialog != null) favDialog.dismiss();
            });

            TextView icon = new TextView(this);
            icon.setText("📍");
            icon.setTextSize(16);
            item.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView name = new TextView(this);
            name.setText(o.optString("name", "未命名"));
            name.setTextSize(14);
            name.setTextColor(TEXT_PRIMARY);
            body.addView(name);
            String note = o.optString("note", "");
            if (note != null && !note.isEmpty()) {
                TextView noteTv = new TextView(this);
                noteTv.setText(note);
                noteTv.setTextSize(12);
                noteTv.setTextColor(ACCENT);
                noteTv.setPadding(0, dp(1), 0, 0);
                body.addView(noteTv);
            }
            TextView coord = new TextView(this);
            coord.setText(formatCoord(o.optDouble("lat", 0), o.optDouble("lon", 0)));
            coord.setTextSize(11);
            coord.setTextColor(TEXT_SECONDARY);
            coord.setTypeface(Typeface.MONOSPACE);
            body.addView(coord);
            item.addView(body);

            TextView del = iconLabel("🗑", 14);
            del.setOnClickListener(v -> {
                removeFavorite(idx);
                renderFavList();
            });
            item.addView(del, new LinearLayout.LayoutParams(dp(36), dp(36)));

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = dp(6);
            favDialogList.addView(item, itemLp);
        }
    }

    private JSONArray getFavorites() {
        String s = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_FAVS, "[]");
        try { return new JSONArray(s); } catch (Exception e) { return new JSONArray(); }
    }

    private void addFavorite(String name, String note, double lat, double lon) {
        JSONArray arr = getFavorites();
        try {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("note", note);
            o.put("lat", lat);
            o.put("lon", lon);
            o.put("ts", System.currentTimeMillis());
            arr.put(o);
        } catch (Exception ignored) {}
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_FAVS, arr.toString()).apply();
    }

    private void removeFavorite(int index) {
        JSONArray arr = getFavorites();
        if (index >= 0 && index < arr.length()) {
            arr.remove(index);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_FAVS, arr.toString()).apply();
        }
    }

    // ================= 设置弹窗 =================

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private String prefStr(String key) {
        return prefs().getString(key, "");
    }

    private void putPrefStr(String key, String value) {
        prefs().edit().putString(key, value == null ? "" : value.trim()).apply();
    }

    private String fmtPref(String key, String unit) {
        String s = prefStr(key);
        if (s.isEmpty()) return "跟真实";
        return unit == null || unit.isEmpty() ? s : s + " " + unit;
    }

    private Bundle camouflageBundle() {
        Bundle b = new Bundle();
        putOptionalDouble(b, PREF_ALTITUDE, MockState.KEY_HAS_ALTITUDE, MockState.KEY_ALTITUDE);
        putOptionalFloat(b, PREF_ACCURACY, MockState.KEY_HAS_ACCURACY, MockState.KEY_ACCURACY);
        putOptionalFloat(b, PREF_SPEED, MockState.KEY_HAS_SPEED, MockState.KEY_SPEED);
        putOptionalFloat(b, PREF_BEARING, MockState.KEY_HAS_BEARING, MockState.KEY_BEARING);
        putOptionalInt(b, PREF_SATELLITES, MockState.KEY_HAS_SATELLITES, MockState.KEY_SATELLITES);
        putOptionalInt(b, PREF_MAX_CN0, MockState.KEY_HAS_MAX_CN0, MockState.KEY_MAX_CN0);
        putOptionalInt(b, PREF_MEAN_CN0, MockState.KEY_HAS_MEAN_CN0, MockState.KEY_MEAN_CN0);
        return b;
    }

    private void putOptionalDouble(Bundle b, String pref, String hasKey, String valKey) {
        Double v = parseDoubleOrNull(prefStr(pref));
        b.putBoolean(hasKey, v != null);
        if (v != null) b.putDouble(valKey, v);
    }

    private void putOptionalFloat(Bundle b, String pref, String hasKey, String valKey) {
        Double v = parseDoubleOrNull(prefStr(pref));
        b.putBoolean(hasKey, v != null);
        if (v != null) b.putFloat(valKey, v.floatValue());
    }

    private void putOptionalInt(Bundle b, String pref, String hasKey, String valKey) {
        Integer v = parseIntOrNull(prefStr(pref));
        b.putBoolean(hasKey, v != null);
        if (v != null) b.putInt(valKey, v);
    }

    private Double parseDoubleOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try { return Double.parseDouble(t); } catch (NumberFormatException e) { return null; }
    }

    private Integer parseIntOrNull(String s) {
        Double d = parseDoubleOrNull(s);
        if (d == null) return null;
        return (int) Math.round(d);
    }

    private void pushCamouflageIfSimulating() {
        if (!uiSimulating) return;
        CommandClient.putConfig(this, camouflageBundle());
    }

    private void showSettingsDialog() {
        final EditText cartoEt = keyInput("粘贴 Carto Basemaps API Key", prefStr("carto_api_key"));
        final EditText amapEt = keyInput("粘贴高德 Web 服务 Key（搜索用）", prefStr(PREF_AMAP_KEY));
        final EditText altEt = numberInput("海拔（米）", prefStr(PREF_ALTITUDE));
        final EditText accEt = numberInput("精度（米）", prefStr(PREF_ACCURACY));
        final EditText spdEt = numberInput("速度（m/s）", prefStr(PREF_SPEED));
        final EditText brgEt = numberInput("方位（度，0=北）", prefStr(PREF_BEARING));
        final EditText satEt = numberInput("satellites", prefStr(PREF_SATELLITES));
        final EditText maxEt = numberInput("maxCn0", prefStr(PREF_MAX_CN0));
        final EditText meanEt = numberInput("meanCn0", prefStr(PREF_MEAN_CN0));

        LinearLayout mapBody = new LinearLayout(this);
        mapBody.setOrientation(LinearLayout.VERTICAL);
        mapBody.addView(fieldLabel("Carto Key"));
        mapBody.addView(cartoEt, fieldLp());
        mapBody.addView(fieldLabel("高德 Key"));
        mapBody.addView(amapEt, fieldLp());

        LinearLayout camouflageBody = new LinearLayout(this);
        camouflageBody.setOrientation(LinearLayout.VERTICAL);
        camouflageBody.addView(sectionHint("空白=跟真实。速度/方位只给应用看，不会走路。"));
        camouflageBody.addView(fieldLabel("海拔"));
        camouflageBody.addView(altEt, fieldLp());
        camouflageBody.addView(fieldLabel("精度"));
        camouflageBody.addView(accEt, fieldLp());
        camouflageBody.addView(fieldLabel("速度"));
        camouflageBody.addView(spdEt, fieldLp());
        camouflageBody.addView(fieldLabel("方位"));
        camouflageBody.addView(brgEt, fieldLp());

        LinearLayout satBody = new LinearLayout(this);
        satBody.setOrientation(LinearLayout.VERTICAL);
        satBody.addView(sectionHint("空白=不造假。填了才写入 Location extras。建议 12 / 38 / 28。"));
        satBody.addView(fieldLabel("satellites"));
        satBody.addView(satEt, fieldLp());
        satBody.addView(fieldLabel("maxCn0"));
        satBody.addView(maxEt, fieldLp());
        satBody.addView(fieldLabel("meanCn0"));
        satBody.addView(meanEt, fieldLp());

        AccordionSection mapSec = accordionSection("底图与搜索", mapSummary(cartoEt, amapEt), mapBody);
        AccordionSection camouflageSec = accordionSection("定位伪装",
                camouflageSummary(altEt, accEt, spdEt, brgEt), camouflageBody);
        AccordionSection satSec = accordionSection("卫星 extras",
                satelliteSummary(satEt, maxEt, meanEt), satBody);
        final AccordionSection[] sections = {mapSec, camouflageSec, satSec};
        for (AccordionSection sec : sections) {
            sec.head.setOnClickListener(v -> toggleAccordion(sec, sections));
        }
        bindSummary(cartoEt, amapEt, () -> mapSec.summary.setText(mapSummary(cartoEt, amapEt)));
        bindSummary(altEt, accEt, spdEt, brgEt,
                () -> camouflageSec.summary.setText(camouflageSummary(altEt, accEt, spdEt, brgEt)));
        bindSummary(satEt, maxEt, meanEt,
                () -> satSec.summary.setText(satelliteSummary(satEt, maxEt, meanEt)));

        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        v.addView(mapSec.root);
        v.addView(camouflageSec.root);
        v.addView(satSec.root);
        TextView ver = new TextView(this);
        ver.setText("v" + APP_VERSION + " · 空白=跟真实");
        ver.setTextSize(11);
        ver.setTextColor(TEXT_SECONDARY);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(10), 0, dp(4));
        v.addView(ver);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(wrapWithMaxHeight(v, (int) (screenH() * 0.72f)))
                .setPositiveButton("保存", (d, w) -> {
                    putPrefStr("carto_api_key", cartoEt.getText().toString());
                    updateCartoTileSource(prefStr("carto_api_key"));
                    putPrefStr(PREF_AMAP_KEY, amapEt.getText().toString());
                    putPrefStr(PREF_ALTITUDE, altEt.getText().toString());
                    putPrefStr(PREF_ACCURACY, accEt.getText().toString());
                    putPrefStr(PREF_SPEED, spdEt.getText().toString());
                    putPrefStr(PREF_BEARING, brgEt.getText().toString());
                    putPrefStr(PREF_SATELLITES, satEt.getText().toString());
                    putPrefStr(PREF_MAX_CN0, maxEt.getText().toString());
                    putPrefStr(PREF_MEAN_CN0, meanEt.getText().toString());
                    pushCamouflageIfSimulating();
                    statusText.setText("已保存");
                })
                .setNegativeButton("关闭", null)
                .create();
        Window win = dlg.getWindow();
        if (win != null) {
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        dlg.show();
    }

    private static final class AccordionSection {
        final LinearLayout root;
        final LinearLayout head;
        final TextView arrow;
        final TextView summary;
        final View body;
        boolean expanded;

        AccordionSection(LinearLayout root, LinearLayout head, TextView arrow, TextView summary, View body) {
            this.root = root;
            this.head = head;
            this.arrow = arrow;
            this.summary = summary;
            this.body = body;
        }
    }

    private AccordionSection accordionSection(String title, String summaryText, View body) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(darkRounded(12, PANEL_CARD));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(12), dp(12), dp(12));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(TEXT_PRIMARY);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        TextView s = new TextView(this);
        s.setText(summaryText);
        s.setTextSize(12);
        s.setTextColor(TEXT_SECONDARY);
        s.setPadding(0, dp(4), 0, 0);
        titles.addView(t);
        titles.addView(s);
        TextView arrow = new TextView(this);
        arrow.setText("▸");
        arrow.setTextSize(16);
        arrow.setTextColor(TEXT_SECONDARY);
        arrow.setPadding(dp(8), 0, 0, 0);
        head.addView(titles);
        head.addView(arrow);

        body.setVisibility(View.GONE);
        body.setPadding(dp(16), 0, dp(16), dp(12));
        body.setOnClickListener(v -> { /* 点输入区不收起 */ });
        card.addView(head);
        card.addView(body);
        return new AccordionSection(card, head, arrow, s, body);
    }

    private void toggleAccordion(AccordionSection target, AccordionSection[] all) {
        boolean open = !target.expanded;
        for (AccordionSection sec : all) {
            boolean expand = open && sec == target;
            sec.expanded = expand;
            sec.body.setVisibility(expand ? View.VISIBLE : View.GONE);
            sec.arrow.setText(expand ? "▾" : "▸");
        }
    }

    private void bindSummary(Runnable refresh, EditText... fields) {
        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh.run(); }
        };
        for (EditText et : fields) et.addTextChangedListener(w);
    }

    private void bindSummary(EditText a, EditText b, Runnable refresh) {
        bindSummary(refresh, a, b);
    }

    private void bindSummary(EditText a, EditText b, EditText c, Runnable refresh) {
        bindSummary(refresh, a, b, c);
    }

    private void bindSummary(EditText a, EditText b, EditText c, EditText d, Runnable refresh) {
        bindSummary(refresh, a, b, c, d);
    }

    private String etText(EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }

    private String fmtLive(EditText et, String unit) {
        String s = etText(et);
        if (s.isEmpty()) return "跟真实";
        return unit == null || unit.isEmpty() ? s : s + " " + unit;
    }

    private String mapSummary(EditText cartoEt, EditText amapEt) {
        boolean carto = !etText(cartoEt).isEmpty();
        boolean amap = !etText(amapEt).isEmpty();
        if (carto && amap) return "Carto、高德 Key 已填";
        if (carto) return "Carto 已填 · 高德未填";
        if (amap) return "Carto 未填 · 高德已填";
        return "Carto / 高德 Key";
    }

    private String camouflageSummary(EditText alt, EditText acc, EditText spd, EditText brg) {
        return "海拔 " + fmtLive(alt, "m")
                + " · 精度 " + fmtLive(acc, "m")
                + " · 速度 " + fmtLive(spd, "m/s")
                + " · 方位 " + fmtLive(brg, "°");
    }

    private String satelliteSummary(EditText sat, EditText max, EditText mean) {
        return "卫星 " + fmtLive(sat, "")
                + " · maxCn0 " + fmtLive(max, "")
                + " · meanCn0 " + fmtLive(mean, "");
    }

    private TextView sectionHint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(TEXT_SECONDARY);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    private TextView fieldLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(TEXT_SECONDARY);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private LinearLayout.LayoutParams fieldLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(4);
        return lp;
    }

    private EditText numberInput(String hint, String value) {
        EditText et = keyInput(hint, value);
        et.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return et;
    }

    // ================= 输入坐标 =================

    private void showInputCoordinateDialog() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(20), dp(16), dp(20), dp(8));
        final EditText latEt = new EditText(this);
        latEt.setHint("纬度（-90 ~ 90）");
        latEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        latEt.setText(String.format(Locale.US, "%.6f", currentLat));
        latEt.setTextColor(TEXT_PRIMARY);
        latEt.setHintTextColor(TEXT_SECONDARY);
        latEt.setBackground(darkRounded(10, INPUT_BG));
        latEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        final EditText lonEt = new EditText(this);
        lonEt.setHint("经度（-180 ~ 180）");
        lonEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        lonEt.setText(String.format(Locale.US, "%.6f", currentLon));
        lonEt.setTextColor(TEXT_PRIMARY);
        lonEt.setHintTextColor(TEXT_SECONDARY);
        lonEt.setBackground(darkRounded(10, INPUT_BG));
        lonEt.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        inputLp.bottomMargin = dp(8);
        v.addView(latEt, inputLp);
        v.addView(lonEt);

        new AlertDialog.Builder(this)
                .setTitle("输入坐标")
                .setView(v)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        double lat = Double.parseDouble(latEt.getText().toString());
                        double lon = Double.parseDouble(lonEt.getText().toString());
                        if (!CoordinateUtils.isValidLat(lat) || !CoordinateUtils.isValidLon(lon)) {
                            statusText.setText("坐标范围错误");
                            return;
                        }
                        currentLat = lat;
                        currentLon = lon;
                        flyTo(lat, lon);
                        if (uiSimulating) {
                            CommandClient.move(this, lat, lon);
                            statusText.setText("已移动至: " + formatCoord(lat, lon));
                        } else {
                            statusText.setText("已选点: " + formatCoord(lat, lon));
                        }
                    } catch (NumberFormatException e) {
                        statusText.setText("坐标格式错误");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 工具 =================

    private void applyImmersiveBars() {
        try {
            Window w = getWindow();
            w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Throwable ignored) {}
    }

    /** 浮层空白处吃掉触摸，不让事件漏到底下的 MapView。子控件仍可点击。 */
    private void blockTouches(View v) {
        if (v == null) return;
        v.setClickable(true);
        v.setFocusable(true);
    }

    private View wrapWithMaxHeight(View content, final int maxH) {
        FrameLayout wrap = new FrameLayout(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int mode = MeasureSpec.getMode(heightMeasureSpec);
                int size = MeasureSpec.getSize(heightMeasureSpec);
                int cap = maxH;
                if (mode != MeasureSpec.UNSPECIFIED) cap = Math.min(maxH, size);
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(Math.max(cap, 0), MeasureSpec.AT_MOST));
            }
        };
        ScrollView scroll = safeScrollView();
        if (scroll != null) {
            scroll.addView(content, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            wrap.addView(scroll, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        } else {
            wrap.addView(content);
        }
        blockTouches(wrap);
        return wrap;
    }

    private boolean compactUi() {
        float d = getResources().getDisplayMetrics().density;
        if (d <= 0f) d = 1f;
        int hDp = (int) (screenH() / d);
        int wDp = (int) (getResources().getDisplayMetrics().widthPixels / d);
        return hDp < 640 || wDp < 360;
    }

    private int searchBarHeight() {
        return dp(compactUi() ? 42 : 48);
    }

    private int screenH() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int statusBarInset() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private ScrollView safeScrollView() {
        try { return new ScrollView(this); } catch (Throwable t) { return null; }
    }
}
