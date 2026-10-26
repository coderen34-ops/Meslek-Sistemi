package me.mesleksistemi.gorev;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Görev (quest) sistemi - Entry #4 / Faz 2, madde 5: dönen günlük (3) / haftalık (2) havuz, kalıcı görevler,
 * zincir görevler (Kayıp Kurye, Madenci Yolu), TOPLA/TESLİM_ET/ÖLDÜR/KAZAN/ZİYARET_ET/SATIŞ_YAP/
 * SÖZLEŞME_TAMAMLA/TURNUVAYA_KATIL türleri; ödül para + XP + eşya. Alma sınırı yok (karar).
 *
 * Veri güvenliği: PazarManager/BasarimManager/BountyManager'daki aynı veriYuklendi boolean-guard kalıbı uygulanır.
 */
public class GorevManager implements Listener, CommandExecutor {

    public enum GorevTuru { TOPLA, OLDUR, KAZAN, TESLIM_ET, ZIYARET_ET, SATIS_YAP, SOZLESME_TAMAMLA, TURNUVAYA_KATIL }
    public enum Havuz { SABIT, GUNLUK, HAFTALIK, ZINCIR }

    public static class GorevTanim {
        public final String id;
        public final String ad;
        public final String aciklama;
        public final GorevTuru tur;
        public final Havuz havuz;
        public Material hedefMateryal;
        public EntityType hedefVarlik;
        public int hedefMiktar;
        public double hedefKazanc;
        public String hedefBiyom;     // ZIYARET_ET
        public String sayacAd;        // SATIS_YAP / SOZLESME_TAMAMLA / TURNUVAYA_KATIL: BasarimManager sayacı farkı
        public final double odul;
        public int xp;
        public Material odulEsya; public int odulEsyaAdet;
        public final String zorluk;
        public String onceki;         // ZINCIR: bir önceki aşamanın id'si (null = ilk aşama)
        public String zincirAd;

        private GorevTanim(String id, String ad, String aciklama, GorevTuru tur, Havuz havuz, double odul, String zorluk) {
            this.id = id; this.ad = ad; this.aciklama = aciklama; this.tur = tur; this.havuz = havuz; this.odul = odul; this.zorluk = zorluk;
        }

        GorevTanim xp(int x) { this.xp = x; return this; }
        GorevTanim esya(Material m, int adet) { this.odulEsya = m; this.odulEsyaAdet = adet; return this; }
        GorevTanim zincir(String zAd, String onceki) { this.zincirAd = zAd; this.onceki = onceki; return this; }

        static GorevTanim topla(Havuz h, String id, String ad, String aciklama, Material mat, int miktar, double odul, String zorluk) {
            GorevTanim g = new GorevTanim(id, ad, aciklama, GorevTuru.TOPLA, h, odul, zorluk); g.hedefMateryal = mat; g.hedefMiktar = miktar; return g;
        }
        static GorevTanim teslim(Havuz h, String id, String ad, String aciklama, Material mat, int miktar, double odul, String zorluk) {
            GorevTanim g = new GorevTanim(id, ad, aciklama, GorevTuru.TESLIM_ET, h, odul, zorluk); g.hedefMateryal = mat; g.hedefMiktar = miktar; return g;
        }
        static GorevTanim oldur(Havuz h, String id, String ad, String aciklama, EntityType varlik, int miktar, double odul, String zorluk) {
            GorevTanim g = new GorevTanim(id, ad, aciklama, GorevTuru.OLDUR, h, odul, zorluk); g.hedefVarlik = varlik; g.hedefMiktar = miktar; return g;
        }
        static GorevTanim kazan(Havuz h, String id, String ad, String aciklama, double hedefKazanc, double odul, String zorluk) {
            GorevTanim g = new GorevTanim(id, ad, aciklama, GorevTuru.KAZAN, h, odul, zorluk); g.hedefKazanc = hedefKazanc; return g;
        }
        static GorevTanim ziyaret(Havuz h, String id, String ad, String aciklama, String biyom, double odul, String zorluk) {
            GorevTanim g = new GorevTanim(id, ad, aciklama, GorevTuru.ZIYARET_ET, h, odul, zorluk); g.hedefBiyom = biyom; g.hedefMiktar = 1; return g;
        }
        static GorevTanim sayac(Havuz h, GorevTuru tur, String sayacAd, String id, String ad, String aciklama, int miktar, double odul, String zorluk) {
            GorevTanim g = new GorevTanim(id, ad, aciklama, tur, h, odul, zorluk); g.sayacAd = sayacAd; g.hedefMiktar = miktar; return g;
        }
    }

    private static final Havuz S = Havuz.SABIT, G = Havuz.GUNLUK, H = Havuz.HAFTALIK, Z = Havuz.ZINCIR;

