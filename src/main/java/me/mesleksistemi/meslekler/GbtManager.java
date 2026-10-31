package me.mesleksistemi.meslekler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import me.mesleksistemi.MeslekSistemi;
import me.mesleksistemi.kacak.KacakMal;

/**
 * Polis GBT kontrolü ve üst arama.
 *  - Polis/amir, başka bir oyuncuya boş elle sağ tıklayınca GBT menüsü açılır (EtkilesimManager yönlendirir).
 *  - GBT menüsünde "Üst Arama" butonu: hedefin envanteri SALT OKUNUR gösterilir, polis hiçbir eşyayı alamaz.
 *  - "Kaçak Mal Ara/Müsadere": sadece kaçak mal etiketli eşyalar alınır; varsa oyuncu 10 dk hapse girer ve 5000$ ceza
 *    kesilir (parası yoksa banka hesabı EKSİYE düşer). Tüm işlem tek senkron blokta yapılır (atomik), loglanır.
 */
public class GbtManager implements Listener {

    private static final int MESAFE = 6;                 // blok
    private static final long BEKLEME_MS = 3000L;        // polis başına GBT/arama bekleme
    private static final int HAPIS_SANIYE = 600;         // 10 dakika
    private static final double CEZA = 5000.0;

    private enum Tip { GBT, ARAMA }

    private static final class Menu implements InventoryHolder {
        final Tip tip; final UUID hedef; Inventory envanter;
        Menu(Tip tip, UUID hedef) { this.tip = tip; this.hedef = hedef; }
        @Override public Inventory getInventory() { return envanter; }
    }

    private final MeslekSistemi plugin;
    private final Map<UUID, Long> sonIslem = new HashMap<>();

    /** GBT/üst arama süresince hareketsiz bırakılan hedef -> işlemi yapan polis. */
    private final Map<UUID, UUID> donuk = new HashMap<>();

