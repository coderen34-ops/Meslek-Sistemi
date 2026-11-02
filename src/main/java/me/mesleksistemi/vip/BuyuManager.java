package me.mesleksistemi.vip;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import me.mesleksistemi.MeslekSistemi;

/**
 * Mağazadan satılan süreli büyüler (potion efektleri). Teslim (konsol/RCON):
 *   buyuver &lt;oyuncu&gt; &lt;gece|nefes|hiz|ziplama|doygunluk|haste|sans&gt; &lt;dakika&gt; [seviye]
 * Süre yalnızca oyuncu ÇEVRİMİÇİYKEN akar. Ölme / süt içme / çıkıp girme efekti silmez; her tick yeniden uygulanır.
 */
public class BuyuManager implements CommandExecutor {

    private static final class Tur {
        final String ad; final PotionEffectType tip; final int maxSeviye;
        Tur(String ad, PotionEffectType tip, int maxSeviye) { this.ad = ad; this.tip = tip; this.maxSeviye = maxSeviye; }
    }

    private static final long TICK_MS = 2000L;
    private final Map<String, Tur> turler = new LinkedHashMap<>();
    private final MeslekSistemi plugin;
    private final File dosya;
    private final YamlConfiguration veri;
    private final BukkitTask gorev;
    private int kaydetSayac = 0;

    public BuyuManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        turler.put("gece", new Tur("Gece Görüşü", PotionEffectType.NIGHT_VISION, 1));
        turler.put("nefes", new Tur("Su Altı Nefesi", PotionEffectType.WATER_BREATHING, 1));
        turler.put("hiz", new Tur("Hız", PotionEffectType.SPEED, 2));
        turler.put("ziplama", new Tur("Zıplama", PotionEffectType.JUMP_BOOST, 2));
        turler.put("doygunluk", new Tur("Doygunluk", PotionEffectType.SATURATION, 1));
        turler.put("haste", new Tur("Haste", PotionEffectType.HASTE, 3));
        turler.put("sans", new Tur("Şans", PotionEffectType.LUCK, 1));
        this.dosya = new File(plugin.getDataFolder(), "buyu.yml");
        this.veri = YamlConfiguration.loadConfiguration(dosya);
        this.gorev = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    public void kapat() {
        if (gorev != null) gorev.cancel();
        kaydet();
    }

    private void kaydet() {
        try { veri.save(dosya); } catch (Exception e) { plugin.getLogger().warning("buyu.yml kaydedilemedi: " + e); }
    }

