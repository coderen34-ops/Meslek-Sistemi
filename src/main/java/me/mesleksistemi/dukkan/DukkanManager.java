package me.mesleksistemi.dukkan;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import me.mesleksistemi.MeslekSistemi;

/**
 * Oyuncu dükkânı, ruhsat, çalışan, İş Menüsü, tamirci hizmeti ve Başkan Menüsü.
 *
 *  - /dukkan kur [ad]: kendi claim'inde (köy içinde) dükkân kurar ($30.000, kişi başı en fazla 2) ve bir Dükkân Tabelası verir.
 *  - Tabela claim'e konur; sağ tık = dükkân bilgisi/ruhsat (polis de buradan kontrol eder).
 *  - Ruhsat $100.000, belediye başkanı onaylar; 24 saat içinde karar vermezse %50-%50 zar atılır (ret = iade).
 *  - /isyeri: ruhsat sahibinin İş Menüsü (ruhsat, çalışan al/çıkar, pay oranı, tamir fiyatı).
 *  - /baskanmenu: başkana vergi oranı, maaş aç/kapat ve ruhsat onayı.
 *  - Tamirci: dükkânda müşterinin eşyasını enchant kaybetmeden, XP harcamadan, tamircinin MALZEMESİYLE onarır.
 */
public class DukkanManager implements Listener, CommandExecutor {

    // ------------------------------------------------------------------ ayarlar (config: dukkan.*)
    private double kurulumUcreti() { return Math.max(0, plugin.getConfig().getDouble("dukkan.kurulum-ucreti", 30000.0)); }
    private double ruhsatUcreti() { return Math.max(0, plugin.getConfig().getDouble("dukkan.ruhsat-ucreti", 100000.0)); }
    private double ruhsatDegistirmeUcreti() { return Math.max(0, plugin.getConfig().getDouble("dukkan.ruhsat-degistirme-ucreti", 150.0)); }
    private int maxDukkan() { return Math.max(1, plugin.getConfig().getInt("dukkan.max-dukkan", 2)); }
    private int maxCalisan() { return Math.max(1, plugin.getConfig().getInt("dukkan.max-calisan", 5)); }
    private long onayBeklemeMs() { return Math.max(1L, plugin.getConfig().getLong("dukkan.onay-saat", 24L)) * 3_600_000L; }
    private static final int MAX_CALISAN_PAY = 50;
    private static final int MAX_VERGI = 25;
    private static final double TAMIR_UCRET_MIN = 10.0, TAMIR_UCRET_MAX = 5000.0;

    // ------------------------------------------------------------------ veri
    private static final class Dukkan {
        UUID id;
        UUID sahip;
        String sahipAd;
        long claimId;
        String ad;
        String tur;               // ruhsat türü (meslek anahtarı) ya da null
        int durum;                // 0 ruhsatsız, 1 başvuru bekliyor, 2 onaylı
        long basvuruMs;
        String ruhsatNo;
        double escrow;            // bekleyen başvuruda bloke edilen ruhsat ücreti
        double tamirUcret = 200.0;
        String bildirim;          // sahibe çevrimdışıyken verilen karar bildirimi
        String dunya; int x, y, z; boolean tabelaVar;
        final LinkedHashMap<UUID, Integer> calisanPay = new LinkedHashMap<>();
        final Map<UUID, String> calisanAd = new HashMap<>();
    }

    private enum Tip { IS_ANA, IS_DUKKAN, IS_CALISAN, MUSTERI, TAMIR, BASKAN, BASKAN_RUHSAT }

    private static final class Menu implements InventoryHolder {
        final Tip tip; final UUID dukkan; Inventory inv;
        final int[] dizin = new int[54];   // TAMIR menüsünde slot -> oyuncu envanter indeksi
        final UUID[] kisi = new UUID[54];  // çalışan / başvuru menülerinde slot -> kişi ya da dükkân
        Menu(Tip tip, UUID dukkan) { this.tip = tip; this.dukkan = dukkan; }
        @Override public Inventory getInventory() { return inv; }
    }

    private static final class Girdi {
        final String tur; final UUID dukkan; final UUID hedef;
        Girdi(String tur, UUID dukkan, UUID hedef) { this.tur = tur; this.dukkan = dukkan; this.hedef = hedef; }
    }

    private static final class Davet {
        final UUID dukkan; final long ms;
        Davet(UUID dukkan, long ms) { this.dukkan = dukkan; this.ms = ms; }
    }

    private final MeslekSistemi plugin;
    private final File dosya;
    private final Map<UUID, Dukkan> dukkanlar = new LinkedHashMap<>();
    private final Map<UUID, Girdi> girdiler = new HashMap<>();
    private final Map<UUID, Davet> davetler = new HashMap<>();
    private final NamespacedKey tabelaKey;
    private final Random rnd = new Random();
    private int ruhsatSayac = 1000;

