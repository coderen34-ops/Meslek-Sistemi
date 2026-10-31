package me.mesleksistemi.haber;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import me.mesleksistemi.MeslekSistemi;

/**
 * Gazete / haber sistemi: sunucudaki önemli olaylar (kale fethi, kervan yağması, NPC klanı ittifak/savaş, belediye,
 * mezat, polis baskını...) haber olarak kaydedilir. Gazeteci NPC'sinden gazete (yazılı kitap) alınır, /haber ile
 * manşetler okunur, oyuncular ücret karşılığı kendi yazılarını (okur yazısı) yayınlayabilir.
 * Diğer eklentiler (KlanSistemi) haberi yansıma ile {@link #yaz(String, String, String)} üzerinden gönderir.
 */
public class HaberManager implements Listener, CommandExecutor {

    private static final int AZAMI = 150;

    private static final class Haber {
        int id; long zaman; String kat, baslik, metin, yazar;
    }

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final MeslekSistemi plugin;
    private final NamespacedKey npcKey, gazeteKey;
    private final File dosya;
    private final List<Haber> haberler = new ArrayList<>(); // en yeni sonda
    private final Map<UUID, Long> yaziBekleme = new HashMap<>();
    private int sonId = 0;
    private boolean kirli = false;

    public HaberManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "gazeteci_npc");
        this.gazeteKey = new NamespacedKey(plugin, "gazete");
        this.dosya = new File(plugin.getDataFolder(), "haberler.yml");
        plugin.getConfig().addDefault("haber.gazete-fiyat", 20.0);
        plugin.getConfig().addDefault("haber.yazi-ucreti", 200.0);
        plugin.getConfig().addDefault("haber.yazi-bekleme-dakika", 10);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
        yukle();
        new BukkitRunnable() {
            @Override public void run() { if (kirli) kaydet(); }
        }.runTaskTimer(plugin, 600L, 600L);
    }

    // ------------------------------------------------------------------ API
    /** Haber ekler (herhangi bir thread'den çağrılabilir). Kategori: SAVAS, TICARET, BELEDIYE, SUC, MEZAT, OKUR vb. */
    public synchronized void yaz(String kat, String baslik, String metin) { ekle(kat, baslik, metin, null); }

    private synchronized void ekle(String kat, String baslik, String metin, String yazar) {
        Haber h = new Haber();
        h.id = ++sonId; h.zaman = System.currentTimeMillis();
        h.kat = kat; h.baslik = temiz(baslik, 60); h.metin = temiz(metin, 400); h.yazar = yazar;
        haberler.add(h);
        while (haberler.size() > AZAMI) haberler.remove(0);
        kirli = true;
        if (plugin.getConfig().getBoolean("haber.sohbete-duyur", false)) {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(ChatColor.GOLD + "[Haber] " + ChatColor.YELLOW + h.baslik));
        }
    }

    private static String temiz(String s, int max) {
        if (s == null) return "";
        s = ChatColor.stripColor(s.replace('&', ' ').replace('§', ' ')).replace('\n', ' ').trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static String tarih(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("dd.MM HH:mm", new Locale("tr"));
        f.setTimeZone(TimeZone.getTimeZone("Europe/Istanbul"));
        return f.format(new Date(ms));
    }

    private static String katAd(String k) {
        switch (k == null ? "" : k) {
            case "SAVAS": return "Savaş";
            case "TICARET": return "Ticaret";
            case "BELEDIYE": return "Belediye";
            case "SUC": return "Asayiş";
            case "MEZAT": return "Mezat";
            case "OKUR": return "Okur Yazısı";
            default: return "Gündem";
        }
    }

    // ------------------------------------------------------------------ kalıcılık
    private synchronized void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("sonId", sonId);
        int i = 0;
        for (Haber h : haberler) {
            String p = "h" + (i++);
            y.set(p + ".id", h.id); y.set(p + ".zaman", h.zaman); y.set(p + ".kat", h.kat);
            y.set(p + ".baslik", h.baslik); y.set(p + ".metin", h.metin); y.set(p + ".yazar", h.yazar);
        }
        try { plugin.getDataFolder().mkdirs(); y.save(dosya); kirli = false; }
        catch (Exception e) { plugin.getLogger().warning("Haberler yazılamadı: " + e.getMessage()); }
    }

    private void yukle() {
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        sonId = y.getInt("sonId", 0);
        for (String p : y.getKeys(false)) {
            if (!p.startsWith("h")) continue;
            Haber h = new Haber();
            h.id = y.getInt(p + ".id"); h.zaman = y.getLong(p + ".zaman"); h.kat = y.getString(p + ".kat", "");
            h.baslik = y.getString(p + ".baslik", ""); h.metin = y.getString(p + ".metin", ""); h.yazar = y.getString(p + ".yazar");
            haberler.add(h);
        }
    }

    public void kapat() { kaydet(); }

    // ------------------------------------------------------------------ komut
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String alt = a.length == 0 ? "son" : a[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "son":
            case "liste": {
                List<Haber> son = sonHaberler(5);
                if (son.isEmpty()) { s.sendMessage(ChatColor.GRAY + "Henüz haber yok."); return true; }
                s.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "— Son Manşetler —");
                for (Haber h : son) s.sendMessage(ChatColor.YELLOW + "• " + ChatColor.WHITE + h.baslik + ChatColor.DARK_GRAY + " (" + tarih(h.zaman) + ")");
                s.sendMessage(ChatColor.GRAY + "Haberlerin tamamı için Gazeteci'den gazete al. Kendi yazın için: /haber yaz <başlık> | <metin>");
                return true;
            }
            case "yaz": {
                if (!(s instanceof Player p)) return true;
                String govde = String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length));
                int bolum = govde.indexOf('|');
                if (bolum < 1 || bolum >= govde.length() - 1) { p.sendMessage(ChatColor.RED + "Kullanım: /haber yaz <başlık> | <metin>"); return true; }
                String baslik = govde.substring(0, bolum).trim(), metin = govde.substring(bolum + 1).trim();
                if (baslik.length() < 3 || metin.length() < 5) { p.sendMessage(ChatColor.RED + "Başlık ve metin çok kısa."); return true; }
                if (baslik.length() > 60 || metin.length() > 200) { p.sendMessage(ChatColor.RED + "Başlık en fazla 60, metin en fazla 200 karakter olabilir."); return true; }
                long bekle = plugin.getConfig().getInt("haber.yazi-bekleme-dakika", 10) * 60_000L;
                Long son = yaziBekleme.get(p.getUniqueId());
                if (son != null && System.currentTimeMillis() - son < bekle) { p.sendMessage(ChatColor.RED + "Yeni yazı için biraz beklemelisin."); return true; }
                double ucret = plugin.getConfig().getDouble("haber.yazi-ucreti", 200.0);
                if (!plugin.processPaymentToKasa(p, ucret)) return true;
                yaziBekleme.put(p.getUniqueId(), System.currentTimeMillis());
                ekle("OKUR", baslik, metin, p.getName());
                plugin.ekonomiLog("haber-yazi", p.getName(), baslik + " | $" + (long) ucret);
                p.sendMessage(ChatColor.GREEN + "Yazın gazeteye gönderildi. (Ücret: $" + (long) ucret + ")");
                return true;
            }
            case "sil": {
                if (!s.hasPermission("meslek.admin")) { s.sendMessage(ChatColor.RED + "Yetkin yok."); return true; }
                if (a.length < 2) { s.sendMessage(ChatColor.RED + "/haber sil <id>"); return true; }
                try {
                    int id = Integer.parseInt(a[1]);
                    boolean silindi;
                    synchronized (this) { silindi = haberler.removeIf(h -> h.id == id); if (silindi) kirli = true; }
                    s.sendMessage(silindi ? ChatColor.GREEN + "Haber silindi." : ChatColor.RED + "Bulunamadı.");
                } catch (NumberFormatException e) { s.sendMessage(ChatColor.RED + "Geçersiz id."); }
                return true;
            }
            case "idler": {
                if (!s.hasPermission("meslek.admin")) { s.sendMessage(ChatColor.RED + "Yetkin yok."); return true; }
                for (Haber h : sonHaberler(10)) s.sendMessage(ChatColor.GRAY + "#" + h.id + " " + h.baslik);
                return true;
            }
            case "npc": {
                if (!(s instanceof Player p) || !p.hasPermission("meslek.admin")) { s.sendMessage(ChatColor.RED + "Yetkin yok."); return true; }
                if (a.length < 2) { p.sendMessage(ChatColor.RED + "/haber npc kur|sil"); return true; }
                if (a[1].equalsIgnoreCase("kur")) {
                    Villager v = (Villager) p.getWorld().spawnEntity(p.getLocation(), EntityType.VILLAGER);
                    v.setAI(false); v.setInvulnerable(true); v.setCollidable(false);
                    v.setProfession(Villager.Profession.CARTOGRAPHER);
                    v.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Gazeteci");
                    v.setCustomNameVisible(true);
                    v.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
                    p.sendMessage(ChatColor.GREEN + "Gazeteci NPC'si kuruldu.");
                } else if (a[1].equalsIgnoreCase("sil")) {
                    int n = 0;
                    for (Entity e : p.getNearbyEntities(5, 5, 5))
                        if (e instanceof Villager && e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) { e.remove(); n++; }
                    p.sendMessage(n > 0 ? ChatColor.GREEN + "" + n + " Gazeteci silindi." : ChatColor.RED + "Yakında Gazeteci yok.");
                }
                return true;
            }
            default:
                s.sendMessage(ChatColor.YELLOW + "/haber [son] | /haber yaz <başlık> | <metin> | /haber npc kur|sil (admin) | /haber sil <id> (admin)");
                return true;
        }
    }

    private synchronized List<Haber> sonHaberler(int n) {
        List<Haber> r = new ArrayList<>();
        for (int i = haberler.size() - 1; i >= 0 && r.size() < n; i--) r.add(haberler.get(i));
        return r;
    }

    // ------------------------------------------------------------------ gazeteci NPC + menü
    @EventHandler
    public void onNpcTik(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Villager v) || !v.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        menuAc(e.getPlayer());
    }

    private void menuAc(Player p) {
        Menu h = new Menu();
        Inventory inv = Bukkit.createInventory(h, 27, ChatColor.GOLD + "Gazeteci");
        h.inv = inv;
        List<String> lore = new ArrayList<>();
        for (Haber hb : sonHaberler(5)) lore.add(ChatColor.YELLOW + "• " + ChatColor.WHITE + hb.baslik);
        if (lore.isEmpty()) lore.add(ChatColor.GRAY + "Henüz haber yok.");
        inv.setItem(11, oge(Material.PAPER, ChatColor.YELLOW + "Son Manşetler", lore));
        inv.setItem(13, oge(Material.WRITTEN_BOOK, ChatColor.GOLD + "Gazete Satın Al",
                List.of(ChatColor.GREEN + "$" + (long) plugin.getConfig().getDouble("haber.gazete-fiyat", 20.0),
                        ChatColor.GRAY + "Son 15 haberin tamamı", ChatColor.GRAY + "Tıkla: satın al")));
        inv.setItem(15, oge(Material.WRITABLE_BOOK, ChatColor.AQUA + "Yazı Yayınla",
                List.of(ChatColor.GREEN + "$" + (long) plugin.getConfig().getDouble("haber.yazi-ucreti", 200.0),
                        ChatColor.GRAY + "/haber yaz <başlık> | <metin>", ChatColor.DARK_GRAY + "Okur yazısı olarak gazetede çıkar.")));
        p.openInventory(inv);
    }

    private static ItemStack oge(Material m, String ad, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad); meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onTikla(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot == 11) { p.closeInventory(); p.performCommand("haber son"); }
        else if (slot == 13) gazeteSat(p);
        else if (slot == 15) { p.closeInventory(); p.sendMessage(ChatColor.AQUA + "Yazmak için: /haber yaz <başlık> | <metin>  ücret: $" + (long) plugin.getConfig().getDouble("haber.yazi-ucreti", 200.0)); }
    }

    @EventHandler
    public void onSurukle(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    private void gazeteSat(Player p) {
        List<Haber> son = sonHaberler(15);
        if (son.isEmpty()) { p.sendMessage(ChatColor.RED + "Basılacak haber yok."); return; }
        double fiyat = plugin.getConfig().getDouble("haber.gazete-fiyat", 20.0);
        if (!plugin.processPaymentToKasa(p, fiyat)) return;
        ItemStack kitap = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta bm = (BookMeta) kitap.getItemMeta();
        bm.setTitle("Koninski Gazetesi");
        bm.setAuthor("Gazeteci");
        List<String> sayfalar = new ArrayList<>();
        sayfalar.add(ChatColor.BOLD + "KONİNSKİ GAZETESİ\n" + ChatColor.RESET + ChatColor.DARK_GRAY + tarih(System.currentTimeMillis()) + "\n\n" + ChatColor.BLACK + son.size() + " haber");
        for (Haber h : son) {
            String s = ChatColor.BOLD + h.baslik + ChatColor.RESET + "\n" + ChatColor.BLACK + h.metin + "\n\n" + ChatColor.DARK_GRAY + katAd(h.kat) + " • " + tarih(h.zaman)
                    + (h.yazar != null ? "\n" + h.yazar : "");
            sayfalar.add(s.length() > 255 ? s.substring(0, 254) : s);
        }
        bm.setPages(sayfalar);
        bm.getPersistentDataContainer().set(gazeteKey, PersistentDataType.BYTE, (byte) 1);
        kitap.setItemMeta(bm);
        for (ItemStack artan : p.getInventory().addItem(kitap).values()) p.getWorld().dropItemNaturally(p.getLocation(), artan);
        plugin.ekonomiLog("gazete-satis", p.getName(), "$" + (long) fiyat);
        p.sendMessage(ChatColor.GREEN + "Gazeteni aldın. (Ücret: $" + (long) fiyat + ")");
        p.closeInventory();
    }
}
