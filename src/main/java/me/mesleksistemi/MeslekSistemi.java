package me.mesleksistemi;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

// YENİ: Adliye paketindeki dosyalarımızı çağırıyoruz
import me.mesleksistemi.adliye.AdliyeManager;
import me.mesleksistemi.adliye.AdliyeCommands;
import me.mesleksistemi.adliye.AdliyeListener;
import me.mesleksistemi.meslekler.PolisManager;
import me.mesleksistemi.meslekler.SaglikManager;
import me.mesleksistemi.ekonomi.KiraManager;
import me.mesleksistemi.ekonomi.ToptanciManager;
import me.mesleksistemi.ekonomi.TicaretManager;
import me.mesleksistemi.sistem.EhliyetManager;
import me.mesleksistemi.sistem.KimlikListener;
import me.mesleksistemi.sistem.MenuManager;
import me.mesleksistemi.sistem.MeslekListener;
import me.mesleksistemi.sistem.SecimManager;
import me.mesleksistemi.sistem.SozlesmeManager;

public class MeslekSistemi extends JavaPlugin {

    public NamespacedKey economyValueKey, npcKey, bankaNpcKey, nufusNpcKey, tapuNpcKey, kimlikIdKey;
    public Location kasaKonumu = null, kursuKonumu = null, yasaKursuKonumu = null; 
    
    public HashMap<UUID, String> basvuruBekleyenler = new HashMap<>();
    public HashMap<UUID, Basvuru> aktifBasvurular = new HashMap<>();
    public HashMap<UUID, String> bankaIslemBekleyenler = new HashMap<>();
    public HashMap<UUID, Integer> kimlikAsama = new HashMap<>();
    public HashMap<UUID, GeciciKimlik> geciciKimlikler = new HashMap<>();
    public HashMap<String, Double> meslekFiyatlari = new HashMap<>();
    public HashMap<String, String> meslekGorunumAdlari = new HashMap<>();
    public HashMap<String, Double> maasMiktarlari = new HashMap<>();
    public HashMap<UUID, Double> bankaHesaplari = new HashMap<>();
    public HashMap<UUID, Long> sonHareketZamani = new HashMap<>();
    public HashMap<UUID, String> oyuncuMeslekCache = new HashMap<>(); 
    
    // TAPU VE EHLİYET VERİLERİ
    public HashSet<UUID> tapuSahipleri = new HashSet<>();
    public HashMap<UUID, String> tapuIslemBekleyenler = new HashMap<>();
    public double ilkTapuBlokFiyati = 300.0;
    public double genisletmeBlokFiyati = 1000.0;
    
    public HashSet<UUID> elytraEhliyetleri = new HashSet<>();
    public double ehliyetFiyati = 50000.0; 

    public boolean ohalAktif = false;
    public BossBar ohalBar;
    public boolean maaslarAcik = true;
    public ItemStack yasaKitabi = null;
    
    public long sonOhalBitisZamani = 0L;

    private MenuManager menuManager;
    
    // ENTEGRE YÖNETİCİLER (MANAGERS)
    public PolisManager polisManager;
    public AdliyeManager adliyeManager;
    public EhliyetManager ehliyetManager;
    
    // YENİ ENTEGRE YÖNETİCİLER (Erişilebilir yapıldı)
    public SaglikManager saglikManager;
    public TicaretManager ticaretManager;
    public ToptanciManager toptanciManager;
    public KiraManager kiraManager;
    private SecimManager secimManager;

    // Config kaydı: istekler birleştirilir, dosya arka planda tek thread ile sırayla yazılır
    private ExecutorService kayitYazici;
    private boolean kayitPlanlandi = false;
    private boolean kapaniyor = false;
    // Kapanışta tüm sistemler veriyi belleğe yazar, dosya en sonda bir kez yazılır
    private boolean kayitlarBiriktiriliyor = false;

