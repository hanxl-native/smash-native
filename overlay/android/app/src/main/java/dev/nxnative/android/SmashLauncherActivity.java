package dev.nxnative.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import dev.peanuts.PeanutsPrefs;

/**
 * The Super Smash Bros. Ultimate app's only screen: the game, the .nup, the GPU driver and the picture settings, nothing of the emulator's own launcher.
 *
 * <p>It is a fork of the Peanuts launcher with the game's data in the APK: the Android pack (assets/smash/pack.zip) and the shared fonts (the standard font, the
 * button icons and the CJK fallbacks the game asks for) are copied into the app on the first start, so there is no setup. The game file is too large to ship: it
 * is picked once and copied into the app's own storage, after which the original can be deleted; a retail .nsp, .xci, .nsz or .xcz can also be converted to the
 * .nup on the phone by the importer that ships in the APK (libnximport, with the app's own prod.keys), so no computer is needed.
 *
 * <p>The GPU driver matters for this game: the phone's own Adreno driver runs it, but loses the device (a GPU hang) at scene changes such as the end of the
 * opening movie, so the launcher picks Mesa Turnip when it is installed (the GPU driver card can change it). The picture setting is the resolution scale
 * ({@code DM_RES_SCALE}, the game's render targets, 0.5x to 2x). The game starts in {@link GameActivity} with the same extras
 * {@code MainActivity.startWithDriver} passes.
 */
public final class SmashLauncherActivity extends Activity {
    static final String TITLE_ID = "01006A800016E000";
    static final String NAME = "Super Smash Bros. Ultimate";
    static final String GAME_FILE = "SmashUltimate.nup";

    private static final String ASSETS = "smash";   // assets/smash/: pack.zip, fonts/, keys/, icon.png
    private static final String PREFS = "smash_launcher";
    private static final int REQ_NUP = 1, REQ_DRIVER = 2, REQ_SAVE_OUT = 3, REQ_SAVE_IN = 4, REQ_UPDATE = 5, REQ_DLC = 6, REQ_PACK = 7;

