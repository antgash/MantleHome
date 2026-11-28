package com.beacon.home;

import android.Manifest;
import android.app.Activity;
import android.graphics.Color;
import android.widget.ScrollView;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * beacon, Veil-style: artwork (or a living video scene) fills the wall, a calm hero tells you
 * what's next, and three pill rows hold Continue watching, Nuvio's streaming services and your apps.
 */
public class HomeActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static float EDGE = 64;
    /** Backdrops sit under a dark shade and drift slowly: 1280 px wide looks the same and uses half the memory of 1920. */
    static final int WALL_W = 1280;

    private SharedPreferences prefs;
    private FrameLayout root, ui;
    private String mode;
    private ScenePlayer player;
    private HeroWall wall;
    private Shade shade;

    // top bar
    private TextView clock, weatherText, memText;
    private FrameLayout memBar;
    private View memFill;
    private final Runnable memTick = new Runnable() {
        @Override public void run() {
            updateMemory();
            if (started) U.MAIN.postDelayed(this, 15000);
        }
    };
    private Icon weatherIcon, wifiIcon;

    // hero
    private LinearLayout hero;
    private TextView heroLabel, heroTitle, heroLine, heroRemaining;
    private ImageView heroLogo;
    private LinearLayout heroProgRow;
    private View heroProgFill;
    private Object heroKey;
    private int heroToken;

    // rows
    private LinearLayout cwBlock, cwRow, svcRow, appsRow;
    private boolean appCircle, appCards;
    private String pendingFocusPkg;
    private boolean pendingFocusMenu;
    private FrameLayout dockBox;
    private TextView dockLabel;
    private Icon dockArrow;          // the small chevron left of the dock: All apps is down there
    private float dockArrowY;
    private android.animation.ObjectAnimator dockArrowNod;
    private boolean dockArrowHooked;
    private LinearLayout volumeRow;
    private TextView cwCount, cwTonight;
    private int defaultFocusId = View.NO_ID;
    private boolean firstFocusDone;

    // data
    private List<Apps.App> apps = new ArrayList<>();
    private Nuvio.Result nuvio;
    private long nuvioAt;

    /**
     * Nuvio rewrites its home-screen rows in the TV provider when you switch profile. Watching that
     * provider lets Continue Watching follow the change at once, with no need to reopen Nuvio first.
     */
    private final Runnable nuvioReload = this::loadNuvio;
    private final android.database.ContentObserver nuvioObserver = new android.database.ContentObserver(U.MAIN) {
        @Override public void onChange(boolean selfChange) {
            nuvioAt = 0; // stale: reload now if Home is showing, otherwise as soon as it comes back
            if (!started) return;
            U.MAIN.removeCallbacks(nuvioReload);
            U.MAIN.postDelayed(nuvioReload, 1200); // Nuvio writes the rows in a burst; wait for it to finish
        }
    };
    private final List<Nuvio.Item> cwItems = new ArrayList<>();
    private final java.util.Set<String> cwKeys = new java.util.HashSet<>();
    private boolean cwLoadedOnce;
    private TextView cwHead;

    // state
    private boolean ambient, started, coldStart = true, swallowUp, receiversOn;
    private long lastInput = System.currentTimeMillis();
    private ConnectivityManager.NetworkCallback netCb;
    private View gifHost;
    private AnimatedImageDrawable gifPlaying;

    private final Runnable idleTick = new Runnable() {
        @Override public void run() {
            if (!started) return;
            int mins = prefs.getInt(U.P_AMBIENT_MIN, 3);
            if (!ambient && mins > 0 && System.currentTimeMillis() - lastInput > mins * 60000L) enterAmbient();
            U.MAIN.postDelayed(this, 10000);
        }
    };

    /**
     * Safety net next to the provider observer: while Home is on screen, look for new Continue watching data
     * every 20 seconds. It is one small provider query; the row is only redrawn when something actually changed.
     */
    private final Runnable cwPoll = new Runnable() {
        @Override public void run() {
            if (!started) return;
            if (!ambient && !menuOpen && System.currentTimeMillis() - nuvioAt > 15000) loadNuvio(true);
            U.MAIN.postDelayed(this, 20000);
        }
    };
    private boolean observing;

    private void observeNuvio() {
        if (observing) return;
        try {
            getContentResolver().registerContentObserver(android.media.tv.TvContract.WatchNextPrograms.CONTENT_URI, true, nuvioObserver);
            getContentResolver().registerContentObserver(android.media.tv.TvContract.PreviewPrograms.CONTENT_URI, true, nuvioObserver);
            observing = true;
        } catch (Throwable ignored) {} // permission not granted yet: retried on the next resume
    }

    private String cwSig = "";

    private final Runnable resumeWall = () -> { if (started && !ambient && wall != null) wall.resume(); };

    private final BroadcastReceiver timeRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };
    private final BroadcastReceiver pkgRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            loadApps();
            String pkg = i.getData() == null ? null : i.getData().getSchemeSpecificPart();
            if (pkg != null && (pkg.equals(U.nuvio()) || pkg.equals(U.NUVIO_BEACON))) {
                profilePkg = null;            // observe it again
                observeProfiles();
                refreshProfilesAfresh(1500);  // give the new version a moment to start
            }
        }
    };

    // ================================================================== lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        U.init(this);
        V.init(this);
        prefs = U.prefs(this);
        applyIntentExtras(getIntent());
        prefs.registerOnSharedPreferenceChangeListener(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(V.BG);
        setContentView(root);
        Sfx.attach(this);
        getWindow().setBackgroundDrawable(null); // root paints the background; skip one full-screen fill per frame
        V.unclipAll(root);
        askPermissions();
        build();
        loadApps();
        observeNuvio();
        Img.trim(this);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        lastInput = System.currentTimeMillis();
        registerReceivers();
        updateClock();
        updateNetwork();
        loadWeather();
        startBackground();
        U.MAIN.removeCallbacks(idleTick);
        U.MAIN.postDelayed(idleTick, 10000);
        U.MAIN.removeCallbacks(cwPoll);
        U.MAIN.postDelayed(cwPoll, 20000);
    }

    @Override protected void onResume() {
        super.onResume();
        lastInput = System.currentTimeMillis();
        observeNuvio();
        if (System.currentTimeMillis() - nuvioAt > 5000) loadNuvio();
        NuvioProfiles.requestCwRefresh(this);
        observeProfiles();
        refreshProfilesAfresh(0);
        refreshUpdatePill();
        if (Updater.due(this)) U.MAIN.postDelayed(() -> Updater.check(this, (info, ok) -> refreshUpdatePill()), 6000);
    }

    /** Shows the "Update available" pill while a newer build is known, hides it once that build is installed. */
    private void refreshUpdatePill() {
        if (updatePill == null) return;
        boolean on = Updater.pending(this);
        if (!on && updatePill.hasFocus()) menuPill.requestFocus();
        updatePill.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        if (menuOpen) closeMenu();   // leaving Home (a film, another app) closes the menu too
        if (player != null) player.stop();
        if (wall != null) wall.pause();
        stopGif();
        U.MAIN.removeCallbacks(idleTick);
        U.MAIN.removeCallbacks(cwPoll);
        U.MAIN.removeCallbacks(memTick);
        U.MAIN.removeCallbacks(resumeWall);
        unregisterReceivers();
        if (ambient) exitAmbient(false);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // Home is out of sight (a film is playing): hand back cached pictures and animations.
            Img.clear();
            stopGif();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { getContentResolver().unregisterContentObserver(nuvioObserver); } catch (Throwable ignored) {}
        try { getContentResolver().unregisterContentObserver(profileObserver); } catch (Throwable ignored) {}
        U.MAIN.removeCallbacks(profileReload);
        U.MAIN.removeCallbacks(profileRetry);
        U.MAIN.removeCallbacks(nuvioReload);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        applyIntentExtras(intent);
        // `started` is false while another app covers Home, so true here = Home pressed on Home.
        endRearrange(false);
        boolean alreadyHome = started;
        if (menuOpen) closeMenu();   // Home closes the beacon menu, wherever in it you are
        if (ambient) exitAmbient(true);
        if (alreadyHome) focusFirstApp(); else focusDefault();
    }

    @Override public void onBackPressed() {
        if (ambient) { exitAmbient(true); return; }
        if (menuOpen) { if (menuPage != null) openMenuPage(null); else closeMenu(); return; }
        View f = getCurrentFocus();
        if (f != null && f.getId() != defaultFocusId) focusDefault(); else enterAmbient();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        lastInput = System.currentTimeMillis();
        if (ambient) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int k = e.getKeyCode();
                if (k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN || k == KeyEvent.KEYCODE_VOLUME_MUTE) return super.dispatchKeyEvent(e);
                boolean select = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER;
                AmbientView shown = select ? ambientView : null;
                if (shown != null) shown.openCurrent();
                exitAmbient(true);
                swallowUp = true;
            }
            return true;
        }
        if (swallowUp && e.getAction() == KeyEvent.ACTION_UP) { swallowUp = false; return true; }
        Sfx.key(this, e);
        if (rearrangeView != null && handleRearrangeKey(e)) return true;
        if (!menuOpen && e.getAction() == KeyEvent.ACTION_DOWN && (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP || e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN)
                && moveBetweenRows(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)) return true;
        if (wall != null && e.getAction() == KeyEvent.ACTION_DOWN) {
            wall.pause();
            U.MAIN.removeCallbacks(resumeWall);
            U.MAIN.postDelayed(resumeWall, 1500);
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (key == null || key.startsWith("last_") || key.startsWith("count_") || key.equals("svc")) return;
        if ("theme_mode".equals(key)) { applyThemeMode(); return; }
        if ("theme".equals(key)) { applyThemeInstant(); return; }
        if (U.P_MODE.equals(key) || "edge".equals(key) || "svc_off".equals(key) || "theme".equals(key) || "focus_glow".equals(key) || "surface".equals(key) || "app_style".equals(key)) {
            V.loadTheme(this);
            if (player != null) player.stop();
            build();
            if (started) startBackground();
            fillRows();
        } else if (U.P_SCENE_SECS.equals(key) || U.P_SCENES_OFF.equals(key) || U.P_LIFT.equals(key) || U.P_SOUND.equals(key)) {
            if (player != null) { player.stop(); configurePlayer(); if (started) player.start(false); }
        } else if (U.P_NAME.equals(key) || U.P_GREET.equals(key)) {
            if (heroKey == null || "greet".equals(heroKey)) showGreeting();
        } else if (U.P_HERO_FILL.equals(key) || "tmdb_key".equals(key)) {
            loadNuvio();
            fillRows();
        } else if (key.startsWith("cat_") || key.startsWith("hide_") || key.equals("pins")) {
            loadApps();
        }
    }

    private void applyIntentExtras(Intent i) {
        if (i == null) return;
        String m = i.getStringExtra("mode");
        if (U.MODE_NUVIO.equals(m) || U.MODE_SCENES.equals(m)) U.prefs(this).edit().putString(U.P_MODE, m).apply();
        String os = i.getStringExtra("open_service");
        if (os != null) startActivity(new Intent(this, ServiceActivity.class).putExtra("service", os));
        String tk = i.getStringExtra("tmdb");
        if (tk != null) U.prefs(this).edit().putString("tmdb_key", tk.trim()).apply();
        String nm = i.getStringExtra("name");
        if (nm != null) U.prefs(this).edit().putString(U.P_NAME, nm.trim()).apply();
        String lt = i.getStringExtra("livetv");   // your Live TV addon link (without /manifest.json)
        if (lt != null) U.prefs(this).edit().putString(GuideActivity.P_SRC, lt.trim().replaceAll("/manifest\\.json$", "")).apply();
    }

    private void askPermissions() {
        List<String> need = new ArrayList<>();
        for (String p : new String[] {Manifest.permission.READ_EXTERNAL_STORAGE,
                "com.android.providers.tv.permission.READ_EPG_DATA", "android.permission.READ_TV_LISTINGS"}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) try { requestPermissions(need.toArray(new String[0]), 7); } catch (Throwable ignored) {}
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (player != null) { player.stop(); configurePlayer(); if (started) player.start(true); }
        loadNuvio();
    }

    // ================================================================== build

    private boolean artMode() { return U.MODE_NUVIO.equals(mode); }

    private void build() {
        mode = U.mode(this);
        EDGE = V.edge(this);
        wallArt = null; memPanel = null; scrim = null; menuPanel = null; menuHost = null; menuOpen = false;
        root.removeAllViews();
        root.setBackgroundColor(V.BG);
        player = null;
        wall = null;
        heroKey = null;
        ambient = false;
        V.artAccent = null;

        if (artMode()) {
            wall = new HeroWall(this);
            wall.setDriftMs(24000);
            root.addView(wall, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        } else {
            player = new ScenePlayer(this);
            root.addView(player, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            configurePlayer();
            player.setListener(new ScenePlayer.Listener() {
                @Override public void onScene(Scene s, int index, int count) {
                    if (V.artTint(HomeActivity.this)) setAccent(s.accent);
                    if (heroKey == null) showGreeting();
                }
                @Override public void onProgress(float f) {}
            });
        }
        shade = new Shade(this);
        root.addView(shade, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        shade.set(V.BG, null);

        ui = new FrameLayout(this);
        root.addView(ui, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        buildTopBar();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        ui.addView(col, U.flp(U.MATCH, U.WRAP, Gravity.BOTTOM, 0, 0, 0, 0));
        col.setPadding(0, 0, 0, V.dp(22 + (EDGE - 48) / 2));
        buildHero(col);
        col.addView(space(24));
        buildContinue(col);
        buildServices(col);
        col.addView(space(14));
        buildApps(col);
        showGreeting();
        updateClock();
        updateNetwork();
        loadWeather();
        if (reopenMenu) { reopenMenu = false; U.MAIN.post(() -> showMenu(true)); }
    }

    private View space(float dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, V.dp(dp)));
        return v;
    }

    // ------------------------------------------------------------------ top bar

    private void buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        bar.setClipChildren(false);
        ui.addView(bar, U.flp(U.MATCH, V.dp(34), Gravity.TOP, 0, 0, 0, 0));
        topBar = bar;
        ((FrameLayout.LayoutParams) bar.getLayoutParams()).topMargin = V.dp(26 + (EDGE - 48) / 2);
        bar.setPadding(V.dp(EDGE), 0, V.dp(EDGE), 0);

        // Left: beacon menu pill (customise Home)
        LinearLayout menu = V.pill(this, 34, 12, 12);
        Icon mi = new Icon(this, Icon.BEACON);
        menu.addView(mi, new LinearLayout.LayoutParams(V.dp(14), V.dp(14)));
        TextView mt = V.text(this, "beacon", 15, 800, V.TEXT);
        mt.setLetterSpacing(-0.02f);
        menu.addView(mt, lp(U.WRAP, U.WRAP, 7, 0, 6, 0));
        Icon chev = new Icon(this, Icon.CHEVRON_DOWN);
        menu.addView(chev, new LinearLayout.LayoutParams(V.dp(11), V.dp(11)));
        V.tint(menu, V.TEXT);
        menu.setContentDescription("Customise Home");
        menu.setOnClickListener(v -> { if (menuOpen) closeMenu(); else showMenu(); });
        menuPill = menu;
        LinearLayout left = U.row(this);
        left.setClipChildren(false);
        left.addView(menu, new LinearLayout.LayoutParams(U.WRAP, V.dp(34)));


        // Next to the menu, only while a newer beacon build exists: Select opens Settings at the update options.
        LinearLayout up = V.pill(this, 34, 12, 14);
        View dot = new View(this);
        dot.setBackground(V.shape(V.accent(), 4, 0));
        up.addView(dot, new LinearLayout.LayoutParams(V.dp(8), V.dp(8)));
        TextView ut = V.text(this, "Update available", 13, 700, V.TEXT);
        up.addView(ut, lp(U.WRAP, U.WRAP, 8, 0, 0, 0));
        V.tint(up, V.TEXT);
        up.setContentDescription("Update available");
        up.setVisibility(View.GONE);
        up.setOnClickListener(v -> openMenuTo("updates"));
        updatePill = up;
        left.addView(up, lp(U.WRAP, V.dp(34), 10, 0, 0, 0));
        bar.addView(left, U.flp(U.WRAP, U.WRAP, Gravity.START | Gravity.CENTER_VERTICAL));

        // Centre: clock + weather
        LinearLayout mid = U.row(this);
        clock = V.text(this, "", 17, 400, V.TEXT);
        clock.setLetterSpacing(0.02f);
        mid.addView(clock);
        weatherIcon = new Icon(this, Icon.SUN);
        weatherIcon.setColor(U.alpha(V.TEXT, 0.9f));
        weatherIcon.setVisibility(View.GONE);
        mid.addView(weatherIcon, lp(V.dp(14), V.dp(14), 12, 0, 4, 0));
        weatherText = V.text(this, "", 14, 400, U.alpha(V.TEXT, 0.9f));
        mid.addView(weatherText);
        bar.addView(mid, U.flp(U.WRAP, U.WRAP, Gravity.CENTER));

        // Right: HDMI, Wi-Fi, projector settings
        LinearLayout right = U.row(this);
        right.setClipChildren(false);
        // The active Nuvio profile's picture; Select lists the profiles to switch to. Hidden until a Nuvio app
        // offers its profiles to launchers (Nuvio Beacon does; official Nuvio doesn't yet).
        right.addView(buildProfileButton(), lp(V.dp(30), V.dp(30), 9, 0, 0, 0));
        right.addView(roundButton(Icon.HDMI, "HDMI", v -> openHdmi()), lp(V.dp(30), V.dp(30), 9, 0, 0, 0));
        wifiIcon = new Icon(this, Icon.WIFI);
        View wifi = V.iconButton(this, wifiIcon, "Wi-Fi");
        wifi.setOnClickListener(v -> openSetting(Settings.ACTION_WIFI_SETTINGS));
        right.addView(wifi, lp(V.dp(30), V.dp(30), 9, 0, 0, 0));
        right.addView(roundButton(Icon.GEAR, "Projector settings", v -> openProjectorSettings()), lp(V.dp(30), V.dp(30), 9, 0, 0, 0));
        bar.addView(right, U.flp(U.WRAP, U.WRAP, Gravity.END | Gravity.CENTER_VERTICAL));
    }


    // ------------------------------------------------------------------ Nuvio profile button

    private FrameLayout profileBtn;
    private ImageView profileImg;
    private TextView profileInitial;
    private List<NuvioProfiles.Profile> profiles;
    private String profilePkg;
    private int profileLoads;

    private final Runnable profileReload = this::refreshProfiles;

    private final android.database.ContentObserver profileObserver = new android.database.ContentObserver(U.MAIN) {
        @Override public void onChange(boolean selfChange) {
            U.MAIN.removeCallbacks(profileReload);
            U.MAIN.postDelayed(profileReload, 400);
        }
    };

    private View buildProfileButton() {
        final FrameLayout b = new FrameLayout(this);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClipChildren(false);
        b.setPadding(V.dp(2), V.dp(2), V.dp(2), V.dp(2));
        b.setContentDescription("Nuvio profile");

        FrameLayout face = new FrameLayout(this);
        face.setBackground(V.shape(0xFF1E88E5, 15, 0));
        V.circle(face);
        profileInitial = V.text(this, "", 12, 800, V.TEXT);
        profileInitial.setGravity(Gravity.CENTER);
        face.addView(profileInitial, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        profileImg = V.image(this, ImageView.ScaleType.CENTER_CROP);
        face.addView(profileImg, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        b.addView(face, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));

        b.setBackground(V.shape(0, 15, 0));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? ring() : V.shape(0, 15, 0));
            b.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(150).start();
        });
        b.setOnClickListener(v -> showProfileMenu());
        b.setVisibility(View.GONE);
        profileBtn = b;
        // The last known profile at once (before Nuvio answers); refreshed when it does.
        final List<NuvioProfiles.Profile> last = NuvioProfiles.cached(this);
        if (last != null) U.MAIN.post(() -> { if (profileBtn == b && profiles == null) showProfile(last); });
        return b;
    }

    private static GradientDrawable ring() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0);
        g.setStroke(V.dp(2), V.TEXT);
        return g;
    }

    private void observeProfiles() {
        String pkg = U.nuvio();
        if (pkg.equals(profilePkg)) return;
        try { getContentResolver().unregisterContentObserver(profileObserver); } catch (Throwable ignored) {}
        try {
            for (String src : NuvioProfiles.sources())
                getContentResolver().registerContentObserver(NuvioProfiles.uri(src), true, profileObserver);
            profilePkg = pkg;
        } catch (Throwable ignored) {} // retried on the next resume
    }

    /** Re-reads Nuvio's profiles off the main thread and updates the button. */
    /** Waits between asks while Nuvio doesn't answer (it is starting up, or being updated). */
    private static final int[] PROFILE_RETRY_MS = {2000, 4000, 8000, 15000, 30000};
    private int profileRetries;
    private final Runnable profileRetry = this::refreshProfiles;

    /**
     * Re-reads Nuvio's profiles off the main thread and updates the button. While Nuvio doesn't answer (just
     * updated, or still starting), the last known profile stays on the button and beacon asks again a few times.
     */
    private void refreshProfiles() {
        final int load = ++profileLoads;
        U.IO.execute(() -> {
            final List<NuvioProfiles.Profile> list = NuvioProfiles.load(getApplicationContext());
            final List<NuvioProfiles.Profile> last = list == null ? NuvioProfiles.cached(getApplicationContext()) : null;
            U.MAIN.post(() -> {
                if (load != profileLoads) return;
                U.MAIN.removeCallbacks(profileRetry);
                if (list != null) {
                    profileRetries = 0;
                    NuvioProfiles.save(this, list);
                    showProfile(list);
                    return;
                }
                showProfile(last);   // null when there's nothing to fall back on: the button hides
                if (last != null && profileRetries < PROFILE_RETRY_MS.length)
                    U.MAIN.postDelayed(profileRetry, PROFILE_RETRY_MS[profileRetries++]);
            });
        });
    }

    /** Asks Nuvio again from the start (after it was installed or updated, or on returning to Home). */
    private void refreshProfilesAfresh(long delayMs) {
        profileRetries = 0;
        U.MAIN.removeCallbacks(profileRetry);
        if (delayMs <= 0) refreshProfiles(); else U.MAIN.postDelayed(profileRetry, delayMs);
    }

    /** Name of the active Nuvio profile, shown on the Continue watching heading (null: plain heading). */
    private String cwOwner;

    /** "Anthony’s continue watching" when a Nuvio profile is known, else "Continue watching". */
    private String cwHeading() {
        if (TextUtils.isEmpty(cwOwner)) return "Continue watching";
        String n = cwOwner.trim();
        return n + (n.toLowerCase(Locale.ROOT).endsWith("s") ? "\u2019" : "\u2019s") + " continue watching";
    }

    /** Sets the heading: white text, with the profile's name (when there is one) in the given theme colour. */
    private void paintCwHead(int accent) {
        if (cwHead == null) return;
        String text = cwHeading();
        if (TextUtils.isEmpty(cwOwner)) { cwHead.setText(text); return; }
        android.text.SpannableString sp = new android.text.SpannableString(text);
        int end = text.indexOf(" continue watching");   // the name and its ’s
        sp.setSpan(new android.text.style.ForegroundColorSpan(accent), 0, end > 0 ? end : cwOwner.trim().length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        cwHead.setText(sp);
    }

    private void showProfile(List<NuvioProfiles.Profile> list) {
        if (profileBtn == null) return;
        profiles = list;
        final NuvioProfiles.Profile p = NuvioProfiles.active(list);
        cwOwner = p == null ? null : p.name;
        activeProfileId = p == null ? -1 : p.id;
        if (p != null) rememberProfileColour(p);
        paintCwHead(V.accent());
        if (p == null) {
            if (profileBtn.hasFocus() && menuPill != null) menuPill.requestFocus();
            profileBtn.setVisibility(View.GONE);
            return;
        }
        profileBtn.setContentDescription("Nuvio profile: " + p.name);
        ((View) profileImg.getParent()).setBackground(V.shape(p.colorInt(), 15, 0));
        profileInitial.setText(p.initial());
        setAvatar(profileImg, p, 96);
        profileBtn.setVisibility(View.VISIBLE);
    }

    private int activeProfileId = -1;

    /** "Follow Nuvio": the accent of the active profile's Nuvio theme (the colour Nuvio highlights with). */
    private void rememberProfileColour(final NuvioProfiles.Profile p) {
        setProfileAccent(p.id, p.accentInt());
    }

    private void setProfileAccent(int profileId, int colour) {
        if (profileId != activeProfileId) return;
        if (prefs.getInt("last_nuvio_accent", 0) == colour) return;
        prefs.edit().putInt("last_nuvio_accent", colour).apply();
        if ("nuvio".equals(V.themeMode(this))) applyThemeMode();
    }

    private void setAvatar(final ImageView iv, NuvioProfiles.Profile p, int px) {
        final String uri = p.avatar;
        iv.setTag(uri);
        if (TextUtils.isEmpty(uri)) { iv.setImageDrawable(null); return; }
        Bitmap hit = Img.cached(uri, px);
        if (hit != null) { iv.setImageBitmap(hit); return; }
        iv.setImageDrawable(null);
        Img.load(this, uri, px, bmp -> { if (uri.equals(iv.getTag())) iv.setImageBitmap(bmp); });
    }

    /** A small sheet listing Nuvio's profiles; picking one makes it active in Nuvio too. */
    private void showProfileMenu() {
        final List<NuvioProfiles.Profile> list = profiles;
        if (list == null || list.isEmpty()) return;
        final android.app.Dialog d = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.TOP | Gravity.END);
            android.view.WindowManager.LayoutParams a = w.getAttributes();
            int[] at = new int[2];
            profileBtn.getLocationOnScreen(at);
            int screenW = getResources().getDisplayMetrics().widthPixels;
            a.x = Math.max(0, screenW - (at[0] + profileBtn.getWidth()) - V.dp(8));
            a.y = at[1] + profileBtn.getHeight() + V.dp(8);
            w.setAttributes(a);
        }

        LinearLayout col = U.col(this);
        col.setPadding(V.dp(9), V.dp(9), V.dp(9), V.dp(9));
        col.setBackground(V.shape(V.MENU_PANEL, 18, V.MENU_PANEL_LINE));
        TextView title = V.text(this, U.NUVIO_BEACON.equals(list.get(0).pkg) ? "Nuvio Beacon profiles" : "Nuvio profiles", 10, 500, U.alpha(V.TEXT, 0.65f));
        title.setPadding(V.dp(8), V.dp(4), 0, V.dp(6));
        col.addView(title, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));

        View focus = null;
        for (final NuvioProfiles.Profile p : list) {
            final LinearLayout row = U.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(V.dp(6), 0, V.dp(12), 0);
            row.setFocusable(true);
            row.setFocusableInTouchMode(true);
            row.setBackground(V.shape(0, 25, 0));

            FrameLayout face = new FrameLayout(this);
            face.setBackground(V.shape(p.colorInt(), 14, 0));
            V.circle(face);
            TextView ini = V.text(this, p.initial(), 12, 800, V.TEXT);
            ini.setGravity(Gravity.CENTER);
            face.addView(ini, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            ImageView iv = V.image(this, ImageView.ScaleType.CENTER_CROP);
            face.addView(iv, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            setAvatar(iv, p, 96);
            row.addView(face, new LinearLayout.LayoutParams(V.dp(28), V.dp(28)));

            final TextView name = V.text(this, p.name, 12.5f, 600, V.TEXT);
            V.ellipsize(name);
            row.addView(name, new LinearLayout.LayoutParams(0, U.WRAP, 1f));
            ((LinearLayout.LayoutParams) name.getLayoutParams()).leftMargin = V.dp(10);
            if (p.pin) {
                TextView pin = V.text(this, "PIN", 9.5f, 700, U.alpha(V.TEXT, 0.6f));
                row.addView(pin, lp(U.WRAP, U.WRAP, 8, 0, 0, 0));
            }
            final Icon check = new Icon(this, Icon.CHECK);
            check.setColor(V.TEXT);
            check.setVisibility(p.active ? View.VISIBLE : View.INVISIBLE);
            row.addView(check, lp(V.dp(13), V.dp(13), 8, 0, 0, 0));

            row.setOnFocusChangeListener((v, has) -> {
                row.setBackground(V.shape(has ? V.TEXT : 0, 25, 0));
                name.setTextColor(has ? V.INK : V.TEXT);
                check.setColor(has ? V.INK : V.TEXT);
            });
            row.setOnClickListener(v -> {
                Sfx.select();
                d.dismiss();
                if (!p.active) switchProfile(p);
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(U.MATCH, V.dp(40));
            rp.topMargin = V.dp(4);
            col.addView(row, rp);
            if (focus == null || p.active) focus = row;
        }
        d.setContentView(col, new ViewGroup.LayoutParams(V.dp(240), U.WRAP));
        d.show();
        Sfx.watch(d.getWindow());
        Sfx.open();
        final View f = focus;
        if (f != null) f.post(f::requestFocus);
    }

    private void switchProfile(final NuvioProfiles.Profile p) {
        if (p.pin) { openNuvioProfiles(p); return; }
        U.IO.execute(() -> {
            final String r = NuvioProfiles.switchTo(getApplicationContext(), p);
            U.MAIN.post(() -> {
                if ("ok".equals(r)) {
                    Toast.makeText(this, "Switched to " + p.name, Toast.LENGTH_SHORT).show();
                    // Show the new profile's name and colour now, before Continue watching reloads and flashes,
                    // so the flash never lands on the previous profile's colour.
                    activeProfileId = p.id;
                    cwOwner = p.name;
                    rememberProfileColour(p);
                    paintCwHead(V.accent());
                    refreshProfiles();
                    U.MAIN.postDelayed(profileReload, 1200); // Nuvio can report the new active profile a moment late
                    nuvioAt = 0;
                    loadNuvio();
                } else if ("pin_required".equals(r)) {
                    openNuvioProfiles(p);
                } else {
                    Toast.makeText(this, "Couldn't switch Nuvio profile", Toast.LENGTH_SHORT).show();
                    refreshProfiles();
                }
            });
        });
    }

    /** PIN-protected profiles are chosen in Nuvio itself, so the PIN stays in Nuvio. */
    private void openNuvioProfiles(NuvioProfiles.Profile p) {
        if (NuvioProfiles.openPicker(this, p.pkg)) {
            Toast.makeText(this, p.name + " has a PIN — choose it in Nuvio", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Couldn't open Nuvio", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ beacon menu (Veil-style dropdown)

    private LinearLayout menuPanel;
    private View menuPill, updatePill;
    private boolean menuOpen, reopenMenu;

    private void showMenu() { showMenu(false); }

    /** instant: no fade or slide (the menu reopening after a setting rebuilt Home, so it looks like it never closed). */
    private void showMenu(boolean instant) {
        if (menuOpen || ambient) return;
        menuOpen = true;
        if (scrim == null) {
            scrim = new View(this);
            scrim.setAlpha(0f);
            ui.addView(scrim, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        }
        scrim.setBackgroundColor(0x8C000000);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFillViewport(false);
        menuPanel = U.col(this);
        menuPanel.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(16));
        sv.addView(menuPanel, new FrameLayout.LayoutParams(U.MATCH, U.WRAP));
        // The whole panel uses the chosen surface, solid: frosted glass keeps its light edge, smoke is near-black
        // and slate a lifted grey-blue card, both with a soft shadow and no edge.
        sv.setBackground(V.shape(V.MENU_PANEL, 20, V.MENU_PANEL_LINE));
        if (V.surface != 0) sv.setElevation(V.dpf(10));
        sv.setClipToOutline(true);
        int top = V.dp(26 + (EDGE - 48) / 2 + 34 + 8);
        FrameLayout.LayoutParams lp = U.flp(V.dp(330), U.WRAP, Gravity.TOP | Gravity.START);
        lp.topMargin = top;
        lp.leftMargin = V.dp(EDGE);
        int maxH = getResources().getDisplayMetrics().heightPixels - top - V.dp(24);
        ui.addView(sv, lp);
        menuHost = sv;
        sv.setAlpha(0f);

        menuScroll = sv;
        menuMaxH = maxH;
        buildMenuPage(menuPage);

        // nothing behind the menu may take focus
        for (int i = 0; i < ui.getChildCount(); i++) {
            View c = ui.getChildAt(i);
            if (c != sv && c != scrim && c instanceof ViewGroup) ((ViewGroup) c).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        scrim.bringToFront();
        sv.bringToFront();
        scrim.animate().cancel();
        if (instant) {
            scrim.setAlpha(1f);
            sv.setAlpha(1f);
        } else {
            scrim.animate().alpha(1f).setDuration(200).start();
            sv.setTranslationY(-V.dpf(12));
            sv.animate().alpha(1f).translationY(0).setDuration(200).withLayer().start();
        }
        sv.post(this::fitMenu);
    }

    private ScrollView menuScroll;
    private int menuMaxH;
    private String menuPage; // null = the hub; otherwise "look", "screensaver", "sound" or "weather"
    private View menuFirst;

    /** Keeps the panel as tall as its content, up to the screen, then focuses the first row. */
    private void fitMenu() {
        final ScrollView sv = menuScroll;
        if (sv == null || !menuOpen) return;
        sv.getLayoutParams().height = U.WRAP;
        sv.requestLayout();
        sv.post(() -> {
            if (menuScroll != sv) return;
            if (sv.getHeight() > menuMaxH) { sv.getLayoutParams().height = menuMaxH; sv.requestLayout(); }
            sv.scrollTo(0, 0);
            View target = menuFirst;
            if (menuRestoreIdx >= 0 && menuPanel != null && menuRestoreIdx < menuPanel.getChildCount()) target = menuPanel.getChildAt(menuRestoreIdx);
            menuRestoreIdx = -1;
            if (target != null) target.requestFocus();
        });
    }

    /**
     * Refits the menu's height after something inside it grew or shrank (an opened changelog entry), without
     * scrolling back to the top or moving the selection: the anchor row stays where it is on screen.
     */
    private void refitMenuKeeping(final View anchor) { refitMenu(anchor, false); }

    /**
     * Refits the menu's height after something inside it grew or shrank. With toTop the anchor row scrolls up to
     * the top of the menu (an opened changelog entry, so its notes show below it); otherwise it stays where it is
     * on screen. The selection never moves.
     */
    private void refitMenu(final View anchor, final boolean toTop) {
        final ScrollView sv = menuScroll;
        if (sv == null || !menuOpen || menuPanel == null) return;
        final int before = topInPanel(anchor) - sv.getScrollY();
        sv.getLayoutParams().height = U.WRAP;
        sv.requestLayout();
        sv.post(() -> {
            if (menuScroll != sv) return;
            if (sv.getHeight() > menuMaxH) { sv.getLayoutParams().height = menuMaxH; sv.requestLayout(); }
            sv.post(() -> {
                if (menuScroll != sv) return;
                int top = topInPanel(anchor);
                if (toTop) sv.smoothScrollTo(0, Math.max(0, top - V.dp(8)));
                else sv.scrollTo(0, Math.max(0, top - before));
            });
        });
    }

    private int topInPanel(View v) {
        android.graphics.Rect r = new android.graphics.Rect();
        v.getDrawingRect(r);
        try { menuPanel.offsetDescendantRectToMyCoords(v, r); } catch (Throwable ignored) { return 0; }
        return r.top;
    }

    /** Closes every open changelog entry (opening one closes the others). */
    private final List<Runnable> changelogClosers = new ArrayList<>();

    private final List<Runnable> menuRefreshers = new ArrayList<>();
    private TextView updateSub;
    private String updateState = "Tap to check for a new build";
    private Updater.Info pendingUpdate;
    private static final String TVLAUNCHER = "com.google.android.tvlauncher", TVREC = "com.google.android.tvrecommendations";

    /** Opens the beacon menu straight onto one page (the Update available pill uses this). */
    private void openMenuTo(String page) {
        if (menuOpen) { openMenuPage(page); }
        else { menuPage = page; showMenu(); }
        if ("updates".equals(page)) checkOrInstallUpdate();
    }

    private void refreshMenuValues() { for (Runnable r : menuRefreshers) r.run(); }

    /** A menu row that shows its current value on the right; Select runs the action and refreshes the value. */
    private LinearLayout valueRow(int icon, String title, final java.util.function.Supplier<String> value, final Runnable onClick) {
        final LinearLayout row = menuButton(icon, title, null, () -> {});
        final TextView state = V.text(this, value.get(), 10, 800, V.TEXT);
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(state);
        V.tint(row, V.TEXT);
        menuRefreshers.add(() -> state.setText(value.get()));
        row.setOnClickListener(v -> { onClick.run(); state.setText(value.get()); });
        return row;
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable t) { return "1.0"; }
    }

    private boolean pkgEnabled(String pkg) {
        try {
            int st = getPackageManager().getApplicationEnabledSetting(pkg);
            return st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
        } catch (Throwable notInstalled) {
            return false;
        }
    }

    private boolean beaconIsHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        android.content.pm.ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
    }

    private int hiddenCount() {
        int n = 0;
        for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) if (e.getKey().startsWith("hide_")) n++;
        return n;
    }

    /** Asks first, then runs the shell command on the device itself (through its network-debugging port). */
    private void confirmRun(String title, String message, final String cmd) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Yes", (d, w) -> {
                    Toast.makeText(this, "Working…", Toast.LENGTH_SHORT).show();
                    U.IO.execute(() -> {
                        final String out = AdbLocal.exec(getApplicationContext(), cmd);
                        U.MAIN.post(() -> {
                            if (out == null) {
                                Toast.makeText(this, "waiting".equals(AdbLocal.state)
                                        ? "Allow the network debugging prompt on screen, then try again"
                                        : "Couldn't reach the device's debugging port. Turn on network debugging in Developer options.",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(this, "Done", Toast.LENGTH_SHORT).show();
                            }
                            refreshMenuValues();
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void switchHomeScreen() {
        if (beaconIsHome()) {
            confirmRun("Go back to the Google TV home screen?",
                    "Turns Google's home and its recommendations back on and makes it the home screen. "
                            + "beacon stays installed: open it from your apps any time to switch back.",
                    "pm enable " + TVLAUNCHER + "; pm enable " + TVREC
                            + "; cmd package set-home-activity " + TVLAUNCHER + "/.MainActivity"
                            + "; settings delete secure enabled_accessibility_services; settings put secure accessibility_enabled 0"
                            + "; am start -a android.intent.action.MAIN -c android.intent.category.HOME");
        } else {
            confirmRun("Make beacon your home screen?",
                    "Switches Google's home off (it stays installed) and makes the Home button open beacon.",
                    "cmd package set-home-activity " + getPackageName() + "/" + HomeActivity.class.getName()
                            + "; settings put secure enabled_accessibility_services " + getPackageName() + "/" + HomeButtonService.class.getName()
                            + "; settings put secure accessibility_enabled 1"
                            + "; pm disable-user --user 0 " + TVREC + "; pm disable-user --user 0 " + TVLAUNCHER
                            + "; am start -n " + getPackageName() + "/" + HomeActivity.class.getName());
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private String nuvioLabel() {
        String p = U.nuvio(this);
        String l = appLabel(p);
        if (U.NUVIO.equals(p)) return l == null ? "Official (not installed)" : "Official";
        return l == null ? p + " (not installed)" : l;
    }

    /** Lets you choose which Nuvio app (the official one or a fork) beacon reads Continue watching from and opens titles in. */
    private void pickNuvio() {
        final String cur = U.nuvio(this);
        ActionSheet sh = new ActionSheet(this, "Which Nuvio app?");
        sh.add((U.NUVIO.equals(cur) ? "\u2713 " : "") + "Nuvio (official)" + (appLabel(U.NUVIO) == null ? " \u00b7 not installed" : ""), Icon.NUVIO, () -> setNuvioApp(U.NUVIO));
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(U.NUVIO);
        seen.add(getPackageName());
        for (Apps.App a : Apps.load(this)) {
            String hay = (a.pkg + " " + a.label).toLowerCase(Locale.ROOT);
            if (!hay.contains("nuvio") || !seen.add(a.pkg)) continue;
            final String pkg = a.pkg;
            // The fork is called Nuvio Beacon (older builds were labelled just "Nuvio").
            String label = U.NUVIO_BEACON.equals(pkg) ? "Nuvio Beacon" : a.label;
            sh.add((pkg.equals(cur) ? "\u2713 " : "") + label, Icon.NUVIO, () -> setNuvioApp(pkg));
        }
        sh.add("Another app\u2026", Icon.APPS, this::pickAnyApp);
        sh.show();
    }

    private void pickAnyApp() {
        final String cur = U.nuvio(this);
        ActionSheet sh = new ActionSheet(this, "Choose the app to use as Nuvio");
        List<Apps.App> all = new ArrayList<>(Apps.load(this));
        java.util.Collections.sort(all, (x, y) -> String.valueOf(x.label).compareToIgnoreCase(String.valueOf(y.label)));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Apps.App a : all) {
            if (a.pkg.equals(getPackageName()) || !seen.add(a.pkg)) continue;
            final String pkg = a.pkg;
            sh.add((pkg.equals(cur) ? "\u2713 " : "") + a.label, Icon.APPS, () -> setNuvioApp(pkg));
        }
        sh.show();
    }

    private void setNuvioApp(String pkg) {
        U.setNuvio(this, pkg);
        cwSig = "";
        String l = appLabel(pkg);
        Toast.makeText(this, "Using " + (l == null ? pkg : l) + " for Continue watching and playback", Toast.LENGTH_LONG).show();
        refreshMenuValues();
        loadNuvio(false);
    }

    private void setUpdateState(String t) {
        updateState = t;
        if (updateSub != null) updateSub.setText(t);
    }

    // ------------------------------------------------------------------ Nuvio Beacon download (Updates page)

    private TextView nbSub;
    private String nbState;

    private static final String NB_SUB = "Built for beacon";
    private boolean nbBusy;

    private void setNbState(String t) {
        nbState = t;
        if (nbSub != null) nbSub.setText(t);
    }

    /** Downloads and installs Nuvio Beacon when it isn't installed yet; does nothing once it is (it updates itself). */
    private void downloadNuvioBeacon() {
        if (nbBusy || NuvioBeaconInstaller.installed(this) != null) return;
        nbBusy = true;
        setNbState("Checking GitHub\u2026");
        NuvioBeaconInstaller.latest(this, (r, ok) -> {
            if (!ok || r == null) {
                nbBusy = false;
                setNbState(!ok ? "Couldn't reach GitHub \u00b7 select to retry" : "No build for this TV yet");
                return;
            }
            setNbState("Downloading\u2026 0%");
            NuvioBeaconInstaller.downloadAndInstall(this, r, pct -> setNbState("Downloading\u2026 " + pct + "%"), msg -> {
                nbBusy = false;
                setNbState(msg);
            });
        });
    }

    private TextView ltSub;
    private String ltState;
    private static final String LT_SUB = "The stand-alone Live TV guide";
    private boolean ltBusy;

    private void setLtState(String t) {
        ltState = t;
        if (ltSub != null) ltSub.setText(t);
    }

    private void downloadLiveTv() {
        final NuvioBeaconInstaller.Target t = NuvioBeaconInstaller.LIVE_TV;
        if (ltBusy || NuvioBeaconInstaller.installed(this, t) != null) return;
        ltBusy = true;
        setLtState("Checking GitHub\u2026");
        NuvioBeaconInstaller.latest(this, t, (r, ok) -> {
            if (!ok || r == null) {
                ltBusy = false;
                setLtState(!ok ? "Couldn't reach GitHub \u00b7 select to retry" : "No build for this TV yet");
                return;
            }
            setLtState("Downloading\u2026 0%");
            NuvioBeaconInstaller.downloadAndInstall(this, t, r, pct -> setLtState("Downloading\u2026 " + pct + "%"), msg -> {
                ltBusy = false;
                setLtState(msg);
            });
        });
    }

    /** A changelog row: title, date and summary; Select opens it to list the main changes (and a picture). */
    private View changelogEntry(final Changelog.Entry e) {
        final LinearLayout box = U.col(this);
        final LinearLayout head = menuButton(Icon.BEACON, e.title, e.date + "  \u00b7  " + e.summary, () -> {});
        View sp = new View(this);
        head.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        final Icon chev = new Icon(this, Icon.CHEVRON_DOWN);
        head.addView(chev, new LinearLayout.LayoutParams(V.dp(12), V.dp(12)));
        V.tint(head, V.TEXT);
        box.addView(head, new LinearLayout.LayoutParams(U.MATCH, V.dp(48)));

        final LinearLayout body = U.col(this);
        body.setPadding(V.dp(12), V.dp(10), V.dp(12), V.dp(10));
        body.setBackground(V.shape(V.MENU_ROW, 14, 0));
        body.setFocusable(true);
        body.setFocusableInTouchMode(true);
        if (!TextUtils.isEmpty(e.image)) {
            final ImageView iv = V.image(this, ImageView.ScaleType.CENTER_CROP);
            V.round(iv, 10);
            body.addView(iv, lp(U.MATCH, V.dp(150), 0, 0, 0, 10));
            Img.load(this, e.image, 640, b -> { if (b != null) iv.setImageBitmap(b); });
        }
        for (int i = 0; i < e.changes.length; i++) {
            LinearLayout line = U.row(this);
            line.setGravity(Gravity.TOP);
            Icon ic = new Icon(this, e.icons[i]);
            ic.setColor(U.alpha(V.TEXT, 0.85f));
            line.addView(ic, lp(V.dp(12), V.dp(12), 0, 2, 0, 0));
            TextView t = V.text(this, e.changes[i], 10.5f, 500, U.alpha(V.TEXT, 0.88f));
            line.addView(t, new LinearLayout.LayoutParams(0, U.WRAP, 1f));
            ((LinearLayout.LayoutParams) t.getLayoutParams()).leftMargin = V.dp(9);
            body.addView(line, lp(U.MATCH, U.WRAP, 0, i == 0 ? 0 : 7, 0, 0));
        }
        body.setVisibility(View.GONE);
        // The opened notes take focus (a ring around them) so the menu scrolls to show them; Select closes them.
        body.setOnFocusChangeListener((v, has) -> body.setBackground(has
                ? V.shape(V.MENU_ROW, 14, U.alpha(V.accent(), 0.9f)) : V.shape(V.MENU_ROW, 14, 0)));
        final Runnable close = () -> { body.setVisibility(View.GONE); chev.setRotation(0f); };
        changelogClosers.add(close);
        final Runnable toggle = () -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            // Keep the selection where it is: work out the heading's place first, then close the others.
            head.requestFocus();
            if (open) for (Runnable r : changelogClosers) if (r != close) r.run();
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            chev.setRotation(open ? 180f : 0f);
            refitMenu(head, open);   // opening: the title moves to the top with its notes below; closing: stays put
        };
        head.setOnClickListener(v -> { Sfx.select(); toggle.run(); });
        body.setOnClickListener(v -> { Sfx.select(); toggle.run(); });
        box.addView(body, lp(U.MATCH, U.WRAP, 0, 6, 0, 0));
        return box;
    }

    private void checkOrInstallUpdate() {
        if (pendingUpdate != null) { downloadAndInstall(pendingUpdate); return; }
        setUpdateState("Checking…");
        Updater.check(this, (info, ok) -> {
            if (!ok) { setUpdateState("Couldn't reach GitHub · select to retry"); return; }
            if (info == null) { setUpdateState("Up to date (build " + Updater.installedCode(this) + ") · select to check again"); return; }
            pendingUpdate = info;
            setUpdateState("New build available (" + Updater.why(this, info) + ") · select to install");
        });
    }

    private void downloadAndInstall(final Updater.Info info) {
        setUpdateState("Downloading… 0%");
        Updater.download(this, info, pct -> setUpdateState("Downloading… " + pct + "%"), apk -> {
            if (apk == null) { setUpdateState("Download failed · select to retry"); return; }
            if (Updater.install(this, apk)) setUpdateState("Confirm the install on screen");
            else setUpdateState("Couldn't start the installer · select to retry");
        });
    }

    private void openMenuPage(String page) {
        buildMenuPage(page);
        if (menuPanel != null) menuPanel.post(this::fitMenu);
    }

    private View hubRow(int icon, String title, String sub, Runnable r) {
        LinearLayout row = menuButton(icon, title, sub, r);
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(new Icon(this, Icon.RIGHT), new LinearLayout.LayoutParams(V.dp(14), V.dp(14)));
        V.tint(row, V.TEXT);
        return row;
    }

    private void buildMenuPage(String page) {
        if (menuPanel == null) return;
        menuPage = page;
        menuPanel.removeAllViews();
        menuFirst = null;
        menuRefreshers.clear();
        changelogClosers.clear();
        String[] titles = {"look", "Appearance", "system", "System", "screensaver", "Screensaver", "sound", "Sound", "weather", "Weather", "updates", "Updates & downloads"};
        String title = "beacon";
        for (int i = 0; i < titles.length; i += 2) if (titles[i].equals(page)) title = titles[i + 1];
        menuPanel.addView(V.text(this, title, 24, 800, V.TEXT));
        menuPanel.addView(V.dim(V.caps(this, page == null ? "Homescreen settings" : "beacon", 9, V.TEXT), 0.55f), lp(U.WRAP, U.WRAP, 0, 2, 0, 14));

        if (page == null) {
            int mins = prefs.getInt(U.P_AMBIENT_MIN, 3);
            String city = prefs.getString("wx_city", "");
            menuFirst = hubRow(Icon.SLIDERS, "Appearance", "Theme, focus, continue watching, surface, dock", () -> openMenuPage("look"));
            menuPanel.addView(menuFirst, lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(hubRow(Icon.MOON, "Screensaver", (mins == 0 ? "Off" : "After " + mins + " min") + " \u00b7 preview, artwork speed", () -> openMenuPage("screensaver")), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(hubRow(Icon.SPEAKER, "Sound", (Sfx.isEnabled() ? "Navigation sounds on" : "Navigation sounds off") + " \u00b7 volume", () -> openMenuPage("sound")), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(hubRow(Icon.SUN, "Weather", (prefs.getBoolean("weather", true) ? "Shown by the clock" : "Hidden") + " \u00b7 " + (TextUtils.isEmpty(city) ? "auto location" : city), () -> openMenuPage("weather")), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(hubRow(Icon.GEAR, "System", "Margins, home screen, Nuvio, hidden apps", () -> openMenuPage("system")), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(hubRow(Icon.CHECK, "Updates & downloads", Updater.pending(this) ? "Update available \u00b7 Nuvio Beacon, Live TV" : "Updates, Nuvio Beacon, Live TV, changelog", () -> openMenuPage("updates")), lp(U.MATCH, V.dp(48), 0, 0, 0, 14));
            menuPanel.addView(V.dim(V.text(this, "beacon " + versionName() + "  \u00b7  ad-free home screen", 9, 500, V.TEXT), 0.5f), lp(U.WRAP, U.WRAP, 4, 0, 0, 12));
            menuPanel.addView(menuButton(Icon.LEFT, "Back to close", null, this::closeMenu), lp(U.MATCH, V.dp(40), 0, 0, 0, 0));
            return;
        }

        if ("look".equals(page)) {
            View themeBlock = buildThemeColors();
            menuPanel.addView(themeBlock, lp(U.MATCH, U.WRAP, 0, 0, 0, 8));
            menuFirst = menuFocusDot ? selectedThemeDot() : themeOption;
            menuFocusDot = false;
            menuPanel.addView(focusStyleToggle(), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(cwStyleToggle(), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(surfaceToggle(), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(appStyleToggle(), lp(U.MATCH, V.dp(48), 0, 0, 0, 14));
        } else if ("screensaver".equals(page)) {
            menuFirst = menuButton(Icon.PLAY, "Start screensaver", "Preview it now · press any key to leave", () -> {
                closeMenu();
                U.MAIN.postDelayed(this::enterAmbient, 350);
            });
            menuPanel.addView(menuFirst, lp(U.MATCH, V.dp(48), 0, 0, 0, 14));

            menuPanel.addView(menuLabel("Screensaver starts after"));
            final int[] mins = {0, 1, 3, 5, 10, 20};
            String[] names = {"Off", "1m", "3m", "5m", "10m", "20m"};
            int cur = prefs.getInt(U.P_AMBIENT_MIN, 3), sel = 2;
            for (int i = 0; i < mins.length; i++) if (mins[i] == cur) sel = i;
            menuPanel.addView(chipRow(names, sel, i -> {
                prefs.edit().putInt(U.P_AMBIENT_MIN, mins[i]).apply();
                lastInput = System.currentTimeMillis();
            }), lp(U.MATCH, V.dp(30), 0, 0, 0, 14));

            menuPanel.addView(menuLabel("Artwork changes every"));
            final int[] secs = {8, 12, 20, 30, 60};
            String[] sNames = {"8s", "12s", "20s", "30s", "1m"};
            int curS = prefs.getInt(U.P_HERO_SECS, 12), selS = 1;
            for (int i = 0; i < secs.length; i++) if (secs[i] == curS) selS = i;
            menuPanel.addView(chipRow(sNames, selS, i -> prefs.edit().putInt(U.P_HERO_SECS, secs[i]).apply()),
                    lp(U.MATCH, V.dp(30), 0, 0, 0, 14));

            menuPanel.addView(menuButton(Icon.MOON, "Use as Android TV screensaver", "Opens the system screensaver picker", this::openDreamSettings), lp(U.MATCH, V.dp(48), 0, 0, 0, 14));
        } else if ("sound".equals(page)) {
            menuFirst = soundToggle();
            menuPanel.addView(menuFirst, lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            volumeRow = buildVolumeRow();
            volumeRow.setAlpha(Sfx.isEnabled() ? 1f : 0.45f);
            menuPanel.addView(volumeRow, lp(U.MATCH, V.dp(48), 0, 0, 0, 14));
        } else if ("system".equals(page)) {
            final int[] edges = {48, 64, 80};
            menuFirst = cycleRow(Icon.SLIDERS, "Screen edge margin", new String[] {"Small", "Medium", "Large"}, () -> {
                int v = Math.min(80, prefs.getInt("edge", 48));
                for (int i = 0; i < edges.length; i++) if (edges[i] == v) return i;
                return 0;
            }, i -> prefs.edit().putInt("edge", edges[i]).apply(), true);
            menuPanel.addView(menuFirst, lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(toggleButton(Icon.EYE, "Hide streaming services row", "svc_off", false), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(valueRow(Icon.SHIELD, "Google TV recommendations", () -> pkgEnabled(TVREC) ? "On" : "Off", () -> {
                final boolean on = pkgEnabled(TVREC);
                confirmRun(on ? "Turn off Google TV recommendations?" : "Turn Google TV recommendations back on?",
                        "A background service only Google's home screen uses. Off saves memory.",
                        (on ? "pm disable-user --user 0 " : "pm enable ") + TVREC);
            }), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(valueRow(Icon.LIGHTHOUSE, "Home screen", () -> beaconIsHome() ? "beacon" : "Google TV", this::switchHomeScreen), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(valueRow(Icon.NUVIO, "Nuvio app", this::nuvioLabel, this::pickNuvio), lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            menuPanel.addView(valueRow(Icon.APPS, "Show hidden apps again", () -> hiddenCount() + " hidden", () -> {
                android.content.SharedPreferences.Editor e = prefs.edit();
                for (String k : new ArrayList<>(prefs.getAll().keySet())) if (k.startsWith("hide_")) e.remove(k);
                e.apply();
                Toast.makeText(this, "All apps are back on Home", Toast.LENGTH_SHORT).show();
            }), lp(U.MATCH, V.dp(48), 0, 0, 0, 14));
        } else if ("updates".equals(page)) {
            menuFirst = toggleButton(Icon.CHECK, "Check for updates", Updater.P_AUTO, true);
            menuPanel.addView(menuFirst, lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            LinearLayout ur = menuButton(Icon.CHECK, "Update beacon", updateState, this::checkOrInstallUpdate);
            updateSub = (TextView) ((LinearLayout) ur.getChildAt(1)).getChildAt(1);
            menuPanel.addView(ur, lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            if (!nbBusy) nbState = NB_SUB;
            LinearLayout nb = menuButton(Icon.DOWNLOAD, "Download Nuvio Beacon", nbState, this::downloadNuvioBeacon);
            nbSub = (TextView) ((LinearLayout) nb.getChildAt(1)).getChildAt(1);
            View nbSp = new View(this);
            nb.addView(nbSp, new LinearLayout.LayoutParams(0, 1, 1f));
            final TextView nbMark = V.text(this, NuvioBeaconInstaller.installed(this) != null ? "INSTALLED" : "", 10, 800, V.TEXT);
            nb.addView(nbMark);
            V.tint(nb, V.TEXT);
            menuRefreshers.add(() -> nbMark.setText(NuvioBeaconInstaller.installed(this) != null ? "INSTALLED" : ""));
            menuPanel.addView(nb, lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            if (!ltBusy) ltState = LT_SUB;
            LinearLayout lt = menuButton(Icon.DOWNLOAD, "Download Live TV app", ltState, this::downloadLiveTv);
            ltSub = (TextView) ((LinearLayout) lt.getChildAt(1)).getChildAt(1);
            lt.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            final TextView ltMark = V.text(this, NuvioBeaconInstaller.installed(this, NuvioBeaconInstaller.LIVE_TV) != null ? "INSTALLED" : "", 10, 800, V.TEXT);
            lt.addView(ltMark);
            V.tint(lt, V.TEXT);
            menuRefreshers.add(() -> ltMark.setText(NuvioBeaconInstaller.installed(this, NuvioBeaconInstaller.LIVE_TV) != null ? "INSTALLED" : ""));
            menuPanel.addView(lt, lp(U.MATCH, V.dp(48), 0, 0, 0, 14));
            menuPanel.addView(menuLabel("Changelog"));
            for (Changelog.Entry e : Changelog.ENTRIES) menuPanel.addView(changelogEntry(e), lp(U.MATCH, U.WRAP, 0, 0, 0, 8));
            menuPanel.addView(new View(this), lp(U.MATCH, V.dp(6), 0, 0, 0, 0));
            menuPanel.addView(menuButton(Icon.INFO, "About", "Version " + versionName() + "  ·  ad-free home screen", () -> {}), lp(U.MATCH, V.dp(48), 0, 0, 0, 14));
        } else if ("weather".equals(page)) {
            menuFirst = toggleButton(Icon.SUN, "Show weather by the clock", "weather", true);
            menuPanel.addView(menuFirst, lp(U.MATCH, V.dp(48), 0, 0, 0, 8));
            String city = prefs.getString("wx_city", "");
            menuPanel.addView(menuButton(Icon.SEARCH, "Weather location", TextUtils.isEmpty(city) ? "Automatic \u00b7 search for a city" : city + " \u00b7 search for another", () -> {
                closeMenu();
                U.MAIN.postDelayed(this::editWeatherCity, 250);
            }), lp(U.MATCH, V.dp(48), 0, 0, 0, 14));
        }
        menuPanel.addView(menuButton(Icon.LEFT, "Back", null, () -> openMenuPage(null)), lp(U.MATCH, V.dp(40), 0, 0, 0, 0));
    }

    private View menuHost;

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        menuPage = null;          // the next opening always starts on the hub, like the first time
        menuRestoreIdx = -1;
        reopenMenu = false;
        menuScroll = null;
        menuFirst = null;
        final View host = menuHost;
        menuHost = null;
        menuPanel = null;
        for (int i = 0; i < ui.getChildCount(); i++) {
            View c = ui.getChildAt(i);
            if (c instanceof ViewGroup) ((ViewGroup) c).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }
        if (scrim != null) scrim.animate().alpha(0f).setDuration(160).start();
        if (host != null) host.animate().alpha(0f).translationY(-V.dpf(8)).setDuration(160).withEndAction(() -> ui.removeView(host)).start();
        if (menuPill != null) menuPill.requestFocus();
    }

    private void editWeatherCity() {
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setSingleLine(true);
        et.setHint("City name, e.g. Austin");
        et.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Weather location")
                .setView(et)
                .setPositiveButton("Search", (d, w) -> searchCity(et.getText().toString().trim()))
                .setNeutralButton("Automatic", (d, w) -> {
                    prefs.edit().remove("wx_lat").remove("wx_lon").remove("wx_city").putBoolean("weather", true).apply();
                    loadWeather();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void searchCity(final String q) {
        if (q.isEmpty()) return;
        U.IO.execute(() -> {
            try {
                JSONObject r = new JSONObject(Http.get("https://geocoding-api.open-meteo.com/v1/search?count=6&language=en&format=json&name="
                        + java.net.URLEncoder.encode(q, "UTF-8")));
                final org.json.JSONArray res = r.optJSONArray("results");
                U.MAIN.post(() -> {
                    if (res == null || res.length() == 0) { Toast.makeText(this, "No place found for \u201c" + q + "\u201d", Toast.LENGTH_LONG).show(); return; }
                    ActionSheet sheet = new ActionSheet(this, "Choose your location");
                    for (int i = 0; i < res.length(); i++) {
                        final JSONObject o = res.optJSONObject(i);
                        if (o == null) continue;
                        StringBuilder sb = new StringBuilder(o.optString("name"));
                        String reg = o.optString("admin1"), ctry = o.optString("country_code");
                        if (!reg.isEmpty()) sb.append(", ").append(reg);
                        if (!ctry.isEmpty()) sb.append(", ").append(ctry);
                        final String label = sb.toString();
                        sheet.add(label, Icon.SEARCH, () -> {
                            prefs.edit().putLong("wx_lat", Double.doubleToLongBits(o.optDouble("latitude")))
                                    .putLong("wx_lon", Double.doubleToLongBits(o.optDouble("longitude")))
                                    .putString("wx_city", o.optString("name")).putBoolean("weather", true).apply();
                            loadWeather();
                            Toast.makeText(this, "Weather set to " + label, Toast.LENGTH_SHORT).show();
                        });
                    }
                    sheet.show();
                });
            } catch (Throwable t) {
                U.MAIN.post(() -> Toast.makeText(this, "Couldn't search right now. Check the connection.", Toast.LENGTH_LONG).show());
            }
        });
    }

    private void openDreamSettings() {
        String[] tries = {"android.settings.DREAM_SETTINGS", "android.settings.DISPLAY_SETTINGS", Settings.ACTION_SETTINGS};
        for (String a : tries) {
            try { closeMenu(); startActivity(new Intent(a).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; } catch (Throwable ignored) {}
        }
        Toast.makeText(this, "Open Settings › Device Preferences › Screen saver and pick beacon", Toast.LENGTH_LONG).show();
    }

    private TextView menuLabel(String t) {
        TextView v = V.dim(V.caps(this, t, 8, V.TEXT), 0.55f);
        v.setLetterSpacing(0.12f);
        v.setPadding(0, 0, 0, V.dp(6));
        return v;
    }

    private LinearLayout menuButton(int icon, String title, String sub, Runnable r) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(V.dp(9), 0, V.dp(12), 0);
        row.setFocusable(true);
        row.setFocusableInTouchMode(true);
        row.setBackground(V.shape(V.MENU_ROW, 14, V.LINE));
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(V.shape(0x22FFFFFF, 9, 0));
        Icon ic = new Icon(this, icon);
        tile.addView(ic, new FrameLayout.LayoutParams(V.dp(15), V.dp(15), Gravity.CENTER));
        row.addView(tile, new LinearLayout.LayoutParams(V.dp(28), V.dp(28)));
        LinearLayout tx = U.col(this);
        tx.addView(V.text(this, title, 12, 700, V.TEXT));
        if (sub != null) tx.addView(V.dim(V.text(this, sub, 9, 500, V.TEXT), 0.6f));
        row.addView(tx, lp(U.WRAP, U.WRAP, 10, 0, 0, 0));
        V.tint(row, V.TEXT);
        // same focus look as Home: accent glow, or the solid fill when "Focus style" is set to Fill
        row.setOnFocusChangeListener((v, has) -> V.stylePill(row, has, 14,
                () -> tile.setBackground(V.shape(has && !V.glowFocus ? 0x22000000 : 0x22FFFFFF, 9, 0)), V.MENU_ROW));
        row.setOnClickListener(v -> r.run());
        return row;
    }

    /** Navigation sounds on/off. Plays its own up/down chime, so the automatic select pop is skipped. */
    private View soundToggle() {
        final LinearLayout row = menuButton(Icon.SPEAKER, "Navigation sounds", null, () -> {});
        row.setTag(Sfx.QUIET);
        final TextView state = V.text(this, "", 10, 800, V.TEXT);
        final Runnable paint = () -> state.setText(Sfx.isEnabled() ? "ON" : "OFF");
        paint.run();
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(state);
        V.tint(row, V.TEXT);
        row.setOnClickListener(v -> {
            boolean on = !Sfx.isEnabled();
            if (on) { Sfx.setEnabled(this, true); Sfx.on(); }
            else { Sfx.off(); Sfx.setEnabled(this, false); }
            paint.run();
            if (volumeRow != null) volumeRow.setAlpha(on ? 1f : 0.45f);
        });
        return row;
    }

    /** Volume as 8 rising bars: Left/Right change it by one step and play a tick at the new level. */
    private LinearLayout buildVolumeRow() {
        final LinearLayout row = menuButton(Icon.SPEAKER, "Volume", null, () -> Sfx.preview());
        row.setTag(Sfx.QUIET);
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        final LinearLayout bars = U.row(this);
        bars.setGravity(Gravity.CENTER_VERTICAL);
        final View[] seg = new View[Sfx.LEVELS];
        for (int i = 0; i < Sfx.LEVELS; i++) {
            View b = new View(this);
            seg[i] = b;
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(V.dp(5), V.dp(6 + i * 2));
            bp.leftMargin = i == 0 ? 0 : V.dp(3);
            bars.addView(b, bp);
        }
        row.addView(bars);
        final Runnable paint = () -> {
            int base = row.hasFocus() ? (V.glowFocus ? V.accent() : V.INK) : V.TEXT;
            for (int i = 0; i < seg.length; i++) seg[i].setBackground(V.shape(i < Sfx.level() ? base : U.alpha(base, 0.25f), 2, 0));
        };
        paint.run();
        final View.OnFocusChangeListener baseFocus = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, has) -> { if (baseFocus != null) baseFocus.onFocusChange(v, has); paint.run(); });
        row.setOnKeyListener((v, code, ev) -> {
            if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
            int d = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
            if (d == 0) return false;
            int n = Math.max(1, Math.min(Sfx.LEVELS, Sfx.level() + d));
            if (n != Sfx.level()) { Sfx.setLevel(this, n); paint.run(); Sfx.preview(); }
            return true;
        });
        return row;
    }

    private View toggleButton(int icon, String title, String key, boolean def) {
        final LinearLayout row = menuButton(icon, title, null, () -> {});
        final TextView state = V.text(this, "", 10, 800, V.TEXT);
        final Runnable paint = () -> state.setText(prefs.getBoolean(key, def) ? "ON" : "OFF");
        paint.run();
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(state);
        V.tint(row, V.TEXT);
        row.setOnClickListener(v -> {
            if ("svc_off".equals(key)) reopenMenu = true; // the row appears or disappears: rebuild Home and reopen the menu
            prefs.edit().putBoolean(key, !prefs.getBoolean(key, def)).apply();
            paint.run();
            if ("weather".equals(key)) loadWeather();
        });
        return row;
    }

    private static final String[] CW_STYLES = {"PILLS", "CIRCLES", "CARDS"};

    private int cwStyle() {
        int d = prefs.getBoolean("cw_rings", true) ? 1 : 0;
        return Math.max(0, Math.min(2, prefs.getInt("cw_style", d)));
    }

    /** Continue watching look: Pills, Circles (progress rings) or Cards (posters). Left/Right change it; the stored values are unchanged. */
    private View cwStyleToggle() {
        return cycleRow(Icon.BEACON, "Continue watching", CW_STYLES, this::cwStyle, i -> {
            prefs.edit().putInt("cw_style", i).apply();
            cwSig = "";
            fillContinue();
        }, false);
    }

    /** Pill and button surface: frosted glass, smoke or slate (the last two have no borders). Left/Right change it. */
    private View surfaceToggle() {
        return cycleRow(Icon.BEACON, "Surface", new String[] {"FROSTED GLASS", "SMOKE", "SLATE"},
                () -> prefs.getInt("surface", 0),
                i -> prefs.edit().putInt("surface", i).apply(), true);
    }

    /** Dock style: Pills (name beside each icon), Circles (a dock of round icons, name above the selected one) or Cards (banners). */
    private View appStyleToggle() {
        return cycleRow(Icon.APPS, "Dock style", new String[] {"PILLS", "CIRCLES", "CARDS"},
                () -> prefs.getInt("app_style", 0),
                i -> prefs.edit().putInt("app_style", i).apply(), true);
    }

    private int menuRestoreIdx = -1;

    /**
     * A menu row with a few choices: Left/Right step through them (Select steps forward too), with small arrows
     * on either side of the value. If changing it rebuilds Home, the menu reopens with this row still selected.
     */
    private LinearLayout cycleRow(int icon, String title, final String[] labels, final java.util.function.IntSupplier cur,
                                  final java.util.function.IntConsumer set, final boolean rebuilds) {
        final LinearLayout row = menuButton(icon, title, null, () -> {});
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(new Icon(this, Icon.LEFT), new LinearLayout.LayoutParams(V.dp(10), V.dp(10)));
        final TextView state = V.text(this, labels[cur.getAsInt()], 10, 800, V.TEXT);
        state.setGravity(Gravity.CENTER);
        row.addView(state, lp(U.WRAP, U.WRAP, 6, 0, 6, 0));
        row.addView(new Icon(this, Icon.RIGHT), new LinearLayout.LayoutParams(V.dp(10), V.dp(10)));
        V.tint(row, V.TEXT);
        final java.util.function.IntConsumer move = dir -> {
            int n = (cur.getAsInt() + dir + labels.length) % labels.length;
            if (rebuilds && menuPanel != null) menuRestoreIdx = menuPanel.indexOfChild(row);
            if (rebuilds) reopenMenu = true;
            set.accept(n);
            state.setText(labels[n]);
            Sfx.select();
        };
        row.setOnClickListener(v -> move.accept(1));
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { move.accept(-1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { move.accept(1); return true; }
            return false;
        });
        menuRefreshers.add(() -> state.setText(labels[cur.getAsInt()]));
        return row;
    }

    /** Picture-first Continue watching tile: title and time left on the art, progress along the bottom. */
    private View cwCard(final Nuvio.Item it, final int idx, final boolean big) {
        final int w = V.dp(big ? 340 : 124), h = V.dp(big ? 92 : 66);
        final FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setBackground(V.shape(0x1AFFFFFF, 12, 0));
        V.round(t, 12);
        t.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        ImageView iv = V.image(this, ImageView.ScaleType.CENTER_CROP);
        t.addView(iv, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        Img.load(this, Img.sized(it.cardArt(), "w500"), V.dp(big ? 340 : 124), b -> { if (b != null) iv.setImageBitmap(b); });
        View shade = big
                ? Ui.gradient(this, GradientDrawable.Orientation.LEFT_RIGHT, 0xEB0A0808, 0x330A0808)
                : Ui.gradient(this, GradientDrawable.Orientation.TOP_BOTTOM, 0x00000000, 0xD0000000);
        t.addView(shade, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        long left = it.durationMs > 0 ? Math.max(0, it.durationMs - it.positionMs) : 0;
        String sub = !TextUtils.isEmpty(it.episode) ? it.episode : remainingLine(it);
        if (left > 0) sub = (!TextUtils.isEmpty(it.episode) ? it.episode + "  ·  " : "") + Nuvio.duration(left) + " left";
        LinearLayout tx = U.col(this);
        tx.setGravity(big ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM);
        if (big) {
            TextView lab = V.text(this, "RESUME", 8, 800, V.accent());
            tx.addView(lab, lp(U.WRAP, U.WRAP, 0, 0, 0, 3));
        }
        TextView title = V.text(this, it.title, big ? 15 : 9.5f, 700, 0xFFFFFFFF);
        V.ellipsize(title);
        tx.addView(title, new LinearLayout.LayoutParams(big ? V.dp(230) : U.MATCH, U.WRAP));
        TextView st = V.text(this, sub == null ? "Movie" : sub, big ? 10 : 8, 500, 0xDDFFFFFF);
        V.ellipsize(st);
        tx.addView(st, lp(big ? V.dp(230) : U.MATCH, U.WRAP, 0, 2, 0, 0));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(U.MATCH, U.MATCH);
        tlp.setMargins(V.dp(big ? 14 : 8), V.dp(big ? 8 : 4), V.dp(8), V.dp(big ? 16 : 11));
        t.addView(tx, tlp);
        if (it.progress() > 0) {
            FrameLayout bar = new FrameLayout(this);
            bar.setBackground(V.shape(0x59FFFFFF, 2, 0));
            View fill = new View(this);
            fill.setBackground(V.shape(V.accent(), 2, 0));
            fill.setPivotX(0);
            fill.setScaleX(it.progress());
            bar.addView(fill, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            FrameLayout.LayoutParams blp = U.flp(U.MATCH, V.dp(3), Gravity.BOTTOM);
            blp.setMargins(V.dp(big ? 14 : 8), 0, V.dp(big ? 14 : 8), V.dp(5));
            t.addView(bar, blp);
        }
        final View ring = new View(this);
        ring.setBackground(new V.Glow(12, 0f, 1));
        ring.setAlpha(0f);
        t.addView(ring, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        t.setContentDescription(it.title);
        t.setOnFocusChangeListener((v, has) -> {
            t.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(150).start();
            V.tileGlow(t, has, 12, 1.06f);
            if (has) {
                cwCount.setText(cwItems.size() > 1 ? (idx + 1) + " of " + cwItems.size() : "");
                showItem(it, "");
            }
        });
        t.setOnClickListener(v -> Nuvio.open(this, it));
        t.setOnLongClickListener(v -> cwMenu(it));
        return t;
    }

    /** Focus look on the home screen: accent glow (new) or the original solid fill. Left/Right change it. */
    private View focusStyleToggle() {
        return cycleRow(Icon.BEACON, "Focus style", new String[] {"GLOW", "FILL"},
                () -> prefs.getBoolean("focus_glow", true) ? 0 : 1,
                i -> prefs.edit().putBoolean("focus_glow", i == 0).apply(), true);
    }

    /** Focused chip: accent glow like the rest of the menu, or the solid fill. */
    private void focusChip(TextView c) {
        if (V.glowFocus) {
            c.setBackground(V.glowShape(15));
            c.setTextColor(V.TEXT);
            V.glow(c, true);
        } else {
            c.setBackground(V.shape(V.TEXT, 15, 0));
            c.setTextColor(V.INK);
        }
    }

    private LinearLayout chipRow(String[] labels, int selected, java.util.function.IntConsumer onPick) {
        final LinearLayout row = U.row(this);
        final TextView[] chips = new TextView[labels.length];
        final int[] sel = {selected};
        final Runnable paint = () -> {
            for (int i = 0; i < chips.length; i++) {
                boolean on = i == sel[0];
                if (!chips[i].hasFocus()) {
                    chips[i].setBackground(on ? V.shape(U.alpha(V.accent(), 0.28f), 15, V.accent()) : V.shape(V.MENU_ROW, 15, V.LINE));
                    chips[i].setTextColor(V.TEXT);
                }
            }
        };
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            final TextView c = V.text(this, labels[i], 10, 700, V.TEXT);
            c.setGravity(Gravity.CENTER);
            c.setFocusable(true);
            c.setFocusableInTouchMode(true);
            chips[i] = c;
            c.setOnFocusChangeListener((v, has) -> {
                if (has) focusChip(c); else paint.run();
            });
            c.setOnClickListener(v -> { sel[0] = idx; onPick.accept(idx); paint.run(); focusChip(c); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, U.MATCH, 1f);
            p.rightMargin = i < labels.length - 1 ? V.dp(5) : 0;
            row.addView(c, p);
        }
        paint.run();
        return row;
    }

    private LinearLayout themeRow() {
        final LinearLayout row = U.row(this);
        themeDots.clear();
        String cur = prefs.getString("theme", "ember");
        for (final String[] t : V.THEMES) {
            final boolean on = t[0].equals(cur);
            final int acc = Color.parseColor(t[3]);
            final View b = new View(this);
            b.setFocusable(true);
            b.setFocusableInTouchMode(true);
            b.setContentDescription(t[1]);
            final android.graphics.drawable.GradientDrawable idle = new android.graphics.drawable.GradientDrawable();
            idle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            idle.setColor(acc);
            idle.setStroke(V.dp(on ? 2.5f : 1), on ? V.TEXT : 0x55FFFFFF);
            b.setBackground(idle);
            b.setOnFocusChangeListener((v, has) -> {
                b.animate().scaleX(has ? 1.25f : 1f).scaleY(has ? 1.25f : 1f).setDuration(140).start();
                android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
                g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                g.setColor(acc);
                g.setStroke(V.dp(has ? 3 : on ? 2.5f : 1), has || on ? V.TEXT : 0x55FFFFFF);
                b.setBackground(g);
            });
            b.setOnClickListener(v -> { menuFocusDot = true; prefs.edit().putString("theme", t[0]).apply(); });
            themeDots.add(b);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(V.dp(24), V.dp(24));
            p.rightMargin = V.dp(9);
            row.addView(b, p);
        }
        return row;
    }

    // ------------------------------------------------------------------ theme colors

    private final List<View> themeDots = new ArrayList<>();
    private View themeOption, themeDotRow, themeWrap;
    private boolean menuFocusDot, themeOpen;
    private android.animation.ValueAnimator themeAnim;
    private static final String[] THEME_MODES = {"nuvio", "artwork", "choose"};
    private static final float PEEK_DP = 12, OPEN_DP = 30; // how far the option sits below the colour circles

    private View selectedThemeDot() {
        String cur = prefs.getString("theme", "ember");
        for (int i = 0; i < V.THEMES.length && i < themeDots.size(); i++) if (V.THEMES[i][0].equals(cur)) return themeDots.get(i);
        return themeDots.isEmpty() ? themeOption : themeDots.get(0);
    }

    private void setDotsFocusable(boolean on) {
        for (View d : themeDots) { d.setFocusable(on); d.setFocusableInTouchMode(on); }
    }

    /**
     * "Theme colors": Follow Nuvio / From artwork / Choose. The colour circles sit half hidden behind the option
     * (peeking over its top edge) and slide out when Choose is picked, with the selection moving up to the chosen colour.
     */
    private View buildThemeColors() {
        final FrameLayout box = new FrameLayout(this);
        box.setClipChildren(false);
        box.setClipToPadding(false);
        final LinearLayout dots = themeRow();
        themeDotRow = dots;
        FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(U.WRAP, V.dp(24));
        dp.leftMargin = V.dp(16);
        box.addView(dots, dp);
        themeOption = cycleRow(Icon.EYE, "Theme colors", new String[] {"FOLLOW NUVIO", "FROM ARTWORK", "CHOOSE"}, () -> {
            String m = V.themeMode(this);
            for (int i = 0; i < THEME_MODES.length; i++) if (THEME_MODES[i].equals(m)) return i;
            return 1;
        }, i -> {
            prefs.edit().putString("theme_mode", THEME_MODES[i]).apply();
            setThemeOpen(i == 2, true);
        }, false);
        // A solid panel-coloured cover sits under the option so nothing of the circles shows through it.
        final FrameLayout wrap = new FrameLayout(this);
        themeWrap = wrap;
        View cover = new View(this);
        cover.setBackgroundColor(V.MENU_PANEL);
        wrap.addView(cover, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        wrap.addView(themeOption, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        FrameLayout.LayoutParams op = new FrameLayout.LayoutParams(U.MATCH, V.dp(48));
        box.addView(wrap, op); // added last, so it covers the lower half of the circles
        themeOpen = "choose".equals(V.themeMode(this));
        op.topMargin = V.dp(themeOpen ? OPEN_DP : PEEK_DP);
        setDotsFocusable(themeOpen);
        return box;
    }

    private void setThemeOpen(final boolean open, boolean animate) {
        if (themeWrap == null || open == themeOpen && animate) return;
        themeOpen = open;
        if (themeAnim != null) themeAnim.cancel();
        final FrameLayout.LayoutParams op = (FrameLayout.LayoutParams) themeWrap.getLayoutParams();
        final int from = op.topMargin, to = V.dp(open ? OPEN_DP : PEEK_DP);
        if (open) setDotsFocusable(true);
        final Runnable done = () -> {
            op.topMargin = to;
            themeWrap.setLayoutParams(op);
            if (!open) setDotsFocusable(false);
            refitMenuKeeping(themeOption);
            if (open) { View d = selectedThemeDot(); if (d != null) d.requestFocus(); } // selection moves up to the chosen colour
        };
        if (!animate) { done.run(); return; }
        themeAnim = android.animation.ValueAnimator.ofFloat(0f, 1f);
        themeAnim.setDuration(240);
        themeAnim.setInterpolator(new android.view.animation.DecelerateInterpolator());
        themeAnim.addUpdateListener(a -> {
            op.topMargin = Math.round(from + (to - from) * (Float) a.getAnimatedValue());
            themeWrap.setLayoutParams(op);
        });
        themeAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator an) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator an) { if (!cancelled) done.run(); }
        });
        themeAnim.start();
    }

    /** The theme-colours source changed: refresh the accent without rebuilding Home (so the menu animation can play). */
    private void applyThemeMode() {
        final int before = V.accent();
        if (V.artAccent == null) V.artAccent = before; // keep showing the old colour until the fade below starts (loadTheme changes the base colour)
        V.loadTheme(this);
        if ("nuvio".equals(V.themeMode(this))) refreshProfiles(); // read the active profile's colour now, in case none is remembered yet
        if (shade != null && !V.artTint(this)) setAccent(null); // fades to the Nuvio profile's colour or the chosen theme
    }

    /** A new theme was picked: swap colours in place in one frame (no rebuild of Home, so nothing flickers). */
    private void applyThemeInstant() {
        if (accentFade != null) accentFade.cancel();
        V.artAccent = null;
        V.loadTheme(this);
        if (root.getBackground() != null) root.setBackgroundColor(V.BG);
        if (shade != null) shade.set(V.BG, null);
        if (heroLabel != null) heroLabel.setTextColor(V.accent());
        paintCwHead(V.accent());
        if (heroProgFill != null) heroProgFill.setBackground(V.shape(V.accent(), 2, 0));
        redrawAll(root);
        if (menuOpen && menuHost != null) { // rebuild the open menu in the new colours, swapping it in the same frame
            ui.removeView(menuHost);
            menuOpen = false;
            menuHost = null; menuPanel = null; menuScroll = null; menuFirst = null;
            menuFocusDot = true;
            showMenu(true);
        }
    }

    private View scrim, topBar;

    // ------------------------------------------------------------------ memory panel

    private LinearLayout memPanel;
    private final Runnable openMem = this::showMem;

    private void showMem() {
        if (memPanel == null) {
            memPanel = U.col(this);
            memPanel.setPadding(V.dp(16), V.dp(16), V.dp(16), V.dp(16));
            memPanel.setBackground(V.shape(U.alpha(U.mix(V.BG, 0xFFFFFFFF, 0.16f), 0.98f), 20, 0x40FFFFFF));
            FrameLayout.LayoutParams lp = U.flp(V.dp(330), U.WRAP, Gravity.TOP | Gravity.END);
            lp.topMargin = V.dp(26 + (EDGE - 48) / 2 + 34 + 8);
            lp.rightMargin = V.dp(EDGE + 3 * 39);
            if (scrim == null) {
                scrim = new View(this);
                scrim.setBackgroundColor(0x8C000000);
                scrim.setAlpha(0f);
                ui.addView(scrim, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            }
            ui.addView(memPanel, lp);
            memPanel.setAlpha(0f);
        }
        scrim.bringToFront();
        topBar.bringToFront();
        memPanel.bringToFront();
        fillMem(MemStats.cached(), null);
        scrim.animate().cancel();
        scrim.animate().alpha(1f).setDuration(200).start();
        memPanel.animate().cancel();
        memPanel.setTranslationY(-V.dpf(12));
        memPanel.animate().alpha(1f).translationY(0).setDuration(200).withLayer().start();
        memLoading = true;
        U.IO.execute(() -> {
            final MemStats.Stats st = MemStats.load(getApplicationContext(), false);
            U.MAIN.post(() -> { memLoading = false; if (memPanel != null && memPanel.getAlpha() > 0) fillMem(st, null); });
        });
        U.MAIN.postDelayed(() -> {
            if (memLoading && "waiting".equals(AdbLocal.state) && memPanel != null && memPanel.getAlpha() > 0) {
                MemStats.Stats w = new MemStats.Stats();
                w.noPermission = true;
                MemStats.Stats c = MemStats.cached();
                if (c != null) { w.totalKb = c.totalKb; w.availKb = c.availKb; }
                fillMem(c == null ? null : w, null);
                if (c == null) fillMem(w, null);
            }
        }, 1500);
    }

    private void hideMem() {
        if (scrim != null) { scrim.animate().cancel(); scrim.animate().alpha(0f).setDuration(120).start(); }
        if (memPanel != null) memPanel.animate().alpha(0f).translationY(-V.dpf(12)).setDuration(120).withLayer().start();
    }

    private boolean freeing, memLoading;

    private void freeUpMemory() {
        if (freeing) return;
        freeing = true;
        if (memPanel != null) fillMem(MemStats.cached(), "Closing background apps\u2026");
        U.IO.execute(() -> {
            final long freed = MemStats.freeUp(getApplicationContext());
            final MemStats.Stats st = MemStats.cached();
            U.MAIN.post(() -> {
                freeing = false;
                updateMemory();
                if (memPanel != null && memPanel.getAlpha() > 0) fillMem(st, freed > 1024 ? "Freed " + MemStats.mb(freed) : "Nothing more to free right now");
            });
        });
    }

    private void fillMem(MemStats.Stats st, String note) {
        memPanel.removeAllViews();
        LinearLayout head = U.row(this);
        LinearLayout ht = U.col(this);
        ht.addView(V.text(this, "Memory", 13, 800, V.TEXT));
        String sub = st == null ? "Checking\u2026" : MemStats.mb(st.availKb) + " free of " + MemStats.mb(st.totalKb);
        ht.addView(V.dim(V.text(this, sub, 9, 500, V.TEXT), 0.7f), lp(U.WRAP, U.WRAP, 0, 2, 0, 0));
        head.addView(ht, new LinearLayout.LayoutParams(0, U.WRAP, 1f));
        memPanel.addView(head);
        if (st != null) {
            FrameLayout track = new FrameLayout(this);
            track.setBackground(V.shape(0x33FFFFFF, 3, 0));
            View fill = new View(this);
            float used = 1f - st.availKb / (float) Math.max(1, st.totalKb);
            fill.setBackground(V.shape(used > 0.85f ? 0xFFFF6B5C : V.accent(), 3, 0));
            track.addView(fill, new FrameLayout.LayoutParams(Math.round(V.dp(298) * used), U.MATCH));
            memPanel.addView(track, lp(U.MATCH, V.dp(5), 0, 10, 0, 0));
        }
        if (st != null && st.noPermission) {
            String msg;
            if ("waiting".equals(AdbLocal.state)) msg = "On screen now: \u201cAllow network debugging?\u201d \u2014 tick Always allow and choose Allow. beacon only uses it to read memory and close background apps.";
            else if ("denied".equals(AdbLocal.state)) msg = "Permission was declined. Open this again to get the prompt back.";
            else msg = "Network debugging is off on the projector, so per-app memory can't be read. Turn it on in Developer options to see it here.";
            memPanel.addView(V.dim(V.text(this, msg, 10, 500, V.TEXT), 0.8f), lp(U.MATCH, U.WRAP, 0, 12, 0, 0));
        } else if (st != null) {
            memPanel.addView(V.dim(V.caps(this, "Using the most", 8, V.TEXT), 0.6f), lp(U.WRAP, U.WRAP, 0, 14, 0, 6));
            long max = st.apps.isEmpty() ? 1 : st.apps.get(0).kb;
            int shown = 0;
            for (MemStats.App a : st.apps) {
                if (shown >= 7) break;
                shown++;
                LinearLayout r = U.row(this);
                TextView n = V.text(this, a.label + (a.self ? " (this home screen)" : ""), 10, 600, V.TEXT);
                V.ellipsize(n);
                r.addView(n, new LinearLayout.LayoutParams(0, U.WRAP, 1f));
                FrameLayout bar = new FrameLayout(this);
                View f = new View(this);
                f.setBackground(V.shape(a.system ? 0x59FFFFFF : V.accent(), 2, 0));
                bar.addView(f, new FrameLayout.LayoutParams(Math.max(V.dp(2), Math.round(V.dp(60) * a.kb / (float) max)), U.MATCH));
                r.addView(bar, lp(V.dp(60), V.dp(4), 8, 0, 8, 0));
                TextView mbv = V.dim(V.text(this, MemStats.mb(a.kb), 10, 500, V.TEXT), 0.75f);
                mbv.setGravity(Gravity.END);
                r.addView(mbv, new LinearLayout.LayoutParams(V.dp(52), U.WRAP));
                memPanel.addView(r, lp(U.MATCH, U.WRAP, 0, 3, 0, 3));
            }
        }
        String foot = note != null ? note : "OK closes background apps you aren't using \u00b7 grey = Android itself";
        memPanel.addView(V.dim(V.text(this, foot, 8.5f, 500, V.TEXT), note != null ? 0.9f : 0.5f), lp(U.WRAP, U.WRAP, 0, 12, 0, 0));
    }

    private void updateMemory() {
        if (memText == null) return;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            float used = 1f - mi.availMem / (float) mi.totalMem;
            memFill.setScaleX(Math.max(0.02f, Math.min(1f, used)));
            memFill.setBackground(V.shape(used > 0.85f ? 0xFFFF6B5C : V.accent(), 2, 0));
            String free = mi.availMem >= 1L << 30
                    ? String.format(java.util.Locale.US, "%.1f GB", mi.availMem / (float) (1L << 30))
                    : (mi.availMem >> 20) + " MB";
            memText.setText(free + " free");
        } catch (Throwable ignored) {}
    }

    private View roundButton(int icon, String label, View.OnClickListener l) {
        View b = V.iconButton(this, new Icon(this, icon), label);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams lp(int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(V.dp(l), V.dp(t), V.dp(r), V.dp(b));
        return p;
    }

    // ------------------------------------------------------------------ hero

    private void buildHero(LinearLayout col) {
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        // Fading a view group normally draws it into a temporary image cut to its own box, which cropped the
        // title artwork where it overhangs the box (the top or bottom slice appeared only when the fade ended).
        // Fading each child directly keeps the overhang visible all the way through.
        hero.forceHasOverlappingRendering(false);
        hero.setPadding(V.dp(EDGE), 0, V.dp(EDGE), 0);
        heroLabel = V.caps(this, "", 10, V.accent());
        hero.addView(heroLabel);
        heroLogo = V.image(this, ImageView.ScaleType.FIT_START);
        heroLogo.setAdjustViewBounds(true);
        heroLogo.setMaxHeight(V.dp(78));
        heroLogo.setMaxWidth(V.dp(420));
        heroLogo.setVisibility(View.GONE);
        hero.addView(heroLogo, lp(U.WRAP, U.WRAP, 0, 8, 0, 0));
        heroTitle = V.text(this, "", 44, 800, V.TEXT);
        heroTitle.setLetterSpacing(-0.03f);
        heroTitle.setMaxLines(1);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        // one row: long titles shrink to fit instead of wrapping or being cut off
        heroTitle.setAutoSizeTextTypeUniformWithConfiguration(18, 44, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        hero.addView(heroTitle, lp(V.dp(600), U.WRAP, 0, 6, 0, 0));
        heroLine = V.text(this, "", 14, 400, U.alpha(V.TEXT, 0.85f));
        V.ellipsize(heroLine);
        heroLine.setMaxWidth(V.dp(560));
        hero.addView(heroLine, lp(U.WRAP, U.WRAP, 0, 8, 0, 0));
        heroProgRow = U.row(this);
        FrameLayout track = new FrameLayout(this);
        track.setBackground(V.shape(0x40FFFFFF, 2, 0));
        heroProgFill = new View(this);
        heroProgFill.setBackground(V.shape(V.accent(), 2, 0));
        heroProgFill.setPivotX(0);
        track.addView(heroProgFill, new FrameLayout.LayoutParams(V.dp(120), U.MATCH));
        heroProgRow.addView(track, new LinearLayout.LayoutParams(V.dp(120), V.dp(2.5f)));
        heroRemaining = V.text(this, "", 11, 400, U.alpha(V.TEXT, 0.8f));
        heroProgRow.addView(heroRemaining, lp(U.WRAP, U.WRAP, 9, 0, 0, 0));
        hero.addView(heroProgRow, lp(U.WRAP, U.WRAP, 0, 10, 0, 0));
        col.addView(hero, new LinearLayout.LayoutParams(U.MATCH, V.dp(150)));
        hero.setGravity(Gravity.BOTTOM);
        hero.setClipChildren(false);
        hero.setClipToPadding(false);
    }

    /** Hero text glides in: old fades out quickly, new fades in and rises 12 dp. */
    private Runnable pendingHero;

    private void setHero(final Object key, final String label, final String title, final String logoUrl, final String line,
                         final float progress, final String remaining, final String art) {
        if (key != null && key.equals(heroKey)) { if (pendingHero != null) U.MAIN.removeCallbacks(pendingHero); return; }
        if (pendingHero != null) U.MAIN.removeCallbacks(pendingHero);
        pendingHero = () -> setHeroNow(key, label, title, logoUrl, line, progress, remaining, art);
        U.MAIN.postDelayed(pendingHero, heroKey == null && "greet".equals(key) ? 0 : 140);
    }

    private Object wantKey;
    private String wantLogo;
    private boolean heroSwapping;

    private Object cwHeroKey;   // key of the Continue watching item the hero is (about to be) showing
    private boolean heroCw;     // true while the hero shows a Continue watching item: bigger artwork, no extra lines

    private static final String LIVE_LOGO = "asset:live-tv-logo-v2.png";
    private static final String LIVE_BACKDROP = "asset:live-backdrop-v4.jpg";

    /** Streaming service logos (and Live TV), decoded once when the row is built so the hero never waits for them. */
    private final java.util.Map<String, Bitmap> svcLogos = new java.util.concurrent.ConcurrentHashMap<>();

    private void prefetchServiceLogos() {
        final java.util.List<String> urls = new java.util.ArrayList<>();
        urls.add(LIVE_LOGO);
        for (Streaming.Service sv : Streaming.visible(this)) if (!TextUtils.isEmpty(sv.logo)) urls.add(sv.logo);
        final Context ac = getApplicationContext();
        final int w = V.dp(420);
        // and the colour of each backdrop, so the glow around the picture is ready when the tile is focused
        final java.util.List<String> arts = new java.util.ArrayList<>();
        if (artMode() && V.artTint(this)) {
            arts.add(LIVE_BACKDROP);
            for (Streaming.Service sv : Streaming.visible(this)) if (!TextUtils.isEmpty(sv.hero)) arts.add(sv.hero);
        }
        U.IO.execute(() -> {
            for (String u : urls) {
                if (svcLogos.containsKey(u)) continue;
                Bitmap b = Img.loadSync(ac, u, w);
                if (b != null) svcLogos.put(u, b);
            }
            for (final String a : arts) {
                Bitmap small = Img.loadSync(ac, a, 160);
                if (small == null) continue;
                final Integer c = ArtColor.extract(small);
                U.MAIN.post(() -> { if (!artColors.containsKey(a)) artColors.put(a, c); });
            }
        });
    }

    private void loadHeroLogo(final int token, final String url) {
        final boolean cw = heroCw;
        Bitmap pre = cw ? null : svcLogos.get(url);
        if (pre != null) { showHeroLogo(token, cw, pre); return; }
        Img.load(this, url, V.dp(cw ? 700 : 420), bm -> {
            if (token != heroToken) return;
            if (bm == null) { heroTitle.setVisibility(View.VISIBLE); return; }   // no artwork after all: fall back to the name
            showHeroLogo(token, cw, bm);
        });
    }

    private void showHeroLogo(final int token, final boolean cw, final Bitmap bm) {
        if (token != heroToken) return;
        ViewGroup.LayoutParams lpl = heroLogo.getLayoutParams();
        if (lpl instanceof LinearLayout.LayoutParams) ((LinearLayout.LayoutParams) lpl).topMargin = cw ? 0 : V.dp(8);
        if (cw) {
            // Fit into the box between the beacon menu and the Continue watching heading, kept in proportion.
            // Never wider than about 36% of the screen, measured from the left edge (10% smaller than before).
            float maxW = getResources().getDisplayMetrics().widthPixels * 0.36f;
            float maxH = V.dp(151);
            float k = Math.min(maxW / Math.max(1, bm.getWidth()), maxH / Math.max(1, bm.getHeight()));
            lpl.width = Math.round(bm.getWidth() * k);
            lpl.height = Math.round(bm.getHeight() * k);
            heroLogo.setMaxHeight(V.dp(151));
            heroLogo.setMaxWidth(Integer.MAX_VALUE);
        } else {
            // streaming services and everything else: the original size
            lpl.width = U.WRAP;
            lpl.height = U.WRAP;
            heroLogo.setMaxHeight(V.dp(78));
            heroLogo.setMaxWidth(V.dp(420));
        }
        heroLogo.setLayoutParams(lpl);
        heroLogo.setImageBitmap(bm);
        // Hidden until it has been sized and placed, then faded in whole (no part of it appearing late).
        heroLogo.animate().cancel();
        heroLogo.setAlpha(cw ? 0f : 1f);
        heroLogo.setVisibility(View.VISIBLE);
        heroTitle.setVisibility(View.GONE);
        centerHeroLogo(cw);
    }

    /**
     * Continue watching artwork is centred (by measurement) between the bottom of the beacon menu button and the top of
     * the "Continue watching" heading, whatever size the hero area ends up. Everything else stays where it was.
     */
    private void centerHeroLogo(final boolean cw) {
        heroLogo.setTranslationY(0);
        heroLogo.setScaleX(1f);
        heroLogo.setScaleY(1f);
        if (!cw || menuPill == null || cwBlock == null) { heroLogo.setAlpha(1f); return; }
        final android.view.ViewTreeObserver vto = heroLogo.getViewTreeObserver();
        vto.addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                android.view.ViewTreeObserver o = heroLogo.getViewTreeObserver();
                if (o.isAlive()) o.removeOnPreDrawListener(this);
                if (!heroCw || heroLogo.getHeight() <= 0) { heroLogo.setAlpha(1f); return true; }
                int[] a = new int[2], b = new int[2], c = new int[2];
                menuPill.getLocationInWindow(a);
                cwBlock.getLocationInWindow(b);
                heroLogo.getLocationInWindow(c);
                float top = a[1] + menuPill.getHeight();
                float bottom = b[1] - cwBlock.getTranslationY();
                // Shrink it (from its left edge) if it's taller than the gap, keeping space above and below.
                float room = (bottom - top) - 2 * V.dp(12);
                float k = room > 0 && heroLogo.getHeight() > room ? room / heroLogo.getHeight() : 1f;
                heroLogo.setPivotX(0);
                heroLogo.setPivotY(heroLogo.getHeight() / 2f);
                heroLogo.setScaleX(k);
                heroLogo.setScaleY(k);
                // where the logo sits now, ignoring the hero's slide-in and any shift already applied
                float centre = c[1] - hero.getTranslationY() - heroLogo.getTranslationY() + heroLogo.getHeight() / 2f;
                heroLogo.setTranslationY((top + bottom) / 2f - centre);
                getWindow().getDecorView().invalidate();   // the artwork can overhang the hero box: repaint all of it
                if (heroLogo.getAlpha() < 1f) heroLogo.animate().alpha(1f).setDuration(160).start();
                return true;
            }
        });
    }

    /** A logo found after the hero was already queued: shown now if the text has landed, else when it does. */
    private void applyHeroLogo(Object key, String url) {
        wantKey = key;
        wantLogo = url;
        if (key != null && key.equals(heroKey) && !heroSwapping) loadHeroLogo(heroToken, url);
    }

    private void setHeroNow(Object key, String label, String title, String logoUrl, String line,
                            float progress, String remaining, String art) {
        if (key != null && key.equals(heroKey)) return;
        heroKey = key;
        final int token = ++heroToken;
        heroSwapping = true;
        hero.animate().cancel();
        hero.animate().alpha(0f).setDuration(140).withEndAction(() -> {
            if (token != heroToken) return;
            heroCw = key != null && key.equals(cwHeroKey);
            ViewGroup.LayoutParams hlp = hero.getLayoutParams();
            int wantH = V.dp(heroCw ? 176 : 150);
            if (hlp.height != wantH) { hlp.height = wantH; hero.setLayoutParams(hlp); }
            hero.setGravity(heroCw ? Gravity.CENTER_VERTICAL : Gravity.BOTTOM); // Continue watching art sits centred between the menu and the heading
            heroLabel.setText(label);
            heroLabel.setVisibility(TextUtils.isEmpty(label) ? View.GONE : View.VISIBLE);
            heroLabel.setTextColor(V.accent());
            heroTitle.setText(title);
            final String logo = !TextUtils.isEmpty(logoUrl) ? logoUrl
                    : key != null && key.equals(wantKey) && wantLogo != null ? wantLogo : null;
            // When artwork is on its way the name stays hidden, so it never flashes up before the logo.
            heroTitle.setVisibility(logo == null ? View.VISIBLE : View.GONE);
            heroLogo.setVisibility(View.GONE);
            heroLogo.setImageDrawable(null);
            heroLine.setText(line == null ? "" : line);
            U.gone(heroLine, TextUtils.isEmpty(line));
            heroProgRow.setVisibility(!heroCw && (progress > 0 || !TextUtils.isEmpty(remaining)) ? View.VISIBLE : View.GONE);
            heroProgFill.setScaleX(Math.max(0.01f, progress));
            heroProgFill.setVisibility(progress > 0 ? View.VISIBLE : View.INVISIBLE);
            ((View) heroProgFill.getParent()).setVisibility(progress > 0 ? View.VISIBLE : View.GONE);
            heroRemaining.setText(remaining == null ? "" : remaining);
            heroRemaining.setVisibility(View.VISIBLE);
            heroSwapping = false;
            // Logos already decoded are put in place before the fade, so they arrive with the rest.
            if (logo != null) loadHeroLogo(token, logo);
            // A quick fade in place (no slide up).
            hero.setTranslationY(0);
            hero.animate().alpha(ambient ? 0f : 1f).setStartDelay(0).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator(1.2f)).start();
        }).start();
        if (!TextUtils.isEmpty(art)) {
            // Full-screen fades are the heaviest thing this GPU draws, so the backdrop only changes once
            // focus has rested; flicking along a row moves just the text. The colour glow follows after
            // the backdrop has finished fading, never at the same time.
            final boolean first = wall == null || !wall.hasImage();
            U.MAIN.postDelayed(() -> {
                if (token != heroToken) return;
                if (art.equals(wallArt) && !first) return; // same picture already up: don't fade or re-zoom it
                Img.load(this, art, WALL_W, bm -> {
                    if (token != heroToken || bm == null) return;
                    if (art.equals(wallArt) && !first) return;
                    if (wall != null) {
                        wallArt = art;
                        wall.show(bm);
                        if (root.getBackground() != null) root.setBackground(null); // the picture covers the screen now
                    }
                });
            }, first ? 0 : 420);
            // The colour does not wait for the backdrop: it is read from a small copy of the picture almost at once.
            if (artMode() && V.artTint(this)) {
                if (artColors.containsKey(art)) {
                    setAccent(artColors.get(art));   // colour already known for this picture: no waiting
                } else U.MAIN.postDelayed(() -> {
                    if (token != heroToken) return;
                    Img.load(this, art, 160, small -> {
                        if (token != heroToken || small == null) return;
                        U.IO.execute(() -> {
                            final Integer c = ArtColor.extract(small);
                            U.MAIN.post(() -> { artColors.put(art, c); if (token == heroToken) setAccent(c); });
                        });
                    });
                }, 80);
            }
        }
    }

    /** Colour read from each backdrop (null when it has none worth using), so going back to a tile is instant. */
    private final java.util.Map<String, Integer> artColors = new java.util.HashMap<>();

    private android.animation.ValueAnimator accentFade;

    /** Fades the accent colour (focus outlines, labels, glow) to the new one instead of snapping. */
    private void setAccent(Integer raw) {
        if (accentFade != null) accentFade.cancel();
        final int from = V.accent();
        final int to = raw == null ? V.themeAccent : ArtColor.asAccent(raw);
        final Integer target = raw == null ? null : to;
        shade.set(V.BG, raw == null ? null : ArtColor.asGlow(raw));
        if (from == to) { V.artAccent = target; Ui.accent = V.accent(); heroLabel.setTextColor(V.accent()); paintCwHead(V.accent()); heroProgFill.setBackground(V.shape(V.accent(), 2, 0)); redrawAll(root); return; }
        final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
        accentFade = android.animation.ValueAnimator.ofFloat(0f, 1f);
        accentFade.setDuration(260);
        accentFade.addUpdateListener(a -> {
            V.artAccent = (Integer) ev.evaluate(a.getAnimatedFraction(), from, to);
            heroLabel.setTextColor(V.artAccent);
            paintCwHead(V.artAccent);
            heroProgFill.setBackground(V.shape(V.artAccent, 2, 0));
            Ui.accent = V.artAccent;
            redrawAll(root);
        });
        accentFade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { V.artAccent = target; Ui.accent = V.accent(); }
        });
        accentFade.start();
    }

    private static void redrawAll(View v) {
        if (v == null) return;
        v.invalidate();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) redrawAll(g.getChildAt(i));
        }
    }

    /** Nothing to say until something is selected: the hero area starts out blank. */
    private void showGreeting() {
        heroKey = null;
        setHero("greet", "", "", null, "", 0, null, null);
    }

    // ------------------------------------------------------------------ rows

    private TextView header(LinearLayout block, String title) {
        LinearLayout h = U.row(this);
        h.setGravity(Gravity.BOTTOM);
        h.setPadding(V.dp(EDGE), 0, V.dp(EDGE), 0);
        h.addView(V.text(this, title, 12, 700, V.TEXT));
        TextView extra = V.text(this, "", 9, 400, U.alpha(V.TEXT, 0.6f));
        h.addView(extra, lp(U.WRAP, U.WRAP, 7, 0, 0, 1));
        block.addView(h);
        return extra;
    }

    private LinearLayout row(LinearLayout block, float spacing) {
        HorizontalScrollView hs = new AnchoredRow(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setHorizontalFadingEdgeEnabled(false);
        hs.setFocusable(false);
        hs.setClipToPadding(false);
        LinearLayout r = U.row(this);
        r.setPadding(V.dp(EDGE), V.dp(6), V.dp(EDGE), V.dp(8));
        r.setClipChildren(false);
        r.setTag(spacing);
        hs.addView(r, new FrameLayout.LayoutParams(U.WRAP, U.WRAP));
        // Room for the focus glow: the scroller clips what it draws, so pad it and pull the layout back with margins.
        int gp = V.dp(30);
        hs.setPadding(0, gp, 0, gp);
        hs.setClipChildren(false);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(U.MATCH, U.WRAP);
        hp.topMargin = -gp;
        hp.bottomMargin = -gp;
        block.addView(hs, hp);
        return r;
    }

    private void buildContinue(LinearLayout col) {
        cwBlock = U.col(this);
        LinearLayout h = U.row(this);
        h.setGravity(Gravity.BOTTOM);
        h.setPadding(V.dp(EDGE), 0, V.dp(EDGE), 0);
        cwHead = V.text(this, "", 18, 800, V.TEXT);   // white, with the profile's name in the theme colour
        paintCwHead(V.accent());
        h.addView(cwHead);
        cwCount = V.text(this, "", 10, 400, U.alpha(V.TEXT, 0.6f));
        h.addView(cwCount, lp(U.WRAP, U.WRAP, 10, 0, 0, 4));
        cwTonight = V.text(this, "", 10, 400, U.alpha(V.TEXT, 0.6f));
        h.addView(cwTonight, lp(U.WRAP, U.WRAP, 8, 0, 0, 4));
        cwBlock.addView(h);
        cwRow = row(cwBlock, 11);
        cwBlock.addView(space(8));
        col.addView(cwBlock, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
        cwBlock.setVisibility(View.GONE);
        // The gap the artwork sits in changes when rows above or below appear or go: fit it again.
        cwBlock.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (t != ot && heroCw && heroLogo != null && heroLogo.getVisibility() == View.VISIBLE) centerHeroLogo(true);
        });
    }

    private void buildServices(LinearLayout col) {
        LinearLayout block = U.col(this);
        header(block, "Streaming services").setText("on Nuvio");
        svcRow = row(block, 12);
        if (prefs.getBoolean("svc_off", false)) block.setVisibility(View.GONE); // gone, not just invisible: the rows above move down
        col.addView(block, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
    }

    private void buildApps(LinearLayout col) {
        LinearLayout block = U.col(this);
        appCircle = prefs.getInt("app_style", 0) == 1;
        appCards = prefs.getInt("app_style", 0) == 2;
        // A box with no height of its own above the dock. What floats around the dock (the Circle name label, the All apps
        // arrow) lives in it, so it takes no space: the dock stays centred on the screen and the rows above never move.
        dockBox = new FrameLayout(this);
        dockBox.setClipChildren(false);
        block.setClipChildren(false);
        dockLabel = null;
        if (appCircle) {
            // Circle dock: the selected app's name floats in a small label above the dock.
            dockLabel = V.text(this, "", 12, 600, V.TEXT);
            dockLabel.setSingleLine(true);
            dockLabel.setGravity(Gravity.CENTER);
            dockLabel.setPadding(V.dp(11), 0, V.dp(11), 0);
            dockLabel.setBackground(V.shape(V.GLASS_STRONG, 10, V.LINE));
            dockLabel.setVisibility(View.INVISIBLE);
            // The label floats over the gap above the dock (block and box must not clip it).
            FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(U.WRAP, V.dp(18), Gravity.TOP | Gravity.START);
            dl.topMargin = -V.dp(7); // label spans -7..11, dock starts at 14
            dockBox.addView(dockLabel, dl);
        }
        dockArrow = new Icon(this, Icon.CHEVRON_DOWN);
        dockArrow.setColor(U.alpha(V.TEXT, 0.5f));
        dockArrow.setVisibility(View.INVISIBLE);
        dockArrow.setFocusable(false);
        dockArrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        dockBox.addView(dockArrow, new FrameLayout.LayoutParams(V.dp(ARROW), V.dp(ARROW), Gravity.TOP | Gravity.START));
        block.addView(dockBox, new LinearLayout.LayoutParams(U.MATCH, 0));
        appsRow = row(block, 10);
        // The arrow follows the dock's left edge when the dock lays out again or scrolls.
        appsRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> appsRow.post(this::placeDockArrow));
        if (appsRow.getParent() instanceof HorizontalScrollView)
            ((HorizontalScrollView) appsRow.getParent()).setOnScrollChangeListener((v, x, y, ox, oy) -> placeDockArrow());
        if (!dockArrowHooked) {
            // Lights up in the theme colour (and nods down) while something on the dock is selected.
            dockArrowHooked = true;
            getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> paintDockArrow());
        }
        // Both styles are centred on the screen; a row too wide to fit (many pinned apps) goes back to the left edge and scrolls.
        if ((appCircle || appCards) && appsRow.getParent() instanceof View) {
            final FrameLayout.LayoutParams cp = (FrameLayout.LayoutParams) appsRow.getLayoutParams();
            cp.gravity = Gravity.CENTER_HORIZONTAL;
            appsRow.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
                View hs = (View) appsRow.getParent();
                int want = appsRow.getWidth() > hs.getWidth() ? Gravity.START : Gravity.CENTER_HORIZONTAL;
                if (hs.getWidth() > 0 && cp.gravity != want) { cp.gravity = want; appsRow.requestLayout(); }
            });
        }
        if (appCircle && appsRow.getParent() instanceof View) {
            FrameLayout.LayoutParams ap = (FrameLayout.LayoutParams) appsRow.getLayoutParams();
            // 14 + 52 - 10 = the 56 a pill row takes, so nothing above shifts; the dock's last 10dp hangs into the bottom margin.
            ap.topMargin = V.dp(14);
            ap.bottomMargin = -V.dp(10);
        }
        if (appCards && appsRow.getParent() instanceof View) {
            // Cards: banners fill the band under the streaming row. 2 + 60 - 6 = the same 56 a pill row takes, and the
            // bottom edge lands where the circle dock's does, so the space to the bottom of the screen is the same.
            FrameLayout.LayoutParams ap = (FrameLayout.LayoutParams) appsRow.getLayoutParams();
            ap.topMargin = V.dp(CARD_TOP);
            ap.bottomMargin = -V.dp(6);
        }
        col.addView(block, new LinearLayout.LayoutParams(U.MATCH, U.WRAP));
    }

    /** Cards: a 5dp-padded dock behind them. 1 (top margin) + 5 + 51 (card) + 5 - 6 (hangs into the bottom margin) = the 56dp every style takes. */
    private static final float CARD_TOP = 1, CARD_H = 51, CARD_PAD = 5, CARD_GAP = 10, CARD_MIN_W = 74;

    /** Width of a banner card: as wide as the band allows (16:9), shrinking so n cards fit, but never below CARD_MIN_W. */
    private float cardWidth(int n) {
        float avail = getResources().getDisplayMetrics().widthPixels / V.scale - 2 * EDGE - 2 * CARD_PAD;
        float full = CARD_H * 16f / 9f;
        float fit = (avail - (n - 1) * CARD_GAP) / Math.max(1, n);
        return Math.max(CARD_MIN_W, Math.min(full, fit));
    }

    private void addToRow(LinearLayout r, View v) {
        float sp = (Float) r.getTag();
        if (v.getId() == View.NO_ID) v.setId(View.generateViewId());
        LinearLayout.LayoutParams p = v.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) v.getLayoutParams()
                : new LinearLayout.LayoutParams(U.WRAP, U.WRAP);
        p.rightMargin = V.dp(sp);
        r.addView(v, p);
    }

    private void fillRows() {
        if (cwRow == null) return;
        boolean hadFocus = getCurrentFocus() != null && ui.hasFocus();
        fillContinue();
        fillServices();
        fillApps();
        stopAtEdges(cwRow);
        stopAtEdges(svcRow);
        stopAtEdges(appsRow);
        defaultFocusId = cwRow.getChildCount() > 0 ? cwRow.getChildAt(0).getId()
                : svcRow.getChildCount() > 0 ? svcRow.getChildAt(0).getId()
                : appsRow.getChildCount() > 0 ? appsRow.getChildAt(0).getId() : View.NO_ID;
        if (pendingFocusPkg != null || pendingFocusMenu) {
            final String want = pendingFocusPkg;
            pendingFocusPkg = null;
            pendingFocusMenu = false;
            View target = null;
            for (int i = 0; want != null && i < appsRow.getChildCount(); i++) if (want.equals(appsRow.getChildAt(i).getTag())) target = appsRow.getChildAt(i);
            if (target == null && menuPill != null) target = menuPill;
            if (target != null) {
                final View tv = target;
                tv.post(() -> tv.requestFocus());
                return;
            }
        }
        if (!hadFocus || (!firstFocusDone && !apps.isEmpty())) {
            focusDefault();
            if (!apps.isEmpty()) firstFocusDone = true;
        }
    }

    private final int[] rowMemory = {-1, -1, -1};
    /** Set on a profile switch: Continue watching is entered at its first item until you leave that row once. */
    private boolean cwFresh;
    private int memIdx(int row) { return row == 0 && cwFresh ? 0 : rowMemory[row]; }

    /**
     * Up/Down between Home's rows returns to the item you were last on in the row you're going to
     * (instead of the nearest one by position). Returns false when there's nothing remembered yet.
     */
    private int lastRowIdx = -1; // the row we last left going up to the top bar

    private boolean inTopBar(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) if (p == topBar) return true;
        return v == topBar;
    }

    private boolean moveBetweenRows(int dir) {
        LinearLayout[] rows = {cwRow, svcRow, appsRow};
        View f = getCurrentFocus();
        if (f == null || ambient) return false;
        if (dir > 0 && topBar != null && inTopBar(f)) {
            // Down from the top bar goes back to the row we came from, at the place we left it
            int li = lastRowIdx;
            if (li < 0 || rows[li] == null || rows[li].getChildCount() == 0 || memIdx(li) < 0) return false;
            View t = rows[li].getChildAt(Math.min(memIdx(li), rows[li].getChildCount() - 1));
            if (t == null) return false;
            t.requestFocus();
            return true;
        }
        int from = -1;
        for (int i = 0; i < rows.length; i++) if (rows[i] != null && f.getParent() == rows[i]) from = i;
        if (from < 0) return false;
        rowMemory[from] = rows[from].indexOfChild(f);
        if (from == 0) cwFresh = false; // you've been in the row since the switch
        if (dir < 0) {
            boolean rowAbove = false;
            for (int to = from - 1; to >= 0; to--) if (rows[to] != null && rows[to].getChildCount() > 0) rowAbove = true;
            if (!rowAbove) lastRowIdx = from; // Up from the first row heads to the top bar
        }
        for (int to = from + dir; to >= 0 && to < rows.length; to += dir) {
            LinearLayout r = rows[to];
            if (r == null || r.getChildCount() == 0) continue;
            // Never visited since the app started (e.g. after a reboot): enter the row at its first item,
            // not whichever tile happens to sit nearest above/below the item we're leaving.
            View target = r.getChildAt(Math.max(0, Math.min(memIdx(to), r.getChildCount() - 1)));
            if (target == null) return false;
            target.requestFocus();
            return true;
        }
        return false;
    }

    /** Focus the left-most pinned app on the bottom row (falls back to the normal default). */
    private void focusFirstApp() {
        final View v = appsRow != null && appsRow.getChildCount() > 0 ? appsRow.getChildAt(0) : null;
        if (v == null) { focusDefault(); return; }
        v.post(() -> {
            v.requestFocus();
            if (appsRow.getParent() instanceof HorizontalScrollView) ((HorizontalScrollView) appsRow.getParent()).smoothScrollTo(0, 0);
        });
    }

    /** Left/Right stop at the first and last item of a row instead of jumping to another row. */
    private static void stopAtEdges(LinearLayout row) {
        int n = row.getChildCount();
        if (n == 0) return;
        View first = row.getChildAt(0), last = row.getChildAt(n - 1);
        first.setNextFocusLeftId(first.getId());
        last.setNextFocusRightId(last.getId());
    }

    private void focusDefault() {
        if (defaultFocusId == View.NO_ID) return;
        final View v = findViewById(defaultFocusId);
        if (v != null) v.post(() -> {
            v.requestFocus();
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null && p.getParent() instanceof HorizontalScrollView) ((HorizontalScrollView) p.getParent()).smoothScrollTo(0, 0);
        });
    }

    // --- continue watching (from Nuvio's own Continue Watching list)

    /** Long press on a Continue watching item: the same options as in Nuvio (Nuvio Beacon only). */
    private boolean cwMenu(final Nuvio.Item it) {
        if (!CwActions.available(it)) return false;
        ActionSheet sheet = new ActionSheet(this, it.title);
        sheet.add("Go to details", Icon.INFO, () -> CwActions.details(this, it));
        if (!it.watchNext) sheet.add("Start from beginning", Icon.PLAY, () -> CwActions.fromBeginning(this, it));
        sheet.add("Mark as watched", Icon.CHECK, () -> CwActions.markWatched(this, it, ok -> cwActionDone(it, ok, "Marked as watched")));
        sheet.add("Remove from Continue watching", Icon.UNPIN, () -> CwActions.remove(this, it, ok -> cwActionDone(it, ok, "Removed")));
        sheet.show();
        return true;
    }

    /** The item is gone from Nuvio's row: drop it here too, keeping the selection in the same place. */
    private void cwActionDone(Nuvio.Item it, boolean ok, String msg) {
        if (!ok) { Toast.makeText(this, "Couldn't do that in Nuvio", Toast.LENGTH_SHORT).show(); return; }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        if (nuvio == null) return;
        int at = cwItems.indexOf(it);
        nuvio.continueWatching.remove(it);
        fillContinue();
        stopAtEdges(cwRow);
        if (cwRow.getChildCount() > 0) cwRow.getChildAt(Math.max(0, Math.min(at, cwRow.getChildCount() - 1))).requestFocus();
        else focusDefault();
    }

    private void fillContinue() {
        cwRow.removeAllViews();
        cwItems.clear();
        if (nuvio != null) for (Nuvio.Item it : nuvio.continueWatching) { if (cwItems.size() < 12) cwItems.add(it); }
        cwBlock.setVisibility(cwItems.isEmpty() ? View.GONE : View.VISIBLE);
        int queued = 0;
        for (int i = 0; i < cwItems.size(); i++) {
            final Nuvio.Item it = cwItems.get(i);
            final int idx = i;
            long left = it.durationMs > 0 ? Math.max(0, it.durationMs - it.positionMs) : 0;
            queued += left / 60000;
            final int style = cwStyle();
            if (style >= 2) {
                View card = cwCard(it, idx, false);
                addToRow(cwRow, card);
                continue;
            }
            final boolean rings = style == 1;
            LinearLayout p = rings ? V.pill(this, 62, 3, 20) : V.pill(this, 54, 6, 17);
            if (rings) {
                FrameLayout box = new FrameLayout(this);
                final ProgressRing ring = new ProgressRing(this, it.progress());
                box.addView(ring, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
                FrameLayout thumb = new FrameLayout(this);
                thumb.setBackground(V.shape(0x1AFFFFFF, 21, 0));
                V.circle(thumb);
                ImageView iv = V.image(this, ImageView.ScaleType.CENTER_CROP);
                thumb.addView(iv, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
                Img.load(this, Img.sized(it.cardArt(), "w500"), V.dp(80), b2 -> { if (b2 != null) iv.setImageBitmap(b2); });
                box.addView(thumb, U.flp(V.dp(49), V.dp(49), Gravity.CENTER));
                p.addView(box, new LinearLayout.LayoutParams(V.dp(58), V.dp(58)));
            } else {
            FrameLayout thumb = new FrameLayout(this);
            thumb.setBackground(V.shape(0x1AFFFFFF, 21, 0));
            V.round(thumb, -1);
            ImageView iv = V.image(this, ImageView.ScaleType.CENTER_CROP);
            thumb.addView(iv, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            Img.load(this, Img.sized(it.cardArt(), "w500"), V.dp(80), b -> { if (b != null) iv.setImageBitmap(b); });
            if (it.progress() > 0) {
                View shadeV = Ui.gradient(this, GradientDrawable.Orientation.TOP_BOTTOM, 0x00000000, 0x8C000000);
                thumb.addView(shadeV, U.flp(U.MATCH, V.dp(14), Gravity.BOTTOM));
                FrameLayout bar = new FrameLayout(this);
                bar.setBackground(V.shape(0x59FFFFFF, 2, 0));
                View fill = new View(this);
                fill.setBackground(V.shape(V.accent(), 2, 0));
                fill.setPivotX(0);
                fill.setScaleX(it.progress());
                bar.addView(fill, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
                FrameLayout.LayoutParams blp = U.flp(U.MATCH, V.dp(2), Gravity.BOTTOM);
                blp.setMargins(V.dp(13), 0, V.dp(13), V.dp(5));
                thumb.addView(bar, blp);
            }
            p.addView(thumb, new LinearLayout.LayoutParams(V.dp(74), V.dp(42)));
            }
            LinearLayout tx = U.col(this);
            TextView t = V.text(this, it.title, 12, 700, V.TEXT);
            V.ellipsize(t);
            String sub = !TextUtils.isEmpty(it.episode) ? it.episode : remainingLine(it);
            if (rings && !TextUtils.isEmpty(it.episode) && it.durationMs > 0) sub = it.episode + "  ·  " + remainingLine(it);
            TextView s = V.dim(V.text(this, sub == null ? "Movie" : sub, 9.5f, 500, V.TEXT), 0.72f);
            V.ellipsize(s);
            // Fixed width from the longer line (wrap_content cut titles short on this TV).
            int tw = (int) Math.ceil(Math.max(t.getPaint().measureText(t.getText().toString()),
                    s.getPaint().measureText(s.getText().toString()))) + V.dp(2);
            tw = Math.min(tw, V.dp(220));
            tx.addView(t, new LinearLayout.LayoutParams(tw, U.WRAP));
            tx.addView(s, lp(tw, U.WRAP, 0, 3, 0, 0));
            p.addView(tx, lp(U.WRAP, U.WRAP, 10, 0, 0, 0));
            final float r = rings ? 31 : 27;
            p.setOnFocusChangeListener((v, has) -> {
                V.stylePill(p, has, r, null);
                if (has) {
                    cwCount.setText(cwItems.size() > 1 ? (idx + 1) + " of " + cwItems.size() : "");
                    showItem(it, "");
                }
            });
            p.setOnClickListener(v -> Nuvio.open(this, it));
            p.setOnLongClickListener(v -> cwMenu(it));
            addToRow(cwRow, p);
        }
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        cwTonight.setText((hour >= 17 || hour < 3) && queued >= 10 ? "·  Tonight · about " + Nuvio.duration(queued * 60000L) + " to catch up" : "");
    }

    /** Thin accent arc around a circular thumbnail showing how far through you are. */
    private static final class ProgressRing extends View {
        private final float frac;
        private final android.graphics.Paint track = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint arc = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF box = new android.graphics.RectF();
        ProgressRing(android.content.Context c, float frac) {
            super(c);
            this.frac = frac;
            float w = V.dpf(2.5f);
            track.setStyle(android.graphics.Paint.Style.STROKE); track.setStrokeWidth(w); track.setColor(0x3CFFFFFF);
            arc.setStyle(android.graphics.Paint.Style.STROKE); arc.setStrokeWidth(w); arc.setColor(V.accent());
            arc.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }
        @Override protected void onDraw(android.graphics.Canvas c) {
            float in = V.dpf(2.5f);
            box.set(in, in, getWidth() - in, getHeight() - in);
            c.drawOval(box, track);
            if (frac > 0) c.drawArc(box, -90, 360f * frac, false, arc);
        }
    }

    private String remainingLine(Nuvio.Item it) {
        if (it.durationMs <= 0) return it.meta();
        long left = Math.max(0, it.durationMs - it.positionMs);
        return Nuvio.duration(left) + " left";
    }

    private void showItem(Nuvio.Item it, String label) {
        String line = !TextUtils.isEmpty(it.episode) ? it.episode : it.meta();
        String remaining = null;
        if (it.durationMs > 0) {
            long left = Math.max(0, it.durationMs - it.positionMs);
            Calendar c = Calendar.getInstance();
            c.add(Calendar.MILLISECOND, (int) left);
            remaining = Nuvio.duration(left) + " left · done by " + DateFormat.getTimeFormat(this).format(c.getTime());
        }
        final boolean art = true;   // title artwork is always on
        String logo = art ? it.logo : null;
        cwHeroKey = it.key();
        setHero(it.key(), label, it.title, logo, "", 0, null, Img.sized(it.art(), "w1280"));
        if (art && TextUtils.isEmpty(logo)) {
            final Object k = it.key();
            U.IO.execute(() -> {
                Context ac = getApplicationContext();
                String l = it.tmdbId > 0 ? Streaming.tmdbLogo(ac, it) : null;
                if (l == null) l = Streaming.logoByTitle(ac, it);
                final String found = l;
                if (found != null) U.MAIN.post(() -> { it.logo = found; applyHeroLogo(k, found); });
            });
        }
    }

    // --- streaming services (Nuvio Personalized folders)

    private void fillServices() {
        svcRow.removeAllViews();
        if (prefs.getBoolean("svc_off", false)) return;
        int th = V.dp(63), tw = V.dp(112);
        addLiveTile(tw, th);
        prefetchServiceLogos();
        for (final Streaming.Service sv : Streaming.visible(this)) {
            final FrameLayout t = new FrameLayout(this);
            t.setTag(sv.key);
            t.setOnLongClickListener(v -> { svcMenu(sv); return true; });
            t.setFocusable(true);
            t.setFocusableInTouchMode(true);
            t.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
            V.round(t, 12);
            t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[] {U.mix(sv.color, 0xFF000000, 0.55f), 0xFF08090C}));
            final TextView name = V.text(this, sv.name, 12, 800, V.TEXT);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            t.addView(name, U.flp(U.MATCH, U.WRAP, Gravity.CENTER, 4, 0, 4, 0));
            final ImageView cover = V.image(this, ImageView.ScaleType.CENTER_CROP);
            t.addView(cover, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            Img.load(this, sv.cover, tw, b -> { if (b != null) { cover.setImageBitmap(b); name.setVisibility(View.GONE); } });
            final ImageView gif = V.image(this, ImageView.ScaleType.CENTER_CROP);
            gif.setAlpha(0f);
            t.addView(gif, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            final View ring = new View(this);
            ring.setBackground(new V.Glow(11, 0f, 1));
            ring.setAlpha(0f);
            t.addView(ring, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
            t.setContentDescription(sv.name);
            t.setOnFocusChangeListener((v, has) -> {
                t.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                        .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
                ring.animate().alpha(has ? 1f : 0f).setDuration(150).start();
                V.tileGlow(t, has, 12, 1.08f);
                if (has) {
                    playGif(t, gif, Streaming.gif(sv.key), tw, th);
                    setHero("svc:" + sv.key, "Streaming on Nuvio", sv.name, sv.logo,
                            "Top 10 movies · Popular series · New releases", 0, null, sv.hero);
                } else if (gifHost == t) {
                    stopGif();
                }
            });
            t.setOnClickListener(v -> openService(sv.key));
            addToRow(svcRow, t);
        }
        if (!Streaming.hidden(this).isEmpty()) addPlusTile(tw, th);
    }

    private void addPlusTile(int tw, int th) {
        final FrameLayout t = new FrameLayout(this);
        t.setTag("+");
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
        V.round(t, 12);
        t.setBackground(V.shape(V.GLASS, 12, V.LINE));
        Icon plus = new Icon(this, Icon.PLUS);
        plus.setColor(V.TEXT);
        t.addView(plus, new FrameLayout.LayoutParams(V.dp(22), V.dp(22), Gravity.CENTER));
        final View ring = new View(this);
        ring.setBackground(new V.Glow(11, 0f, 1));
        ring.setAlpha(0f);
        t.addView(ring, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        t.setContentDescription("Add streaming services back");
        t.setOnFocusChangeListener((v, has) -> {
            t.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(150).start();
                V.tileGlow(t, has, 12, 1.08f);
        });
        t.setOnClickListener(v -> hiddenMenu());
        addToRow(svcRow, t);
    }

    private void hiddenMenu() {
        final List<Streaming.Service> hid = new ArrayList<>();
        java.util.Set<String> h = Streaming.hidden(this);
        for (Streaming.Service s : Streaming.ordered(this)) if (h.contains(s.key)) hid.add(s);
        if (hid.isEmpty()) return;
        ActionSheet sheet = new ActionSheet(this, "Add back to Home");
        if (hid.size() > 1) sheet.add("Add all", Icon.PLUS, () -> { Streaming.showAll(this); refreshServices(null); });
        for (final Streaming.Service s : hid) sheet.add(s.name, Icon.PLUS, () -> { Streaming.setHidden(this, s.key, false); refreshServices(s.key); });
        sheet.show();
    }

    /** Rebuilds the streaming row and puts focus on the tile with this key (or the first tile). */
    private void refreshServices(final String focusKey) {
        fillServices();
        stopAtEdges(svcRow);
        U.MAIN.post(() -> {
            View target = null;
            for (int i = 0; i < svcRow.getChildCount(); i++) {
                View c = svcRow.getChildAt(i);
                if (focusKey != null && focusKey.equals(c.getTag())) { target = c; break; }
            }
            if (target == null && svcRow.getChildCount() > 0) target = svcRow.getChildAt(0);
            if (target != null) target.requestFocus();
        });
    }

    // ------------------------------------------------------------------ rearrange (apps and streaming tiles)

    private View rearrangeView;
    private LinearLayout rearrangeRow;
    private long rearrangeAt;

    private boolean movable(View c) {
        return c != null && c.getTag() instanceof String && !"+".equals(c.getTag());
    }

    /** Pick the tile up: Left/Right slide it along its row, OK puts it down, Back puts everything back. */
    private void startRearrange(LinearLayout row, final String tag) {
        View target = null;
        for (int i = 0; i < row.getChildCount(); i++) if (tag.equals(row.getChildAt(i).getTag())) target = row.getChildAt(i);
        if (target == null) return;
        rearrangeRow = row;
        rearrangeView = target;
        rearrangeAt = System.currentTimeMillis();
        target.requestFocus();
        liftTile(target, true);
        showRearrangeHint(row);
    }

    private View rearrangeHint;

    /**
     * The "how to move it" bubble, in the menu colours (solid, from the surface setting) with the keys in the theme
     * colour. It floats just above the row being rearranged so every tile in it stays in view.
     */
    private void showRearrangeHint(final LinearLayout row) {
        hideRearrangeHint();
        int ac = V.accent();
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        String[][] parts = {{"Left / Right", " to move   \u00b7   "}, {"OK", " to place   \u00b7   "}, {"Back", " to cancel"}};
        for (String[] p : parts) {
            int st = sb.length();
            sb.append(p[0]);
            sb.setSpan(new android.text.style.ForegroundColorSpan(ac), st, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(p[1]);
        }
        TextView t = V.text(this, "", 11, 600, V.TEXT);
        t.setText(sb);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(V.dp(14), V.dp(8), V.dp(14), V.dp(8));
        t.setBackground(V.shape(V.MENU_PANEL, 14, V.MENU_PANEL_LINE));
        t.setElevation(V.dpf(6));
        t.setAlpha(0f);
        rearrangeHint = t;
        root.addView(t, new FrameLayout.LayoutParams(U.WRAP, U.WRAP, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        t.post(() -> {
            if (rearrangeHint != t) return;
            int[] r = new int[2], o = new int[2];
            row.getLocationInWindow(r);
            root.getLocationInWindow(o);
            // clear the lifted tile, and the circle dock's name label that floats above it
            int gap = V.dp(row == appsRow && appCircle ? 30 : 14);
            t.setTranslationY(Math.max(V.dp(8), r[1] - o[1] - gap - t.getHeight()));
            t.animate().alpha(1f).setDuration(160).start();
        });
    }

    private void hideRearrangeHint() {
        final View t = rearrangeHint;
        rearrangeHint = null;
        if (t == null) return;
        t.animate().cancel();
        t.animate().alpha(0f).setDuration(120).withEndAction(() -> root.removeView(t)).start();
    }

    private void liftTile(final View v, boolean up) {
        v.animate().cancel();
        v.animate().scaleX(up ? 1.18f : 1.08f).scaleY(up ? 1.18f : 1.08f).translationY(up ? -V.dpf(5) : 0).setDuration(150).start();
    }

    private boolean handleRearrangeKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down) slideTile(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) {
            // the OK press that chose "Rearrange" is still ending: ignore it
            if (!down && System.currentTimeMillis() - rearrangeAt > 450) endRearrange(true);
            return true;
        }
        if (k == KeyEvent.KEYCODE_BACK) {
            if (!down) endRearrange(false);
            return true;
        }
        return false;
    }

    private void slideTile(int dir) {
        LinearLayout row = rearrangeRow;
        View v = rearrangeView;
        int i = row.indexOfChild(v), j = i + dir;
        if (j < 0 || j >= row.getChildCount() || !movable(row.getChildAt(j))) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        row.removeView(v);
        row.addView(v, j, lp);
        v.requestFocus();
        liftTile(v, true);
        Sfx.move(1.3f);
    }

    private void endRearrange(boolean save) {
        final View v = rearrangeView;
        final LinearLayout row = rearrangeRow;
        if (v == null || row == null) return;
        rearrangeView = null;
        rearrangeRow = null;
        hideRearrangeHint();
        final String tag = (String) v.getTag();
        List<String> order = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) if (movable(row.getChildAt(i))) order.add((String) row.getChildAt(i).getTag());
        if (save) {
            if (row == svcRow) Streaming.setVisibleOrder(this, order);
            else {
                List<String> cur = Apps.pins(this, apps);
                for (String p : cur) if (!order.contains(p)) order.add(p); // uninstalled pins stay, at the end
                Apps.savePins(this, order);
            }
        }
        // rebuild from what's saved (this also puts everything back when cancelled)
        if (row == svcRow) { fillServices(); stopAtEdges(svcRow); } else { fillApps(); stopAtEdges(appsRow); }
        U.MAIN.post(() -> {
            for (int i = 0; i < row.getChildCount(); i++) if (tag.equals(row.getChildAt(i).getTag())) { row.getChildAt(i).requestFocus(); break; }
        });
    }

    private void svcMenu(final Streaming.Service sv) {
        List<Streaming.Service> l = Streaming.visible(this);
        int at = -1;
        for (int i = 0; i < l.size(); i++) if (l.get(i).key.equals(sv.key)) at = i;
        ActionSheet sheet = new ActionSheet(this, sv.name);
        if (l.size() > 1 && at >= 0) sheet.add("Rearrange", Icon.APPS, () -> startRearrange(svcRow, sv.key));
        final String next = at >= 0 && at + 1 < l.size() ? l.get(at + 1).key : at > 0 ? l.get(at - 1).key : null;
        sheet.add("Hide from Home", Icon.UNPIN, () -> { Streaming.setHidden(this, sv.key, true); refreshServices(next); });
        sheet.show();
    }

    private void addLiveTile(int tw, int th) {
        final FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setLayoutParams(new LinearLayout.LayoutParams(tw, th));
        V.round(t, 12);
        // The original Horizon tile: red gradient with "● Live" over "TV Guide". (The Live TV logo shows in the hero.)
        t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {0xFF7A1420, 0xFF1A0A0E}));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        col.setPadding(V.dp(14), 0, V.dp(10), 0);
        TextView dot = V.caps(this, "● Live", 8.5f, 0xFFFF6B6B);
        dot.setSingleLine(true);
        col.addView(dot, new LinearLayout.LayoutParams(-2, -2));
        TextView name = V.text(this, "TV Guide", 13, 800, V.TEXT);
        name.setSingleLine(true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = V.dp(2);
        col.addView(name, np);
        t.addView(col, new FrameLayout.LayoutParams(tw, th, Gravity.CENTER));
        final ImageView gif = V.image(this, ImageView.ScaleType.CENTER_CROP);
        gif.setTag("gif");
        gif.setAlpha(0f);
        t.addView(gif, new FrameLayout.LayoutParams(-1, -1));
        final View ring = new View(this);   // on top, so the outline is never hidden by the animation
        ring.setBackground(new V.Glow(11, 0f, 1));
        ring.setAlpha(0f);
        t.addView(ring, new FrameLayout.LayoutParams(-1, -1));
        t.setContentDescription("Live TV guide");
        t.setOnFocusChangeListener((v, has) -> {
            t.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            ring.animate().alpha(has ? 1f : 0f).setDuration(150).start();
                V.tileGlow(t, has, 12, 1.08f);
            if (has) {
                playGif(t, gif, "asset:tvguide-hover.gif", tw, th);
                setHero("svc:live", "Live TV", "TV Guide", LIVE_LOGO,
                        "Channels with a full TV guide · watch here or in Nuvio", 0, null, LIVE_BACKDROP);
            } else if (gifHost == t) {
                stopGif();
            }
        });
        t.setOnClickListener(v -> startActivity(new Intent(this, GuideActivity.class)));
        addToRow(svcRow, t);
    }

    private void playGif(final View host, final ImageView target, String url, int w, int h) {
        stopGif();
        gifHost = host;
        if (url == null) return;
        Anim.load(this, url, w, h, d -> {
            if (gifHost != host || !host.hasFocus() || d == null) return;
            target.setImageDrawable(d);
            if (d instanceof AnimatedImageDrawable) { gifPlaying = (AnimatedImageDrawable) d; gifPlaying.start(); }
            target.animate().alpha(1f).setDuration(220).start();
        });
    }

    private void stopGif() {
        if (gifPlaying != null) { try { gifPlaying.stop(); } catch (Throwable ignored) {} gifPlaying = null; }
        if (gifHost != null) {
            ViewGroup gh = (ViewGroup) gifHost;
            View g = gh.getChildAt(2);
            for (int i = 0; i < gh.getChildCount(); i++) if ("gif".equals(gh.getChildAt(i).getTag())) g = gh.getChildAt(i);
            if (g instanceof ImageView) { g.animate().alpha(0f).setDuration(150).start(); final ImageView gi = (ImageView) g; g.postDelayed(() -> { if (gifHost == null || gifHost != gi.getParent()) gi.setImageDrawable(null); }, 200); }
        }
        gifHost = null;
    }

    private void openService(String key) {
        Streaming.Service s = Streaming.find(key);
        if (!Streaming.canBrowse(this, s)) { Apps.launchPackage(this, U.nuvio()); return; }
        startActivity(new Intent(this, ServiceActivity.class).putExtra("service", key));
    }

    // --- apps (pinned) + All apps

    /** All apps arrow: its size and the gap between it and the dock (dp). */
    private static final float ARROW = 20, ARROW_GAP = 13;

    /** Put the All apps arrow just left of the dock, centred on it. It is not part of the row, so it never shifts the centring. */
    private void placeDockArrow() {
        if (dockArrow == null || dockBox == null || appsRow == null) return;
        View first = appsRow.getChildCount() > 0 ? appsRow.getChildAt(0) : null;
        // With nothing pinned the row is just the All apps button, which says so itself.
        if (first == null || first.getTag() == null || appsRow.getWidth() == 0) { dockArrow.setVisibility(View.INVISIBLE); return; }
        int[] row = new int[2], box = new int[2];
        appsRow.getLocationOnScreen(row);
        dockBox.getLocationOnScreen(box);
        // Circle and Cards: the dock is the row's own background. Pill: no background, so the first pill is the edge.
        boolean pill = !appCircle && !appCards;
        float x = row[0] + (pill ? first.getLeft() : 0);
        float cy = row[1] + (pill ? first.getTop() + first.getHeight() / 2f : appsRow.getHeight() / 2f);
        int size = V.dp(ARROW);
        float left = x - V.dp(ARROW_GAP) - size;
        dockArrowY = cy - box[1] - size / 2f;
        dockArrow.setTranslationX(left - box[0]);
        if (dockArrowNod == null || !dockArrowNod.isRunning()) dockArrow.setTranslationY(dockArrowY);
        // Scrolled off (a long row that starts at the left edge and has scrolled): hide it rather than draw it half off screen.
        dockArrow.setVisibility(left >= 0 ? View.VISIBLE : View.INVISIBLE);
    }

    /** Faint white while the dock isn't selected; theme colour plus three small nods down while it is. */
    private void paintDockArrow() {
        if (dockArrow == null || appsRow == null) return;
        boolean on = appsRow.hasFocus();
        boolean was = dockArrow.getTag() == Boolean.TRUE;
        dockArrow.setTag(on);
        dockArrow.setColor(on ? V.accent() : U.alpha(V.TEXT, 0.5f));
        if (on == was) return;
        if (dockArrowNod != null) dockArrowNod.cancel();
        dockArrow.setTranslationY(dockArrowY);
        if (!on) return;
        dockArrowNod = android.animation.ObjectAnimator.ofFloat(dockArrow, View.TRANSLATION_Y, dockArrowY, dockArrowY + V.dp(4), dockArrowY);
        dockArrowNod.setDuration(650);
        dockArrowNod.setRepeatCount(2);
        dockArrowNod.setStartDelay(120);
        dockArrowNod.start();
    }

    /** Circle style: put the name label above the focused icon, kept inside the dock. */
    private void placeDockLabel(final View it, final String name) {
        if (dockLabel == null) return;
        dockLabel.setText(name);
        dockLabel.setVisibility(View.VISIBLE);
        dockLabel.post(() -> {
            int[] a = new int[2], b = new int[2], c = new int[2];
            it.getLocationOnScreen(a);
            dockBox.getLocationOnScreen(b);
            appsRow.getLocationOnScreen(c);
            float centre = a[0] - b[0] + it.getWidth() / 2f;
            float w = dockLabel.getWidth();
            float left = centre - w / 2f;
            float dockL = c[0] - b[0], dockR = dockL + appsRow.getWidth();
            if (left + w > dockR) left = dockR - w;
            if (left < dockL) left = dockL;
            dockLabel.setTranslationX(left);
        });
    }

    private void fillApps() {
        appsRow.removeAllViews();
        appsRow.setTag(appCards ? CARD_GAP : 10f);
        final List<Apps.App> pins = pinned();
        // The apps band always takes the same 56dp whatever the style, row size or number of apps, so the rows above never move.
        if (appsRow.getParent() instanceof View && appsRow.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) appsRow.getLayoutParams();
            fp.topMargin = pins.isEmpty() ? 0 : appCircle ? V.dp(14) : appCards ? V.dp(CARD_TOP) : 0;
            fp.bottomMargin = pins.isEmpty() ? 0 : appCircle ? -V.dp(10) : appCards ? -V.dp(6) : 0;
        }
        appsRow.setMinimumHeight(appCards && !pins.isEmpty() ? V.dp(CARD_H + 2 * CARD_PAD) : appCircle && !pins.isEmpty() ? V.dp(52) : 0);
        if (appCircle || appCards) {
            appsRow.setGravity(Gravity.CENTER_VERTICAL);
        } else {
            // Pill: the row is at least as wide as the screen and its pills are centred inside it. When the pills (and their
            // names) are wider than the screen the row simply grows, so it starts at the left edge and scrolls instead.
            appsRow.setMinimumWidth(getResources().getDisplayMetrics().widthPixels);
            appsRow.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        }
        if (appCards) {
            appsRow.setPadding(V.dp(CARD_PAD), V.dp(CARD_PAD), V.dp(CARD_PAD), V.dp(CARD_PAD));
            appsRow.setBackground(V.shape(V.GLASS_STRONG, 15, V.LINE));
        }
        if (appCircle) {
            appsRow.setPadding(V.dp(5), V.dp(5), V.dp(5), V.dp(5));
            appsRow.setBackground(V.shape(V.GLASS_STRONG, 20, V.LINE));
        }
        for (final Apps.App a : pins) {
            if (appCards) {
                final float cw = cardWidth(pins.size()), ch = cw * 9f / 16f;
                final int tw = V.dp(cw), th = V.dp(ch);
                final FrameLayout t = new FrameLayout(this);
                t.setFocusable(true);
                t.setFocusableInTouchMode(true);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(tw, th);
                t.setLayoutParams(tp);
                V.round(t, 10);
                if (a.banner != null) {
                    t.setBackgroundColor(0xFF1A1C22);
                    ImageView iv = V.image(this, ImageView.ScaleType.CENTER_CROP);
                    iv.setImageDrawable(a.banner);
                    t.addView(iv, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
                } else {
                    t.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                            new int[] {U.mix(a.color, 0xFFFFFFFF, 0.1f), U.mix(a.color, 0xFF000000, 0.5f)}));
                    ImageView iv = V.image(this, ImageView.ScaleType.FIT_CENTER);
                    iv.setImageDrawable(a.icon);
                    int is = Math.round(th * 0.46f);
                    t.addView(iv, U.flp(is, is, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, th * 0.10f / U.k, 0, 0));
                    TextView tv = V.text(this, a.label, 9.5f, 600, V.TEXT);
                    tv.setGravity(Gravity.CENTER);
                    V.ellipsize(tv);
                    t.addView(tv, U.flp(U.MATCH, U.WRAP, Gravity.BOTTOM, 4, 0, 4, 5));
                }
                final View ring = new View(this);
                ring.setBackground(new V.Glow(9, 0f, 1));
                ring.setAlpha(0f);
                t.addView(ring, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
                t.setContentDescription(a.label);
                t.setTag(a.pkg);
                t.setOnFocusChangeListener((v, has) -> {
                    t.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                            .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
                    ring.animate().alpha(has ? 1f : 0f).setDuration(150).start();
                    V.tileGlow(t, has, 10, 1.08f);
                });
                t.setOnClickListener(v -> Apps.launch(this, a));
                t.setOnLongClickListener(v -> { appMenu(a); return true; });
                t.setOnKeyListener((v, keyCode, ev) -> {
                    if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                    if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0)
                        startActivity(new Intent(this, AllAppsActivity.class));
                    return true;
                });
                addToRow(appsRow, t);
                continue;
            }
            if (appCircle) {
                final FrameLayout it = new FrameLayout(this);
                it.setFocusable(true);
                it.setFocusableInTouchMode(true);
                it.setLayoutParams(new LinearLayout.LayoutParams(V.dp(42), V.dp(42)));
                ImageView ic = V.image(this, ImageView.ScaleType.CENTER_CROP);
                ic.setImageDrawable(a.icon);
                ic.setBackground(V.shape(V.GLASS_STRONG, 19, 0));
                V.circle(ic);
                it.addView(ic, new FrameLayout.LayoutParams(V.dp(38), V.dp(38), Gravity.CENTER));
                it.setContentDescription(a.label);
                it.setTag(a.pkg);
                it.setOnFocusChangeListener((v, has) -> {
                    it.setBackground(has ? new V.Glow(21, 0.14f, 0) : null);
                    it.animate().scaleX(has ? 1.15f : 1f).scaleY(has ? 1.15f : 1f).setDuration(160).start();
                    if (has) placeDockLabel(it, a.label);
                    else it.post(() -> { if (!appsRow.hasFocus() && dockLabel != null) dockLabel.setVisibility(View.INVISIBLE); });
                });
                it.setOnClickListener(v -> Apps.launch(this, a));
                it.setOnLongClickListener(v -> { appMenu(a); return true; });
                it.setOnKeyListener((v, keyCode, ev) -> {
                    if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                    if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0)
                        startActivity(new Intent(this, AllAppsActivity.class));
                    return true;
                });
                addToRow(appsRow, it);
                continue;
            }
            LinearLayout p = V.pill(this, 42, 6, 16);
            ImageView ic = V.image(this, ImageView.ScaleType.CENTER_CROP);
            ic.setImageDrawable(a.icon);
            ic.setBackground(V.shape(V.GLASS_STRONG, 15, 0));
            V.circle(ic);
            p.addView(ic, new LinearLayout.LayoutParams(V.dp(30), V.dp(30)));
            p.addView(V.text(this, a.label, 11.5f, 600, V.TEXT), lp(U.WRAP, U.WRAP, 10, 0, 0, 0));
            p.setContentDescription(a.label);
            p.setTag(a.pkg);
            p.setOnClickListener(v -> Apps.launch(this, a));
            p.setOnLongClickListener(v -> { appMenu(a); return true; });
            p.setOnKeyListener((v, keyCode, ev) -> {
                if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
                if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0)
                    startActivity(new Intent(this, AllAppsActivity.class));
                return true; // Down on the bottom row never goes anywhere else
            });
            addToRow(appsRow, p);
        }
        // The All apps button only remains when nothing is pinned (the row needs something to focus).
        if (appsRow.getChildCount() > 0) {
            ((ViewGroup.MarginLayoutParams) appsRow.getChildAt(appsRow.getChildCount() - 1).getLayoutParams()).rightMargin = 0;
        }
        if (appsRow.getChildCount() > 0) return;
        if (appCircle || appCards) { appsRow.setBackground(null); appsRow.setPadding(V.dp(EDGE), V.dp(6), V.dp(EDGE), V.dp(8)); }
        LinearLayout all = V.pill(this, 42, 6, 18);
        FrameLayout dot = new FrameLayout(this);
        dot.setBackground(V.shape(0x24FFFFFF, 15, 0));
        Icon g = new Icon(this, Icon.GRID4);
        dot.addView(g, U.flp(V.dp(13), V.dp(13), Gravity.CENTER));
        all.addView(dot, new LinearLayout.LayoutParams(V.dp(30), V.dp(30)));
        all.addView(V.text(this, "All apps", 11.5f, 600, V.TEXT), lp(U.WRAP, U.WRAP, 10, 0, 0, 0));
        V.tint(all, V.TEXT);
        all.setOnClickListener(v -> startActivity(new Intent(this, AllAppsActivity.class)));
        all.setOnKeyListener((v, keyCode, ev) -> {
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false;
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0)
                startActivity(new Intent(this, AllAppsActivity.class));
            return true;
        });
        addToRow(appsRow, all);
    }

    /** Pinned apps, in the order you set (Pin to Home adds to the end; Move left / right reorders). */
    private List<Apps.App> pinned() {
        return Apps.pinned(this, apps);
    }

    private void appMenu(final Apps.App a) {
        final List<Apps.App> pins = pinned();
        int at = -1;
        for (int i = 0; i < pins.size(); i++) if (pins.get(i).pkg.equals(a.pkg)) at = i;
        ActionSheet sheet = new ActionSheet(this, a.label).add("Open", Icon.PLAY, () -> Apps.launch(this, a));
        if (pins.size() > 1 && at >= 0) sheet.add("Rearrange", Icon.APPS, () -> startRearrange(appsRow, a.pkg));
        sheet.add("Unpin from Home", Icon.UNPIN, () -> {
            // Selection goes to the next app on the left, else the next on the right, else up to the beacon menu.
            pendingFocusPkg = null;
            pendingFocusMenu = true;
            for (int i = 0; i < appsRow.getChildCount(); i++) {
                if (!a.pkg.equals(appsRow.getChildAt(i).getTag())) continue;
                Object l = i > 0 ? appsRow.getChildAt(i - 1).getTag() : null;
                Object r = i + 1 < appsRow.getChildCount() ? appsRow.getChildAt(i + 1).getTag() : null;
                if (l instanceof String) { pendingFocusPkg = (String) l; pendingFocusMenu = false; }
                else if (r instanceof String) { pendingFocusPkg = (String) r; pendingFocusMenu = false; }
            }
            Apps.unpin(this, apps, a.pkg);
        });
        sheet.show();
    }

    // ================================================================== data

    private void loadApps() {
        U.IO.execute(() -> {
            final List<Apps.App> list = Apps.load(getApplicationContext());
            U.MAIN.post(() -> { apps = list; fillRows(); });
        });
    }

    /**
     * Nuvio doesn't say which profile is active, but switching profile replaces most of the Continue
     * Watching list. When under 40% of the titles carry over, treat it as a profile switch.
     */
    private boolean detectCwSwitch(Nuvio.Result r) {
        java.util.Set<String> now = new java.util.HashSet<>();
        int n = 0;
        for (Nuvio.Item it : r.continueWatching) {
            if (n++ >= 12) break;
            if (it.title != null) now.add(it.title);
        }
        boolean sw = false;
        if (cwLoadedOnce) {
            int inter = 0;
            for (String s : now) if (cwKeys.contains(s)) inter++;
            int union = cwKeys.size() + now.size() - inter;
            sw = union > 0 && inter / (float) union < 0.4f;
        }
        cwLoadedOnce = true;
        cwKeys.clear();
        cwKeys.addAll(now);
        return sw;
    }

    /** After a switch the row starts again from its first item, wherever it was scrolled to. */
    /** After a profile switch, put the selection on the first Continue watching item. */
    private void selectFirstCw() {
        if (menuOpen || ambient || !started || cwRow == null || cwRow.getChildCount() == 0) return;
        View first = cwRow.getChildAt(0);
        if (first != null && first.isFocusable()) first.requestFocus();
    }

    private void scrollCwToStart() {
        if (!(cwRow.getParent() instanceof HorizontalScrollView)) return;
        final HorizontalScrollView hs = (HorizontalScrollView) cwRow.getParent();
        hs.scrollTo(0, 0);
        hs.post(() -> hs.scrollTo(0, 0));
    }

    /** The flash: the row slides and fades in fresh, the heading pulses in the accent colour. */
    private void flashCw() {
        cwBlock.animate().cancel();
        cwBlock.setAlpha(0.15f);
        cwBlock.setTranslationX(V.dpf(36));
        cwBlock.animate().alpha(1f).translationX(0).setDuration(450).start();
        if (cwHead != null) {
            // The flash pulses the profile's name white and settles it back on the theme colour.
            // It heads for whatever the theme colour is on each frame, so a colour change mid-flash (the new
            // profile's colour arriving) is followed smoothly instead of landing on the old colour first.
            final android.animation.ArgbEvaluator ev = new android.animation.ArgbEvaluator();
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(1100);
            a.addUpdateListener(an -> paintCwHead((Integer) ev.evaluate(an.getAnimatedFraction(), V.TEXT, V.accent())));
            a.start();
        }
        cwBlock.post(this::showCwNotice);
    }

    private View cwNotice;

    /** The "Continue watching updated" popup, styled like the long-press sheets and lifted over the row. */
    private void showCwNotice() { showNotice("Continue watching updated"); }

    private void showNotice(String text) {
        if (cwNotice != null) { ui.removeView(cwNotice); cwNotice = null; }
        // A see-through pill like the other pills on the home screen, sitting just above the Continue watching row.
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.HORIZONTAL);
        sheet.setGravity(Gravity.CENTER_VERTICAL);
        sheet.setPadding(V.dp(14), 0, V.dp(18), 0);
        sheet.setBackground(V.shape(V.GLASS, 25, V.LINE));
        Icon ic = new Icon(this, Icon.BEACON);
        ic.setColor(V.accent());
        sheet.addView(ic, new LinearLayout.LayoutParams(V.dp(13), V.dp(13)));
        TextView tv = V.text(this, text, 12f, 600, V.TEXT);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(U.WRAP, U.WRAP);
        tp.leftMargin = V.dp(9);
        sheet.addView(tv, tp);

        int[] a = new int[2], b = new int[2];
        cwBlock.getLocationInWindow(a);
        ui.getLocationInWindow(b);
        int h = V.dp(36);
        int top = cwBlock.getVisibility() == View.VISIBLE ? a[1] - b[1] - h - V.dp(8) : ui.getHeight() * 5 / 10;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(U.WRAP, h, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Math.max(V.dp(40), top);
        ui.addView(sheet, lp);
        cwNotice = sheet;
        sheet.setAlpha(0f);
        sheet.setTranslationY(V.dpf(14));
        sheet.animate().alpha(1f).translationY(0).setDuration(240).start();
        final View me = sheet;
        U.MAIN.postDelayed(() -> {
            if (cwNotice != me) return;
            me.animate().alpha(0f).translationY(-V.dpf(8)).setDuration(260).withEndAction(() -> {
                if (cwNotice == me) cwNotice = null;
                ui.removeView(me);
            }).start();
        }, 2600);
    }

    private void loadNuvio() { loadNuvio(false); }

    /** quiet = a background check: nothing is redrawn (and focus is left alone) unless the data changed. */
    private void loadNuvio(final boolean quiet) {
        nuvioAt = System.currentTimeMillis();
        final boolean fill = prefs.getBoolean(U.P_HERO_FILL, true);
        U.IO.execute(() -> {
            final Nuvio.Result r = Nuvio.load(getApplicationContext(), fill);
            Nuvio.applyWallpapers(getApplicationContext(), r, false);
            final StringBuilder sg = new StringBuilder();
            for (Nuvio.Item it : r.continueWatching) sg.append(it.key()).append('@').append(Math.round(it.progress() * 100)).append(';');
            sg.append('|');
            for (Nuvio.Item it : r.picks) sg.append(it.key()).append(';');
            final String sig = sg.toString();
            final Runnable show = () -> {
                if (quiet && sig.equals(cwSig) && nuvio != null) return; // nothing new
                cwSig = sig;
                nuvio = r;
                View f = getCurrentFocus();
                boolean onCw = f != null && f.getParent() == cwRow;
                final int focusedAt = onCw ? cwRow.indexOfChild(f) : -1;
                boolean switched = detectCwSwitch(r);
                fillContinue();
                stopAtEdges(cwRow);
                defaultFocusId = cwRow.getChildCount() > 0 ? cwRow.getChildAt(0).getId()
                        : svcRow.getChildCount() > 0 ? svcRow.getChildAt(0).getId() : defaultFocusId;
                if (switched) { rowMemory[0] = 0; cwFresh = true; scrollCwToStart(); flashCw(); cwRow.post(this::selectFirstCw); } // row 0 is Continue watching: forget the old place
                if (onCw && !switched && focusedAt >= 0 && cwRow.getChildCount() > 0) cwRow.getChildAt(Math.min(focusedAt, cwRow.getChildCount() - 1)).requestFocus(); // stay where you were
                else if (onCw || getCurrentFocus() == null) focusDefault();
            };
            U.MAIN.post(show);
            // episodes not seen before: fetch their series wallpaper, then refresh once
            if (Nuvio.applyWallpapers(getApplicationContext(), r, true) > 0) U.MAIN.post(() -> { if (r == nuvio) show.run(); });
        });
    }

    private void loadWeather() {
        if (!prefs.getBoolean("weather", true)) { weatherIcon.setVisibility(View.GONE); weatherText.setText(""); return; }
        U.IO.execute(() -> {
            try {
                double lat = Double.longBitsToDouble(prefs.getLong("wx_lat", Double.doubleToLongBits(Double.NaN)));
                double lon = Double.longBitsToDouble(prefs.getLong("wx_lon", Double.doubleToLongBits(Double.NaN)));
                if (Double.isNaN(lat)) {
                    JSONObject loc;
                    try {
                        loc = new JSONObject(Http.get("https://ipapi.co/json/"));
                        lat = loc.getDouble("latitude");
                        lon = loc.getDouble("longitude");
                    } catch (Throwable first) {
                        loc = new JSONObject(Http.get("https://ipwho.is/")); // second opinion if the first is rate-limited
                        lat = loc.getDouble("latitude");
                        lon = loc.getDouble("longitude");
                    }
                    prefs.edit().putLong("wx_lat", Double.doubleToLongBits(lat)).putLong("wx_lon", Double.doubleToLongBits(lon))
                            .putString("wx_city", loc.optString("city")).apply();
                }
                JSONObject w = new JSONObject(Http.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code,is_day&temperature_unit=fahrenheit")).getJSONObject("current");
                final int temp = (int) Math.round(w.getDouble("temperature_2m"));
                final int code = w.optInt("weather_code");
                final boolean day = w.optInt("is_day", 1) == 1;
                U.MAIN.post(() -> {
                    weatherIcon.setType(code >= 51 ? Icon.RAIN : code >= 2 ? Icon.CLOUD : day ? Icon.SUN : Icon.MOON);
                    weatherIcon.setVisibility(View.VISIBLE);
                    weatherText.setText(temp + "°");
                });
            } catch (Throwable ignored) {}
        });
    }

    // ================================================================== background

    private void configurePlayer() {
        if (player == null) return;
        player.configure(Scene.rotation(this), prefs.getInt(U.P_SCENE_SECS, 30),
                prefs.getBoolean(U.P_LIFT, true), prefs.getBoolean(U.P_SOUND, false));
    }

    private void startBackground() {
        if (player != null) {
            if (player.count() == 0) configurePlayer();
            player.start(coldStart && prefs.getBoolean(U.P_START_MARS, true));
            coldStart = false;
        }
        if (wall != null) wall.resume();
        loadNuvio();
    }

    // ================================================================== ambient

    private AmbientView ambientView;
    private String wallArt;

    private void enterAmbient() {
        if (ambient) return;
        ambient = true;
        stopGif();
        if (wall != null) wall.pause();
        final AmbientView av = new AmbientView(this);
        List<Nuvio.Item> pool = new ArrayList<>(cwItems);
        if (nuvio != null) { pool.addAll(nuvio.continueWatching); pool.addAll(nuvio.picks); }
        av.setItems(pool);
        av.setAlpha(0f);
        root.addView(av, new FrameLayout.LayoutParams(U.MATCH, U.MATCH));
        av.start();
        av.animate().alpha(1f).setDuration(900).start();
        ambientView = av;
        Nuvio.Result nr = nuvio;
        if (nr != null) {
            final Nuvio.Result fr = nr;
            U.IO.execute(() -> {
                final List<Nuvio.Item> more = Streaming.ambientExtras(getApplicationContext(), 5, 8);
                U.MAIN.post(() -> { if (ambientView == av) av.addItems(more); });
                if (Nuvio.applyWallpapers(getApplicationContext(), fr, true) > 0) U.MAIN.post(() -> {
                    if (ambientView == av) {
                        List<Nuvio.Item> p2 = new ArrayList<>(fr.continueWatching);
                        p2.addAll(fr.picks);
                        av.setItems(p2);
                    }
                });
            });
        }
        ui.animate().cancel();
        ui.animate().alpha(0f).setDuration(900).withLayer().start();
        shade.animate().alpha(0.35f).setDuration(900).start();
        if (player != null) player.animateLift(0f);
    }

    private void exitAmbient(boolean animate) {
        ambient = false;
        final AmbientView av = ambientView;
        ambientView = null;
        if (av != null) {
            av.stop();
            av.animate().cancel();
            if (animate) av.animate().alpha(0f).setDuration(420).withEndAction(() -> root.removeView(av)).start();
            else root.removeView(av);
        }
        if (wall != null) wall.resume();
        int d = animate ? 420 : 0;
        ui.animate().cancel();
        ui.animate().alpha(1f).setDuration(d).withLayer().start();
        hero.setAlpha(1f);
        shade.animate().alpha(1f).setDuration(d).start();
        if (player != null) player.animateLift(1f);
        if (getCurrentFocus() == null) focusDefault();
    }

    // ================================================================== top bar data

    private void updateClock() {
        if (clock == null) return;
        clock.setText(DateFormat.getTimeFormat(this).format(new Date()));
    }

    private void updateNetwork() {
        if (wifiIcon == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) { wifiIcon.setType(Icon.WIFI_OFF); return; }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) { wifiIcon.setType(Icon.ETHERNET); return; }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            int level = wi == null ? 0 : WifiManager.calculateSignalLevel(wi.getRssi(), 5);
            wifiIcon.setType(Icon.WIFI);
            wifiIcon.setLevel(Math.max(1, level));
        } catch (Throwable t) {
            wifiIcon.setType(Icon.WIFI);
        }
    }

    private void registerReceivers() {
        if (receiversOn) return;
        receiversOn = true;
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeRx, tf);
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgRx, pf);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { U.MAIN.post(HomeActivity.this::updateNetwork); }
                @Override public void onLost(Network network) { U.MAIN.post(HomeActivity.this::updateNetwork); }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities c) { U.MAIN.post(HomeActivity.this::updateNetwork); }
            };
            cm.registerDefaultNetworkCallback(netCb);
        } catch (Throwable ignored) {}
    }

    private void unregisterReceivers() {
        if (!receiversOn) return;
        receiversOn = false;
        try { unregisterReceiver(timeRx); } catch (Throwable ignored) {}
        try { unregisterReceiver(pkgRx); } catch (Throwable ignored) {}
        try { if (netCb != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb); } catch (Throwable ignored) {}
        netCb = null;
    }

    // ================================================================== actions

    private void openSetting(String action) {
        if (!Apps.start(this, new Intent(action))) Apps.start(this, new Intent(Settings.ACTION_SETTINGS));
    }

    private void openProjectorSettings() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setComponent(new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
        if (!Apps.start(this, i)) openSetting(Settings.ACTION_SETTINGS);
    }

    private void openHdmi() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        try {
            TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
            if (tim != null) for (TvInputInfo info : tim.getTvInputList()) {
                if (info.isPassthroughInput() && info.getType() == TvInputInfo.TYPE_HDMI) {
                    if (Apps.start(this, new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.getId())))) return;
                }
            }
        } catch (Throwable ignored) {}
        Toast.makeText(this, "Couldn't open the HDMI input", Toast.LENGTH_SHORT).show();
    }

    /**
     * Rows scroll so the focused card always sits at the left margin (like Google TV / Apple TV),
     * so nothing is ever pushed against the screen edge where the focus ring and scale get clipped.
     */
    static final class AnchoredRow extends HorizontalScrollView {
        AnchoredRow(Context c) { super(c); setSmoothScrollingEnabled(true); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
            if (getChildCount() == 0) return 0;
            int max = Math.max(0, getChildAt(0).getWidth() - getWidth());
            int target = Math.max(0, Math.min(max, r.left - V.dp(EDGE)));
            return target - getScrollX();
        }
    }
}
