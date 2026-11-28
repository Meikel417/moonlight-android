package com.limelight.hub;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.ServerHelper;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Startbildschirm der Hub-App: oben Statuszeile (Tailscale, Heim-WLAN, welcher PC läuft),
 * in der Mitte die letzten Nachrichten, unten die Kacheln.
 */
public class HubActivity extends Activity {
    private static final int ANFRAGE_STANDORT = 4711;
    private static final long STATUS_INTERVALL_MS = 3000;
    private static final long TAILSCALE_WARTEZEIT_MS = 2500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, ComputerDetails> computer = new LinkedHashMap<>();

    private TextView chipTailscale;
    private TextView chipWlan;
    private TextView chipPc;
    private LinearLayout kachelBereich;
    private TextView updateChip;

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean imVordergrund;
    private boolean pollingLaeuft;

    private final Runnable statusAktualisieren = new Runnable() {
        @Override
        public void run() {
            zeigeNetzStatus();
            if (imVordergrund) {
                handler.postDelayed(this, STATUS_INTERVALL_MS);
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder lokal =
                    (ComputerManagerService.ComputerManagerBinder) binder;
            new Thread(() -> {
                lokal.waitForReady();
                runOnUiThread(() -> {
                    managerBinder = lokal;
                    starteAbfrage();
                });
            }).start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            managerBinder = null;
            pollingLaeuft = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hub);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(getResources().getColor(R.color.hub_hintergrund));
            getWindow().setNavigationBarColor(getResources().getColor(R.color.hub_hintergrund));
        }

        chipTailscale = findViewById(R.id.hubChipTailscale);
        chipWlan = findViewById(R.id.hubChipWlan);
        chipPc = findViewById(R.id.hubChipPc);
        kachelBereich = findViewById(R.id.hubKacheln);

        chipTailscale.setOnClickListener(v -> tailscaleAntippen());
        chipWlan.setOnClickListener(v -> wlanAntippen());
        findViewById(R.id.hubMoonlightAnsicht).setOnClickListener(v -> oeffneMoonlightAnsicht());

        // Update: beim Start still nachsehen, Titel antippen = von Hand nachsehen
        updateChip = findViewById(R.id.hubUpdate);
        findViewById(R.id.hubTitel).setOnClickListener(v -> pruefeUpdate(true));
        pruefeUpdate(false);

        ladeGespeicherteComputer();
        baueKacheln();

        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        imVordergrund = true;
        handler.removeCallbacks(statusAktualisieren);
        handler.post(statusAktualisieren);
        starteAbfrage();
    }

    @Override
    protected void onPause() {
        super.onPause();
        imVordergrund = false;
        handler.removeCallbacks(statusAktualisieren);
        stoppeAbfrage();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (managerBinder != null) {
            stoppeAbfrage();
            unbindService(serviceConnection);
            managerBinder = null;
        }
    }

    // ---------------------------------------------------------------- Computer

    private void ladeGespeicherteComputer() {
        ComputerDatabaseManager db = null;
        try {
            db = new ComputerDatabaseManager(this);
            for (ComputerDetails d : db.getAllComputers()) {
                computer.put(d.uuid, d);
            }
        } catch (Exception e) {
            // Ohne gespeicherte Computer zeigen wir einfach nur die festen Kacheln
        } finally {
            if (db != null) {
                db.close();
            }
        }
    }

