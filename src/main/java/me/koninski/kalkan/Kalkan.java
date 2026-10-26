package me.koninski.kalkan;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hafif bot / baglanti seli korumasi. Tum kontroller AsyncPlayerPreLoginEvent icinde, oyuncu
 * olusturulmadan once yapilir; ana thread'e is dusmez.
 *
 * Katmanlar:
 *  1) Kullanici adi kurali (regex)
 *  2) IP basina baglanti hiz siniri (asilirsa gecici IP engeli)
 *  3) Saldiri modu (kisa surede cok farkli IP): sadece bilinen IP'ler girebilir
 *  4) IP basina ayni anda en fazla N online oyuncu
 */
public class Kalkan extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Pattern adKurali;
    private int pencereMs;
    private int maxDeneme;
    private long engelMs;
    private int ipBasinaOnline;
    private long saldiriPencereMs;
    private int saldiriEsik;
    private long saldiriSureMs;
    private boolean sadeceBilinen;
    private long logAralikMs;
    private final Set<String> guvenilir = ConcurrentHashMap.newKeySet();

    // Rezerve (yasakli) kullanici adlari, kucuk harfle tam eslesme. Config'te "rezerve-isimler" yoksa
    // (eski config.yml'ler anahtari icermez) bu varsayilan liste kullanilir.
    private static final List<String> VARSAYILAN_REZERVE_ISIMLER = List.of(
            "null", "nil", "none", "undefined", "console", "konsol", "server", "sunucu",
            "admin", "administrator", "root", "system", "owner", "operator", "op", "staff", "moderator");
    private final Set<String> rezerveIsimler = ConcurrentHashMap.newKeySet();

    // IP -> deneme zamanlari
    private final Map<String, ArrayDeque<Long>> denemeler = new ConcurrentHashMap<>();
    // IP -> engel bitis zamani
    private final Map<String, Long> engelli = new ConcurrentHashMap<>();
    // son saldiri penceresindeki farkli IP'ler (IP -> ilk gorulme)
    private final Map<String, Long> yeniIpPenceresi = new ConcurrentHashMap<>();
    // basariyla giris yapmis IP'ler
    private final Set<String> bilinen = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> onlineIp = new ConcurrentHashMap<>();
    private final Map<String, Long> sonLog = new ConcurrentHashMap<>();

    private volatile long saldiriBitis = 0L;
    private volatile boolean elleSaldiri = false;
    private volatile boolean bilinenDegisti = false;
    private final AtomicInteger engellenenSayac = new AtomicInteger();
    private Path bilinenDosya;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ayarlariYukle();
        bilinenDosya = getDataFolder().toPath().resolve("bilinen-ipler.txt");
        bilinenYukle();
        // zaten online olanlari say (reload durumu)
        for (Player p : Bukkit.getOnlinePlayers()) sayacArtir(p);
        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("kalkan").setExecutor(this);
        getCommand("kalkan").setTabCompleter(this);
        // bakim: eski kayitlari temizle, bilinen IP'leri kaydet (async)
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::bakim, 20L * 60, 20L * 60);
        getLogger().info("Kalkan aktif. Bilinen IP: " + bilinen.size());
    }

    @Override
    public void onDisable() {
        bilinenKaydet();
    }

    // ------------------------------------------------------------------ ayar
    private void ayarlariYukle() {
        reloadConfig();
        var c = getConfig();
        try {
            adKurali = Pattern.compile(c.getString("kullanici-adi-regex", "^[A-Za-z0-9_]{3,16}$"));
        } catch (Exception e) {
            getLogger().warning("kullanici-adi-regex gecersiz, varsayilan kullanildi.");
            adKurali = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
        }
        pencereMs = Math.max(1, c.getInt("baglanti.pencere-sn", 20)) * 1000;
        maxDeneme = Math.max(1, c.getInt("baglanti.max-deneme", 6));
        engelMs = Math.max(1, c.getInt("baglanti.engel-dk", 10)) * 60_000L;
        ipBasinaOnline = c.getInt("ip-basina-max-online", 3);
        saldiriPencereMs = Math.max(1, c.getInt("saldiri-modu.pencere-sn", 10)) * 1000L;
        saldiriEsik = Math.max(2, c.getInt("saldiri-modu.esik-farkli-ip", 8));
        saldiriSureMs = Math.max(10, c.getInt("saldiri-modu.sure-sn", 300)) * 1000L;
        sadeceBilinen = c.getBoolean("saldiri-modu.sadece-bilinen-ip", true);
        logAralikMs = Math.max(1, c.getInt("log-aralik-sn", 30)) * 1000L;
        guvenilir.clear();
        for (String s : c.getStringList("guvenilir-ipler")) guvenilir.add(s.trim());

        rezerveIsimler.clear();
        List<String> rezerve = c.contains("rezerve-isimler") ? c.getStringList("rezerve-isimler") : VARSAYILAN_REZERVE_ISIMLER;
        for (String s : rezerve) {
            if (s != null && !s.trim().isEmpty()) rezerveIsimler.add(s.trim().toLowerCase(Locale.ROOT));
        }
    }

    // ------------------------------------------------------------------ kalici kayit
    private void bilinenYukle() {
        try {
            if (Files.exists(bilinenDosya)) {
                for (String s : Files.readAllLines(bilinenDosya, StandardCharsets.UTF_8)) {
                    s = s.trim();
                    if (!s.isEmpty()) bilinen.add(s);
                }
            }
        } catch (IOException e) {
            getLogger().warning("bilinen-ipler.txt okunamadi: " + e.getMessage());
        }
    }

    private synchronized void bilinenKaydet() {
        try {
            Files.createDirectories(bilinenDosya.getParent());
            List<String> satirlar = new ArrayList<>(bilinen);
            Collections.sort(satirlar);
            Files.write(bilinenDosya, satirlar, StandardCharsets.UTF_8);
            bilinenDegisti = false;
        } catch (IOException e) {
            getLogger().warning("bilinen-ipler.txt yazilamadi: " + e.getMessage());
        }
    }

    private void bakim() {
        long simdi = System.currentTimeMillis();
        engelli.entrySet().removeIf(e -> e.getValue() <= simdi);
        denemeler.entrySet().removeIf(e -> {
            ArrayDeque<Long> q = e.getValue();
            synchronized (q) {
                while (!q.isEmpty() && simdi - q.peekFirst() > pencereMs) q.pollFirst();
                return q.isEmpty();
            }
        });
        yeniIpPenceresi.entrySet().removeIf(e -> simdi - e.getValue() > saldiriPencereMs);
        sonLog.entrySet().removeIf(e -> simdi - e.getValue() > 10 * logAralikMs);
        if (bilinenDegisti) bilinenKaydet();
    }

    // ------------------------------------------------------------------ yardimci
    private static String ipOf(InetAddress a) {
        return a == null ? "?" : a.getHostAddress();
    }

    public boolean saldiriModuAcik() {
        return elleSaldiri || System.currentTimeMillis() < saldiriBitis;
    }

    private void logla(String ip, String mesaj) {
        long simdi = System.currentTimeMillis();
        Long son = sonLog.get(ip);
        if (son != null && simdi - son < logAralikMs) return;
        sonLog.put(ip, simdi);
        getLogger().info("[ENGEL] " + ip + " - " + mesaj);
    }

    private void reddet(AsyncPlayerPreLoginEvent e, String ip, String log, String oyuncuMesaji) {
        engellenenSayac.incrementAndGet();
        logla(ip, log);
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(oyuncuMesaji, NamedTextColor.RED));
    }

    private void sayacArtir(Player p) {
        if (p.hasPermission("kalkan.muaf")) return;
        if (p.getAddress() == null) return;
        onlineIp.computeIfAbsent(ipOf(p.getAddress().getAddress()), k -> new AtomicInteger()).incrementAndGet();
    }

    // ------------------------------------------------------------------ giris kontrolu
    @EventHandler(priority = EventPriority.LOWEST)
    public void onOnGiris(AsyncPlayerPreLoginEvent e) {
        final String ip = ipOf(e.getAddress());
        if (guvenilir.contains(ip)) return;
        final long simdi = System.currentTimeMillis();

        // 1) ad kurali
        if (!adKurali.matcher(e.getName()).matches()) {
            reddet(e, ip, "gecersiz ad: " + e.getName().replaceAll("[^\\x20-\\x7E]", "?"), "Gecersiz kullanici adi.");
            return;
        }

        // 1b) rezerve isimler (null, console, server, admin ...): giris/kayit baglanti kurulmadan reddedilir.
        // AuthMe tek basina bu adlari engellemez; bu kontrol AuthMe'den once calisir (LOWEST).
        if (rezerveIsimler.contains(e.getName().toLowerCase(Locale.ROOT))) {
            reddet(e, ip, "rezerve ad: " + e.getName().replaceAll("[^\\x20-\\x7E]", "?"), "Bu kullanici adi kullanilamaz.");
            return;
        }

        // 2) hazirda engelli mi
        Long bitis = engelli.get(ip);
        if (bitis != null) {
            if (bitis > simdi) {
                reddet(e, ip, "engelli IP tekrar denedi", "Cok fazla baglanti denemesi. Bir sure sonra tekrar dene.");
                return;
            }
            engelli.remove(ip);
        }

        // 3) hiz siniri
        ArrayDeque<Long> q = denemeler.computeIfAbsent(ip, k -> new ArrayDeque<>());
        int sayi;
        synchronized (q) {
            while (!q.isEmpty() && simdi - q.peekFirst() > pencereMs) q.pollFirst();
            q.addLast(simdi);
            sayi = q.size();
        }
        if (sayi > maxDeneme) {
            engelli.put(ip, simdi + engelMs);
            reddet(e, ip, sayi + " deneme/" + (pencereMs / 1000) + "sn, " + (engelMs / 60000) + " dk engellendi",
                    "Cok fazla baglanti denemesi. Bir sure sonra tekrar dene.");
            return;
        }

        // 4) saldiri modu tespiti: kisa surede cok farkli IP
        if (!bilinen.contains(ip)) {
            yeniIpPenceresi.putIfAbsent(ip, simdi);
            yeniIpPenceresi.entrySet().removeIf(en -> simdi - en.getValue() > saldiriPencereMs);
            if (yeniIpPenceresi.size() >= saldiriEsik) {
                boolean yeni = !saldiriModuAcik();
                saldiriBitis = simdi + saldiriSureMs;
                if (yeni) {
                    getLogger().warning("SALDIRI MODU ACILDI: " + yeniIpPenceresi.size() + " farkli yeni IP/" + (saldiriPencereMs / 1000) + "sn");
                }
            }
        }
        if (saldiriModuAcik() && sadeceBilinen && !bilinen.contains(ip)) {
            reddet(e, ip, "saldiri modu: bilinmeyen IP", "Sunucu su an yogun baglanti altinda. Birkac dakika sonra tekrar dene.");
            return;
        }

        // 5) IP basina online siniri
        if (ipBasinaOnline > 0) {
            AtomicInteger n = onlineIp.get(ip);
            if (n != null && n.get() >= ipBasinaOnline) {
                reddet(e, ip, "IP basina online siniri (" + n.get() + ")", "Bu IP'den en fazla " + ipBasinaOnline + " hesap ayni anda girebilir.");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGiris(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.getAddress() == null) return;
        String ip = ipOf(p.getAddress().getAddress());
        if (bilinen.add(ip)) bilinenDegisti = true;
        sayacArtir(p);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCikis(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("kalkan.muaf") || p.getAddress() == null) return;
        String ip = ipOf(p.getAddress().getAddress());
        AtomicInteger n = onlineIp.get(ip);
        if (n != null && n.decrementAndGet() <= 0) onlineIp.remove(ip, n);
    }

    // ------------------------------------------------------------------ komut
    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!s.hasPermission("kalkan.admin")) {
            s.sendMessage(ChatColor.RED + "Yetkin yok.");
            return true;
        }
        String alt = a.length == 0 ? "durum" : a[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "durum": {
                s.sendMessage(ChatColor.GOLD + "--- Kalkan ---");
                s.sendMessage(ChatColor.YELLOW + "Saldiri modu: " + (saldiriModuAcik()
                        ? ChatColor.RED + (elleSaldiri ? "ACIK (elle)" : "ACIK (" + Math.max(0, (saldiriBitis - System.currentTimeMillis()) / 1000) + " sn)")
                        : ChatColor.GREEN + "kapali"));
                s.sendMessage(ChatColor.YELLOW + "Engellenen deneme: " + ChatColor.WHITE + engellenenSayac.get());
                s.sendMessage(ChatColor.YELLOW + "Su an engelli IP: " + ChatColor.WHITE + engelli.size());
                s.sendMessage(ChatColor.YELLOW + "Bilinen IP: " + ChatColor.WHITE + bilinen.size());
                return true;
            }
            case "saldiri": {
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Kullanim: /kalkan saldiri <ac|kapat>");
                    return true;
                }
                boolean ac = a[1].equalsIgnoreCase("ac");
                elleSaldiri = ac;
                if (!ac) saldiriBitis = 0L;
                s.sendMessage(ChatColor.YELLOW + "Saldiri modu " + (ac ? "elle acildi." : "kapatildi."));
                return true;
            }
            case "engel": {
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Kullanim: /kalkan engel <ip>");
                    return true;
                }
                engelli.put(a[1], System.currentTimeMillis() + engelMs);
                s.sendMessage(ChatColor.YELLOW + a[1] + " " + (engelMs / 60000) + " dk engellendi.");
                return true;
            }
            case "ac": {
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Kullanim: /kalkan ac <ip>");
                    return true;
                }
                engelli.remove(a[1]);
                denemeler.remove(a[1]);
                s.sendMessage(ChatColor.YELLOW + a[1] + " engeli kaldirildi.");
                return true;
            }
            case "guvenli": {
                if (a.length < 2) {
                    s.sendMessage(ChatColor.RED + "Kullanim: /kalkan guvenli <ip>");
                    return true;
                }
                List<String> liste = new ArrayList<>(getConfig().getStringList("guvenilir-ipler"));
                if (!liste.contains(a[1])) liste.add(a[1]);
                getConfig().set("guvenilir-ipler", liste);
                saveConfig();
                guvenilir.add(a[1]);
                s.sendMessage(ChatColor.YELLOW + a[1] + " guvenilir listeye eklendi.");
                return true;
            }
            case "reload": {
                ayarlariYukle();
                s.sendMessage(ChatColor.GREEN + "Kalkan ayarlari yenilendi.");
                return true;
            }
            default:
                s.sendMessage(ChatColor.RED + "Kullanim: /kalkan <durum|saldiri|engel|ac|guvenli|reload>");
                return true;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> sonuc = new ArrayList<>();
        if (!s.hasPermission("kalkan.admin")) return sonuc;
        if (a.length == 1) {
            for (String x : new String[]{"durum", "saldiri", "engel", "ac", "guvenli", "reload"}) {
                if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) sonuc.add(x);
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("saldiri")) {
            sonuc.add("ac");
            sonuc.add("kapat");
        } else if (a.length == 2 && a[0].equalsIgnoreCase("ac")) {
            sonuc.addAll(new HashSet<>(engelli.keySet()));
        }
        return sonuc;
    }
}
