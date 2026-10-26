package me.mesleksistemi.basarim;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Monster;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.GameMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Başarım (achievement) sistemi.
 *
 * Entry #4 tasarımı: 100+ başarımlık geniş bir havuz, kademeli (Bronz/Gümüş/Altın/Efsane)
 * eşikler, her sisteme yayılan kategoriler. Bu ilk sürüm (Faz 1) temel altyapıyı ve
 * Ekonomi kategorisindeki ilk kademeli seti kurar; diğer kategoriler (Savaş/Klan/Keşif/
 * Üretim/Zindan/Görev) ileride buraya BasarimTanim eklenerek büyütülecek - altyapı
 * (persist, idempotent ver(), bildirim) baştan genel/soyut tasarlandı.
 *
 * Veri güvenliği: PazarManager'daki standVeriYuklendi bug-fix kalıbı burada da uygulanıyor -
 * veriYukle() gerçekten tamamlanmadan veriKaydet() diske YAZMAZ (bkz. MeslekSistemi.java'daki
 * ana veriYuklendi alanı, aynı mantık).
 */
public class BasarimManager implements Listener, CommandExecutor {

    public static class BasarimTanim {
        public final String id;
        public final String ad;
        public final String aciklama;
        public final String kategori;
        public final String tier;
        public final Double ekonomiEsigi; // null ise otomatik/ekonomi-tabanlı kontrol edilmez, sadece ver() ile manuel verilir
        public final String sayac;        // null değilse: bu sayaç esik'e ulaşınca otomatik verilir (sayacArtir)
        public final long esik;

        public BasarimTanim(String id, String ad, String aciklama, String kategori, String tier, Double ekonomiEsigi) {
            this.id = id;
            this.ad = ad;
            this.aciklama = aciklama;
            this.kategori = kategori;
            this.tier = tier;
            this.ekonomiEsigi = ekonomiEsigi;
            this.sayac = null;
            this.esik = 0;
        }

        public BasarimTanim(String id, String ad, String aciklama, String kategori, String tier, String sayac, long esik) {
            this.id = id;
            this.ad = ad;
            this.aciklama = aciklama;
            this.kategori = kategori;
            this.tier = tier;
            this.ekonomiEsigi = null;
            this.sayac = sayac;
            this.esik = esik;
        }
    }

    private static final String[] KADEMELER = {"Bronz", "Gümüş", "Altın", "Efsane"};
    private static final String[] KADEME_ID = {"bronz", "gumus", "altin", "efsane"};

    /** Aynı sayaca bağlı 4 kademelik (Bronz/Gümüş/Altın/Efsane) başarım seti üretir. aciklama: {n} eşik ile değişir. */
    private static List<BasarimTanim> kademeli(String kategori, String sayac, String[] adlar, String aciklama, long[] esikler) {
        List<BasarimTanim> l = new ArrayList<>();
        for (int i = 0; i < KADEMELER.length; i++) {
            l.add(new BasarimTanim(sayac + "_" + KADEME_ID[i], adlar[i],
                    aciklama.replace("{n}", String.format(Locale.ROOT, "%,d", esikler[i]).replace(',', '.')), kategori, KADEMELER[i], sayac, esikler[i]));
        }
        return l;
    }