    public DukkanManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "dukkanlar.yml");
        this.tabelaKey = new NamespacedKey(plugin, "dukkan_tabela");
        yukle();
        // Her dakika: 24 saat dolan ruhsat başvurularına zar at
        Bukkit.getScheduler().runTaskTimer(plugin, this::sureKontrol, 20L * 60, 20L * 60);
    }

    public void kapat() { kaydet(); }

    // ------------------------------------------------------------------ kayıt
    private void yukle() {
        dukkanlar.clear();
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        ruhsatSayac = y.getInt("ruhsat-sayac", 1000);
        ConfigurationSection kok = y.getConfigurationSection("dukkanlar");
        if (kok == null) return;
        for (String k : kok.getKeys(false)) {
            try {
                ConfigurationSection s = kok.getConfigurationSection(k);
                if (s == null) continue;
                Dukkan d = new Dukkan();
                d.id = UUID.fromString(k);
                d.sahip = UUID.fromString(s.getString("sahip"));
                d.sahipAd = s.getString("sahipAd", "?");
                d.claimId = s.getLong("claim");
                d.ad = s.getString("ad", "Dükkân");
                d.tur = s.getString("tur");
                d.durum = s.getInt("durum", 0);
                d.basvuruMs = s.getLong("basvuru");
                d.ruhsatNo = s.getString("ruhsatNo");
                d.escrow = s.getDouble("escrow");
                d.tamirUcret = s.getDouble("tamirUcret", 200.0);
                d.bildirim = s.getString("bildirim");
                d.tabelaVar = s.getBoolean("tabela.var", false);
                d.dunya = s.getString("tabela.dunya");
                d.x = s.getInt("tabela.x"); d.y = s.getInt("tabela.y"); d.z = s.getInt("tabela.z");
                ConfigurationSection c = s.getConfigurationSection("calisanlar");
                if (c != null) for (String ck : c.getKeys(false)) {
                    UUID cu = UUID.fromString(ck);
                    d.calisanPay.put(cu, c.getInt(ck + ".pay", 10));
                    d.calisanAd.put(cu, c.getString(ck + ".ad", "?"));
                }
                dukkanlar.put(d.id, d);
            } catch (Exception e) {
                plugin.getLogger().warning("[Dukkan] Bozuk kayıt atlandı: " + k + " (" + e.getMessage() + ")");
            }
        }
    }

    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("ruhsat-sayac", ruhsatSayac);
        for (Dukkan d : dukkanlar.values()) {
            String p = "dukkanlar." + d.id + ".";
            y.set(p + "sahip", d.sahip.toString());
            y.set(p + "sahipAd", d.sahipAd);
            y.set(p + "claim", d.claimId);
            y.set(p + "ad", d.ad);
            y.set(p + "tur", d.tur);
            y.set(p + "durum", d.durum);
            y.set(p + "basvuru", d.basvuruMs);
            y.set(p + "ruhsatNo", d.ruhsatNo);
            y.set(p + "escrow", d.escrow);
            y.set(p + "tamirUcret", d.tamirUcret);
            y.set(p + "bildirim", d.bildirim);
            y.set(p + "tabela.var", d.tabelaVar);
            y.set(p + "tabela.dunya", d.dunya);
            y.set(p + "tabela.x", d.x); y.set(p + "tabela.y", d.y); y.set(p + "tabela.z", d.z);
            for (Map.Entry<UUID, Integer> e : d.calisanPay.entrySet()) {
                y.set(p + "calisanlar." + e.getKey() + ".pay", e.getValue());
                y.set(p + "calisanlar." + e.getKey() + ".ad", d.calisanAd.getOrDefault(e.getKey(), "?"));
            }
        }
        try {
            dosya.getParentFile().mkdirs();
            y.save(dosya);
        } catch (Exception e) {
            plugin.getLogger().warning("[Dukkan] dukkanlar.yml yazılamadı: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ yardımcılar
    private String meslekOf(UUID id) { return plugin.oyuncuMeslekCache.getOrDefault(id, "vatandas"); }
    private boolean baskanMi(Player p) {
        return meslekOf(p.getUniqueId()).equalsIgnoreCase("belediyebaskani") || p.hasPermission("meslek.baskan");
    }
    private boolean polisMi(Player p) {
        String m = meslekOf(p.getUniqueId());
        return m.equalsIgnoreCase("polis") || m.equalsIgnoreCase("amir");
    }
    private static String para(double d) { return "$" + String.format(Locale.US, "%,.0f", d); }
    private double bakiye(UUID id) { return plugin.bankaHesaplari.getOrDefault(id, 0.0); }
    private boolean paraAl(UUID id, double miktar) {
        double b = bakiye(id);
        if (b + 0.0001 < miktar) return false;
        plugin.bankaHesaplari.put(id, Math.round((b - miktar) * 100.0) / 100.0);
        return true;
    }
    private void paraVer(UUID id, double miktar) {
        plugin.bankaHesaplari.put(id, Math.round((bakiye(id) + miktar) * 100.0) / 100.0);
    }
    private String turAdi(String tur) {
        if (tur == null) return "yok";
        String ad = plugin.meslekGorunumAdlari.get(tur);
        return ad == null ? tur : ad;
    }
    private double vergiYuzde() {
        return Math.max(0, Math.min(MAX_VERGI, plugin.getConfig().getDouble("dukkan-vergi-yuzde", 10.0)));
    }

    private List<Dukkan> dukkanlariOf(UUID sahip) {
        List<Dukkan> l = new ArrayList<>();
        for (Dukkan d : dukkanlar.values()) if (d.sahip.equals(sahip)) l.add(d);
        return l;
    }

    private me.ryanhamshire.GriefPrevention.Claim claimAt(Location l) {
        try {
            return me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.getClaimAt(l, false, null);
        } catch (Throwable t) { return null; }
    }

    private Dukkan claimdenDukkan(long claimId) {
        for (Dukkan d : dukkanlar.values()) if (d.claimId == claimId) return d;
        return null;
    }

    private Dukkan tabeladanDukkan(Block b) {
        if (b == null || b.getWorld() == null) return null;
        for (Dukkan d : dukkanlar.values()) {
            if (d.tabelaVar && b.getWorld().getName().equals(d.dunya) && b.getX() == d.x && b.getY() == d.y && b.getZ() == d.z) return d;
        }
        return null;
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

    private String ruhsatDurumYazi(Dukkan d) {
        if (d.durum == 2) return ChatColor.GREEN + "Ruhsatlı (" + turAdi(d.tur) + ", No: " + d.ruhsatNo + ")";
        if (d.durum == 1) return ChatColor.YELLOW + "Ruhsat başvurusu başkan onayında (" + turAdi(d.tur) + ")";
        return ChatColor.RED + "RUHSATSIZ";
    }

    // ------------------------------------------------------------------ komutlar
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Bu komut oyunculara özeldir."); return true; }
        String c = cmd.getName().toLowerCase(Locale.ROOT);
        if (c.equals("isyeri")) { isAnaMenu(p); return true; }
        if (c.equals("baskanmenu")) {
            if (!baskanMi(p)) { p.sendMessage(ChatColor.RED + "Bu menü sadece belediye başkanı içindir."); return true; }
            baskanMenu(p);
            return true;
        }
        // /dukkan
        String alt = args.length == 0 ? "menu" : args[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "kur": dukkanKur(p, args); break;
            case "kabul": davetKabul(p); break;
            case "ayril": ayril(p); break;
            case "menu": isAnaMenu(p); break;
            default:
                p.sendMessage(ChatColor.GOLD + "/dukkan kur [ad] " + ChatColor.GRAY + "- kendi claim'inde dükkân kur (" + para(kurulumUcreti()) + ")");
                p.sendMessage(ChatColor.GOLD + "/isyeri " + ChatColor.GRAY + "- İş Menüsü (ruhsat, çalışanlar, tamir fiyatı)");
                p.sendMessage(ChatColor.GOLD + "/dukkan kabul " + ChatColor.GRAY + "- iş teklifini kabul et");
                p.sendMessage(ChatColor.GOLD + "/dukkan ayril " + ChatColor.GRAY + "- çalıştığın dükkândan ayrıl");
        }
        return true;
    }

    private void dukkanKur(Player p, String[] args) {
        String m = meslekOf(p.getUniqueId());
        if (m.equalsIgnoreCase("gocmen") || m.equalsIgnoreCase("default") || m.equalsIgnoreCase("mahkum")) {
            p.sendMessage(ChatColor.RED + "Göçmenler ve mahkumlar dükkân kuramaz."); return;
        }
        if (dukkanlariOf(p.getUniqueId()).size() >= maxDukkan()) {
            p.sendMessage(ChatColor.RED + "En fazla " + maxDukkan() + " dükkânın olabilir."); return;
        }
        if (plugin.koyManager == null || !plugin.koyManager.icerideMi(p.getLocation())) {
            p.sendMessage(ChatColor.RED + "Dükkân sadece köy sınırları içinde kurulabilir."); return;
        }
        me.ryanhamshire.GriefPrevention.Claim claim = claimAt(p.getLocation());
        if (claim == null || claim.getOwnerID() == null || !claim.getOwnerID().equals(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Dükkân kurmak için kendi claim'inin içinde durmalısın."); return;
        }
        if (claimdenDukkan(claim.getID()) != null) {
            p.sendMessage(ChatColor.RED + "Bu claim zaten bir dükkân."); return;
        }
        double ucret = kurulumUcreti();
        if (!paraAl(p.getUniqueId(), ucret)) {
            p.sendMessage(ChatColor.RED + "Banka hesabında yeterli para yok. Dükkân kurma ücreti: " + para(ucret)); return;
        }
        plugin.kasayaParaEkle(ucret);
        Dukkan d = new Dukkan();
        d.id = UUID.randomUUID();
        d.sahip = p.getUniqueId();
        d.sahipAd = p.getName();
        d.claimId = claim.getID();
        String ad = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : p.getName() + " Dükkânı";
        d.ad = ad.length() > 24 ? ad.substring(0, 24) : ad;
        dukkanlar.put(d.id, d);
        kaydet();
        plugin.veriKaydet();
        plugin.ekonomiLog("dukkan-kur", p.getName(), d.ad + " | " + para(ucret));
        p.getInventory().addItem(tabelaEsyasi(d)).values().forEach(k -> p.getWorld().dropItemNaturally(p.getLocation(), k));
        p.sendMessage(ChatColor.GREEN + "Dükkânın kuruldu: " + d.ad + " (" + para(ucret) + " ödendi).");
        p.sendMessage(ChatColor.GRAY + "Verilen Dükkân Tabelası'nı claim'inin içine yerleştir. Sonra /isyeri ile ruhsat başvurusu yap (" + para(ruhsatUcreti()) + ").");
    }

    private ItemStack tabelaEsyasi(Dukkan d) {
        ItemStack it = esya(Material.OAK_SIGN, ChatColor.GOLD + "Dükkân Tabelası: " + d.ad,
                ChatColor.GRAY + "Dükkânının claim'ine yerleştir.", ChatColor.DARK_GRAY + "Sağ tık: ruhsat/bilgi");
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(tabelaKey, PersistentDataType.STRING, d.id.toString());
        it.setItemMeta(meta);
        return it;
    }

    private void davetKabul(Player p) {
        Davet dv = davetler.remove(p.getUniqueId());
        if (dv == null || System.currentTimeMillis() - dv.ms > 5 * 60_000L) { p.sendMessage(ChatColor.RED + "Bekleyen bir iş teklifin yok."); return; }
        Dukkan d = dukkanlar.get(dv.dukkan);
        if (d == null) { p.sendMessage(ChatColor.RED + "Dükkân artık yok."); return; }
        if (d.calisanPay.size() >= maxCalisan()) { p.sendMessage(ChatColor.RED + "Dükkânın çalışan sınırı dolu."); return; }
        d.calisanPay.put(p.getUniqueId(), 10);
        d.calisanAd.put(p.getUniqueId(), p.getName());
        kaydet();
        p.sendMessage(ChatColor.GREEN + d.ad + " dükkânında çalışmaya başladın. Satış payın şimdilik %10, sahibi değiştirebilir.");
        Player sahip = Bukkit.getPlayer(d.sahip);
        if (sahip != null) sahip.sendMessage(ChatColor.GREEN + p.getName() + " iş teklifini kabul etti. /isyeri ile pay oranını belirleyebilirsin.");
    }

    private void ayril(Player p) {
        boolean oldu = false;
        for (Dukkan d : dukkanlar.values()) {
            if (d.calisanPay.remove(p.getUniqueId()) != null) { d.calisanAd.remove(p.getUniqueId()); oldu = true; }
        }
        if (oldu) { kaydet(); p.sendMessage(ChatColor.YELLOW + "Çalıştığın dükkân(lar)dan ayrıldın."); }
        else p.sendMessage(ChatColor.RED + "Bir dükkânda çalışmıyorsun.");
    }

    // ------------------------------------------------------------------ İŞ MENÜSÜ
    private void isAnaMenu(Player p) {
        List<Dukkan> l = dukkanlariOf(p.getUniqueId());
        if (l.isEmpty()) {
            p.sendMessage(ChatColor.RED + "Hiç dükkânın yok. Kurmak için claim'inde /dukkan kur yaz (" + para(kurulumUcreti()) + ").");
            return;
        }
        Menu h = new Menu(Tip.IS_ANA, null);
        h.inv = Bukkit.createInventory(h, 27, ChatColor.DARK_GREEN + "İş Menüsü");
        int[] yerler = {11, 15, 13, 12, 14};
        for (int i = 0; i < l.size() && i < yerler.length; i++) {
            Dukkan d = l.get(i);
            h.inv.setItem(yerler[i], esya(Material.OAK_SIGN, ChatColor.GOLD + d.ad,
                    ruhsatDurumYazi(d), ChatColor.GRAY + "Çalışan: " + d.calisanPay.size() + "/" + maxCalisan(),
                    ChatColor.YELLOW + "Tıkla: yönet"));
            h.kisi[yerler[i]] = d.id;
        }
        p.openInventory(h.inv);
    }

    private void dukkanPaneli(Player p, Dukkan d) {
        Menu h = new Menu(Tip.IS_DUKKAN, d.id);
        h.inv = Bukkit.createInventory(h, 27, ChatColor.DARK_GREEN + d.ad);
        h.inv.setItem(4, esya(Material.OAK_SIGN, ChatColor.GOLD + d.ad, ruhsatDurumYazi(d),
                ChatColor.GRAY + "Vergi: %" + (int) vergiYuzde() + " (başkan belirler)",
                d.tabelaVar ? ChatColor.GRAY + "Tabela: yerleştirilmiş" : ChatColor.RED + "Tabela henüz yerleştirilmedi"));
        if (d.durum == 0) {
            h.inv.setItem(10, esya(Material.EMERALD, ChatColor.GREEN + "Ruhsat Başvurusu Yap",
                    ChatColor.GRAY + "Tür: mevcut mesleğin (" + turAdi(meslekOf(p.getUniqueId())) + ")",
                    ChatColor.GRAY + "Ücret: " + ChatColor.WHITE + para(ruhsatUcreti()),
                    ChatColor.GRAY + "Başkan onaylar; " + (onayBeklemeMs() / 3_600_000L) + " saatte karar vermezse %50-%50 zar atılır.",
                    ChatColor.GRAY + "Reddedilirse ücret iade edilir."));
        } else if (d.durum == 1) {
            long kalan = Math.max(0, d.basvuruMs + onayBeklemeMs() - System.currentTimeMillis());
            h.inv.setItem(10, esya(Material.CLOCK, ChatColor.YELLOW + "Başvuru bekliyor",
                    ChatColor.GRAY + "Kalan süre: " + MeslekSistemi.sureYaz(kalan)));
        } else {
            boolean uyumlu = d.tur != null && d.tur.equalsIgnoreCase(meslekOf(p.getUniqueId()));
            if (!uyumlu) {
                h.inv.setItem(10, esya(Material.EMERALD, ChatColor.GREEN + "Ruhsatı mesleğime çevir",
                        ChatColor.GRAY + turAdi(d.tur) + " -> " + turAdi(meslekOf(p.getUniqueId())),
                        ChatColor.GRAY + "Ücret: " + ChatColor.WHITE + para(ruhsatDegistirmeUcreti())));
            } else {
                h.inv.setItem(10, esya(Material.LIME_CONCRETE, ChatColor.GREEN + "Ruhsat geçerli", ruhsatDurumYazi(d)));
            }
        }
        h.inv.setItem(12, esya(Material.PLAYER_HEAD, ChatColor.AQUA + "Çalışanlar",
                ChatColor.GRAY + "Çalışan al / çıkar, satış payını belirle", ChatColor.GRAY + "Mevcut: " + d.calisanPay.size() + "/" + maxCalisan()));
        if ("tamirci".equals(d.tur)) {
            h.inv.setItem(14, esya(Material.ANVIL, ChatColor.YELLOW + "Tamir fiyatı: " + para(d.tamirUcret),
                    ChatColor.GRAY + "Tam hasarlı demir alet için ücret.", ChatColor.GRAY + "Tıkla, sonra sohbete yeni fiyatı yaz."));
        }
        if (!d.tabelaVar) {
            h.inv.setItem(16, esya(Material.OAK_SIGN, ChatColor.GOLD + "Yeni tabela al",
                    ChatColor.GRAY + "Tabelanı claim'ine yerleştir."));
        }
        h.inv.setItem(22, esya(Material.ARROW, ChatColor.YELLOW + "Geri"));
        p.openInventory(h.inv);
    }

    private void calisanMenu(Player p, Dukkan d) {
        Menu h = new Menu(Tip.IS_CALISAN, d.id);
        h.inv = Bukkit.createInventory(h, 36, ChatColor.DARK_GREEN + "Çalışanlar: " + d.ad);
        int s = 0;
        for (Map.Entry<UUID, Integer> e : d.calisanPay.entrySet()) {
            if (s >= 27) break;
            ItemStack kafa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) kafa.getItemMeta();
            sm.setOwningPlayer(Bukkit.getOfflinePlayer(e.getKey()));
            sm.setDisplayName(ChatColor.AQUA + d.calisanAd.getOrDefault(e.getKey(), "?"));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Satış payı: " + ChatColor.WHITE + "%" + e.getValue());
            lore.add(ChatColor.YELLOW + "Tıkla: pay oranını belirle (sohbete yaz)");
            lore.add(ChatColor.RED + "Shift + tıkla: işten çıkar");
            sm.setLore(lore);
            kafa.setItemMeta(sm);
            h.inv.setItem(s, kafa);
            h.kisi[s] = e.getKey();
            s++;
        }
        h.inv.setItem(30, esya(Material.EMERALD, ChatColor.GREEN + "Çalışan Al",
                ChatColor.GRAY + "Tıkla, sonra sohbete oyuncu adını yaz.", ChatColor.GRAY + "Oyuncu çevrimiçi olmalı ve /dukkan kabul demeli."));
        h.inv.setItem(31, esya(Material.ARROW, ChatColor.YELLOW + "Geri"));
        p.openInventory(h.inv);
    }

    // ------------------------------------------------------------------ BAŞKAN MENÜSÜ
    private void baskanMenu(Player p) {
        Menu h = new Menu(Tip.BASKAN, null);
        h.inv = Bukkit.createInventory(h, 27, ChatColor.DARK_BLUE + "Başkan Menüsü");
        double v = vergiYuzde();
        h.inv.setItem(9, esya(Material.RED_CONCRETE, ChatColor.RED + "Vergi -5", ChatColor.GRAY + "Dükkân satış vergisini 5 puan düşür"));
        h.inv.setItem(10, esya(Material.ORANGE_CONCRETE, ChatColor.GOLD + "Vergi -1"));
        h.inv.setItem(13, esya(Material.GOLD_INGOT, ChatColor.YELLOW + "Dükkân vergisi: %" + (int) v,
                ChatColor.GRAY + "Her dükkân satışından belediye kasasına", ChatColor.GRAY + "Sınır: %0 - %" + MAX_VERGI));
        h.inv.setItem(16, esya(Material.LIME_CONCRETE, ChatColor.GREEN + "Vergi +5"));
        h.inv.setItem(15, esya(Material.GREEN_CONCRETE, ChatColor.GREEN + "Vergi +1"));
        h.inv.setItem(11, esya(plugin.maaslarAcik ? Material.LIME_DYE : Material.GRAY_DYE,
                plugin.maaslarAcik ? ChatColor.GREEN + "Maaşlar: AÇIK" : ChatColor.RED + "Maaşlar: KAPALI",
                ChatColor.YELLOW + "Tıkla: aç / kapat"));
        long bekleyen = dukkanlar.values().stream().filter(d -> d.durum == 1).count();
        h.inv.setItem(22, esya(Material.PAPER, ChatColor.AQUA + "Ruhsat başvuruları (" + bekleyen + ")",
                ChatColor.GRAY + "Bekleyen başvuruları onayla / reddet"));
        p.openInventory(h.inv);
    }

    private void baskanRuhsatMenu(Player p) {
        Menu h = new Menu(Tip.BASKAN_RUHSAT, null);
        h.inv = Bukkit.createInventory(h, 54, ChatColor.DARK_BLUE + "Ruhsat Başvuruları");
        int s = 0;
        for (Dukkan d : dukkanlar.values()) {
            if (d.durum != 1 || s >= 45) continue;
            long kalan = Math.max(0, d.basvuruMs + onayBeklemeMs() - System.currentTimeMillis());
            h.inv.setItem(s, esya(Material.PAPER, ChatColor.GOLD + d.ad,
                    ChatColor.GRAY + "Sahip: " + ChatColor.WHITE + d.sahipAd,
                    ChatColor.GRAY + "Tür: " + ChatColor.WHITE + turAdi(d.tur),
                    ChatColor.GRAY + "Süre: " + MeslekSistemi.sureYaz(kalan) + " (dolunca zar atılır)",
                    ChatColor.GREEN + "Sol tık: ONAYLA", ChatColor.RED + "Sağ tık: REDDET"));
            h.kisi[s] = d.id;
            s++;
        }
        h.inv.setItem(49, esya(Material.ARROW, ChatColor.YELLOW + "Geri"));
        p.openInventory(h.inv);
    }

    // ------------------------------------------------------------------ RUHSAT
    private void ruhsatBasvur(Player p, Dukkan d) {
        if (d.durum != 0) return;
        String m = meslekOf(p.getUniqueId()).toLowerCase(Locale.ROOT);
        if (m.equals("vatandas") || m.equals("gocmen") || m.equals("default") || m.equals("mahkum") || m.equals("belediyebaskani")
                || !plugin.meslekGorunumAdlari.containsKey(m)) {
            p.sendMessage(ChatColor.RED + "Ruhsat için bir mesleğin olmalı (vatandaş, göçmen ve mahkum alamaz).");
            return;
        }
        double ucret = ruhsatUcreti();
        if (!paraAl(p.getUniqueId(), ucret)) {
            p.sendMessage(ChatColor.RED + "Banka hesabında yeterli para yok. Ruhsat ücreti: " + para(ucret)); return;
        }
        d.escrow = ucret;
        d.tur = m;
        d.durum = 1;
        d.basvuruMs = System.currentTimeMillis();
        kaydet();
        plugin.veriKaydet();
        plugin.ekonomiLog("dukkan-ruhsat-basvuru", p.getName(), d.ad + " | " + turAdi(m) + " | bloke " + para(ucret));
        p.sendMessage(ChatColor.GREEN + "Ruhsat başvurun belediye başkanına iletildi (" + para(ucret) + " bloke edildi). Reddedilirse iade edilir.");
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (baskanMi(o)) o.sendMessage(ChatColor.AQUA + "[Belediye] " + d.sahipAd + " '" + d.ad + "' için " + turAdi(m) + " ruhsatı başvurusu yaptı. /baskanmenu");
        }
    }

    private void ruhsatTuruCevir(Player p, Dukkan d) {
        if (d.durum != 2) return;
        String m = meslekOf(p.getUniqueId()).toLowerCase(Locale.ROOT);
        if (m.equals(d.tur) || !plugin.meslekGorunumAdlari.containsKey(m) || m.equals("vatandas") || m.equals("gocmen")
                || m.equals("default") || m.equals("mahkum") || m.equals("belediyebaskani")) {
            p.sendMessage(ChatColor.RED + "Ruhsatı çevirebilmek için ruhsat türünden farklı geçerli bir mesleğin olmalı."); return;
        }
        double ucret = ruhsatDegistirmeUcreti();
        if (!paraAl(p.getUniqueId(), ucret)) {
            p.sendMessage(ChatColor.RED + "Yeterli paran yok. Değiştirme ücreti: " + para(ucret)); return;
        }
        plugin.kasayaParaEkle(ucret);
        String eski = d.tur;
        d.tur = m;
        kaydet();
        plugin.veriKaydet();
        tabelaGuncelle(d);
        plugin.ekonomiLog("dukkan-ruhsat-cevir", p.getName(), d.ad + " | " + eski + " -> " + m + " | " + para(ucret));
        p.sendMessage(ChatColor.GREEN + "Ruhsatın " + turAdi(m) + " türüne çevrildi (" + para(ucret) + ").");
    }

    private void ruhsatOnayla(Dukkan d, String kim) {
        if (d.durum != 1) return;
        d.durum = 2;
        d.ruhsatNo = "R-" + (++ruhsatSayac);
        plugin.kasayaParaEkle(d.escrow);
        plugin.ekonomiLog("dukkan-ruhsat-onay", kim, d.ad + " | " + d.ruhsatNo + " | " + para(d.escrow));
        d.escrow = 0;
        d.bildirim = "Ruhsat başvurun ONAYLANDI! (" + d.ruhsatNo + ")";
        kaydet();
        plugin.veriKaydet();
        tabelaGuncelle(d);
        Player s = Bukkit.getPlayer(d.sahip);
        if (s != null) { s.sendMessage(ChatColor.GREEN + d.bildirim); d.bildirim = null; kaydet(); }
        if (plugin.haberManager != null) {
            plugin.haberManager.yaz("EKONOMİ", "Yeni dükkân açıldı", d.sahipAd + ", '" + d.ad + "' adlı " + turAdi(d.tur) + " dükkânının ruhsatını aldı.");
        }
    }

    private void ruhsatReddet(Dukkan d, String kim) {
        if (d.durum != 1) return;
        d.durum = 0;
        paraVer(d.sahip, d.escrow);
        plugin.ekonomiLog("dukkan-ruhsat-red", kim, d.ad + " | iade " + para(d.escrow));
        d.escrow = 0;
        d.tur = null;
        d.bildirim = "Ruhsat başvurun REDDEDİLDİ, ücret hesabına iade edildi.";
        kaydet();
        plugin.veriKaydet();
        Player s = Bukkit.getPlayer(d.sahip);
        if (s != null) { s.sendMessage(ChatColor.RED + d.bildirim); d.bildirim = null; kaydet(); }
    }

    private void sureKontrol() {
        long simdi = System.currentTimeMillis();
        for (Dukkan d : new ArrayList<>(dukkanlar.values())) {
            if (d.durum == 1 && simdi - d.basvuruMs >= onayBeklemeMs()) {
                boolean onay = rnd.nextBoolean();
                Bukkit.broadcastMessage(ChatColor.AQUA + "[Belediye] " + d.sahipAd + " adlı dükkân sahibinin ruhsat başvurusu süresinde karara bağlanmadı, zar atıldı: "
                        + (onay ? ChatColor.GREEN + "ONAY" : ChatColor.RED + "RET"));
                if (onay) ruhsatOnayla(d, "zar"); else ruhsatReddet(d, "zar");
            }
        }
    }

    @EventHandler
    public void onGiris(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        for (Dukkan d : dukkanlariOf(p.getUniqueId())) {
            if (d.bildirim != null) {
                String msg = d.bildirim;
                d.bildirim = null;
                kaydet();
                Bukkit.getScheduler().runTaskLater(plugin, () -> p.sendMessage(ChatColor.AQUA + "[" + d.ad + "] " + msg), 40L);
            }
        }
    }

    // ------------------------------------------------------------------ TABELA
    @EventHandler(ignoreCancelled = true)
    public void onYerlestir(BlockPlaceEvent e) {
        ItemStack it = e.getItemInHand();
        if (it == null || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(tabelaKey, PersistentDataType.STRING);
        if (id == null) return;
        Dukkan d;
        try { d = dukkanlar.get(UUID.fromString(id)); } catch (Exception ex) { d = null; }
        Player p = e.getPlayer();
        if (d == null) { e.setCancelled(true); p.sendMessage(ChatColor.RED + "Bu tabelanın dükkânı artık yok."); return; }
        if (!d.sahip.equals(p.getUniqueId())) { e.setCancelled(true); p.sendMessage(ChatColor.RED + "Bu tabela sana ait değil."); return; }
        me.ryanhamshire.GriefPrevention.Claim c = claimAt(e.getBlockPlaced().getLocation());
        if (c == null || c.getID() != d.claimId) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Tabela dükkânının claim'inin içine yerleştirilmeli.");
            return;
        }
        if (d.tabelaVar) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Bu dükkânın zaten bir tabelası var. Önce eskisini kır.");
            return;
        }
        Block b = e.getBlockPlaced();
        d.tabelaVar = true; d.dunya = b.getWorld().getName(); d.x = b.getX(); d.y = b.getY(); d.z = b.getZ();
        kaydet();
        final Dukkan fd = d;
        Bukkit.getScheduler().runTask(plugin, () -> tabelaGuncelle(fd));
        p.sendMessage(ChatColor.GREEN + "Dükkân tabelası yerleştirildi.");
    }

    private static Component lc(String s) { return LegacyComponentSerializer.legacySection().deserialize(s); }

    private void tabelaGuncelle(Dukkan d) {
        if (!d.tabelaVar) return;
        World w = Bukkit.getWorld(d.dunya == null ? "" : d.dunya);
        if (w == null) return;
        Block b = w.getBlockAt(d.x, d.y, d.z);
        if (!(b.getState() instanceof Sign sign)) return;
        sign.getSide(Side.FRONT).line(0, lc(ChatColor.GOLD + d.ad));
        sign.getSide(Side.FRONT).line(1, lc(ChatColor.DARK_GRAY + "Sahip: " + d.sahipAd));
        sign.getSide(Side.FRONT).line(2, lc(d.durum == 2 ? ChatColor.DARK_GREEN + turAdi(d.tur) : ChatColor.DARK_RED + "RUHSATSIZ"));
        sign.getSide(Side.FRONT).line(3, lc(d.durum == 2 ? ChatColor.DARK_GRAY + d.ruhsatNo : ""));
        sign.setWaxed(true);
        sign.update();
    }

    @EventHandler(ignoreCancelled = true)
    public void onKir(BlockBreakEvent e) {
        Dukkan d = tabeladanDukkan(e.getBlock());
        if (d == null) return;
        Player p = e.getPlayer();
        if (!d.sahip.equals(p.getUniqueId())) {
            e.setCancelled(true);
            p.sendMessage(ChatColor.RED + "Başkasının dükkân tabelasını kıramazsın.");
            return;
        }
        e.setDropItems(false);
        d.tabelaVar = false;
        kaydet();
        p.getInventory().addItem(tabelaEsyasi(d)).values().forEach(k -> p.getWorld().dropItemNaturally(p.getLocation(), k));
        p.sendMessage(ChatColor.YELLOW + "Tabela geri alındı. Yeniden yerleştirebilirsin.");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTikTabela(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Dukkan d = tabeladanDukkan(e.getClickedBlock());
        if (d == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        musteriMenu(p, d);
    }

    private void musteriMenu(Player p, Dukkan d) {
        Menu h = new Menu(Tip.MUSTERI, d.id);
        h.inv = Bukkit.createInventory(h, 27, ChatColor.DARK_GREEN + d.ad);
        List<String> bilgi = new ArrayList<>();
        bilgi.add(ChatColor.GRAY + "Sahip: " + ChatColor.WHITE + d.sahipAd);
        bilgi.add(ChatColor.GRAY + "Ruhsat: " + ruhsatDurumYazi(d));
        if (!d.calisanPay.isEmpty()) bilgi.add(ChatColor.GRAY + "Çalışan: " + String.join(", ", d.calisanAd.values()));
        if (polisMi(p)) {
            bilgi.add(ChatColor.DARK_AQUA + "[Polis kontrolü] Ruhsat " + (d.durum == 2 ? "geçerli." : "GEÇERSİZ / yok."));
            bilgi.add(ChatColor.DARK_AQUA + "Sahibin mesleği şu an: " + turAdi(meslekOf(d.sahip)));
        }
        h.inv.setItem(11, esya(Material.OAK_SIGN, ChatColor.GOLD + d.ad, bilgi.toArray(new String[0])));
        if (d.durum == 2 && "tamirci".equals(d.tur)) {
            h.inv.setItem(13, esya(Material.ANVIL, ChatColor.YELLOW + "Tamir Hizmeti",
                    ChatColor.GRAY + "Hasarlı eşyanı tamirciye onar.", ChatColor.GRAY + "Enchant kaybolmaz, XP harcanmaz.",
                    ChatColor.GRAY + "Tam hasarlı demir alet: " + para(d.tamirUcret)));
        }
        if (d.sahip.equals(p.getUniqueId())) {
            h.inv.setItem(15, esya(Material.EMERALD, ChatColor.GREEN + "İş Menüsü", ChatColor.GRAY + "Dükkânını yönet"));
        }
        p.openInventory(h.inv);
    }

    // ------------------------------------------------------------------ TAMİR
    private static final class Malzeme { final Material mat; final int adet; final double carpan; Malzeme(Material m, int a, double c) { mat = m; adet = a; carpan = c; } }

    private static Material mat(String ad, Material yedek) {
        Material m = Material.matchMaterial(ad);
        return m == null ? yedek : m;
    }

    /** Eşyanın tam tamiri için gereken malzeme (kademeye göre) ve ücret çarpanı. */
    private Malzeme malzemeOf(Material t) {
        String n = t.name();
        if (n.startsWith("WOODEN_")) return new Malzeme(mat("OAK_PLANKS", Material.STICK), 2, 0.25);
        if (n.startsWith("STONE_")) return new Malzeme(mat("COBBLESTONE", Material.STICK), 2, 0.5);
        if (n.startsWith("COPPER_")) return new Malzeme(mat("COPPER_INGOT", Material.IRON_INGOT), 3, 0.6);
        if (n.startsWith("LEATHER_")) return new Malzeme(mat("LEATHER", Material.IRON_INGOT), 3, 0.5);
        if (n.startsWith("CHAINMAIL_")) return new Malzeme(Material.IRON_INGOT, 3, 0.75);
        if (n.startsWith("GOLDEN_")) return new Malzeme(mat("GOLD_INGOT", Material.IRON_INGOT), 3, 0.5);
        if (n.startsWith("IRON_")) return new Malzeme(Material.IRON_INGOT, 3, 1.0);
        if (n.startsWith("DIAMOND_")) return new Malzeme(mat("DIAMOND", Material.IRON_INGOT), 3, 2.0);
        if (n.startsWith("NETHERITE_")) return new Malzeme(mat("NETHERITE_INGOT", Material.IRON_INGOT), 1, 4.0);
        if (n.equals("ELYTRA")) return new Malzeme(mat("PHANTOM_MEMBRANE", Material.IRON_INGOT), 2, 2.0);
        if (n.equals("BOW") || n.equals("CROSSBOW") || n.equals("FISHING_ROD")) return new Malzeme(mat("STRING", Material.IRON_INGOT), 2, 0.5);
        if (n.equals("TURTLE_HELMET")) return new Malzeme(mat("TURTLE_SCUTE", Material.IRON_INGOT), 2, 1.0);
        return new Malzeme(Material.IRON_INGOT, 2, 1.0);
    }

    private static double eksikOran(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return 0;
        if (!(it.getItemMeta() instanceof Damageable dm)) return 0;
        int max = it.getType().getMaxDurability();
        if (max <= 0) return 0;
        return Math.max(0, Math.min(1.0, dm.getDamage() / (double) max));
    }

    private double tamirFiyat(Dukkan d, ItemStack it) {
        return Math.max(1.0, Math.round(eksikOran(it) * d.tamirUcret * malzemeOf(it.getType()).carpan * 100.0) / 100.0);
    }

    private int gerekenMalzeme(ItemStack it) {
        Malzeme m = malzemeOf(it.getType());
        return Math.max(1, (int) Math.ceil(eksikOran(it) * m.adet));
    }

    private void tamirMenu(Player p, Dukkan d) {
        Menu h = new Menu(Tip.TAMIR, d.id);
        h.inv = Bukkit.createInventory(h, 54, ChatColor.DARK_GREEN + "Tamir: " + d.ad);
        ItemStack[] icerik = p.getInventory().getContents();
        int s = 0;
        for (int i = 0; i < icerik.length && s < 45; i++) {
            ItemStack it = icerik[i];
            if (eksikOran(it) <= 0) continue;
            ItemStack gos = it.clone();
            ItemMeta meta = gos.getItemMeta();
            List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add("");
            lore.add(ChatColor.GRAY + "Hasar: %" + (int) Math.round(eksikOran(it) * 100));
            lore.add(ChatColor.YELLOW + "Tamir ücreti: " + para(tamirFiyat(d, it)));
            lore.add(ChatColor.GRAY + "Tamircinin harcayacağı malzeme: " + gerekenMalzeme(it) + "x " + malzemeOf(it.getType()).mat.name().toLowerCase(Locale.ROOT));
            lore.add(ChatColor.GREEN + "Tıkla: onar");
            meta.setLore(lore);
            gos.setItemMeta(meta);
            h.inv.setItem(s, gos);
            h.dizin[s] = i;
            s++;
        }
        h.inv.setItem(49, esya(Material.ARROW, ChatColor.YELLOW + "Geri"));
        if (s == 0) h.inv.setItem(22, esya(Material.BARRIER, ChatColor.GRAY + "Onarılacak hasarlı eşyan yok"));
        p.openInventory(h.inv);
    }

    /** Dükkânda şu an tamir edebilecek bir tamirci (sahip ya da çalışan) ve gerekli malzemesi var mı? */
    private Player tamirciBul(Dukkan d, Malzeme m, int adet, Player musteri) {
        List<UUID> adaylar = new ArrayList<>();
        adaylar.add(d.sahip);
        adaylar.addAll(d.calisanPay.keySet());
        for (UUID u : adaylar) {
            Player t = Bukkit.getPlayer(u);
            if (t == null || !t.isOnline()) continue;
            if (!meslekOf(u).equalsIgnoreCase("tamirci")) continue;
            me.ryanhamshire.GriefPrevention.Claim c = claimAt(t.getLocation());
            if (c == null || c.getID() != d.claimId) continue;
            if (t.getInventory().containsAtLeast(new ItemStack(m.mat), adet)) return t;
        }
        return null;
    }

    private void tamirEt(Player musteri, Dukkan d, int envanterIndeksi) {
        if (d.durum != 2 || !"tamirci".equals(d.tur)) { musteri.sendMessage(ChatColor.RED + "Bu dükkânın tamir ruhsatı yok."); return; }
        ItemStack it = musteri.getInventory().getItem(envanterIndeksi);
        if (eksikOran(it) <= 0) { musteri.sendMessage(ChatColor.RED + "Eşya artık hasarlı değil."); tamirMenu(musteri, d); return; }
        Malzeme m = malzemeOf(it.getType());
        int adet = gerekenMalzeme(it);
        double fiyat = tamirFiyat(d, it);
        me.ryanhamshire.GriefPrevention.Claim mc = claimAt(musteri.getLocation());
        if (mc == null || mc.getID() != d.claimId) { musteri.sendMessage(ChatColor.RED + "Tamir için dükkânın içinde olmalısın."); return; }
        Player tamirci = tamirciBul(d, m, adet, musteri);
        if (tamirci == null) {
            musteri.sendMessage(ChatColor.RED + "Dükkânda şu an bu eşyayı onarabilecek bir tamirci yok (tamirci dükkânda ve " + adet + "x "
                    + m.mat.name().toLowerCase(Locale.ROOT) + " malzemesi üzerinde olmalı).");
            return;
        }
        if (!paraAl(musteri.getUniqueId(), fiyat)) { musteri.sendMessage(ChatColor.RED + "Banka hesabında yeterli para yok: " + para(fiyat)); return; }

        // Malzeme tamircinin envanterinden düşer
        tamirci.getInventory().removeItem(new ItemStack(m.mat, adet));
        // Onar: enchant ve diğer meta korunur, XP harcanmaz
        ItemMeta meta = it.getItemMeta();
        ((Damageable) meta).setDamage(0);
        it.setItemMeta(meta);
        musteri.getInventory().setItem(envanterIndeksi, it);

        // Para dağıtımı: vergi -> kasa, kalan: çalışan payı + sahip
        double vergi = Math.round(fiyat * (vergiYuzde() / 100.0) * 100.0) / 100.0;
        if (vergi > 0) plugin.kasayaParaEkle(vergi);
        double net = Math.round((fiyat - vergi) * 100.0) / 100.0;
        double calisanPay = 0;
        if (!tamirci.getUniqueId().equals(d.sahip)) {
            int yuzde = d.calisanPay.getOrDefault(tamirci.getUniqueId(), 0);
            calisanPay = Math.round(net * yuzde) / 100.0;
            if (calisanPay > 0) paraVer(tamirci.getUniqueId(), calisanPay);
        }
        double sahipPay = Math.round((net - calisanPay) * 100.0) / 100.0;
        paraVer(d.sahip, sahipPay);
        plugin.veriKaydet();
        plugin.ekonomiLog("dukkan-tamir", musteri.getName(), d.ad + " | " + it.getType() + " | " + para(fiyat) + " | vergi " + para(vergi)
                + " | tamirci " + tamirci.getName() + " pay " + para(calisanPay) + " | sahip " + para(sahipPay));
        musteri.playSound(musteri.getLocation(), Sound.BLOCK_ANVIL_USE, 0.8f, 1.1f);
        musteri.sendMessage(ChatColor.GREEN + "Eşyan " + tamirci.getName() + " tarafından onarıldı (" + para(fiyat) + ").");
        tamirci.sendMessage(ChatColor.GREEN + musteri.getName() + " için " + adet + "x " + m.mat.name().toLowerCase(Locale.ROOT) + " harcayarak tamir ettin."
                + (calisanPay > 0 ? " Payın: " + para(calisanPay) : ""));
        tamirMenu(musteri, d);
    }

    // ------------------------------------------------------------------ MENÜ TIKLAMALARI
    @EventHandler
    public void onSurukle(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onTikla(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= e.getView().getTopInventory().getSize()) return;
        Dukkan d = menu.dukkan == null ? null : dukkanlar.get(menu.dukkan);

        switch (menu.tip) {
            case IS_ANA: {
                UUID id = menu.kisi[slot];
                Dukkan x = id == null ? null : dukkanlar.get(id);
                if (x != null && x.sahip.equals(p.getUniqueId())) dukkanPaneli(p, x);
                break;
            }
            case IS_DUKKAN: {
                if (d == null || !d.sahip.equals(p.getUniqueId())) { p.closeInventory(); break; }
                if (slot == 22) isAnaMenu(p);
                else if (slot == 10) {
                    if (d.durum == 0) { ruhsatBasvur(p, d); dukkanPaneli(p, d); }
                    else if (d.durum == 2 && !(d.tur != null && d.tur.equalsIgnoreCase(meslekOf(p.getUniqueId())))) { ruhsatTuruCevir(p, d); dukkanPaneli(p, d); }
                } else if (slot == 12) calisanMenu(p, d);
                else if (slot == 14 && "tamirci".equals(d.tur)) girdiIste(p, new Girdi("tamirfiyat", d.id, null),
                        "Yeni tam tamir ücretini yaz (" + (int) TAMIR_UCRET_MIN + "-" + (int) TAMIR_UCRET_MAX + "). İptal için 'iptal' yaz.");
                else if (slot == 16 && !d.tabelaVar) {
                    p.getInventory().addItem(tabelaEsyasi(d)).values().forEach(k -> p.getWorld().dropItemNaturally(p.getLocation(), k));
                    p.sendMessage(ChatColor.GREEN + "Yeni tabela verildi.");
                }
                break;
            }
            case IS_CALISAN: {
                if (d == null || !d.sahip.equals(p.getUniqueId())) { p.closeInventory(); break; }
                if (slot == 31) { dukkanPaneli(p, d); break; }
                if (slot == 30) {
                    if (d.calisanPay.size() >= maxCalisan()) { p.sendMessage(ChatColor.RED + "Çalışan sınırı dolu."); break; }
                    girdiIste(p, new Girdi("calisanal", d.id, null), "Çalışan olarak almak istediğin (çevrimiçi) oyuncunun adını yaz. İptal için 'iptal'.");
                    break;
                }
                UUID cu = menu.kisi[slot];
                if (cu == null || !d.calisanPay.containsKey(cu)) break;
                if (e.isShiftClick()) {
                    d.calisanPay.remove(cu);
                    String ad = d.calisanAd.remove(cu);
                    kaydet();
                    p.sendMessage(ChatColor.YELLOW + (ad == null ? "Çalışan" : ad) + " işten çıkarıldı.");
                    Player o = Bukkit.getPlayer(cu);
                    if (o != null) o.sendMessage(ChatColor.YELLOW + d.ad + " dükkânından çıkarıldın.");
                    calisanMenu(p, d);
                } else {
                    girdiIste(p, new Girdi("calisanpay", d.id, cu),
                            d.calisanAd.getOrDefault(cu, "?") + " için satış payını yaz (0-" + MAX_CALISAN_PAY + "). Örn: 15. İptal için 'iptal'.");
                }
                break;
            }
            case MUSTERI: {
                if (d == null) { p.closeInventory(); break; }
                if (slot == 13 && d.durum == 2 && "tamirci".equals(d.tur)) tamirMenu(p, d);
                else if (slot == 15 && d.sahip.equals(p.getUniqueId())) dukkanPaneli(p, d);
                break;
            }
            case TAMIR: {
                if (d == null) { p.closeInventory(); break; }
                if (slot == 49) { musteriMenu(p, d); break; }
                if (slot < 45 && e.getView().getTopInventory().getItem(slot) != null) tamirEt(p, d, menu.dizin[slot]);
                break;
            }
            case BASKAN: {
                if (!baskanMi(p)) { p.closeInventory(); break; }
                double v = vergiYuzde();
                double yeni = v;
                if (slot == 9) yeni = v - 5; else if (slot == 10) yeni = v - 1; else if (slot == 15) yeni = v + 1; else if (slot == 16) yeni = v + 5;
                if (yeni != v) {
                    yeni = Math.max(0, Math.min(MAX_VERGI, yeni));
                    plugin.getConfig().set("dukkan-vergi-yuzde", yeni);
                    plugin.saveConfig();
                    plugin.ekonomiLog("dukkan-vergi", p.getName(), "%" + v + " -> %" + yeni);
                    baskanMenu(p);
                } else if (slot == 11) {
                    plugin.maaslarAcik = !plugin.maaslarAcik;
                    plugin.veriKaydet();
                    plugin.ekonomiLog("maaslar", p.getName(), plugin.maaslarAcik ? "acildi" : "kapatildi");
                    baskanMenu(p);
                } else if (slot == 22) baskanRuhsatMenu(p);
                break;
            }
            case BASKAN_RUHSAT: {
                if (!baskanMi(p)) { p.closeInventory(); break; }
                if (slot == 49) { baskanMenu(p); break; }
                UUID id = menu.kisi[slot];
                Dukkan x = id == null ? null : dukkanlar.get(id);
                if (x == null || x.durum != 1) break;
                if (e.isLeftClick()) { ruhsatOnayla(x, p.getName()); p.sendMessage(ChatColor.GREEN + x.ad + " ruhsatı onaylandı."); }
                else if (e.isRightClick()) { ruhsatReddet(x, p.getName()); p.sendMessage(ChatColor.RED + x.ad + " ruhsatı reddedildi, ücret iade edildi."); }
                baskanRuhsatMenu(p);
                break;
            }
            default: break;
        }
    }

    // ------------------------------------------------------------------ SOHBETTEN GİRDİ
    private void girdiIste(Player p, Girdi g, String mesaj) {
        girdiler.put(p.getUniqueId(), g);
        p.closeInventory();
        p.sendMessage(ChatColor.AQUA + mesaj);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSohbet(AsyncChatEvent e) {
        Player p = e.getPlayer();
        Girdi g = girdiler.get(p.getUniqueId());
        if (g == null) return;
        e.setCancelled(true);
        final String metin = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> girdiIsle(p, g, metin));
    }

    private void girdiIsle(Player p, Girdi g, String metin) {
        girdiler.remove(p.getUniqueId());
        if (metin.equalsIgnoreCase("iptal")) { p.sendMessage(ChatColor.GRAY + "İptal edildi."); return; }
        Dukkan d = dukkanlar.get(g.dukkan);
        if (d == null || !d.sahip.equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Dükkân bulunamadı."); return; }
        switch (g.tur) {
            case "calisanpay": {
                int v;
                try { v = Integer.parseInt(metin.replace("%", "").trim()); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Geçerli bir sayı yazmalısın. Menüden tekrar dene."); return; }
                if (v < 0 || v > MAX_CALISAN_PAY) { p.sendMessage(ChatColor.RED + "Pay 0 ile " + MAX_CALISAN_PAY + " arasında olmalı."); return; }
                if (!d.calisanPay.containsKey(g.hedef)) return;
                d.calisanPay.put(g.hedef, v);
                kaydet();
                p.sendMessage(ChatColor.GREEN + d.calisanAd.getOrDefault(g.hedef, "?") + " için satış payı %" + v + " yapıldı.");
                Player o = Bukkit.getPlayer(g.hedef);
                if (o != null) o.sendMessage(ChatColor.GREEN + d.ad + " dükkânında satış payın %" + v + " oldu.");
                calisanMenu(p, d);
                break;
            }
            case "calisanal": {
                Player h = Bukkit.getPlayerExact(metin);
                if (h == null) { p.sendMessage(ChatColor.RED + "Oyuncu çevrimiçi değil."); return; }
                if (h.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Kendini çalışan olarak alamazsın."); return; }
                if (d.calisanPay.containsKey(h.getUniqueId())) { p.sendMessage(ChatColor.RED + "Zaten çalışanın."); return; }
                String hm = meslekOf(h.getUniqueId());
                if (hm.equalsIgnoreCase("gocmen") || hm.equalsIgnoreCase("default") || hm.equalsIgnoreCase("mahkum")) {
                    p.sendMessage(ChatColor.RED + "Göçmen ve mahkumlar çalışan olamaz."); return;
                }
                davetler.put(h.getUniqueId(), new Davet(d.id, System.currentTimeMillis()));
                p.sendMessage(ChatColor.GREEN + h.getName() + " adlı oyuncuya iş teklifi gönderildi.");
                h.sendMessage(ChatColor.AQUA + p.getName() + " seni '" + d.ad + "' dükkânında çalışmaya çağırıyor. Kabul için: /dukkan kabul (5 dk)");
                break;
            }
            case "tamirfiyat": {
                double v;
                try { v = Double.parseDouble(metin.replace("$", "").replace(",", ".").trim()); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Geçerli bir sayı yazmalısın."); return; }
                if (!(v >= TAMIR_UCRET_MIN && v <= TAMIR_UCRET_MAX)) { p.sendMessage(ChatColor.RED + "Ücret " + (int) TAMIR_UCRET_MIN + " ile " + (int) TAMIR_UCRET_MAX + " arasında olmalı."); return; }
                d.tamirUcret = Math.round(v * 100.0) / 100.0;
                kaydet();
                p.sendMessage(ChatColor.GREEN + "Tam tamir ücreti " + para(d.tamirUcret) + " yapıldı.");
                dukkanPaneli(p, d);
                break;
            }
            default: break;
        }
    }

    @EventHandler
    public void onCikis(PlayerQuitEvent e) {
        girdiler.remove(e.getPlayer().getUniqueId());
        davetler.remove(e.getPlayer().getUniqueId());
    }

    /** Dışarıdan (ör. EtkilesimManager) sahibin dükkân sayısı. */
    public int dukkanSayisi(UUID sahip) { return dukkanlariOf(sahip).size(); }
}
