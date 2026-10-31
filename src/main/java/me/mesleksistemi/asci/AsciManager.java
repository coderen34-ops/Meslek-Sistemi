package me.mesleksistemi.asci;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.FoodComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import me.mesleksistemi.MeslekSistemi;

/**
 * Aşçılık: yemek tarifleri SADECE "asci" mesleğindeki oyuncu tarafından pişirilir; herkes yiyebilir.
 * Üç kademe (Çırak/Kalfa/Usta) pişirme sayısına göre açılır; usta aşçının yemeği daha uzun etki verir.
 * Pişirmek için yakında bir ocak (fırın/dumanlı ocak/kamp ateşi) gerekir. Yemekler PDC ile etiketlidir.
 */
public class AsciManager implements Listener, CommandExecutor {

    private static final class Tarif {
        final String id, ad; final Material baz; final int rank; final String tur;
        final Map<Material, Integer> malzeme = new LinkedHashMap<>();
        int tokluk, doygunluk; final List<Object[]> etki = new ArrayList<>(); // {PotionEffectType, amp, sn}
        ChatColor renk = ChatColor.GOLD; String aciklama = "";
        Tarif(String id, String ad, Material baz, int rank, String tur) { this.id = id; this.ad = ad; this.baz = baz; this.rank = rank; this.tur = tur; }
        Tarif m(Material m, int n) { malzeme.put(m, n); return this; }
        Tarif t(int tokluk, int doygunluk) { this.tokluk = tokluk; this.doygunluk = doygunluk; return this; }
        Tarif e(PotionEffectType t, int amp, int sn) { etki.add(new Object[]{t, amp, sn}); return this; }
        Tarif r(ChatColor c) { renk = c; return this; }
        Tarif a(String s) { aciklama = s; return this; }
    }

    private static final class Menu implements InventoryHolder {
        Inventory inv; @Override public Inventory getInventory() { return inv; }
    }

    private static final String[] RANK_ADI = {"Çırak", "Kalfa", "Usta"};
    private static final String[] KALITE_ADI = {"Sade", "Özenli", "Şefin Seçimi"};

    private final MeslekSistemi plugin;
    private final NamespacedKey yemekKey, kaliteKey;
    private final File dosya;
    private final Map<String, Tarif> tarifler = new LinkedHashMap<>();
    private final Map<UUID, Integer> pisirilen = new HashMap<>();
    private final Map<UUID, Long> pisiriyor = new HashMap<>();
    private final Map<UUID, Long> yemeBekleme = new HashMap<>();