    private static List<BasarimTanim> havuzuOlustur() {
        List<BasarimTanim> l = new ArrayList<>();
        l.add(new BasarimTanim("ekonomi_bronz", "İlk Adım", "Banka bakiyen $1.000'a ulaştı.", "Ekonomi", "Bronz", 1000.0));
        l.add(new BasarimTanim("ekonomi_gumus", "Yükseliş", "Banka bakiyen $10.000'a ulaştı.", "Ekonomi", "Gümüş", 10000.0));
        l.add(new BasarimTanim("ekonomi_altin", "Zengin", "Banka bakiyen $100.000'a ulaştı.", "Ekonomi", "Altın", 100000.0));
        l.add(new BasarimTanim("ekonomi_efsane", "Milyoner", "Banka bakiyen $1.000.000'a ulaştı.", "Ekonomi", "Efsane", 1000000.0));
        // Savaş
        l.addAll(kademeli("Savaş", "savas_mob", new String[]{"Av Başlıyor", "Canavar Avcısı", "Karanlığın Kâbusu", "Efsanevi Avcı"},
                "{n} düşman canlıyı öldürdün.", new long[]{50, 500, 2500, 10000}));
        l.addAll(kademeli("Savaş", "savas_pvp", new String[]{"İlk Kan", "Düellocu", "Savaş Ustası", "Arena Efsanesi"},
                "{n} oyuncuyu öldürdün.", new long[]{1, 10, 50, 200}));
        // Keşif
        l.addAll(kademeli("Keşif", "kesif_biyom", new String[]{"Meraklı", "Gezgin", "Dünya Seyyahı", "Haritacı"},
                "{n} farklı biyom keşfettin.", new long[]{5, 12, 25, 40}));
        l.addAll(kademeli("Keşif", "kesif_uzaklik", new String[]{"Köyün Ötesi", "Uzak Diyarlar", "Ufkun Ötesi", "Dünyanın Kenarı"},
                "Dünya merkezinden {n} blok uzağa gittin.", new long[]{1000, 5000, 15000, 30000}));
        // Üretim / Meslek
        l.addAll(kademeli("Üretim/Meslek", "uretim_blok", new String[]{"Çırak Madenci", "Kazıcı", "Usta Kazıcı", "Dağ Yıkan"},
                "{n} blok kırdın.", new long[]{500, 5000, 25000, 100000}));
        l.addAll(kademeli("Üretim/Meslek", "uretim_ekin", new String[]{"Tohum Eken", "Çiftçi", "Bereket", "Hasat Kralı"},
                "{n} olgunlaşmış ekin hasat ettin.", new long[]{100, 1000, 5000, 20000}));
        l.addAll(kademeli("Üretim/Meslek", "uretim_balik", new String[]{"Olta Kurdu", "Balıkçı", "Usta Balıkçı", "Denizlerin Efendisi"},
                "{n} balık tuttun.", new long[]{25, 250, 1000, 5000}));
        // Görev (Görev Panosu)
        l.addAll(kademeli("Görev", "gorev", new String[]{"Göreve Hazır", "Güvenilir", "Görev Adamı", "Her İşin Ustası"},
                "{n} görev tamamladın.", new long[]{1, 5, 15, 30}));
        // Klan (Klan-Sistemi konsoldan sayaç artırır: /basarimsayac)
        l.addAll(kademeli("Klan", "klan_savas", new String[]{"Klan Savaşçısı", "Savaş Komutanı", "Fatih", "Klan Efsanesi"},
                "Klanınla {n} klan savaşı kazandın.", new long[]{1, 5, 15, 40}));
        l.addAll(kademeli("Klan", "klan_kale", new String[]{"Kale Yağmacısı", "Kale Avcısı", "Kale Fatihi", "Kalelerin Korkusu"},
                "{n} Vahşi Kale'yi temizledin.", new long[]{1, 5, 15, 40}));
        l.add(new BasarimTanim("klan_kurucu", "Kurucu", "Bir klan kurdun.", "Klan", "Bronz", "klan_kurucu", 1));
        // Zindan / Boss (Arena-Ligi konsoldan sayaç artırır)
        l.addAll(kademeli("Zindan/Boss", "zindan_boss", new String[]{"Zindan Avcısı", "Boss Kıran", "Derinlerin Efendisi", "Zindan Efsanesi"},
                "{n} zindan boss'u öldürdün.", new long[]{1, 5, 15, 40}));
        return List.copyOf(l);
    }

    // İlk kategori seti: Ekonomi (bakiye kademeleri). Diğer kategoriler ileride eklenecek.
    public static final List<BasarimTanim> TUM_BASARIMLAR = havuzuOlustur();

    private static final Map<String, BasarimTanim> TANIM_INDEX = new HashMap<>();
    static {
        for (BasarimTanim t : TUM_BASARIMLAR) TANIM_INDEX.put(t.id, t);
    }

    private final MeslekSistemi plugin;
    private boolean veriYuklendi = false;
    private final Map<UUID, Set<String>> kazanilan = new HashMap<>();
    // Sayaç tabanlı başarımlar: oyuncu -> (sayaç adı -> değer). kesif_uzaklik "en yüksek değer" olarak tutulur.
    private final Map<UUID, Map<String, Long>> sayaclar = new HashMap<>();
    private final Map<UUID, Set<String>> ziyaretEdilenBiyomlar = new HashMap<>();
    private final Map<UUID, Long> sonKesifKontrol = new HashMap<>();