    private void tick() {
        boolean degisti = false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ConfigurationSection s = veri.getConfigurationSection("buyu." + p.getUniqueId());
            if (s == null) continue;
            for (String anahtar : new ArrayList<>(s.getKeys(false))) {
                Tur t = turler.get(anahtar);
                String yol = "buyu." + p.getUniqueId() + "." + anahtar;
                long kalan = veri.getLong(yol + ".kalan", 0L);
                if (t == null || kalan <= 0) { veri.set(yol, null); degisti = true; continue; }
                int amp = Math.max(0, veri.getInt(yol + ".seviye", 1) - 1);
                p.addPotionEffect(new PotionEffect(t.tip, 100, amp, true, false, true));
                kalan -= TICK_MS;
                if (kalan <= 0) {
                    veri.set(yol, null);
                    p.sendMessage(ChatColor.GRAY + "✦ " + t.ad + " büyün bitti.");
                } else {
                    veri.set(yol + ".kalan", kalan);
                }
                degisti = true;
            }
            ConfigurationSection kontrol = veri.getConfigurationSection("buyu." + p.getUniqueId());
            if (kontrol != null && kontrol.getKeys(false).isEmpty()) veri.set("buyu." + p.getUniqueId(), null);
        }
        if (degisti && ++kaydetSayac >= 15) { kaydetSayac = 0; kaydet(); }
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equalsIgnoreCase("buyu")) return durum(s);
        if (!(s instanceof org.bukkit.command.ConsoleCommandSender) && !s.hasPermission("meslek.admin")) {
            s.sendMessage(ChatColor.RED + "Yetkin yok.");
            return true;
        }
        if (a.length < 3) {
            s.sendMessage(ChatColor.YELLOW + "Kullanım: /buyuver <oyuncu> <" + String.join("|", turler.keySet()) + "> <dakika> [seviye]");
            return true;
        }
        Tur t = turler.get(a[1].toLowerCase(Locale.ROOT));
        if (t == null) { s.sendMessage(ChatColor.RED + "Bilinmeyen büyü: " + a[1]); return true; }
        int dk, sv = 1;
        try {
            dk = Integer.parseInt(a[2]);
            if (a.length > 3) sv = Integer.parseInt(a[3]);
        } catch (NumberFormatException e) { s.sendMessage(ChatColor.RED + "Dakika/seviye sayı olmalı."); return true; }
        if (dk < 1 || dk > 1440) { s.sendMessage(ChatColor.RED + "Dakika 1-1440 arası olmalı."); return true; }
        if (sv < 1 || sv > t.maxSeviye) { s.sendMessage(ChatColor.RED + t.ad + " için seviye 1-" + t.maxSeviye + " olabilir."); return true; }
        OfflinePlayer h = Bukkit.getPlayerExact(a[0]);
        if (h == null) h = Bukkit.getOfflinePlayer(a[0]);
        if (h == null || (!h.isOnline() && !h.hasPlayedBefore())) { s.sendMessage(ChatColor.RED + "Oyuncu bulunamadı: " + a[0]); return true; }
        String yol = "buyu." + h.getUniqueId() + "." + a[1].toLowerCase(Locale.ROOT);
        long ek = dk * 60_000L;
        long mevcut = veri.getLong(yol + ".kalan", 0L);
        int mevcutSv = veri.getInt(yol + ".seviye", 1);
        long yeni;
        if (mevcut > 0 && mevcutSv == sv) yeni = mevcut + ek;   // aynı seviye: süre uzar
        else yeni = ek;                                         // farklı seviye: yenisi geçerli olur
        veri.set(yol + ".kalan", yeni);
        veri.set(yol + ".seviye", sv);
        kaydet();
        String seviyeYazi = t.maxSeviye > 1 ? " " + sv : "";
        Player p = h.getPlayer();
        if (p != null) p.sendMessage(ChatColor.LIGHT_PURPLE + "✦ " + t.ad + seviyeYazi + " büyüsü aktif: " + (yeni / 60_000L) + " dk (çevrimiçiyken akar). /buyu ile bak.");
        s.sendMessage(ChatColor.GREEN + h.getName() + " için " + t.ad + seviyeYazi + " " + dk + " dk verildi.");
        return true;
    }

    private boolean durum(CommandSender s) {
        if (!(s instanceof Player)) return true;
        Player p = (Player) s;
        ConfigurationSection sec = veri.getConfigurationSection("buyu." + p.getUniqueId());
        if (sec == null || sec.getKeys(false).isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Aktif büyün yok. Mağazadan süreli büyü alabilirsin.");
            return true;
        }
        p.sendMessage(ChatColor.LIGHT_PURPLE + "✦ Aktif büyülerin:");
        for (String k : sec.getKeys(false)) {
            Tur t = turler.get(k);
            if (t == null) continue;
            long kalan = veri.getLong("buyu." + p.getUniqueId() + "." + k + ".kalan", 0L);
            int sv = veri.getInt("buyu." + p.getUniqueId() + "." + k + ".seviye", 1);
            p.sendMessage(ChatColor.GRAY + "• " + ChatColor.WHITE + t.ad + (t.maxSeviye > 1 ? " " + sv : "")
                    + ChatColor.GRAY + " — " + ChatColor.WHITE + Math.max(1, kalan / 60_000L) + " dk" + ChatColor.GRAY + " kaldı (çevrimiçiyken akar)");
        }
        return true;
    }
}
