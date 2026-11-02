package me.mesleksistemi.evlilik;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import me.mesleksistemi.MeslekSistemi;

/**
 * Rahip, kilise ve nikah sistemi.
 *
 *  - Rahip oyuncu mesleğidir. Evlenmek isteyen, rahibe sağ tıklayıp esIdini seçer; esIdi menüden kabul eder;
 *    rahip kendi panelinde çifti kiliseye çağırır ve nikahı kıyar. Teklifi yapan 10.000$ öder, para rahibe gider.
 *  - Kilise bir admin claim'idir; admin "Kilise Asası" ile claim içinde sağ tıklayarak orayı kilise yapar.
 *  - Nikah için kilisede çift, rahip ve en az N tanık bulunmalıdır.
 *  - Nikahtan sonra: alyans + nikah belgesi, çan/havai fişek/kalp, nota bloklu müzik, sunucu duyurusu,
 *    ortak claim yetkisi, esIdler yan yanayken kalp, sohbette kalp, bağış menüsü (15 dk), eş menüsü, boşanma.
 */
public class EvlilikManager implements Listener, CommandExecutor {

    private double ucret() { return Math.max(0, plugin.getConfig().getDouble("evlilik.ucret", 10000.0)); }
    private int minTanik() { return Math.max(0, plugin.getConfig().getInt("evlilik.min-tanik", 1)); }
    private double bosanmaUcreti() { return Math.max(0, plugin.getConfig().getDouble("evlilik.bosanma-ucreti", 5000.0)); }
    private long esTpBeklemeMs() { return Math.max(0L, plugin.getConfig().getLong("evlilik.es-tp-bekleme-sn", 600L)) * 1000L; }
    private static final long TEKLIF_OMUR_MS = 30L * 60_000L;
    private static final long BAGIS_SURE_MS = 15L * 60_000L;

    // ------------------------------------------------------------------ veri
    private static final class Evlilik {
        UUID a, b; String adA, adB; long ms; String rahip; String no;
        UUID diger(UUID x) { return x.equals(a) ? b : a; }
        String digerAd(UUID x) { return x.equals(a) ? adB : adA; }
    }

    private static final class Teklif {
        UUID a, b, rahip; String adA, adB, adR; int asama; long ms;
    }

    private static final class Dugun {
        UUID a, b; long bitis;
    }

    private enum Tip { ANA, SEC_ES, ES_ONAY, RAHIP, EVLILIK, BOSANMA, BAGIS, BAGIS_ESYA }

    private static final class Menu implements InventoryHolder {
        final Tip tip; UUID hedef; Inventory inv;
        final UUID[] kisi = new UUID[54];
        Menu(Tip tip) { this.tip = tip; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final MeslekSistemi plugin;
    private final File dosya;
    private final Map<UUID, Evlilik> evlilikler = new HashMap<>();      // oyuncu -> evlilik (iki giriş)
    private final Map<UUID, Teklif> teklifler = new LinkedHashMap<>();  // teklif edenin id'si -> teklif
    private final Map<UUID, Dugun> dugunler = new HashMap<>();
    private final Map<UUID, Long> esTpSon = new HashMap<>();
    private final Map<String, BukkitTask> muzikler = new HashMap<>();
    private final NamespacedKey asaKey, alyansKey;
    private long kiliseClaim = -1;
    private String kiliseDunya; private double kx, ky, kz; private float kyaw, kpitch;
    private int belgeSayac = 1000;

    public EvlilikManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "evlilikler.yml");
        this.asaKey = new NamespacedKey(plugin, "kilise_asa");
        this.alyansKey = new NamespacedKey(plugin, "alyans");
        yukle();
        // Eşler yan yanayken kalp
        Bukkit.getScheduler().runTaskTimer(plugin, this::kalpler, 60L, 60L);
        // Ortak claim yetkilerini 5 dakikada bir tazele (sonradan alınan claim'ler için)
        Bukkit.getScheduler().runTaskTimer(plugin, this::tumYetkiler, 20L * 120, 20L * 300);
    }

    public void kapat() {
        kaydet();
        for (BukkitTask t : muzikler.values()) t.cancel();
        muzikler.clear();
    }

