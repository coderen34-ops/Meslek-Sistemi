package me.mesleksistemi.pazar;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * OYUNCU PAZARLARI: köydeki pazar standlarının NPC'lerle kiralanıp işletilmesi.
 *
 * - Yetkili /pazarnpc kur ile boş bir stand NPC'si (köylü) kurar, /pazarnpc sil ile siler.
 * - Boş standa sağ tıklayan oyuncu kiralama ücretini (varsayılan $20.000, belediye kasasına
 *   gider) ödeyip standın sahibi olabilir; NPC'nin adı o anda kiralayan oyuncuya özel değişir.
 * - Sahip, elindeki eşya yığınını standa gizlice (shift+sağ tık) vererek satışa çıkarır;
 *   ilk kez satışa çıkan eşyanın birim fiyatı sohbetten sorulur, sonraki eklemelerde (TAMAMEN
 *   aynı eşya - tip + büyü/iksir/isim vb. - ise) stok olduğu gibi artar. Yönetim menüsünden
 *   (kendi standına normal sağ tık) ilan kaldırılabilir veya stand tamamen bırakılabilir.
 * - Başka bir oyuncu standa sağ tıklarsa önce eşya kategorisi, sonra o kategorideki ilanları
 *   görür (gerekirse birden fazla sayfa halinde); satın alınan her üründen belediyeye %10
 *   (ayarlanabilir) vergi gider, kalanı doğrudan stand sahibinin banka hesabına yatar.
 *
 * ÖNEMLİ: bir ilan artık sadece "Material" ile değil, satışa çıkarılan eşyanın TAMAMI
 * (ItemStack - büyü, iksir efekti, özel isim, NBT vb. dahil) ile tutulur. Bu yüzden her
 * ilanın kendine özgü bir kimliği (listingId) vardır; aynı Material'dan ama farklı
 * büyülü/iksirli iki eşya AYRI ilan olarak durur, biri verilirken diğerininki bozulmaz.
 */
public class PazarManager implements Listener, CommandExecutor {

    public static final String YETKI_ADMIN = "pazar.admin";
    public static final String YETKI_KULLAN = "pazar.kullan";

    /** Liste GUI'sinde bir sayfada gösterilecek azami ürün sayısı (sabit çift sandık: 54 slot - alt sıra navigasyon). */
    private static final int SAYFA_BASINA_URUN = 45;

    private final MeslekSistemi plugin;
    private final NamespacedKey standKey;
    private final NamespacedKey idKey;

    private final Map<String, Stand> standlar = new LinkedHashMap<>();
    private final Map<UUID, PendingListing> bekleyenIlan = new HashMap<>();
    private final Map<UUID, GuiContext> acikGui = new HashMap<>();