    public AsciManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.yemekKey = new NamespacedKey(plugin, "yemek_id");
        this.kaliteKey = new NamespacedKey(plugin, "yemek_kalite");
        this.dosya = new File(plugin.getDataFolder(), "asci.yml");
        plugin.getConfig().addDefault("asci.kalfa-esik", 40);
        plugin.getConfig().addDefault("asci.usta-esik", 150);
        plugin.getConfig().addDefault("asci.pisirme-sn", 4);
        plugin.getConfig().addDefault("asci.ocak-mesafe", 5);
        plugin.getConfig().addDefault("asci.yeme-bekleme-sn", 20);
        plugin.getConfig().addDefault("asci.adet", 2);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
        tanimla();
        yukle();
    }

    // ------------------------------------------------------------------ tarifler
    private Tarif ekle(Tarif t) { tarifler.put(t.id, t); return t; }

    private void tanimla() {
        PotionEffectType SPEED = PotionEffectType.SPEED, HASTE = PotionEffectType.HASTE, STR = PotionEffectType.STRENGTH,
                RES = PotionEffectType.RESISTANCE, REG = PotionEffectType.REGENERATION, ABS = PotionEffectType.ABSORPTION,
                FIRE = PotionEffectType.FIRE_RESISTANCE, NV = PotionEffectType.NIGHT_VISION, JUMP = PotionEffectType.JUMP_BOOST,
                WB = PotionEffectType.WATER_BREATHING, HB = PotionEffectType.HEALTH_BOOST, SF = PotionEffectType.SLOW_FALLING,
                LUCK = PotionEffectType.LUCK, DOL = PotionEffectType.DOLPHINS_GRACE;
        // ---- ÇIRAK (rank 0)
        ekle(new Tarif("ekmek_dilimi", "Tereyağlı Ekmek", Material.BREAD, 0, "Hamur").m(Material.WHEAT, 3).t(7, 8).r(ChatColor.YELLOW).a("Sade ve doyurucu."));
        ekle(new Tarif("kor_patates", "Közde Patates", Material.BAKED_POTATO, 0, "Ana").m(Material.POTATO, 3).t(6, 10).e(SPEED, 0, 180).r(ChatColor.GOLD).a("Sıcak sıcak, ayaklar hızlanır."));
        ekle(new Tarif("tavuk_izgara", "Izgara Tavuk", Material.COOKED_CHICKEN, 0, "Ana").m(Material.CHICKEN, 2).m(Material.COAL, 1).t(8, 12).e(HASTE, 0, 200).r(ChatColor.GOLD).a("Eller çevik, kazma hızlı (acele)."));
        ekle(new Tarif("mantar_corbasi", "Mantar Çorbası", Material.MUSHROOM_STEW, 0, "Çorba").m(Material.RED_MUSHROOM, 2).m(Material.BROWN_MUSHROOM, 2).m(Material.BOWL, 1).t(8, 9).e(REG, 0, 180).r(ChatColor.RED).a("Yenilenme verir."));
        ekle(new Tarif("balik_tava", "Tavada Balık", Material.COOKED_COD, 0, "Ana").m(Material.COD, 3).m(Material.KELP, 2).t(7, 11).e(WB, 0, 240).r(ChatColor.AQUA).a("Suyun altında nefes."));
        ekle(new Tarif("elmali_turta", "Elmalı Turta", Material.PUMPKIN_PIE, 0, "Tatlı").m(Material.APPLE, 2).m(Material.SUGAR, 2).m(Material.WHEAT, 2).t(8, 9).e(ABS, 0, 240).r(ChatColor.YELLOW).a("Sarı kalpler (emilim) verir."));
        ekle(new Tarif("sebze_corba", "Sebze Çorbası", Material.BEETROOT_SOUP, 0, "Çorba").m(Material.BEETROOT, 3).m(Material.CARROT, 2).m(Material.BOWL, 1).t(9, 8).e(RES, 0, 180).r(ChatColor.DARK_RED).a("Hafif sertleşir (direnç)."));
        ekle(new Tarif("tuzlu_kuru", "Kuru Yosun Atıştırmalığı", Material.DRIED_KELP, 0, "Atıştırma").m(Material.KELP, 4).t(3, 6).e(DOL, 0, 180).r(ChatColor.DARK_GREEN).a("Suda hız (yunus zarafeti)."));
        ekle(new Tarif("bal_kurabiye", "Ballı Kurabiye", Material.COOKIE, 0, "Tatlı").m(Material.COCOA_BEANS, 2).m(Material.WHEAT, 2).m(Material.HONEY_BOTTLE, 1).t(4, 10).e(HASTE, 1, 150).r(ChatColor.GOLD).a("Şekerli acele."));
        ekle(new Tarif("havuc_tatli", "Altın Havuç Dilimi", Material.GOLDEN_CARROT, 0, "Atıştırma").m(Material.CARROT, 3).m(Material.GOLD_NUGGET, 4).t(6, 14).e(NV, 0, 300).r(ChatColor.GOLD).a("Gece görüşü."));
        // ---- KALFA (rank 1)
        ekle(new Tarif("biftek_tam", "Tam Pişmiş Biftek", Material.COOKED_BEEF, 1, "Ana").m(Material.BEEF, 3).m(Material.COAL, 1).m(Material.SWEET_BERRIES, 2).t(10, 18).e(STR, 0, 200).r(ChatColor.DARK_RED).a("Güç verir."));
        ekle(new Tarif("kuzu_tava", "Kuzu Tava", Material.COOKED_MUTTON, 1, "Ana").m(Material.MUTTON, 3).m(Material.POTATO, 2).m(Material.CARROT, 1).t(10, 16).e(RES, 0, 240).e(ABS, 0, 120).r(ChatColor.RED).a("Dayanıklılık ve sarı kalp."));
        ekle(new Tarif("somon_firin", "Fırın Somon", Material.COOKED_SALMON, 1, "Ana").m(Material.SALMON, 3).t(9, 15).e(WB, 0, 300).e(DOL, 0, 240).r(ChatColor.LIGHT_PURPLE).a("Su altı uzmanı."));
        ekle(new Tarif("tavsan_yahni", "Tavşan Yahnisi", Material.RABBIT_STEW, 1, "Çorba").m(Material.RABBIT, 2).m(Material.CARROT, 2).m(Material.BAKED_POTATO, 1).m(Material.BOWL, 1).t(10, 17).e(JUMP, 1, 240).e(SPEED, 0, 180).r(ChatColor.GREEN).a("Zıplama ve hız."));
        ekle(new Tarif("bal_corbasi", "Bal Şerbeti", Material.HONEY_BOTTLE, 1, "İçecek").m(Material.HONEY_BOTTLE, 1).m(Material.SUGAR, 2).t(5, 12).e(REG, 1, 120).e(SF, 0, 240).r(ChatColor.YELLOW).a("Tatlı yenilenme, yavaş düşüş."));
        ekle(new Tarif("kavun_tabagi", "Soğuk Kavun Tabağı", Material.MELON_SLICE, 1, "Tatlı").m(Material.MELON_SLICE, 4).m(Material.SUGAR, 1).t(5, 10).e(FIRE, 0, 300).r(ChatColor.GREEN).a("Ateşe dayanıklılık."));
        ekle(new Tarif("kabak_kek", "Kabak Kek", Material.PUMPKIN_PIE, 1, "Tatlı").m(Material.PUMPKIN, 1).m(Material.EGG, 1).m(Material.SUGAR, 2).m(Material.WHEAT, 2).t(8, 14).e(ABS, 1, 240).e(HASTE, 0, 150).r(ChatColor.GOLD).a("Dört kalp emilim."));
        ekle(new Tarif("avci_tabagi", "Avcı Tabağı", Material.COOKED_PORKCHOP, 1, "Ana").m(Material.PORKCHOP, 3).m(Material.POTATO, 2).m(Material.BROWN_MUSHROOM, 1).t(11, 19).e(SPEED, 1, 200).e(HASTE, 0, 200).r(ChatColor.GOLD).a("Tok ve çevik."));
        ekle(new Tarif("sutlu_bisk", "Sütlü Bisküvi", Material.COOKIE, 1, "Tatlı").m(Material.COCOA_BEANS, 3).m(Material.WHEAT, 3).m(Material.EGG, 1).t(5, 12).e(HB, 0, 240).r(ChatColor.DARK_GRAY).a("Fazladan canlılık."));
        // ---- USTA (rank 2)
        ekle(new Tarif("sef_biftek", "Şefin Bifteği", Material.COOKED_BEEF, 2, "Ana").m(Material.BEEF, 4).m(Material.GOLD_NUGGET, 4).m(Material.SWEET_BERRIES, 3).t(14, 20).e(STR, 1, 240).e(RES, 0, 240).e(ABS, 1, 120).r(ChatColor.DARK_RED).a("Tam teşekküllü savaşçı yemeği."));
        ekle(new Tarif("altin_cig", "Altın Elma Güveci", Material.GOLDEN_APPLE, 2, "Ana").m(Material.APPLE, 2).m(Material.GOLD_INGOT, 4).m(Material.HONEY_BOTTLE, 1).t(12, 20).e(REG, 1, 180).e(ABS, 2, 180).r(ChatColor.GOLD).a("Altın sarısı kalpler, yüksek yenilenme."));
        ekle(new Tarif("madenci_yahnisi", "Madenci Yahnisi", Material.MUSHROOM_STEW, 2, "Çorba").m(Material.POTATO, 3).m(Material.BEEF, 2).m(Material.IRON_NUGGET, 4).m(Material.BOWL, 1).t(12, 18).e(HASTE, 2, 300).e(NV, 0, 300).r(ChatColor.GRAY).a("Kazma hızlı, gece görüşlü (kuvvetli acele)."));
        ekle(new Tarif("gezgin_azigi", "Gezgin Azığı", Material.BREAD, 2, "Ana").m(Material.WHEAT, 4).m(Material.HONEY_BOTTLE, 1).m(Material.CARROT, 3).t(13, 20).e(SPEED, 1, 300).e(JUMP, 0, 300).r(ChatColor.AQUA).a("Uzun yol yemeği."));
        ekle(new Tarif("deniz_sofrasi", "Deniz Sofrası", Material.COOKED_SALMON, 2, "Ana").m(Material.SALMON, 3).m(Material.COD, 3).m(Material.KELP, 4).t(12, 18).e(WB, 0, 300).e(DOL, 1, 300).e(ABS, 0, 180).r(ChatColor.BLUE).a("Dalgıç menüsü."));
        ekle(new Tarif("ejder_corbasi", "Ejder Çorbası", Material.BEETROOT_SOUP, 2, "Çorba").m(Material.BEETROOT, 4).m(Material.BLAZE_POWDER, 1).m(Material.MAGMA_CREAM, 1).m(Material.BOWL, 1).t(11, 17).e(FIRE, 0, 300).e(STR, 0, 200).e(RES, 1, 150).r(ChatColor.RED).a("İçini ısıtır, ateşe karşı korur."));
        ekle(new Tarif("sans_kurabiyesi", "Şans Kurabiyesi", Material.COOKIE, 2, "Tatlı").m(Material.COCOA_BEANS, 4).m(Material.GOLD_NUGGET, 3).m(Material.EGG, 2).t(5, 16).e(LUCK, 0, 300).e(HB, 0, 240).r(ChatColor.GREEN).a("Şans ve fazladan can."));
        ekle(new Tarif("bayram_sofrasi", "Bayram Sofrası", Material.COOKED_CHICKEN, 2, "Ana").m(Material.CHICKEN, 3).m(Material.BREAD, 2).m(Material.APPLE, 2).m(Material.HONEY_BOTTLE, 1).t(20, 20).e(REG, 0, 240).e(SPEED, 0, 240).e(HASTE, 0, 240).e(STR, 0, 180).r(ChatColor.LIGHT_PURPLE).a("Her şeyden biraz; çok tok tutar."));
        ekle(new Tarif("tatli_hamle", "Usta Baklavası", Material.PUMPKIN_PIE, 2, "Tatlı").m(Material.HONEY_BOTTLE, 2).m(Material.WHEAT, 4).m(Material.SUGAR, 3).m(Material.GOLD_NUGGET, 2).t(10, 20).e(ABS, 2, 240).e(HB, 1, 240).e(SF, 0, 240).r(ChatColor.YELLOW).a("Sarı kalpler ve tokluk."));
        ekle(new Tarif("kral_kebabi", "Kral Kebabı", Material.COOKED_MUTTON, 2, "Ana").m(Material.MUTTON, 4).m(Material.BEEF, 2).m(Material.COAL, 2).m(Material.SWEET_BERRIES, 2).t(16, 20).e(STR, 1, 240).e(SPEED, 1, 240).e(REG, 0, 120).r(ChatColor.DARK_RED).a("Çok yüksek tokluk."));
    }

    // ------------------------------------------------------------------ yardımcılar
    private boolean asciMi(Player p) { return "asci".equals(plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas")); }

    private int rankOf(UUID id) {
        int n = pisirilen.getOrDefault(id, 0);
        if (n >= plugin.getConfig().getInt("asci.usta-esik", 150)) return 2;
        if (n >= plugin.getConfig().getInt("asci.kalfa-esik", 40)) return 1;
        return 0;
    }

    private boolean ocakVar(Player p) {
        int r = plugin.getConfig().getInt("asci.ocak-mesafe", 5);
        Location l = p.getLocation();
        for (int x = -r; x <= r; x++) for (int y = -2; y <= 2; y++) for (int z = -r; z <= r; z++) {
            Material m = l.getWorld().getBlockAt(l.getBlockX() + x, l.getBlockY() + y, l.getBlockZ() + z).getType();
            if (m == Material.FURNACE || m == Material.SMOKER || m == Material.BLAST_FURNACE || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE) return true;
        }
        return false;
    }

    private int say(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == m && !it.hasItemMeta()) n += it.getAmount();
        return n;
    }

    private void al(Player p, Material m, int adet) {
        int kalan = adet;
        ItemStack[] c = p.getInventory().getContents();
        for (int i = 0; i < c.length && kalan > 0; i++) {
            ItemStack it = c[i];
            if (it == null || it.getType() != m || it.hasItemMeta()) continue;
            int al = Math.min(kalan, it.getAmount());
            it.setAmount(it.getAmount() - al);
            if (it.getAmount() <= 0) p.getInventory().setItem(i, null);
            kalan -= al;
        }
    }

    private boolean malzemeTam(Player p, Tarif t) {
        for (Map.Entry<Material, Integer> e : t.malzeme.entrySet()) if (e.getValue() > 0 && say(p, e.getKey()) < e.getValue()) return false;
        return true;
    }

    // ------------------------------------------------------------------ yemek eşyası
    private ItemStack yemek(Tarif t, int kalite, int adet) {
        ItemStack it = new ItemStack(t.baz, adet);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(t.renk + "" + ChatColor.BOLD + t.ad);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + t.tur + " • " + ChatColor.WHITE + KALITE_ADI[kalite]);
        lore.add(ChatColor.DARK_GRAY + t.aciklama);
        lore.add(ChatColor.YELLOW + "Etki süresi: " + ChatColor.WHITE + "x" + kaliteCarpan(kalite));
        meta.setLore(lore);
        try {
            FoodComponent f = meta.getFood();
            f.setNutrition(t.tokluk);
            f.setSaturation(t.doygunluk);
            f.setCanAlwaysEat(true);
            meta.setFood(f);
        } catch (Throwable ignored) { }
        meta.getPersistentDataContainer().set(yemekKey, PersistentDataType.STRING, t.id);
        meta.getPersistentDataContainer().set(kaliteKey, PersistentDataType.INTEGER, kalite);
        it.setItemMeta(meta);
        return it;
    }

    private static double kaliteCarpan(int k) { return k == 0 ? 1.0 : k == 1 ? 1.25 : 1.5; }

    // ------------------------------------------------------------------ yeme
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onYe(PlayerItemConsumeEvent e) {
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(yemekKey, PersistentDataType.STRING);
        if (id == null) return;
        Tarif t = tarifler.get(id);
        if (t == null) return;
        Player p = e.getPlayer();
        long simdi = System.currentTimeMillis();
        long bek = plugin.getConfig().getInt("asci.yeme-bekleme-sn", 20) * 1000L;
        Long son = yemeBekleme.get(p.getUniqueId());
        if (son != null && simdi - son < bek) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Önceki yemeğin tadı hâlâ damağında; " + ((bek - (simdi - son)) / 1000 + 1) + " sn sonra tekrar ye.");
            return;
        }
        yemeBekleme.put(p.getUniqueId(), simdi);
        Integer k = it.getItemMeta().getPersistentDataContainer().get(kaliteKey, PersistentDataType.INTEGER);
        double c = kaliteCarpan(k == null ? 0 : k);
        for (Object[] x : t.etki)
            p.addPotionEffect(new PotionEffect((PotionEffectType) x[0], (int) Math.round((Integer) x[2] * c * 20), (Integer) x[1], true, true, true));
        p.sendMessage(ChatColor.GREEN + "Afiyet olsun! " + t.renk + t.ad + ChatColor.GRAY + " (" + t.aciklama + ")");
    }

    @EventHandler public void onCikis(PlayerQuitEvent e) { pisiriyor.remove(e.getPlayer().getUniqueId()); yemeBekleme.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ komut + menü
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (a.length >= 1 && a[0].equalsIgnoreCase("ver") && p.hasPermission("meslek.admin")) { // /yemek ver <id> [kalite 0-2] [adet]
            Tarif t = a.length > 1 ? tarifler.get(a[1]) : null;
            if (t == null) { p.sendMessage(ChatColor.RED + "Tarif yok. Kimlikler: " + String.join(", ", tarifler.keySet())); return true; }
            int k = 0, n = 1;
            try { if (a.length > 2) k = Math.max(0, Math.min(2, Integer.parseInt(a[2]))); if (a.length > 3) n = Math.max(1, Math.min(64, Integer.parseInt(a[3]))); } catch (NumberFormatException ex) { }
            p.getInventory().addItem(yemek(t, k, n));
            return true;
        }
        if (!asciMi(p) && !p.hasPermission("meslek.admin")) {
            p.sendMessage(ChatColor.RED + "Yemek tarifleri sadece Aşçıların sırrıdır. Meslek NPC'sinden Aşçı olabilirsin.");
            return true;
        }
        menuAc(p);
        return true;
    }

    private void menuAc(Player p) {
        Menu mn = new Menu();
        Inventory inv = Bukkit.createInventory(mn, 54, ChatColor.GOLD + "Aşçı Tarifleri");
        mn.inv = inv;
        int rank = rankOf(p.getUniqueId());
        int slot = 0;
        for (Tarif t : tarifler.values()) {
            if (slot >= 45) break;
            ItemStack g = new ItemStack(t.rank > rank ? Material.BARRIER : t.baz);
            ItemMeta m = g.getItemMeta();
            m.setDisplayName(t.renk + "" + ChatColor.BOLD + t.ad);
            List<String> l = new ArrayList<>();
            l.add(ChatColor.GRAY + t.tur + " • " + RANK_ADI[t.rank] + " tarifi");
            l.add(ChatColor.DARK_GRAY + t.aciklama);
            l.add(ChatColor.YELLOW + "Tokluk: " + ChatColor.WHITE + t.tokluk + ChatColor.YELLOW + "  Doygunluk: " + ChatColor.WHITE + t.doygunluk);
            for (Object[] x : t.etki) l.add(ChatColor.AQUA + " + " + etkiAdi((PotionEffectType) x[0]) + " " + roma((Integer) x[1] + 1) + " (" + (Integer) x[2] / 60 + "dk " + (Integer) x[2] % 60 + "sn)");
            l.add("");
            for (Map.Entry<Material, Integer> e : t.malzeme.entrySet()) {
                if (e.getValue() <= 0) continue;
                boolean var = say(p, e.getKey()) >= e.getValue();
                l.add((var ? ChatColor.GREEN : ChatColor.RED) + "• " + e.getValue() + "x " + e.getKey().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            }
            l.add("");
            l.add(t.rank > rank ? ChatColor.RED + "Kilitli: " + RANK_ADI[t.rank] + " olmalısın" : ChatColor.YELLOW + "Tıkla: pişir");
            m.setLore(l);
            g.setItemMeta(m);
            inv.setItem(slot++, g);
        }
        int n = pisirilen.getOrDefault(p.getUniqueId(), 0);
        ItemStack bilgi = new ItemStack(Material.BOOK);
        ItemMeta bm = bilgi.getItemMeta();
        bm.setDisplayName(ChatColor.GOLD + "Aşçılık: " + RANK_ADI[rank]);
        List<String> bl = new ArrayList<>();
        bl.add(ChatColor.GRAY + "Pişirilen yemek: " + ChatColor.WHITE + n);
        bl.add(ChatColor.GRAY + "Kalfa: " + plugin.getConfig().getInt("asci.kalfa-esik", 40) + ", Usta: " + plugin.getConfig().getInt("asci.usta-esik", 150));
        bl.add(ChatColor.GRAY + "Pişirmek için yakında ocak/fırın/kamp ateşi gerekir.");
        bm.setLore(bl);
        bilgi.setItemMeta(bm);
        inv.setItem(49, bilgi);
        p.openInventory(inv);
    }

    private static String roma(int n) { return n == 1 ? "I" : n == 2 ? "II" : n == 3 ? "III" : String.valueOf(n); }

    private static String etkiAdi(PotionEffectType t) {
        if (t == PotionEffectType.SPEED) return "Hız";
        if (t == PotionEffectType.HASTE) return "Acele";
        if (t == PotionEffectType.STRENGTH) return "Güç";
        if (t == PotionEffectType.RESISTANCE) return "Direnç";
        if (t == PotionEffectType.REGENERATION) return "Yenilenme";
        if (t == PotionEffectType.ABSORPTION) return "Emilim (sarı kalp)";
        if (t == PotionEffectType.FIRE_RESISTANCE) return "Ateş Direnci";
        if (t == PotionEffectType.NIGHT_VISION) return "Gece Görüşü";
        if (t == PotionEffectType.JUMP_BOOST) return "Zıplama";
        if (t == PotionEffectType.WATER_BREATHING) return "Su Altı Nefesi";
        if (t == PotionEffectType.HEALTH_BOOST) return "Ekstra Can";
        if (t == PotionEffectType.SLOW_FALLING) return "Yavaş Düşüş";
        if (t == PotionEffectType.LUCK) return "Şans";
        if (t == PotionEffectType.DOLPHINS_GRACE) return "Yunus Zarafeti";
        return "Etki";
    }

    @EventHandler
    public void onTikla(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 45) return;
        int i = 0; Tarif sec = null;
        for (Tarif t : tarifler.values()) { if (i++ == slot) { sec = t; break; } }
        if (sec == null) return;
        pisir(p, sec);
    }

    @EventHandler
    public void onSurukle(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    // ------------------------------------------------------------------ pişirme
    private void pisir(Player p, Tarif t) {
        p.closeInventory();
        if (!asciMi(p) && !p.hasPermission("meslek.admin")) return;
        int rank = rankOf(p.getUniqueId());
        if (t.rank > rank) { p.sendMessage(ChatColor.RED + "Bu tarif için " + RANK_ADI[t.rank] + " olmalısın."); return; }
        if (pisiriyor.containsKey(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Zaten bir şey pişiriyorsun."); return; }
        if (!ocakVar(p)) { p.sendMessage(ChatColor.RED + "Yakında ocak yok. Fırın, dumanlı ocak ya da kamp ateşinin yanında pişirmelisin."); return; }
        if (!malzemeTam(p, t)) { p.sendMessage(ChatColor.RED + "Malzemelerin eksik. /asci menüsünde kırmızı yazılar eksik olanlar."); return; }
        for (Map.Entry<Material, Integer> e : t.malzeme.entrySet()) if (e.getValue() > 0) al(p, e.getKey(), e.getValue());
        int sn = Math.max(1, plugin.getConfig().getInt("asci.pisirme-sn", 4));
        pisiriyor.put(p.getUniqueId(), System.currentTimeMillis());
        p.sendMessage(ChatColor.GOLD + t.ad + ChatColor.GRAY + " pişiyor... (" + sn + " sn, ocağın başından ayrılma)");
        final Location baslangic = p.getLocation().clone();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pisiriyor.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (!p.getWorld().equals(baslangic.getWorld()) || p.getLocation().distanceSquared(baslangic) > 36 || !ocakVar(p)) {
                p.sendMessage(ChatColor.RED + "Ocaktan ayrıldın, yemek yandı! Malzemeler boşa gitti.");
                return;
            }
            int r = rankOf(p.getUniqueId());
            int kalite = r;
            if (r >= 1 && Math.random() < 0.12 && kalite < 2) kalite++; // arada usta işi
            int adet = Math.max(1, plugin.getConfig().getInt("asci.adet", 2));
            ItemStack yemek = yemek(t, kalite, adet);
            Map<Integer, ItemStack> artan = p.getInventory().addItem(yemek);
            for (ItemStack k : artan.values()) p.getWorld().dropItemNaturally(p.getLocation(), k);
            int eski = pisirilen.getOrDefault(p.getUniqueId(), 0);
            pisirilen.put(p.getUniqueId(), eski + 1);
            kaydet();
            p.playSound(p.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.4f);
            p.sendMessage(ChatColor.GREEN + "Hazır: " + adet + "x " + t.renk + t.ad + ChatColor.GRAY + " (" + KALITE_ADI[kalite] + ")");
            int yeni = rankOf(p.getUniqueId());
            if (yeni > r || (yeni > rankOfOnce(eski))) p.sendMessage(ChatColor.GOLD + "★ Aşçılıkta yükseldin: " + RANK_ADI[yeni] + "! Yeni tarifler açıldı.");
            plugin.ekonomiLog("yemek-pisir", p.getName(), t.id + " kalite " + kalite);
        }, sn * 20L);
    }

    private int rankOfOnce(int n) {
        if (n >= plugin.getConfig().getInt("asci.usta-esik", 150)) return 2;
        if (n >= plugin.getConfig().getInt("asci.kalfa-esik", 40)) return 1;
        return 0;
    }

    // ------------------------------------------------------------------ kalıcılık
    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : pisirilen.entrySet()) y.set("pisirilen." + e.getKey(), e.getValue());
        try { plugin.getDataFolder().mkdirs(); y.save(dosya); } catch (Exception ex) { plugin.getLogger().warning("asci.yml yazılamadı: " + ex.getMessage()); }
    }

    private void yukle() {
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        if (y.getConfigurationSection("pisirilen") != null)
            for (String k : y.getConfigurationSection("pisirilen").getKeys(false)) {
                try { pisirilen.put(UUID.fromString(k), y.getInt("pisirilen." + k)); } catch (Exception ignored) { }
            }
    }

    public void kapat() { kaydet(); }
}