    private static final int BG = 0xFF0B0B10, CARD = 0xFF17171F, TEXT = 0xFFF2F2F5, DIM = 0xFF9A9AA6, ACCENT = 0xFFC8322B;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView gameValue, updateValue, dlcValue, packValue, driverValue, pictureValue, savesValue, keysValue, firmwareValue, status, playLabel, cancelLink;
    private ProgressBar progress;
    private View playCard;
    private volatile boolean preparing;
    private volatile boolean importing;
    private volatile boolean cancelImport;
    private long lastPost;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildUi());
        refresh();
        prepare(() -> {
            Intent i = getIntent();
            String nup = i == null ? null : i.getStringExtra("import_nup");
            if (nup != null) importNup(Uri.fromFile(new File(nup)), true);
            String save = i == null ? null : i.getStringExtra("import_save");   // adb, for a save zip already on the phone
            if (save != null) restoreSaveZip(Uri.fromFile(new File(save)));
            String game = i == null ? null : i.getStringExtra("import_game");   // adb, for a .nsp/.xci already on the phone
            if (game != null) importGamePath(game);
        });
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String nup = intent.getStringExtra("import_nup");
        if (nup != null) importNup(Uri.fromFile(new File(nup)), true);
        String save = intent.getStringExtra("import_save");
        if (save != null) restoreSaveZip(Uri.fromFile(new File(save)));
        String game = intent.getStringExtra("import_game");
        if (game != null) importGamePath(game);
    }

    /** adb's --es import_game &lt;path&gt;: as if the file had been picked (the picker's own path) */
    private void importGamePath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        probeForImport(Uri.fromFile(new File(path)), dot > 0 ? lower.substring(dot + 1) : "nsp");
    }

    // ---- where things live ----

    private File root() {
        File f = getExternalFilesDir(null);   // /sdcard/Android/data/<package>/files: the host opens it by path, and it goes with the app
        return f != null ? f : getFilesDir();
    }

    private File gamesDir() { return new File(root(), "games"); }
    private File gameFile() { return new File(gamesDir(), GAME_FILE); }
    private File packDir() { return new File(AppStorage.packs(this), TITLE_ID); }
    private File envFile() { return new File(root(), "env.txt"); }
    private File saveDir() { return AppStorage.save(this, TITLE_ID); }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    /** DM_RES_SCALE: 0.5, 0.75, 1, 1.25, 1.5 or 2 (the host's values) */
    private float resScale() { return prefs().getFloat("res_scale", 1f); }

    // ---- first run: the game's data from the APK ----

    private void prepare(Runnable after) {
        if (preparing) return;
        preparing = true;
        showBusy(true, "Checking the game data…", 0);
        new Thread(() -> {
            String error = null;
            try {
                File pack = packDir();
                if (!new File(pack, "pack.tsv").isFile()) {
                    say("Copying the game data into the app (one time)…", 0);
                    File parent = pack.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
                    File tmp = new File(parent, TITLE_ID + ".tmp");
                    deleteTree(tmp);
                    if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
                    unzipAsset(ASSETS + "/pack.zip", tmp, this::sayProgress);
                    deleteTree(pack);
                    if (!tmp.renameTo(pack)) throw new IOException("cannot move the game data into place");
                }
                File fonts = PeanutsPrefs.fontsDir(this);
                copyAsset(ASSETS + "/fonts/standard.ttf", new File(fonts, "standard.ttf"));
                copyAsset(ASSETS + "/fonts/nintendo_ext.ttf", new File(fonts, "nintendo_ext.ttf"));
                copyAsset(ASSETS + "/fonts/cjk_sc_ext.ttf", new File(fonts, "cjk_sc_ext.ttf"));
                copyAsset(ASSETS + "/fonts/cjk_sc.ttf", new File(fonts, "cjk_sc.ttf"));
                copyAsset(ASSETS + "/fonts/cjk_tc.ttf", new File(fonts, "cjk_tc.ttf"));
                copyAsset(ASSETS + "/fonts/cjk_kr.ttf", new File(fonts, "cjk_kr.ttf"));
                // no prod.keys in the APK: the player picks their own console's (the KEYS card), as the license of that file requires
                copyAsset(ASSETS + "/icon.png", new File(PeanutsPrefs.iconsDir(this), TITLE_ID + ".png"));
                dev.peanuts.build.GameEnv.ensureFonts(this, TITLE_ID);   // DM_SHARED_FONT / DM_SHARED_FONT_EXT in the game's own env
                defaultDriver();   // Turnip when it is installed: the phone's own driver hangs at scene changes
                if (!prefs().getBoolean("overlay_set", false)) {   // the corner tag is this app's own name: off unless the player turns it back on in the in-game panel
                    Prefs.overlayItem(this, "tag", false);
                    prefs().edit().putBoolean("overlay_set", true).commit();
                }
                if (!envFile().isFile()) writeEnv();
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String err = error;
            ui.post(() -> {
                preparing = false;
                showBusy(false, null, 0);
                if (err != null) new AlertDialog.Builder(this).setTitle("The game data could not be prepared")
                        .setMessage(err + "\n\nFree some space and open the app again.").setPositiveButton("OK", null).show();
                refresh();
                if (after != null) after.run();
            });
        }, "smash-prepare").start();
    }

    /** Mesa Turnip, when it is installed and the player has not chosen a driver: the phone's own Adreno driver loses the device at scene changes. */
    private void defaultDriver() {
        if (prefs().getBoolean("driver_default", false)) return;
        for (GpuDriver.Info i : GpuDriver.installed(this))
            if (i.dir.getName().toLowerCase(java.util.Locale.ROOT).contains("turnip")) { GpuDriver.select(this, i); break; }
        prefs().edit().putBoolean("driver_default", true).commit();
    }

    /** Copies an asset beside the app's files when it is not there yet; a failure is not fatal (the keys are only needed to build) */
    private void copyAsset(String asset, File dst) {
        try {
            if (dst.isFile() && dst.length() > 0) return;
            File parent = dst.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
            try (InputStream in = getAssets().open(asset); OutputStream out = new FileOutputStream(dst)) { copy(in, out, null, 0); }
        } catch (IOException ignored) { /* an older build's APK has no such asset */ }
    }

    interface Progress { void at(int percent); }

    private void say(String text, int percent) { ui.post(() -> showBusy(true, text, percent)); }
    private void sayProgress(int percent) { ui.post(() -> showBusy(true, null, percent)); }

    private void showBusy(boolean on, String text, int percent) {
        if (progress == null) return;
        progress.setVisibility(on ? View.VISIBLE : View.GONE);
        status.setVisibility(on || text != null ? View.VISIBLE : View.GONE);
        if (text != null) status.setText(text);
        if (on) { progress.setIndeterminate(percent <= 0); if (percent > 0) progress.setProgress(percent); }
    }

    // ---- the game data pack (never distributed: the player's own, zipped) ----

    /** A .zip of a pack folder (pack.tsv at its root), extracted into files/packs/<title id> */
    private void pickPack() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_PACK);
    }

    private void packPicked(Uri uri) {
        if (preparing || importing) { toast("Wait for the current job to finish"); return; }
        preparing = true;
        showBusy(true, "Reading the pack…", 0);
        new Thread(() -> {
            String error = null;
            File tmp = new File(packDir().getParentFile(), TITLE_ID + ".pack.tmp");
            try {
                File parent = packDir().getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
                deleteTree(tmp);
                if (!tmp.mkdirs()) throw new IOException("cannot create " + tmp);
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IOException("cannot open the picked file");
                    unzipStream(in, tmp, this::sayProgress);
                }
                if (!new File(tmp, "pack.tsv").isFile()) throw new IOException("that .zip has no pack.tsv at its root: zip the pack folder's contents, not the folder itself");
                deleteTree(packDir());
                if (!tmp.renameTo(packDir())) throw new IOException("cannot move the pack into place");
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                deleteTree(tmp);
            }
            final String err = error;
            ui.post(() -> {
                preparing = false;
                showBusy(false, null, 0);
                toast(err != null ? "The pack could not be added: " + err : "Pack ready");
                refresh();
            });
        }, "pack").start();
    }

    // ---- the game file ----

    private void pickGameFile() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION), REQ_NUP);
    }

    /** What was picked: a .nup goes into the app as it is; a retail .nsp/.xci/.nsz/.xcz is kept as the base of a build (UPDATE and DLC are added to it) */
    private void askGameFile(Uri uri) {
        String name = displayName(uri);
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".nup")) { askNup(uri); return; }
        if (lower.endsWith(".nsp") || lower.endsWith(".xci") || lower.endsWith(".nsz") || lower.endsWith(".xcz")) basePicked(uri, lower.substring(lower.lastIndexOf('.') + 1));
        else toast("Choose the game's .nup, .nsp or .xci");
    }

    // ---- the pieces of a build: the base game, the 13.0.5 update and the DLC ----

    private String basePath() { return prefs().getString("base_path", null); }
    private String updatePath() { return prefs().getString("update_path", null); }

    private java.util.List<String> dlcPaths() {
        java.util.List<String> out = new java.util.ArrayList<>();
        String s = prefs().getString("dlc_paths", "");
        if (s != null && !s.isEmpty()) for (String p : s.split("\n")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    private void clearSources() { prefs().edit().remove("base_path").remove("update_path").remove("dlc_paths").commit(); }

    /** A path the importer can open: the picked file's own when the app may read it, else a copy in the cache (kept until the build) */
    private String localCopy(Uri uri, String name) throws IOException {
        String real = realPath(uri);
        if (real != null && new File(real).isFile()) {
            if (!new File(real).canRead()) throw new IOException("Allow All files access for the app so it can read " + real);
            return real;
        }
        File dst = new File(getCacheDir(), name);
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("cannot open the picked file");
            copy(in, out, null, 0);
        }
        return dst.getPath();
    }

    private void pickUpdate() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_UPDATE);
    }

    private void pickDlc() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQ_DLC);
    }

    /** The base game: the .nup is built from it, the update and the DLC together, so it is only read and kept for the build */
    private void basePicked(Uri uri, String ext) {
        if (preparing || importing) { toast("Wait for the current job to finish"); return; }
        showBusy(true, "Reading the game file…", 0);
        new Thread(() -> {
            String error = null, p = null;
            try {
                p = localCopy(uri, "base." + ext);
                JSONArray a = probe(new File(p));
                boolean base = false;
                for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i);
                    if (o.optInt("kind", -1) == 0 && TITLE_ID.equalsIgnoreCase(o.optString("title_id", ""))) base = true; }
                if (!base) error = "That file does not hold the game itself. Pick the game's own .nsp or .xci (the update and the DLC go in their own cards).";
            } catch (Throwable e) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final String err = error, path = p;
            ui.post(() -> {
                showBusy(false, null, 0);
                if (err != null) new AlertDialog.Builder(this).setTitle("The game file could not be read").setMessage(err).setPositiveButton("OK", null).show();
                else { prefs().edit().putString("base_path", path).commit(); refresh(); }
            });
        }, "smash-base").start();
    }

    /** The update: this app's pack is built for 13.0.5 (v2031616), so any other version is refused before anything is written */
    private void updatePicked(Uri uri) {
        if (preparing || importing) { toast("Wait for the current job to finish"); return; }
        showBusy(true, "Reading the update…", 0);
        new Thread(() -> {
            String error = null, p = null;
            try {
                p = localCopy(uri, "update.nsp");
                JSONArray a = probe(new File(p));
                JSONObject pick = null;
                for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i); if (o.optInt("kind", -1) == 1) { pick = o; break; } }
                if (pick == null) error = "That file holds no update for a game.";
                else if (!TITLE_ID.equalsIgnoreCase(pick.optString("app_id", ""))) error = "That update is for another game (" + pick.optString("app_id", "") + ").";
                else if (pick.optInt("version", -1) != 2031616) error = "That update is v" + pick.optInt("version", 0) + ". This app is built for 13.0.5 (v2031616): pick that one, so the game and its data match.";
            } catch (Throwable e) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final String err = error, path = p;
            ui.post(() -> {
                showBusy(false, null, 0);
                if (err != null) new AlertDialog.Builder(this).setTitle("The update was not added").setMessage(err).setPositiveButton("OK", null).show();
                else { prefs().edit().putString("update_path", path).commit(); refresh(); }
            });
        }, "smash-update").start();
    }

    /** The DLC: any number of .nsp/.nsz of this game; each one must be an add-on of its own title */
    private void dlcPicked(Intent data) {
        if (preparing || importing) { toast("Wait for the current job to finish"); return; }
        java.util.List<Uri> uris = new java.util.ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return;
        showBusy(true, "Reading the DLC…", 0);
        new Thread(() -> {
            String error = null; java.util.List<String> paths = new java.util.ArrayList<>(); long bytes = 0;
            try {
                for (Uri u : uris) {
                    String p = localCopy(u, "dlc_" + paths.size() + ".nsp");
                    JSONArray a = probe(new File(p));
                    boolean ok = false;
                    for (int i = 0; i < a.length(); i++) { JSONObject o = a.getJSONObject(i);
                        if (o.optInt("kind", -1) == 2 && TITLE_ID.equalsIgnoreCase(o.optString("app_id", ""))) { ok = true; bytes += o.optLong("size", 0); } }
                    if (!ok) { error = "Not a DLC of this game: " + displayName(u); break; }
                    paths.add(p);
                }
            } catch (Throwable e) { error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
            final String err = error; final java.util.List<String> list = paths; final long total = bytes;
            ui.post(() -> {
                showBusy(false, null, 0);
                if (err != null) new AlertDialog.Builder(this).setTitle("The DLC was not added").setMessage(err).setPositiveButton("OK", null).show();
                else { prefs().edit().putString("dlc_paths", String.join("\n", list)).putLong("dlc_bytes", total).commit(); refresh(); }
            });
        }, "smash-dlc").start();
    }

    private JSONArray probe(File f) throws Exception {
        loadKeysOrThrow();
        String json = dev.peanuts.importer.Native.probe(f.getPath());
        if (json == null || json.trim().isEmpty()) throw new IOException(dev.peanuts.importer.Native.lastError());
        return new JSONArray(json);
    }

    private void askNup(Uri uri) {
        String name = displayName(uri);
        long size = displaySize(uri);
        String sizeText = size > 0 ? human(size) : "unknown size";
        new AlertDialog.Builder(this).setTitle("Add " + (name == null ? "the game file" : name))
                .setMessage(sizeText + "\n\nMove into the app: the file leaves its folder at once and the game reads the app's copy (the original is gone).\n\n"
                        + "Copy: the original stays where it is and the app keeps its own copy (needs the free space twice).")
                .setPositiveButton("Move into the app", (d, w) -> importNup(uri, true))
                .setNeutralButton("Copy (keeps the original)", (d, w) -> importNup(uri, false))
                .setNegativeButton("Cancel", null).show();
    }

    /** The picked .nup into the app: a same-volume rename when the provider gives a real path (instant, 15 GB), else a stream copy with progress */
    private void importNup(Uri uri, boolean move) {
        if (preparing || importing) { toast("Wait for the current job to finish"); return; }
        preparing = true;
        showBusy(true, "Adding the game file…", 0);
        new Thread(() -> {
            String error = null;
            try {
                File dst = gameFile();
                File dir = gamesDir();
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
                if (dst.exists() && !dst.delete()) throw new IOException("cannot replace " + dst);
                String real = realPath(uri);
                boolean moved = false;
                if (real != null) {
                    File src = new File(real);
                    if (src.isFile() && src.renameTo(dst)) moved = true;
                }
                if (!moved) {
                    long total = displaySize(uri);
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("cannot open the picked file");
                        try (OutputStream out = new FileOutputStream(dst)) { copy(in, out, this::sayProgress, total); }
                    }
                    if (move) removeOriginal(uri, real);   // its copy is in the app now
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String err = error;
            ui.post(() -> {
                preparing = false;
                showBusy(false, null, 0);
                toast(err != null ? "The game file could not be added: " + err : "Game file ready");
                refresh();
            });
        }, "smash-nup").start();
    }

    /** The picked file after a copy: by its path when the app may write there (All files access), else through the picker's own write grant, so a "move" is a move */
    private boolean removeOriginal(Uri uri, String real) {
        if (real != null && new File(real).delete()) return true;
        try { return DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { return false; /* the grant may be read-only */ }
    }

    // ---- a retail .nsp/.xci, converted to the app's .nup by the importer in the APK ----

    /** What the importer says about the picked file: its package(s) of this game and the size of the .nup they make */
    private static final class ImportInfo {
        String path, ext = "nsp", name = NAME;
        long bytes;
        int titles;
        boolean base, update, dlc;
        final java.util.List<String> inputs = new java.util.ArrayList<>();   // a build: base, the 13.0.5 update and the DLC together
    }

    private void probeForImport(Uri uri, String ext) {
        if (preparing || importing) { toast("Wait for the current job to finish"); return; }
        String real = realPath(uri);
        File f = real == null ? null : new File(real);
        if (f == null || !f.isFile() || !f.canRead()) { allFilesDialog(uri); return; }   // the native importer opens the file by its path
        preparing = true;
        showBusy(true, "Reading the game file…", 0);
        new Thread(() -> {
            ImportInfo info = new ImportInfo();
            info.path = f.getPath();
            info.ext = ext;
            String error = null;
            try {
                loadKeysOrThrow();
                String json = dev.peanuts.importer.Native.probe(info.path);
                if (json == null || json.trim().isEmpty()) throw new IOException(dev.peanuts.importer.Native.lastError());
                JSONArray a = new JSONArray(json);
                long fallback = f.length();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    String app = o.optString("app_id", ""), tid = o.optString("title_id", "");
                    if (!TITLE_ID.equalsIgnoreCase(app) && !TITLE_ID.equalsIgnoreCase(tid)) continue;
                    int kind = o.optInt("kind", -1);
                    long size = o.optLong("size", 0);
                    info.bytes += size > 0 ? size : fallback;
                    info.titles++;
                    if (kind == 0) { info.base = true; String n = o.optString("name", ""); if (!n.isEmpty()) info.name = n; }
                    else if (kind == 1) info.update = true;
                    else if (kind == 2) info.dlc = true;
                }
                if (!info.base) {
                    error = info.update || info.dlc
                            ? "This file holds only an update or DLC of the game, not the game itself. Pick the game's own .nsp or .xci."
                            : "This file is not Super Smash Bros. Ultimate.";
                }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final ImportInfo i = info; final String err = error;
            ui.post(() -> {
                preparing = false;
                showBusy(false, null, 0);
                if (err != null) new AlertDialog.Builder(this).setTitle("The game file could not be read").setMessage(err).setPositiveButton("OK", null).show();
                else importDialog(i);
            });
        }, "smash-probe").start();
    }

    private void importDialog(ImportInfo info) {
        File old = gameFile();
        long oldLen = old.isFile() ? old.length() : 0;
        boolean replace = oldLen > 0;
        long free = freeSpace();
        long available = free + (replace ? oldLen : 0);
        long need = info.bytes + (256L << 20);
        boolean ok = available >= need;
        String msg = info.name + "\n\nIt becomes a .nup of " + human(info.bytes) + " in the app"
                + (info.titles > 1 ? " (" + info.titles + " packages: game, update or DLC)" : "") + ".\n\n"
                + (replace ? "The game file in the app (" + human(oldLen) + ") is deleted first"
                        + (ok ? "." : " because there is no room for both.") + "\n\n" : "")
                + "Free space: " + human(free) + (replace ? " + " + human(oldLen) + " from the old file" : "") + " · needed: " + human(need) + "\n\n"
                + "It takes a few minutes and the phone warms up; you can cancel while it runs.";
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("Import into the app?").setMessage(msg).setNegativeButton("Cancel", null);
        if (!ok) {
            b.setPositiveButton("Not enough space", null);
        } else {
            b.setPositiveButton("Import", (d, w) -> startImport(info, false));
            b.setNeutralButton("Import and delete the ." + info.ext, (d, w) -> startImport(info, true));
        }
        b.show();
    }

    private void startImport(ImportInfo info, boolean deleteSource) {
        if (importing) return;
        importing = true;
        cancelImport = false;
        showBusy(true, "Importing…", 0);
        cancelLink.setVisibility(View.VISIBLE);
        refresh();
        new Thread(() -> {
            String error = null, warning = null;
            boolean done = false, deleted = false;
            File out = gameFile(), part = new File(gamesDir(), GAME_FILE + ".part");
            try {
                loadKeysOrThrow();
                if (!gamesDir().isDirectory() && !gamesDir().mkdirs()) throw new IOException("cannot create " + gamesDir());
                deleteTree(part);
                if (out.isFile() && !out.delete()) throw new IOException("cannot delete the old " + out.getName());
                dev.peanuts.importer.Native.setThreads(0);
                dev.peanuts.importer.Native.cancel(false);
                String[] inputs = info.inputs.isEmpty() ? new String[] {info.path} : info.inputs.toArray(new String[0]);
                int rc = dev.peanuts.importer.Native.writeNup(part.getPath(), inputs, TITLE_ID, this::onImportProgress);
                if (cancelImport) { part.delete(); throw new IOException("cancelled"); }
                if (rc != 0) { part.delete(); throw new IOException(dev.peanuts.importer.Native.lastError()); }
                if (!part.renameTo(out)) throw new IOException("cannot move the new game file into place");
                done = true;
                if (deleteSource) deleted = removeOriginal(Uri.fromFile(new File(info.path)), info.path);
                try {   // the pack in the APK is for one build of the game: say when the imported file is another one
                    warning = PackCheck.blocking(this, Pack.read(packDir()), out);
                } catch (Throwable ignored) { /* a check that cannot run says nothing */ }
            } catch (Throwable e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String err = error, warn = warning; final boolean ok = done, del = deleted;
            ui.post(() -> {
                importing = false;
                cancelLink.setVisibility(View.GONE);
                showBusy(false, null, 0);
                if (ok && !info.inputs.isEmpty()) clearSources();   // the .nup holds them now
                toast(ok ? ("Game imported into the app" + (deleteSource ? (del ? " and the original deleted" : "; the original could not be deleted") : ""))
                        : "The import did not finish: " + err);
                refresh();
                if (ok && warn != null) new AlertDialog.Builder(this).setTitle("Imported, but the app is not prepared for this version")
                        .setMessage(warn + "\n\nThe game may not start correctly: this app's pack was made for the version it shipped with.").setPositiveButton("OK", null).show();
            });
        }, "smash-import").start();
    }

    /** The importer's progress: bytes of the .nup written (the callback comes often: the screen is told at most three times a second) */
    private void onImportProgress(String step, long done, long total) {
        long now = System.currentTimeMillis();
        if (now - lastPost < 300 && done < total) return;
        lastPost = now;
        ui.post(() -> {
            if (total > 0) { progress.setIndeterminate(false); progress.setProgress((int) Math.min(100, done * 100 / total)); }
            status.setText("Importing… " + human(done) + " / " + human(total));
        });
    }

    /** The importer's keys, or why they cannot be used (no importer in this build, or missing keys) */
    private void loadKeysOrThrow() throws IOException {
        File k = PeanutsPrefs.keysFile(this);
        if (!k.isFile() || k.length() == 0) throw new IOException("the app's keys are missing: open the app once so it copies them");
        int rc;
        try {
            rc = dev.peanuts.importer.Native.keys(k.getPath());
        } catch (Throwable t) {   // no importer in this build, or its library cannot load on this device
            throw new IOException("the importer is not available in this build (it needs an ARM phone)");
        }
        if (rc != 0) throw new IOException("the keys could not be read: " + dev.peanuts.importer.Native.lastError());
    }    private long freeSpace() {
        File d = gamesDir();
        return (d.isDirectory() ? d : root()).getUsableSpace();
    }

    /** The importer opens the picked file by its path: without All files access Android only gives the system picker's stream, so say how to grant it */
    private void allFilesDialog(Uri uri) {
        boolean onPhone = "content".equals(uri.getScheme()) && "com.android.externalstorage.documents".equals(uri.getAuthority());
        if (!onPhone) {
            new AlertDialog.Builder(this).setTitle("Copy the file to the phone first")
                    .setMessage("The importer reads the game file by its path, and this one is not in the phone's storage (it comes from another app or the cloud).\n\n"
                            + "Copy the .nsp or .xci to Download and pick it from there.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("Allow the app to read the game file")
                .setMessage("The importer reads the .nsp or .xci where it is (15 GB: it cannot copy it first). Android needs \"All files access\" for that.\n\n"
                        + "Allow it, come back and pick the file again. It is only used to read your own game file.")
                .setPositiveButton("Open settings", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- the GPU driver ----

    private void pickDriver() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"}), REQ_DRIVER);
    }

    private void driverPicked(Uri uri) {
        String name = displayName(uri);
        GpuDriver.installInBackground(this, name == null ? "the driver" : name, () -> GpuDriver.install(this, uri), this::refresh);
    }

    private void driverDialog() {
        List<GpuDriver.Info> installed = GpuDriver.installed(this);
        GpuDriver.Info selected = GpuDriver.selected(this);
        List<String> labels = new ArrayList<>();
        labels.add("System (the phone's own driver)");
        int checked = 0;
        for (int i = 0; i < installed.size(); i++) {
            labels.add(installed.get(i).label());
            if (selected != null && installed.get(i).dir.equals(selected.dir)) checked = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("GPU driver").setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, w) -> {
            GpuDriver.select(this, w == 0 ? null : installed.get(w - 1));
            d.dismiss();
            refresh();
        }).setPositiveButton("Add a driver zip…", (d, w) -> pickDriver()).setNegativeButton("Close", null).show();
    }

    // ---- picture ----

    private void pictureDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(14), dp(20), 0);

        TextView resTitle = label("Resolution (the game's render targets)", 12, DIM, true);
        resTitle.setPadding(0, dp(18), 0, 0);
        box.addView(resTitle);
        float[] scales = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        String[] names = {"50 % — lightest", "75 %", "100 % — the game's own", "125 %", "150 %", "200 % — sharpest, heaviest"};
        TextView[] resDots = new TextView[scales.length];
        for (int i = 0; i < scales.length; i++) {
            final float s = scales[i];
            resDots[i] = choiceRow(box, names[i], () -> {
                prefs().edit().putFloat("res_scale", s).commit();
                writeEnv();
                int on = 2;
                for (int x = 0; x < scales.length; x++) if (Math.abs(resScale() - scales[x]) < 0.001f) on = x;
                paintDots(resDots, on);
                refresh();
            });
        }
        int on = 2;
        for (int x = 0; x < scales.length; x++) if (Math.abs(resScale() - scales[x]) < 0.001f) on = x;
        paintDots(resDots, on);

        TextView note = label("Applies from the next start. 100 % is the game's own resolution; lower it if the phone gets hot.", 12, DIM, false);
        note.setPadding(0, dp(18), 0, dp(6));
        box.addView(note);

        new AlertDialog.Builder(this).setTitle("Graphics").setView(box).setPositiveButton("Done", null).show();
    }

    private TextView choiceRow(LinearLayout box, String text, Runnable pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));
        TextView dot = label("○", 15, DIM, false);
        dot.setWidth(dp(26));
        row.addView(dot);
        row.addView(label(text, 16, TEXT, false));
        row.setOnClickListener(v -> pick.run());
        box.addView(row);
        return dot;
    }

    private void paintDots(TextView[] dots, int on) {
        for (int i = 0; i < dots.length; i++) {
            dots[i].setText(i == on ? "●" : "○");
            dots[i].setTextColor(i == on ? ACCENT : DIM);
        }
    }

    /** files/env.txt: the host's switches for the next start (the GPU driver is the launcher's own choice, GpuDriver) */
    private void writeEnv() {
        StringBuilder b = new StringBuilder();
        if (Math.abs(resScale() - 1f) > 0.001f) b.append("DM_RES_SCALE=").append(scaleText(resScale())).append('\n');
        File f = envFile();
        try {
            File parent = f.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
            try (OutputStream out = new FileOutputStream(f)) { out.write(b.toString().getBytes("UTF-8")); }
        } catch (IOException e) {
            toast("Cannot write the graphics settings: " + e.getMessage());
        }
        Prefs.fpsCap(this, TITLE_ID, 60);   // the cap the in-game panel shows
    }

    private static String scaleText(float f) {
        if (Math.abs(f - 0.5f) < 0.001f) return "0.5";
        if (Math.abs(f - 0.75f) < 0.001f) return "0.75";
        if (Math.abs(f - 1.25f) < 0.001f) return "1.25";
        if (Math.abs(f - 1.5f) < 0.001f) return "1.5";
        if (Math.abs(f - 2f) < 0.001f) return "2";
        return String.format(Locale.ROOT, "%.2f", f);
    }

    // ---- saves ----

    private void savesDialog() {
        File dir = saveDir();
        int files = count(dir);
        new AlertDialog.Builder(this).setTitle("Saves")
                .setMessage((files == 0 ? "No save yet." : files + (files == 1 ? " file" : " files") + " in the app's save folder.")
                        + "\n\nLoad a save from a .zip (Eden, Ryujinx or a Switch export): the save in the app is replaced, and the one it had is kept in the app's backups folder."
                        + "\n\nClose the game first if it is still open behind this screen (its Stop button in the in-game panel)."
                        + "\n\nThe saves live inside the app, so uninstalling deletes them. Back up before updating or uninstalling.")
                .setPositiveButton("Load a save (.zip)…", (d, w) -> importSave())
                .setNeutralButton("Back up mine (.zip)…", (d, w) -> exportSave())
                .setNegativeButton("Close", null).show();
    }

    private void exportSave() {
        if (count(saveDir()) == 0) { toast("There is no save to back up yet"); return; }
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, "Smash-save-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new java.util.Date()) + ".zip"), REQ_SAVE_OUT);
    }

    private void importSave() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"}), REQ_SAVE_IN);
    }

    private void writeSaveZip(Uri uri) {
        new Thread(() -> {
            String error = null;
            try (OutputStream raw = getContentResolver().openOutputStream(uri)) {
                if (raw == null) throw new IOException("cannot open the picked file");
                try (ZipOutputStream z = new ZipOutputStream(raw)) { zipInto(z, saveDir(), ""); }
            } catch (Throwable e) { error = e.getMessage(); }
            final String err = error;
            ui.post(() -> toast(err == null ? "Save backed up" : "The backup failed: " + err));
        }, "smash-save-out").start();
    }

    private void restoreSaveZip(Uri uri) {
        new AlertDialog.Builder(this).setTitle("Load this save?").setMessage("The save in the app is replaced by the zip's. This cannot be undone (the one it had is kept in the app's backups folder).")
                .setPositiveButton("Load", (d, w) -> new Thread(() -> {
                    String error = null;
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new IOException("cannot open the picked file");
                        File dir = saveDir();
                        File stage = new File(root(), "save-import.tmp");
                        deleteTree(stage);
                        if (!stage.mkdirs()) throw new IOException("cannot create " + stage);
                        unzipStream(in, stage, null);
                        File from = saveRoot(stage);
                        if (count(from) == 0) throw new IOException("the zip has no save files");
                        keepCurrentSave();
                        deleteTree(dir);
                        File parent = dir.getParentFile();
                        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
                        if (!from.renameTo(dir)) { copyTree(from, dir); deleteTree(from); }
                        deleteTree(stage);
                    } catch (Throwable e) { error = e.getMessage(); }
                    final String err = error;
                    ui.post(() -> { toast(err == null ? "Save loaded" : "The save could not be loaded: " + err); refresh(); });
                }, "smash-save-in").start())
                .setNegativeButton("Cancel", null).show();
    }

    /**
     * The save folder inside an unzipped export: a folder named after the title (Eden's and Ryujinx's tree: nand/user/save/&lt;user&gt;/&lt;title ID&gt;/), else the zip's only top
     * folder (an export of the folder itself), else the root (the save's files at the top of the zip)
     */
    private static File saveRoot(File stage) {
        File byTitle = findDir(stage, TITLE_ID, 0, true);
        if (byTitle != null) return byTitle;
        File[] top = stage.listFiles();
        if (top != null) {
            File withFiles = null, onlyDir = null;
            int dirsWithFiles = 0, dirs = 0, files = 0;
            for (File f : top) {
                if (f.isDirectory()) {
                    dirs++;
                    onlyDir = f;
                    if (count(f) > 0) { dirsWithFiles++; withFiles = f; }
                } else files++;
            }
            if (dirsWithFiles == 1 && files == 0) return withFiles;   // the one folder that holds the save
            if (dirs == 1 && files == 0) return onlyDir;              // one folder, even an empty save
        }
        return stage;
    }

    private static File findDir(File dir, String name, int depth, boolean needFiles) {
        if (depth > 8) return null;
        File[] list = dir.listFiles();
        if (list == null) return null;
        for (File f : list) if (f.isDirectory() && f.getName().equalsIgnoreCase(name) && (!needFiles || count(f) > 0)) return f;
        for (File f : list) if (f.isDirectory()) { File r = findDir(f, name, depth + 1, needFiles); if (r != null) return r; }
        return null;
    }

    /** The save the app has now into save-backups/pre-restore-&lt;date&gt;, so a wrong zip never loses it */
    private void keepCurrentSave() {
        File dir = saveDir();
        if (count(dir) == 0) return;
        String at = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date());
        try { copyTree(dir, new File(root(), "save-backups/pre-restore-" + at)); } catch (IOException ignored) { /* the new save still loads */ }
    }

    // ---- the console's own firmware and keys (the player's, never in the APK) ----

    /** prod.keys from the player's own console: the importer needs it to read a firmware and to build a .nup from an .nsp or .xci */
    private void keysDialog() { dev.peanuts.ui.LibrarySettings.pickKeys(this); }

    /** the firmware's shared fonts: a firmware .zip (its NCAs flat inside) or a folder of extracted NCAs; the importer reads standard.ttf, the button icons and
     *  the CJK fonts into files/fonts, where the host looks for them (DM_FONT_DIR) */
    private void firmwareDialog() { dev.peanuts.ui.LibrarySettings.pickFirmwareSource(this); }

    /** Base + the 13.0.5 update + the DLC files into one .nup, all at once: the importer merges them (they may also hold an update inside) */
    private void build() {
        String base = basePath(), upd = updatePath();
        if (base == null || !new File(base).isFile()) { toast("Choose the game file first (.nsp, .xci or .nup)"); pickGameFile(); return; }
        if (upd == null || !new File(upd).isFile()) { toast("Choose the 13.0.5 update (v2031616) first"); pickUpdate(); return; }
        java.util.List<String> in = new java.util.ArrayList<>();
        in.add(base); in.add(upd); in.addAll(dlcPaths());
        long need = 0; for (String f : in) need += new File(f).length();
        long old = gameFile().isFile() ? gameFile().length() : 0;
        long free = freeSpace();
        boolean ok = free + old >= need + (256L << 20);
        ImportInfo info = new ImportInfo();
        info.inputs.addAll(in);
        info.bytes = need;
        info.titles = in.size();
        info.name = NAME;
        new AlertDialog.Builder(this).setTitle("Build the game file?")
                .setMessage(in.size() + " files (" + human(need) + ")\n\n"
                        + "The app writes one .nup of about " + human(need) + " from the game, the 13.0.5 update and " + dlcPaths().size() + " DLC"
                        + (old > 0 ? "; the game file in the app (" + human(old) + ") is replaced" : "") + ".\n\n"
                        + "Free space: " + human(free) + (old > 0 ? " + " + human(old) : "") + " · needed: " + human(need + (256L << 20)) + "\n\n"
                        + "It takes a few minutes and the phone warms up; you can cancel while it runs.")
                .setPositiveButton(ok ? "Build" : "Not enough space", (d, w) -> { if (ok) startImport(info, false); })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- play ----

    private void play() {
        if (importing) { toast("The import is still running"); return; }
        if (preparing) { toast("Still preparing the game data"); return; }
        if (basePath() != null || updatePath() != null) { build(); return; }
        if (!gameFile().isFile()) { toast("Choose the game file first (.nup, .nsp or .xci)"); pickGameFile(); return; }
        if (!new File(packDir(), "pack.tsv").isFile()) { toast("The game data is not ready: opening it again"); prepare(null); return; }
        writeEnv();
        Intent i = new Intent(this, GameActivity.class)
                .putExtra("title_id", TITLE_ID)
                .putExtra("name", NAME)
                .putExtra("game", gameFile().getPath())
                .putExtra("pack", packDir().getPath());
        startActivity(i);
    }

    // ---- the screen ----

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(30), dp(22), dp(28));
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        try (InputStream in = getAssets().open(ASSETS + "/icon.png")) {
            Drawable d = Drawable.createFromStream(in, "icon");
            if (d != null) icon.setImageDrawable(d);
        } catch (IOException ignored) { }
        head.addView(icon, new LinearLayout.LayoutParams(dp(92), dp(92)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(16), 0, 0, 0);
        titles.addView(label("SUPER SMASH BROS.", 24, TEXT, true));
        titles.addView(label("ULTIMATE · Native", 14, DIM, false));
        head.addView(titles);
        box.addView(head);

        box.addView(space(26));
        gameValue = card(box, "GAME FILE", "Not chosen", this::pickGameFile);
        updateValue = card(box, "UPDATE (13.0.5)", "—", this::pickUpdate);
        dlcValue = card(box, "DLC", "—", this::pickDlc);
        packValue = card(box, "PACK", "—", this::pickPack);
        driverValue = card(box, "GPU DRIVER", "—", this::driverDialog);
        pictureValue = card(box, "GRAPHICS", "—", this::pictureDialog);
        savesValue = card(box, "SAVES", "—", this::savesDialog);
        keysValue = card(box, "KEYS", "—", this::keysDialog);
        firmwareValue = card(box, "FIRMWARE", "—", this::firmwareDialog);

        box.addView(space(26));
        LinearLayout play = new LinearLayout(this);
        play.setGravity(Gravity.CENTER);
        play.setBackground(round(ACCENT, 18));
        playLabel = label("PLAY", 22, 0xFFFFFFFF, true);
        playLabel.setPadding(0, dp(22), 0, dp(22));
        play.addView(playLabel);
        play.setOnClickListener(v -> this.play());
        box.addView(play, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        playCard = play;

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        pp.topMargin = dp(22);
        box.addView(progress, pp);
        status = label("", 13, DIM, false);
        status.setPadding(0, dp(10), 0, 0);
        status.setVisibility(View.GONE);
        box.addView(status);
        cancelLink = label("Cancel the import", 14, ACCENT, true);
        cancelLink.setGravity(Gravity.CENTER);
        cancelLink.setPadding(0, dp(14), 0, dp(6));
        cancelLink.setVisibility(View.GONE);
        cancelLink.setOnClickListener(v -> {
            if (!importing) return;
            cancelImport = true;
            try { dev.peanuts.importer.Native.cancel(true); } catch (Throwable ignored) { }
            toast("Stopping…");
        });
        box.addView(cancelLink);
        return scroll;
    }

    private TextView card(LinearLayout parent, String title, String value, Runnable tap) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(15), dp(18), dp(15));
        c.setBackground(round(CARD, 16));
        c.addView(label(title, 12, DIM, true));
        TextView v = label(value, 17, TEXT, false);
        v.setPadding(0, dp(4), 0, 0);
        c.addView(v);
        c.setOnClickListener(x -> tap.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        parent.addView(c, lp);
        return v;
    }

    private void refresh() {
        boolean nup = gameFile().isFile();
        String base = basePath();
        gameValue.setText(nup && base == null ? GAME_FILE + " · " + human(gameFile().length())
                : base != null ? new File(base).getName() + " · " + human(new File(base).length())
                : "Not chosen — tap for .nup, .nsp or .xci");
        gameValue.setTextColor(nup || base != null ? TEXT : ACCENT);
        String upd = updatePath();
        updateValue.setText(upd != null ? "13.0.5 (v2031616) · " + new File(upd).getName() : "Not chosen — needs 13.0.5 (v2031616)");
        updateValue.setTextColor(upd != null ? TEXT : ACCENT);
        int nd = dlcPaths().size();
        dlcValue.setText(nd == 0 ? "None — tap to pick .nsp files" : nd + (nd == 1 ? " file" : " files") + " · " + human(prefs().getLong("dlc_bytes", 0)));
        dlcValue.setTextColor(nd == 0 ? DIM : TEXT);
        GpuDriver.Info drv = GpuDriver.selected(this);
        driverValue.setText(drv == null ? "System (the phone's own)" : drv.label());
        driverValue.setTextColor(drv == null ? DIM : TEXT);
        pictureValue.setText("Resolution " + Math.round(resScale() * 100) + " %");
        int files = count(saveDir());
        savesValue.setText(files == 0 ? "No save — tap to load a .zip" : files + (files == 1 ? " file" : " files") + " · load or back up");
        boolean keys = PeanutsPrefs.hasKeys(this);
        keysValue.setText(keys ? "prod.keys loaded (" + human(PeanutsPrefs.keysFile(this).length()) + ")" : "Add your prod.keys");
        keysValue.setTextColor(keys ? TEXT : ACCENT);
        String fw = PeanutsPrefs.firmwareDir(this);   // set only when the player added a firmware; the APK's stand-in fonts are not one
        firmwareValue.setText(fw != null ? "Firmware added" + (fw.length() < 42 ? " · " + fw : "") : "Stand-in fonts — add your firmware (.zip or folder)");
        firmwareValue.setTextColor(fw != null ? TEXT : ACCENT);
        boolean canBuild = base != null && upd != null;
        boolean pack = new File(packDir(), "pack.tsv").isFile();
        packValue.setText(pack ? "Ready" : "Not added — tap to import a .zip");
        packValue.setTextColor(pack ? TEXT : ACCENT);
        boolean ready = !preparing && !importing && (canBuild || (nup && pack));
        if (playCard != null) playCard.setAlpha(ready ? 1f : 0.45f);
        if (playLabel != null) playLabel.setText(importing ? "IMPORTING…" : canBuild ? (nup ? "REBUILD GAME FILE" : "BUILD GAME FILE")
                : nup ? (pack ? "PLAY" : "PREPARING…") : "CHOOSE THE GAME FILE");
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View space(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dp)));
        return v;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ---- files ----

    private static void copy(InputStream in, OutputStream out, Progress progress, long total) throws IOException {
        byte[] buf = new byte[1 << 20];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (progress != null && total > 0) progress.at((int) Math.min(100, done * 100 / total));
        }
        out.flush();
    }

    /** A zip asset into a folder, with progress by the entries' sizes (the asset is opened twice: once to count, once to read) */
    private void unzipAsset(String asset, File dir, Progress progress) throws IOException {
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(getAssets().open(asset))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) if (e.getSize() > 0) total += e.getSize();
        }
        try (ZipInputStream in = new ZipInputStream(getAssets().open(asset))) { unzipEntries(in, dir, progress, total); }
    }

    private static void unzipStream(InputStream raw, File dir, Progress progress) throws IOException {
        try (ZipInputStream in = new ZipInputStream(raw)) { unzipEntries(in, dir, progress, 0); }
    }

    private static void unzipEntries(ZipInputStream in, File dir, Progress progress, long total) throws IOException {
        byte[] buf = new byte[1 << 20];
        long done = 0;
        String base = dir.getCanonicalPath() + File.separator;
        for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
            File f = new File(dir, e.getName());
            if (!f.getCanonicalPath().startsWith(base)) continue;   // never outside the target
            if (e.isDirectory()) { f.mkdirs(); continue; }
            File p = f.getParentFile();
            if (p != null && !p.isDirectory() && !p.mkdirs()) throw new IOException("cannot create " + p);
            try (OutputStream out = new FileOutputStream(f)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null && total > 0) progress.at((int) Math.min(100, done * 100 / total));
                }
            }
        }
    }

    private static void zipInto(ZipOutputStream z, File dir, String rel) throws IOException {
        File[] list = dir.listFiles();
        if (list == null) return;
        byte[] buf = new byte[1 << 20];
        for (File f : list) {
            String name = rel.isEmpty() ? f.getName() : rel + "/" + f.getName();
            if (f.isDirectory()) { zipInto(z, f, name); continue; }
            z.putNextEntry(new ZipEntry(name));
            try (InputStream in = new FileInputStream(f)) { int n; while ((n = in.read(buf)) > 0) z.write(buf, 0, n); }
            z.closeEntry();
        }
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            File[] list = from.listFiles();
            if (list != null) for (File f : list) copyTree(f, new File(to, f.getName()));
            return;
        }
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) { copy(in, out, null, 0); }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] list = f.listFiles();
            if (list != null) for (File x : list) deleteTree(x);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static int count(File dir) {
        File[] list = dir.listFiles();
        if (list == null) return 0;
        int n = 0;
        for (File f : list) n += f.isDirectory() ? count(f) : 1;
        return n;
    }

    private static String human(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(Locale.ROOT, "%.0f MB", bytes / (double) (1L << 20));
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
    }

    /** The real /storage path behind a documents provider's URI, or null when it has none (then the copy goes through the resolver) */
    private static String realPath(Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getPath();
        if (!"content".equals(uri.getScheme()) || !"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
        try {
            String id = DocumentsContract.getDocumentId(uri);   // "primary:Roms/SmashUltimate.nup"
            String[] parts = id.split(":", 2);
            if (parts.length != 2) return null;
            String base = "primary".equalsIgnoreCase(parts[0]) ? Environment.getExternalStorageDirectory().getPath() : "/storage/" + parts[0];
            return base + "/" + parts[1];
        } catch (RuntimeException e) { return null; }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) { }
        return uri.getLastPathSegment();
    }

    private long displaySize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) { }
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath()).length();
        return -1;
    }

    // ---- the picker's answers ----

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        // KEYS / FIRMWARE / Console pickers of the launcher's own LibrarySettings (its requests 60..70); true when it handled this one
        if (dev.peanuts.ui.LibrarySettings.result(this, req, res, data)) { refresh(); ui.postDelayed(this::refresh, 4000); return; }
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_DLC) { dlcPicked(data); return; }
        if (req == REQ_PACK && data.getData() != null) { packPicked(data.getData()); return; }
        if (data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_NUP) askGameFile(uri);
        else if (req == REQ_UPDATE) updatePicked(uri);
        else if (req == REQ_DRIVER) driverPicked(uri);
        else if (req == REQ_SAVE_OUT) writeSaveZip(uri);
        else if (req == REQ_SAVE_IN) restoreSaveZip(uri);
    }
}
