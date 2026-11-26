package com.limelight.hub;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Prüft, ob das Handy im Heim-WLAN ist oder Tailscale (VPN) läuft,
 * und kann Tailscale einschalten bzw. öffnen.
 */
public final class NetzStatus {
    public static final String TAILSCALE_PAKET = "com.tailscale.ipn";
    private static final String PREFS = "hub_einstellungen";
    private static final String KEY_HEIM_WLAN_ALT = "heim_wlan_ssid";
    private static final String KEY_HEIM_WLANS = "heim_wlans";

    private NetzStatus() {}

    /** Läuft gerade ein VPN (bei Meikel: Tailscale)? */
    public static boolean vpnAktiv(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
            return false;
        }
        NetworkInfo info = cm.getNetworkInfo(ConnectivityManager.TYPE_VPN);
        return info != null && info.isConnected();
    }

    /** Ist das Handy überhaupt mit einem WLAN verbunden? */
    public static boolean wlanVerbunden(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return true;
                }
            }
            return false;
        }
        NetworkInfo info = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return info != null && info.isConnected();
    }

    public static boolean standortErlaubt(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Name des aktuellen WLANs, oder null (kein WLAN oder keine Standort-Berechtigung). */
    @SuppressWarnings("deprecation")
    public static String aktuellesWlan(Context ctx) {
        if (!wlanVerbunden(ctx) || !standortErlaubt(ctx)) {
            return null;
        }
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) {
            return null;
        }
        WifiInfo info = wm.getConnectionInfo();
        if (info == null || info.getSSID() == null) {
            return null;
        }
        String ssid = info.getSSID();
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        if (ssid.isEmpty() || "<unknown ssid>".equals(ssid)) {
            return null;
        }
        return ssid;
    }

    /** Alle gespeicherten Heim-WLANs (es kann mehrere geben). */
    public static Set<String> heimWlans(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Set<String> liste = new TreeSet<>(p.getStringSet(KEY_HEIM_WLANS, new HashSet<String>()));
        // Alte Version hat nur ein einziges Heim-WLAN gespeichert
        String alt = p.getString(KEY_HEIM_WLAN_ALT, null);
        if (alt != null) {
            liste.add(alt);
            p.edit().putStringSet(KEY_HEIM_WLANS, new HashSet<>(liste)).remove(KEY_HEIM_WLAN_ALT).apply();
        }
        return liste;
    }

    public static void heimWlanHinzufuegen(Context ctx, String ssid) {
        Set<String> liste = heimWlans(ctx);
        liste.add(ssid);
        prefs(ctx).edit().putStringSet(KEY_HEIM_WLANS, new HashSet<>(liste)).apply();
    }

    public static void heimWlanEntfernen(Context ctx, String ssid) {
        Set<String> liste = heimWlans(ctx);
        liste.remove(ssid);
        prefs(ctx).edit().putStringSet(KEY_HEIM_WLANS, new HashSet<>(liste)).apply();
    }

    public static boolean imHeimWlan(Context ctx) {
        String aktuell = aktuellesWlan(ctx);
        return aktuell != null && heimWlans(ctx).contains(aktuell);
    }

    /** Ist das Heimnetz erreichbar (Heim-WLAN oder Tailscale)? */
    public static boolean heimnetzErreichbar(Context ctx) {
        return vpnAktiv(ctx) || imHeimWlan(ctx);
    }

    public static boolean tailscaleInstalliert(Context ctx) {
        return ctx.getPackageManager().getLaunchIntentForPackage(TAILSCALE_PAKET) != null;
    }

    /**
     * Bittet Tailscale, sich zu verbinden. Tailscale bietet dafür eine Broadcast-Aktion
     * (gedacht für Tasker & Co.). Ob sie ankommt, prüft der Aufrufer danach über {@link #vpnAktiv}.
     */
    public static void tailscaleEinschalten(Context ctx) {
        Intent i = new Intent("com.tailscale.ipn.CONNECT_VPN");
        i.setComponent(new ComponentName(TAILSCALE_PAKET, "com.tailscale.ipn.IPNReceiver"));
        ctx.sendBroadcast(i);
    }

    /** Öffnet die Tailscale-App. Gibt false zurück, wenn sie nicht installiert ist. */
    public static boolean tailscaleOeffnen(Context ctx) {
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(TAILSCALE_PAKET);
        if (i == null) {
            return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        return true;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
