package me.mesleksistemi.ekonomi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import me.mesleksistemi.MeslekSistemi;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.ClaimPermission;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

public class KiraManager implements Listener, CommandExecutor {

    private final MeslekSistemi plugin;
    public final HashMap<String, KiralikEv> evler = new HashMap<>();
    private final NamespacedKey npcKey;
    private final NamespacedKey evIdKey;

    // Başvuru, sözleşme kitabı ve /kira akışı
    public final KiraSozlesmeManager sozlesmeManager;

    private static final String MENU_BASLIGI = ChatColor.DARK_GREEN + "Kiralık Evler";
    private static final int MENU_BOYUTU = 54;

    // 10 Saat = 36.000.000 Milisaniye (kiracının oyunda geçirdiği süre)
    static final long KIRA_SURESI_MS = 36000000L;

    // Kiracı bu kadar süre (gerçek zaman) oyuna girmezse sözleşme kendiliğinden biter
    static final long TERK_SURESI_MS = 7L * 24 * 60 * 60 * 1000;

    // Kira süresi sayacının en son işlendiği an
    private long sonSayacZamani = System.currentTimeMillis();

    public KiraManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "emlakci_npc");
        this.evIdKey = new NamespacedKey(plugin, "ev_id");
        veriYukle();
        this.sozlesmeManager = new KiraSozlesmeManager(plugin, this);

        // Her dakika: kiracı oyundaysa süresi düşer, süre bitince kira yenilenir ya da haciz gelir
        Bukkit.getScheduler().runTaskTimer(plugin, this::kiraZamanlariniKontrolEt, 1200L, 1200L);
    }

    // GP3D: 3D alt claim'leri de doğru bulmak için yükseklik dikkate alınır (ignoreHeight = false).
    // Dünya yüklü değilse GP'ye null dünyalı konum göndermiyoruz.
    Claim claimBul(KiralikEv ev) {
        if (ev.loc == null || ev.loc.getWorld() == null) return null;
        return GriefPrevention.instance.dataStore.getClaimAt(ev.loc, false, null);
    }

    // Evi kiraya veren taraf mı? Oyuncu evi: sahibi. Belediye evi: başkan.
    boolean evYoneticisiMi(Player p, KiralikEv ev) {
        return ev.sahip == null ? p.hasPermission("meslek.baskan") : ev.sahip.equals(p.getUniqueId());
    }

    // Ev sahibine (belediye evinde çevrimiçi başkanlara) mesaj gönderir. Kimse yoksa false döner.
    boolean evYoneticilerineGonder(KiralikEv ev, java.util.function.Consumer<Player> gonder) {
        boolean ulasti = false;
        if (ev.sahip != null) {
            Player sahip = Bukkit.getPlayer(ev.sahip);
            if (sahip != null) { gonder.accept(sahip); ulasti = true; }
        } else {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("meslek.baskan")) { gonder.accept(online); ulasti = true; }
            }
        }
        return ulasti;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;
        String cmd = command.getName().toLowerCase();

        // Belediye evi: kira belediye kasasına gider
        if (cmd.equals("kirakur")) {
            if (!p.hasPermission("meslek.baskan")) {
                p.sendMessage(ChatColor.RED + "Belediye evlerini sadece Belediye Başkanı kiraya verebilir!");
                return true;
            }
            evKur(p, args, null, "/kirakur");
            return true;
        }

        // Oyuncu evi/odası: kira ev sahibinin banka hesabına gider
        if (cmd.equals("kiraver")) {
            if (!p.hasPermission("kira.kiraver")) return true;
            evKur(p, args, p.getUniqueId(), "/kiraver");
            return true;
        }

        if (cmd.equals("kirasil")) {
            evSil(p, args);
            return true;
        }

        if (cmd.equals("kiranpc")) {
            if (!p.hasPermission("kira.admin")) return true;
            Villager npc = (Villager) p.getWorld().spawnEntity(p.getLocation(), EntityType.VILLAGER);
            npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
            npc.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Emlak Ofisi");
            npc.setCustomNameVisible(true);
            npc.setProfession(Villager.Profession.CARTOGRAPHER);
            npc.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
            p.sendMessage(ChatColor.GREEN + "Emlak NPC'si kuruldu!");
            return true;
        }
        return false;
    }

    // sahip == null ise belediye evi, değilse o oyuncunun kiraya verdiği ev/oda
    private void evKur(Player p, String[] args, UUID sahip, String komut) {
        if (args.length < 3) {
            p.sendMessage(ChatColor.RED + "Kullanım: " + komut + " <id> <fiyat> <Ekranda Görünecek İsim>");
            return;
        }

        String id = args[0].toLowerCase();
        double fiyat;
        try { fiyat = MeslekSistemi.parsePara(args[1]); } catch (Exception e) { p.sendMessage(ChatColor.RED + "Fiyat rakam olmalıdır!"); return; }
        if (fiyat <= 0 || Double.isNaN(fiyat) || Double.isInfinite(fiyat)) {
            p.sendMessage(ChatColor.RED + "Fiyat sıfırdan büyük olmalıdır!");
            return;
        }

        StringBuilder isim = new StringBuilder();
        for (int i = 2; i < args.length; i++) isim.append(args[i]).append(" ");

        KiralikEv yeniEv = new KiralikEv(id, isim.toString().trim(), p.getLocation(), fiyat);
        yeniEv.sahip = sahip;

        // GP3D: Kurulum anında evin bir claim/alt claim içinde olduğunu doğrula
        Claim claim = claimBul(yeniEv);
        if (claim == null) {
            p.sendMessage(ChatColor.RED + "Bulunduğunuz yerde bir Claim/Subclaim yok! Önce evi claim'leyip içinde durun.");
            return;
        }
        // Oyuncu sadece kendi claim'ini (veya kendi claim'indeki odayı) kiraya verebilir
        if (sahip != null && !sahip.equals(claim.getOwnerID())) {
            p.sendMessage(ChatColor.RED + "Sadece size ait bir claim'i veya odayı kiraya verebilirsiniz!");
            return;
        }

        KiralikEv eskiEv = evler.get(id);
        if (eskiEv != null) {
            // Oyuncu başkasının (veya belediyenin) ilanının üzerine yazamaz
            if (sahip != null && !sahip.equals(eskiEv.sahip)) {
                p.sendMessage(ChatColor.RED + "'" + id + "' kimliği başka bir ev için kullanılıyor, farklı bir id seçin.");
                return;
            }
            // Aynı id ile tekrar kurulursa mevcut kiracı ve süresi korunur
            if (eskiEv.kiraci != null) {
                yeniEv.kiraci = eskiEv.kiraci;
                yeniEv.kalanSureMs = eskiEv.kalanSureMs;
            }
        }

        evler.put(id, yeniEv);
        // Fiyat/konum değişmiş olabilir; bu ev için hazırlanmış eski sözleşmeler geçersiz
        sozlesmeManager.evSozlesmeleriniIptalEt(id);
        veriKaydet();
        p.sendMessage(ChatColor.GREEN + "Ev başarıyla kiralık listesine eklendi! Koordinat bulunduğunuz yer olarak kaydedildi.");
        p.sendMessage(ChatColor.GRAY + "Claim ID: " + claim.getID() + (claim.parent != null ? " (alt claim)" : "") + (claim.is3D() ? " [3D]" : ""));
        p.sendMessage(ChatColor.GRAY + "Kira geliri: " + (sahip == null ? "Belediye Kasası" : "Banka hesabınız"));
    }

    // Oyuncu kendi ilanını, başkan belediye ilanlarını, kira.admin hepsini silebilir.
    // Kirada olan ev hemen silinmez: kiracının ödediği süre bitince kapanır.
    private void evSil(Player p, String[] args) {
        if (args.length < 1) {
            p.sendMessage(ChatColor.RED + "Kullanım: /kirasil <id>");
            return;
        }
        String id = args[0].toLowerCase();
        KiralikEv ev = evler.get(id);
        if (ev == null) {
            p.sendMessage(ChatColor.RED + "'" + id + "' kimliğiyle kiralık bir ev bulunamadı.");
            return;
        }

        if (!p.hasPermission("kira.admin") && !evYoneticisiMi(p, ev)) {
            p.sendMessage(ChatColor.RED + "Bu ilanı kaldırma yetkiniz yok!");
            return;
        }

        if (ev.kiraci == null) {
            evler.remove(id);
            sozlesmeManager.evSozlesmeleriniIptalEt(id);
            veriKaydet();
            p.sendMessage(ChatColor.GREEN + ev.isim + " kiralık listesinden kaldırıldı.");
            return;
        }

        if (ev.kaldirilacak) {
            p.sendMessage(ChatColor.YELLOW + "Bu ev zaten kira süresi bitince kapanacak şekilde işaretli.");
            return;
        }
        ev.kaldirilacak = true;
        veriKaydet();
        String kalan = sureMetni(ev.kalanSureMs);
        p.sendMessage(ChatColor.YELLOW + ev.isim + " şu an kirada. Kiracının ödediği süre bitince (oyunda " + kalan
                + ") kira yenilenmeyecek ve ilan kaldırılacak.");
        Player kiraci = Bukkit.getPlayer(ev.kiraci);
        if (kiraci != null) {
            kiraci.sendMessage(ChatColor.GOLD + "[Emlak] " + ev.isim + " evinin sahibi kirayı sonlandırıyor. Ödediğiniz süre bitince (oyunda "
                    + kalan + ") evden yetkileriniz kaldırılacak.");
        }
    }

    // Sözleşme kabul edilince çağrılır. Sorun varsa hata mesajı, başarılıysa null döner.
    String kiraBaslat(KiralikEv ev, Player kiraci, double fiyat) {
        if (!evler.containsKey(ev.id)) return "Bu ev artık kiralık listesinde değil.";
        if (ev.kiraci != null) return "Bu ev artık dolu.";
        if (ev.fiyat != fiyat) return "Evin kira bedeli değişmiş, yeni bir sözleşme gerekiyor.";

        UUID pId = kiraci.getUniqueId();
        Claim claim = claimBul(ev);
        if (claim == null) return "Evin bulunduğu yerde aktif bir Claim/Subclaim bulunamadı! Lütfen yetkililere bildirin.";
        if (pId.equals(claim.getOwnerID()) || pId.equals(ev.sahip)) return "Bu ev zaten size ait, kiralayamazsınız.";

        double bakiye = plugin.bankaHesaplari.getOrDefault(pId, 0.0);
        if (bakiye < fiyat) return "Banka hesabınızda yeterli bakiye yok! Gereken: $" + fiyat;

        // Önce para alıcısına ulaştırılır (kasa yoksa işlem yapılmaz), sonra kiracıdan düşülür
        if (!kiraOdemesiAktar(ev, fiyat)) return "Belediye kasası şu an kira kabul edemiyor. Lütfen yetkililere bildirin.";
        plugin.bankaHesaplari.put(pId, bakiye - fiyat);

        ev.kiraci = pId;
        ev.kalanSureMs = KIRA_SURESI_MS;
        ev.kaldirilacak = false;

        claim.setPermission(pId.toString(), ClaimPermission.Build);
        GriefPrevention.instance.dataStore.saveClaim(claim);
        veriKaydet();
        return null;
    }

    // Kiracı kendi isteğiyle çıkar (/kira). Ödenen süre iade edilmez.
    boolean kiracilikBitir(KiralikEv ev) {
        if (ev.kiraci == null) return false;
        if (!kiraciYetkisiniKaldir(ev)) return false;
        ev.kiraci = null;
        ev.kalanSureMs = 0L;
        if (ev.kaldirilacak) evler.remove(ev.id); // Sahibi zaten kaldırmak istiyordu
        veriKaydet();
        return true;
    }

    // Kiracının GP3D yetkilerini kaldırır. Dünya yüklü değilse false döner, sonra tekrar denenmeli.
    private boolean kiraciYetkisiniKaldir(KiralikEv ev) {
        if (ev.loc.getWorld() == null) return false;
        Claim claim = claimBul(ev);
        if (claim != null) {
            String kiraciId = ev.kiraci.toString();
            claim.dropPermission(kiraciId);
            claim.dropManager(kiraciId);
            GriefPrevention.instance.dataStore.saveClaim(claim);
        } else {
            plugin.getLogger().warning("[Kira] '" + ev.id + "' evinin claim'i bulunamadı, kiracının GP yetkisi elle kaldırılmalı.");
        }
        return true;
    }

    String sahipAdi(KiralikEv ev) {
        if (ev.sahip == null) return "Belediye";
        OfflinePlayer op = Bukkit.getOfflinePlayer(ev.sahip);
        return op.getName() != null ? op.getName() : "Bilinmiyor";
    }

    static String sureMetni(long ms) {
        long dakika = Math.max(0, ms) / 60000L;
        if (dakika < 60) return Math.max(1, dakika) + " dakika";
        return (dakika / 60) + " saat " + (dakika % 60) + " dakika";
    }

    // Ödenen kirayı alıcısına aktarır: oyuncu evi ise sahibinin bankasına, belediye evi ise kasa sandığına.
    // Belediye kasası kurulu değilse veya doluysa false döner; bu durumda kiracıdan para alınmamalı.
    private boolean kiraOdemesiAktar(KiralikEv ev, double miktar) {
        if (ev.sahip != null) {
            plugin.bankaHesaplari.put(ev.sahip, plugin.bankaHesaplari.getOrDefault(ev.sahip, 0.0) + miktar);
            Player sahipOyuncu = Bukkit.getPlayer(ev.sahip);
            if (sahipOyuncu != null) {
                sahipOyuncu.sendMessage(ChatColor.GREEN + "[Emlak] " + ev.isim + " için $" + miktar + " kira geliri banka hesabınıza yatırıldı.");
            }
            return true;
        }
        if (plugin.kasayaParaEkle(miktar)) return true;
        plugin.getLogger().warning("[Kira] Belediye kasası kurulu değil veya dolu, '" + ev.id + "' evinin $" + miktar + " kirası tahsil edilemedi!");
        return false;
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        // Sağ tık iki kez (iki el için) tetiklenmesin
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getRightClicked() instanceof Villager) {
            Villager npc = (Villager) event.getRightClicked();
            if (npc.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                openEmlakMenu(event.getPlayer());
            }
        }
    }

    private void openEmlakMenu(Player p) {
        Inventory inv = Bukkit.createInventory(null, MENU_BOYUTU, MENU_BASLIGI);

        for (KiralikEv ev : evler.values()) {
            if (inv.firstEmpty() == -1) break; // 54'ten fazla ev varsa menü taşmasın
            ItemStack item = new ItemStack(ev.kiraci == null ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + ev.isim);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Kira Bedeli (10 Saat): " + ChatColor.DARK_GREEN + "$" + ev.fiyat);
            lore.add(ChatColor.GRAY + "Ev Sahibi: " + ChatColor.WHITE + sahipAdi(ev));
            lore.add("");

            if (ev.kiraci == null) {
                lore.add(ChatColor.GREEN + "► Durum: BOŞ");
                if (sozlesmeManager.basvurduMu(ev.id, p.getUniqueId())) {
                    lore.add(ChatColor.AQUA + "Başvurunuz ev sahibine iletildi.");
                } else {
                    lore.add(ChatColor.YELLOW + "Ev sahibine başvurmak için tıkla.");
                }
            } else {
                lore.add(ChatColor.RED + "► Durum: DOLU");
                if (ev.kaldirilacak) lore.add(ChatColor.GOLD + "Kira süresi bitince ilandan kalkacak.");
                if (ev.kiraci.equals(p.getUniqueId())) {
                    lore.add(ChatColor.AQUA + "Bu evi sen kiraladın.");
                    lore.add(ChatColor.AQUA + "Kalan Süre (oyunda): " + sureMetni(ev.kalanSureMs));
                }
            }
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(evIdKey, PersistentDataType.STRING, ev.id);
            item.setItemMeta(meta);
            inv.addItem(item);
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTitle().equals(MENU_BASLIGI)) {
            event.setCancelled(true);
            // Sadece üstteki menüye yapılan tıklamalar işlenir (oyuncu envanteri değil)
            if (event.getRawSlot() < 0 || event.getRawSlot() >= MENU_BOYUTU) return;
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

            Player p = (Player) event.getWhoClicked();
            ItemMeta meta = event.getCurrentItem().getItemMeta();
            if (meta == null || !meta.getPersistentDataContainer().has(evIdKey, PersistentDataType.STRING)) return;

            String eId = meta.getPersistentDataContainer().get(evIdKey, PersistentDataType.STRING);
            KiralikEv ev = evler.get(eId);
            if (ev == null) return;

            // Doğrudan kiralama yok: ev sahibine başvuru gider, sözleşme ile kiralanır
            p.closeInventory();
            sozlesmeManager.basvur(p, ev);
        }
    }

    private void kiraZamanlariniKontrolEt() {
        long now = System.currentTimeMillis();
        long gecen = now - sonSayacZamani;
        sonSayacZamani = now;
        boolean degisiklik = false;

        Iterator<KiralikEv> it = evler.values().iterator();
        while (it.hasNext()) {
            KiralikEv ev = it.next();
            if (ev.kiraci == null) continue;

            // Süre sadece kiracı oyundayken akar
            Player kiraciOyuncu = Bukkit.getPlayer(ev.kiraci);

            // 7 gündür oyuna girmeyen kiracı evi terk etmiş sayılır (süre akmadığı için ev sonsuza dek kilitlenmesin)
            if (kiraciOyuncu == null) {
                OfflinePlayer kiraciOp = Bukkit.getOfflinePlayer(ev.kiraci);
                long sonGorulme = kiraciOp.getLastSeen();
                if (sonGorulme > 0 && now - sonGorulme > TERK_SURESI_MS) {
                    if (!kiraciYetkisiniKaldir(ev)) continue;
                    String kiraciAdi = kiraciOp.getName() != null ? kiraciOp.getName() : "Kiracı";
                    evYoneticilerineGonder(ev, y -> y.sendMessage(ChatColor.YELLOW + "[Emlak] " + kiraciAdi + " 7 gündür oyuna girmediği için "
                            + ev.isim + " kira sözleşmesi sona erdi, ev tekrar boşta."));
                    plugin.getLogger().info("[Kira] '" + ev.id + "' kiracısı " + kiraciAdi + " 7 gündür girmediği için sözleşme sonlandırıldı.");
                    if (ev.kaldirilacak) {
                        it.remove();
                    } else {
                        ev.kiraci = null;
                        ev.kalanSureMs = 0L;
                    }
                    degisiklik = true;
                    continue;
                }
            }
            if (kiraciOyuncu != null && ev.kalanSureMs > 0) {
                ev.kalanSureMs -= gecen;
                degisiklik = true;
            }
            if (ev.kalanSureMs > 0) continue;

            if (ev.kaldirilacak) {
                // Sahibi ilanı kaldırdı: kira yenilenmez, kiracı çıkarılır, ev listeden silinir
                if (!kiraciYetkisiniKaldir(ev)) continue;
                if (kiraciOyuncu != null) kiraciOyuncu.sendMessage(ChatColor.GOLD + "[Emlak] " + ev.isim + " evinin kira sözleşmesi sona erdi, yetkileriniz kaldırıldı.");
                it.remove();
                degisiklik = true;
                continue;
            }

            double bakiye = plugin.bankaHesaplari.getOrDefault(ev.kiraci, 0.0);
            if (bakiye >= ev.fiyat) {
                // Kirayı ödeyebiliyor, süreyi 10 saat uzat
                // Kasa kurulu değilse para boşa gitmesin: bu dönem tahsil edilmez, kiracılık devam eder
                if (kiraOdemesiAktar(ev, ev.fiyat)) {
                    plugin.bankaHesaplari.put(ev.kiraci, bakiye - ev.fiyat);
                    if (kiraciOyuncu != null) kiraciOyuncu.sendMessage(ChatColor.GREEN + "[Emlak] " + ev.isim + " evinizin kirası ($" + ev.fiyat + ") otomatik olarak bankanızdan kesildi.");
                }
                ev.kalanSureMs += KIRA_SURESI_MS;
                degisiklik = true;
            } else {
                // Parası yok, HACİZ (Evden çıkarma)
                // Dünya yüklenince tekrar denenir, yetki asılı kalmasın
                if (!kiraciYetkisiniKaldir(ev)) continue;

                if (kiraciOyuncu != null) kiraciOyuncu.sendMessage(ChatColor.DARK_RED + "[Emlak] " + ev.isim + " evinizin kirasını ödeyemediğiniz için evinize HACİZ geldi ve yetkileriniz silindi!");

                ev.kiraci = null;
                ev.kalanSureMs = 0L;
                degisiklik = true;
            }
        }
        if (degisiklik) {
            veriKaydet();
        }
    }

    public void veriKaydet() {
        plugin.getConfig().set("kiralik_evler", null);
        for (KiralikEv ev : evler.values()) {
            String yol = "kiralik_evler." + ev.id;
            plugin.getConfig().set(yol + ".isim", ev.isim);
            plugin.getConfig().set(yol + ".fiyat", ev.fiyat);
            plugin.getConfig().set(yol + ".world", ev.dunya);
            plugin.getConfig().set(yol + ".x", ev.loc.getX());
            plugin.getConfig().set(yol + ".y", ev.loc.getY());
            plugin.getConfig().set(yol + ".z", ev.loc.getZ());
            plugin.getConfig().set(yol + ".sahip", ev.sahip != null ? ev.sahip.toString() : null);
            plugin.getConfig().set(yol + ".kiraci", ev.kiraci != null ? ev.kiraci.toString() : null);
            plugin.getConfig().set(yol + ".kalanSure", ev.kalanSureMs);
            plugin.getConfig().set(yol + ".kaldirilacak", ev.kaldirilacak ? true : null);
        }
        plugin.saveConfig();
    }

    private void veriYukle() {
        ConfigurationSection bolum = plugin.getConfig().getConfigurationSection("kiralik_evler");
        if (bolum == null) return;
        for (String id : bolum.getKeys(false)) {
            try {
                String yol = "kiralik_evler." + id;
                String isim = plugin.getConfig().getString(yol + ".isim");
                double fiyat = plugin.getConfig().getDouble(yol + ".fiyat");
                String dunyaAdi = plugin.getConfig().getString(yol + ".world");
                World dunya = dunyaAdi != null ? Bukkit.getWorld(dunyaAdi) : null;
                if (dunya == null) {
                    plugin.getLogger().warning("[Kira] '" + id + "' evinin dünyası (" + dunyaAdi + ") yüklü değil.");
                }
                Location loc = new Location(
                    dunya,
                    plugin.getConfig().getDouble(yol + ".x"),
                    plugin.getConfig().getDouble(yol + ".y"),
                    plugin.getConfig().getDouble(yol + ".z")
                );
                KiralikEv ev = new KiralikEv(id, isim, loc, fiyat);
                ev.dunya = dunyaAdi;

                // Eski kayıtlarda sahip yok, onlar belediye evi sayılır
                String sUUID = plugin.getConfig().getString(yol + ".sahip");
                if (sUUID != null && !sUUID.isEmpty()) ev.sahip = UUID.fromString(sUUID);

                String kUUID = plugin.getConfig().getString(yol + ".kiraci");
                if (kUUID != null && !kUUID.isEmpty()) {
                    ev.kiraci = UUID.fromString(kUUID);
                    if (plugin.getConfig().contains(yol + ".kalanSure")) {
                        ev.kalanSureMs = plugin.getConfig().getLong(yol + ".kalanSure");
                    } else {
                        // Eski kayıt (gerçek zamanlı bitiş): kalan süreye çevir
                        long bitis = plugin.getConfig().getLong(yol + ".bitisZamani");
                        ev.kalanSureMs = Math.max(0L, bitis - System.currentTimeMillis());
                    }
                    ev.kaldirilacak = plugin.getConfig().getBoolean(yol + ".kaldirilacak", false);
                }
                evler.put(id, ev);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[Kira] '" + id + "' evi yüklenemedi", e);
            }
        }
    }
}