    private void starteAbfrage() {
        if (managerBinder == null || pollingLaeuft || !imVordergrund) {
            return;
        }
        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                runOnUiThread(() -> {
                    if (details.uuid == null) {
                        return;
                    }
                    computer.put(details.uuid, new ComputerDetails(details));
                    baueKacheln();
                    zeigePcStatus();
                });
            }
        });
        pollingLaeuft = true;
    }

    private void stoppeAbfrage() {
        if (managerBinder != null && pollingLaeuft) {
            managerBinder.stopPolling();
            pollingLaeuft = false;
        }
    }

    // ---------------------------------------------------------------- Statuszeile

    private void zeigeNetzStatus() {
        boolean vpn = NetzStatus.vpnAktiv(this);
        setzeChip(chipTailscale, vpn ? getString(R.string.hub_tailscale_an) : getString(R.string.hub_tailscale_aus),
                vpn ? R.color.hub_ok : R.color.hub_warnung);

        boolean keinHeimWlan = NetzStatus.heimWlans(this).isEmpty();
        if (!NetzStatus.wlanVerbunden(this)) {
            setzeChip(chipWlan, getString(R.string.hub_wlan_keins), R.color.hub_text_gedimmt);
        } else if (keinHeimWlan || !NetzStatus.standortErlaubt(this)) {
            setzeChip(chipWlan, getString(R.string.hub_wlan_unbekannt), R.color.hub_text_gedimmt);
        } else if (NetzStatus.imHeimWlan(this)) {
            setzeChip(chipWlan, getString(R.string.hub_wlan_heim), R.color.hub_ok);
        } else {
            setzeChip(chipWlan, getString(R.string.hub_wlan_fremd), R.color.hub_warnung);
        }

        zeigePcStatus();
    }

    private void zeigePcStatus() {
        List<String> laufend = new ArrayList<>();
        for (ComputerDetails d : computer.values()) {
            if (d.state == ComputerDetails.State.ONLINE) {
                laufend.add(d.name);
            }
        }
        if (laufend.isEmpty()) {
            setzeChip(chipPc, getString(R.string.hub_pc_keiner), R.color.hub_text_gedimmt);
        } else {
            setzeChip(chipPc, getString(R.string.hub_pc_laeuft, android.text.TextUtils.join(", ", laufend)), R.color.hub_ok);
        }
    }

    private void setzeChip(TextView chip, String text, int farbe) {
        chip.setText(text);
        chip.setTextColor(getResources().getColor(farbe));
    }

    private void tailscaleAntippen() {
        if (!NetzStatus.tailscaleInstalliert(this)) {
            Toast.makeText(this, R.string.hub_tailscale_fehlt, Toast.LENGTH_LONG).show();
            return;
        }
        if (NetzStatus.vpnAktiv(this)) {
            NetzStatus.tailscaleOeffnen(this);
            return;
        }
        tailscaleEinschaltenUndDann(null);
    }

    /** Schaltet Tailscale ein und führt danach {@code danach} aus. Klappt es nicht, öffnet sich Tailscale. */
    private void tailscaleEinschaltenUndDann(final Runnable danach) {
        Toast.makeText(this, R.string.hub_tailscale_verbinde, Toast.LENGTH_SHORT).show();
        try {
            NetzStatus.tailscaleEinschalten(this);
        } catch (Exception ignored) {
            // Manche Tailscale-Versionen kennen den Befehl nicht, dann hilft nur die App
        }
        handler.postDelayed(() -> {
            zeigeNetzStatus();
            if (NetzStatus.vpnAktiv(this)) {
                if (danach != null) {
                    danach.run();
                }
            } else {
                Toast.makeText(this, R.string.hub_tailscale_selbst, Toast.LENGTH_LONG).show();
                NetzStatus.tailscaleOeffnen(this);
            }
        }, TAILSCALE_WARTEZEIT_MS);
    }

    private void wlanAntippen() {
        if (!NetzStatus.wlanVerbunden(this)) {
            Toast.makeText(this, R.string.hub_wlan_keins_hinweis, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!NetzStatus.standortErlaubt(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.hub_wlan_dialog_titel)
                        .setMessage(R.string.hub_wlan_berechtigung)
                        .setPositiveButton(R.string.hub_ok, (d, w) -> requestPermissions(
                                new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION}, ANFRAGE_STANDORT))
                        .setNegativeButton(R.string.hub_abbrechen, null)
                        .show();
            }
            return;
        }
        frageHeimWlanSpeichern();
    }

    /** Zeigt die gespeicherten Heim-WLANs: aktuelles hinzufügen, gespeicherte antippen zum Entfernen. */
    private void frageHeimWlanSpeichern() {
        final String ssid = NetzStatus.aktuellesWlan(this);
        final String[] liste = NetzStatus.heimWlans(this).toArray(new String[0]);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.hub_wlan_liste_titel)
                .setNegativeButton(R.string.hub_schliessen, null);
        if (liste.length == 0) {
            b.setMessage(R.string.hub_wlan_liste_leer);
        } else {
            b.setItems(liste, (d, welches) -> frageHeimWlanEntfernen(liste[welches]));
        }
        if (ssid == null) {
            Toast.makeText(this, R.string.hub_wlan_name_fehlt, Toast.LENGTH_LONG).show();
        } else if (!NetzStatus.heimWlans(this).contains(ssid)) {
            b.setPositiveButton(getString(R.string.hub_wlan_hinzufuegen, ssid), (d, w) -> {
                NetzStatus.heimWlanHinzufuegen(this, ssid);
                zeigeNetzStatus();
            });
        }
        b.show();
    }

    private void frageHeimWlanEntfernen(final String ssid) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.hub_wlan_liste_titel)
                .setMessage(getString(R.string.hub_wlan_entfernen_frage, ssid))
                .setPositiveButton(R.string.hub_entfernen, (d, w) -> {
                    NetzStatus.heimWlanEntfernen(this, ssid);
                    zeigeNetzStatus();
                })
                .setNegativeButton(R.string.hub_abbrechen, null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ANFRAGE_STANDORT && NetzStatus.standortErlaubt(this)) {
            frageHeimWlanSpeichern();
        }
    }

    // ---------------------------------------------------------------- Kacheln

    private void baueKacheln() {
        kachelBereich.removeAllViews();

        List<View> kacheln = new ArrayList<>();
        for (final ComputerDetails d : computer.values()) {
            kacheln.add(hostKachel(d));
        }
        if (kacheln.isEmpty()) {
            kacheln.add(kachel(getString(R.string.hub_kachel_moonlight_icon), getString(R.string.hub_kachel_host_hinzufuegen),
                    getString(R.string.hub_kachel_host_hinzufuegen_info), true, v -> oeffneMoonlightAnsicht(), null));
        }
        kacheln.add(kachel(getString(R.string.hub_kachel_sprache_icon), getString(R.string.hub_kachel_sprache),
                getString(R.string.hub_bald_stufe3), false,
                v -> Toast.makeText(this, R.string.hub_sprache_bald, Toast.LENGTH_SHORT).show(), null));
        kacheln.add(kachel(getString(R.string.hub_kachel_werkzeuge_icon), getString(R.string.hub_kachel_werkzeuge),
                getString(R.string.hub_bald_stufe2), false,
                v -> Toast.makeText(this, R.string.hub_werkzeuge_bald, Toast.LENGTH_SHORT).show(), null));

        // Immer zwei Kacheln pro Reihe
        LinearLayout reihe = null;
        for (int i = 0; i < kacheln.size(); i++) {
            if (i % 2 == 0) {
                reihe = new LinearLayout(this);
                reihe.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(12);
                kachelBereich.addView(reihe, rp);
            }
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, dp(104), 1f);
            if (i % 2 == 1) {
                kp.leftMargin = dp(12);
            }
            reihe.addView(kacheln.get(i), kp);
        }
        if (kacheln.size() % 2 == 1 && reihe != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(104), 1f);
            lp.leftMargin = dp(12);
            reihe.addView(new View(this), lp);
        }
    }

    private View hostKachel(final ComputerDetails d) {
        String info;
        boolean aktiv;
        if (d.state == ComputerDetails.State.ONLINE) {
            info = getString(R.string.hub_host_laeuft);
            aktiv = true;
        } else if (d.state == ComputerDetails.State.OFFLINE) {
            info = getString(R.string.hub_host_aus);
            aktiv = false;
        } else {
            info = getString(R.string.hub_host_pruefe);
            aktiv = false;
        }
        return kachel(getString(R.string.hub_kachel_host_icon), d.name, info, aktiv,
                v -> mitHeimnetz(() -> starteDesktop(d)),
                v -> {
                    mitHeimnetz(() -> oeffneAppListe(d));
                    return true;
                });
    }

    private View kachel(String icon, String titel, String info, boolean hervorgehoben,
                        View.OnClickListener klick, View.OnLongClickListener langerKlick) {
        LinearLayout k = new LinearLayout(this);
        k.setOrientation(LinearLayout.VERTICAL);
        k.setGravity(Gravity.CENTER_VERTICAL);
        k.setPadding(dp(16), dp(12), dp(16), dp(12));
        k.setBackgroundResource(hervorgehoben ? R.drawable.bg_hub_kachel_aktiv : R.drawable.bg_hub_kachel);
        k.setClickable(true);
        k.setFocusable(true);
        k.setOnClickListener(klick);
        if (langerKlick != null) {
            k.setOnLongClickListener(langerKlick);
        }

        TextView i = new TextView(this);
        i.setText(icon);
        i.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        k.addView(i);

        TextView t = new TextView(this);
        t.setText(titel);
        t.setTextColor(getResources().getColor(R.color.hub_text));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        k.addView(t, tp);

        TextView s = new TextView(this);
        s.setText(info);
        s.setTextColor(getResources().getColor(hervorgehoben ? R.color.hub_akzent : R.color.hub_text_gedimmt));
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        k.addView(s);

        return k;
    }

    // ---------------------------------------------------------------- Aktionen

    /** Führt {@code aktion} aus, wenn das Heimnetz erreichbar ist; sonst wird nach Tailscale gefragt. */
    private void mitHeimnetz(final Runnable aktion) {
        boolean erreichbar = NetzStatus.vpnAktiv(this)
                || (NetzStatus.heimWlans(this).isEmpty() ? NetzStatus.wlanVerbunden(this) : NetzStatus.imHeimWlan(this));
        if (erreichbar) {
            aktion.run();
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(R.string.hub_nicht_daheim_titel)
                .setMessage(R.string.hub_nicht_daheim_text)
                .setNeutralButton(R.string.hub_trotzdem, (d, w) -> aktion.run())
                .setNegativeButton(R.string.hub_abbrechen, null);
        if (NetzStatus.tailscaleInstalliert(this)) {
            b.setPositiveButton(R.string.hub_tailscale_einschalten, (d, w) -> tailscaleEinschaltenUndDann(aktion));
        }
        b.show();
    }

    /** Startet direkt den Desktop-Stream. Ist der PC aus, weckt Moonlight ihn und wartet. */
    private void starteDesktop(ComputerDetails d) {
        NvApp desktop = findeDesktop(d);
        Intent i = desktop != null
                ? ServerHelper.createAppShortcutIntent(this, d, desktop)
                : ServerHelper.createPcShortcutIntent(this, d);
        startActivity(i);
    }

    /** Öffnet die App-Liste des Hosts (langer Druck auf die Kachel). */
    private void oeffneAppListe(ComputerDetails d) {
        startActivity(ServerHelper.createPcShortcutIntent(this, d));
    }

    private NvApp findeDesktop(ComputerDetails d) {
        try {
            String roh = CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(getCacheDir(), "applist", d.uuid));
            if (roh == null || roh.isEmpty()) {
                return null;
            }
            for (NvApp app : NvHTTP.getAppListByReader(new StringReader(roh))) {
                if (app.getAppName() != null && app.getAppName().trim().equalsIgnoreCase("Desktop")) {
                    return app;
                }
            }
        } catch (Exception e) {
            // Noch keine App-Liste gespeichert: dann öffnet sich die App-Liste
        }
        return null;
    }

    // ---------------------------------------------------------------- Update

    private void pruefeUpdate(final boolean vonHand) {
        if (vonHand) {
            Toast.makeText(this, R.string.hub_update_pruefe, Toast.LENGTH_SHORT).show();
        }
        HubUpdater.pruefe(this, release -> {
            if (release != null && release.istNeuer()) {
                updateChip.setVisibility(View.VISIBLE);
                updateChip.setOnClickListener(v -> HubUpdater.frageUndInstalliere(this, release));
                if (vonHand) {
                    HubUpdater.frageUndInstalliere(this, release);
                }
            } else {
                updateChip.setVisibility(View.GONE);
                if (vonHand) {
                    Toast.makeText(this, release == null
                            ? getString(R.string.hub_update_nicht_erreichbar)
                            : getString(R.string.hub_update_aktuell, HubUpdater.installierterBuild()),
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private void oeffneMoonlightAnsicht() {
        startActivity(new Intent(this, PcView.class));
    }

    private int dp(int wert) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, wert,
                getResources().getDisplayMetrics()));
    }
}