    /** Kalıcı (tek seferlik) görevler - eski sürümden aynen korunur. */
    public static final List<GorevTanim> SABIT_GOREVLER = List.of(
            GorevTanim.topla(S, "odun_toplama_1", "Oduncu Çırağı", "32 adet meşe kütüğü topla ve Görev Panosu'na teslim et.", Material.OAK_LOG, 32, 300.0, "Kolay"),
            GorevTanim.topla(S, "tasci_1", "Taş Ocağı", "64 adet taş topla ve Görev Panosu'na teslim et.", Material.COBBLESTONE, 64, 250.0, "Kolay"),
            GorevTanim.oldur(S, "zombi_avi_1", "Gece Nöbeti", "10 zombi öldür.", EntityType.ZOMBIE, 10, 400.0, "Orta"),
            GorevTanim.oldur(S, "iskelet_avi_1", "Kemik Avcısı", "15 iskelet öldür.", EntityType.SKELETON, 15, 500.0, "Orta"),
            GorevTanim.kazan(S, "kazanc_1", "Girişimci", "Toplam $5.000 net kazanç elde et (ticaret, maaş, satış - her yoldan sayılır).", 5000.0, 200.0, "Orta")
    );

    /** Günlük havuz: her gün 3 tanesi (Kolay/Orta/Zor karışık) rastgele seçilir. */
    public static final List<GorevTanim> GUNLUK_HAVUZ = List.of(
            GorevTanim.topla(G, "g_bugday", "Fırın Sırası", "48 buğday topla ve panoya teslim et.", Material.WHEAT, 48, 220.0, "Kolay").xp(30),
            GorevTanim.topla(G, "g_komur", "Kömür Vardiyası", "64 kömür topla.", Material.COAL, 64, 260.0, "Kolay").xp(30),
            GorevTanim.topla(G, "g_demir", "Demirci Siparişi", "24 demir külçesi teslim et.", Material.IRON_INGOT, 24, 520.0, "Orta").xp(60),
            GorevTanim.teslim(G, "g_balik", "Taze Balık", "16 çiğ morina teslim et.", Material.COD, 16, 300.0, "Kolay").xp(30),
            GorevTanim.oldur(G, "g_creeper", "Patlayıcı Tehdit", "8 creeper öldür.", EntityType.CREEPER, 8, 450.0, "Orta").xp(60),
            GorevTanim.oldur(G, "g_orumcek", "Ağ Temizliği", "12 örümcek öldür.", EntityType.SPIDER, 12, 420.0, "Orta").xp(60),
            GorevTanim.oldur(G, "g_zombi", "Sürü Avı", "25 zombi öldür.", EntityType.ZOMBIE, 25, 800.0, "Zor").xp(120),
            GorevTanim.sayac(G, GorevTuru.SATIS_YAP, "pazar_satis", "g_satis", "Tezgâh Açılışı", "Pazar standında 10 eşya sat.", 10, 350.0, "Kolay").xp(40),
            GorevTanim.ziyaret(G, "g_col", "Çöl Seferi", "Bir çöl biyomuna git.", "desert", 400.0, "Orta").xp(50),
            GorevTanim.ziyaret(G, "g_orman", "Derin Orman", "Karanlık orman biyomuna git.", "dark_forest", 500.0, "Orta").xp(50),
            GorevTanim.kazan(G, "g_kazanc", "Günün Kazancı", "Bugün $1.500 net kazanç elde et.", 1500.0, 300.0, "Kolay").xp(40),
            GorevTanim.topla(G, "g_elmas", "Parıltı Peşinde", "4 elmas topla.", Material.DIAMOND, 4, 900.0, "Zor").xp(150)
    );

    /** Haftalık havuz: her hafta 2 tanesi seçilir; daha büyük ödül. */
    public static final List<GorevTanim> HAFTALIK_HAVUZ = List.of(
            GorevTanim.topla(H, "h_tas", "Büyük Şantiye", "512 taş topla.", Material.COBBLESTONE, 512, 2200.0, "Orta").xp(250).esya(Material.IRON_BLOCK, 2),
            GorevTanim.oldur(H, "h_avci", "Haftanın Avcısı", "80 düşman canlı öldür (zombi).", EntityType.ZOMBIE, 80, 3000.0, "Zor").xp(400).esya(Material.DIAMOND, 3),
            GorevTanim.sayac(H, GorevTuru.SATIS_YAP, "pazar_satis", "h_satis", "Pazarın Yıldızı", "Pazar standında 100 eşya sat.", 100, 2800.0, "Orta").xp(300),
            GorevTanim.sayac(H, GorevTuru.SOZLESME_TAMAMLA, "sozlesme_tamam", "h_sozlesme", "Sözünün Eri", "2 sözleşmeyi başarıyla tamamla.", 2, 3500.0, "Zor").xp(350).esya(Material.EMERALD, 8),
            GorevTanim.sayac(H, GorevTuru.TURNUVAYA_KATIL, "turnuva_katilim", "h_turnuva", "Arenaya Atıl", "Bir Arena turnuvasına katıl.", 1, 2500.0, "Orta").xp(300).esya(Material.GOLDEN_APPLE, 2),
            GorevTanim.kazan(H, "h_kazanc", "Haftalık Hedef", "Bu hafta $15.000 net kazanç elde et.", 15000.0, 3200.0, "Zor").xp(350),
            GorevTanim.ziyaret(H, "h_kar", "Kuzey Seferi", "Karlı bir biyoma git (snowy_plains).", "snowy_plains", 1800.0, "Orta").xp(200)
    );

