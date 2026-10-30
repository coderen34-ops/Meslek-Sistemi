package me.mesleksistemi.kacak;

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
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Lightable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import me.mesleksistemi.MeslekSistemi;

/**
 * Kaçakçı NPC'si ve gizli kazan üretimi (tamamen oyun içi, uydurma maddeler).
 *
 * KAÇAKÇI: admin /kacakci nokta ekle ile istediği kadar nokta ekler; /kacakci saat ekle ile oyun saati aralıkları
 *   (varsayılan 21:00-01:00 ve 02:00-06:00) belirler. Her aralıkta noktalardan biri rastgele seçilir, tüccar orada belirir. Ekonomi: oyuncunun ödediği para BELEDİYE KASASINA gider,
 *   kaçakçının mal alırken ödediği para BELEDİYE KASASINDAN çekilir (para basılmaz; kasada yoksa alım reddedilir).
 *
 * KAZAN ÜRETİMİ (~10 dk): Gizli Kazan yere konur -> sırayla malzeme atılır (yanlış sıra = bozulur) -> ISINMA 4 dk
 *   (altında yanan kamp ateşi/ateş/lav gerekir) -> KARIŞTIRMA (4 pencere, her biri 15 sn, Shift+sağ tık) ->
 *   SOĞUMA 2 dk -> HAZIR. Kalite karıştırma başarısına göre 1-3. Duman/bulantı, rastgele polis ihbarı.
 *   Polis kazana sağ tıklayınca baskın: kazan el konur, sahibi yakındaysa 10 dk hapis + $5000 ceza.
 */
public class KacakciManager implements Listener, CommandExecutor {

    // ---------------------------------------------------------------- eşya tanımları
    private static final String ESYA_MASKE = "maske", ESYA_KAZAN = "kazan", ESYA_GOLGE = "golge_koku", ESYA_KIZIL = "kizil_kul", ESYA_TUZ = "mavi_tuz";

    private enum Asama { BOS, MALZEME, ISINMA, KARISTIRMA, SOGUMA, HAZIR }

    private static final class Kazan {
        String dunya; int x, y, z;
        UUID sahip; String sahipAd = "";
        KacakMal.Tur tur;
        List<String> eklenen = new ArrayList<>();
        Asama asama = Asama.BOS;
        int kalanSn;            // aşamanın kalan süresi (ISINMA/SOGUMA) ya da sonraki pencereye kalan (KARISTIRMA)
        int pencere;            // KARISTIRMA: kaçıncı pencere (0-4)
        int pencereAcikSn;      // >0 ise pencere açık
        int basari;
        int kalite = 1;
        int adet = 0;
        boolean ihbar1, ihbar2;

        String anahtar() { return dunya + ";" + x + ";" + y + ";" + z; }
    }

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private static final int PENCERE_SAYISI = 4;

    private final MeslekSistemi plugin;
    private final NamespacedKey esyaKey, npcKey;
    private final Map<String, Kazan> kazanlar = new HashMap<>();
    private final Map<UUID, Long> tuketimBekleme = new HashMap<>();
    private final Map<UUID, long[]> satisKota = new HashMap<>(); // {geceNo, satilanAdet}
    private final Random rnd = new Random();
    private final File veriDosyasi;
    private UUID npcId;

    public KacakciManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.esyaKey = new NamespacedKey(plugin, "kacak_esya");
        this.npcKey = new NamespacedKey(plugin, "kacakci_npc");
        this.veriDosyasi = new File(plugin.getDataFolder(), "kacakci-kazanlar.yml");

        plugin.getConfig().addDefault("kacakci.min-polis", 1);
        plugin.getConfig().addDefault("kacakci.fiyat.kazan", 2500.0);
        plugin.getConfig().addDefault("kacakci.fiyat.maske", 800.0);
        plugin.getConfig().addDefault("kacakci.saatler", List.of("21:00-01:00", "02:00-06:00"));
        plugin.getConfig().addDefault("kacakci.saat-turu", "oyun");            // oyun (Minecraft saati) | gercek (Europe/Istanbul)
        plugin.getConfig().addDefault("kacakci.rastgele.aktif", true);          // aralık içinde rastgele zamanlarda çıkıp kaybolsun
        plugin.getConfig().addDefault("kacakci.rastgele.kalma-dk", "10-25");    // her çıkışta ne kadar kalsın (gerçek dk)
        plugin.getConfig().addDefault("kacakci.rastgele.bekleme-dk", "10-40"); // (gerçek saat modu) kaybolunca ne kadar sonra yeniden çıksın (gerçek dk)
        // Oyun saati modunda bir gece aralığı sadece ~3,3 gerçek dakika sürdüğü için süreler daha kısa
        plugin.getConfig().addDefault("kacakci.rastgele-oyun.kalma-dk", "1-2");
        plugin.getConfig().addDefault("kacakci.rastgele-oyun.bekleme-dk", "0.5-1.5");
        plugin.getConfig().addDefault("kacakci.fiyat.golge-koku", 150.0);
        plugin.getConfig().addDefault("kacakci.fiyat.kizil-kul", 200.0);
        plugin.getConfig().addDefault("kacakci.fiyat.mavi-tuz", 120.0);
        plugin.getConfig().addDefault("kacakci.alis.kara-toz", 900.0);
        plugin.getConfig().addDefault("kacakci.alis.mavi-oz", 700.0);
        plugin.getConfig().addDefault("kacakci.gece-satis-kotasi", 12);
        plugin.getConfig().addDefault("kacakci.ihbar-sans-isinma", 0.6);
        plugin.getConfig().addDefault("kacakci.ihbar-sans-karistirma", 0.4);
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();

        // Önceki sürüm saat türünü "gercek" yazmış olabilir; admin elle seçmediyse oyun saatine al
        if (!plugin.getConfig().getBoolean("kacakci.saat-turu-elle", false)) {
            plugin.getConfig().set("kacakci.saat-turu", "oyun");
            plugin.saveConfig();
        }
        // Eski tek noktalı ayarı yeni nokta listesine taşı
        ConfigurationSection eski = plugin.getConfig().getConfigurationSection("kacakci.konum");
        if (eski != null && noktalar().isEmpty()) {
            World w = Bukkit.getWorld(eski.getString("dunya", ""));
            if (w != null) {
                List<Location> l = new ArrayList<>();
                l.add(new Location(w, eski.getDouble("x"), eski.getDouble("y"), eski.getDouble("z"), (float) eski.getDouble("yaw"), 0f));
                noktalariKaydet(l);
            }
            plugin.getConfig().set("kacakci.konum", null);
            plugin.saveConfig();
        }

        yukle();

