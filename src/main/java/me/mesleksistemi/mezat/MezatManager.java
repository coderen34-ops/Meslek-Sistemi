package me.mesleksistemi.mezat;

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
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import me.mesleksistemi.MeslekSistemi;

/**
 * Mezat / açık artırma: Mezatçı NPC'si ya da /mezat ile nadir eşyalar açık artırmaya çıkarılır.
 * Teklifler BANKA hesabından peşin bloke edilir (aşılınca anında iade); sonuçta satıcıya (komisyon düşülmüş) banka
 * hesabına para yatar, kazanana eşya teslim edilir (çevrimdışı/envanter doluysa teslimat kuyruğunda bekler, kaybolmaz).
 * Eşya mezata konulurken oyuncudan alınır ve mezat.yml'de saklanır; iptal/sonuçsuz kalma = satıcıya iade.
 * Kaçak mal ve kaçak üretim eşyaları mezata konamaz.
 */
public class MezatManager implements Listener, CommandExecutor {

    private static final class Mezat {
        int id; UUID satici; String saticiAd = ""; ItemStack esya;
        double baslangic, teklif; UUID lider; String liderAd = ""; long bitis;
    }

    private static final class Menu implements InventoryHolder {
        Inventory inv; int sayfa;
        @Override public Inventory getInventory() { return inv; }
    }

    private final MeslekSistemi plugin;
    private final NamespacedKey npcKey, kacakMalKey, kacakEsyaKey;
    private final File dosya;
    private final Map<Integer, Mezat> aktif = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> teslimat = new HashMap<>();
    private int sonId = 0;