    /** Zincir görevler: aşama bitince devamı açılır; son aşamada özel ödül. */
    public static final List<GorevTanim> ZINCIR_GOREVLER = List.of(
            GorevTanim.teslim(Z, "kurye_1", "Kayıp Kurye I: Erzak", "Kayıp kuryenin ailesine 16 ekmek teslim et.", Material.BREAD, 16, 400.0, "Kolay").zincir("Kayıp Kurye", null).xp(50),
            GorevTanim.oldur(Z, "kurye_2", "Kayıp Kurye II: Haydutlar", "Yolu kesen 10 yağmacıyı öldür.", EntityType.PILLAGER, 10, 700.0, "Orta").zincir("Kayıp Kurye", "kurye_1").xp(100),
            GorevTanim.teslim(Z, "kurye_3", "Kayıp Kurye III: Son Paket", "Kuryenin son paketi için 1 pusula teslim et.", Material.COMPASS, 1, 1500.0, "Orta").zincir("Kayıp Kurye", "kurye_2").xp(200).esya(Material.DIAMOND, 5),
            GorevTanim.teslim(Z, "maden_1", "Madenci Yolu I: Demir", "32 demir külçesi teslim et.", Material.IRON_INGOT, 32, 600.0, "Orta").zincir("Madenci Yolu", null).xp(80),
            GorevTanim.teslim(Z, "maden_2", "Madenci Yolu II: Altın", "16 altın külçesi teslim et.", Material.GOLD_INGOT, 16, 800.0, "Orta").zincir("Madenci Yolu", "maden_1").xp(120),
            GorevTanim.teslim(Z, "maden_3", "Madenci Yolu III: Elmas", "8 elmas teslim et.", Material.DIAMOND, 8, 2500.0, "Zor").zincir("Madenci Yolu", "maden_2").xp(300).esya(Material.NETHERITE_SCRAP, 2)
    );

    public static final List<GorevTanim> TUM_GOREVLER;
    private static final Map<String, GorevTanim> TANIM_INDEX = new HashMap<>();
    static {
        List<GorevTanim> l = new ArrayList<>();
        l.addAll(SABIT_GOREVLER); l.addAll(GUNLUK_HAVUZ); l.addAll(HAFTALIK_HAVUZ); l.addAll(ZINCIR_GOREVLER);
        TUM_GOREVLER = List.copyOf(l);
        for (GorevTanim g : TUM_GOREVLER) TANIM_INDEX.put(g.id, g);
    }

    private static final long ISTANBUL_OFFSET = 3L * 3600_000L;
    private static final long GUN_MS = 86_400_000L;

    private static long gunNo(long simdi) { return (simdi + ISTANBUL_OFFSET) / GUN_MS; }
    private static long haftaNo(long simdi) { return gunNo(simdi) / 7; }
    private static long periyot(Havuz h, long simdi) { return h == Havuz.GUNLUK ? gunNo(simdi) : h == Havuz.HAFTALIK ? haftaNo(simdi) : 0; }
    private static long periyotBitis(Havuz h, long no) {
        if (h == Havuz.GUNLUK) return (no + 1) * GUN_MS - ISTANBUL_OFFSET;
        if (h == Havuz.HAFTALIK) return (no + 1) * 7 * GUN_MS - ISTANBUL_OFFSET;
        return 0;
    }

    /** Örnek id: sabit/zincir görevde tanım id'si, günlük/haftalıkta "tanimId@periyot". */
    private static String tanimIdOf(String ornekId) { int i = ornekId.indexOf('@'); return i < 0 ? ornekId : ornekId.substring(0, i); }
    private static GorevTanim tanimBul(String ornekId) { return TANIM_INDEX.get(tanimIdOf(ornekId)); }
    private static String ornekId(GorevTanim g, long simdi) { return g.havuz == Havuz.GUNLUK || g.havuz == Havuz.HAFTALIK ? g.id + "@" + periyot(g.havuz, simdi) : g.id; }

    /** Periyoda göre DETERMİNİSTİK seçim (herkese aynı günün görevleri; depolama gerekmez). */
    private static List<GorevTanim> gununSecimi(Havuz h, long simdi) {
        List<GorevTanim> havuz = h == Havuz.GUNLUK ? GUNLUK_HAVUZ : HAFTALIK_HAVUZ;
        List<GorevTanim> kopya = new ArrayList<>(havuz);
        java.util.Collections.shuffle(kopya, new java.util.Random(periyot(h, simdi) * 7919L + (h == Havuz.GUNLUK ? 11 : 29)));
        return kopya.subList(0, Math.min(h == Havuz.GUNLUK ? 3 : 2, kopya.size()));
    }

    private static class Ilerleme {
        int sayac = 0;             // OLDUR / ZIYARET_ET
        double baslangicBakiye = 0; // KAZAN
        long baslangicSayac = 0;    // SATIS_YAP / SOZLESME_TAMAMLA / TURNUVAYA_KATIL (BasarimManager sayacı farkı)
        long bitis = 0;             // günlük/haftalık: süre sonu (0 = süresiz)
    }

    private final MeslekSistemi plugin;
    private final NamespacedKey gorevNpcKey;
    private final NamespacedKey gorevIdKey;
    private boolean veriYuklendi = false;

