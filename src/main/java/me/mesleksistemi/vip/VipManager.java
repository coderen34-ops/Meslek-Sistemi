package me.mesleksistemi.vip;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import me.mesleksistemi.MeslekSistemi;

/**
 * VIP paketleri (Bronz / Gümüş / Altın, aylık) ve süreli oyun XP boost'u.
 * Mağazadan (Anksoft) satın alınca konsol komutuyla teslim edilir:
 *   vipver &lt;oyuncu&gt; &lt;bronz|gumus|altin&gt; [gun]      xpboostver &lt;oyuncu&gt; &lt;gun&gt; [yuzde]
 * Oyun gücü/ekonomi satılmaz; kolaylık ve görünüm verilir.
 */
public class VipManager implements Listener, CommandExecutor {

    private static final String[] PAKETLER = {"bronz", "gumus", "altin"};

    private final MeslekSistemi plugin;
    private final File dosya;
    private YamlConfiguration veri;
    private BukkitTask zamanlayici;
    private BukkitTask efektGorevi;
    /** Efekti kapatan oyuncular (bellek). */
    private final java.util.Set<UUID> efektKapali = new java.util.HashSet<>();
    /** Altın VIP meşale ışığı: açık olanlar ve oyuncuya gönderilen son (sahte) ışık bloğu. */
    private final java.util.Set<UUID> isikAcik = new java.util.HashSet<>();
    private final Map<UUID, org.bukkit.Location> sonIsik = new HashMap<>();
    private BukkitTask isikGorevi;