    public PazarManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.standKey = new NamespacedKey(plugin, "pazar_stand");
        this.idKey = new NamespacedKey(plugin, "pazar_stand_id");
        veriYukle();
    }

    // ------------------------------------------------------------------ MODEL
    private static class Stand {
        final String id;
        UUID sahip; // null = boş, kiralanabilir
        final Map<String, Listing> listings = new LinkedHashMap<>(); // key: listingId (UUID string)

        Stand(String id) { this.id = id; }
    }

    private static class Listing {
        ItemStack ornek; // amount=1, TAM meta (büyü/iksir/isim/NBT dahil) ile
        double fiyat;
        int stok;
    }

    private static class PendingListing {
        final String standId;
        final ItemStack ornek; // amount=1, TAM meta ile (fiyat sorulurken eşyanın kendisini kaybetmemek için)
        final int miktar;

        PendingListing(String standId, ItemStack ornek, int miktar) {
            this.standId = standId;
            this.ornek = ornek;
            this.miktar = miktar;
        }
    }

    private static class GuiContext {
        String tip; // KIRALA, YONETIM, KATEGORI, LISTE
        String standId;
        Kategori kategori; // sadece LISTE
        int sayfa; // sadece LISTE (0 tabanlı)
        final Map<Integer, String> slotListingId = new HashMap<>();
        final Map<Integer, Kategori> slotKategori = new HashMap<>();
        int geriSlot = -1;
    }

    // ------------------------------------------------------------------ KOMUT: /pazarnpc kur|sil
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Bu komut oyun içinden kullanılır.");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission(YETKI_ADMIN)) {
            p.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkin yok.");
            return true;
        }
        if (args.length == 0) {
            p.sendMessage(ChatColor.YELLOW + "Kullanım: /pazarnpc <kur|sil>");
            return true;
        }
        if (args[0].equalsIgnoreCase("kur")) {
            kur(p);
        } else if (args[0].equalsIgnoreCase("sil")) {
            sil(p);
        } else {
            p.sendMessage(ChatColor.YELLOW + "Kullanım: /pazarnpc <kur|sil>");
        }
        return true;
    }

    private void kur(Player p) {
        Villager v = (Villager) p.getWorld().spawnEntity(p.getLocation(), EntityType.VILLAGER);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setSilent(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setCollidable(false);
        // NITWIT: bu meslekteki köylünün vanilla ticareti olmaz, böylece sağ tık menümüz yerine
        // yanlışlıkla köylü ticaret ekranı açılmaz (Arena Ligi'nde görülen aynı bug'ın önceden önlenmesi).
        v.setProfession(Villager.Profession.NITWIT);

        String standId = UUID.randomUUID().toString();
        v.getPersistentDataContainer().set(standKey, PersistentDataType.BYTE, (byte) 1);
        v.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, standId);

        Stand s = new Stand(standId);
        standlar.put(standId, s);
        veriKaydetPazar();
        standAdiGuncelle(standId, v);

        p.sendMessage(ChatColor.GREEN + "Pazar stand NPC'si kuruldu! Kiralama fiyatı: $" + fmt(plugin.pazarKiralamaFiyati()));
    }

    private void sil(Player p) {
        int silinen = 0;
        for (Entity e : p.getNearbyEntities(5, 5, 5)) {
            if (!e.getPersistentDataContainer().has(standKey, PersistentDataType.BYTE)) continue;
            String id = e.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
            if (id != null) standlar.remove(id);
            e.remove();
            silinen++;
        }
        veriKaydetPazar();
        p.sendMessage(ChatColor.GREEN + "" + silinen + " adet pazar standı silindi.");
    }

    // ------------------------------------------------------------------ ETKİLEŞİM: NPC'ye tıklama
    @EventHandler(priority = EventPriority.LOW)
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (!event.getRightClicked().getPersistentDataContainer().has(standKey, PersistentDataType.BYTE)) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player p = event.getPlayer();
        if (!p.hasPermission(YETKI_KULLAN)) {
            p.sendMessage(ChatColor.RED + "Bu işlem için yetkiniz yok.");
            return;
        }
        String standId = event.getRightClicked().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        if (standId == null) return;
        Stand s = standlar.get(standId);
        if (s == null) return;

        boolean sahipMi = s.sahip != null && s.sahip.equals(p.getUniqueId());
        if (sahipMi && p.isSneaking()) {
            ItemStack elindeki = p.getInventory().getItemInMainHand();
            if (elindeki != null && elindeki.getType() != Material.AIR) {
                ilanEkle(p, s, standId, elindeki);
                return;
            }
        }

        if (s.sahip == null) {
            acKiralamaGui(p, standId);
        } else if (sahipMi) {
            acYonetimGui(p, standId);
        } else {
            acKategoriGui(p, standId);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHasar(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(standKey, PersistentDataType.BYTE)) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ İLAN EKLEME (shift + sağ tık)
    private void ilanEkle(Player p, Stand s, String standId, ItemStack elindeki) {
        // Oyuncunun zaten cevap bekleyen bir ilanı varsa (fiyat sohbete henüz yazılmadıysa) ikinci
        // bir eşyayı kabul ETMİYORUZ: eskiden burada bekleyenIlan sessizce ÜZERİNE YAZILIYORDU ve
        // ilk eşya (zaten elden alınmıştı) hiçbir yere geri verilmeden kayboluyordu. Artık oyuncuyu
        // uyarıp önce ilk ilanı bitirmesini (fiyat yaz ya da 'iptal') istiyoruz, veri kaybı olmasın.
        if (bekleyenIlan.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "Önce elindeki ürün için fiyat yaz (ya da 'iptal' yaz), sonra yeni bir ürün ekleyebilirsin.");
            return;
        }

        int miktar = elindeki.getAmount();
        ItemStack ornekAday = elindeki.clone();
        ornekAday.setAmount(1);

        Listing mevcut = null;
        for (Listing l : s.listings.values()) {
            if (l.ornek.isSimilar(ornekAday)) { mevcut = l; break; }
        }

        if (mevcut != null) {
            mevcut.stok += miktar;
            p.getInventory().setItemInMainHand(null);
            veriKaydetPazar();
            p.sendMessage(ChatColor.GREEN + "" + miktar + " adet " + esyaGorunenIsim(mevcut.ornek) + " stoğa eklendi. Yeni stok: "
                    + mevcut.stok + " §7(Fiyat: $" + fmt(mevcut.fiyat) + "/adet)");
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            return;
        }

        p.getInventory().setItemInMainHand(null);
        bekleyenIlan.put(p.getUniqueId(), new PendingListing(standId, ornekAday, miktar));
        p.sendMessage(ChatColor.GOLD + esyaGorunenIsim(ornekAday) + ChatColor.YELLOW + " için birim fiyatı (adet başına $) sohbete yazın. İptal için 'iptal' yazın.");
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player p = event.getPlayer();
        PendingListing pl = bekleyenIlan.get(p.getUniqueId());
        if (pl == null) return;
        event.setCancelled(true);
        String mesaj = event.getMessage().trim();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (bekleyenIlan.get(p.getUniqueId()) != pl) return;

            if (mesaj.equalsIgnoreCase("iptal")) {
                bekleyenIlan.remove(p.getUniqueId());
                geriVer(p, pl.ornek, pl.miktar);
                p.sendMessage(ChatColor.YELLOW + "İptal edildi, eşyalar geri verildi.");
                return;
            }

            double fiyat;
            try {
                fiyat = MeslekSistemi.parsePara(mesaj);
                if (fiyat <= 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                p.sendMessage(ChatColor.RED + "Geçersiz fiyat. Sadece sayı yazın (örn: 50) ya da 'iptal' yazın.");
                return;
            }

            bekleyenIlan.remove(p.getUniqueId());
            Stand s = standlar.get(pl.standId);
            if (s == null || !p.getUniqueId().equals(s.sahip)) {
                geriVer(p, pl.ornek, pl.miktar);
                p.sendMessage(ChatColor.RED + "Stand artık sizin değil, eşyalar geri verildi.");
                return;
            }

            Listing l = new Listing();
            l.ornek = pl.ornek;
            l.fiyat = fiyat;
            l.stok = pl.miktar;
            s.listings.put(UUID.randomUUID().toString(), l);
            veriKaydetPazar();
            p.sendMessage(ChatColor.GREEN + esyaGorunenIsim(pl.ornek) + " $" + fmt(fiyat) + " birim fiyatla satışa çıkarıldı! ("
                    + pl.miktar + " adet)");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
        });
    }

    // ------------------------------------------------------------------ KİRALAMA GUI
    private void acKiralamaGui(Player p, String standId) {
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.DARK_GREEN + "Stand Kiralama");
        gui.setItem(3, basitEsya(Material.EMERALD, ChatColor.GREEN + "Kirala - $" + fmt(plugin.pazarKiralamaFiyati()),
                List.of(ChatColor.GRAY + "Tıkla ve bu standı kirala!", ChatColor.GRAY + "Ücret belediye kasasına yatar.")));
        gui.setItem(5, basitEsya(Material.BARRIER, ChatColor.RED + "İptal", List.of()));

        GuiContext ctx = new GuiContext();
        ctx.tip = "KIRALA";
        ctx.standId = standId;
        // ÖNEMLİ: openInventory() önce çağrılmalı. Oyuncunun zaten açık bir Pazar GUI'si varsa
        // (kategori -> liste geçişi, satın alma sonrası yenileme, vb.) openInventory() bu çağrının
        // İÇİNDE, senkron olarak eski GUI için InventoryCloseEvent fırlatır ve onInventoryClose
        // acikGui'den kaydı siler. put() bu satırdan ÖNCE yapılırsa, o silme az önce koyduğumuz
        // YENİ context'i siler (çünkü close event anında acikGui zaten yeni ctx'i gösterir) ve
        // bir sonraki tıklamada ctx == null olur: event iptal edilmez, oyuncu vitrindeki eşyayı
        // bedavaya (satın alma mantığı hiç çalışmadan) envanterine almış olur. Bu yüzden önce
        // GUI'yi aç (eski GUI'nin kapanma event'i -eğer varsa- eski context'i temizler), SONRA
        // yeni context'i koy.
        p.openInventory(gui);
        acikGui.put(p.getUniqueId(), ctx);
    }

    private void kiralaStand(Player p, String standId) {
        Stand s = standlar.get(standId);
        if (s == null) return;
        if (s.sahip != null) {
            p.sendMessage(ChatColor.RED + "Bu stand az önce başka biri tarafından kiralandı!");
            return;
        }
        double fiyat = plugin.pazarKiralamaFiyati();
        double bakiye = plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
        if (bakiye < fiyat) {
            p.sendMessage(ChatColor.RED + "Yetersiz bakiye! Gereken: $" + fmt(fiyat) + ", mevcut: $" + fmt(bakiye));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return;
        }
        if (!plugin.kasayaParaEkle(fiyat)) {
            p.sendMessage(ChatColor.RED + "Belediye kasası aktif değil ya da dolu, kiralama yapılamıyor! Yetkililere bildirin.");
            return;
        }
        plugin.bankaHesaplari.put(p.getUniqueId(), bakiye - fiyat);
        s.sahip = p.getUniqueId();
        plugin.veriKaydet();
        veriKaydetPazar();
        standAdiGuncelle(standId);
        plugin.ekonomiLog("pazar-kiralama", p.getName(), "Stand " + standId + " kiralandı, $" + fmt(fiyat) + " belediye kasasına yatırıldı.");

        p.sendMessage(ChatColor.GREEN + "Standı kiraladın! Satışa çıkarmak için elindeki eşyayla standa shift+sağ tık yap.");
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------ YÖNETİM GUI (sahip)
    private void acYonetimGui(Player p, String standId) {
        Stand s = standlar.get(standId);
        if (s == null) return;
        Inventory gui = Bukkit.createInventory(null, 54, ChatColor.GOLD + "Stand Yönetimi");
        GuiContext ctx = new GuiContext();
        ctx.tip = "YONETIM";
        ctx.standId = standId;

        int slot = 0;
        for (Map.Entry<String, Listing> e : s.listings.entrySet()) {
            if (slot >= 45) break;
            Listing l = e.getValue();
            ItemStack item = l.ornek.clone();
            item.setAmount(Math.max(1, Math.min(l.stok, item.getMaxStackSize())));
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Fiyat: " + ChatColor.GREEN + "$" + fmt(l.fiyat) + "/adet");
            lore.add(ChatColor.GRAY + "Stok: " + (l.stok > 0 ? ChatColor.AQUA + "" + l.stok : ChatColor.RED + "Tükendi"));
            lore.add("");
            lore.add(ChatColor.YELLOW + "Tıkla: " + ChatColor.WHITE + "İlanı kaldır (stok sana geri verilir)");
            esyaLoreEkle(item, lore);
            gui.setItem(slot, item);
            ctx.slotListingId.put(slot, e.getKey());
            slot++;
        }

        gui.setItem(49, basitEsya(Material.BARRIER, ChatColor.RED + "Standı Bırak",
                List.of(ChatColor.GRAY + "Kiralamayı sonlandırır, satılmamış", ChatColor.GRAY + "stok envanterine geri verilir.")));

        // ÖNEMLİ: openInventory() önce çağrılmalı. Oyuncunun zaten açık bir Pazar GUI'si varsa
        // (kategori -> liste geçişi, satın alma sonrası yenileme, vb.) openInventory() bu çağrının
        // İÇİNDE, senkron olarak eski GUI için InventoryCloseEvent fırlatır ve onInventoryClose
        // acikGui'den kaydı siler. put() bu satırdan ÖNCE yapılırsa, o silme az önce koyduğumuz
        // YENİ context'i siler (çünkü close event anında acikGui zaten yeni ctx'i gösterir) ve
        // bir sonraki tıklamada ctx == null olur: event iptal edilmez, oyuncu vitrindeki eşyayı
        // bedavaya (satın alma mantığı hiç çalışmadan) envanterine almış olur. Bu yüzden önce
        // GUI'yi aç (eski GUI'nin kapanma event'i -eğer varsa- eski context'i temizler), SONRA
        // yeni context'i koy.
        p.openInventory(gui);
        acikGui.put(p.getUniqueId(), ctx);
    }

    private void ilanKaldir(Player p, Stand s, String listingId) {
        Listing l = s.listings.remove(listingId);
        if (l == null) return;
        if (l.stok > 0) geriVer(p, l.ornek, l.stok);
        veriKaydetPazar();
        p.sendMessage(ChatColor.YELLOW + esyaGorunenIsim(l.ornek) + " ilanı kaldırıldı" + (l.stok > 0 ? ", " + l.stok + " adet envanterine geri verildi." : "."));
    }

    private void standBirak(Player p, String standId) {
        Stand s = standlar.get(standId);
        if (s == null || !p.getUniqueId().equals(s.sahip)) return;
        for (Listing l : s.listings.values()) {
            if (l.stok > 0) geriVer(p, l.ornek, l.stok);
        }
        s.listings.clear();
        s.sahip = null;
        veriKaydetPazar();
        standAdiGuncelle(standId);
        p.sendMessage(ChatColor.YELLOW + "Standı bıraktın, tekrar kiralanabilir hale geldi.");
    }

    // ------------------------------------------------------------------ KATEGORİ GUI (başka oyuncu)
    private void acKategoriGui(Player p, String standId) {
        Stand s = standlar.get(standId);
        if (s == null) return;

        List<Kategori> mevcutKategoriler = new ArrayList<>();
        for (Kategori k : Kategori.values()) {
            boolean varMi = s.listings.values().stream().anyMatch(l -> Kategori.sinifla(l.ornek.getType()) == k);
            if (varMi) mevcutKategoriler.add(k);
        }
        if (mevcutKategoriler.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Bu standın şu anda satışta hiçbir ürünü yok.");
            return;
        }

        String sahipAdi = adiniBul(s.sahip);
        Inventory gui = Bukkit.createInventory(null, 9, ChatColor.DARK_AQUA + sahipAdi + " Standı " + ChatColor.GRAY + "- Kategoriler");
        GuiContext ctx = new GuiContext();
        ctx.tip = "KATEGORI";
        ctx.standId = standId;

        int slot = 0;
        for (Kategori k : mevcutKategoriler) {
            if (slot >= 9) break;
            gui.setItem(slot, basitEsya(k.ikon, k.ad, List.of(ChatColor.GRAY + "Tıkla ve ürünleri gör.")));
            ctx.slotKategori.put(slot, k);
            slot++;
        }

        // ÖNEMLİ: openInventory() önce çağrılmalı. Oyuncunun zaten açık bir Pazar GUI'si varsa
        // (kategori -> liste geçişi, satın alma sonrası yenileme, vb.) openInventory() bu çağrının
        // İÇİNDE, senkron olarak eski GUI için InventoryCloseEvent fırlatır ve onInventoryClose
        // acikGui'den kaydı siler. put() bu satırdan ÖNCE yapılırsa, o silme az önce koyduğumuz
        // YENİ context'i siler (çünkü close event anında acikGui zaten yeni ctx'i gösterir) ve
        // bir sonraki tıklamada ctx == null olur: event iptal edilmez, oyuncu vitrindeki eşyayı
        // bedavaya (satın alma mantığı hiç çalışmadan) envanterine almış olur. Bu yüzden önce
        // GUI'yi aç (eski GUI'nin kapanma event'i -eğer varsa- eski context'i temizler), SONRA
        // yeni context'i koy.
        p.openInventory(gui);
        acikGui.put(p.getUniqueId(), ctx);
    }

    // ------------------------------------------------------------------ İLAN LİSTESİ GUI (satın alma, sayfalı)
    private void acListeGui(Player p, String standId, Kategori kategori) {
        acListeGui(p, standId, kategori, 0);
    }

    private void acListeGui(Player p, String standId, Kategori kategori, int sayfa) {
        Stand s = standlar.get(standId);
        if (s == null) return;

        List<Map.Entry<String, Listing>> urunler = new ArrayList<>();
        for (Map.Entry<String, Listing> e : s.listings.entrySet()) {
            if (Kategori.sinifla(e.getValue().ornek.getType()) == kategori) urunler.add(e);
        }
        if (urunler.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Bu kategoride artık ürün yok.");
            acKategoriGui(p, standId);
            return;
        }

        int toplamSayfa = Math.max(1, (urunler.size() + SAYFA_BASINA_URUN - 1) / SAYFA_BASINA_URUN);
        if (sayfa < 0) sayfa = 0;
        if (sayfa >= toplamSayfa) sayfa = toplamSayfa - 1;

        // Sayfa boyutu her zaman sabit çift sandık (54 slot): üst 5 sıra (0-44) ürünler,
        // alt sıra (45-53) navigasyon için ayrılmış.
        String baslik = ChatColor.DARK_AQUA + adiniBul(s.sahip) + " Standı " + ChatColor.GRAY + "- " + kategori.ad
                + (toplamSayfa > 1 ? ChatColor.DARK_GRAY + " (" + (sayfa + 1) + "/" + toplamSayfa + ")" : "");
        Inventory gui = Bukkit.createInventory(null, 54, baslik);
        GuiContext ctx = new GuiContext();
        ctx.tip = "LISTE";
        ctx.standId = standId;
        ctx.kategori = kategori;
        ctx.sayfa = sayfa;

        int basla = sayfa * SAYFA_BASINA_URUN;
        int bitis = Math.min(urunler.size(), basla + SAYFA_BASINA_URUN);
        int slot = 0;
        for (int i = basla; i < bitis; i++) {
            Map.Entry<String, Listing> e = urunler.get(i);
            Listing l = e.getValue();
            ItemStack item = l.ornek.clone();
            item.setAmount(1);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Birim Fiyat: " + ChatColor.GREEN + "$" + fmt(l.fiyat));
            lore.add(ChatColor.GRAY + "Stok: " + (l.stok > 0 ? ChatColor.AQUA + "" + l.stok : ChatColor.RED + "Tükendi"));
            lore.add("");
            lore.add(ChatColor.YELLOW + "Sol tık: " + ChatColor.WHITE + "1 adet al");
            lore.add(ChatColor.YELLOW + "Shift + Sol tık: " + ChatColor.WHITE + "Alabildiğin kadar al");
            esyaLoreEkle(item, lore);
            gui.setItem(slot, item);
            ctx.slotListingId.put(slot, e.getKey());
            slot++;
        }

        if (sayfa > 0) {
            gui.setItem(45, basitEsya(Material.SPECTRAL_ARROW, ChatColor.YELLOW + "Önceki Sayfa", List.of()));
        }
        int geriSlot = 49;
        gui.setItem(geriSlot, basitEsya(Material.ARROW, ChatColor.YELLOW + "Geri (Kategoriler)", List.of()));
        ctx.geriSlot = geriSlot;
        if (sayfa < toplamSayfa - 1) {
            gui.setItem(53, basitEsya(Material.SPECTRAL_ARROW, ChatColor.YELLOW + "Sonraki Sayfa", List.of()));
        }

        // ÖNEMLİ: openInventory() önce çağrılmalı. Oyuncunun zaten açık bir Pazar GUI'si varsa
        // (kategori -> liste geçişi, satın alma sonrası yenileme, vb.) openInventory() bu çağrının
        // İÇİNDE, senkron olarak eski GUI için InventoryCloseEvent fırlatır ve onInventoryClose
        // acikGui'den kaydı siler. put() bu satırdan ÖNCE yapılırsa, o silme az önce koyduğumuz
        // YENİ context'i siler (çünkü close event anında acikGui zaten yeni ctx'i gösterir) ve
        // bir sonraki tıklamada ctx == null olur: event iptal edilmez, oyuncu vitrindeki eşyayı
        // bedavaya (satın alma mantığı hiç çalışmadan) envanterine almış olur. Bu yüzden önce
        // GUI'yi aç (eski GUI'nin kapanma event'i -eğer varsa- eski context'i temizler), SONRA
        // yeni context'i koy.
        p.openInventory(gui);
        acikGui.put(p.getUniqueId(), ctx);
    }

    private void satinAl(Player alici, Stand s, String listingId, int adet) {
        Listing l = s.listings.get(listingId);
        if (l == null || l.stok <= 0 || adet <= 0) {
            alici.sendMessage(ChatColor.RED + "Bu üründen alınamıyor.");
            return;
        }
        if (s.sahip == null) return;

        double toplam = Math.round(l.fiyat * adet * 100.0) / 100.0;
        double aliciBakiye = plugin.bankaHesaplari.getOrDefault(alici.getUniqueId(), 0.0);
        if (aliciBakiye < toplam) {
            alici.sendMessage(ChatColor.RED + "Yetersiz bakiye! Gereken: $" + fmt(toplam));
            return;
        }
        double vergiYuzde = plugin.pazarVergiYuzde();
        double vergi = Math.round(toplam * (vergiYuzde / 100.0) * 100.0) / 100.0;
        double saticiPay = Math.round((toplam - vergi) * 100.0) / 100.0;

        if (!plugin.kasayaParaEkle(vergi)) {
            alici.sendMessage(ChatColor.RED + "Belediye kasası aktif değil ya da dolu, şu an satış yapılamıyor! Yetkililere bildirin.");
            return;
        }

        plugin.bankaHesaplari.put(alici.getUniqueId(), aliciBakiye - toplam);
        double saticiBakiye = plugin.bankaHesaplari.getOrDefault(s.sahip, 0.0);
        plugin.bankaHesaplari.put(s.sahip, saticiBakiye + saticiPay);
        l.stok -= adet;

        String isim = esyaGorunenIsim(l.ornek);
        geriVer(alici, l.ornek, adet);
        plugin.veriKaydet();
        veriKaydetPazar();

        alici.sendMessage(ChatColor.GREEN + "" + adet + " adet " + isim + " satın alındı! $" + fmt(toplam) + " ödendi.");
        alici.playSound(alici.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        plugin.ekonomiLog("pazar-satis", alici.getName(), adet + "x " + l.ornek.getType().name() + " | stand: " + s.id + " | sahip: " + adiniBul(s.sahip)
                + " | toplam: $" + fmt(toplam) + " | vergi: $" + fmt(vergi));

        OfflinePlayer saticiOP = Bukkit.getOfflinePlayer(s.sahip);
        if (saticiOP.isOnline() && saticiOP.getPlayer() != null) {
            saticiOP.getPlayer().sendMessage(ChatColor.GREEN + "Standından " + adet + " adet " + isim + " satıldı! $"
                    + fmt(saticiPay) + " hesabına yattı " + ChatColor.GRAY + "(vergi: $" + fmt(vergi) + ")");
        }
    }

    private int hesaplaMaxAlinabilir(Player p, Listing l) {
        double bakiye = plugin.bankaHesaplari.getOrDefault(p.getUniqueId(), 0.0);
        int karsilanabilir = l.fiyat > 0 ? (int) Math.floor(bakiye / l.fiyat + 1e-9) : Integer.MAX_VALUE;
        int envanterBosYer = hesaplaBosYer(p, l.ornek);
        return Math.max(0, Math.min(Math.min(karsilanabilir, l.stok), envanterBosYer));
    }

    private int hesaplaBosYer(Player p, ItemStack ornek) {
        int bosYer = 0;
        for (ItemStack i : p.getInventory().getStorageContents()) {
            if (i == null || i.getType() == Material.AIR) bosYer += ornek.getMaxStackSize();
            else if (i.isSimilar(ornek) && i.getAmount() < i.getMaxStackSize()) bosYer += (i.getMaxStackSize() - i.getAmount());
        }
        return bosYer;
    }

    // ------------------------------------------------------------------ GUI TIKLAMA
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player p = (Player) event.getWhoClicked();
        GuiContext ctx = acikGui.get(p.getUniqueId());
        if (ctx == null) return;
        if (event.getClickedInventory() == null || event.getClickedInventory().equals(p.getInventory())) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        int slot = event.getSlot();

        switch (ctx.tip) {
            case "KIRALA":
                if (slot == 3) {
                    p.closeInventory();
                    kiralaStand(p, ctx.standId);
                } else if (slot == 5) {
                    p.closeInventory();
                }
                break;
            case "YONETIM": {
                Stand s = standlar.get(ctx.standId);
                if (s == null || !p.getUniqueId().equals(s.sahip)) { p.closeInventory(); return; }
                if (slot == 49) {
                    p.closeInventory();
                    standBirak(p, ctx.standId);
                    return;
                }
                String listingId = ctx.slotListingId.get(slot);
                if (listingId != null) {
                    ilanKaldir(p, s, listingId);
                    acYonetimGui(p, ctx.standId);
                }
                break;
            }
            case "KATEGORI": {
                Kategori k = ctx.slotKategori.get(slot);
                if (k != null) acListeGui(p, ctx.standId, k);
                break;
            }
            case "LISTE": {
                if (slot == ctx.geriSlot) {
                    acKategoriGui(p, ctx.standId);
                    return;
                }
                if (slot == 45 && ctx.sayfa > 0 && event.getCurrentItem() != null) {
                    acListeGui(p, ctx.standId, ctx.kategori, ctx.sayfa - 1);
                    return;
                }
                if (slot == 53 && event.getCurrentItem() != null && !ctx.slotListingId.containsKey(53)) {
                    // slotListingId'de 53 yoksa bu slot bir ürün değil, "Sonraki Sayfa" butonudur
                    // (acListeGui 53'ü sadece gerçekten bir sonraki sayfa varsa dolduruyor).
                    acListeGui(p, ctx.standId, ctx.kategori, ctx.sayfa + 1);
                    return;
                }
                String listingId = ctx.slotListingId.get(slot);
                if (listingId == null) return;
                Stand s = standlar.get(ctx.standId);
                if (s == null) return;
                Listing l = s.listings.get(listingId);
                if (l == null) return;
                ClickType click = event.getClick();
                int adet;
                if (click == ClickType.SHIFT_LEFT) adet = hesaplaMaxAlinabilir(p, l);
                else if (click == ClickType.LEFT) adet = Math.min(1, hesaplaMaxAlinabilir(p, l));
                else return;
                if (adet <= 0) {
                    p.sendMessage(ChatColor.RED + "Alamazsın: stok tükenmiş, bakiyen yetersiz ya da envanterin dolu.");
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
                    return;
                }
                satinAl(p, s, listingId, adet);
                acListeGui(p, ctx.standId, ctx.kategori, ctx.sayfa);
                break;
            }
            default:
                break;
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        acikGui.remove(event.getPlayer().getUniqueId());
    }

    /** Oyuncu vitrindeki eşyayı sürükleyerek bedavaya kendi envanterine alamasın. */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (acikGui.containsKey(p.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        acikGui.remove(id);
        PendingListing pl = bekleyenIlan.remove(id);
        if (pl != null) {
            // Oyuncu sohbet yanıtı vermeden ayrıldı: eşyalar kaybolmasın, giriş yaptığında envanterine eklenir
            // zaten ana thread'de inventory erişimi olmadığından burada bırakıyoruz; basitçe düşürmek yerine
            // çıkışta envantere eklemeyi deniyoruz (oyuncu nesnesi hâlâ erişilebilir).
            ItemStack vermelik = pl.ornek.clone();
            vermelik.setAmount(pl.miktar);
            Map<Integer, ItemStack> kalan = event.getPlayer().getInventory().addItem(vermelik);
            for (ItemStack it : kalan.values()) event.getPlayer().getWorld().dropItem(event.getPlayer().getLocation(), it);
        }
    }

    // ------------------------------------------------------------------ YARDIMCI
    /** ornek'in TAM kopyasını (meta/büyü/iksir dahil) adet kadar oyuncuya verir. */
    private void geriVer(Player p, ItemStack ornek, int adet) {
        if (adet <= 0) return;
        int maxYigin = ornek.getMaxStackSize();
        int kalanAdet = adet;
        while (kalanAdet > 0) {
            int buSefer = Math.min(kalanAdet, maxYigin);
            ItemStack parca = ornek.clone();
            parca.setAmount(buSefer);
            Map<Integer, ItemStack> veremedigi = p.getInventory().addItem(parca);
            for (ItemStack it : veremedigi.values()) p.getWorld().dropItem(p.getLocation(), it);
            kalanAdet -= buSefer;
        }
    }

    private String adiniBul(UUID uuid) {
        if (uuid == null) return "Boş";
        String isim = Bukkit.getOfflinePlayer(uuid).getName();
        return isim != null ? isim : "?";
    }

    private void standAdiGuncelle(String standId) {
        Entity e = bulEntity(standId);
        if (e instanceof Villager) standAdiGuncelle(standId, (Villager) e);
    }

    private void standAdiGuncelle(String standId, Villager v) {
        Stand s = standlar.get(standId);
        if (s == null) return;
        if (s.sahip == null) {
            v.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + "Boş Stand " + ChatColor.GRAY + "(Kiralanabilir - $" + fmt(plugin.pazarKiralamaFiyati()) + ")");
        } else {
            v.setCustomName(ChatColor.YELLOW + "" + ChatColor.BOLD + adiniBul(s.sahip) + ChatColor.YELLOW + "'nin Standı");
        }
        v.setCustomNameVisible(true);
        standGorunumGuncelle(s, v);
    }

    /**
     * NPC'yi kiralayan oyuncunun skin'ine büründürür; stand boşsa disguise'ı kaldırır.
     * LibsDisguises API'sine dokunan kod bilerek StandDisguiseYardimcisi sınıfında: bu sınıf
     * (ve dolayısıyla me.libraryaddict.* sınıfları) sadece LibsDisguises gerçekten etkinken
     * yükleniyor, yoksa PazarManager'ın kendi sınıf doğrulaması (classloading) etkilenmiyor.
     */
    private void standGorunumGuncelle(Stand s, Villager v) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LibsDisguises")) return;
        String isim = s.sahip == null ? null : adiniBul(s.sahip);
        StandDisguiseYardimcisi.guncelle(v, isim);
    }

    private Entity bulEntity(String standId) {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (!e.getPersistentDataContainer().has(idKey, PersistentDataType.STRING)) continue;
                if (standId.equals(e.getPersistentDataContainer().get(idKey, PersistentDataType.STRING))) return e;
            }
        }
        return null;
    }

    /** Vitrinde/listede gösterilecek görünen isim: eşyanın kendi özel ismi varsa o, yoksa Material adı. */
    private static String esyaGorunenIsim(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return ChatColor.stripColor(item.getItemMeta().getDisplayName());
        }
        return item.getType().name().replace("_", " ");
    }

    private static String fmt(double d) {
        return String.format(Locale.US, "%,.0f", d).replace(",", ".");
    }

    private static ItemStack basitEsya(Material mat, String isim, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(isim);
            if (lore != null && !lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /**
     * GUI'de gösterilecek bir satış öğesinin lore'una fiyat/stok/kontrol bilgisini EKLER.
     * Eşyanın kendi orijinal display name'i, büyüsü, iksir efekti vb. KORUNUR (üzerine yazılmaz) -
     * bu yüzden isimDuzelt + setDisplayName yerine meta'yı alıp lore'un başına ekleme yapıyoruz.
     */
    private static void esyaLoreEkle(ItemStack item, List<String> ekLore) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;
        List<String> lore = new ArrayList<>();
        if (meta.hasLore()) lore.addAll(meta.getLore());
        if (!lore.isEmpty()) lore.add("");
        lore.addAll(ekLore);
        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    // ------------------------------------------------------------------ KALICILIK (config.yml -> pazar_standlari.*)
    public void veriKaydetPazar() {
        plugin.getConfig().set("pazar_standlari", null);
        for (Stand s : standlar.values()) {
            String yol = "pazar_standlari." + s.id;
            if (s.sahip != null) plugin.getConfig().set(yol + ".sahip", s.sahip.toString());
            for (Map.Entry<String, Listing> e : s.listings.entrySet()) {
                String uYol = yol + ".urunler." + e.getKey();
                // YamlConfiguration ItemStack'i (ConfigurationSerializable) TAM meta'sıyla
                // (büyü, iksir efekti, isim, lore, NBT) native olarak serileştirir/geri okur.
                plugin.getConfig().set(uYol + ".esya", e.getValue().ornek);
                plugin.getConfig().set(uYol + ".fiyat", e.getValue().fiyat);
                plugin.getConfig().set(uYol + ".stok", e.getValue().stok);
            }
        }
        plugin.saveConfig();
    }

    private void veriYukle() {
        standlar.clear();
        if (!plugin.getConfig().isConfigurationSection("pazar_standlari")) return;
        for (String standId : plugin.getConfig().getConfigurationSection("pazar_standlari").getKeys(false)) {
            Stand s = new Stand(standId);
            String yol = "pazar_standlari." + standId;
            String sahipStr = plugin.getConfig().getString(yol + ".sahip");
            if (sahipStr != null) {
                try {
                    s.sahip = UUID.fromString(sahipStr);
                } catch (IllegalArgumentException ignored) {
                }
            }
            if (plugin.getConfig().isConfigurationSection(yol + ".urunler")) {
                for (String listingId : plugin.getConfig().getConfigurationSection(yol + ".urunler").getKeys(false)) {
                    String uYol = yol + ".urunler." + listingId;
                    ItemStack ornek = plugin.getConfig().getItemStack(uYol + ".esya");
                    if (ornek == null) continue; // eski format (sadece Material) ya da bozuk kayıt: atla
                    Listing l = new Listing();
                    l.ornek = ornek;
                    l.fiyat = plugin.getConfig().getDouble(uYol + ".fiyat");
                    l.stok = plugin.getConfig().getInt(uYol + ".stok");
                    s.listings.put(listingId, l);
                }
            }
            standlar.put(standId, s);
        }
    }
}
