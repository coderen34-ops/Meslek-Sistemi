package me.mesleksistemi.susuzluk;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

import me.mesleksistemi.MeslekSistemi;

/**
 * Susuzluk: 0-20 arası bir seviye. Zamanla azalır (koşmak, sıcak biyom ve kuraklıkta daha hızlı).
 * Düşükken yavaşlama; bitince daha ağır yavaşlama/halsizlik (ölüm yok).
 * Su şişesi, süt, bal, çorba tarzı yemekler ve su kaynağından içerek dolar.
 */
public class SusuzlukManager implements Listener, CommandExecutor {
    private static final double TAM = 20.0;

    private final MeslekSistemi plugin;
    private final File dosya;
    private final Map<UUID, Double> seviye = new HashMap<>();
    private final Map<UUID, Long> sonIcme = new HashMap<>();
    private final NamespacedKey yemekKey;
    /** Kuraklık gibi olaylar azalma hızını bu çarpanla büyütür. */
    private volatile double azalmaCarpani = 1.0;
    private int tur = 0;

    public SusuzlukManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "susuzluk.yml");
        this.yemekKey = new NamespacedKey(plugin, "yemek_id");
        yukle();
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, KANAL);
        // 20 saniyede bir azalt, 5 saniyede bir ceza/ekran güncelle
        Bukkit.getScheduler().runTaskTimer(plugin, this::azalt, 20L * 20, 20L * 20);
        Bukkit.getScheduler().runTaskTimer(plugin, this::cezaVeBar, 20L * 5, 20L * 5);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { if (aktif()) for (Player q : Bukkit.getOnlinePlayers()) guncelleBar(q); }, 30L, 30L);
    }

    private static final String KANAL = "koninski:client";
    private final Map<UUID, Integer> sonGonderilen = new HashMap<>();

    private boolean modVar(Player p) {
        try { return p.getListeningPluginChannels().contains(KANAL); } catch (Throwable t) { return false; }
    }

    /** Client modu olan oyuncuya susuzluk seviyesini (0-20) gönderir; değişmediyse göndermez. */
    private void modaGonder(Player p, boolean zorla) {
        if (!modVar(p)) { sonGonderilen.remove(p.getUniqueId()); return; }
        int lv = (int) Math.max(0, Math.min(TAM, Math.round(seviye(p.getUniqueId()))));
        Integer son = sonGonderilen.get(p.getUniqueId());
        if (!zorla && son != null && son == lv) return;
        sonGonderilen.put(p.getUniqueId(), lv);
        try { p.sendPluginMessage(plugin, KANAL, new byte[]{0x20, (byte) lv}); } catch (Throwable ignored) { }
    }

    public void setAzalmaCarpani(double c) { this.azalmaCarpani = Math.max(0.1, c); }

    private boolean aktif() { return plugin.getConfig().getBoolean("susuzluk.aktif", true); }
    private double azalmaMiktari() { return Math.max(0.01, plugin.getConfig().getDouble("susuzluk.azalma-20sn", 0.15)); }

    private void yukle() {
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        for (String k : y.getKeys(false)) {
            try { seviye.put(UUID.fromString(k), Math.max(0, Math.min(TAM, y.getDouble(k)))); } catch (Exception ignored) { }
        }
    }

    public void kapat() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : seviye.entrySet()) y.set(e.getKey().toString(), e.getValue());
        try { dosya.getParentFile().mkdirs(); y.save(dosya); } catch (Exception e) { plugin.getLogger().warning("[Susuzluk] kaydedilemedi: " + e.getMessage()); }
    }

    private boolean muaf(Player p) {
        org.bukkit.GameMode g = p.getGameMode();
        return g == org.bukkit.GameMode.CREATIVE || g == org.bukkit.GameMode.SPECTATOR;
    }

    public double seviye(UUID id) { return seviye.getOrDefault(id, TAM); }

    public void ekle(Player p, double miktar) {
        double yeni = Math.max(0, Math.min(TAM, seviye(p.getUniqueId()) + miktar));
        seviye.put(p.getUniqueId(), yeni);
        modaGonder(p, false);
        guncelleBar(p);
    }

    private void azalt() {
        if (!aktif()) return;
        double temel = azalmaMiktari() * azalmaCarpani;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (muaf(p) || p.isDead()) continue;
            double m = temel;
            if (p.isSprinting()) m *= 2.0;
            org.bukkit.World.Environment env = p.getWorld().getEnvironment();
            String biyom = p.getLocation().getBlock().getBiome().getKey().getKey();
            if (env == org.bukkit.World.Environment.NETHER || biyom.contains("desert") || biyom.contains("badlands") || biyom.contains("savanna")) m *= 1.5;
            // Suyun içindeyken azalmaz
            if (p.isInWater()) m = 0;
            double eski = seviye(p.getUniqueId());
            seviye.put(p.getUniqueId(), Math.max(0, eski - m));
        }
    }

    private void cezaVeBar() {
        if (!aktif()) return;
        tur++;
        for (Player p : Bukkit.getOnlinePlayers()) {
            guncelleBar(p);
            if (muaf(p) || p.isDead()) continue;
            double s = seviye(p.getUniqueId());
            if (s <= 0) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 140, 1, true, false, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 140, 0, true, false, true));
                if (tur % 6 == 0) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 120, 0, true, false, true));
                    p.sendMessage(ChatColor.RED + "Çok susadın! Bir şeyler içmelisin (su şişesi, çeşme, nehir).");
                }
            } else if (s < 6) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 140, 0, true, false, true));
                if (tur % 12 == 0) p.sendMessage(ChatColor.YELLOW + "Susuyorsun. Su içmeyi unutma.");
            }
        }
    }

    private void guncelleBar(Player p) {
        double s = seviye(p.getUniqueId());
        modaGonder(p, false);
        if (modVar(p)) return; // modlu oyuncuda actionbar kapalı, HUD'ı mod çizer
        if (!aktif() || muaf(p) || s >= 14) return;
        int dolu = (int) Math.round(s / 2.0);
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.AQUA).append("Su ").append(ChatColor.DARK_AQUA).append("[");
        sb.append(s < 6 ? ChatColor.RED : ChatColor.AQUA);
        for (int i = 0; i < 10; i++) {
            if (i == dolu) sb.append(ChatColor.DARK_GRAY);
            sb.append("|");
        }
        sb.append(ChatColor.DARK_AQUA).append("] ").append(ChatColor.WHITE).append((int) Math.round(s)).append("/20");
        p.sendActionBar(net.kyori.adventure.text.Component.text(sb.toString()));
    }

    // ------------------------------------------------------------------ içme
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTuket(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        Player p = e.getPlayer();
        double kazanc = 0;
        if (it.getType() == Material.POTION && it.getItemMeta() instanceof PotionMeta pm) {
            kazanc = (pm.getBasePotionType() == PotionType.WATER) ? 8 : 3;
        } else if (it.getType() == Material.MILK_BUCKET) kazanc = 5;
        else if (it.getType() == Material.HONEY_BOTTLE) kazanc = 3;
        else if (it.getType() == Material.MELON_SLICE) kazanc = 1.5;
        else if (it.getType() == Material.BEETROOT_SOUP || it.getType() == Material.MUSHROOM_STEW || it.getType() == Material.RABBIT_STEW || it.getType() == Material.SUSPICIOUS_STEW) kazanc = 3;
        if (it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(yemekKey, PersistentDataType.STRING)) {
            String id = it.getItemMeta().getPersistentDataContainer().get(yemekKey, PersistentDataType.STRING);
            // Çorba/güveç tarzı yemekler susuzluğu daha çok giderir, kuru yemekler az
            kazanc = (id != null && (id.equals("miso") || id.equals("tom_yum") || id.equals("bigos") || id.equals("kimchi_jjigae") || id.equals("bibimbap"))) ? 6 : 2;
        }
        if (kazanc > 0) {
            ekle(p, kazanc);
            if (seviye(p.getUniqueId()) >= TAM) p.sendMessage(ChatColor.AQUA + "Susuzluğun geçti.");
        }
    }

    /** Su kaynağına eğilip (sneak) boş elle sağ tıklamak: 1,5 saniyede bir yudum. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSuIc(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!p.isSneaking() || p.getInventory().getItemInMainHand().getType() != Material.AIR) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Material t = b.getType();
        boolean su = t == Material.WATER || t == Material.WATER_CAULDRON;
        if (!su) return;
        long simdi = System.currentTimeMillis();
        Long son = sonIcme.get(p.getUniqueId());
        if (son != null && simdi - son < 1500) return;
        sonIcme.put(p.getUniqueId(), simdi);
        if (seviye(p.getUniqueId()) >= TAM) { p.sendActionBar(net.kyori.adventure.text.Component.text("Susamadın.")); return; }
        ekle(p, 2.0);
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_GENERIC_DRINK, 0.6f, 1.0f);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Su içtin (" + (int) Math.round(seviye(p.getUniqueId())) + "/20)"));
    }

    @EventHandler
    public void onGiris(PlayerJoinEvent e) {
        seviye.putIfAbsent(e.getPlayer().getUniqueId(), TAM);
        final Player oy = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (oy.isOnline()) modaGonder(oy, true); }, 20L);
        guncelleBar(e.getPlayer());
    }

    @EventHandler
    public void onCikis(PlayerQuitEvent e) {
        sonGonderilen.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onDogus(PlayerRespawnEvent e) {
        seviye.put(e.getPlayer().getUniqueId(), TAM);
        modaGonder(e.getPlayer(), false);
    }

    // ------------------------------------------------------------------ komut
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 3 && args[0].equalsIgnoreCase("ver") && sender.hasPermission("meslek.admin")) {
            Player h = Bukkit.getPlayerExact(args[1]);
            if (h == null) { sender.sendMessage(ChatColor.RED + "Oyuncu çevrimiçi değil."); return true; }
            try {
                double d = Double.parseDouble(args[2]);
                seviye.put(h.getUniqueId(), Math.max(0, Math.min(TAM, d)));
                guncelleBar(h);
                sender.sendMessage(ChatColor.GREEN + h.getName() + " susuzluk seviyesi " + d + " yapıldı.");
            } catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "Geçersiz sayı."); }
            return true;
        }
        if (sender instanceof Player p) {
            double s = seviye(p.getUniqueId());
            p.sendMessage(ChatColor.AQUA + "Susuzluk: " + ChatColor.WHITE + String.format(Locale.US, "%.1f", s) + "/20");
            p.sendMessage(ChatColor.GRAY + "Su şişesi, süt, çorba ve çay doldurur. Eğilip (sneak) su kaynağına boş elle sağ tıkla da içebilirsin.");
        }
        return true;
    }
}
