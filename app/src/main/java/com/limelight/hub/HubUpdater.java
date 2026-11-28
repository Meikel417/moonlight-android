package com.limelight.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.support.v4.content.FileProvider;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Schaut in den GitHub-Releases des Forks nach einer neueren Version,
 * lädt die APK herunter und übergibt sie an den Android-Installer.
 * Android fragt vor dem Installieren immer selbst nach (ohne Root geht es nicht still).
 */
public final class HubUpdater {
    private static final String RELEASES_URL =
            "https://api.github.com/repos/Meikel417/moonlight-android/releases/latest";
    private static final String TAG_PREFIX = "build-";

    public static final class Release {
        public final int build;
        public final String apkUrl;
        public final String text;

        Release(int build, String apkUrl, String text) {
            this.build = build;
            this.apkUrl = apkUrl;
            this.text = text;
        }

        public boolean istNeuer() {
            return build > BuildConfig.HUB_BUILD;
        }
    }

    public interface Ergebnis {
        /** Wird im UI-Thread aufgerufen. {@code release} ist null, wenn die Abfrage fehlschlug. */
        void fertig(Release release);
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private HubUpdater() {}

    public static int installierterBuild() {
        return BuildConfig.HUB_BUILD;
    }

    /** Fragt die neueste Version bei GitHub ab (im Hintergrund). */
    public static void pruefe(final Activity activity, final Ergebnis ergebnis) {
        new Thread(() -> {
            Release release = null;
            try {
                Request req = new Request.Builder()
                        .url(RELEASES_URL)
                        .header("Accept", "application/vnd.github+json")
                        .build();
                try (Response resp = CLIENT.newCall(req).execute()) {
                    if (resp.isSuccessful() && resp.body() != null) {
                        release = lese(new JSONObject(resp.body().string()));
                    }
                }
            } catch (Exception ignored) {
                // Kein Netz oder GitHub nicht erreichbar: dann eben kein Update-Hinweis
            }
            final Release r = release;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) {
                    ergebnis.fertig(r);
                }
            });
        }).start();
    }

    private static Release lese(JSONObject json) throws Exception {
        String tag = json.optString("tag_name", "");
        if (!tag.startsWith(TAG_PREFIX)) {
            return null;
        }
        int build = Integer.parseInt(tag.substring(TAG_PREFIX.length()).trim());
        String apk = null;
        JSONArray assets = json.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                    apk = a.optString("browser_download_url", null);
                    break;
                }
            }
        }
        if (apk == null) {
            return null;
        }
        return new Release(build, apk, json.optString("body", ""));
    }

    /** Fragt nach, lädt herunter und startet die Installation. */
    public static void frageUndInstalliere(final Activity activity, final Release release) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.hub_update_titel)
                .setMessage(activity.getString(R.string.hub_update_frage, release.build, installierterBuild()))
                .setPositiveButton(R.string.hub_update_installieren, (d, w) -> installiere(activity, release))
                .setNegativeButton(R.string.hub_update_spaeter, null)
                .show();
    }

    @SuppressWarnings("deprecation")
    private static void installiere(final Activity activity, final Release release) {
        // Ab Android 8 muss man einmalig erlauben, dass diese App andere Apps installieren darf
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.hub_update_titel)
                    .setMessage(R.string.hub_update_erlaubnis)
                    .setPositiveButton(R.string.hub_update_einstellungen, (d, w) -> activity.startActivity(
                            new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName()))))
                    .setNegativeButton(R.string.hub_abbrechen, null)
                    .show();
            return;
        }

        final ProgressDialog fortschritt = new ProgressDialog(activity);
        fortschritt.setTitle(R.string.hub_update_titel);
        fortschritt.setMessage(activity.getString(R.string.hub_update_laedt));
        fortschritt.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        fortschritt.setMax(100);
        fortschritt.setCancelable(false);
        fortschritt.show();

        new Thread(() -> {
            File ziel = null;
            try {
                // Vor Android 7 kann der Installer nur öffentlich lesbare Dateien öffnen
                File basis = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N || activity.getExternalCacheDir() == null
                        ? activity.getCacheDir() : activity.getExternalCacheDir();
                File ordner = new File(basis, "updates");
                if (!ordner.exists() && !ordner.mkdirs()) {
                    throw new Exception("Ordner fehlt");
                }
                File[] alte = ordner.listFiles();
                if (alte != null) {
                    for (File f : alte) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                ziel = new File(ordner, "hub-" + release.build + ".apk");

                Request req = new Request.Builder().url(release.apkUrl).build();
                try (Response resp = CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) {
                        throw new Exception("HTTP " + resp.code());
                    }
                    long gesamt = resp.body().contentLength();
                    try (InputStream in = resp.body().byteStream();
                         OutputStream out = new FileOutputStream(ziel)) {
                        byte[] puffer = new byte[64 * 1024];
                        long geladen = 0;
                        int n;
                        while ((n = in.read(puffer)) != -1) {
                            out.write(puffer, 0, n);
                            geladen += n;
                            if (gesamt > 0) {
                                final int prozent = (int) (geladen * 100 / gesamt);
                                activity.runOnUiThread(() -> fortschritt.setProgress(prozent));
                            }
                        }
                    }
                }
            } catch (Exception e) {
                ziel = null;
            }

            final File apk = ziel;
            activity.runOnUiThread(() -> {
                fortschritt.dismiss();
                if (apk == null) {
                    Toast.makeText(activity, R.string.hub_update_fehler, Toast.LENGTH_LONG).show();
                    return;
                }
                starteInstaller(activity, apk);
            });
        }).start();
    }

    private static void starteInstaller(Activity activity, File apk) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(apk);
        }
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(activity, R.string.hub_update_fehler, Toast.LENGTH_LONG).show();
        }
    }
}