    @Override
    public void onEnable() {
        this.kayitYazici = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MeslekSistemi-Kayit");
            t.setDaemon(true);
            return t;
        });

        if (Bukkit.getPluginManager().getPlugin("Economy") != null) {
            this.economyValueKey = new NamespacedKey(Bukkit.getPluginManager().getPlugin("Economy"), "value");
        } else {
            this.economyValueKey = new NamespacedKey(this, "value"); 
        }
        this.npcKey = new NamespacedKey(this, "meslek_npc");
        this.bankaNpcKey = new NamespacedKey(this, "banka_npc");
        this.nufusNpcKey = new NamespacedKey(this, "nufus_npc");
        this.tapuNpcKey = new NamespacedKey(this, "tapu_npc");
        this.kimlikIdKey = new NamespacedKey(this, "kimlik_no");

        // MESLEK FİYATLARI
        meslekFiyatlari.put("madenci", 300.0); meslekFiyatlari.put("doktor", 950.0);
        meslekFiyatlari.put("avukat", 300.0); meslekFiyatlari.put("polis", 600.0); 
        meslekFiyatlari.put("taseron", 150.0); meslekFiyatlari.put("hakim", 600.0); 
        meslekFiyatlari.put("belediyecalisani", 200.0); meslekFiyatlari.put("oduncu", 250.0);

        // MESLEK GÖRÜNÜM ADLARI
        meslekGorunumAdlari.put("madenci", "Madenci"); meslekGorunumAdlari.put("doktor", "Doktor");
        meslekGorunumAdlari.put("avukat", "Avukat"); meslekGorunumAdlari.put("polis", "Polis"); 
        meslekGorunumAdlari.put("taseron", "Taseron"); meslekGorunumAdlari.put("hakim", "Hakim"); 
        meslekGorunumAdlari.put("belediyecalisani", "Belediye Calisani");
        meslekGorunumAdlari.put("belediyebaskani", "Belediye Baskani"); 
        meslekGorunumAdlari.put("mahkum", "Mahkum"); meslekGorunumAdlari.put("oduncu", "Oduncu");
        
        // MAAŞ MİKTARLARI
        maasMiktarlari.put("vatandas", 50.0); maasMiktarlari.put("taseron", 80.0);
        maasMiktarlari.put("madenci", 100.0); maasMiktarlari.put("avukat", 150.0);
        maasMiktarlari.put("belediyecalisani", 150.0); maasMiktarlari.put("polis", 250.0); 
        maasMiktarlari.put("doktor", 300.0); maasMiktarlari.put("hakim", 400.0); 
        maasMiktarlari.put("belediyebaskani", 500.0); maasMiktarlari.put("oduncu", 100.0);

        ohalBar = Bukkit.createBossBar(
            ChatColor.DARK_RED + "" + ChatColor.BOLD + "DİKKAT: SOKAĞA ÇIKMA YASAĞI AKTİFTİR - HERKES EVLERİNE DÖNSÜN!", 
            BarColor.RED, 
            BarStyle.SOLID
        );
        
        this.menuManager = new MenuManager(this);
        getServer().getPluginManager().registerEvents(this.menuManager, this);
        getServer().getPluginManager().registerEvents(new KimlikListener(this), this);
        getServer().getPluginManager().registerEvents(new MeslekListener(this), this);

        // ENTEGRASYON 1: Sözleşme
        SozlesmeManager sozlesmeManager = new SozlesmeManager(this);
        getServer().getPluginManager().registerEvents(sozlesmeManager, this);
        if (getCommand("sozlesmeolustur") != null) getCommand("sozlesmeolustur").setExecutor(sozlesmeManager);
        if (getCommand("belediyesozlesmesi") != null) getCommand("belediyesozlesmesi").setExecutor(sozlesmeManager);
        if (getCommand("sozlesmekabul") != null) getCommand("sozlesmekabul").setExecutor(sozlesmeManager);
        if (getCommand("sozlesmereddet") != null) getCommand("sozlesmereddet").setExecutor(sozlesmeManager);
        if (getCommand("sozlesmebitir") != null) getCommand("sozlesmebitir").setExecutor(sozlesmeManager);
        if (getCommand("sozlesmeonayla") != null) getCommand("sozlesmeonayla").setExecutor(sozlesmeManager);

        // ENTEGRASYON 2: Seçim
        this.secimManager = new SecimManager(this);
        getServer().getPluginManager().registerEvents(secimManager, this);
        if (getCommand("secimoylama") != null) getCommand("secimoylama").setExecutor(secimManager);
        if (getCommand("adayol") != null) getCommand("adayol").setExecutor(secimManager);
        if (getCommand("pusulaal") != null) getCommand("pusulaal").setExecutor(secimManager);
        if (getCommand("secim") != null) getCommand("secim").setExecutor(secimManager);

        // ENTEGRASYON 3: PolisManager
        this.polisManager = new PolisManager(this);
        getServer().getPluginManager().registerEvents(this.polisManager, this);
        String[] polisCmds = {"hucreolustur", "hucresil", "copal", "serbestbirak", "amirkursu", "sikayetnpc", "sikayetkarar"};
        for (String c : polisCmds) {
            if (getCommand(c) != null) getCommand(c).setExecutor(this.polisManager);
        }

        // ENTEGRASYON 4: AdliyeManager (BÖLÜNMÜŞ HALİYLE KAYDEDİLİYOR)
        this.adliyeManager = new AdliyeManager(this);
        getServer().getPluginManager().registerEvents(new AdliyeListener(this, this.adliyeManager), this);
        
        AdliyeCommands adliyeCommandsExecutor = new AdliyeCommands(this, this.adliyeManager);
        String[] adliyeCmds = {"adliyenpc", "mahkemeayarla", "avukatkabul", "avukatred"}; 
        for (String c : adliyeCmds) {
            if (getCommand(c) != null) getCommand(c).setExecutor(adliyeCommandsExecutor);
        }

        // ENTEGRASYON 5: EhliyetManager
        this.ehliyetManager = new EhliyetManager(this);
        getServer().getPluginManager().registerEvents(this.ehliyetManager, this);
        if (getCommand("ehliyetnpc") != null) getCommand("ehliyetnpc").setExecutor(this.ehliyetManager);

        // YENİ ENTEGRASYON 6: TicaretManager (Orijinal oyuncu takas pazarı & Sadakat Sistemi)
        this.ticaretManager = new TicaretManager(this);
        getServer().getPluginManager().registerEvents(this.ticaretManager, this);
        if (getCommand("indirim") != null) getCommand("indirim").setExecutor(this.ticaretManager);

        // YENİ ENTEGRASYON 7: SaglikManager (Kanama, Kırık, Hastane ve Ambulans)
        this.saglikManager = new SaglikManager(this);
        getServer().getPluginManager().registerEvents(this.saglikManager, this);
        if (getCommand("doktormarket") != null) getCommand("doktormarket").setExecutor(this.saglikManager);
        if (getCommand("hastanenpc") != null) getCommand("hastanenpc").setExecutor(this.saglikManager);
        if (getCommand("ambulanscagir") != null) getCommand("ambulanscagir").setExecutor(this.saglikManager);
        if (getCommand("ambulanskabul") != null) getCommand("ambulanskabul").setExecutor(this.saglikManager);
        if (getCommand("hastaneyatak") != null) getCommand("hastaneyatak").setExecutor(this.saglikManager);

        // YENİ ENTEGRASYON 8: ToptanciManager (Madenci ve Oduncu Pazarı Sistemi)
        this.toptanciManager = new ToptanciManager(this);
        getServer().getPluginManager().registerEvents(this.toptanciManager, this);
        if (getCommand("madencinpc") != null) getCommand("madencinpc").setExecutor(this.toptanciManager);
        if (getCommand("oduncunpc") != null) getCommand("oduncunpc").setExecutor(this.toptanciManager);

        // ENTEGRASYON 9: KiraManager (Kiralık Evler & GriefPrevention3D Trust)
        this.kiraManager = new KiraManager(this);
        getServer().getPluginManager().registerEvents(this.kiraManager, this);
        if (getCommand("kirakur") != null) getCommand("kirakur").setExecutor(this.kiraManager);
        if (getCommand("kiranpc") != null) getCommand("kiranpc").setExecutor(this.kiraManager);
        if (getCommand("kiraver") != null) getCommand("kiraver").setExecutor(this.kiraManager);
        if (getCommand("kirasil") != null) getCommand("kirasil").setExecutor(this.kiraManager);
        getServer().getPluginManager().registerEvents(this.kiraManager.sozlesmeManager, this);
        if (getCommand("kirasozlesme") != null) getCommand("kirasozlesme").setExecutor(this.kiraManager.sozlesmeManager);
        if (getCommand("kira") != null) getCommand("kira").setExecutor(this.kiraManager.sozlesmeManager);

        veriYukle();
        ayarlariHazirla();

        Commands cmdExecutor = new Commands(this);
        String[] cmds = {
            "meslekata", "primver", "kasaayarla", "kursuayarla", "mesleknpc", "bankanpc", 
            "nufusnpc", "tapunpc", "kimlikbiyomsec", "meslekler", "basvurular", 
            "basvurucevapla", "tapumenusu", "ohal", "maaslar", "yasayayinla", "yasakursuayarla", "yasalar", "rehber"
        };
        for (String c : cmds) {
            if (getCommand(c) != null) getCommand(c).setExecutor(cmdExecutor);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::maasDagitimiYap, 36000L, 36000L);

        // Diğer eklentiler (Klan-Sistemi vb.) için bağlantı noktası
        getServer().getServicesManager().register(me.mesleksistemi.api.MeslekAPI.class,
                new me.mesleksistemi.api.MeslekAPIImpl(this), this, org.bukkit.plugin.ServicePriority.Normal);
    }

    @Override
    public void onDisable() { 
        if (ohalBar != null) ohalBar.removeAll();

        // Bekleyen arka plan yazımları bitsin ki eski içerik son kaydın üzerine yazılmasın
        kapaniyor = true;
        if (kayitYazici != null) {
            kayitYazici.shutdown();
            try {
                kayitYazici.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // YENİ: Kapanırken hastalıkları ve stokları kalıcı olarak kaydeder
        kayitlarBiriktiriliyor = true;
        if (this.saglikManager != null) this.saglikManager.veriKaydetSaglik();
        if (this.toptanciManager != null) this.toptanciManager.veriKaydetStok();
        if (this.secimManager != null) this.secimManager.onDisable();
        veriKaydet();
        kayitlarBiriktiriliyor = false;

        super.saveConfig(); // Tüm veriler tek seferde, senkron ve eksiksiz yazılır
    }

    // Tüm sistemler saveConfig() çağırır. Her çağrıda diske yazmak yerine aynı saniyedeki
    // istekler tek kayıtta birleştirilir; YAML ana thread'de üretilir, dosya arka planda yazılır.
    @Override
    public void saveConfig() {
        if (kayitlarBiriktiriliyor) return;
        if (kapaniyor || kayitYazici == null || !isEnabled()) {
            super.saveConfig();
            return;
        }
        if (kayitPlanlandi) return;
        kayitPlanlandi = true;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            kayitPlanlandi = false;
            String icerik = getConfig().saveToString();
            File dosya = new File(getDataFolder(), "config.yml");
            kayitYazici.execute(() -> dosyayaYaz(dosya, icerik));
        }, 20L);
    }

    // Önce geçici dosyaya yazıp sonra yerine taşır: yazım yarıda kalırsa config bozulmaz
    private void dosyayaYaz(File dosya, String icerik) {
        try {
            Files.createDirectories(dosya.getParentFile().toPath());
            Path gecici = new File(dosya.getParentFile(), "config.yml.tmp").toPath();
            Files.writeString(gecici, icerik, StandardCharsets.UTF_8);
            try {
                Files.move(gecici, dosya.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(gecici, dosya.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "config.yml kaydedilemedi!", e);
        }
    }

    // Oyuncunun yazdığı para miktarı. NaN/Infinity gibi değerler reddedilir (bakiye bozulmasın),
    // kuruş hassasiyetine yuvarlanır. Geçersizse NumberFormatException fırlatır.
    public static double parsePara(String metin) {
        double deger = Double.parseDouble(metin.trim());
        if (!Double.isFinite(deger)) throw new NumberFormatException("Geçersiz miktar: " + metin);
        return Math.round(deger * 100.0) / 100.0;
    }

    // "/essentials:home ev" -> "home". Eklenti önekiyle yazılan komutlar da yakalanır.
    public static String komutAdi(String mesaj) {
        String govde = mesaj.startsWith("/") ? mesaj.substring(1) : mesaj;
        String komut = govde.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        int onekSonu = komut.lastIndexOf(':');
        return onekSonu >= 0 ? komut.substring(onekSonu + 1) : komut;
    }

    // ------------------------------------------------------------------
    // AYARLAR (config.yml kökünde; eksikse varsayılanla eklenir)
    // ------------------------------------------------------------------
    public static final String AYAR_BASVURU_PRIM = "basvuru-prim";
    public static final String AYAR_BASVURU_PRIM_GUNLUK = "basvuru-prim-gunluk-limit";
    public static final String AYAR_SOZLESME_UST_LIMIT = "belediye-sozlesme-ust-limit";
    public static final String AYAR_SOZLESME_BEKLEME = "belediye-sozlesme-bekleme-dakika";

    private void ayarlariHazirla() {
        boolean degisti = false;
        Object[][] varsayilanlar = {
                {AYAR_BASVURU_PRIM, 30.0}, {AYAR_BASVURU_PRIM_GUNLUK, 20},
                {AYAR_SOZLESME_UST_LIMIT, 10000.0}, {AYAR_SOZLESME_BEKLEME, 60}};
        for (Object[] v : varsayilanlar) {
            if (!getConfig().contains((String) v[0])) { getConfig().set((String) v[0], v[1]); degisti = true; }
        }
        if (degisti) saveConfig();
        double prim = getConfig().getDouble(AYAR_BASVURU_PRIM);
        if (prim > basvuruPrimUstSiniri()) {
            getLogger().warning("[Prim] " + AYAR_BASVURU_PRIM + " (" + prim + ") en düşük başvuru ücretinin %20'sini ("
                    + basvuruPrimUstSiniri() + ") aşıyor; " + basvuruPrimUstSiniri() + " olarak uygulanacak.");
        }
    }

    // Config'ten pozitif, sonlu sayı okur; geçersizse varsayılan döner
    private double ayarSayi(String yol, double varsayilan) {
        double d = getConfig().getDouble(yol, varsayilan);
        return (Double.isFinite(d) && d >= 0) ? d : varsayilan;
    }

    /** Prim, en düşük meslek başvuru ücretinin %20'sini geçemez (başvuru açıp prim toplamak kâr etmesin). */
    public double basvuruPrimUstSiniri() {
        double enDusuk = meslekFiyatlari.values().stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
        return Math.round(enDusuk * 0.2 * 100.0) / 100.0;
    }

    public double basvuruPrimi() { return Math.min(ayarSayi(AYAR_BASVURU_PRIM, 30.0), basvuruPrimUstSiniri()); }
    public int basvuruPrimGunlukLimit() { return (int) ayarSayi(AYAR_BASVURU_PRIM_GUNLUK, 20); }
    public double belediyeSozlesmeUstLimit() { return ayarSayi(AYAR_SOZLESME_UST_LIMIT, 10000.0); }
    public long belediyeSozlesmeBeklemeMs() { return (long) ayarSayi(AYAR_SOZLESME_BEKLEME, 60) * 60_000L; }

    // ------------------------------------------------------------------
    // EKONOMİ LOG'U: plugins/MeslekSistemi/loglar/ekonomi-YYYY-AA.log
    // Dosya arka plandaki kayıt thread'inde yazılır (sunucu donmaz).
    // ------------------------------------------------------------------
    public void ekonomiLog(String kategori, String kim, String detay) {
        java.time.LocalDateTime simdi = java.time.LocalDateTime.now();
        String satir = "[" + simdi.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "] ["
                + kategori + "] " + kim + " | " + detay + System.lineSeparator();
        File dosya = new File(getDataFolder(), "loglar/ekonomi-" + simdi.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")) + ".log");
        Runnable yaz = () -> {
            try {
                Files.createDirectories(dosya.getParentFile().toPath());
                Files.writeString(dosya.toPath(), satir, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Ekonomi log'u yazılamadı!", e);
            }
        };
        if (kapaniyor || kayitYazici == null || kayitYazici.isShutdown()) yaz.run();
        else kayitYazici.execute(yaz);
    }

    /** "1 saat 5 dakika" / "12 dakika" / "40 saniye" */
    public static String sureYaz(long ms) {
        long sn = Math.max(0, (ms + 999) / 1000);
        long saat = sn / 3600, dk = (sn % 3600) / 60;
        if (saat > 0) return saat + " saat " + dk + " dakika";
        if (dk > 0) return dk + " dakika";
        return sn + " saniye";
    }

    public boolean hapisteMi(UUID oyuncu) {
        return polisManager != null && polisManager.jailedPlayers.containsKey(oyuncu);
    }
    
    public MenuManager getMenuManager() { return this.menuManager; }

    public ItemStack getRehberKitabi() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(ChatColor.GOLD + "Şehir Rehberi");
        meta.setAuthor(ChatColor.DARK_RED + "Devlet Yönetimi");

        // Bir sayfaya ~14 satır sığar; her konu kısa tutuldu
        meta.addPage(ChatColor.DARK_BLUE + ChatColor.BOLD.toString() + "HOŞ GELDİNİZ\n\n" +
                ChatColor.BLACK + "Şehre göçmen olarak geldiniz. " + ChatColor.DARK_RED + "Nüfus Müdürlüğü" + ChatColor.BLACK +
                "'nden kimlik çıkarın; devlet hesabınıza " + ChatColor.DARK_GREEN + "$1000" + ChatColor.BLACK + " yatırır.\n\n" +
                ChatColor.DARK_RED + "Göçmenler" + ChatColor.BLACK + " blok kıramaz, meslek ve banka hesabı edinemez.");
        meta.addPage(ChatColor.DARK_GREEN + ChatColor.BOLD.toString() + "ÖNEMLİ KOMUTLAR\n\n" +
                ChatColor.DARK_RED + "/yasalar " + ChatColor.BLACK + "Şehrin kuralları\n" +
                ChatColor.DARK_RED + "/rehber " + ChatColor.BLACK + "Bu kitap\n" +
                ChatColor.DARK_RED + "/sozlesmeolustur " + ChatColor.BLACK + "İş sözleşmesi\n" +
                ChatColor.DARK_RED + "/kira " + ChatColor.BLACK + "Kira sözleşmen\n" +
                ChatColor.DARK_RED + "/klan " + ChatColor.BLACK + "Klan sistemi\n" +
                ChatColor.DARK_RED + "/ambulanscagir " + ChatColor.BLACK + "Ağır yaralıyken\n" +
                ChatColor.DARK_RED + "/secimoylama " + ChatColor.BLACK + "Erken seçim");
        meta.addPage(ChatColor.DARK_AQUA + ChatColor.BOLD.toString() + "PARA VE BANKA\n\n" +
                ChatColor.BLACK + "Cebinizdeki kağıt para kaybolabilir; bankada güvendedir. Maaşlar bankaya yatar.\n\n" +
                ChatColor.DARK_RED + "Mesai: " + ChatColor.BLACK + "08:00-17:00 (oyun saati).\n\n" +
                "Bankadaki " + ChatColor.DARK_BLUE + "Borsa" + ChatColor.BLACK + "'da maden alıp satabilirsiniz.");
        meta.addPage(ChatColor.DARK_GREEN + ChatColor.BOLD.toString() + "MESLEKLER\n\n" +
                ChatColor.BLACK + "Meslek NPC'sinden başvurup ücreti ödeyin. Başkan onaylarsa mesleğiniz verilir.\n\n" +
                "Her 30 dakikada " + ChatColor.DARK_GREEN + "maaş" + ChatColor.BLACK + " alırsınız (AFK hariç).\n\n" +
                "İstifa da aynı menüden.");
        meta.addPage(ChatColor.GOLD + ChatColor.BOLD.toString() + "PAZARLAR\n\n" +
                ChatColor.DARK_BLUE + "Madenci/Oduncu Pazarı: " + ChatColor.BLACK + "Meslek sahipleri taş ve odun satar, herkes alabilir.\n\n" +
                ChatColor.DARK_BLUE + "Köylüler " + ChatColor.BLACK + "kağıt parayla satış yapar; sık alışverişte indirim verir.");
        meta.addPage(ChatColor.DARK_AQUA + ChatColor.BOLD.toString() + "ARSA VE KİRA\n\n" +
                ChatColor.BLACK + "Tapu Dairesi'nden arsa bloğu alıp altın kürekle çizin.\n\n" +
                "Ev kiralamak için Emlak Ofisi'ne başvurun. Evinizi " + ChatColor.DARK_RED + "/kiraver" + ChatColor.BLACK + " ile kiraya verebilirsiniz.");
        meta.addPage(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "BELEDİYE BAŞKANI\n\n" +
                ChatColor.BLACK + "Seçimle gelir:\n" +
                "• Başvuruları onaylar\n" +
                "• Yasa yayınlar\n" +
                "• OHAL ilan eder\n" +
                "• Maaşları dondurur\n" +
                "• Belediye adına iş sözleşmesi yapar");
        meta.addPage(ChatColor.DARK_PURPLE + ChatColor.BOLD.toString() + "SEÇİM\n\n" +
                ChatColor.DARK_RED + "/secimoylama" + ChatColor.BLACK + " ile erken seçim istenir; %51 evet gerekir.\n\n" +
                ChatColor.DARK_RED + "/adayol" + ChatColor.BLACK + " ($5000) ile aday olun, " + ChatColor.DARK_RED + "/pusulaal" + ChatColor.BLACK +
                " ile pusula alıp sandıkta oy verin.");
        meta.addPage(ChatColor.DARK_BLUE + ChatColor.BOLD.toString() + "POLİS VE HAPİS\n\n" +
                ChatColor.BLACK + "Suçları karakoldaki Şikayet NPC'sine bildirin.\n\n" +
                "Copla vurulan hapse girer, Mahkum olur. Hapiste çoğu komut kapalıdır; ceza bitince eski mesleğe dönülür.");
        meta.addPage(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "ADLİYE\n\n" +
                ChatColor.BLACK + "Adalet Sarayı'ndan dava açıp tazminat isteyebilirsiniz. Avukat tutabilirsiniz.\n\n" +
                "Hakim duruşmada tazminat ve hapis cezası verir. Duruşma bitmeden salondan ayrılamazsınız.");
        meta.addPage(ChatColor.DARK_RED + ChatColor.BOLD.toString() + "SAĞLIK\n\n" +
                ChatColor.BLACK + "Ölmezsiniz, " + ChatColor.DARK_RED + "ağır yaralanırsınız" + ChatColor.BLACK + ": 20 dakikada doktor gelmezse ölürsünüz.\n\n" +
                "Kanama için bandaj, kırık bacak için atel gerekir. " + ChatColor.DARK_RED + "/ambulanscagir" + ChatColor.BLACK +
                " ($1500). Doktor yoksa hastane NPC'si tedavi eder.");
        meta.addPage(ChatColor.DARK_GREEN + ChatColor.BOLD.toString() + "EHLİYET VE KLAN\n\n" +
                ChatColor.BLACK + "Elytra ile uçmak için Sivil Havacılık'tan " + ChatColor.DARK_RED + "uçuş ehliyeti" + ChatColor.BLACK + " ($50.000) almalısınız.\n\n" +
                "Bir klan kurmak ya da katılmak için " + ChatColor.DARK_RED + "/klan" + ChatColor.BLACK + " yazın. Klanlar savaşır, büyü ustalığı edinir.");
        meta.addPage(ChatColor.DARK_PURPLE + ChatColor.BOLD.toString() + "İLETİŞİM\n\n" +
                ChatColor.BLACK + "Topluluğumuza katılmak, şikayet bildirmek veya yetkili başvurusu yapmak için Discord sunucumuza gelmeyi unutmayın!\n\n" +
                ChatColor.BLUE + "https://discord.gg/invite/9GdXJ9q");

        book.setItemMeta(meta);
        return book;
    }

    // ------------------------------------------------------------------
    // BELEDİYE KASASI: tüm sistemler kasadaki parayı bu metotlarla okur/değiştirir.
    // Kasadaki kağıt paralar her zaman tek bir notta toplanır.
    // ------------------------------------------------------------------
    public Chest getKasa() {
        if (kasaKonumu == null || kasaKonumu.getWorld() == null) return null;
        org.bukkit.block.BlockState durum = kasaKonumu.getBlock().getState();
        return durum instanceof Chest ? (Chest) durum : null;
    }

    public double kasaBakiyesi(Chest kasa) {
        double toplam = 0.0;
        for (ItemStack item : kasa.getInventory().getContents()) {
            Double deger = getMoneyValue(item);
            if (deger != null) toplam += deger * item.getAmount();
        }
        return Math.round(toplam * 100.0) / 100.0;
    }

    // Kasadaki tüm notları silip yerine tek bir "yeniToplam" değerli not koyar.
    // Kasada hiç not yokken sandık tamamen doluysa yer açılamaz ve false döner (hiçbir şey değişmez).
    private boolean kasaNotunuAyarla(Chest kasa, double yeniToplam) {
        Inventory inv = kasa.getInventory();
        boolean notVar = false;
        for (ItemStack item : inv.getContents()) {
            if (getMoneyValue(item) != null) { notVar = true; break; }
        }
        yeniToplam = Math.round(yeniToplam * 100.0) / 100.0;
        if (yeniToplam > 0 && !notVar && inv.firstEmpty() == -1) return false;

        for (int i = 0; i < inv.getSize(); i++) {
            if (getMoneyValue(inv.getItem(i)) != null) inv.setItem(i, null);
        }
        if (yeniToplam > 0) inv.addItem(createEconomyNote(yeniToplam));
        return true;
    }

    // Kasaya para koyar. Kasa yoksa veya dolu ise false (para kaybolmasın diye işlem yapılmamalı).
    public boolean kasayaParaEkle(double miktar) {
        Chest kasa = getKasa();
        if (kasa == null) return false;
        return kasaNotunuAyarla(kasa, kasaBakiyesi(kasa) + miktar);
    }

    // Kasadan para çeker. Kasa yoksa veya bakiye yetmiyorsa false.
    public boolean kasadanParaCek(double miktar) {
        Chest kasa = getKasa();
        if (kasa == null) return false;
        double bakiye = kasaBakiyesi(kasa);
        if (bakiye < miktar) return false;
        return kasaNotunuAyarla(kasa, bakiye - miktar);
    }

    public void mergeKasaMoney(Chest kasa) {
        kasaNotunuAyarla(kasa, kasaBakiyesi(kasa));
    }

    // Oyuncunun üstündeki kağıt paradan tahsil edip kasaya aktarır.
    // Kasa kurulu değilse veya doluysa ödeme alınmaz (eskiden para boşa gidiyordu).
    public boolean processPaymentToKasa(Player player, double price) {
        Chest kasa = getKasa();
        if (kasa == null) {
            player.sendMessage(ChatColor.RED + "Belediye kasası aktif değil, ödeme alınamıyor! (Yetkililer /kasaayarla yapmalı)");
            return false;
        }
        double totalMoney = 0.0;
        PlayerInventory inventory = player.getInventory();
        for (ItemStack item : inventory.getContents()) {
            Double itemValue = getMoneyValue(item);
            if (itemValue != null) totalMoney += itemValue * item.getAmount();
        }
        if (totalMoney < price) { player.sendMessage(ChatColor.RED + "Yeterli fiziksel paran yok! Gereken: $" + price); return false; }
        if (!kasaNotunuAyarla(kasa, kasaBakiyesi(kasa) + price)) {
            player.sendMessage(ChatColor.RED + "Belediye kasası tamamen dolu, ödeme alınamıyor! Yetkililere bildirin.");
            return false;
        }
        oyuncuParasiniDuzenle(player, totalMoney - price);
        return true;
    }

    // Oyuncunun tüm kağıt paralarını silip yerine "kalan" değerli tek not verir
    private void oyuncuParasiniDuzenle(Player player, double kalan) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (getMoneyValue(inventory.getItem(i)) != null) inventory.setItem(i, null);
        }
        double remaining = Math.round(kalan * 100.0) / 100.0;
        if (remaining > 0) {
            for (ItemStack artan : inventory.addItem(createEconomyNote(remaining)).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), artan);
            }
        }
        player.updateInventory();
    }

    public boolean processBankDeposit(Player player, double amount) {
        double totalMoney = 0.0;
        for (ItemStack item : player.getInventory().getContents()) {
            Double itemValue = getMoneyValue(item);
            if (itemValue != null) totalMoney += itemValue * item.getAmount();
        }
        if (totalMoney < amount) {
            player.sendMessage(ChatColor.RED + "Uzerinizde yeterli fiziksel para yok!"); return false;
        }
        oyuncuParasiniDuzenle(player, totalMoney - amount);
        return true;
    }

    public Double getMoneyValue(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) return null;
        PersistentDataContainer data = item.getItemMeta().getPersistentDataContainer();
        if (economyValueKey != null && data.has(economyValueKey, PersistentDataType.DOUBLE)) {
            Double deger = data.get(economyValueKey, PersistentDataType.DOUBLE);
            return (deger != null && Double.isFinite(deger)) ? deger : null;
        }
        return null;
    }

    public ItemStack createEconomyNote(double amount) {
        ItemStack note = new ItemStack(Material.PAPER); ItemMeta meta = note.getItemMeta();
        String formatted = amount == Math.floor(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
        meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "$" + formatted);
        meta.setLore(Collections.singletonList(ChatColor.GRAY + "Paper money"));
        meta.getPersistentDataContainer().set(economyValueKey, PersistentDataType.DOUBLE, amount);
        note.setItemMeta(meta); return note;
    }

    public Material getBlockMaterial(Material ingot) {
        switch (ingot) {
            case DIAMOND: return Material.DIAMOND_BLOCK; case EMERALD: return Material.EMERALD_BLOCK;
            case GOLD_INGOT: return Material.GOLD_BLOCK; case IRON_INGOT: return Material.IRON_BLOCK;
            case LAPIS_LAZULI: return Material.LAPIS_BLOCK; case COPPER_INGOT: return Material.COPPER_BLOCK;
            case COAL: return Material.COAL_BLOCK;
            default: return null;
        }
    }

    public int getChestStock(Inventory inv, Material base, Material block) {
        int total = 0;
        for (ItemStack item : inv.getContents()) {
            if (item != null) {
                if (item.getType() == base) total += item.getAmount();
                else if (item.getType() == block) total += (item.getAmount() * 9);
            }
        }
        return total;
    }

    public void setChestStock(Inventory inv, Material base, Material block, int totalAmount) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && (item.getType() == base || item.getType() == block)) inv.setItem(i, null);
        }
        if (totalAmount <= 0) return;
        int newBlocks = totalAmount / 9; int newBases = totalAmount % 9;
        if (newBlocks > 0) inv.addItem(new ItemStack(block, newBlocks));
        if (newBases > 0) inv.addItem(new ItemStack(base, newBases));
    }

    // Kasada stok "base + blok" olarak tutulur. Yeni toplamın sandığa sığıp sığmadığını hesaplar.
    public boolean stokSigarMi(Inventory inv, Material base, Material block, int yeniToplam) {
        int kullanilabilir = 0;
        for (ItemStack item : inv.getStorageContents()) {
            if (item == null || item.getType() == Material.AIR || item.getType() == base || item.getType() == block) kullanilabilir++;
        }
        int bloklar = yeniToplam / 9;
        int tekler = yeniToplam % 9;
        int gerekenSlot = (bloklar + block.getMaxStackSize() - 1) / block.getMaxStackSize() + (tekler > 0 ? 1 : 0);
        return gerekenSlot <= kullanilabilir;
    }

    public void removeItemFromInventory(Inventory inv, Material mat, int amount) {
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() == mat) {
                if (item.getAmount() > amount) { item.setAmount(item.getAmount() - amount); break; } 
                else if (item.getAmount() == amount) { inv.setItem(i, null); break; } 
                else { amount -= item.getAmount(); inv.setItem(i, null); }
            }
        }
    }

    private void maasDagitimiYap() {
        if (!maaslarAcik) {
            Bukkit.broadcastMessage("");
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Belediye Başkanlığı] " + ChatColor.RED + "Şehrimizdeki tasarruf tedbirleri kapsamında memur maaş ödemeleri geçici bir süreliğine ASKIYA ALINMIŞTIR.");
            Bukkit.broadcastMessage("");
            return;
        }

        Chest kasa = getKasa();
        if (kasa == null) {
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Belediye] SISTEM HATASI: Belediye Kasasi bulunamadigi icin maaslar odenemedi!"); return;
        }
        double odenecekToplamMaas = 0.0;
        List<Player> maasAlacaklar = new ArrayList<>();
        long simdi = System.currentTimeMillis();

        for (Player p : Bukkit.getOnlinePlayers()) {
            long sonHareket = sonHareketZamani.getOrDefault(p.getUniqueId(), 0L);
            if (simdi - sonHareket > 300000L) { p.sendMessage(ChatColor.RED + "[Banka] AFK oldugunuz icin bu donemki maasinizi alamadiniz."); continue; }
            String meslekID = oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas");
            
            if (meslekID.equalsIgnoreCase("gocmen") || meslekID.equalsIgnoreCase("mahkum") || meslekID.equalsIgnoreCase("default")) continue;
            
            odenecekToplamMaas += maasMiktarlari.getOrDefault(meslekID.toLowerCase(Locale.ROOT), 50.0);
            maasAlacaklar.add(p);
        }

        if (maasAlacaklar.isEmpty()) return;
        if (kasadanParaCek(odenecekToplamMaas)) {
            for (Player p : maasAlacaklar) {
                String meslekID = oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas");
                double maas = maasMiktarlari.getOrDefault(meslekID.toLowerCase(Locale.ROOT), 50.0);
                double mevcutBakiye = bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
                bankaHesaplari.put(p.getUniqueId(), mevcutBakiye + maas);
                p.sendMessage(ChatColor.GREEN + "[Banka] Maasiniz ($" + maas + ") dijital hesabiniza yatirildi.");
            }
            veriKaydet();
        } else { Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "BELEDIYE IFLAS ETTI! Memur maaşları ödenemedi."); }
    }

    public void veriKaydet() {
        if (kasaKonumu != null) {
            getConfig().set("kasa.world", kasaKonumu.getWorld().getName()); getConfig().set("kasa.x", kasaKonumu.getX());
            getConfig().set("kasa.y", kasaKonumu.getY()); getConfig().set("kasa.z", kasaKonumu.getZ());
        }
        if (kursuKonumu != null) {
            getConfig().set("kursu.world", kursuKonumu.getWorld().getName()); getConfig().set("kursu.x", kursuKonumu.getX());
            getConfig().set("kursu.y", kursuKonumu.getY()); getConfig().set("kursu.z", kursuKonumu.getZ());
        }
        if (yasaKursuKonumu != null) {
            getConfig().set("yasakursu.world", yasaKursuKonumu.getWorld().getName()); getConfig().set("yasakursu.x", yasaKursuKonumu.getX());
            getConfig().set("yasakursu.y", yasaKursuKonumu.getY()); getConfig().set("yasakursu.z", yasaKursuKonumu.getZ());
        }
        
        getConfig().set("maaslarAcik", maaslarAcik);
        getConfig().set("yasaKitabi", yasaKitabi);
        getConfig().set("sonOhalBitisZamani", sonOhalBitisZamani);

        getConfig().set("basvurular", null); 
        for (Map.Entry<UUID, Basvuru> entry : aktifBasvurular.entrySet()) {
            String id = entry.getKey().toString();
            getConfig().set("basvurular." + id + ".isim", entry.getValue().oyuncuAdi);
            getConfig().set("basvurular." + id + ".meslek", entry.getValue().meslek);
            getConfig().set("basvurular." + id + ".sebep", entry.getValue().sebep);
        }
        getConfig().set("banka", null);
        for (Map.Entry<UUID, Double> entry : bankaHesaplari.entrySet()) { getConfig().set("banka." + entry.getKey().toString(), entry.getValue()); }
        getConfig().set("meslek_cache", null);
        for (Map.Entry<UUID, String> entry : oyuncuMeslekCache.entrySet()) { getConfig().set("meslek_cache." + entry.getKey().toString(), entry.getValue()); }
        
        List<String> tapuList = new ArrayList<>();
        for (UUID id : tapuSahipleri) { tapuList.add(id.toString()); }
        getConfig().set("tapu_sahipleri", tapuList);

        List<String> ehliyetList = new ArrayList<>();
        for (UUID id : elytraEhliyetleri) { ehliyetList.add(id.toString()); }
        getConfig().set("elytra_ehliyetleri", ehliyetList);

        if (this.polisManager != null) this.polisManager.veriKaydet(); 
        if (this.adliyeManager != null) this.adliyeManager.veriKaydetAdliye();

        saveConfig();
    }

    private void veriYukle() {
        if (!getDataFolder().exists()) getDataFolder().mkdir();
        if (getConfig().contains("kasa.world")) {
            org.bukkit.World world = Bukkit.getWorld(getConfig().getString("kasa.world"));
            if (world != null) kasaKonumu = new Location(world, getConfig().getDouble("kasa.x"), getConfig().getDouble("kasa.y"), getConfig().getDouble("kasa.z"));
        }
        if (getConfig().contains("kursu.world")) {
            org.bukkit.World world = Bukkit.getWorld(getConfig().getString("kursu.world"));
            if (world != null) kursuKonumu = new Location(world, getConfig().getDouble("kursu.x"), getConfig().getDouble("kursu.y"), getConfig().getDouble("kursu.z"));
        }
        if (getConfig().contains("yasakursu.world")) {
            org.bukkit.World world = Bukkit.getWorld(getConfig().getString("yasakursu.world"));
            if (world != null) yasaKursuKonumu = new Location(world, getConfig().getDouble("yasakursu.x"), getConfig().getDouble("yasakursu.y"), getConfig().getDouble("yasakursu.z"));
        }
        
        maaslarAcik = getConfig().getBoolean("maaslarAcik", true);
        yasaKitabi = getConfig().getItemStack("yasaKitabi");
        sonOhalBitisZamani = getConfig().getLong("sonOhalBitisZamani", 0L);

        if (getConfig().contains("basvurular")) {
            for (String uuidStr : getConfig().getConfigurationSection("basvurular").getKeys(false)) {
                try { aktifBasvurular.put(UUID.fromString(uuidStr), new Basvuru(getConfig().getString("basvurular." + uuidStr + ".isim"), getConfig().getString("basvurular." + uuidStr + ".meslek"), getConfig().getString("basvurular." + uuidStr + ".sebep"))); } catch (Exception e) {}
            }
        }
        if (getConfig().contains("banka")) {
            for (String uuidStr : getConfig().getConfigurationSection("banka").getKeys(false)) {
                try {
                    double bakiye = getConfig().getDouble("banka." + uuidStr);
                    if (!Double.isFinite(bakiye)) {
                        getLogger().warning("[Banka] " + uuidStr + " hesabında geçersiz bakiye (" + bakiye + ") vardı, 0 yapıldı.");
                        bakiye = 0.0;
                    }
                    bankaHesaplari.put(UUID.fromString(uuidStr), bakiye);
                } catch (Exception e) {}
            }
        }
        if (getConfig().contains("meslek_cache")) {
            for (String uuidStr : getConfig().getConfigurationSection("meslek_cache").getKeys(false)) {
                try {
                    String meslek = getConfig().getString("meslek_cache." + uuidStr);
                    if (meslek != null) oyuncuMeslekCache.put(UUID.fromString(uuidStr), meslek.toLowerCase(Locale.ROOT));
                } catch (Exception e) {}
            }
        }
        if (getConfig().contains("tapu_sahipleri")) {
            List<String> tapuList = getConfig().getStringList("tapu_sahipleri");
            for (String id : tapuList) {
                try { tapuSahipleri.add(UUID.fromString(id)); } catch (Exception e) {}
            }
        }

        if (getConfig().contains("elytra_ehliyetleri")) {
            List<String> ehliyetList = getConfig().getStringList("elytra_ehliyetleri");
            for (String id : ehliyetList) {
                try { elytraEhliyetleri.add(UUID.fromString(id)); } catch (Exception e) {}
            }
        }
        
        if (this.polisManager != null) this.polisManager.veriYukle(); 
        if (this.adliyeManager != null) this.adliyeManager.veriYukleAdliye();
    }

    public static class Basvuru {
        public String oyuncuAdi, meslek, sebep;
        public Basvuru(String oyuncuAdi, String meslek, String sebep) { this.oyuncuAdi = oyuncuAdi; this.meslek = meslek; this.sebep = sebep; }
    }
    public static class GeciciKimlik {
        public String isim, soyisim, kutuk; public int yas;
    }
}