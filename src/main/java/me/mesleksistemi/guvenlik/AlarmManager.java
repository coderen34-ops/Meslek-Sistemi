package me.mesleksistemi.guvenlik;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import me.mesleksistemi.MeslekSistemi;

/**
 * Alarm / log sistemi (SADECE tespit ve bildirim; hiçbir limit uygulamaz, hiçbir işlemi engellemez).
 *  - Sohbette RMT anahtar kelimeleri (papara, iban, gerçek para...) -> alarm
 *  - Büyük tek seferlik transfer -> alarm
 *  - Aynı çiftin kısa sürede tekrarlı transferleri -> alarm
 *  - Bir alıcıya kısa sürede çok sayıda farklı göndericiden para -> alarm
 * Alarm: loglar/alarm-YYYY-AA.log + çevrimiçi yöneticilere (meslek.admin) mesaj + isteğe bağlı Discord webhook.
 * Ayarlar config.yml "alarm." altında (webhook boş = kapalı).
 */
public class AlarmManager implements Listener {

    private static final long PENCERE_MS = 60L * 60_000L;
    private static final long TEKRAR_BEKLEME_MS = 10L * 60_000L;

    private final MeslekSistemi plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, ArrayDeque<Long>> ciftler = new HashMap<>();
    private final Map<UUID, ArrayDeque<Object[]>> alicilar = new HashMap<>(); // {zaman, gonderenUuid}
    private final Map<String, Long> sonAlarm = new HashMap<>();

    public AlarmManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        plugin.getConfig().addDefault("alarm.buyuk-transfer", 100000.0);
        plugin.getConfig().addDefault("alarm.tekrar-transfer-adedi", 5);
        plugin.getConfig().addDefault("alarm.farkli-gonderici-adedi", 4);
        plugin.getConfig().addDefault("alarm.discord-webhook", "");
        plugin.getConfig().addDefault("alarm.rmt-anahtar-kelimeler", List.of(
                "papara", "iban", "havale", "rmt", "gerçek para", "gercek para", "tl karşılığı", "tl karsiligi",
                "tl'ye sat", "tl ye sat", "hesap satılık", "hesap satilik", "bitcoin", "usdt", "ininal"));
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
    }

    // ------------------------------------------------------------------ sohbet izleme
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSohbet(AsyncPlayerChatEvent event) {
        String metin = event.getMessage().toLowerCase(new Locale("tr", "TR"));
        String metinEn = event.getMessage().toLowerCase(Locale.ROOT);
        for (String k : plugin.getConfig().getStringList("alarm.rmt-anahtar-kelimeler")) {
            String kk = k.toLowerCase(new Locale("tr", "TR"));
            if (!kk.isBlank() && (metin.contains(kk) || metinEn.contains(kk.toLowerCase(Locale.ROOT)))) {
                alarm("rmt-sohbet:" + event.getPlayer().getUniqueId(), "RMT şüphesi (sohbet)",
                        event.getPlayer().getName() + " şunu yazdı: \"" + event.getMessage() + "\" (anahtar: " + k + ")");
                return;
            }
        }
    }

    // ------------------------------------------------------------------ transfer izleme
    public void transfer(Player gonderen, UUID alici, String aliciAd, double miktar) {
        long simdi = System.currentTimeMillis();
        double buyuk = plugin.getConfig().getDouble("alarm.buyuk-transfer", 100000.0);
        if (buyuk > 0 && miktar >= buyuk) {
            alarm("buyuk:" + gonderen.getUniqueId(), "Büyük transfer",
                    gonderen.getName() + " -> " + aliciAd + " $" + String.format(Locale.US, "%.2f", miktar));
        }

        String cift = gonderen.getUniqueId() + ">" + alici;
        ArrayDeque<Long> q = ciftler.computeIfAbsent(cift, x -> new ArrayDeque<>());
        q.addLast(simdi);
        while (!q.isEmpty() && simdi - q.peekFirst() > PENCERE_MS) q.pollFirst();
        int tekrar = plugin.getConfig().getInt("alarm.tekrar-transfer-adedi", 5);
        if (tekrar > 0 && q.size() >= tekrar) {
            alarm("tekrar:" + cift, "Tekrarlı transfer",
                    gonderen.getName() + " -> " + aliciAd + " son 1 saatte " + q.size() + " kez para gönderdi");
        }

        ArrayDeque<Object[]> a = alicilar.computeIfAbsent(alici, x -> new ArrayDeque<>());
        a.addLast(new Object[] {simdi, gonderen.getUniqueId()});
        while (!a.isEmpty() && simdi - (Long) a.peekFirst()[0] > PENCERE_MS) a.pollFirst();
        Set<UUID> farkli = new HashSet<>();
        for (Object[] o : a) farkli.add((UUID) o[1]);
        int fa = plugin.getConfig().getInt("alarm.farkli-gonderici-adedi", 4);
        if (fa > 0 && farkli.size() >= fa) {
            alarm("toplanma:" + alici, "Para toplanıyor",
                    aliciAd + " son 1 saatte " + farkli.size() + " farklı oyuncudan para aldı");
        }
        plugin.ekonomiLog("para-transfer", gonderen.getName(), "-> " + aliciAd + " $" + String.format(Locale.US, "%.2f", miktar));
    }

    // ------------------------------------------------------------------ alarm
    public synchronized void alarm(String anahtar, String baslik, String detay) {
        long simdi = System.currentTimeMillis();
        Long son = sonAlarm.get(anahtar);
        if (son != null && simdi - son < TEKRAR_BEKLEME_MS) return;
        sonAlarm.put(anahtar, simdi);

        final String zaman = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        final String satir = "[" + zaman + "] [" + baslik + "] " + detay;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                File f = new File(plugin.getDataFolder(), "loglar/alarm-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM")) + ".log");
                Files.createDirectories(f.getParentFile().toPath());
                Files.writeString(f.toPath(), satir + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Alarm log'u yazılamadı", e);
            }
            webhookGonder("**" + baslik + "**\n" + detay);
        });

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("meslek.admin")) {
                    p.sendMessage(ChatColor.DARK_RED + "[ALARM] " + ChatColor.RED + baslik + ChatColor.GRAY + ": " + ChatColor.WHITE + detay);
                }
            }
            plugin.getLogger().warning("[ALARM] " + baslik + ": " + detay);
        });
    }

    private void webhookGonder(String icerik) {
        String url = plugin.getConfig().getString("alarm.discord-webhook", "");
        if (url == null || url.isBlank() || !url.startsWith("https://")) return;
        try {
            String json = "{\"content\":\"" + icerik.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "") + "\"}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Discord webhook gönderilemedi: " + e.getMessage());
        }
    }
}