    public VipManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "vip.yml");
        this.veri = YamlConfiguration.loadConfiguration(dosya);
        this.zamanlayici = Bukkit.getScheduler().runTaskTimer(plugin, this::sureKontrol, 20L * 30, 20L * 60 * 5);
        this.efektGorevi = Bukkit.getScheduler().runTaskTimer(plugin, this::efektTick, 40L, 20L);
        this.isikGorevi = Bukkit.getScheduler().runTaskTimer(plugin, this::isikTick, 20L, 3L);
    }

    public void kapat() {
        if (zamanlayici != null) zamanlayici.cancel();
        if (efektGorevi != null) efektGorevi.cancel();
        if (isikGorevi != null) isikGorevi.cancel();
        for (Player pl : Bukkit.getOnlinePlayers()) isikTemizle(pl);
        kaydet();
    }

    // ---------------------------------------------------------------- ayarlar

    private static int seviye(String paket) {
        if (paket == null) return 0;
        switch (paket) {
            case "bronz": return 1;
            case "gumus": return 2;
            case "altin": return 3;
            default: return 0;
        }
    }

    private static String ad(String paket) {
        switch (paket) {
            case "bronz": return "Bronz";
            case "gumus": return "Gümüş";
            case "altin": return "Altın";
            default: return paket;
        }
    }

    private static TextColor renk(String paket) {
        switch (paket) {
            case "bronz": return TextColor.color(0xCD7F32);
            case "gumus": return TextColor.color(0xC0C0C0);
            case "altin": return TextColor.color(0xFFD700);
            default: return TextColor.color(0xFFFFFF);
        }
    }

    private int claimBonus(String paket) {
        return plugin.getConfig().getInt("vip.claim-blok." + paket, paket.equals("altin") ? 400 : paket.equals("gumus") ? 250 : 100);
    }

    private int boostVarsayilanYuzde() { return plugin.getConfig().getInt("vip.xp-boost-yuzde", 25); }

    // ----------------------------------------------------------------- veri

    private void kaydet() {
        try { veri.save(dosya); } catch (Exception e) { plugin.getLogger().warning("vip.yml kaydedilemedi: " + e); }
    }

    /** Aktif paket adı (süresi dolmamış) ya da null. */
    public String paket(UUID id) {
        String p = veri.getString("vip." + id + ".paket");
        long bitis = veri.getLong("vip." + id + ".bitis", 0L);
        if (p == null || bitis <= System.currentTimeMillis()) return null;
        return p;
    }

    public int paketSeviyesi(UUID id) { return seviye(paket(id)); }

    private long kalanMs(UUID id) { return veri.getLong("vip." + id + ".bitis", 0L) - System.currentTimeMillis(); }

    private int boostYuzde(UUID id) {
        long bitis = veri.getLong("boost." + id + ".bitis", 0L);
        if (bitis <= System.currentTimeMillis()) return 0;
        return veri.getInt("boost." + id + ".yuzde", boostVarsayilanYuzde());
    }

    /** Sohbette isimden önce gösterilecek etiket (VIP değilse null). */
    public Component etiket(UUID id) {
        String p = paket(id);
        if (p == null) return null;
        int c1, c2;
        String sembol, yazi;
        switch (p) {
            case "altin": c1 = 0xFFE259; c2 = 0xFF9A3C; sembol = "\u2605 "; yazi = "ALTIN"; break;
            case "gumus": c1 = 0xF2F5F8; c2 = 0x8E9BAA; sembol = "\u2726 "; yazi = "GÜMÜŞ"; break;
            default: c1 = 0xE8A15B; c2 = 0xA85A25; sembol = "\u2726 "; yazi = "BRONZ"; break;
        }
        String metin = sembol + yazi;
        Component r = Component.text("[", TextColor.color(0x555555));
        int n = metin.length();
        for (int i = 0; i < n; i++) {
            double t = n <= 1 ? 0 : (double) i / (n - 1);
            int rr = (int) Math.round(((c1 >> 16) & 255) * (1 - t) + ((c2 >> 16) & 255) * t);
            int gg = (int) Math.round(((c1 >> 8) & 255) * (1 - t) + ((c2 >> 8) & 255) * t);
            int bb = (int) Math.round((c1 & 255) * (1 - t) + (c2 & 255) * t);
            r = r.append(Component.text(String.valueOf(metin.charAt(i)), TextColor.color(rr, gg, bb))
                    .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true));
        }
        return r.append(Component.text("] ", TextColor.color(0x555555)));
    }

    // --------------------------------------------------------------- teslimat

    private void vipVer(OfflinePlayer hedef, String paket, int gun) {
        UUID id = hedef.getUniqueId();
        String eski = paket(id);
        long simdi = System.currentTimeMillis();
        long sure = gun * 86_400_000L;
        long bitis;
        if (eski != null && eski.equals(paket)) {
            bitis = veri.getLong("vip." + id + ".bitis") + sure; // aynı paket: süre uzar
        } else {
            bitis = simdi + sure; // farklı paket: yenisi başlar
            if (eski != null) ayricalikGeriAl(id, hedef.getName(), eski);
        }
        veri.set("vip." + id + ".paket", paket);
        veri.set("vip." + id + ".bitis", bitis);
        veri.set("vip." + id + ".isim", hedef.getName());
        if (eski == null || !eski.equals(paket)) ayricalikUygula(id, hedef.getName(), paket);
        kaydet();

        Player p = hedef.getPlayer();
        if (p != null) {
            p.sendMessage(ChatColor.GOLD + "★ " + ad(paket) + " VIP aktif! " + ChatColor.YELLOW + "Süre: " + gun + " gün eklendi. /vip ile ayrıntılara bak.");
            p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
        Bukkit.broadcastMessage(ChatColor.GOLD + "★ " + ChatColor.YELLOW + hedef.getName() + ChatColor.GOLD + " " + ad(paket) + " VIP oldu! Destekleri için teşekkürler.");
    }

    private void ayricalikUygula(UUID id, String isim, String paket) {
        int seviye = seviye(paket);
        // Claim bloğu: tek seferlik ve kalıcı. Yenilemede tekrar verilmez; üst pakete geçişte yalnızca fark verilir.
        int hedef = claimBonus(paket);
        int zatenVerilen = veri.getInt("claimlar." + id, 0);
        int ek = Math.max(0, hedef - zatenVerilen);
        if (ek > 0) {
            try {
                var pd = me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.getPlayerData(id);
                pd.setBonusClaimBlocks(pd.getBonusClaimBlocks() + ek);
                veri.set("claimlar." + id, hedef);
                Player oy = Bukkit.getPlayer(id);
                if (oy != null) oy.sendMessage(ChatColor.GOLD + "+" + ek + " claim bloğu hesabına eklendi (kalıcı, tek seferlik).");
            } catch (Throwable t) {
                plugin.getLogger().warning("VIP claim bloğu verilemedi (" + isim + "): " + t);
            }
        }
        // Gümüş+: /hat
        if (seviye >= 2) komut("lp user " + isim + " permission set essentials.hat true");
    }

    private void ayricalikGeriAl(UUID id, String isim, String paket) {
        // Claim blokları kalıcıdır, geri alınmaz.
        if (seviye(paket) >= 2) komut("lp user " + isim + " permission unset essentials.hat");
    }

    private void komut(String c) {
        try { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c); } catch (Throwable ignored) { }
    }

    private void vipBitir(UUID id, String bildirimIsmi) {
        String p = veri.getString("vip." + id + ".paket");
        if (p != null) ayricalikGeriAl(id, bildirimIsmi == null ? veri.getString("vip." + id + ".isim", "") : bildirimIsmi, p);
        veri.set("vip." + id, null);
        kaydet();
    }

    private void sureKontrol() {
        ConfigurationSection s = veri.getConfigurationSection("vip");
        if (s == null) return;
        long simdi = System.currentTimeMillis();
        for (String k : new ArrayList<>(s.getKeys(false))) {
            long bitis = veri.getLong("vip." + k + ".bitis", 0L);
            if (bitis > simdi) continue;
            UUID id;
            try { id = UUID.fromString(k); } catch (Exception e) { veri.set("vip." + k, null); continue; }
            String isim = veri.getString("vip." + k + ".isim", "");
            vipBitir(id, isim);
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(ChatColor.RED + "VIP süren bitti. Yenilemek için mağazaya bakabilirsin.");
        }
    }

    // ------------------------------------------------------------- olaylar

    /** Oyun XP boost'u. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onXp(PlayerExpChangeEvent e) {
        int amount = e.getAmount();
        if (amount <= 0) return;
        int y = boostYuzde(e.getPlayer().getUniqueId());
        if (y <= 0) return;
        e.setAmount((int) Math.ceil(amount * (1.0 + y / 100.0)));
    }

    /** Gümüş+: sunucu doluyken de girebilir. */
    @EventHandler(priority = EventPriority.HIGH)
    @SuppressWarnings("deprecation")
    public void onGiris(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.KICK_FULL) return;
        if (paketSeviyesi(e.getPlayer().getUniqueId()) >= 2) e.allow();
    }

    @EventHandler
    public void onKatil(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (veri.contains("vip." + id)) {
            String pk = paket(id);
            if (pk == null) {
                vipBitir(id, p.getName());
                p.sendMessage(ChatColor.RED + "VIP süren bitti. Yenilemek için mağazaya bakabilirsin.");
            } else {
                veri.set("vip." + id + ".isim", p.getName());
                long gun = kalanMs(id) / 86_400_000L;
                if (gun < 3) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (p.isOnline()) p.sendMessage(ChatColor.GOLD + "★ " + ad(pk) + " VIP süren " + Math.max(0, gun) + " gün içinde bitiyor.");
                    }, 60L);
                }
            }
        }
    }

    /** Gümüş: hafif parçacık, Altın: biraz daha gösterişli. */
    private void efektTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            int sv = paketSeviyesi(p.getUniqueId());
            if (sv < 2 || efektKapali.contains(p.getUniqueId())) continue;
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.isInvisible()) continue;
            var loc = p.getLocation().add(0, 0.15, 0);
            if (sv >= 3) {
                p.getWorld().spawnParticle(Particle.END_ROD, loc, 2, 0.35, 0.05, 0.35, 0.005);
                p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(0, 0.9, 0), 1, 0.4, 0.5, 0.4, 0);
            } else {
                p.getWorld().spawnParticle(Particle.END_ROD, loc, 1, 0.3, 0.05, 0.3, 0.002);
            }
        }
    }

    // ---------------------------------------------------------- meşale ışığı

    /** Oyuncuya gönderilmiş sahte ışık bloğunu gerçek bloğuyla değiştirir. */
    private void isikTemizle(Player p) {
        org.bukkit.Location eski = sonIsik.remove(p.getUniqueId());
        if (eski != null && eski.getWorld() != null && p.isOnline()) {
            p.sendBlockChange(eski, eski.getBlock().getBlockData());
        }
    }

    private void isikTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!isikAcik.contains(id)) { if (sonIsik.containsKey(id)) isikTemizle(p); continue; }
            if (paketSeviyesi(id) < 3 || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                isikAcik.remove(id); isikTemizle(p); continue;
            }
            org.bukkit.block.Block b = p.getEyeLocation().getBlock();
            if (!b.getType().isAir()) b = p.getLocation().getBlock();
            if (!b.getType().isAir()) { continue; }
            org.bukkit.Location konum = b.getLocation();
            org.bukkit.Location eski = sonIsik.get(id);
            if (eski != null && eski.getWorld() == konum.getWorld() && eski.getBlockX() == konum.getBlockX()
                    && eski.getBlockY() == konum.getBlockY() && eski.getBlockZ() == konum.getBlockZ()) continue;
            isikTemizle(p);
            org.bukkit.block.data.type.Light l = (org.bukkit.block.data.type.Light) Bukkit.createBlockData(org.bukkit.Material.LIGHT);
            l.setLevel(14);
            p.sendBlockChange(konum, l);
            sonIsik.put(id, konum);
        }
    }

    private void isikDegistir(Player p) {
        UUID id = p.getUniqueId();
        if (paketSeviyesi(id) < 3) { p.sendMessage(ChatColor.RED + "Meşale ışığı Altın VIP'e özel."); return; }
        if (isikAcik.remove(id)) {
            isikTemizle(p);
            p.sendActionBar(Component.text("Işık: kapalı", net.kyori.adventure.text.format.NamedTextColor.GRAY));
        } else {
            isikAcik.add(id);
            p.sendActionBar(Component.text("Işık: açık", net.kyori.adventure.text.format.NamedTextColor.GOLD));
        }
    }

    @EventHandler
    public void onCik(org.bukkit.event.player.PlayerQuitEvent e) {
        isikAcik.remove(e.getPlayer().getUniqueId());
        sonIsik.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------- komutlar

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String cmd = c.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("vip")) return vipKomut(s, a);
        if (!(s instanceof org.bukkit.command.ConsoleCommandSender) && !s.hasPermission("meslek.admin")) {
            s.sendMessage(ChatColor.RED + "Yetkin yok.");
            return true;
        }
        switch (cmd) {
            case "vipver": return vipVerKomut(s, a);
            case "vipal": return vipAlKomut(s, a);
            case "xpboostver": return boostVerKomut(s, a);
            default: return false;
        }
    }

    private boolean vipKomut(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        UUID id = p.getUniqueId();
        if (a.length > 0 && a[0].equalsIgnoreCase("efekt")) {
            if (paketSeviyesi(id) < 2) { p.sendMessage(ChatColor.RED + "Efekt Gümüş ve Altın VIP'e özel."); return true; }
            if (!efektKapali.remove(id)) efektKapali.add(id);
            p.sendMessage(ChatColor.GOLD + "VIP efekti: " + (efektKapali.contains(id) ? ChatColor.RED + "kapalı" : ChatColor.GREEN + "açık"));
            return true;
        }
        if (a.length > 0 && a[0].equalsIgnoreCase("isik")) { isikDegistir(p); return true; }
        String pk = paket(id);
        p.sendMessage(ChatColor.GOLD + "━━ VIP ━━");
        if (pk == null) {
            p.sendMessage(ChatColor.YELLOW + "VIP paketin yok. Bronz / Gümüş / Altın için mağazaya bak.");
        } else {
            long ms = kalanMs(id);
            p.sendMessage(ChatColor.YELLOW + "Paket: " + ChatColor.WHITE + ad(pk) + ChatColor.YELLOW + "  Kalan: " + ChatColor.WHITE
                    + (ms / 86_400_000L) + " gün " + ((ms / 3_600_000L) % 24) + " saat");
            int sv = seviye(pk);
            p.sendMessage(ChatColor.GRAY + "• Sohbet etiketi, " + claimBonus(pk) + " claim bloğu (tek seferlik, kalıcı)");
            if (sv >= 2) p.sendMessage(ChatColor.GRAY + "• Sunucu doluyken giriş önceliği, /hat, parçacık efekti (/vip efekt)");
            if (sv >= 3) p.sendMessage(ChatColor.GRAY + "• Meşale ışığı: /vip isik (client modla ş tuşu), altın efekt, nikah paketi (yakında)");
        }
        int y = boostYuzde(id);
        if (y > 0) {
            long ms = veri.getLong("boost." + id + ".bitis") - System.currentTimeMillis();
            p.sendMessage(ChatColor.AQUA + "XP boost: +%" + y + ChatColor.GRAY + " (" + (ms / 86_400_000L) + " gün " + ((ms / 3_600_000L) % 24) + " saat kaldı)");
        }
        return true;
    }

    private OfflinePlayer oyuncu(String ad) {
        Player on = Bukkit.getPlayerExact(ad);
        if (on != null) return on;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(ad);
        if (off != null) return off;
        // Sunucu offline modda: UUID addan türetilir, oyuncu henüz girmemiş olsa da doğru UUID bulunur.
        if (ad != null && ad.matches("[A-Za-z0-9_]{3,16}")) {
            plugin.getLogger().warning("[VIP] '" + ad + "' sunucuya hiç girmemiş görünüyor; ad üzerinden UUID türetildi.");
            return Bukkit.getOfflinePlayer(ad);
        }
        return null;
    }

    private boolean vipVerKomut(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage(ChatColor.YELLOW + "Kullanım: /vipver <oyuncu> <bronz|gumus|altin> [gün=30]"); return true; }
        OfflinePlayer h = oyuncu(a[0]);
        if (h == null) { s.sendMessage(ChatColor.RED + "Oyuncu bulunamadı (hiç girmemiş): " + a[0]); return true; }
        String pk = a[1].toLowerCase(Locale.ROOT).replace("ü", "u").replace("ı", "i");
        if (seviye(pk) == 0) { s.sendMessage(ChatColor.RED + "Paket: bronz, gumus veya altin."); return true; }
        int gun = 30;
        if (a.length > 2) {
            try { gun = Math.max(1, Math.min(3650, Integer.parseInt(a[2]))); }
            catch (NumberFormatException e) { s.sendMessage(ChatColor.RED + "Gün sayı olmalı."); return true; }
        }
        vipVer(h, pk, gun);
        s.sendMessage(ChatColor.GREEN + h.getName() + " için " + ad(pk) + " VIP verildi (" + gun + " gün).");
        return true;
    }

    private boolean vipAlKomut(CommandSender s, String[] a) {
        if (a.length < 1) { s.sendMessage(ChatColor.YELLOW + "Kullanım: /vipal <oyuncu>"); return true; }
        OfflinePlayer h = oyuncu(a[0]);
        if (h == null) { s.sendMessage(ChatColor.RED + "Oyuncu bulunamadı: " + a[0]); return true; }
        if (!veri.contains("vip." + h.getUniqueId())) { s.sendMessage(ChatColor.YELLOW + "Bu oyuncunun VIP'i yok."); return true; }
        vipBitir(h.getUniqueId(), h.getName());
        s.sendMessage(ChatColor.GREEN + h.getName() + " VIP'i kaldırıldı.");
        return true;
    }

    private boolean boostVerKomut(CommandSender s, String[] a) {
        if (a.length < 2) { s.sendMessage(ChatColor.YELLOW + "Kullanım: /xpboostver <oyuncu> <gün> [yüzde=25]"); return true; }
        OfflinePlayer h = oyuncu(a[0]);
        if (h == null) { s.sendMessage(ChatColor.RED + "Oyuncu bulunamadı: " + a[0]); return true; }
        int gun, yuzde = boostVarsayilanYuzde();
        try {
            gun = Math.max(1, Math.min(365, Integer.parseInt(a[1])));
            if (a.length > 2) yuzde = Math.max(1, Math.min(200, Integer.parseInt(a[2])));
        } catch (NumberFormatException e) { s.sendMessage(ChatColor.RED + "Gün/yüzde sayı olmalı."); return true; }
        UUID id = h.getUniqueId();
        long simdi = System.currentTimeMillis();
        long mevcut = veri.getLong("boost." + id + ".bitis", 0L);
        // Aynı anda tek boost: süreler üst üste binmez, aktif boost varsa süresi uzar (yüzde aynı kalır).
        long bitis;
        if (mevcut > simdi) { bitis = mevcut + gun * 86_400_000L; yuzde = veri.getInt("boost." + id + ".yuzde", yuzde); }
        else bitis = simdi + gun * 86_400_000L;
        veri.set("boost." + id + ".bitis", bitis);
        veri.set("boost." + id + ".yuzde", yuzde);
        veri.set("boost." + id + ".isim", h.getName());
        kaydet();
        Player p = h.getPlayer();
        if (p != null) p.sendMessage(ChatColor.AQUA + "XP boost aktif: +%" + yuzde + " (" + gun + " gün eklendi). /vip ile bak.");
        s.sendMessage(ChatColor.GREEN + h.getName() + " için XP boost verildi: +%" + yuzde + ", " + gun + " gün.");
        return true;
    }
}
