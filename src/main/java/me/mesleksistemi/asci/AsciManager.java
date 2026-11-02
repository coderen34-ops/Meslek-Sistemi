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
    private final NamespacedKey yemekKey, kaliteKey, kitapKey;
    private final File dosya;
    private final Map<String, Tarif> tarifler = new LinkedHashMap<>();
    private final Map<UUID, Integer> pisirilen = new HashMap<>();
    private final Map<UUID, Long> pisiriyor = new HashMap<>();
    private final Map<UUID, Long> yemeBekleme = new HashMap<>();

    public AsciManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.yemekKey = new NamespacedKey(plugin, "yemek_id");
        this.kaliteKey = new NamespacedKey(plugin, "yemek_kalite");
        this.kitapKey = new NamespacedKey(plugin, "asci_kitabi");
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
        ekle(new Tarif("menemen", "Menemen", Material.BAKED_POTATO, 0, "Türk Mutfağı").m(Material.EGG, 3).m(Material.BEETROOT, 1).m(Material.POTATO, 1).t(7, 11).e(HASTE, 0, 200).r(ChatColor.RED).a("Sabah acelesi: kazma hızlanır."));
        ekle(new Tarif("pierogi", "Pierogi", Material.BREAD, 0, "Polonya Mutfağı").m(Material.WHEAT, 3).m(Material.POTATO, 2).m(Material.BROWN_MUSHROOM, 1).t(8, 10).e(SPEED, 0, 200).r(ChatColor.YELLOW).a("Dolgun hamur, hafif adım."));
        ekle(new Tarif("fish_chips", "Fish and Chips", Material.COOKED_COD, 0, "İngiliz Mutfağı").m(Material.COD, 2).m(Material.POTATO, 2).m(Material.COAL, 1).t(7, 11).e(WB, 0, 240).r(ChatColor.AQUA).a("Liman yemeği: suda nefes."));
        ekle(new Tarif("miso", "Miso Çorbası", Material.MUSHROOM_STEW, 0, "Japon Mutfağı").m(Material.BROWN_MUSHROOM, 2).m(Material.KELP, 2).m(Material.BOWL, 1).t(8, 9).e(REG, 0, 180).r(ChatColor.GOLD).a("Sıcak çorba, yenilenme."));
        ekle(new Tarif("tom_yum", "Tom Yum", Material.BEETROOT_SOUP, 0, "Tay Mutfağı").m(Material.COD, 2).m(Material.GLOW_BERRIES, 2).m(Material.RED_MUSHROOM, 1).m(Material.BOWL, 1).t(9, 9).e(FIRE, 0, 240).e(SPEED, 0, 120).r(ChatColor.DARK_RED).a("Acı ekşi; ateşe karşı korur."));
        ekle(new Tarif("krep", "Crêpe Suzette", Material.PUMPKIN_PIE, 0, "Fransız Mutfağı").m(Material.WHEAT, 2).m(Material.EGG, 1).m(Material.SUGAR, 2).t(8, 9).e(ABS, 0, 240).r(ChatColor.YELLOW).a("İnce, tatlı; sarı kalpler (emilim)."));
        ekle(new Tarif("bruschetta", "Bruschetta", Material.BREAD, 0, "İtalyan Mutfağı").m(Material.WHEAT, 3).m(Material.BEETROOT, 2).m(Material.SWEET_BERRIES, 1).t(7, 9).e(JUMP, 0, 200).r(ChatColor.RED).a("Çıtır ekmek, hafif sıçrayış."));
        // ---- KALFA (rank 1)
        ekle(new Tarif("iskender", "İskender Kebap", Material.COOKED_BEEF, 1, "Türk Mutfağı").m(Material.BEEF, 3).m(Material.WHEAT, 2).m(Material.BEETROOT, 1).m(Material.COAL, 1).t(11, 17).e(STR, 0, 200).e(ABS, 0, 120).r(ChatColor.DARK_RED).a("Güç ve fazladan kalp."));
        ekle(new Tarif("coq_au_vin", "Coq au Vin", Material.COOKED_CHICKEN, 1, "Fransız Mutfağı").m(Material.CHICKEN, 3).m(Material.BROWN_MUSHROOM, 2).m(Material.SWEET_BERRIES, 2).t(10, 16).e(RES, 0, 240).e(REG, 0, 100).r(ChatColor.DARK_PURPLE).a("Şaraplı tavuk; dayanıklılık."));
        ekle(new Tarif("schnitzel", "Wiener Schnitzel", Material.COOKED_PORKCHOP, 1, "Alman Mutfağı").m(Material.PORKCHOP, 3).m(Material.WHEAT, 2).m(Material.EGG, 2).t(11, 16).e(SPEED, 1, 200).e(HASTE, 0, 200).r(ChatColor.GOLD).a("Çıtır ve hızlı."));
        ekle(new Tarif("dim_sum", "Dim Sum", Material.COOKED_MUTTON, 1, "Çin Mutfağı").m(Material.MUTTON, 2).m(Material.WHEAT, 3).m(Material.KELP, 1).t(9, 15).e(HB, 0, 240).r(ChatColor.LIGHT_PURPLE).a("Buharda pişmiş; fazladan can."));
        ekle(new Tarif("bibimbap", "Bibimbap", Material.RABBIT_STEW, 1, "Kore Mutfağı").m(Material.CARROT, 2).m(Material.EGG, 1).m(Material.BEEF, 1).m(Material.KELP, 2).m(Material.BOWL, 1).t(10, 17).e(REG, 1, 150).e(HASTE, 0, 150).r(ChatColor.GREEN).a("Renkli kase; yenilenme."));
        ekle(new Tarif("sushi", "Sushi Tabağı", Material.COOKED_SALMON, 1, "Japon Mutfağı").m(Material.SALMON, 2).m(Material.KELP, 3).m(Material.WHEAT_SEEDS, 4).t(9, 15).e(WB, 0, 300).e(DOL, 0, 240).r(ChatColor.BLUE).a("Denizci menüsü."));
        ekle(new Tarif("lazanya", "Lazanya", Material.BAKED_POTATO, 1, "İtalyan Mutfağı").m(Material.BEEF, 2).m(Material.WHEAT, 3).m(Material.BEETROOT, 2).m(Material.EGG, 1).t(12, 17).e(ABS, 1, 240).e(RES, 0, 150).r(ChatColor.GOLD).a("Katmanlı; dört kalp emilim."));
        // ---- USTA (rank 2)
        ekle(new Tarif("pekin_ordegi", "Pekin Ördeği", Material.COOKED_CHICKEN, 2, "Çin Mutfağı").m(Material.CHICKEN, 4).m(Material.HONEY_BOTTLE, 1).m(Material.SUGAR, 2).t(14, 20).e(STR, 0, 240).e(SPEED, 1, 240).r(ChatColor.DARK_RED).a("Lake ördek; güç ve hız."));
        ekle(new Tarif("baklava", "Fıstıklı Baklava", Material.PUMPKIN_PIE, 2, "Türk Mutfağı").m(Material.HONEY_BOTTLE, 2).m(Material.WHEAT, 4).m(Material.GOLD_NUGGET, 2).t(10, 20).e(ABS, 2, 240).e(HB, 1, 240).r(ChatColor.YELLOW).a("Altın sarısı kalpler ve ekstra can."));
        ekle(new Tarif("wellington", "Beef Wellington", Material.COOKED_BEEF, 2, "İngiliz Mutfağı").m(Material.BEEF, 4).m(Material.WHEAT, 3).m(Material.BROWN_MUSHROOM, 2).m(Material.EGG, 1).t(15, 20).e(STR, 1, 240).e(RES, 0, 240).e(ABS, 1, 120).r(ChatColor.DARK_RED).a("Kraliyet sofrası."));
        ekle(new Tarif("bigos", "Bigos (Avcı Güveci)", Material.RABBIT_STEW, 2, "Polonya Mutfağı").m(Material.PORKCHOP, 2).m(Material.BEEF, 2).m(Material.BEETROOT, 3).m(Material.BROWN_MUSHROOM, 2).m(Material.BOWL, 1).t(16, 20).e(RES, 1, 240).e(FIRE, 0, 240).e(REG, 0, 120).r(ChatColor.GRAY).a("Uzun pişen güveç; çok tok tutar."));
        ekle(new Tarif("kimchi_jjigae", "Kimchi Jjigae", Material.BEETROOT_SOUP, 2, "Kore Mutfağı").m(Material.BEETROOT, 4).m(Material.PORKCHOP, 2).m(Material.BLAZE_POWDER, 1).m(Material.BOWL, 1).t(12, 18).e(FIRE, 0, 300).e(STR, 0, 200).e(HASTE, 1, 200).r(ChatColor.RED).a("Acılı kazan; güç ve acele."));
        ekle(new Tarif("creme_brulee", "Crème Brûlée", Material.HONEY_BOTTLE, 2, "Fransız Mutfağı").m(Material.EGG, 3).m(Material.SUGAR, 3).m(Material.HONEY_BOTTLE, 1).m(Material.GOLD_NUGGET, 1).t(8, 20).e(LUCK, 0, 300).e(ABS, 1, 200).e(NV, 0, 200).r(ChatColor.GOLD).a("Karamel kabuk; şans ve kalp."));
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
        try { meta.setItemModel(new NamespacedKey("koninski", "yemek/" + t.id)); } catch (Throwable ignored) { }
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
        if (a.length >= 1 && a[0].equalsIgnoreCase("menu")) { menuAc(p); return true; }
        kitapVer(p);
        return true;
    }

    private boolean kitapMi(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(kitapKey, PersistentDataType.BYTE);
    }

    private void kitapVer(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (kitapMi(it)) { p.sendMessage(ChatColor.YELLOW + "Aşçı Tarif Kitabı zaten sende. Ocağa (fırın/kamp ateşi) kitapla sağ tıklayıp pişirebilirsin."); return; }
        ItemStack k = new ItemStack(Material.WRITTEN_BOOK);
        org.bukkit.inventory.meta.BookMeta bm = (org.bukkit.inventory.meta.BookMeta) k.getItemMeta();
        bm.setTitle("Aşçının Tarif Kitabı");
        bm.setAuthor("Koninski Mutfağı");
        List<String> sayfa = new ArrayList<>();
        sayfa.add(ChatColor.GOLD + "" + ChatColor.BOLD + "Aşçının Tarif Kitabı\n\n" + ChatColor.BLACK
                + "Dünya mutfaklarından " + tarifler.size() + " tarif.\n\nPişirmek için kitabı elinde tutup bir ocağa (fırın, dumanlı ocak, kamp ateşi) SAĞ TIKLA.\n\nÇırak: ilk 7 tarif\nKalfa: " + plugin.getConfig().getInt("asci.kalfa-esik", 40) + " yemekten sonra\nUsta: " + plugin.getConfig().getInt("asci.usta-esik", 150) + " yemekten sonra");
        for (Tarif t : tarifler.values()) {
            StringBuilder sb = new StringBuilder();
            sb.append(ChatColor.DARK_RED).append(ChatColor.BOLD).append(t.ad).append("\n").append(ChatColor.DARK_GRAY).append(t.tur).append(" • ").append(RANK_ADI[t.rank]).append("\n\n").append(ChatColor.BLACK);
            sb.append("Malzeme:\n");
            for (Map.Entry<Material, Integer> e : t.malzeme.entrySet()) if (e.getValue() > 0) sb.append("- ").append(e.getValue()).append(" ").append(malzemeAdi(e.getKey())).append("\n");
            sb.append("\nEtki:\n");
            for (Object[] x : t.etki) sb.append("- ").append(etkiAdi((PotionEffectType) x[0])).append(" ").append(roma((Integer) x[1] + 1)).append("\n");
            sb.append("Tokluk ").append(t.tokluk).append(" / ").append(t.doygunluk);
            sayfa.add(sb.toString());
        }
        bm.setPages(sayfa);
        bm.getPersistentDataContainer().set(kitapKey, PersistentDataType.BYTE, (byte) 1);
        k.setItemMeta(bm);
        p.getInventory().addItem(k).values().forEach(x -> p.getWorld().dropItemNaturally(p.getLocation(), x));
        p.sendMessage(ChatColor.GOLD + "Aşçı Tarif Kitabı envanterine eklendi. Elinde tutup bir ocağa sağ tıklayarak pişir.");
    }

    private static String malzemeAdi(Material m) {
        switch (m) {
            case EGG: return "yumurta"; case WHEAT: return "buğday"; case POTATO: return "patates"; case BEETROOT: return "pancar";
            case BROWN_MUSHROOM: return "kahverengi mantar"; case RED_MUSHROOM: return "kırmızı mantar"; case COD: return "morina"; case SALMON: return "somon";
            case COAL: return "kömür"; case KELP: return "yosun"; case BOWL: return "kase"; case GLOW_BERRIES: return "ışıklı meyve";
            case SUGAR: return "şeker"; case SWEET_BERRIES: return "tatlı meyve"; case BEEF: return "sığır eti"; case CHICKEN: return "tavuk";
            case PORKCHOP: return "domuz eti"; case MUTTON: return "koyun eti"; case CARROT: return "havuç"; case WHEAT_SEEDS: return "buğday tohumu";
            case HONEY_BOTTLE: return "bal şişesi"; case GOLD_NUGGET: return "altın parçası"; case BLAZE_POWDER: return "alev tozu";
            default: return m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onOcakTik(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!kitapMi(e.getItem()) || e.getClickedBlock() == null) return;
        Material m = e.getClickedBlock().getType();
        if (m != Material.FURNACE && m != Material.SMOKER && m != Material.BLAST_FURNACE && m != Material.CAMPFIRE && m != Material.SOUL_CAMPFIRE) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!asciMi(p) && !p.hasPermission("meslek.admin")) { p.sendMessage(ChatColor.RED + "Bu kitap sadece Aşçılar için."); return; }
        menuAc(p);
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