        new BukkitRunnable() {
            @Override public void run() { gece(); }
        }.runTaskTimer(plugin, 60L, 40L);
        new BukkitRunnable() {
            @Override public void run() { kazanTick(); }
        }.runTaskTimer(plugin, 40L, 20L);
        new BukkitRunnable() {
            @Override public void run() { kaydet(); }
        }.runTaskTimer(plugin, 1200L, 1200L);
    }

    public void kapat() {
        kaydet();
        Entity e = npcId == null ? null : Bukkit.getEntity(npcId);
        if (e != null) e.remove();
    }

    // ---------------------------------------------------------------- eşya
    private ItemStack esya(String id, Material m, String ad, String lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        List<String> l = new ArrayList<>();
        l.add(ChatColor.GRAY + lore);
        l.add(ChatColor.DARK_RED + "Kaçak ürün");
        meta.setLore(l);
        meta.getPersistentDataContainer().set(esyaKey, PersistentDataType.STRING, id);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack esyaYap(String id) {
        switch (id) {
            case ESYA_MASKE: {
                ItemStack m = esya(id, Material.LEATHER_HELMET, ChatColor.YELLOW + "" + ChatColor.BOLD + "Koruyucu Maske", "Kazan dumanından korur. Başına tak.");
                org.bukkit.inventory.meta.LeatherArmorMeta lm = (org.bukkit.inventory.meta.LeatherArmorMeta) m.getItemMeta();
                lm.setColor(org.bukkit.Color.YELLOW);
                lm.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES, org.bukkit.inventory.ItemFlag.HIDE_DYE);
                m.setItemMeta(lm);
                return m;
            }
            case ESYA_KAZAN: return esya(id, Material.CAULDRON, ChatColor.DARK_GRAY + "" + ChatColor.BOLD + "Gizli Kazan", "Yere koy; malzemeleri sırayla at.");
            case ESYA_GOLGE: return esya(id, Material.SPIDER_EYE, ChatColor.DARK_PURPLE + "Gölge Kökü", "Kazan malzemesi.");
            case ESYA_KIZIL: return esya(id, Material.BLAZE_POWDER, ChatColor.RED + "Kızıl Kül", "Kazan malzemesi.");
            default: return esya(ESYA_TUZ, Material.PRISMARINE_CRYSTALS, ChatColor.AQUA + "Mavi Tuz", "Kazan malzemesi.");
        }
    }

    private String esyaId(ItemStack it) {
        if (it == null || it.getType() == Material.AIR || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(esyaKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onYeme(PlayerItemConsumeEvent e) {
        if (esyaId(e.getItem()) != null) e.setCancelled(true);
    }

    // ---------------------------------------------------------------- kaçakçı NPC
    // Birden çok nokta (admin ekler) + oyun saati aralıkları. Her aralığın başında noktalardan biri rastgele seçilir.
    private List<Location> noktalar() {
        List<Location> l = new ArrayList<>();
        for (Map<?, ?> m : plugin.getConfig().getMapList("kacakci.noktalar")) {
            try {
                World w = Bukkit.getWorld(String.valueOf(m.get("dunya")));
                if (w == null) continue;
                l.add(new Location(w, ((Number) m.get("x")).doubleValue(), ((Number) m.get("y")).doubleValue(),
                        ((Number) m.get("z")).doubleValue(), ((Number) m.get("yaw")).floatValue(), 0f));
            } catch (Exception ignored) { }
        }
        return l;
    }

    private void noktalariKaydet(List<Location> l) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Location k : l) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("dunya", k.getWorld().getName()); m.put("x", k.getX()); m.put("y", k.getY()); m.put("z", k.getZ()); m.put("yaw", (double) k.getYaw());
            out.add(m);
        }
        plugin.getConfig().set("kacakci.noktalar", out);
        plugin.saveConfig();
        aktifAnahtar = null;
    }

    /** "21:00" -> oyun tick'i (06:00 = tick 0, 18:00 = tick 12000, 00:00 = tick 18000). */
    private static long saatTick(String hhmm) {
        String[] p = hhmm.trim().split(":");
        int dk = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        return Math.round(((dk - 360 + 1440) % 1440) * 24000.0 / 1440.0);
    }

    private boolean gercekSaat() { return !"oyun".equalsIgnoreCase(plugin.getConfig().getString("kacakci.saat-turu", "gercek")); }

    private static int dakika(String hhmm) {
        String[] p = hhmm.trim().split(":");
        return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
    }

    /** Şu anki saatin (gerçek: Europe/Istanbul, ya da oyun saati) düştüğü aralığın indeksi, yoksa -1. */
    private int aktifPencere(World w) {
        List<String> saatler = plugin.getConfig().getStringList("kacakci.saatler");
        boolean gercek = gercekSaat();
        java.time.ZonedDateTime z = java.time.ZonedDateTime.now(java.time.ZoneId.of("Europe/Istanbul"));
        int simdiDk = z.getHour() * 60 + z.getMinute();
        long t = w.getTime();
        for (int i = 0; i < saatler.size(); i++) {
            try {
                String[] ab = saatler.get(i).split("-");
                boolean icinde;
                if (gercek) {
                    int bas = dakika(ab[0]), bit = dakika(ab[1]);
                    icinde = bas <= bit ? (simdiDk >= bas && simdiDk < bit) : (simdiDk >= bas || simdiDk < bit);
                } else {
                    long bas = saatTick(ab[0]), bit = saatTick(ab[1]);
                    icinde = bas <= bit ? (t >= bas && t < bit) : (t >= bas || t < bit);
                }
                if (icinde) return i;
            } catch (Exception ignored) { }
        }
        return -1;
    }

    private long rastgeleMs(String anahtar, String varsayilan) {
        if (!gercekSaat()) anahtar = anahtar.replace("kacakci.rastgele.", "kacakci.rastgele-oyun.");
        if (!gercekSaat()) varsayilan = anahtar.endsWith("kalma-dk") ? "1-2" : "0.5-1.5";
        String[] ab = plugin.getConfig().getString(anahtar, varsayilan).split("-");
        try {
            double lo = Double.parseDouble(ab[0].trim()), hi = Double.parseDouble(ab[ab.length > 1 ? 1 : 0].trim());
            if (hi < lo) { double x = lo; lo = hi; hi = x; }
            return (long) ((lo + rnd.nextDouble() * (hi - lo)) * 60_000L);
        } catch (Exception e) {
            return 15 * 60_000L;
        }
    }

    private String aktifAnahtar;       // pencere + gün: değişince yeni rastgele nokta seçilir
    private int secilenNokta = -1;
    private long zorlaBitis = 0;       // /kacakci cagir: bu zamana kadar saat kuralından bağımsız ortada
    private long oturumBitis = 0;      // rastgele mod: şu anki çıkışın bitişi (0 = şu an ortada değil)
    private long sonrakiCikis = 0;     // rastgele mod: bir sonraki çıkış zamanı (0 = henüz planlanmadı)

    private void gece() {
        List<Location> noktalar = noktalar();
        Entity mevcut = npcId == null ? null : Bukkit.getEntity(npcId);
        if (mevcut != null && !mevcut.isValid()) { mevcut = null; npcId = null; }
        if (noktalar.isEmpty()) { if (mevcut != null) { mevcut.remove(); npcId = null; } return; }

        World ref = noktalar.get(0).getWorld();
        long simdi = System.currentTimeMillis();
        boolean zorla = simdi < zorlaBitis;
        int pencere = aktifPencere(ref);
        boolean rastgele = plugin.getConfig().getBoolean("kacakci.rastgele.aktif", true);

        if (pencere < 0 && !zorla) {
            if (mevcut != null) { mevcut.remove(); npcId = null; }
            aktifAnahtar = null; oturumBitis = 0; sonrakiCikis = 0;
            return;
        }

        if (rastgele && !zorla) {
            if (oturumBitis > 0 && simdi >= oturumBitis) {          // çıkış bitti: kaybol, sonrakini planla
                if (mevcut != null) { mevcut.remove(); npcId = null; mevcut = null; }
                oturumBitis = 0;
                sonrakiCikis = simdi + rastgeleMs("kacakci.rastgele.bekleme-dk", "10-40");
                aktifAnahtar = null;
            }
            if (oturumBitis == 0) {                                   // şu an ortada değil
                if (sonrakiCikis == 0) sonrakiCikis = simdi + (long) (rnd.nextDouble() * rastgeleMs("kacakci.rastgele.bekleme-dk", "10-40"));
                if (simdi < sonrakiCikis) {
                    if (mevcut != null) { mevcut.remove(); npcId = null; }
                    return;
                }
                oturumBitis = simdi + rastgeleMs("kacakci.rastgele.kalma-dk", "10-25");   // yeni çıkış başlıyor
                aktifAnahtar = null;
            }
        }

        String anahtar = (zorla ? "z" + zorlaBitis : rastgele ? "r" + oturumBitis : "p" + pencere + ":" + (ref.getFullTime() / 24000L)) + ":" + noktalar.size();
        if (!anahtar.equals(aktifAnahtar)) {
            aktifAnahtar = anahtar;
            secilenNokta = rnd.nextInt(noktalar.size());
            if (mevcut != null) { mevcut.remove(); npcId = null; mevcut = null; }
        }
        Location k = noktalar.get(Math.min(secilenNokta, noktalar.size() - 1));
        if (mevcut == null && k.getWorld().isChunkLoaded(k.getBlockX() >> 4, k.getBlockZ() >> 4)) {
            Villager v = k.getWorld().spawn(k, Villager.class, vv -> {
                vv.setAI(false);
                vv.setInvulnerable(true);
                vv.setSilent(true);
                vv.setPersistent(false);
                vv.setRemoveWhenFarAway(false);
                vv.setProfession(Villager.Profession.NITWIT);
                vv.customName(net.kyori.adventure.text.Component.text("Şüpheli Tüccar", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY));
                vv.setCustomNameVisible(true);
                vv.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
            });
            npcId = v.getUniqueId();
        }
    }

    private boolean kacakciMi(Entity e) {
        return e instanceof Villager && e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onDonusum(EntityTransformEvent e) {
        if (kacakciMi(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler
    public void onNpcTik(PlayerInteractEntityEvent e) {
        if (!kacakciMi(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        menuAc(e.getPlayer());
    }

    // ---------------------------------------------------------------- kaçakçı menüsü
    private double fiyat(String anahtar) { return plugin.getConfig().getDouble("kacakci.fiyat." + anahtar, 100.0); }

    private double alisFiyati(KacakMal.Tur t, int kalite) {
        double taban = plugin.getConfig().getDouble("kacakci.alis." + (t == KacakMal.Tur.KARA_TOZ ? "kara-toz" : "mavi-oz"), 500.0);
        double carpan = kalite >= 3 ? 1.7 : kalite == 2 ? 1.3 : 1.0;
        return Math.round(taban * carpan);
    }

    private long geceNo(Player p) { return p.getWorld().getFullTime() / 24000L; }

    private int kalanKota(Player p) {
        long[] k = satisKota.get(p.getUniqueId());
        int limit = plugin.getConfig().getInt("kacakci.gece-satis-kotasi", 12);
        if (k == null || k[0] != geceNo(p)) return limit;
        return (int) Math.max(0, limit - k[1]);
    }

    private ItemStack menuEsya(Material m, String ad, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        List<String> l = new ArrayList<>();
        for (String s : lore) l.add(s);
        meta.setLore(l);
        it.setItemMeta(meta);
        return it;
    }

    private void menuAc(Player p) {
        Menu h = new Menu();
        Inventory inv = Bukkit.createInventory(h, 27, ChatColor.DARK_GRAY + "Şüpheli Tüccar");
        h.inv = inv;
        inv.setItem(10, menuEsya(Material.CAULDRON, ChatColor.GRAY + "Gizli Kazan", ChatColor.YELLOW + "$" + (long) fiyat("kazan"), ChatColor.GRAY + "Tıkla: satın al"));
        inv.setItem(11, menuEsya(Material.SPIDER_EYE, ChatColor.DARK_PURPLE + "Gölge Kökü", ChatColor.YELLOW + "$" + (long) fiyat("golge-koku"), ChatColor.GRAY + "Tıkla: 1 adet al"));
        inv.setItem(12, menuEsya(Material.BLAZE_POWDER, ChatColor.RED + "Kızıl Kül", ChatColor.YELLOW + "$" + (long) fiyat("kizil-kul"), ChatColor.GRAY + "Tıkla: 1 adet al"));
        inv.setItem(13, menuEsya(Material.PRISMARINE_CRYSTALS, ChatColor.AQUA + "Mavi Tuz", ChatColor.YELLOW + "$" + (long) fiyat("mavi-tuz"), ChatColor.GRAY + "Tıkla: 1 adet al"));
        inv.setItem(15, menuEsya(Material.GUNPOWDER, ChatColor.DARK_GRAY + "Kara Toz sat", ChatColor.GRAY + "Üstündeki hepsini satar (kota dahilinde)",
                ChatColor.GRAY + "Kalite 1/2/3: $" + (long) alisFiyati(KacakMal.Tur.KARA_TOZ, 1) + " / $" + (long) alisFiyati(KacakMal.Tur.KARA_TOZ, 2) + " / $" + (long) alisFiyati(KacakMal.Tur.KARA_TOZ, 3)));
        inv.setItem(16, menuEsya(Material.LIGHT_BLUE_DYE, ChatColor.AQUA + "Mavi Öz sat", ChatColor.GRAY + "Üstündeki hepsini satar (kota dahilinde)",
                ChatColor.GRAY + "Kalite 1/2/3: $" + (long) alisFiyati(KacakMal.Tur.MAVI_OZ, 1) + " / $" + (long) alisFiyati(KacakMal.Tur.MAVI_OZ, 2) + " / $" + (long) alisFiyati(KacakMal.Tur.MAVI_OZ, 3)));
        inv.setItem(14, menuEsya(Material.LEATHER_HELMET, ChatColor.YELLOW + "Koruyucu Maske", ChatColor.YELLOW + "$" + (long) fiyat("maske"), ChatColor.GRAY + "Kazan dumanından korur", ChatColor.GRAY + "Tıkla: satın al"));
        inv.setItem(22, menuEsya(Material.CLOCK, ChatColor.YELLOW + "Bu gece kalan satış kotan: " + kalanKota(p),
                ChatColor.DARK_GRAY + "Ödemeler belediye kasasından/kasasına işler."));
        p.openInventory(inv);
    }

    @EventHandler
    public void onMenuTikla(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getRawSlot() < 0 || e.getRawSlot() >= 27) return;
        switch (e.getRawSlot()) {
            case 10: satinAl(p, ESYA_KAZAN, "kazan", "Gizli Kazan"); break;
            case 11: satinAl(p, ESYA_GOLGE, "golge-koku", "Gölge Kökü"); break;
            case 12: satinAl(p, ESYA_KIZIL, "kizil-kul", "Kızıl Kül"); break;
            case 13: satinAl(p, ESYA_TUZ, "mavi-tuz", "Mavi Tuz"); break;
            case 14: satinAl(p, ESYA_MASKE, "maske", "Koruyucu Maske"); break;
            case 15: sat(p, KacakMal.Tur.KARA_TOZ); break;
            case 16: sat(p, KacakMal.Tur.MAVI_OZ); break;
            default: break;
        }
    }

    @EventHandler
    public void onMenuSurukle(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    private void satinAl(Player p, String id, String fiyatKey, String ad) {
        double f = fiyat(fiyatKey);
        if (p.getInventory().firstEmpty() == -1) { p.sendMessage(ChatColor.RED + "Envanterin dolu."); return; }
        if (!plugin.processPaymentToKasa(p, f)) return;
        p.getInventory().addItem(esyaYap(id));
        p.sendMessage(ChatColor.GRAY + ad + " aldın. (-$" + (long) f + ")");
        plugin.ekonomiLog("kacakci-alim", p.getName(), ad + " $" + (long) f);
        menuAc(p);
    }

    private void sat(Player p, KacakMal.Tur tur) {
        int kota = kalanKota(p);
        if (kota <= 0) { p.sendMessage(ChatColor.RED + "Bu gece daha fazla mal almam."); return; }
        double toplam = 0;
        int satilan = 0;
        ItemStack[] icerik = p.getInventory().getContents();
        for (int i = 0; i < icerik.length && satilan < kota; i++) {
            ItemStack it = icerik[i];
            if (KacakMal.turu(plugin, it) != tur) continue;
            int al = Math.min(it.getAmount(), kota - satilan);
            toplam += alisFiyati(tur, KacakMal.kalitesi(plugin, it)) * al;
            satilan += al;
            if (al >= it.getAmount()) p.getInventory().setItem(i, null); else it.setAmount(it.getAmount() - al);
        }
        if (satilan == 0) { p.sendMessage(ChatColor.RED + "Üstünde " + tur.ad + " yok."); return; }
        // Ödeme belediye kasasından; kasada yoksa mal geri verilir (para basılmaz)
        if (!plugin.kasadanParaCek(toplam)) {
            // geri ver (kalite bilgisi kaybolmasın diye ortalama yerine orijinalleri tek tek geri koymak gerekir;
            // bu yüzden kasayı ÖNCE kontrol edemediysek en kötü ihtimalle 1. kaliteyi değil, düşülen adedi aynı kaliteyle iade ederiz)
            p.sendMessage(ChatColor.RED + "Tüccarın bu gece cebinde yeterli para yok (kasa yetersiz). Mal iade edildi.");
            iadeEt(p, tur, satilan, icerik);
            return;
        }
        for (ItemStack kalan : p.getInventory().addItem(plugin.createEconomyNote(toplam)).values())
            p.getWorld().dropItemNaturally(p.getLocation(), kalan);
        long[] k = satisKota.get(p.getUniqueId());
        if (k == null || k[0] != geceNo(p)) { k = new long[] {geceNo(p), 0}; satisKota.put(p.getUniqueId(), k); }
        k[1] += satilan;
        p.sendMessage(ChatColor.GRAY + "" + satilan + "x " + tur.ad + " sattın: +$" + (long) toplam);
        plugin.ekonomiLog("kacakci-satis", p.getName(), satilan + "x " + tur.ad + " $" + (long) toplam);
        menuAc(p);
    }

    /** Kasa yetersizse: satılan eşyaları orijinal stoklarıyla (kalite korunarak) geri koyar. */
    private void iadeEt(Player p, KacakMal.Tur tur, int adet, ItemStack[] onceki) {
        int kalan = adet;
        for (ItemStack it : onceki) {
            if (kalan <= 0) break;
            if (KacakMal.turu(plugin, it) != tur) continue;
            int n = Math.min(kalan, it.getAmount());
            ItemStack kopya = it.clone();
            kopya.setAmount(n);
            for (ItemStack art : p.getInventory().addItem(kopya).values()) p.getWorld().dropItemNaturally(p.getLocation(), art);
            kalan -= n;
        }
    }

    // ---------------------------------------------------------------- kazan: yerleştirme / kırma
    private Kazan kazanBul(Block b) {
        if (b == null || b.getType() != Material.CAULDRON) return null;
        return kazanlar.get(b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ());
    }

    @EventHandler(ignoreCancelled = true)
    public void onYerlestir(BlockPlaceEvent e) {
        if (!ESYA_KAZAN.equals(esyaId(e.getItemInHand()))) return;
        Block b = e.getBlockPlaced();
        if (plugin.koyManager == null || !plugin.koyManager.icerideMi(b.getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "Gizli kazan sadece köy sınırları içinde kurulabilir.");
            return;
        }
        if (!kendiClaiminde(e.getPlayer(), b.getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(ChatColor.RED + "Gizli kazan sadece kendi claim'inin içinde kurulabilir.");
            return;
        }
        Kazan k = new Kazan();
        k.dunya = b.getWorld().getName(); k.x = b.getX(); k.y = b.getY(); k.z = b.getZ();
        k.sahip = e.getPlayer().getUniqueId(); k.sahipAd = e.getPlayer().getName();
        kazanlar.put(k.anahtar(), k);
        kaydet();
        e.getPlayer().sendMessage(ChatColor.GRAY + "Gizli kazan kuruldu. Malzemeleri sırayla at: Gölge Kökü ile başlarsan Kara Toz, Mavi Tuz ile başlarsan Mavi Öz.");
    }

    /** Konum, oyuncunun KENDİ sahip olduğu bir GriefPrevention claim'inin içinde mi? */
    private boolean kendiClaiminde(Player p, Location l) {
        try {
            me.ryanhamshire.GriefPrevention.Claim c = me.ryanhamshire.GriefPrevention.GriefPrevention.instance.dataStore.getClaimAt(l, false, null);
            return c != null && p.getUniqueId().equals(c.getOwnerID());
        } catch (Throwable t) {
            return false;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onKir(BlockBreakEvent e) {
        Kazan k = kazanBul(e.getBlock());
        if (k == null) return;
        e.setDropItems(false);
        kazanlar.remove(k.anahtar());
        kaydet();
        if (k.sahip.equals(e.getPlayer().getUniqueId())) {
            e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(0.5, 0.5, 0.5), esyaYap(ESYA_KAZAN));
        }
        if (k.asama != Asama.BOS) e.getPlayer().sendMessage(ChatColor.GRAY + "Kazandaki karışım boşa gitti.");
    }

    // ---------------------------------------------------------------- kazan: etkileşim
    @EventHandler(priority = EventPriority.HIGH)
    public void onTik(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Kazan k = kazanBul(e.getClickedBlock());
            if (k != null) {
                e.setCancelled(true);
                kazanTikla(p, k, e.getClickedBlock());
                return;
            }
        }
        // Kaçak mal kullanımı (sağ tık)
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) {
            ItemStack el = p.getInventory().getItemInMainHand();
            KacakMal.Tur t = KacakMal.turu(plugin, el);
            if (t != null) {
                e.setCancelled(true);
                tuket(p, el, t);
            }
        }
    }

    private void kazanTikla(Player p, Kazan k, Block b) {
        // Polis baskını
        if (plugin.gbtManager != null && plugin.gbtManager.polisMi(p)) {
            baskin(p, k, b);
            return;
        }
        ItemStack el = p.getInventory().getItemInMainHand();
        String id = esyaId(el);

        // Hazır ürünü al
        if (k.asama == Asama.HAZIR) {
            p.getInventory().addItem(KacakMal.olustur(plugin, k.tur, k.kalite, k.adet)).values()
                    .forEach(art -> p.getWorld().dropItemNaturally(p.getLocation(), art));
            p.sendMessage(ChatColor.GREEN + "Kazandan " + k.adet + "x " + k.tur.ad + " aldın (" + KacakMal.KALITE_ADI[k.kalite] + ").");
            plugin.ekonomiLog("kazan-uretim", p.getName(), k.adet + "x " + k.tur.ad + " kalite " + k.kalite);
            sifirla(k);
            kaydet();
            return;
        }

        // Karıştırma (Shift + sağ tık, boş el)
        if (p.isSneaking() && el.getType() == Material.AIR) {
            if (k.asama != Asama.KARISTIRMA) { p.sendActionBar(ac("Şu an karıştırılacak bir şey yok.", ChatColor.GRAY)); return; }
            if (k.pencereAcikSn > 0) {
                k.pencereAcikSn = 0;
                k.basari++;
                k.pencere++;
                k.kalanSn = 45;
                p.sendActionBar(ac("Güzel karıştırdın! (" + k.basari + "/" + PENCERE_SAYISI + ")", ChatColor.GREEN));
                p.playSound(b.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.2f);
                if (k.pencere >= PENCERE_SAYISI) sogumayaGec(k);
            } else {
                p.sendActionBar(ac("Henüz zamanı değil!", ChatColor.RED));
                p.playSound(b.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.6f, 1f);
            }
            return;
        }

        // Malzeme ekleme
        if (id != null && (id.equals(ESYA_GOLGE) || id.equals(ESYA_KIZIL) || id.equals(ESYA_TUZ))) {
            if (k.asama != Asama.BOS && k.asama != Asama.MALZEME) { p.sendMessage(ChatColor.RED + "Kazan şu an malzeme kabul etmiyor."); return; }
            int gerekli = plugin.getConfig().getInt("kacakci.min-polis", 1);
            if (gerekli > 0 && cevrimiciPolis() < gerekli) {
                p.sendMessage(ChatColor.RED + "Şehirde polis yok; kimse bu işe kalkışmaz. Polis çevrimiçi olunca tekrar dene.");
                p.playSound(b.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
                return;
            }
            malzemeEkle(p, k, b, id, el);
            return;
        }

        // Durum
        p.sendActionBar(ac(durumMetni(k), ChatColor.YELLOW));
    }

    private String durumMetni(Kazan k) {
        switch (k.asama) {
            case BOS: return "Kazan boş. Malzeme at.";
            case MALZEME: return "Malzemeler: " + k.eklenen.size() + "/" + tarif(k.tur).size();
            case ISINMA: return "Isınıyor... kalan " + k.kalanSn + " sn (altı yanmalı)";
            case KARISTIRMA: return k.pencereAcikSn > 0 ? "ŞİMDİ KARIŞTIR! (Shift+sağ tık)" : "Karışım kaynıyor... sonraki karıştırma " + k.kalanSn + " sn";
            case SOGUMA: return "Soğuyor... kalan " + k.kalanSn + " sn";
            default: return "Hazır!";
        }
    }

    private List<String> tarif(KacakMal.Tur t) {
        if (t == KacakMal.Tur.KARA_TOZ) return List.of(ESYA_GOLGE, ESYA_GOLGE, ESYA_KIZIL, ESYA_TUZ);
        return List.of(ESYA_TUZ, ESYA_TUZ, ESYA_GOLGE);
    }

    private int cevrimiciPolis() {
        int n = 0;
        if (plugin.gbtManager == null) return 0;
        for (Player o : Bukkit.getOnlinePlayers())
            if (!o.hasMetadata("NPC") && plugin.gbtManager.polisMi(o)) n++;
        return n;
    }

    private void malzemeEkle(Player p, Kazan k, Block b, String id, ItemStack el) {
        if (k.asama == Asama.BOS) {
            if (id.equals(ESYA_GOLGE)) k.tur = KacakMal.Tur.KARA_TOZ;
            else if (id.equals(ESYA_TUZ)) k.tur = KacakMal.Tur.MAVI_OZ;
            else { bozul(p, k, b); el.setAmount(el.getAmount() - 1); return; }
            k.sahip = p.getUniqueId(); k.sahipAd = p.getName();
            k.asama = Asama.MALZEME;
        }
        List<String> t = tarif(k.tur);
        String beklenen = t.get(k.eklenen.size());
        el.setAmount(el.getAmount() - 1);
        if (!beklenen.equals(id)) { bozul(p, k, b); return; }
        k.eklenen.add(id);
        p.playSound(b.getLocation(), Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 1.5f);
        p.sendActionBar(ac("Malzeme eklendi (" + k.eklenen.size() + "/" + t.size() + ")", ChatColor.GRAY));
        if (k.eklenen.size() >= t.size()) {
            k.asama = Asama.ISINMA;
            k.kalanSn = 240;
            p.sendMessage(ChatColor.GRAY + "Karışım hazır. Kazanın altına bir kamp ateşi (veya ateş/lav) yak; 4 dk ısınması lazım.");
            if (rnd.nextDouble() < plugin.getConfig().getDouble("kacakci.ihbar-sans-isinma", 0.6)) { k.ihbar1 = true; ihbarEt(b.getLocation()); }
        }
        kaydet();
    }

    private void bozul(Player p, Kazan k, Block b) {
        p.sendMessage(ChatColor.RED + "Yanlış sıra! Karışım bozuldu ve kazan boşaldı.");
        p.playSound(b.getLocation(), Sound.ENTITY_GENERIC_EXTINGUISH_FIRE, 1f, 0.8f);
        b.getWorld().spawnParticle(Particle.SMOKE, b.getLocation().add(0.5, 1.0, 0.5), 25, 0.2, 0.2, 0.2, 0.02);
        sifirla(k);
        kaydet();
    }

    private void sifirla(Kazan k) {
        k.tur = null; k.eklenen.clear(); k.asama = Asama.BOS; k.kalanSn = 0; k.pencere = 0; k.pencereAcikSn = 0;
        k.basari = 0; k.kalite = 1; k.adet = 0; k.ihbar1 = false; k.ihbar2 = false;
    }

    private void sogumayaGec(Kazan k) {
        k.asama = Asama.SOGUMA;
        k.kalanSn = 120;
        k.pencereAcikSn = 0;
        k.kalite = 1 + (k.basari >= 3 ? 1 : 0) + (k.basari >= 4 ? 1 : 0);
        k.adet = 3 + (k.kalite >= 3 ? 1 : 0);
    }

    // ---------------------------------------------------------------- kazan döngüsü (saniyede bir)
    private boolean isiVar(Block kazanBlok) {
        Block alt = kazanBlok.getRelative(0, -1, 0);
        Material m = alt.getType();
        if (m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE)
            return alt.getBlockData() instanceof Lightable l && l.isLit();
        return m == Material.FIRE || m == Material.SOUL_FIRE || m == Material.LAVA || m == Material.MAGMA_BLOCK;
    }

    private void kazanTick() {
        for (Kazan k : new ArrayList<>(kazanlar.values())) {
            World w = Bukkit.getWorld(k.dunya);
            if (w == null || !w.isChunkLoaded(k.x >> 4, k.z >> 4)) continue;
            Block b = w.getBlockAt(k.x, k.y, k.z);
            if (b.getType() != Material.CAULDRON) { kazanlar.remove(k.anahtar()); continue; }
            Location merkez = b.getLocation().add(0.5, 1.0, 0.5);
            Player sahip = Bukkit.getPlayer(k.sahip);

            switch (k.asama) {
                case ISINMA:
                    if (isiVar(b)) {
                        k.kalanSn--;
                        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, merkez, 2, 0.1, 0.3, 0.1, 0.01);
                        if (k.kalanSn <= 0) {
                            k.asama = Asama.KARISTIRMA; k.kalanSn = 45; k.pencere = 0; k.basari = 0; k.pencereAcikSn = 0;
                            if (sahip != null && sahip.getWorld().equals(w) && sahip.getLocation().distanceSquared(merkez) < 900)
                                sahip.sendMessage(ChatColor.GRAY + "Karışım kaynamaya başladı. Işık verildiğinde Shift+sağ tıkla karıştır!");
                            if (rnd.nextDouble() < plugin.getConfig().getDouble("kacakci.ihbar-sans-karistirma", 0.4)) { k.ihbar2 = true; ihbarEt(merkez); }
                        }
                    } else if (k.kalanSn % 10 == 0 && sahip != null && sahip.getWorld().equals(w) && sahip.getLocation().distanceSquared(merkez) < 400) {
                        sahip.sendActionBar(ac("Kazanın altı soğuk! Ateş yak.", ChatColor.RED));
                    }
                    duman(w, merkez);
                    break;
                case KARISTIRMA:
                    w.spawnParticle(Particle.SMOKE, merkez, 3, 0.15, 0.2, 0.15, 0.01);
                    duman(w, merkez);
                    if (k.pencereAcikSn > 0) {
                        k.pencereAcikSn--;
                        if (sahip != null && sahip.getWorld().equals(w) && sahip.getLocation().distanceSquared(merkez) < 900)
                            sahip.sendActionBar(ac("KARIŞTIR! Shift+sağ tık (" + k.pencereAcikSn + " sn)", ChatColor.GOLD));
                        if (k.pencereAcikSn <= 0) { // kaçırdı
                            k.pencere++; k.kalanSn = 45;
                            if (k.pencere >= PENCERE_SAYISI) sogumayaGec(k);
                        }
                    } else {
                        k.kalanSn--;
                        if (k.kalanSn <= 0) {
                            k.pencereAcikSn = 15;
                            w.playSound(merkez, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.8f);
                        }
                    }
                    break;
                case SOGUMA:
                    k.kalanSn--;
                    if (k.kalanSn <= 0) {
                        k.asama = Asama.HAZIR;
                        w.playSound(merkez, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                        if (sahip != null) sahip.sendMessage(ChatColor.GREEN + "Kazandaki " + k.tur.ad + " hazır! (" + KacakMal.KALITE_ADI[k.kalite] + ")");
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private boolean maskeli(Player p) {
        return ESYA_MASKE.equals(esyaId(p.getInventory().getHelmet()));
    }

    /** Kazanın 4 blok çevresinde Koruyucu Maske takmayanların başı döner ve zehirlenirler (5 sn'de bir uygulanır). */
    private void duman(World w, Location merkez) {
        if ((System.currentTimeMillis() / 1000) % 5 != 0) return;
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(merkez) > 16) continue;
            if (maskeli(p)) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 200, 0, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 120, 0, false, false));
            p.sendActionBar(ac("Zehirli duman! Koruyucu Maske tak.", ChatColor.RED));
        }
    }

    private void ihbarEt(Location l) {
        int x = Math.round(l.getBlockX() / 50f) * 50, z = Math.round(l.getBlockZ() / 50f) * 50;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.gbtManager != null && plugin.gbtManager.polisMi(p)) {
                p.sendMessage(ChatColor.RED + "[İhbar] " + ChatColor.GRAY + "Şüpheli duman görüldü: " + l.getWorld().getName() + " yaklaşık X:" + x + " Z:" + z);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1f);
            }
        }
    }

    // ---------------------------------------------------------------- polis baskını
    private void baskin(Player polis, Kazan k, Block b) {
        if (k.asama == Asama.BOS) {
            polis.sendMessage(ChatColor.GRAY + "Boş bir kazan. Bir şey yapılmadı (kırarak kaldırabilirsin).");
            return;
        }
        kazanlar.remove(k.anahtar());
        b.setType(Material.AIR);
        kaydet();
        String ozet = (k.tur == null ? "kazan" : k.tur.ad) + " kazanı";
        polis.sendMessage(ChatColor.GREEN + "Baskın! " + ozet + " (" + k.sahipAd + ") el konuldu.");
        plugin.ekonomiLog("kazan-baskin", polis.getName(), k.sahipAd + " | " + k.asama + " | " + (k.tur == null ? "-" : k.tur.ad));
        Player sahip = Bukkit.getPlayer(k.sahip);
        if (sahip != null && sahip.getWorld().equals(polis.getWorld()) && sahip.getLocation().distanceSquared(polis.getLocation()) <= 400
                && plugin.gbtManager != null && !plugin.hapisteMi(sahip.getUniqueId())) {
            plugin.gbtManager.cezaKes(polis, sahip, ozet);
        } else {
            // sahibi yakında değil: sadece sabıka + ceza (banka eksiye düşebilir), hapis yok
            UUID id = k.sahip;
            double eski = plugin.bankaHesaplari.getOrDefault(id, 0.0);
            plugin.bankaHesaplari.put(id, Math.round((eski - 5000.0) * 100.0) / 100.0);
            plugin.kasayaParaEkle(5000.0);
            plugin.getConfig().set("sabika." + id, plugin.getConfig().getInt("sabika." + id, 0) + 1);
            plugin.saveConfig();
            plugin.veriKaydet();
            polis.sendMessage(ChatColor.YELLOW + "Sahibi yakında değildi: $5000 ceza ve sabıka kaydı işlendi, hapis yok.");
            if (sahip != null) sahip.sendMessage(ChatColor.DARK_RED + "Kazanına polis baskını yapıldı! $5000 ceza kesildi.");
        }
    }

    // ---------------------------------------------------------------- tüketim (buff)
    private void tuket(Player p, ItemStack el, KacakMal.Tur t) {
        long simdi = System.currentTimeMillis();
        Long son = tuketimBekleme.get(p.getUniqueId());
        if (son != null && simdi - son < 30_000L) { p.sendMessage(ChatColor.RED + "Biraz bekle."); return; }
        tuketimBekleme.put(p.getUniqueId(), simdi);
        int kalite = Math.max(1, KacakMal.kalitesi(plugin, el));
        el.setAmount(el.getAmount() - 1);
        int sure = (20 + 10 * kalite) * 20;
        if (t == KacakMal.Tur.KARA_TOZ) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, sure, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, sure, 0));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 20, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 20 * 20, 1));
            }, sure);
        } else {
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, sure / 2, 1));
            p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, sure, 0));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 10 * 20, 0));
            }, sure);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 0.8f);
        p.sendMessage(ChatColor.GRAY + t.ad + " kullandın.");
    }

    // ---------------------------------------------------------------- yardımcılar
    private static net.kyori.adventure.text.Component ac(String s, ChatColor renk) {
        return net.kyori.adventure.text.Component.text(s).color(net.kyori.adventure.text.format.NamedTextColor.NAMES.valueOr(
                renk == ChatColor.RED ? "red" : renk == ChatColor.GREEN ? "green" : renk == ChatColor.GOLD ? "gold" : renk == ChatColor.YELLOW ? "yellow" : "gray",
                net.kyori.adventure.text.format.NamedTextColor.GRAY));
    }

    // ---------------------------------------------------------------- kalıcılık
    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Kazan k : kazanlar.values()) {
            String p = "k" + (i++);
            y.set(p + ".dunya", k.dunya); y.set(p + ".x", k.x); y.set(p + ".y", k.y); y.set(p + ".z", k.z);
            y.set(p + ".sahip", k.sahip.toString()); y.set(p + ".sahipAd", k.sahipAd);
            y.set(p + ".tur", k.tur == null ? "" : k.tur.id);
            y.set(p + ".eklenen", k.eklenen);
            y.set(p + ".asama", k.asama.name());
            y.set(p + ".kalanSn", k.kalanSn); y.set(p + ".pencere", k.pencere); y.set(p + ".pencereAcikSn", k.pencereAcikSn);
            y.set(p + ".basari", k.basari); y.set(p + ".kalite", k.kalite); y.set(p + ".adet", k.adet);
        }
        try { plugin.getDataFolder().mkdirs(); y.save(veriDosyasi); } catch (Exception e) { plugin.getLogger().warning("Kazan verisi yazılamadı: " + e.getMessage()); }
    }

    private void yukle() {
        if (!veriDosyasi.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(veriDosyasi);
        for (String p : y.getKeys(false)) {
            try {
                Kazan k = new Kazan();
                k.dunya = y.getString(p + ".dunya"); k.x = y.getInt(p + ".x"); k.y = y.getInt(p + ".y"); k.z = y.getInt(p + ".z");
                k.sahip = UUID.fromString(y.getString(p + ".sahip")); k.sahipAd = y.getString(p + ".sahipAd", "");
                k.tur = KacakMal.Tur.bul(y.getString(p + ".tur", ""));
                k.eklenen = new ArrayList<>(y.getStringList(p + ".eklenen"));
                k.asama = Asama.valueOf(y.getString(p + ".asama", "BOS"));
                k.kalanSn = y.getInt(p + ".kalanSn"); k.pencere = y.getInt(p + ".pencere"); k.pencereAcikSn = y.getInt(p + ".pencereAcikSn");
                k.basari = y.getInt(p + ".basari"); k.kalite = y.getInt(p + ".kalite", 1); k.adet = y.getInt(p + ".adet");
                kazanlar.put(k.anahtar(), k);
            } catch (Exception ex) {
                plugin.getLogger().warning("Bozuk kazan kaydı atlandı: " + p);
            }
        }
    }

    // ---------------------------------------------------------------- admin komutu
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("meslek.admin")) { s.sendMessage(ChatColor.RED + "Yetkin yok."); return true; }
        String alt = a.length == 0 ? "bilgi" : a[0].toLowerCase(Locale.ROOT);
        switch (alt) {
            case "kur":
            case "nokta": {
                if (!(s instanceof Player p)) return true;
                String islem = alt.equals("kur") ? "ekle" : (a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "liste");
                List<Location> n = noktalar();
                if (islem.equals("ekle")) {
                    n.add(p.getLocation());
                    noktalariKaydet(n);
                    s.sendMessage(ChatColor.GREEN + "Kaçakçı noktası #" + n.size() + " eklendi. Toplam nokta: " + n.size() + ". Her saat aralığında rastgele biri seçilir.");
                } else if (islem.equals("sil") && a.length >= 3) {
                    try {
                        int no = Integer.parseInt(a[2]);
                        if (no < 1 || no > n.size()) { s.sendMessage(ChatColor.RED + "Geçersiz numara."); return true; }
                        n.remove(no - 1);
                        noktalariKaydet(n);
                        s.sendMessage(ChatColor.YELLOW + "Nokta #" + no + " silindi.");
                    } catch (NumberFormatException ex) { s.sendMessage(ChatColor.RED + "Numara yaz: /kacakci nokta sil <no>"); }
                } else if (islem.equals("git") && a.length >= 3) {
                    try {
                        int no = Integer.parseInt(a[2]);
                        if (no < 1 || no > n.size()) { s.sendMessage(ChatColor.RED + "Geçersiz numara."); return true; }
                        p.teleport(n.get(no - 1));
                    } catch (NumberFormatException ex) { s.sendMessage(ChatColor.RED + "Numara yaz."); }
                } else {
                    s.sendMessage(ChatColor.YELLOW + "Kaçakçı noktaları (" + n.size() + "):");
                    for (int i = 0; i < n.size(); i++) {
                        Location k = n.get(i);
                        s.sendMessage(ChatColor.GRAY + " #" + (i + 1) + " " + k.getWorld().getName() + " " + k.getBlockX() + "," + k.getBlockY() + "," + k.getBlockZ());
                    }
                    s.sendMessage(ChatColor.GRAY + "/kacakci nokta ekle | sil <no> | git <no> | liste");
                }
                return true;
            }
            case "saat": {
                String islem = a.length >= 2 ? a[1].toLowerCase(Locale.ROOT) : "liste";
                List<String> sa = new ArrayList<>(plugin.getConfig().getStringList("kacakci.saatler"));
                if (islem.equals("ekle") && a.length >= 3) {
                    try {
                        String[] ab = a[2].split("-");
                        saatTick(ab[0]); saatTick(ab[1]);
                        sa.add(a[2]);
                        plugin.getConfig().set("kacakci.saatler", sa);
                        plugin.saveConfig();
                        aktifAnahtar = null;
                        s.sendMessage(ChatColor.GREEN + "Saat aralığı eklendi: " + a[2] + " (" + (gercekSaat() ? "gerçek saat" : "oyun saati") + ")");
                    } catch (Exception ex) { s.sendMessage(ChatColor.RED + "Biçim: /kacakci saat ekle 21:00-01:00"); }
                } else if (islem.equals("tur") && a.length >= 3 && (a[2].equalsIgnoreCase("gercek") || a[2].equalsIgnoreCase("oyun"))) {
                    plugin.getConfig().set("kacakci.saat-turu", a[2].toLowerCase(Locale.ROOT));
                    plugin.getConfig().set("kacakci.saat-turu-elle", true);
                    plugin.saveConfig();
                    aktifAnahtar = null;
                    s.sendMessage(ChatColor.GREEN + "Saat türü: " + a[2].toLowerCase(Locale.ROOT));
                } else if (islem.equals("rastgele") && a.length >= 3) {
                    plugin.getConfig().set("kacakci.rastgele.aktif", a[2].equalsIgnoreCase("ac"));
                    plugin.saveConfig();
                    oturumBitis = 0; sonrakiCikis = 0; aktifAnahtar = null;
                    s.sendMessage(ChatColor.GREEN + "Rastgele çıkış: " + (a[2].equalsIgnoreCase("ac") ? "açık" : "kapalı (aralık boyunca hep ortada)"));
                } else if (islem.equals("sil") && a.length >= 3) {
                    try {
                        int no = Integer.parseInt(a[2]);
                        if (no < 1 || no > sa.size()) { s.sendMessage(ChatColor.RED + "Geçersiz numara."); return true; }
                        sa.remove(no - 1);
                        plugin.getConfig().set("kacakci.saatler", sa);
                        plugin.saveConfig();
                        aktifAnahtar = null;
                        s.sendMessage(ChatColor.YELLOW + "Aralık silindi.");
                    } catch (NumberFormatException ex) { s.sendMessage(ChatColor.RED + "Numara yaz."); }
                } else {
                    s.sendMessage(ChatColor.YELLOW + "Kaçakçı saatleri (" + (gercekSaat() ? "gerçek saat, İstanbul" : "oyun saati") + "; rastgele çıkış: "
                            + (plugin.getConfig().getBoolean("kacakci.rastgele.aktif", true) ? "açık, kalma " + plugin.getConfig().getString(gercekSaat() ? "kacakci.rastgele.kalma-dk" : "kacakci.rastgele-oyun.kalma-dk") + " dk, bekleme " + plugin.getConfig().getString(gercekSaat() ? "kacakci.rastgele.bekleme-dk" : "kacakci.rastgele-oyun.bekleme-dk") + " dk (gerçek)" : "kapalı") + "): " + (sa.isEmpty() ? "yok (hiç çıkmaz)" : ""));
                    for (int i = 0; i < sa.size(); i++) s.sendMessage(ChatColor.GRAY + " #" + (i + 1) + " " + sa.get(i));
                    s.sendMessage(ChatColor.GRAY + "/kacakci saat ekle 21:00-01:00 | sil <no> | tur gercek|oyun | rastgele ac|kapat | liste");
                }
                return true;
            }
            case "cagir":
                zorlaBitis = System.currentTimeMillis() + 10 * 60_000L;
                aktifAnahtar = null;
                s.sendMessage(ChatColor.GREEN + "Kaçakçı 10 dakikalığına rastgele bir noktada belirecek (test).");
                return true;
            case "kaldir": {
                noktalariKaydet(new ArrayList<>());
                Entity e = npcId == null ? null : Bukkit.getEntity(npcId);
                if (e != null) e.remove();
                npcId = null;
                s.sendMessage(ChatColor.YELLOW + "Tüm kaçakçı noktaları silindi.");
                return true;
            }
            case "kazanlar":
                s.sendMessage(ChatColor.YELLOW + "Kayıtlı kazan sayısı: " + kazanlar.size());
                for (Kazan k : kazanlar.values())
                    s.sendMessage(ChatColor.GRAY + "- " + k.dunya + " " + k.x + "," + k.y + "," + k.z + " | " + k.sahipAd + " | " + k.asama);
                return true;
            case "ver":
                if (!(s instanceof Player pp) || a.length < 2) { s.sendMessage(ChatColor.RED + "/kacakci ver <kazan|golge|kizil|tuz|kara|mavi> [adet]"); return true; }
                int adet = 1;
                if (a.length >= 3) try { adet = Math.max(1, Math.min(64, Integer.parseInt(a[2]))); } catch (NumberFormatException ignored) { }
                ItemStack it;
                switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "kazan": it = esyaYap(ESYA_KAZAN); break;
                    case "maske": it = esyaYap(ESYA_MASKE); break;
                    case "golge": it = esyaYap(ESYA_GOLGE); break;
                    case "kizil": it = esyaYap(ESYA_KIZIL); break;
                    case "tuz": it = esyaYap(ESYA_TUZ); break;
                    case "kara": it = KacakMal.olustur(plugin, KacakMal.Tur.KARA_TOZ, 2, 1); break;
                    case "mavi": it = KacakMal.olustur(plugin, KacakMal.Tur.MAVI_OZ, 2, 1); break;
                    default: s.sendMessage(ChatColor.RED + "Bilinmeyen eşya."); return true;
                }
                it.setAmount(adet);
                pp.getInventory().addItem(it);
                plugin.ekonomiLog("kacakci-admin-ver", s.getName(), a[1] + " x" + adet);
                return true;
            default:
                s.sendMessage(ChatColor.YELLOW + "Kaçakçı: " + noktalar().size() + " nokta, saatler " + plugin.getConfig().getStringList("kacakci.saatler")
                        + " | şu an " + (npcId != null && Bukkit.getEntity(npcId) != null ? "ortada" : "yok") + " | kazan: " + kazanlar.size());
                s.sendMessage(ChatColor.GRAY + "/kacakci nokta ekle|sil|git|liste | saat ekle|sil|liste | cagir | kaldir | kazanlar | ver <eşya> [adet]");
                return true;
        }
    }
}