    private final Map<UUID, Map<String, Ilerleme>> aktifGorevler = new HashMap<>();
    private final Map<UUID, Set<String>> tamamlananGorevler = new HashMap<>();

    public GorevManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.gorevNpcKey = new NamespacedKey(plugin, "gorev_npc");
        this.gorevIdKey = new NamespacedKey(plugin, "gorev_id");
        veriYukle();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("gorevnpc")) {
            if (!player.hasPermission("meslek.admin") && !player.isOp()) {
                player.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkin yok.");
                return true;
            }
            if (args.length < 1) {
                player.sendMessage(ChatColor.RED + "Kullanım: /gorevnpc kur | sil");
                return true;
            }
            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
                npc.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Görev Panosu");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.CARTOGRAPHER);
                npc.getPersistentDataContainer().set(gorevNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Görev Panosu NPC'si kuruldu!");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(gorevNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(silinen > 0 ? ChatColor.GREEN + "" + silinen + " Görev Panosu NPC'si silindi." : ChatColor.RED + "Yakında Görev Panosu NPC'si bulunamadı.");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("gorevlerim")) {
            if (!veriYuklendi) {
                player.sendMessage(ChatColor.RED + "Sistem henüz hazır değil, birazdan tekrar dene.");
                return true;
            }
            suresiDolanlariTemizle(player.getUniqueId());
            Map<String, Ilerleme> aktif = aktifGorevler.getOrDefault(player.getUniqueId(), Map.of());
            Set<String> tamam = tamamlananGorevler.getOrDefault(player.getUniqueId(), Set.of());
            player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "=== Görevlerin ===");
            if (aktif.isEmpty()) {
                player.sendMessage(ChatColor.GRAY + "Aktif görevin yok. Görev Panosu'na gidip yeni görev kabul edebilirsin.");
            } else {
                for (Map.Entry<String, Ilerleme> e : aktif.entrySet()) {
                    GorevTanim g = tanimBul(e.getKey());
                    if (g == null) continue;
                    String kalan = e.getValue().bitis > 0 ? ChatColor.DARK_GRAY + " | kalan: " + sureMetni(e.getValue().bitis - System.currentTimeMillis()) : "";
                    player.sendMessage(ChatColor.YELLOW + "- " + g.ad + ChatColor.GRAY + " (" + ilerlemeMetni(player, g, e.getValue()) + ")" + kalan);
                }
            }
            long sabitBitti = tamam.stream().filter(id -> !id.contains("@")).count();
            player.sendMessage(ChatColor.DARK_GRAY + "Tamamlanan kalıcı/zincir görev: " + sabitBitti + " | günlük/haftalık tamamlanan: " + (tamam.size() - sabitBitti));
            return true;
        }
        return false;
    }

    private String sureMetni(long ms) {
        if (ms <= 0) return "doldu";
        long dk = ms / 60_000L;
        if (dk >= 1440) return (dk / 1440) + " gün " + ((dk % 1440) / 60) + " sa";
        if (dk >= 60) return (dk / 60) + " sa " + (dk % 60) + " dk";
        return dk + " dk";
    }

    private long sayacFarki(Player p, GorevTanim g, Ilerleme i) {
        if (plugin.basarimManager == null || g.sayacAd == null) return 0;
        return Math.max(0, plugin.basarimManager.sayacDegeri(p.getUniqueId(), g.sayacAd) - i.baslangicSayac);
    }

    private String ilerlemeMetni(Player p, GorevTanim g, Ilerleme i) {
        switch (g.tur) {
            case TOPLA: case TESLIM_ET:
                return sayEsya(p, g.hedefMateryal) + "/" + g.hedefMiktar + " " + guzelAd(g.hedefMateryal) + (g.tur == GorevTuru.TESLIM_ET ? " (panoya teslim)" : "");
            case OLDUR:
                return i.sayac + "/" + g.hedefMiktar + " " + guzelAd(g.hedefVarlik);
            case KAZAN: {
                double k = Math.max(0, plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0) - i.baslangicBakiye);
                return "$" + fmt(k) + "/$" + fmt(g.hedefKazanc);
            }
            case ZIYARET_ET:
                return (i.sayac >= 1 ? "ziyaret edildi" : "henüz gidilmedi") + " (" + g.hedefBiyom.replace('_', ' ') + ")";
            case SATIS_YAP: case SOZLESME_TAMAMLA: case TURNUVAYA_KATIL:
                return Math.min(sayacFarki(p, g, i), g.hedefMiktar) + "/" + g.hedefMiktar;
            default:
                return "?";
        }
    }

    private String guzelAd(Material m) { return m == null ? "" : m.name().toLowerCase(Locale.ROOT).replace('_', ' '); }
    private String guzelAd(EntityType e) { return e == null ? "" : e.name().toLowerCase(Locale.ROOT).replace('_', ' '); }
    private String fmt(double d) { return String.format(Locale.US, "%.2f", d); }

    private void suresiDolanlariTemizle(UUID u) {
        Map<String, Ilerleme> aktif = aktifGorevler.get(u);
        if (aktif == null) return;
        long simdi = System.currentTimeMillis();
        boolean degisti = aktif.entrySet().removeIf(e -> e.getValue().bitis > 0 && e.getValue().bitis < simdi);
        if (degisti) veriKaydet();
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!(event.getRightClicked() instanceof Villager)) return;
        Villager npc = (Villager) event.getRightClicked();
        if (!npc.getPersistentDataContainer().has(gorevNpcKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (!veriYuklendi) {
            event.getPlayer().sendMessage(ChatColor.RED + "Sistem henüz hazır değil, birazdan tekrar dene.");
            return;
        }
        openGorevGUI(event.getPlayer());
    }

    private static final String GUI_BASLIK = ChatColor.GOLD + "Görev Panosu";

    private ItemStack etiket(Material m, String ad, String... satirlar) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        List<String> lore = new ArrayList<>();
        for (String s : satirlar) lore.add(s);
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack gorevOgesi(Player player, GorevTanim g, String ornekId, Set<String> tamam, Map<String, Ilerleme> aktif) {
        Material simge = switch (g.tur) {
            case TOPLA, TESLIM_ET -> Material.CHEST; case OLDUR -> Material.IRON_SWORD; case KAZAN -> Material.GOLD_INGOT;
            case ZIYARET_ET -> Material.COMPASS; case SATIS_YAP -> Material.EMERALD; case SOZLESME_TAMAMLA -> Material.WRITABLE_BOOK; case TURNUVAYA_KATIL -> Material.NETHERITE_SWORD;
        };
        boolean bitti = tamam.contains(ornekId);
        Ilerleme ilerleme = aktif.get(ornekId);
        ItemStack item = new ItemStack(bitti ? Material.PAPER : simge);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((bitti ? ChatColor.DARK_GRAY : ChatColor.YELLOW) + g.ad + ChatColor.GRAY + " [" + g.zorluk + "]");
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.WHITE + g.aciklama);
        StringBuilder odul = new StringBuilder(ChatColor.GREEN + "Ödül: $" + fmt(g.odul));
        if (g.xp > 0) odul.append(ChatColor.AQUA).append(" + ").append(g.xp).append(" XP");
        if (g.odulEsya != null) odul.append(ChatColor.LIGHT_PURPLE).append(" + ").append(g.odulEsyaAdet).append("x ").append(guzelAd(g.odulEsya));
        lore.add(odul.toString());
        if (bitti) {
            lore.add(ChatColor.DARK_GRAY + "✔ Tamamlandı");
        } else if (ilerleme != null) {
            lore.add(ChatColor.AQUA + "İlerleme: " + ilerlemeMetni(player, g, ilerleme));
            if (ilerleme.bitis > 0) lore.add(ChatColor.GRAY + "Kalan süre: " + sureMetni(ilerleme.bitis - System.currentTimeMillis()));
            lore.add(ChatColor.GREEN + "Tıkla: teslim etmeyi dene");
        } else {
            long bitis = g.havuz == Havuz.GUNLUK || g.havuz == Havuz.HAFTALIK ? periyotBitis(g.havuz, periyot(g.havuz, System.currentTimeMillis())) : 0;
            if (bitis > 0) lore.add(ChatColor.GRAY + "Yenilenmesine: " + sureMetni(bitis - System.currentTimeMillis()));
            lore.add(ChatColor.YELLOW + "Tıkla: görevi kabul et");
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(gorevIdKey, PersistentDataType.STRING, ornekId);
        item.setItemMeta(meta);
        return item;
    }

    private void openGorevGUI(Player player) {
        suresiDolanlariTemizle(player.getUniqueId());
        long simdi = System.currentTimeMillis();
        Inventory gui = Bukkit.createInventory(null, 54, GUI_BASLIK);
        Set<String> tamam = tamamlananGorevler.getOrDefault(player.getUniqueId(), Set.of());
        Map<String, Ilerleme> aktif = aktifGorevler.getOrDefault(player.getUniqueId(), Map.of());

        gui.setItem(0, etiket(Material.BOOK, ChatColor.GOLD + "Kalıcı Görevler", ChatColor.GRAY + "Tek seferlik görevler."));
        int slot = 1;
        for (GorevTanim g : SABIT_GOREVLER) gui.setItem(slot++, gorevOgesi(player, g, g.id, tamam, aktif));

        gui.setItem(9, etiket(Material.CLOCK, ChatColor.GOLD + "Günlük Görevler", ChatColor.GRAY + "Her gün yenilenir.", ChatColor.GRAY + "Sınırsız alabilirsin."));
        slot = 10;
        for (GorevTanim g : gununSecimi(Havuz.GUNLUK, simdi)) gui.setItem(slot++, gorevOgesi(player, g, ornekId(g, simdi), tamam, aktif));

        gui.setItem(18, etiket(Material.CLOCK, ChatColor.GOLD + "Haftalık Görevler", ChatColor.GRAY + "Her hafta yenilenir.", ChatColor.GRAY + "Daha büyük ödül."));
        slot = 19;
        for (GorevTanim g : gununSecimi(Havuz.HAFTALIK, simdi)) gui.setItem(slot++, gorevOgesi(player, g, ornekId(g, simdi), tamam, aktif));

        gui.setItem(27, etiket(Material.WRITABLE_BOOK, ChatColor.GOLD + "Zincir Görevler", ChatColor.GRAY + "Aşama bitince devamı açılır.", ChatColor.GRAY + "Son aşamada özel ödül."));
        slot = 28;
        Set<String> gosterilenZincir = new HashSet<>();
        for (GorevTanim g : ZINCIR_GOREVLER) {
            if (gosterilenZincir.contains(g.zincirAd) || tamam.contains(g.id)) continue;
            if (g.onceki != null && !tamam.contains(g.onceki)) continue;
            gosterilenZincir.add(g.zincirAd); // zincirin ŞU ANKİ aşaması
            gui.setItem(slot++, gorevOgesi(player, g, g.id, tamam, aktif));
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(GUI_BASLIK)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;
        String ornekId = clicked.getItemMeta().getPersistentDataContainer().get(gorevIdKey, PersistentDataType.STRING);
        if (ornekId == null) return;
        GorevTanim secilen = tanimBul(ornekId);
        if (secilen == null) return;
        if (!veriYuklendi) {
            player.sendMessage(ChatColor.RED + "Sistem henüz hazır değil, birazdan tekrar dene.");
            return;
        }
        long simdi = System.currentTimeMillis();
        // Günlük/haftalık: yalnızca ŞU ANKİ periyodun örneği kabul/teslim edilebilir
        if ((secilen.havuz == Havuz.GUNLUK || secilen.havuz == Havuz.HAFTALIK) && !ornekId.equals(ornekId(secilen, simdi))) {
            player.sendMessage(ChatColor.RED + "Bu görevin süresi doldu, panoyu yeniden aç.");
            player.closeInventory();
            return;
        }
        suresiDolanlariTemizle(player.getUniqueId());

        Set<String> tamam = tamamlananGorevler.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>());
        if (tamam.contains(ornekId)) {
            player.sendMessage(ChatColor.GRAY + "Bu görevi zaten tamamladın.");
            return;
        }
        if (secilen.havuz == Havuz.ZINCIR && secilen.onceki != null && !tamam.contains(secilen.onceki)) {
            player.sendMessage(ChatColor.RED + "Önce zincirin önceki aşamasını tamamlamalısın.");
            return;
        }

        Map<String, Ilerleme> aktif = aktifGorevler.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        if (!aktif.containsKey(ornekId)) {
            // GÖREV KABUL
            Ilerleme yeni = new Ilerleme();
            if (secilen.tur == GorevTuru.KAZAN) yeni.baslangicBakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
            if (secilen.sayacAd != null && plugin.basarimManager != null) yeni.baslangicSayac = plugin.basarimManager.sayacDegeri(player.getUniqueId(), secilen.sayacAd);
            if (secilen.havuz == Havuz.GUNLUK || secilen.havuz == Havuz.HAFTALIK) yeni.bitis = periyotBitis(secilen.havuz, periyot(secilen.havuz, simdi));
            aktif.put(ornekId, yeni);
            veriKaydet();
            player.sendMessage(ChatColor.GREEN + "Görev kabul edildi: " + secilen.ad);
            player.closeInventory();
            return;
        }

        // GÖREV TAMAMLAMA DENEMESİ
        denemeTamamla(player, secilen, ornekId, aktif.get(ornekId));
    }

    private void denemeTamamla(Player player, GorevTanim g, String ornekId, Ilerleme ilerleme) {
        boolean tamam = false;
        switch (g.tur) {
            case TOPLA: case TESLIM_ET: {
                int envanterdeki = sayEsya(player, g.hedefMateryal);
                if (envanterdeki >= g.hedefMiktar) {
                    esyaCikar(player, g.hedefMateryal, g.hedefMiktar);
                    tamam = true;
                } else {
                    player.sendMessage(ChatColor.RED + "Yeterli eşyan yok! (" + envanterdeki + "/" + g.hedefMiktar + " " + guzelAd(g.hedefMateryal) + ")");
                }
                break;
            }
            case OLDUR: {
                if (ilerleme.sayac >= g.hedefMiktar) tamam = true;
                else player.sendMessage(ChatColor.RED + "Henüz yeterli öldürme yapmadın! (" + ilerleme.sayac + "/" + g.hedefMiktar + ")");
                break;
            }
            case KAZAN: {
                double kazanc = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0) - ilerleme.baslangicBakiye;
                if (kazanc >= g.hedefKazanc) tamam = true;
                else player.sendMessage(ChatColor.RED + "Henüz hedefe ulaşmadın! (Şu ana kadarki kazanç: $" + fmt(Math.max(0, kazanc)) + "/$" + fmt(g.hedefKazanc) + ")");
                break;
            }
            case ZIYARET_ET: {
                if (ilerleme.sayac >= 1) tamam = true;
                else player.sendMessage(ChatColor.RED + "Henüz hedef biyoma gitmedin: " + g.hedefBiyom.replace('_', ' '));
                break;
            }
            case SATIS_YAP: case SOZLESME_TAMAMLA: case TURNUVAYA_KATIL: {
                long f = sayacFarki(player, g, ilerleme);
                if (f >= g.hedefMiktar) tamam = true;
                else player.sendMessage(ChatColor.RED + "Henüz yeterli ilerleme yok! (" + f + "/" + g.hedefMiktar + ")");
                break;
            }
            default:
                return;
        }
        if (!tamam) return;

        // Ödül - idempotent: aktiften silme + tamamlananlara ekleme tek senkron blokta (ana thread)
        Map<String, Ilerleme> aktif = aktifGorevler.get(player.getUniqueId());
        if (aktif != null) aktif.remove(ornekId);
        tamamlananGorevler.computeIfAbsent(player.getUniqueId(), k -> new HashSet<>()).add(ornekId);

        double bakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
        plugin.bankaHesaplari.put(player.getUniqueId(), bakiye + g.odul);
        plugin.veriKaydet();
        veriKaydet();
        if (g.xp > 0) player.giveExp(g.xp);
        if (g.odulEsya != null) {
            Map<Integer, ItemStack> tasan = player.getInventory().addItem(new ItemStack(g.odulEsya, g.odulEsyaAdet));
            for (ItemStack t : tasan.values()) player.getWorld().dropItemNaturally(player.getLocation(), t);
        }

        if (plugin.basarimManager != null) plugin.basarimManager.sayacArtir(player.getUniqueId(), "gorev", 1);
        plugin.ekonomiLog("gorev-tamamlandi", player.getName(), "Görev tamamlandı: " + ornekId + " (" + g.ad + ") - ödül $" + fmt(g.odul));

        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "✔ GÖREV TAMAMLANDI: " + ChatColor.YELLOW + g.ad);
        player.sendMessage(ChatColor.GREEN + "Ödül: $" + fmt(g.odul) + " hesabına yatırıldı."
                + (g.xp > 0 ? ChatColor.AQUA + " +" + g.xp + " XP" : "")
                + (g.odulEsya != null ? ChatColor.LIGHT_PURPLE + " +" + g.odulEsyaAdet + "x " + guzelAd(g.odulEsya) : ""));
        if (g.havuz == Havuz.ZINCIR) {
            boolean sonrakiVar = ZINCIR_GOREVLER.stream().anyMatch(z -> g.id.equals(z.onceki));
            player.sendMessage(sonrakiVar ? ChatColor.AQUA + "Zincirin sonraki aşaması panoda açıldı!" : ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "\"" + g.zincirAd + "\" zincirini tamamladın!");
        }
        player.sendMessage("");
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        } catch (Exception ignored) {
        }
        player.closeInventory();
    }

    private int sayEsya(Player player, Material mat) {
        int toplam = 0;
        for (ItemStack it : player.getInventory().getContents()) {
            if (it != null && it.getType() == mat) toplam += it.getAmount();
        }
        return toplam;
    }

    private void esyaCikar(Player player, Material mat, int miktar) {
        int kalan = miktar;
        ItemStack[] icerik = player.getInventory().getContents();
        for (int i = 0; i < icerik.length && kalan > 0; i++) {
            ItemStack it = icerik[i];
            if (it == null || it.getType() != mat) continue;
            int cikarilacak = Math.min(kalan, it.getAmount());
            it.setAmount(it.getAmount() - cikarilacak);
            kalan -= cikarilacak;
            if (it.getAmount() <= 0) player.getInventory().setItem(i, null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!veriYuklendi) return;
        LivingEntity olen = event.getEntity();
        Player katil = olen.getKiller();
        if (katil == null) return;

        Map<String, Ilerleme> aktif = aktifGorevler.get(katil.getUniqueId());
        if (aktif == null || aktif.isEmpty()) return;

        boolean degisti = false;
        for (Map.Entry<String, Ilerleme> e : aktif.entrySet()) {
            GorevTanim g = tanimBul(e.getKey());
            if (g == null || g.tur != GorevTuru.OLDUR) continue;
            if (g.hedefVarlik == olen.getType() && e.getValue().sayac < g.hedefMiktar) {
                e.getValue().sayac++;
                degisti = true;
                if (e.getValue().sayac >= g.hedefMiktar) {
                    katil.sendMessage(ChatColor.GREEN + "[Görev] \"" + g.ad + "\" hedefine ulaştın! Görev Panosu'na gidip teslim edebilirsin.");
                }
            }
        }
        if (degisti) veriKaydet();
    }

    /** ZIYARET_ET: aktif ziyaret görevi olan oyuncular için chunk değişiminde biyom kontrolü (ucuz: yoksa hemen döner). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHareket(org.bukkit.event.player.PlayerMoveEvent e) {
        if (!veriYuklendi || e.getTo() == null) return;
        if (e.getFrom().getBlockX() >> 3 == e.getTo().getBlockX() >> 3 && e.getFrom().getBlockZ() >> 3 == e.getTo().getBlockZ() >> 3) return;
        Map<String, Ilerleme> aktif = aktifGorevler.get(e.getPlayer().getUniqueId());
        if (aktif == null || aktif.isEmpty()) return;
        String biyom = null;
        boolean degisti = false;
        for (Map.Entry<String, Ilerleme> en : aktif.entrySet()) {
            GorevTanim g = tanimBul(en.getKey());
            if (g == null || g.tur != GorevTuru.ZIYARET_ET || en.getValue().sayac >= 1) continue;
            if (biyom == null) {
                org.bukkit.Location l = e.getTo();
                biyom = l.getWorld().getBiome(l.getBlockX(), Math.max(l.getBlockY(), l.getWorld().getMinHeight()), l.getBlockZ()).getKey().getKey();
            }
            if (biyom.equals(g.hedefBiyom)) {
                en.getValue().sayac = 1;
                degisti = true;
                e.getPlayer().sendMessage(ChatColor.GREEN + "[Görev] \"" + g.ad + "\" hedef biyoma ulaştın! Panoya dönüp teslim edebilirsin.");
            }
        }
        if (degisti) veriKaydet();
    }

    // ---- Veri güvenliği: standVeriYuklendi kalıbının aynısı ----

    public void veriKaydet() {
        if (!veriYuklendi) {
            plugin.getLogger().warning("[Gorev] veriKaydet() veriYukle() tamamlanmadan çağrıldı, atlandı (veri kaybını önlemek için).");
            return;
        }
        plugin.getConfig().set("gorevler_aktif", null);
        for (Map.Entry<UUID, Map<String, Ilerleme>> oyuncu : aktifGorevler.entrySet()) {
            if (oyuncu.getValue().isEmpty()) continue;
            for (Map.Entry<String, Ilerleme> giris : oyuncu.getValue().entrySet()) {
                String yol = "gorevler_aktif." + oyuncu.getKey() + "." + giris.getKey();
                plugin.getConfig().set(yol + ".sayac", giris.getValue().sayac);
                plugin.getConfig().set(yol + ".baslangicBakiye", giris.getValue().baslangicBakiye);
                plugin.getConfig().set(yol + ".baslangicSayac", giris.getValue().baslangicSayac);
                plugin.getConfig().set(yol + ".bitis", giris.getValue().bitis);
            }
        }
        long simdi = System.currentTimeMillis();
        for (Map.Entry<UUID, Set<String>> oyuncu : tamamlananGorevler.entrySet()) {
            // eski periyotların (günlük/haftalık) tamamlanma kayıtlarını buda
            List<String> liste = new ArrayList<>();
            for (String id : oyuncu.getValue()) {
                int i = id.indexOf('@');
                if (i >= 0) {
                    GorevTanim g = TANIM_INDEX.get(id.substring(0, i));
                    try { if (g != null && Long.parseLong(id.substring(i + 1)) < periyot(g.havuz, simdi) - 1) continue; } catch (NumberFormatException ignored) { }
                }
                liste.add(id);
            }
            plugin.getConfig().set("gorevler_tamamlanan." + oyuncu.getKey(), liste);
        }
        plugin.saveConfig();
    }

    public void veriYukle() {
        try {
            aktifGorevler.clear();
            tamamlananGorevler.clear();

            ConfigurationSection aktifSec = plugin.getConfig().getConfigurationSection("gorevler_aktif");
            if (aktifSec != null) {
                for (String uuidStr : aktifSec.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        ConfigurationSection oyuncuSec = aktifSec.getConfigurationSection(uuidStr);
                        Map<String, Ilerleme> map = new HashMap<>();
                        if (oyuncuSec != null) {
                            for (String gorevId : oyuncuSec.getKeys(false)) {
                                try {
                                    ConfigurationSection g = oyuncuSec.getConfigurationSection(gorevId);
                                    Ilerleme i = new Ilerleme();
                                    i.sayac = g.getInt("sayac", 0);
                                    i.baslangicBakiye = g.getDouble("baslangicBakiye", 0.0);
                                    i.baslangicSayac = g.getLong("baslangicSayac", 0L);
                                    i.bitis = g.getLong("bitis", 0L);
                                    map.put(gorevId, i);
                                } catch (Exception innerEx) {
                                    plugin.getLogger().warning("[Gorev] " + uuidStr + "/" + gorevId + " ilerlemesi okunamadı, atlandı: " + innerEx.getMessage());
                                }
                            }
                        }
                        aktifGorevler.put(uuid, map);
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Gorev] " + uuidStr + " için aktif görev verisi okunamadı, atlandı: " + innerEx.getMessage());
                    }
                }
            }

            ConfigurationSection tamamSec = plugin.getConfig().getConfigurationSection("gorevler_tamamlanan");
            if (tamamSec != null) {
                for (String uuidStr : tamamSec.getKeys(false)) {
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        List<String> liste = plugin.getConfig().getStringList("gorevler_tamamlanan." + uuidStr);
                        tamamlananGorevler.put(uuid, new HashSet<>(liste));
                    } catch (Exception innerEx) {
                        plugin.getLogger().warning("[Gorev] " + uuidStr + " için tamamlanan görev verisi okunamadı, atlandı: " + innerEx.getMessage());
                    }
                }
            }

            veriYuklendi = true;
        } catch (Exception e) {
            plugin.getLogger().severe("[Gorev] veriYukle() sırasında hata, veriYuklendi=false kalacak (kayıt atlanacak): " + e.getMessage());
            veriYuklendi = false;
        }
    }
}