    public MezatManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "mezat_npc");
        this.kacakMalKey = new NamespacedKey(plugin, "kacak_mal");
        this.kacakEsyaKey = new NamespacedKey(plugin, "kacak_esya");
        this.dosya = new File(plugin.getDataFolder(), "mezat.yml");
        plugin.getConfig().addDefault("mezat.komisyon-yuzde", 5.0);
        plugin.getConfig().addDefault("mezat.oyuncu-limit", 3);
        plugin.getConfig().addDefault("mezat.min-sure-dakika", 2);
        plugin.getConfig().addDefault("mezat.max-sure-dakika", 60);
        plugin.getConfig().addDefault("mezat.varsayilan-sure-dakika", 10);
        plugin.getConfig().addDefault("mezat.min-artis-yuzde", 5.0);
        plugin.getConfig().addDefault("mezat.min-artis-tutar", 10.0);
        plugin.getConfig().addDefault("mezat.son-saniye-uzatma", 30);
        plugin.getConfig().addDefault("mezat.sat-npc-yakininda", true);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
        yukle();
        new BukkitRunnable() {
            @Override public void run() { tick(); }
        }.runTaskTimer(plugin, 40L, 20L);
    }

    // ------------------------------------------------------------------ yardımcılar
    private double komisyon() { return plugin.getConfig().getDouble("mezat.komisyon-yuzde", 5.0) / 100.0; }
    private static double yuvarla(double d) { return Math.round(d * 100.0) / 100.0; }
    private static String para(double d) { return "$" + String.format(Locale.US, "%,.2f", d); }

    private double minTeklif(Mezat m) {
        if (m.lider == null) return m.baslangic;
        double artis = Math.max(plugin.getConfig().getDouble("mezat.min-artis-tutar", 10.0),
                m.teklif * plugin.getConfig().getDouble("mezat.min-artis-yuzde", 5.0) / 100.0);
        return yuvarla(m.teklif + artis);
    }

    private boolean yasakliEsya(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        var pdc = it.getItemMeta().getPersistentDataContainer();
        return pdc.has(kacakMalKey, PersistentDataType.STRING) || pdc.has(kacakEsyaKey, PersistentDataType.STRING);
    }

    private boolean npcYakinda(Player p) {
        for (Entity e : p.getNearbyEntities(8, 4, 8))
            if (e instanceof Villager && e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return true;
        return false;
    }

    private void banka(UUID id, double delta) {
        plugin.bankaHesaplari.put(id, yuvarla(plugin.bankaHesaplari.getOrDefault(id, 0.0) + delta));
    }

    private static String sureYaz(long ms) {
        long sn = Math.max(0, ms / 1000);
        return (sn / 60) + "dk " + (sn % 60) + "sn";
    }

    // ------------------------------------------------------------------ kalıcılık
    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("sonId", sonId);
        for (Mezat m : aktif.values()) {
            String p = "mezat." + m.id;
            y.set(p + ".satici", m.satici.toString()); y.set(p + ".saticiAd", m.saticiAd);
            y.set(p + ".esya", m.esya);
            y.set(p + ".baslangic", m.baslangic); y.set(p + ".teklif", m.teklif);
            y.set(p + ".lider", m.lider == null ? "" : m.lider.toString()); y.set(p + ".liderAd", m.liderAd);
            y.set(p + ".bitis", m.bitis);
        }
        for (Map.Entry<UUID, List<ItemStack>> e : teslimat.entrySet())
            if (!e.getValue().isEmpty()) y.set("teslimat." + e.getKey(), e.getValue());
        try { plugin.getDataFolder().mkdirs(); y.save(dosya); }
        catch (Exception ex) { plugin.getLogger().severe("mezat.yml yazılamadı: " + ex.getMessage()); }
    }

    private void yukle() {
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        sonId = y.getInt("sonId", 0);
        ConfigurationSection ms = y.getConfigurationSection("mezat");
        if (ms != null) for (String k : ms.getKeys(false)) {
            try {
                Mezat m = new Mezat();
                m.id = Integer.parseInt(k);
                String p = "mezat." + k;
                m.satici = UUID.fromString(y.getString(p + ".satici"));
                m.saticiAd = y.getString(p + ".saticiAd", "");
                m.esya = y.getItemStack(p + ".esya");
                m.baslangic = y.getDouble(p + ".baslangic"); m.teklif = y.getDouble(p + ".teklif");
                String l = y.getString(p + ".lider", "");
                m.lider = l.isEmpty() ? null : UUID.fromString(l);
                m.liderAd = y.getString(p + ".liderAd", "");
                m.bitis = y.getLong(p + ".bitis");
                if (m.esya != null) aktif.put(m.id, m);
            } catch (Exception ex) { plugin.getLogger().warning("Bozuk mezat kaydı atlandı: " + k); }
        }
        ConfigurationSection ts = y.getConfigurationSection("teslimat");
        if (ts != null) for (String k : ts.getKeys(false)) {
            try {
                List<ItemStack> l = new ArrayList<>();
                for (Object o : y.getList("teslimat." + k, new ArrayList<>())) if (o instanceof ItemStack is) l.add(is);
                if (!l.isEmpty()) teslimat.put(UUID.fromString(k), l);
            } catch (Exception ignored) { }
        }
    }

    public void kapat() { kaydet(); }

    // ------------------------------------------------------------------ komut
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Sadece oyuncular."); return true; }
        String alt = a.length == 0 ? "menu" : a[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "menu": case "liste": menuAc(p, 0); return true;
            case "sat": mezataKoy(p, a); return true;
            case "teklif": teklifKomut(p, a); return true;
            case "teslim": teslimEt(p, true); return true;
            case "iptal": {
                if (!p.hasPermission("meslek.admin")) { p.sendMessage(ChatColor.RED + "Yetkin yok."); return true; }
                try { adminIptal(p, Integer.parseInt(a[1])); } catch (Exception e) { p.sendMessage(ChatColor.RED + "/mezat iptal <id>"); }
                return true;
            }
            case "npc": {
                if (!p.hasPermission("meslek.admin")) { p.sendMessage(ChatColor.RED + "Yetkin yok."); return true; }
                if (a.length < 2) { p.sendMessage(ChatColor.RED + "/mezat npc kur|sil"); return true; }
                if (a[1].equalsIgnoreCase("kur")) {
                    Villager v = (Villager) p.getWorld().spawnEntity(p.getLocation(), EntityType.VILLAGER);
                    v.setAI(false); v.setInvulnerable(true); v.setCollidable(false);
                    v.setProfession(Villager.Profession.LIBRARIAN);
                    v.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Mezatçı");
                    v.setCustomNameVisible(true);
                    v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
                    p.sendMessage(ChatColor.GREEN + "Mezatçı NPC'si kuruldu.");
                } else if (a[1].equalsIgnoreCase("sil")) {
                    int n = 0;
                    for (Entity e : p.getNearbyEntities(5, 5, 5))
                        if (e instanceof Villager && e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) { e.remove(); n++; }
                    p.sendMessage(n > 0 ? ChatColor.GREEN + "" + n + " Mezatçı silindi." : ChatColor.RED + "Yakında Mezatçı yok.");
                }
                return true;
            }
            default:
                p.sendMessage(ChatColor.YELLOW + "/mezat | /mezat sat <başlangıç fiyatı> [süre dk] | /mezat teklif <id> <tutar> | /mezat teslim");
                return true;
        }
    }

    // ------------------------------------------------------------------ mezata koy
    private void mezataKoy(Player p, String[] a) {
        if (a.length < 2) { p.sendMessage(ChatColor.RED + "Kullanım: /mezat sat <başlangıç fiyatı> [süre dk] (elindeki eşya satılır)"); return; }
        if (plugin.getConfig().getBoolean("mezat.sat-npc-yakininda", true) && !npcYakinda(p) && !p.hasPermission("meslek.admin")) {
            p.sendMessage(ChatColor.RED + "Mezata eşya koymak için Mezatçı'nın yanında olmalısın."); return;
        }
        ItemStack el = p.getInventory().getItemInMainHand();
        if (el == null || el.getType() == Material.AIR) { p.sendMessage(ChatColor.RED + "Elin boş."); return; }
        if (yasakliEsya(el) || plugin.getMoneyValue(el) != null) { p.sendMessage(ChatColor.RED + "Bu eşya mezata konamaz."); return; }
        double fiyat;
        try { fiyat = yuvarla(Double.parseDouble(a[1].replace(',', '.'))); } catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Geçersiz fiyat."); return; }
        if (!(fiyat >= 1) || fiyat > 100_000_000) { p.sendMessage(ChatColor.RED + "Başlangıç fiyatı $1 ile $100.000.000 arasında olmalı."); return; }
        int min = plugin.getConfig().getInt("mezat.min-sure-dakika", 2), max = plugin.getConfig().getInt("mezat.max-sure-dakika", 60);
        int dk = plugin.getConfig().getInt("mezat.varsayilan-sure-dakika", 10);
        if (a.length >= 3) try { dk = Integer.parseInt(a[2]); } catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Geçersiz süre."); return; }
        if (dk < min || dk > max) { p.sendMessage(ChatColor.RED + "Süre " + min + " ile " + max + " dakika arasında olmalı."); return; }
        int limit = plugin.getConfig().getInt("mezat.oyuncu-limit", 3);
        int sayi = 0;
        for (Mezat m : aktif.values()) if (m.satici.equals(p.getUniqueId())) sayi++;
        if (sayi >= limit) { p.sendMessage(ChatColor.RED + "Aynı anda en fazla " + limit + " mezatın olabilir."); return; }

        Mezat m = new Mezat();
        m.id = ++sonId; m.satici = p.getUniqueId(); m.saticiAd = p.getName();
        m.esya = el.clone();
        m.baslangic = fiyat; m.teklif = 0; m.lider = null;
        m.bitis = System.currentTimeMillis() + dk * 60_000L;
        p.getInventory().setItemInMainHand(null); // eşya oyuncudan alınır, mezat.yml'de güvende
        aktif.put(m.id, m);
        kaydet();
        plugin.ekonomiLog("mezat-ac", p.getName(), "#" + m.id + " " + m.esya.getAmount() + "x " + m.esya.getType() + " baslangic " + para(fiyat) + " " + dk + "dk");
        p.sendMessage(ChatColor.GREEN + "Mezat açıldı (#" + m.id + "): " + m.esya.getAmount() + "x " + m.esya.getType() + ", başlangıç " + para(fiyat) + ", " + dk + " dk.");
        Bukkit.broadcastMessage(ChatColor.GOLD + "[Mezat] " + ChatColor.YELLOW + p.getName() + ChatColor.GRAY + " mezata yeni bir eşya koydu: "
                + ChatColor.WHITE + m.esya.getAmount() + "x " + ChatColor.stripColor(adi(m.esya)) + ChatColor.GRAY + " (#" + m.id + ")");
    }

    private String adi(ItemStack it) {
        ItemMeta meta = it.getItemMeta();
        if (meta != null && meta.hasDisplayName()) return meta.getDisplayName();
        return it.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ------------------------------------------------------------------ teklif
    private void teklifKomut(Player p, String[] a) {
        if (a.length < 3) { p.sendMessage(ChatColor.RED + "Kullanım: /mezat teklif <id> <tutar>"); return; }
        try {
            Mezat m = aktif.get(Integer.parseInt(a[1]));
            if (m == null) { p.sendMessage(ChatColor.RED + "Böyle bir mezat yok."); return; }
            teklifVer(p, m, yuvarla(Double.parseDouble(a[2].replace(',', '.'))));
        } catch (NumberFormatException e) { p.sendMessage(ChatColor.RED + "Geçersiz sayı."); }
    }

    private void teklifVer(Player p, Mezat m, double tutar) {
        long simdi = System.currentTimeMillis();
        if (m.bitis <= simdi) { p.sendMessage(ChatColor.RED + "Bu mezat bitti."); return; }
        if (m.satici.equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Kendi mezatına teklif veremezsin."); return; }
        double min = minTeklif(m);
        if (tutar < min) { p.sendMessage(ChatColor.RED + "En az " + para(min) + " teklif vermelisin."); return; }
        boolean zatenLider = p.getUniqueId().equals(m.lider);
        double gerekli = zatenLider ? tutar - m.teklif : tutar;
        double bakiye = plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
        if (bakiye < gerekli) { p.sendMessage(ChatColor.RED + "Banka hesabında yeterli para yok. Gereken: " + para(gerekli) + " (bakiye " + para(bakiye) + ")"); return; }

        UUID eskiLider = m.lider; double eskiTeklif = m.teklif;
        banka(p.getUniqueId(), -gerekli);
        if (eskiLider != null && !zatenLider) {
            banka(eskiLider, eskiTeklif); // bloke edilen para anında iade
            Player ep = Bukkit.getPlayer(eskiLider);
            if (ep != null) ep.sendMessage(ChatColor.GOLD + "[Mezat] " + ChatColor.RED + "#" + m.id + " numaralı mezattaki teklifin aşıldı. Paran hesabına iade edildi (" + para(eskiTeklif) + ").");
        }
        m.teklif = tutar; m.lider = p.getUniqueId(); m.liderAd = p.getName();
        long uzat = plugin.getConfig().getInt("mezat.son-saniye-uzatma", 30) * 1000L;
        if (m.bitis - simdi < uzat) m.bitis = simdi + uzat; // son saniye teklifi: mezat uzar
        plugin.veriKaydet();
        kaydet();
        plugin.ekonomiLog("mezat-teklif", p.getName(), "#" + m.id + " " + para(tutar) + (eskiLider != null ? " (önceki " + para(eskiTeklif) + ")" : ""));
        p.sendMessage(ChatColor.GREEN + "Teklif verildi: " + para(tutar) + " (#" + m.id + ")");
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
        Player sp = Bukkit.getPlayer(m.satici);
        if (sp != null) sp.sendMessage(ChatColor.GOLD + "[Mezat] " + ChatColor.GRAY + "#" + m.id + " için yeni teklif: " + ChatColor.WHITE + para(tutar));
    }

    // ------------------------------------------------------------------ bitiş
    private void tick() {
        long simdi = System.currentTimeMillis();
        List<Mezat> biten = new ArrayList<>();
        for (Mezat m : aktif.values()) if (m.bitis <= simdi) biten.add(m);
        for (Mezat m : biten) bitir(m);
        // açık menüleri tazele (kalan süre)
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Menu mn) menuDoldur(p, mn);
        }
    }

    private void bitir(Mezat m) {
        aktif.remove(m.id);
        if (m.lider == null) {
            teslimatEkle(m.satici, m.esya);
            plugin.ekonomiLog("mezat-sonuc", m.saticiAd, "#" + m.id + " teklif yok, eşya iade");
            Player sp = Bukkit.getPlayer(m.satici);
            if (sp != null) sp.sendMessage(ChatColor.GOLD + "[Mezat] " + ChatColor.GRAY + "#" + m.id + " numaralı mezatına teklif gelmedi; eşyan teslimat kuyruğuna alındı.");
        } else {
            double kom = yuvarla(m.teklif * komisyon());
            double net = yuvarla(m.teklif - kom);
            banka(m.satici, net);
            if (kom > 0) plugin.kasayaParaEkle(kom);
            teslimatEkle(m.lider, m.esya);
            plugin.veriKaydet();
            plugin.ekonomiLog("mezat-sonuc", m.saticiAd, "#" + m.id + " " + m.esya.getAmount() + "x " + m.esya.getType() + " -> " + m.liderAd + " " + para(m.teklif) + " (komisyon " + para(kom) + ")");
            Player sp = Bukkit.getPlayer(m.satici);
            if (sp != null) sp.sendMessage(ChatColor.GOLD + "[Mezat] " + ChatColor.GREEN + "#" + m.id + " satıldı: " + para(m.teklif) + " (komisyon " + para(kom) + "), hesabına " + para(net) + " yattı.");
            Player lp = Bukkit.getPlayer(m.lider);
            if (lp != null) lp.sendMessage(ChatColor.GOLD + "[Mezat] " + ChatColor.GREEN + "#" + m.id + " mezatını kazandın! Eşyan teslim ediliyor.");
            if (plugin.haberManager != null && m.teklif >= 1000)
                plugin.haberManager.yaz("MEZAT", "Mezatta " + para(m.teklif) + "'lık satış", m.liderAd + ", " + m.saticiAd + "'ın mezata koyduğu " + m.esya.getAmount() + "x " + ChatColor.stripColor(adi(m.esya)) + " eşyasını " + para(m.teklif) + " karşılığında kazandı.");
        }
        kaydet();
        teslimEt(Bukkit.getPlayer(m.lider != null ? m.lider : m.satici), false);
        if (m.lider != null) teslimEt(Bukkit.getPlayer(m.satici), false);
    }

    private void adminIptal(Player admin, int id) {
        Mezat m = aktif.remove(id);
        if (m == null) { admin.sendMessage(ChatColor.RED + "Böyle bir mezat yok."); return; }
        if (m.lider != null) banka(m.lider, m.teklif);
        teslimatEkle(m.satici, m.esya);
        plugin.veriKaydet(); kaydet();
        plugin.ekonomiLog("mezat-iptal", admin.getName(), "#" + id);
        admin.sendMessage(ChatColor.GREEN + "Mezat iptal edildi; eşya satıcıya, teklif lidere iade edildi.");
    }

    // ------------------------------------------------------------------ teslimat
    private void teslimatEkle(UUID id, ItemStack it) {
        teslimat.computeIfAbsent(id, k -> new ArrayList<>()).add(it.clone());
    }

    private void teslimEt(Player p, boolean mesaj) {
        if (p == null) return;
        List<ItemStack> l = teslimat.get(p.getUniqueId());
        if (l == null || l.isEmpty()) { if (mesaj) p.sendMessage(ChatColor.GRAY + "Bekleyen teslimatın yok."); return; }
        List<ItemStack> kalan = new ArrayList<>();
        int verilen = 0;
        for (ItemStack it : l) {
            Map<Integer, ItemStack> artan = p.getInventory().addItem(it.clone());
            if (artan.isEmpty()) verilen++; else kalan.addAll(artan.values());
        }
        if (kalan.isEmpty()) teslimat.remove(p.getUniqueId()); else teslimat.put(p.getUniqueId(), kalan);
        kaydet();
        if (verilen > 0) p.sendMessage(ChatColor.GOLD + "[Mezat] " + ChatColor.GREEN + verilen + " teslimat envanterine eklendi.");
        if (!kalan.isEmpty()) p.sendMessage(ChatColor.GOLD + "[Mezat] " + ChatColor.RED + "Envanterin dolu; " + kalan.size() + " teslimat bekliyor (yer açıp /mezat teslim).");
    }

    @EventHandler
    public void onGiris(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (teslimat.containsKey(p.getUniqueId()))
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) { p.sendMessage(ChatColor.GOLD + "[Mezat] " + ChatColor.YELLOW + "Bekleyen mezat teslimatın var."); teslimEt(p, false); }
            }, 100L);
    }

    // ------------------------------------------------------------------ NPC + menü
    @EventHandler
    public void onNpcTik(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Villager v) || !v.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        menuAc(e.getPlayer(), 0);
    }

    private void menuAc(Player p, int sayfa) {
        Menu mn = new Menu();
        mn.sayfa = sayfa;
        Inventory inv = Bukkit.createInventory(mn, 54, ChatColor.GOLD + "Mezat");
        mn.inv = inv;
        menuDoldur(p, mn);
        p.openInventory(inv);
    }

    private void menuDoldur(Player p, Menu mn) {
        Inventory inv = mn.inv;
        inv.clear();
        List<Mezat> liste = new ArrayList<>(aktif.values());
        int sayfaSayi = Math.max(1, (liste.size() + 44) / 45);
        if (mn.sayfa >= sayfaSayi) mn.sayfa = sayfaSayi - 1;
        long simdi = System.currentTimeMillis();
        for (int i = 0; i < 45; i++) {
            int idx = mn.sayfa * 45 + i;
            if (idx >= liste.size()) break;
            Mezat m = liste.get(idx);
            ItemStack g = m.esya.clone();
            ItemMeta meta = g.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(ChatColor.DARK_GRAY + "----------------------");
            lore.add(ChatColor.GRAY + "Mezat #" + m.id + "  Satıcı: " + ChatColor.WHITE + m.saticiAd);
            lore.add(ChatColor.GRAY + "Başlangıç: " + ChatColor.WHITE + para(m.baslangic));
            lore.add(m.lider == null ? ChatColor.GRAY + "Teklif yok" : ChatColor.GRAY + "Güncel: " + ChatColor.GREEN + para(m.teklif) + ChatColor.GRAY + " (" + m.liderAd + ")");
            lore.add(ChatColor.GRAY + "Kalan: " + ChatColor.YELLOW + sureYaz(m.bitis - simdi));
            lore.add("");
            lore.add(ChatColor.YELLOW + "Sol tık: " + para(minTeklif(m)) + " teklif ver");
            lore.add(ChatColor.YELLOW + "Sağ tık: " + para(yuvarla(minTeklif(m) * 1.25)) + " teklif ver");
            meta.setLore(lore);
            g.setItemMeta(meta);
            inv.setItem(i, g);
        }
        inv.setItem(45, oge(Material.ARROW, ChatColor.YELLOW + "Önceki sayfa"));
        inv.setItem(53, oge(Material.ARROW, ChatColor.YELLOW + "Sonraki sayfa"));
        int bekleyen = teslimat.getOrDefault(p.getUniqueId(), new ArrayList<>()).size();
        inv.setItem(48, oge(Material.CHEST, ChatColor.GREEN + "Teslimatı Al", ChatColor.GRAY + "Bekleyen: " + bekleyen));
        inv.setItem(49, oge(Material.PAPER, ChatColor.GOLD + "Mezat (" + (mn.sayfa + 1) + "/" + sayfaSayi + ")",
                ChatColor.GRAY + "Eşya satmak için Mezatçı'nın yanında:", ChatColor.YELLOW + "/mezat sat <fiyat> [dk]",
                ChatColor.GRAY + "Teklifler banka hesabından bloke edilir.", ChatColor.GRAY + "Komisyon: %" + (long) (komisyon() * 100)));
        double bakiye = plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
        inv.setItem(50, oge(Material.GOLD_INGOT, ChatColor.GOLD + "Banka bakiyen", ChatColor.WHITE + para(bakiye)));
    }

    private static ItemStack oge(Material m, String ad, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        if (lore.length > 0) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(s); meta.setLore(l); }
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onTikla(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu mn)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        if (slot == 45) { if (mn.sayfa > 0) { mn.sayfa--; menuDoldur(p, mn); } return; }
        if (slot == 53) { mn.sayfa++; menuDoldur(p, mn); return; }
        if (slot == 48) { teslimEt(p, true); menuDoldur(p, mn); return; }
        if (slot >= 45) return;
        List<Mezat> liste = new ArrayList<>(aktif.values());
        int idx = mn.sayfa * 45 + slot;
        if (idx >= liste.size()) return;
        Mezat m = liste.get(idx);
        double tutar = e.isRightClick() ? yuvarla(minTeklif(m) * 1.25) : minTeklif(m);
        teklifVer(p, m, tutar);
        menuDoldur(p, mn);
    }

    @EventHandler
    public void onSurukle(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