    // ------------------------------------------------------------------ kayıt
    private void yukle() {
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        kiliseClaim = y.getLong("kilise.claim", -1);
        kiliseDunya = y.getString("kilise.dunya");
        kx = y.getDouble("kilise.x"); ky = y.getDouble("kilise.y"); kz = y.getDouble("kilise.z");
        kyaw = (float) y.getDouble("kilise.yaw"); kpitch = (float) y.getDouble("kilise.pitch");
        belgeSayac = y.getInt("belge-sayac", 1000);
        ConfigurationSection s = y.getConfigurationSection("evlilik");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                ConfigurationSection c = s.getConfigurationSection(k);
                if (c == null) continue;
                Evlilik e = new Evlilik();
                e.a = UUID.fromString(c.getString("a")); e.b = UUID.fromString(c.getString("b"));
                e.adA = c.getString("adA", "?"); e.adB = c.getString("adB", "?");
                e.ms = c.getLong("ms"); e.rahip = c.getString("rahip", "?"); e.no = c.getString("no", "-");
                evlilikler.put(e.a, e); evlilikler.put(e.b, e);
            } catch (Exception ignored) { }
        }
    }

    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("kilise.claim", kiliseClaim);
        y.set("kilise.dunya", kiliseDunya);
        y.set("kilise.x", kx); y.set("kilise.y", ky); y.set("kilise.z", kz);
        y.set("kilise.yaw", (double) kyaw); y.set("kilise.pitch", (double) kpitch);
        y.set("belge-sayac", belgeSayac);
        int i = 0;
        for (Evlilik e : benzersiz()) {
            String p = "evlilik." + (i++);
            y.set(p + ".a", e.a.toString()); y.set(p + ".b", e.b.toString());
            y.set(p + ".adA", e.adA); y.set(p + ".adB", e.adB);
            y.set(p + ".ms", e.ms); y.set(p + ".rahip", e.rahip); y.set(p + ".no", e.no);
        }
        try { dosya.getParentFile().mkdirs(); y.save(dosya); }
        catch (Exception ex) { plugin.getLogger().warning("[Evlilik] evlilikler.yml yazılamadı: " + ex.getMessage()); }
    }

    private List<Evlilik> benzersiz() {
        List<Evlilik> l = new ArrayList<>();
        for (Evlilik e : evlilikler.values()) if (!l.contains(e)) l.add(e);
        return l;
    }

    // ------------------------------------------------------------------ yardımcılar
    /** Sohbet vb. için: oyuncunun esIdinin adı, evli değilse null. */
    public String esAdi(UUID id) {
        Evlilik e = evlilikler.get(id);
        return e == null ? null : e.digerAd(id);
    }

    private String meslekOf(UUID id) { return plugin.oyuncuMeslekCache.getOrDefault(id, "vatandas"); }
    private boolean rahipMi(UUID id) { return meslekOf(id).equalsIgnoreCase("rahip"); }
    private boolean mahkumMu(UUID id) { return meslekOf(id).equalsIgnoreCase("mahkum"); }
    private static String para(double d) { return "$" + String.format(Locale.US, "%,.0f", d); }
    private double bakiye(UUID id) { return plugin.bankaHesaplari.getOrDefault(id, 0.0); }
    private boolean paraAl(UUID id, double m) {
        double b = bakiye(id);
        if (b + 0.0001 < m) return false;
        plugin.bankaHesaplari.put(id, Math.round((b - m) * 100.0) / 100.0);
        return true;
    }
    private void paraVer(UUID id, double m) {
        plugin.bankaHesaplari.put(id, Math.round((bakiye(id) + m) * 100.0) / 100.0);
    }

    private static ItemStack esya(Material m, String ad, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        if (lore.length > 0) {
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add(s);
            meta.setLore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private me.ryanhamshire.GriefPrevention.Claim claimAt(Location l) {
        try { return me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.getClaimAt(l, false, null); }
        catch (Throwable t) { return null; }
    }

    private boolean kiliseAyarli() { return kiliseClaim >= 0 && kiliseDunya != null && Bukkit.getWorld(kiliseDunya) != null; }

    private boolean kilisedeMi(Player p) {
        if (kiliseClaim < 0) return false;
        me.ryanhamshire.GriefPrevention.Claim c = claimAt(p.getLocation());
        if (c == null) return false;
        Long id = c.getID();
        return id != null && id.longValue() == kiliseClaim;
    }

    private Location kiliseNokta() {
        return new Location(Bukkit.getWorld(kiliseDunya), kx, ky, kz, kyaw, kpitch);
    }

    private Component tikla(String yazi, String komut) {
        return Component.text(yazi, NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand(komut));
    }

    private Teklif gelenTeklif(UUID b) {
        long simdi = System.currentTimeMillis();
        teklifler.values().removeIf(t -> simdi - t.ms > TEKLIF_OMUR_MS);
        for (Teklif t : teklifler.values()) if (t.b.equals(b) && t.asama == 0) return t;
        return null;
    }

    private Teklif teklifOf(UUID a) { return teklifler.get(a); }

    // ------------------------------------------------------------------ komutlar
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        if (c.equals("nikahmuzik")) { muzikKomut(sender, args); return true; }
        if (!(sender instanceof Player p)) { sender.sendMessage("Bu komut oyunculara özeldir."); return true; }
        if (c.equals("kilise")) { kiliseKomut(p, args); return true; }
        // /evlilik
        if (args.length > 0 && args[0].equalsIgnoreCase("gel")) { kiliseyeGit(p); return true; }
        anaMenu(p);
        return true;
    }

    private void kiliseKomut(Player p, String[] args) {
        if (!p.hasPermission("meslek.admin")) { p.sendMessage(ChatColor.RED + "Yetkin yok."); return; }
        String alt = args.length == 0 ? "bilgi" : args[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "ver": {
                ItemStack asa = esya(Material.BLAZE_ROD, ChatColor.GOLD + "" + ChatColor.BOLD + "Kilise Asası",
                        ChatColor.GRAY + "Bir ADMIN claim'inin içinde sağ tıkla:",
                        ChatColor.GRAY + "o claim kilise olur, durduğun nokta",
                        ChatColor.GRAY + "nikah ışınlanma noktası olarak kaydedilir.");
                ItemMeta m = asa.getItemMeta();
                m.getPersistentDataContainer().set(asaKey, PersistentDataType.BYTE, (byte) 1);
                try { m.setItemModel(new NamespacedKey("koninski", "esya/kilise_asa")); } catch (Throwable ignored) { }
                asa.setItemMeta(m);
                p.getInventory().addItem(asa);
                p.sendMessage(ChatColor.GOLD + "Kilise Asası verildi.");
                break;
            }
            case "kaldir":
                kiliseClaim = -1; kiliseDunya = null; kaydet();
                p.sendMessage(ChatColor.YELLOW + "Kilise kaydı kaldırıldı.");
                break;
            default:
                if (kiliseAyarli()) p.sendMessage(ChatColor.GOLD + "Kilise: claim #" + kiliseClaim + " (" + kiliseDunya + " "
                        + (int) kx + ", " + (int) ky + ", " + (int) kz + ")");
                else p.sendMessage(ChatColor.RED + "Kilise ayarlı değil. /kilise ver ile asayı al, admin claim'inde sağ tıkla.");
        }
    }

    @EventHandler
    public void onAsa(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null || !it.hasItemMeta() || !it.getItemMeta().getPersistentDataContainer().has(asaKey, PersistentDataType.BYTE)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("meslek.admin")) return;
        me.ryanhamshire.GriefPrevention.Claim c = claimAt(e.getClickedBlock().getLocation());
        if (c == null || !c.isAdminClaim()) { p.sendMessage(ChatColor.RED + "Burası bir ADMIN claim'i değil."); return; }
        kiliseClaim = c.getID();
        Location l = p.getLocation();
        kiliseDunya = l.getWorld().getName();
        kx = l.getX(); ky = l.getY(); kz = l.getZ(); kyaw = l.getYaw(); kpitch = l.getPitch();
        kaydet();
        p.sendMessage(ChatColor.GOLD + "Burası artık KİLİSE (claim #" + kiliseClaim + "). Işınlanma noktası bulunduğun yer.");
    }

    // ------------------------------------------------------------------ menüler
    private void ac(Player p, Menu m, int boyut, String baslik) {
        m.inv = Bukkit.createInventory(m, boyut, baslik);
    }

    private void anaMenu(Player p) {
        Menu m = new Menu(Tip.ANA);
        ac(p, m, 27, ChatColor.GOLD + "Evlilik");
        Teklif gelen = gelenTeklif(p.getUniqueId());
        if (gelen != null) m.inv.setItem(10, esya(Material.PAPER, ChatColor.GREEN + "Evlilik teklifi var!",
                ChatColor.GRAY + gelen.adA + " seninle evlenmek istiyor.", ChatColor.YELLOW + "Cevaplamak için tıkla"));
        else m.inv.setItem(10, esya(Material.GRAY_DYE, ChatColor.GRAY + "Gelen teklif yok"));
        if (rahipMi(p.getUniqueId())) m.inv.setItem(12, esya(Material.BELL, ChatColor.GOLD + "Rahip Paneli",
                ChatColor.GRAY + "Bekleyen çiftler ve nikah"));
        Evlilik ev = evlilikler.get(p.getUniqueId());
        if (ev != null) m.inv.setItem(14, esya(Material.RED_DYE, ChatColor.RED + "♥ Evliliğim", ChatColor.GRAY + "Eşin: " + ev.digerAd(p.getUniqueId())));
        else m.inv.setItem(14, esya(Material.BOOK, ChatColor.AQUA + "Evli değilsin",
                ChatColor.GRAY + "Evlenmek için bir rahibe sağ tıkla."));
        m.inv.setItem(16, esya(Material.GOLD_INGOT, ChatColor.YELLOW + "Çifte bağış", ChatColor.GRAY + "Nikahtaki çifte para/esIdya bağışla"));
        p.openInventory(m.inv);
    }

    private void esSecMenu(Player p, Player rahip) {
        Menu m = new Menu(Tip.SEC_ES);
        m.hedef = rahip.getUniqueId();
        ac(p, m, 54, ChatColor.GOLD + "Kiminle evlenmek istiyorsun?");
        int s = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (s >= 45) break;
            if (o.equals(p) || evlilikler.containsKey(o.getUniqueId()) || o.equals(rahip)) continue;
            ItemStack baş = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) baş.getItemMeta();
            sm.setOwningPlayer(o);
            sm.setDisplayName(ChatColor.WHITE + o.getName());
            sm.setLore(List.of(ChatColor.GRAY + "Evlenme teklifi gönder"));
            baş.setItemMeta(sm);
            m.inv.setItem(s, baş);
            m.kisi[s] = o.getUniqueId();
            s++;
        }
        if (s == 0) m.inv.setItem(22, esya(Material.BARRIER, ChatColor.RED + "Evlenebilecek çevrimiçi oyuncu yok"));
        p.openInventory(m.inv);
    }

    private void esOnayMenu(Player p, Teklif t) {
        Menu m = new Menu(Tip.ES_ONAY);
        ac(p, m, 27, ChatColor.GOLD + "Evlilik teklifi");
        m.inv.setItem(4, esya(Material.PLAYER_HEAD, ChatColor.WHITE + t.adA, ChatColor.GRAY + "seninle evlenmek istiyor", ChatColor.GRAY + "Rahip: " + t.adR));
        m.inv.setItem(11, esya(Material.LIME_CONCRETE, ChatColor.GREEN + "Kabul et"));
        m.inv.setItem(15, esya(Material.RED_CONCRETE, ChatColor.RED + "Reddet"));
        p.openInventory(m.inv);
    }

    private void rahipMenu(Player p) {
        Menu m = new Menu(Tip.RAHIP);
        ac(p, m, 27, ChatColor.GOLD + "Rahip Paneli");
        int s = 0;
        for (Teklif t : teklifler.values()) {
            if (s >= 18) break;
            if (t.asama != 1 || !t.rahip.equals(p.getUniqueId())) continue;
            Player a = Bukkit.getPlayer(t.a), b = Bukkit.getPlayer(t.b);
            boolean ak = a != null && kilisedeMi(a), bk = b != null && kilisedeMi(b);
            m.inv.setItem(s, esya(Material.PLAYER_HEAD, ChatColor.GOLD + t.adA + ChatColor.RED + " ♥ " + ChatColor.GOLD + t.adB,
                    (a != null ? ChatColor.GREEN : ChatColor.RED) + t.adA + (ak ? " (kilisede)" : a != null ? " (dışarıda)" : " (çevrimdışı)"),
                    (b != null ? ChatColor.GREEN : ChatColor.RED) + t.adB + (bk ? " (kilisede)" : b != null ? " (dışarıda)" : " (çevrimdışı)"),
                    ChatColor.YELLOW + "Sol tık: çifti kiliseye çağır",
                    ChatColor.GREEN + "Sağ tık: nikahı kıy (" + para(ucret()) + ")",
                    ChatColor.RED + "Shift+tık: teklifi iptal et"));
            m.kisi[s] = t.a;
            s++;
        }
        if (s == 0) m.inv.setItem(13, esya(Material.BARRIER, ChatColor.GRAY + "Bekleyen çift yok"));
        m.inv.setItem(22, esya(Material.ENDER_PEARL, ChatColor.AQUA + "Kiliseye git"));
        p.openInventory(m.inv);
    }

    private void evlilikMenu(Player p) {
        Evlilik ev = evlilikler.get(p.getUniqueId());
        if (ev == null) { p.sendMessage(ChatColor.RED + "Evli değilsin."); return; }
        Menu m = new Menu(Tip.EVLILIK);
        ac(p, m, 27, ChatColor.RED + "♥ Evliliğim");
        m.inv.setItem(4, esya(Material.PLAYER_HEAD, ChatColor.WHITE + ev.digerAd(p.getUniqueId()),
                ChatColor.GRAY + "Nikah: " + new java.text.SimpleDateFormat("dd.MM.yyyy").format(new java.util.Date(ev.ms)),
                ChatColor.GRAY + "Rahip: " + ev.rahip, ChatColor.GRAY + "Belge No: " + ev.no));
        m.inv.setItem(11, esya(Material.COMPASS, ChatColor.AQUA + "Eşim nerede?"));
        m.inv.setItem(13, esya(Material.ENDER_PEARL, ChatColor.LIGHT_PURPLE + "Eşime ışınlan",
                ChatColor.GRAY + "5 sn kıpırdama, bekleme: " + (esTpBeklemeMs() / 60000) + " dk"));
        m.inv.setItem(15, esya(Material.BARRIER, ChatColor.RED + "Boşan", ChatColor.GRAY + "Ücret: " + para(bosanmaUcreti())));
        p.openInventory(m.inv);
    }

    private void bosanmaMenu(Player p) {
        Menu m = new Menu(Tip.BOSANMA);
        ac(p, m, 27, ChatColor.DARK_RED + "Boşanmak istiyor musun?");
        m.inv.setItem(11, esya(Material.RED_CONCRETE, ChatColor.RED + "Evet, boşan", ChatColor.GRAY + "Ücret: " + para(bosanmaUcreti())));
        m.inv.setItem(15, esya(Material.LIME_CONCRETE, ChatColor.GREEN + "Vazgeç"));
        p.openInventory(m.inv);
    }

    private Dugun aktifDugun(Player p) {
        long simdi = System.currentTimeMillis();
        dugunler.values().removeIf(d -> d.bitis < simdi);
        for (Dugun d : dugunler.values()) {
            if (d.a.equals(p.getUniqueId()) || d.b.equals(p.getUniqueId())) continue;
            return d;
        }
        return null;
    }

    private void bagisMenu(Player p) {
        Dugun d = aktifDugun(p);
        if (d == null || !kilisedeMi(p)) {
            p.sendMessage(ChatColor.RED + "Şu an bağış yapılabilecek bir nikah yok (kilisede olmalısın, nikahtan sonra 15 dk).");
            return;
        }
        Menu m = new Menu(Tip.BAGIS);
        m.hedef = d.a;
        ac(p, m, 27, ChatColor.GOLD + "Çifte bağış");
        int[] tut = {1000, 5000, 10000, 50000};
        for (int i = 0; i < tut.length; i++)
            m.inv.setItem(10 + i * 2, esya(Material.GOLD_INGOT, ChatColor.YELLOW + para(tut[i]), ChatColor.GRAY + "Çifte esIdit bölünür"));
        m.inv.setItem(22, esya(Material.CHEST, ChatColor.AQUA + "Eşya bağışla", ChatColor.GRAY + "Açılan pencereye esIdya koy, kapatınca çifte gider"));
        p.openInventory(m.inv);
    }

    private void esyaBagisMenu(Player p, UUID dugunKey) {
        Menu m = new Menu(Tip.BAGIS_ESYA);
        m.hedef = dugunKey;
        ac(p, m, 27, ChatColor.AQUA + "Eşya bağışı (kapatınca gider)");
        p.openInventory(m.inv);
    }

    // ------------------------------------------------------------------ tıklamalar
    @EventHandler
    public void onTikla(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        if (menu.tip == Tip.BAGIS_ESYA) return; // esIdya bağış penceresinde serbest
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;
        switch (menu.tip) {
            case ANA:
                if (slot == 10) { Teklif t = gelenTeklif(p.getUniqueId()); if (t != null) esOnayMenu(p, t); }
                else if (slot == 12 && rahipMi(p.getUniqueId())) rahipMenu(p);
                else if (slot == 14) evlilikMenu(p);
                else if (slot == 16) bagisMenu(p);
                break;
            case SEC_ES: {
                UUID id = menu.kisi[slot];
                Player hedef = id == null ? null : Bukkit.getPlayer(id);
                Player rahip = menu.hedef == null ? null : Bukkit.getPlayer(menu.hedef);
                if (hedef != null && rahip != null) { p.closeInventory(); teklifGonder(p, hedef, rahip); }
                break;
            }
            case ES_ONAY: {
                Teklif t = gelenTeklif(p.getUniqueId());
                if (t == null) { p.closeInventory(); break; }
                p.closeInventory();
                if (slot == 11) teklifKabul(p, t);
                else if (slot == 15) teklifRed(p, t);
                break;
            }
            case RAHIP: {
                if (slot == 22) { p.closeInventory(); kiliseyeGit(p); break; }
                UUID id = menu.kisi[slot];
                Teklif t = id == null ? null : teklifOf(id);
                if (t == null || t.asama != 1 || !t.rahip.equals(p.getUniqueId())) break;
                if (e.isShiftClick()) { teklifler.remove(t.a); p.sendMessage(ChatColor.YELLOW + "Teklif iptal edildi."); rahipMenu(p); }
                else if (e.isRightClick()) { p.closeInventory(); nikahKiy(p, t); }
                else { cagir(p, t); }
                break;
            }
            case EVLILIK:
                if (slot == 11) { p.closeInventory(); esSorgu(p); }
                else if (slot == 13) { p.closeInventory(); esTp(p); }
                else if (slot == 15) bosanmaMenu(p);
                break;
            case BOSANMA:
                p.closeInventory();
                if (slot == 11) bosan(p);
                break;
            case BAGIS: {
                Dugun d = aktifDugun(p);
                if (d == null || !kilisedeMi(p)) { p.closeInventory(); break; }
                if (slot == 22) { esyaBagisMenu(p, d.a); break; }
                int[] tut = {1000, 5000, 10000, 50000};
                for (int i = 0; i < tut.length; i++) if (slot == 10 + i * 2) { p.closeInventory(); paraBagisla(p, d, tut[i]); }
                break;
            }
            default: break;
        }
    }

    @EventHandler
    public void onKapat(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu) || menu.tip != Tip.BAGIS_ESYA) return;
        if (!(e.getPlayer() instanceof Player p)) return;
        Evlilik ev = menu.hedef == null ? null : evlilikler.get(menu.hedef);
        List<ItemStack> esyalar = new ArrayList<>();
        for (ItemStack it : e.getInventory().getContents()) if (it != null && it.getType() != Material.AIR) esyalar.add(it);
        e.getInventory().clear();
        if (esyalar.isEmpty()) return;
        Player a = ev == null ? null : Bukkit.getPlayer(ev.a), b = ev == null ? null : Bukkit.getPlayer(ev.b);
        int n = 0;
        for (ItemStack it : esyalar) {
            Player hedef = (n++ % 2 == 0) ? a : b;
            if (hedef == null) hedef = (a != null) ? a : b;
            if (hedef == null) hedef = p; // çift çevrimdışı: bağışçıya iade
            for (ItemStack kalan : hedef.getInventory().addItem(it).values())
                hedef.getWorld().dropItemNaturally(hedef.getLocation(), kalan);
        }
        p.sendMessage(ChatColor.GOLD + "Hediyen çifte ulaştı. Allah mutlu etsin!");
        if (a != null) a.sendMessage(ChatColor.GOLD + p.getName() + " size esIdya bağışladı.");
        if (b != null) b.sendMessage(ChatColor.GOLD + p.getName() + " size esIdya bağışladı.");
    }

    // ------------------------------------------------------------------ teklif akışı
    @EventHandler
    public void onRahipTik(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player rahip)) return;
        if (!rahipMi(rahip.getUniqueId())) return;
        Player p = e.getPlayer();
        if (p.equals(rahip)) return;
        e.setCancelled(true);
        if (evlilikler.containsKey(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Zaten evlisin."); return; }
        if (!kiliseAyarli()) { p.sendMessage(ChatColor.RED + "Kilise henüz hazır değil (yönetici ayarlamalı)."); return; }
        esSecMenu(p, rahip);
    }

    private void teklifGonder(Player a, Player b, Player rahip) {
        if (evlilikler.containsKey(a.getUniqueId()) || evlilikler.containsKey(b.getUniqueId())) { a.sendMessage(ChatColor.RED + "Biri zaten evli."); return; }
        if (bakiye(a.getUniqueId()) < ucret()) { a.sendMessage(ChatColor.RED + "Nikah ücreti " + para(ucret()) + ", banka hesabında yeterli para yok."); return; }
        Teklif t = new Teklif();
        t.a = a.getUniqueId(); t.b = b.getUniqueId(); t.rahip = rahip.getUniqueId();
        t.adA = a.getName(); t.adB = b.getName(); t.adR = rahip.getName();
        t.asama = 0; t.ms = System.currentTimeMillis();
        teklifler.put(t.a, t);
        a.sendMessage(ChatColor.GOLD + b.getName() + " kişisine evlilik teklifi gönderildi. Cevabını bekle.");
        b.sendMessage(Component.text(a.getName() + " seninle evlenmek istiyor! ", NamedTextColor.LIGHT_PURPLE)
                .append(tikla("[Menüyü aç]", "/evlilik")));
        b.playSound(b.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
    }

    private void teklifKabul(Player b, Teklif t) {
        t.asama = 1;
        Player a = Bukkit.getPlayer(t.a), rahip = Bukkit.getPlayer(t.rahip);
        b.sendMessage(ChatColor.GREEN + "Teklifi kabul ettin. Rahip " + t.adR + " nikahı ayarlayacak.");
        if (a != null) a.sendMessage(ChatColor.GREEN + t.adB + " teklifini kabul etti! Rahibin çağrısını bekleyin.");
        if (rahip != null) rahip.sendMessage(Component.text(t.adA + " ve " + t.adB + " evlenmek istiyor. ", NamedTextColor.GOLD)
                .append(tikla("[Rahip Paneli]", "/evlilik")));
    }

    private void teklifRed(Player b, Teklif t) {
        teklifler.remove(t.a);
        b.sendMessage(ChatColor.YELLOW + "Teklifi reddettin.");
        Player a = Bukkit.getPlayer(t.a);
        if (a != null) a.sendMessage(ChatColor.RED + t.adB + " evlilik teklifini reddetti.");
    }

    private void cagir(Player rahip, Teklif t) {
        if (!kiliseAyarli()) { rahip.sendMessage(ChatColor.RED + "Kilise ayarlı değil."); return; }
        Component c = Component.text("Rahip " + rahip.getName() + " sizi nikah için kiliseye çağırıyor. ", NamedTextColor.GOLD)
                .append(tikla("[Kiliseye ışınlan]", "/evlilik gel"));
        Player a = Bukkit.getPlayer(t.a), b = Bukkit.getPlayer(t.b);
        if (a != null) a.sendMessage(c);
        if (b != null) b.sendMessage(c);
        rahip.sendMessage(ChatColor.GREEN + "Çifte kiliseye çağrı gönderildi.");
        if (a == null || b == null) rahip.sendMessage(ChatColor.YELLOW + "Not: çiftten biri şu an çevrimdışı.");
    }

    private void kiliseyeGit(Player p) {
        if (!kiliseAyarli()) { p.sendMessage(ChatColor.RED + "Kilise ayarlı değil."); return; }
        if (mahkumMu(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Mahkûmken kiliseye gidemezsin."); return; }
        boolean izin = rahipMi(p.getUniqueId()) || p.hasPermission("meslek.admin");
        if (!izin) for (Teklif t : teklifler.values()) if (t.asama == 1 && (t.a.equals(p.getUniqueId()) || t.b.equals(p.getUniqueId()))) izin = true;
        if (!izin) { p.sendMessage(ChatColor.RED + "Kiliseye ışınlanman için onaylı bir nikah teklifin olmalı."); return; }
        p.teleport(kiliseNokta());
        p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
        p.sendMessage(ChatColor.GOLD + "Kiliseye ışınlandın.");
    }

    // ------------------------------------------------------------------ nikah
    private void nikahKiy(Player rahip, Teklif t) {
        Player a = Bukkit.getPlayer(t.a), b = Bukkit.getPlayer(t.b);
        if (!kiliseAyarli()) { rahip.sendMessage(ChatColor.RED + "Kilise ayarlı değil."); return; }
        if (a == null || b == null) { rahip.sendMessage(ChatColor.RED + "Çiftin ikisi de çevrimiçi olmalı."); return; }
        if (evlilikler.containsKey(t.a) || evlilikler.containsKey(t.b)) { teklifler.remove(t.a); rahip.sendMessage(ChatColor.RED + "Biri zaten evli."); return; }
        if (!kilisedeMi(rahip)) { rahip.sendMessage(ChatColor.RED + "Nikahı kıymak için kilisede olmalısın."); return; }
        if (!kilisedeMi(a) || !kilisedeMi(b)) { rahip.sendMessage(ChatColor.RED + "Çiftin ikisi de kilisede olmalı."); return; }
        int tanik = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(a) || o.equals(b) || o.equals(rahip)) continue;
            if (kilisedeMi(o)) tanik++;
        }
        if (tanik < minTanik()) { rahip.sendMessage(ChatColor.RED + "En az " + minTanik() + " tanık kilisede olmalı (şu an " + tanik + ")."); return; }
        if (!paraAl(t.a, ucret())) { rahip.sendMessage(ChatColor.RED + t.adA + " nikah ücretini (" + para(ucret()) + ") ödeyemiyor."); a.sendMessage(ChatColor.RED + "Nikah ücretini ödemek için bankanda " + para(ucret()) + " olmalı."); return; }
        paraVer(t.rahip, ucret());
        plugin.ekonomiLog("nikah", t.adA, t.adA + " + " + t.adB + " | rahip " + rahip.getName() + " | " + para(ucret()));
        teklifler.remove(t.a);

        Evlilik ev = new Evlilik();
        ev.a = t.a; ev.b = t.b; ev.adA = t.adA; ev.adB = t.adB; ev.ms = System.currentTimeMillis();
        ev.rahip = rahip.getName(); ev.no = "NK-" + (belgeSayac++);
        evlilikler.put(ev.a, ev); evlilikler.put(ev.b, ev);
        kaydet();

        Dugun d = new Dugun();
        d.a = ev.a; d.b = ev.b; d.bitis = System.currentTimeMillis() + BAGIS_SURE_MS;
        dugunler.put(ev.a, d);

        yuzukVer(a, ev.adB); yuzukVer(b, ev.adA);
        belgeVer(a, ev); belgeVer(b, ev);
        yetkiVer(ev.a, ev.b); yetkiVer(ev.b, ev.a);
        toren(a, b, rahip);

        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "♥ " + ChatColor.GOLD + ev.adA + ChatColor.WHITE + " ve " + ChatColor.GOLD + ev.adB
                + ChatColor.WHITE + " Rahip " + rahip.getName() + " huzurunda dünyaevine girdi! Mutluluklar dileriz. " + ChatColor.LIGHT_PURPLE + "♥");
        if (plugin.haberManager != null)
            plugin.haberManager.yaz("TOPLUM", "Kilisede nikah", ev.adA + " ve " + ev.adB + ", Rahip " + rahip.getName() + " huzurunda evlendi.");
    }

    private void yuzukVer(Player p, String esAd) {
        ItemStack y = esya(Material.GOLD_NUGGET, ChatColor.GOLD + "" + ChatColor.BOLD + "Alyans",
                ChatColor.GRAY + "Eşim: " + esAd, ChatColor.DARK_GRAY + "Sonsuza dek...");
        ItemMeta m = y.getItemMeta();
        m.setEnchantmentGlintOverride(true);
        try { m.setItemModel(new NamespacedKey("koninski", "esya/alyans")); } catch (Throwable ignored) { }
        m.getPersistentDataContainer().set(alyansKey, PersistentDataType.STRING, p.getName() + "+" + esAd);
        y.setItemMeta(m);
        ver(p, y);
    }

    private void belgeVer(Player p, Evlilik ev) {
        ItemStack k = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta bm = (BookMeta) k.getItemMeta();
        bm.setTitle("Nikah Belgesi");
        bm.setAuthor("Rahip " + ev.rahip);
        bm.addPage("NİKAH BELGESİ\n\nBelge No: " + ev.no + "\n\n" + ev.adA + "\nve\n" + ev.adB
                + "\n\n" + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                + " tarihinde Koninski kilisesinde Rahip " + ev.rahip + " huzurunda evlenmişlerdir.");
        k.setItemMeta(bm);
        ver(p, k);
    }

    private void ver(Player p, ItemStack it) {
        for (ItemStack kalan : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), kalan);
    }

    @SuppressWarnings("deprecation")
    private void toren(Player a, Player b, Player rahip) {
        Location l = rahip.getLocation();
        World w = l.getWorld();
        for (int i = 0; i < 3; i++) {
            final int k = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> w.playSound(l, Sound.BLOCK_BELL_USE, SoundCategory.RECORDS, 3f, 0.7f + 0.15f * k), 15L * i);
        }
        for (int i = 0; i < 4; i++) {
            final int k = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Location f = (k % 2 == 0 ? a : b).getLocation().add(0, 1, 0);
                Firework fw = (Firework) f.getWorld().spawnEntity(f, EntityType.FIREWORK_ROCKET);
                org.bukkit.inventory.meta.FireworkMeta fm = fw.getFireworkMeta();
                fm.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(Color.RED, Color.WHITE).withFade(Color.YELLOW).flicker(true).build());
                fw.setFireworkMeta(fm);
                fw.detonate();
            }, 20L + 12L * i);
        }
        w.spawnParticle(Particle.HEART, a.getLocation().add(0, 2.2, 0), 15, 0.6, 0.4, 0.6, 0);
        w.spawnParticle(Particle.HEART, b.getLocation().add(0, 2.2, 0), 15, 0.6, 0.4, 0.6, 0);
        a.sendTitle(ChatColor.LIGHT_PURPLE + "♥ Evlendiniz ♥", ChatColor.GOLD + "Mutluluklar!", 10, 70, 20);
        b.sendTitle(ChatColor.LIGHT_PURPLE + "♥ Evlendiniz ♥", ChatColor.GOLD + "Mutluluklar!", 10, 70, 20);
        final Location merkez = l.clone();
        muzikBaslat("dugun", () -> {
            List<Player> hs = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.getWorld().equals(merkez.getWorld()) && (o.getLocation().distanceSquared(merkez) < 150 * 150 || kilisedeMi(o))) hs.add(o);
            }
            return hs;
        });
    }

    // ------------------------------------------------------------------ ortak claim yetkisi
    private void yetkiVer(UUID sahip, UUID eş) {
        try {
            me.ryanhamshire.GriefPrevention.GriefPrevention gp = me.ryanhamshire.GriefPrevention.GriefPrevention.instance;
            for (me.ryanhamshire.GriefPrevention.Claim c : gp.dataStore.getPlayerData(sahip).getClaims()) {
                c.setPermission(eş.toString(), me.ryanhamshire.GriefPrevention.ClaimPermission.Build);
                gp.dataStore.saveClaim(c);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Evlilik] claim yetkisi verilemedi: " + t.getMessage());
        }
    }

    private void yetkiAl(UUID sahip, UUID eş) {
        try {
            me.ryanhamshire.GriefPrevention.GriefPrevention gp = me.ryanhamshire.GriefPrevention.GriefPrevention.instance;
            for (me.ryanhamshire.GriefPrevention.Claim c : gp.dataStore.getPlayerData(sahip).getClaims()) {
                c.dropPermission(eş.toString());
                gp.dataStore.saveClaim(c);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[Evlilik] claim yetkisi alınamadı: " + t.getMessage());
        }
    }

    private void tumYetkiler() {
        for (Evlilik e : benzersiz()) { yetkiVer(e.a, e.b); yetkiVer(e.b, e.a); }
    }

    // ------------------------------------------------------------------ evli oyuncu menüsü işlevleri
    private void kalpler() {
        for (Evlilik e : benzersiz()) {
            Player a = Bukkit.getPlayer(e.a), b = Bukkit.getPlayer(e.b);
            if (a == null || b == null || !a.getWorld().equals(b.getWorld())) continue;
            if (a.getLocation().distanceSquared(b.getLocation()) > 12 * 12) continue;
            a.getWorld().spawnParticle(Particle.HEART, a.getLocation().add(0, 2.2, 0), 1, 0.2, 0.1, 0.2, 0);
            b.getWorld().spawnParticle(Particle.HEART, b.getLocation().add(0, 2.2, 0), 1, 0.2, 0.1, 0.2, 0);
        }
    }

    private void esSorgu(Player p) {
        Evlilik ev = evlilikler.get(p.getUniqueId());
        if (ev == null) return;
        Player es = Bukkit.getPlayer(ev.diger(p.getUniqueId()));
        if (es == null) { p.sendMessage(ChatColor.GRAY + ev.digerAd(p.getUniqueId()) + " şu an çevrimdışı."); return; }
        String d = es.getWorld().equals(p.getWorld()) ? ((int) es.getLocation().distance(p.getLocation())) + " blok uzakta" : "basItemka bir dünyada (" + es.getWorld().getName() + ")";
        p.sendMessage(ChatColor.LIGHT_PURPLE + "♥ " + es.getName() + " çevrimiçi, " + d + ".");
    }

    private void esTp(Player p) {
        Evlilik ev = evlilikler.get(p.getUniqueId());
        if (ev == null) return;
        Player es = Bukkit.getPlayer(ev.diger(p.getUniqueId()));
        if (es == null) { p.sendMessage(ChatColor.RED + "Eşin çevrimdışı."); return; }
        if (mahkumMu(p.getUniqueId()) || mahkumMu(es.getUniqueId())) { p.sendMessage(ChatColor.RED + "Mahkûmluk varken ışınlanma yapılamaz."); return; }
        long simdi = System.currentTimeMillis();
        Long son = esTpSon.get(p.getUniqueId());
        if (son != null && simdi - son < esTpBeklemeMs()) {
            long kalan = (esTpBeklemeMs() - (simdi - son)) / 1000;
            p.sendMessage(ChatColor.RED + "Eşine tekrar ışınlanmak için " + (kalan / 60) + " dk " + (kalan % 60) + " sn beklemelisin.");
            return;
        }
        final Location basla = p.getLocation();
        p.sendMessage(ChatColor.LIGHT_PURPLE + "5 saniye kıpırdama, esIdinin yanına ışınlanıyorsun...");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || !es.isOnline()) return;
            if (!p.getWorld().equals(basla.getWorld()) || p.getLocation().distanceSquared(basla) > 4) {
                p.sendMessage(ChatColor.RED + "Kıpırdadın, ışınlanma iptal edildi.");
                return;
            }
            esTpSon.put(p.getUniqueId(), System.currentTimeMillis());
            p.teleport(es.getLocation());
            p.sendMessage(ChatColor.LIGHT_PURPLE + "Eşinin yanındasın.");
        }, 100L);
    }

    private void bosan(Player p) {
        Evlilik ev = evlilikler.get(p.getUniqueId());
        if (ev == null) return;
        if (!paraAl(p.getUniqueId(), bosanmaUcreti())) { p.sendMessage(ChatColor.RED + "Boşanma ücreti " + para(bosanmaUcreti()) + ", hesabında yeterli para yok."); return; }
        plugin.kasayaParaEkle(bosanmaUcreti());
        plugin.ekonomiLog("bosanma", p.getName(), ev.adA + " - " + ev.adB + " | " + para(bosanmaUcreti()));
        evlilikler.remove(ev.a); evlilikler.remove(ev.b);
        dugunler.remove(ev.a);
        yetkiAl(ev.a, ev.b); yetkiAl(ev.b, ev.a);
        kaydet();
        p.sendMessage(ChatColor.GRAY + "Boşandın.");
        Player diger = Bukkit.getPlayer(ev.diger(p.getUniqueId()));
        if (diger != null) diger.sendMessage(ChatColor.GRAY + p.getName() + " senden boşandı.");
        Bukkit.broadcastMessage(ChatColor.GRAY + ev.adA + " ve " + ev.adB + " boşandı.");
        if (plugin.haberManager != null) plugin.haberManager.yaz("TOPLUM", "Bir evlilik bitti", ev.adA + " ve " + ev.adB + " boşandı.");
    }

    private void paraBagisla(Player p, Dugun d, int miktar) {
        if (!paraAl(p.getUniqueId(), miktar)) { p.sendMessage(ChatColor.RED + "Yeterli paran yok."); return; }
        paraVer(d.a, miktar / 2.0); paraVer(d.b, miktar / 2.0);
        plugin.ekonomiLog("nikah-bagis", p.getName(), para(miktar));
        p.sendMessage(ChatColor.GOLD + "Çifte " + para(miktar) + " bağışladın.");
        Player a = Bukkit.getPlayer(d.a), b = Bukkit.getPlayer(d.b);
        String msj = ChatColor.GOLD + p.getName() + " size " + para(miktar) + " bağışladı!";
        if (a != null) a.sendMessage(msj);
        if (b != null) b.sendMessage(msj);
    }

    // ------------------------------------------------------------------ müzik
    private static final class Nota {
        final int tick; final Sound ses; final int n;
        Nota(int tick, Sound ses, int n) { this.tick = tick; this.ses = ses; this.n = n; }
    }

    /** Pachelbel - Canon (Re majör), nota blokları ile. n: F#'ye göre yarım ses sayısı (0-24). */
    private List<Nota> muzikYaz() {
        List<Nota> l = new ArrayList<>();
        int[] bas = {8, 15, 17, 12, 13, 8, 13, 15};                 // D A B F# G D G A  (bas)
        int[][] akor = {{8, 12, 15}, {3, 7, 10}, {5, 8, 12}, {0, 3, 7}, {1, 5, 8}, {8, 12, 15}, {1, 5, 8}, {3, 7, 10}};
        int[] melA = {24, 22, 20, 19, 17, 15, 17, 19};              // F# E D C# B A B C#
        int[] melB = {20, 19, 17, 15, 13, 12, 13, 10};              // D C# B A G F# G E
        int[] kalip = {0, 1, 2, 1};
        int t = 0;
        for (int tur = 0; tur < 3; tur++) {
            for (int c = 0; c < 8; c++) {
                int basla = t + c * 32;
                l.add(new Nota(basla, Sound.BLOCK_NOTE_BLOCK_BASS, bas[c]));
                for (int k = 0; k < 4; k++) l.add(new Nota(basla + k * 8, Sound.BLOCK_NOTE_BLOCK_GUITAR, akor[c][kalip[k]]));
                if (tur == 1) l.add(new Nota(basla, Sound.BLOCK_NOTE_BLOCK_HARP, melA[c]));
                if (tur == 2) {
                    l.add(new Nota(basla, Sound.BLOCK_NOTE_BLOCK_HARP, melB[c]));
                    l.add(new Nota(basla + 16, Sound.BLOCK_NOTE_BLOCK_HARP, melA[c] - 0));
                }
            }
            t += 256;
        }
        // final akoru
        l.add(new Nota(t, Sound.BLOCK_NOTE_BLOCK_BASS, 8));
        l.add(new Nota(t, Sound.BLOCK_NOTE_BLOCK_HARP, 8));
        l.add(new Nota(t, Sound.BLOCK_NOTE_BLOCK_HARP, 12));
        l.add(new Nota(t, Sound.BLOCK_NOTE_BLOCK_HARP, 15));
        l.add(new Nota(t, Sound.BLOCK_NOTE_BLOCK_BELL, 8));
        l.add(new Nota(t + 10, Sound.BLOCK_NOTE_BLOCK_BELL, 12));
        l.add(new Nota(t + 20, Sound.BLOCK_NOTE_BLOCK_BELL, 15));
        return l;
    }

    private void muzikBaslat(String anahtar, Supplier<Collection<Player>> dinleyiciler) {
        muzikDurdur(anahtar);
        final List<Nota> notalar = muzikYaz();
        int son = 0;
        for (Nota n : notalar) son = Math.max(son, n.tick);
        final int bitis = son + 60;
        BukkitTask task = new BukkitRunnable() {
            int tick = 0;
            Collection<Player> hs = dinleyiciler.get();
            @Override public void run() {
                if (tick % 20 == 0) hs = dinleyiciler.get();
                for (Nota n : notalar) {
                    if (n.tick != tick) continue;
                    float pitch = (float) Math.pow(2.0, (n.n - 12) / 12.0);
                    for (Player p : hs) p.playSound(p.getLocation(), n.ses, SoundCategory.RECORDS, 0.9f, pitch);
                }
                if (++tick > bitis) { muzikler.remove(anahtar); cancel(); }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        muzikler.put(anahtar, task);
    }

    private void muzikDurdur(String anahtar) {
        BukkitTask t = muzikler.remove(anahtar);
        if (t != null) t.cancel();
    }

    private void muzikKomut(CommandSender s, String[] args) {
        if (!(s instanceof Player p)) { s.sendMessage("Oyunculara özel."); return; }
        if (!p.hasPermission("meslek.admin") && !rahipMi(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Yetkin yok."); return; }
        String alt = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (alt.equals("dur")) {
            muzikDurdur("test-" + p.getUniqueId()); muzikDurdur("dugun");
            p.sendMessage(ChatColor.YELLOW + "Müzik durduruldu.");
            return;
        }
        if (alt.equals("herkes") && p.hasPermission("meslek.admin")) {
            muzikBaslat("dugun", () -> new ArrayList<>(Bukkit.getOnlinePlayers()));
            p.sendMessage(ChatColor.GOLD + "Düğün müziği herkese çalıyor (durdurmak için /nikahmuzik dur).");
            return;
        }
        final UUID id = p.getUniqueId();
        muzikBaslat("test-" + id, () -> {
            Player q = Bukkit.getPlayer(id);
            List<Player> l = new ArrayList<>();
            if (q != null) l.add(q);
            return l;
        });
        p.sendMessage(ChatColor.GOLD + "Düğün müziği çalıyor (yaklaşık 40 sn). Durdurmak için /nikahmuzik dur" + (p.hasPermission("meslek.admin") ? ", herkese çalmak için /nikahmuzik herkes" : "") + ".");
    }
}