    public BasarimManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        veriYukle();
        // Ekonomi başarımları için ayrı event hook'u yok (bakiye birçok yerden doğrudan
        // bankaHesaplari.put ile değişiyor) - bunun yerine online oyuncular periyodik
        // kontrol edilir. Düşük risk: mevcut para hareketi kodlarına dokunulmuyor.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tumOnlineOyunculariKontrolEt, 200L, 6000L); // ~5 dakikada bir
    }

    public void tumOnlineOyunculariKontrolEt() {
        if (!veriYuklendi) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            ekonomiKontrolEt(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            ekonomiKontrolEt(e.getPlayer());
            // Çevrimdışıyken biriken sayaçların başarımlarını ver
            UUID u = e.getPlayer().getUniqueId();
            Map<String, Long> m;
            synchronized (this) { m = new HashMap<>(sayaclar.getOrDefault(u, Map.of())); }
            for (Map.Entry<String, Long> en : m.entrySet()) sayacKontrol(u, en.getKey(), en.getValue());
        }, 40L);
    }

    public synchronized void ekonomiKontrolEt(Player p) {
        if (!veriYuklendi) return;
        double bakiye = plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
        for (BasarimTanim t : TUM_BASARIMLAR) {
            if (t.ekonomiEsigi != null && bakiye >= t.ekonomiEsigi) {
                ver(p, t.id);
            }
        }
    }

    // ------------------------------------------------------------------ SAYAÇLAR
    /** Sayacı artırır ve eşiğe ulaşan başarımları verir. Çevrimdışı oyuncuda sayılır, başarım bir sonraki girişte verilir. */
    public synchronized void sayacArtir(UUID uuid, String sayac, long artis) {
        if (!veriYuklendi || artis <= 0) return;
        Map<String, Long> m = sayaclar.computeIfAbsent(uuid, k -> new HashMap<>());
        long yeni = m.merge(sayac, artis, Long::sum);
        sayacKontrol(uuid, sayac, yeni);
        sayacKaydetGecikmeli();
    }

    /** Sayacı, mevcut değerden büyükse bu değere yükseltir (örn. en uzak mesafe). */
    public synchronized void sayacEnAz(UUID uuid, String sayac, long deger) {
        if (!veriYuklendi) return;
        Map<String, Long> m = sayaclar.computeIfAbsent(uuid, k -> new HashMap<>());
        long eski = m.getOrDefault(sayac, 0L);
        if (deger <= eski) return;
        m.put(sayac, deger);
        sayacKontrol(uuid, sayac, deger);
        sayacKaydetGecikmeli();
    }

    public synchronized long sayacDegeri(UUID uuid, String sayac) {
        return sayaclar.getOrDefault(uuid, Map.of()).getOrDefault(sayac, 0L);
    }

    private void sayacKontrol(UUID uuid, String sayac, long deger) {
        Player p = Bukkit.getPlayer(uuid);
        if (p == null) return;
        for (BasarimTanim t : TUM_BASARIMLAR) {
            if (t.sayac != null && t.sayac.equals(sayac) && deger >= t.esik) ver(p, t.id);
        }
    }

    private boolean kaydetBekliyor = false;
    /** Sık artan sayaçlar için diske yazmayı ~30 sn'de bire indirir (her blok kırışında yazmasın). */
    private void sayacKaydetGecikmeli() {
        if (kaydetBekliyor) return;
        kaydetBekliyor = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> { kaydetBekliyor = false; veriKaydet(); }, 600L);
    }

    // ------------------------------------------------------------------ OLAYLAR (sayaç kaynakları)
    @EventHandler(ignoreCancelled = true)
    public void onBlokKir(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        sayacArtir(p.getUniqueId(), "uretim_blok", 1);
        if (e.getBlock().getBlockData() instanceof Ageable a && a.getAge() >= a.getMaximumAge()) {
            sayacArtir(p.getUniqueId(), "uretim_ekin", 1);
        }
    }

    @EventHandler
    public void onBalik(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) sayacArtir(e.getPlayer().getUniqueId(), "uretim_balik", 1);
    }

    @EventHandler
    public void onOldur(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null) return;
        if (e.getEntity() instanceof Player) sayacArtir(k.getUniqueId(), "savas_pvp", 1);
        else if (e.getEntity() instanceof Monster) sayacArtir(k.getUniqueId(), "savas_mob", 1);
    }

    /** Keşif: sadece oyuncu yeni bir chunk'a geçince (çok sık değil) biyom ve merkezden uzaklık değerlendirilir. */
    @EventHandler(ignoreCancelled = true)
    public void onHareket(PlayerMoveEvent e) {
        if (e.getTo() == null || (e.getFrom().getBlockX() >> 4 == e.getTo().getBlockX() >> 4 && e.getFrom().getBlockZ() >> 4 == e.getTo().getBlockZ() >> 4)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        UUID u = p.getUniqueId();
        if (veriYuklendi) {
            org.bukkit.Location l = e.getTo();
            String biyom = l.getWorld().getBiome(l.getBlockX(), Math.max(l.getBlockY(), l.getWorld().getMinHeight()), l.getBlockZ()).getKey().toString();
            Set<String> set;
            synchronized (this) { set = ziyaretEdilenBiyomlar.computeIfAbsent(u, k -> new HashSet<>()); }
            if (set.add(biyom)) {
                synchronized (this) { sayaclar.computeIfAbsent(u, k -> new HashMap<>()).put("kesif_biyom", (long) set.size()); }
                sayacKontrol(u, "kesif_biyom", set.size());
                sayacKaydetGecikmeli();
            }
            // Sadece Overworld merkezinden uzaklık (Nether koordinatları 8x ölçekli olduğu için sayılmaz)
            if (l.getWorld().getEnvironment() == org.bukkit.World.Environment.NORMAL) {
                long uzaklik = (long) Math.sqrt(l.getX() * l.getX() + l.getZ() * l.getZ());
                sayacEnAz(u, "kesif_uzaklik", uzaklik);
            }
        }
    }

    /** Başarımı verir. Zaten kazanılmışsa idempotent (tekrar vermez, false döner). */
    public synchronized boolean ver(Player p, String basarimId) {
        if (!veriYuklendi) return false;
        Set<String> set = kazanilan.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (set.contains(basarimId)) return false; // idempotent - dupe/çift bildirim önleme
        set.add(basarimId);
        veriKaydet();

        BasarimTanim t = TANIM_INDEX.get(basarimId);
        String ad = t != null ? t.ad : basarimId;
        String tier = t != null ? t.tier : "";
        String aciklama = t != null ? t.aciklama : "";

        p.sendMessage("");
        p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "★ BAŞARIM KAZANILDI: " + ChatColor.YELLOW + ad + ChatColor.GOLD + " [" + tier + "]");
        if (!aciklama.isEmpty()) p.sendMessage(ChatColor.GRAY + aciklama);
        p.sendMessage("");
        try {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
        plugin.ekonomiLog("basarim-kazanildi", p.getName(), "Başarım kazanıldı: " + basarimId + " (" + ad + ", " + tier + ")");
        return true;
    }

    public Set<String> kazanilanlar(UUID uuid) {
        return kazanilan.getOrDefault(uuid, new HashSet<>());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Diğer eklentiler (Klan-Sistemi, Arena-Ligi) başarım sayacını konsol komutuyla artırır:
        // /basarimsayac <oyuncu> <sayaç> [artış]   (sadece konsol ya da meslek.admin)
        if (command.getName().equalsIgnoreCase("basarimsayac")) {
            if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !sender.hasPermission("meslek.admin")) return true;
            if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Kullanım: /basarimsayac <oyuncu> <sayaç> [artış]"); return true; }
            Player hedef = Bukkit.getPlayerExact(args[0]);
            if (hedef == null) { sender.sendMessage(ChatColor.RED + "Oyuncu çevrimiçi değil: " + args[0]); return true; }
            boolean taniyor = false;
            for (BasarimTanim t : TUM_BASARIMLAR) if (args[1].equals(t.sayac)) { taniyor = true; break; }
            if (!taniyor) { sender.sendMessage(ChatColor.RED + "Bilinmeyen sayaç: " + args[1]); return true; }
            long artis = 1;
            if (args.length >= 3) {
                try { artis = Math.min(1000L, Long.parseLong(args[2])); } catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "Artış sayı olmalı."); return true; }
            }
            sayacArtir(hedef.getUniqueId(), args[1], artis);
            return true;
        }

        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("basarimlarim")) {
            Set<String> kazanilanlar = kazanilanlar(player.getUniqueId());
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "=== Başarımların (" + kazanilanlar.size() + "/" + TUM_BASARIMLAR.size() + ") ===");
            String oncekiKategori = "";
            for (BasarimTanim t : TUM_BASARIMLAR) {
                if (!t.kategori.equals(oncekiKategori)) {
                    oncekiKategori = t.kategori;
                    player.sendMessage(ChatColor.AQUA + "" + ChatColor.BOLD + "-- " + t.kategori + " --");
                }
                boolean var = kazanilanlar.contains(t.id);
                ChatColor renk = var ? ChatColor.GREEN : ChatColor.DARK_GRAY;
                String isaret = var ? "✔" : "✘";
                String ilerleme = "";
                if (!var && t.sayac != null) ilerleme = ChatColor.DARK_AQUA + " (" + Math.min(sayacDegeri(player.getUniqueId(), t.sayac), t.esik) + "/" + t.esik + ")";
                player.sendMessage(renk + isaret + " " + t.ad + ChatColor.GRAY + " [" + t.tier + "] - " + t.aciklama + ilerleme);
            }
            return true;
        }
        return false;
    }

    // ---- Veri güvenliği: standVeriYuklendi kalıbının aynısı ----

    public void veriKaydet() {
        if (!veriYuklendi) {
            plugin.getLogger().warning("[Basarim] veriKaydet() veriYukle() tamamlanmadan çağrıldı, atlandı (veri kaybını önlemek için).");
            return;
        }
        for (Map.Entry<UUID, Set<String>> entry : kazanilan.entrySet()) {
            plugin.getConfig().set("basarimlar." + entry.getKey().toString(), new ArrayList<>(entry.getValue()));
        }
        synchronized (this) {
            for (Map.Entry<UUID, Map<String, Long>> entry : sayaclar.entrySet()) {
                for (Map.Entry<String, Long> c : entry.getValue().entrySet()) {
                    plugin.getConfig().set("basarim_sayaclar." + entry.getKey() + "." + c.getKey(), c.getValue());
                }
            }
            for (Map.Entry<UUID, Set<String>> entry : ziyaretEdilenBiyomlar.entrySet()) {
                plugin.getConfig().set("basarim_biyomlar." + entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }
        plugin.saveConfig();
    }

    public void veriYukle() {
        try {
            kazanilan.clear();
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("basarimlar");
            if (sec != null) {
                for (String uuidStr : sec.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        List<String> liste = plugin.getConfig().getStringList("basarimlar." + uuidStr);
                        kazanilan.put(uuid, new HashSet<>(liste));
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Basarim] " + uuidStr + " için başarım verisi okunamadı, atlandı: " + innerEx.getMessage());
                    }
                }
            }
            sayaclar.clear();
            ziyaretEdilenBiyomlar.clear();
            ConfigurationSection sc = plugin.getConfig().getConfigurationSection("basarim_sayaclar");
            if (sc != null) {
                for (String uuidStr : sc.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        ConfigurationSection alt = sc.getConfigurationSection(uuidStr);
                        if (alt == null) continue;
                        Map<String, Long> m = new HashMap<>();
                        for (String k : alt.getKeys(false)) m.put(k, alt.getLong(k));
                        sayaclar.put(uuid, m);
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Basarim] " + uuidStr + " sayaçları okunamadı, atlandı: " + innerEx.getMessage());
                    }
                }
            }
            ConfigurationSection bs = plugin.getConfig().getConfigurationSection("basarim_biyomlar");
            if (bs != null) {
                for (String uuidStr : bs.getKeys(false)) {
                    try {
                        ziyaretEdilenBiyomlar.put(UUID.fromString(uuidStr), new HashSet<>(plugin.getConfig().getStringList("basarim_biyomlar." + uuidStr)));
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Basarim] " + uuidStr + " biyom listesi okunamadı, atlandı: " + innerEx.getMessage());
                    }
                }
            }
            veriYuklendi = true;
        } catch (Exception e) {
            plugin.getLogger().severe("[Basarim] veriYukle() sırasında hata, veriYuklendi=false kalacak (kayıt atlanacak): " + e.getMessage());
            veriYuklendi = false;
        }
    }
}