    public GbtManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        // Emniyet: polis menüyü kapattıysa/çıktıysa/hedef için başka menü açık değilse çöz
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (java.util.Iterator<Map.Entry<UUID, UUID>> it = donuk.entrySet().iterator(); it.hasNext();) {
                Map.Entry<UUID, UUID> en = it.next();
                Player polis = Bukkit.getPlayer(en.getValue());
                Player hedef = Bukkit.getPlayer(en.getKey());
                if (polis == null || hedef == null || !menuAcikMi(polis, en.getKey())) {
                    it.remove();
                    if (hedef != null) hedef.sendMessage(ChatColor.GRAY + "Kontrol bitti, hareket edebilirsin.");
                }
            }
        }, 20L, 10L);
    }

    private boolean menuAcikMi(Player polis, UUID hedef) {
        return polis.getOpenInventory().getTopInventory().getHolder() instanceof Menu m && m.hedef.equals(hedef);
    }

    private void dondur(Player polis, Player hedef) {
        if (donuk.put(hedef.getUniqueId(), polis.getUniqueId()) == null)
            hedef.sendMessage(ChatColor.GRAY + "Polis kontrolü bitene kadar hareket edemezsin.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onHareket(PlayerMoveEvent e) {
        if (donuk.isEmpty() || !donuk.containsKey(e.getPlayer().getUniqueId())) return;
        org.bukkit.Location a = e.getFrom(), b = e.getTo();
        if (b == null) return;
        if (a.getX() != b.getX() || a.getY() != b.getY() || a.getZ() != b.getZ()) {
            b.setX(a.getX()); b.setY(a.getY()); b.setZ(a.getZ());
            e.setTo(b); // bakış yönü serbest, konum sabit
        }
    }

    @EventHandler
    public void onKapat(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu) || !(e.getPlayer() instanceof Player polis)) return;
        // GBT <-> arama geçişinde yeniden açılacağı için bir tick sonra kontrol et
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!menuAcikMi(polis, menu.hedef)) {
                if (donuk.remove(menu.hedef) != null) {
                    Player h = Bukkit.getPlayer(menu.hedef);
                    if (h != null) h.sendMessage(ChatColor.GRAY + "Kontrol bitti, hareket edebilirsin.");
                }
            }
        });
    }

    @EventHandler
    public void onCikis(PlayerQuitEvent e) { donuk.remove(e.getPlayer().getUniqueId()); }


    private String meslekOf(Player p) { return plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas"); }

    public boolean polisMi(Player p) {
        String m = meslekOf(p);
        return m.equalsIgnoreCase("polis") || m.equalsIgnoreCase("amir");
    }

    private boolean yakin(Player a, Player b) {
        return a.getWorld().equals(b.getWorld()) && a.getLocation().distanceSquared(b.getLocation()) <= (double) MESAFE * MESAFE;
    }

    // ------------------------------------------------------------------ GBT MENÜSÜ
    public void gbtAc(Player polis, Player hedef) {
        if (!polisMi(polis)) return;
        if (polis.getUniqueId().equals(hedef.getUniqueId())) return;
        if (!yakin(polis, hedef)) { polis.sendMessage(ChatColor.RED + "Oyuncu çok uzakta."); return; }
        long simdi = System.currentTimeMillis();
        Long son = sonIslem.get(polis.getUniqueId());
        if (son != null && simdi - son < BEKLEME_MS) { polis.sendMessage(ChatColor.RED + "Biraz bekle, çok sık GBT yapıyorsun."); return; }
        sonIslem.put(polis.getUniqueId(), simdi);

        Menu holder = new Menu(Tip.GBT, hedef.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 27, ChatColor.DARK_BLUE + "GBT: " + hedef.getName());
        holder.envanter = inv;

        ItemStack kafa = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta sm = (SkullMeta) kafa.getItemMeta();
        sm.setOwningPlayer(hedef);
        sm.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + hedef.getName());
        kafa.setItemMeta(sm);
        inv.setItem(4, kafa);

        String meslek = meslekOf(hedef);
        boolean gocmen = meslek.equalsIgnoreCase("gocmen") || meslek.equalsIgnoreCase("default");
        boolean aranan = plugin.polisManager != null && plugin.polisManager.arananOyuncular.contains(hedef.getName().toLowerCase(Locale.ROOT));
        boolean hapis = plugin.polisManager != null && plugin.polisManager.jailedPlayers.containsKey(hedef.getUniqueId());
        int sabika = sabikaSayisi(hedef.getUniqueId());

        inv.setItem(10, esya(gocmen ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                ChatColor.YELLOW + "Statü: " + (gocmen ? ChatColor.RED + "Kayıtsız / Göçmen" : ChatColor.GREEN + "Vatandaş"),
                ChatColor.GRAY + "Meslek: " + ChatColor.WHITE + meslek));
        inv.setItem(12, esya(aranan ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                ChatColor.YELLOW + "Aranıyor mu: " + (aranan ? ChatColor.RED + "EVET" : ChatColor.GREEN + "Hayır")));
        inv.setItem(14, esya(Material.PAPER, ChatColor.YELLOW + "Sabıka kaydı: " + ChatColor.WHITE + sabika,
                ChatColor.GRAY + "Kaçak mal müsaderesi sayısı"));
        inv.setItem(16, esya(hapis ? Material.IRON_BARS : Material.OAK_DOOR,
                ChatColor.YELLOW + "Hapis: " + (hapis ? ChatColor.RED + "Şu an hapiste" : ChatColor.GREEN + "Değil")));
        inv.setItem(22, esya(Material.CHEST, ChatColor.GOLD + "" + ChatColor.BOLD + "Üst Arama",
                ChatColor.GRAY + "Oyuncunun üstünü ara.",
                ChatColor.GRAY + "Eşyaları alamazsın; sadece kaçak mal müsadere edilir."));
        inv.setItem(26, esya(Material.BOOK, ChatColor.AQUA + "Oyuncu Menüsü", ChatColor.GRAY + "Beğeni/yorum/para gönder menüsü."));

        polis.openInventory(inv);
        dondur(polis, hedef);
        hedef.sendMessage(ChatColor.GRAY + "Bir polis üzerinde GBT kontrolü yapıyor.");
        plugin.ekonomiLog("gbt", polis.getName(), "GBT: " + hedef.getName());
    }

    // ------------------------------------------------------------------ ÜST ARAMA
    private void aramaAc(Player polis, Player hedef) {
        if (!yakin(polis, hedef)) { polis.closeInventory(); polis.sendMessage(ChatColor.RED + "Oyuncu çok uzakta."); return; }
        Menu holder = new Menu(Tip.ARAMA, hedef.getUniqueId());
        Inventory inv = Bukkit.createInventory(holder, 54, ChatColor.DARK_RED + "Üst Arama: " + hedef.getName());
        holder.envanter = inv;
        PlayerInventory pi = hedef.getInventory();
        ItemStack[] depo = pi.getStorageContents();           // 36 slot
        for (int i = 0; i < depo.length && i < 36; i++) if (depo[i] != null) inv.setItem(i, depo[i].clone());
        ItemStack[] zirh = pi.getArmorContents();
        for (int i = 0; i < zirh.length; i++) if (zirh[i] != null) inv.setItem(36 + i, zirh[i].clone());
        if (pi.getItemInOffHand().getType() != Material.AIR) inv.setItem(40, pi.getItemInOffHand().clone());
        for (int i = 41; i < 45; i++) inv.setItem(i, esya(Material.GRAY_STAINED_GLASS_PANE, " "));
        inv.setItem(49, esya(Material.TRIPWIRE_HOOK, ChatColor.RED + "" + ChatColor.BOLD + "Kaçak Mal Müsadere Et",
                ChatColor.GRAY + "Üstünde (zırh dahil) kaçak mal ya da üretim eşyası varsa el konur,",
                ChatColor.GRAY + "oyuncu 10 dk hapse girer ve $5000 ceza keser.",
                ChatColor.DARK_GRAY + "Başka eşya alınamaz."));
        inv.setItem(45, esya(Material.ARROW, ChatColor.YELLOW + "Geri (GBT)"));
        polis.openInventory(inv);
        dondur(polis, hedef);
        hedef.sendMessage(ChatColor.GRAY + "Bir polis üstünü arıyor.");
        plugin.ekonomiLog("ust-arama", polis.getName(), "Üst arama: " + hedef.getName());
    }

    @EventHandler
    public void onTikla(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player polis)) return;
        if (!polisMi(polis)) { polis.closeInventory(); return; }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;
        Player hedef = Bukkit.getPlayer(menu.hedef);
        if (hedef == null) { polis.closeInventory(); polis.sendMessage(ChatColor.RED + "Oyuncu artık oyunda değil."); return; }

        if (menu.tip == Tip.GBT) {
            if (slot == 22) aramaAc(polis, hedef);
            else if (slot == 26 && plugin.etkilesimManager != null) { polis.closeInventory(); plugin.etkilesimManager.menuAc(polis, menu.hedef); }
        } else {
            if (slot == 45) { sonIslem.remove(polis.getUniqueId()); gbtAc(polis, hedef); }
            else if (slot == 49) musadere(polis, hedef);
        }
    }

    @EventHandler
    public void onSurukle(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ MÜSADERE (atomik, tek senkron blok)
    private void musadere(Player polis, Player hedef) {
        polis.closeInventory();
        if (!polisMi(polis)) return;
        if (!yakin(polis, hedef)) { polis.sendMessage(ChatColor.RED + "Oyuncu çok uzakta."); return; }
        if (plugin.polisManager == null || plugin.polisManager.jailedPlayers.containsKey(hedef.getUniqueId())) {
            polis.sendMessage(ChatColor.RED + "Bu oyuncu zaten hapiste."); return;
        }
        if (plugin.polisManager.jailCells.isEmpty()) {
            polis.sendMessage(ChatColor.RED + "Hapishane hücresi ayarlı değil, işlem yapılamadı."); return;
        }

        // 1) Hedefte kaçak mal var mı? Önce say, sonra (varsa) kaldır - hepsi aynı tikte.
        PlayerInventory pi = hedef.getInventory();
        int toplam = 0;
        StringBuilder ozet = new StringBuilder();
        ItemStack[] icerik = pi.getContents();
        for (ItemStack it : icerik) {
            String ad = kacakAdi(it);
            if (ad == null) continue;
            toplam += it.getAmount();
            ozet.append(it.getAmount()).append("x ").append(ad).append(' ');
        }
        if (toplam == 0) {
            polis.sendMessage(ChatColor.YELLOW + "Üstünde kaçak mal bulunamadı.");
            return;
        }
        for (int i = 0; i < icerik.length; i++) {
            if (kacakAdi(icerik[i]) != null) pi.setItem(i, null);
        }
        hedef.updateInventory();

        cezaKes(polis, hedef, ozet.toString().trim());
    }

    /** Kaçak mal (Kara Toz vb.) ya da kaçak üretim eşyası (kazan, malzemeler, koruyucu maske; zırh/elde dahil) ise adı, değilse null. */
    private String kacakAdi(ItemStack it) {
        if (it == null || it.getType() == Material.AIR) return null;
        KacakMal.Tur t = KacakMal.turu(plugin, it);
        if (t != null) return t.ad;
        if (!it.hasItemMeta()) return null;
        String id = it.getItemMeta().getPersistentDataContainer().get(new org.bukkit.NamespacedKey(plugin, "kacak_esya"), org.bukkit.persistence.PersistentDataType.STRING);
        if (id == null) return null;
        switch (id) {
            case "kazan": return "Gizli Kazan";
            case "golge_koku": return "Gölge Kökü";
            case "kizil_kul": return "Kızıl Kül";
            case "mavi_tuz": return "Mavi Tuz";
            case "maske": return "Koruyucu Maske";
            default: return "kaçak eşya";
        }
    }

    /** Kaçak mal cezası (GBT müsaderesi ve kazan baskını ortak): $5000 ceza (banka eksiye düşebilir), 10 dk hapis, sabıka, log. */
    public void cezaKes(Player polis, Player hedef, String ozet) {
        // 2) Ceza: parası yoksa banka hesabı eksiye düşer. Ceza belediye kasasına yazılır.
        UUID id = hedef.getUniqueId();
        double eski = plugin.bankaHesaplari.getOrDefault(id, 0.0);
        double yeni = Math.round((eski - CEZA) * 100.0) / 100.0;
        plugin.bankaHesaplari.put(id, yeni);
        plugin.kasayaParaEkle(CEZA);

        // 3) Sabıka kaydı ve hapis
        sabikaArttir(id);
        if (plugin.polisManager != null) plugin.polisManager.hapseGonder(id, hedef.getName(), HAPIS_SANIYE);
        plugin.veriKaydet();

        String polisAd = polis == null ? "baskın" : polis.getName();
        plugin.ekonomiLog("kacak-mal-musadere", polisAd, hedef.getName() + " | el konan: " + ozet
                + " | ceza $" + (long) CEZA + " | banka " + eski + " -> " + yeni + " | hapis 10 dk");
        if (polis != null) {
            polis.sendMessage(ChatColor.GREEN + hedef.getName() + " üzerinde kaçak mal bulundu: " + ozet + ". Müsadere edildi, 10 dk hapse atıldı, $5000 ceza kesildi.");
            polis.playSound(polis.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.2f);
        }
        hedef.sendMessage(ChatColor.DARK_RED + "Kaçak mal yüzünden yakalandın! Müsadere edildi, $" + (long) CEZA + " ceza kesildi"
                + (yeni < 0 ? ChatColor.RED + " (hesabın eksiye düştü: $" + String.format(Locale.US, "%.2f", yeni) + ")" : "") + ".");
    }

    // ------------------------------------------------------------------ SABIKA
    public int sabikaSayisi(UUID id) { return plugin.getConfig().getInt("sabika." + id, 0); }

    private void sabikaArttir(UUID id) {
        plugin.getConfig().set("sabika." + id, sabikaSayisi(id) + 1);
        plugin.saveConfig();
    }

    // ------------------------------------------------------------------ yardımcı
    private static ItemStack esya(Material m, String ad, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        if (lore.length > 0) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(s); meta.setLore(l); }
        it.setItemMeta(meta);
        return it;
    }
}
