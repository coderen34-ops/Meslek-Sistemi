package me.mesleksistemi.olay;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.Ageable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import me.mesleksistemi.MeslekSistemi;

/**
 * Dünya olayları: Fırtına (~15 dk), Kuraklık (24 sa), Kıtlık (24-48 sa). Gerçek zamanlıdır.
 * Rastgele başlar (haftada ortalama ~2,5 olay), gazeteye düşer, sunucu yeniden başlasa da sürer.
 *  - Fırtına: gökyüzüne açık oyuncular yavaşlar, uzaktan şimşek efekti; çatı altı güvenli.
 *  - Kuraklık: ekinler yavaş büyür, susuzluk daha hızlı artar.
 *  - Kıtlık: toptancıda gıda (çiftçi/balıkçı ürünleri) fiyatı yükselir.
 */
public class DunyaOlayManager implements Listener, CommandExecutor {

    public enum Tur {
        FIRTINA("Fırtına", 15L * 60_000L, 15L * 60_000L),
        KURAKLIK("Kuraklık", 24L * 3_600_000L, 24L * 3_600_000L),
        KITLIK("Kıtlık", 24L * 3_600_000L, 48L * 3_600_000L);
        public final String ad; final long min, max;
        Tur(String ad, long min, long max) { this.ad = ad; this.min = min; this.max = max; }
        static Tur bul(String s) {
            for (Tur t : values()) if (t.name().equalsIgnoreCase(s) || t.ad.equalsIgnoreCase(s)) return t;
            if (s.equalsIgnoreCase("firtina")) return FIRTINA;
            if (s.equalsIgnoreCase("kuraklik")) return KURAKLIK;
            if (s.equalsIgnoreCase("kitlik")) return KITLIK;
            return null;
        }
    }

    private final MeslekSistemi plugin;
    private final File dosya;
    private final Random rnd = new Random();
    private final Map<Tur, Long> bitis = new HashMap<>();

    public DunyaOlayManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "olaylar.yml");
        yukle();
        // 5 dakikada bir rastgele olay şansı + bitiş kontrolü
        Bukkit.getScheduler().runTaskTimer(plugin, this::rastgele, 20L * 300, 20L * 300);
        // Her 5 sn: fırtına etkisi
        Bukkit.getScheduler().runTaskTimer(plugin, this::firtinaEtkisi, 20L * 5, 20L * 5);
        Bukkit.getScheduler().runTaskLater(plugin, this::etkileriUygula, 40L);
    }

    // ------------------------------------------------------------------ durum
    public boolean aktif(Tur t) {
        Long b = bitis.get(t);
        return b != null && System.currentTimeMillis() < b;
    }

    /** Toptancıda gıda ürünlerinin fiyat çarpanı (kıtlıkta yükselir). */
    public double gidaCarpani() { return aktif(Tur.KITLIK) ? plugin.getConfig().getDouble("olay.kitlik-gida-carpani", 1.5) : 1.0; }

    private void yukle() {
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        for (Tur t : Tur.values()) {
            long b = y.getLong(t.name(), 0L);
            if (b > System.currentTimeMillis()) bitis.put(t, b);
        }
    }

    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<Tur, Long> e : bitis.entrySet()) y.set(e.getKey().name(), e.getValue());
        try { dosya.getParentFile().mkdirs(); y.save(dosya); } catch (Exception e) { plugin.getLogger().warning("[Olay] olaylar.yml yazılamadı: " + e.getMessage()); }
    }

    public void kapat() { kaydet(); }

    // ------------------------------------------------------------------ başlat / bitir
    public boolean baslat(Tur t, long sureMs) {
        if (aktif(t)) return false;
        bitis.put(t, System.currentTimeMillis() + sureMs);
        kaydet();
        etkileriUygula();
        String sure = MeslekSistemi.sureYaz(sureMs);
        String metin;
        switch (t) {
            case FIRTINA: metin = "Şiddetli bir fırtına yaklaşıyor! Açıkta kalanlar yavaşlayacak, çatı altına sığının. (~" + sure + ")"; break;
            case KURAKLIK: metin = "Kuraklık başladı! Ekinler yavaş büyüyor, insanlar daha çabuk susuyor. (~" + sure + ")"; break;
            default: metin = "Kıtlık ilan edildi! Gıda fiyatları yükseldi. (~" + sure + ")"; break;
        }
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Dünya] " + ChatColor.RED + t.ad + ": " + metin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendTitle(ChatColor.RED + t.ad, ChatColor.GRAY + "Sunucu genelinde etkili", 10, 60, 20);
            p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.7f, 0.9f);
        }
        if (plugin.haberManager != null) plugin.haberManager.yaz("DÜNYA", t.ad + " başladı", metin);
        return true;
    }

    public boolean bitir(Tur t) {
        if (!aktif(t)) return false;
        bitis.remove(t);
        kaydet();
        etkileriUygula();
        Bukkit.broadcastMessage(ChatColor.DARK_GREEN + "[Dünya] " + ChatColor.GREEN + t.ad + " sona erdi.");
        if (plugin.haberManager != null) plugin.haberManager.yaz("DÜNYA", t.ad + " sona erdi", t.ad + " olayı sona erdi, hayat normale dönüyor.");
        return true;
    }

    private void etkileriUygula() {
        // Kuraklık susuzluğu hızlandırır
        if (plugin.susuzlukManager != null) plugin.susuzlukManager.setAzalmaCarpani(aktif(Tur.KURAKLIK) ? 1.5 : 1.0);
        // Fırtına: ana dünyada hava
        World w = anaDunya();
        if (w != null) {
            if (aktif(Tur.FIRTINA)) {
                w.setStorm(true);
                w.setThundering(true);
                int tick = (int) Math.max(1200L, (bitis.get(Tur.FIRTINA) - System.currentTimeMillis()) / 50L);
                w.setWeatherDuration(tick);
                w.setThunderDuration(tick);
            } else if (w.hasStorm()) {
                // Olay bitti: kalan yağmuru da bitir ki "fırtına" bitmiş gibi hissettirsin
                w.setStorm(false);
                w.setThundering(false);
            }
        }
    }

    private World anaDunya() {
        for (World w : Bukkit.getWorlds()) if (w.getEnvironment() == World.Environment.NORMAL) return w;
        return null;
    }

    private void rastgele() {
        long simdi = System.currentTimeMillis();
        boolean degisti = false;
        for (Tur t : Tur.values()) {
            Long b = bitis.get(t);
            if (b != null && simdi >= b) { bitir(t); degisti = true; }
        }
        if (!plugin.getConfig().getBoolean("olay.aktif", true)) return;
        if (Bukkit.getOnlinePlayers().isEmpty()) return;
        double haftalik = Math.max(0.0, plugin.getConfig().getDouble("olay.haftalik-ortalama", 2.5));
        double p = haftalik / (7.0 * 24.0 * 12.0); // 5 dakikalık kontrol sayısı
        if (rnd.nextDouble() >= p) return;
        List<Tur> adaylar = new ArrayList<>();
        for (Tur t : Tur.values()) if (!aktif(t)) adaylar.add(t);
        if (adaylar.isEmpty()) return;
        Tur secim = adaylar.get(rnd.nextInt(adaylar.size()));
        long sure = secim.min + (secim.max > secim.min ? (long) (rnd.nextDouble() * (secim.max - secim.min)) : 0L);
        baslat(secim, sure);
    }

    // ------------------------------------------------------------------ etkiler
    private void firtinaEtkisi() {
        if (!aktif(Tur.FIRTINA)) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().getEnvironment() != World.Environment.NORMAL) continue;
            Location l = p.getLocation();
            boolean acik = p.getWorld().getHighestBlockYAt(l) <= l.getBlockY() + 1;
            if (!acik) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 140, 0, true, false, true));
            if (rnd.nextInt(8) == 0) {
                double aci = rnd.nextDouble() * Math.PI * 2;
                double uzak = 10 + rnd.nextInt(15);
                Location hedef = l.clone().add(Math.cos(aci) * uzak, 0, Math.sin(aci) * uzak);
                hedef.setY(p.getWorld().getHighestBlockYAt(hedef) + 1);
                p.getWorld().strikeLightningEffect(hedef);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBuyume(BlockGrowEvent e) {
        if (!aktif(Tur.KURAKLIK)) return;
        if (e.getNewState().getBlockData() instanceof Ageable && rnd.nextInt(100) < 50) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ komut
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("durum")) {
            sender.sendMessage(ChatColor.GOLD + "Dünya olayları:");
            for (Tur t : Tur.values()) {
                Long b = bitis.get(t);
                sender.sendMessage(ChatColor.GRAY + " - " + t.ad + ": " + (aktif(t) ? ChatColor.RED + "AKTİF, kalan " + MeslekSistemi.sureYaz(b - System.currentTimeMillis()) : ChatColor.GREEN + "yok"));
            }
            return true;
        }
        if (!sender.hasPermission("meslek.admin")) { sender.sendMessage(ChatColor.RED + "Yetkin yok."); return true; }
        if (args.length >= 2) {
            Tur t = Tur.bul(args[1]);
            if (t == null) { sender.sendMessage(ChatColor.RED + "Tür: firtina, kuraklik, kitlik"); return true; }
            if (args[0].equalsIgnoreCase("baslat")) {
                long sure = t.min;
                if (args.length >= 3) {
                    try { sure = (long) (Double.parseDouble(args[2]) * 60_000L); } catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "Süre dakika olarak sayı olmalı."); return true; }
                }
                sender.sendMessage(baslat(t, sure) ? ChatColor.GREEN + t.ad + " başlatıldı." : ChatColor.RED + "Zaten aktif.");
                return true;
            }
            if (args[0].equalsIgnoreCase("bitir")) {
                sender.sendMessage(bitir(t) ? ChatColor.GREEN + t.ad + " bitirildi." : ChatColor.RED + "Aktif değil.");
                return true;
            }
        }
        sender.sendMessage(ChatColor.GOLD + "/olay [durum | baslat <firtina|kuraklik|kitlik> [dakika] | bitir <tur>]");
        return true;
    }
}
