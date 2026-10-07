package me.mesleksistemi.ekonomi;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import me.mesleksistemi.MeslekSistemi;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Kiralama akışı:
 * 1) Oyuncu Emlak NPC menüsünden eve tıklar -> ev sahibine/başkana başvuru bildirimi gider.
 * 2) Ev sahibi /kirasozlesme ile başvuranı seçer -> eline sözleşme kitabı gelir, [İMZALA] der.
 * 3) Kiracıya kitap gider -> [KABUL ET] ile ödeme alınır ve kira başlar, ya da [REDDET].
 * 4) Kiracı /kira ile sözleşmesini görür ve [KİRADAN ÇIK] ile kiracılığı bırakabilir.
 */
public class KiraSozlesmeManager implements Listener, CommandExecutor {

    private static class Sozlesme {
        final String id;
        final String evId;
        final UUID kiraci;
        final UUID hazirlayan;
        final double fiyat;
        boolean imzali;

        Sozlesme(String id, String evId, UUID kiraci, UUID hazirlayan, double fiyat) {
            this.id = id;
            this.evId = evId;
            this.kiraci = kiraci;
            this.hazirlayan = hazirlayan;
            this.fiyat = fiyat;
        }
    }

    private static final String MENU_BASLIGI = ChatColor.DARK_GREEN + "Kira Başvuruları";
    private static final int MENU_BOYUTU = 54;
    private static final String KITAP_ADI = "Kira Sözleşmesi";

    private final MeslekSistemi plugin;
    private final KiraManager kira;
    // Kitapları işaretler: "sozlesme:<id>" ya da "aktif:<evId>"
    private final NamespacedKey kitapKey;
    // Başvuru menüsündeki kafalar: "<evId>|<oyuncu uuid>"
    private final NamespacedKey basvuruKey;

    // evId -> başvuran oyuncular (sıralı)
    private final Map<String, LinkedHashSet<UUID>> basvurular = new HashMap<>();
    private final Map<String, Sozlesme> sozlesmeler = new HashMap<>();

    public KiraSozlesmeManager(MeslekSistemi plugin, KiraManager kira) {
        this.plugin = plugin;
        this.kira = kira;
        this.kitapKey = new NamespacedKey(plugin, "kira_sozlesme");
        this.basvuruKey = new NamespacedKey(plugin, "kira_basvuru");
    }

    // ------------------------------------------------------------------
    // 1) BAŞVURU
    // ------------------------------------------------------------------
    boolean basvurduMu(String evId, UUID oyuncu) {
        Set<UUID> liste = basvurular.get(evId);
        return liste != null && liste.contains(oyuncu);
    }

    void basvur(Player p, KiralikEv ev) {
        UUID pId = p.getUniqueId();
        if (ev.kiraci != null) {
            p.sendMessage(ChatColor.RED + "Bu ev halihazırda dolu!");
            return;
        }
        if (pId.equals(ev.sahip)) {
            p.sendMessage(ChatColor.RED + "Bu ev zaten size ait.");
            return;
        }
        if (basvurduMu(ev.id, pId)) {
            p.sendMessage(ChatColor.YELLOW + "Bu ev için zaten başvurdunuz, ev sahibinin sözleşme göndermesini bekleyin.");
            return;
        }

        basvurular.computeIfAbsent(ev.id, k -> new LinkedHashSet<>()).add(pId);

        boolean ulasti = kira.evYoneticilerineGonder(ev, yonetici -> {
            yonetici.showTitle(Title.title(
                    Component.text(p.getName(), NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text("dairenizle ilgileniyor!", NamedTextColor.YELLOW),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            yonetici.sendMessage(ChatColor.GOLD + "" + ChatColor.STRIKETHROUGH + "                                             ");
            yonetici.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "  🏠 " + p.getName().toUpperCase() + " DAİRENİZLE İLGİLENİYOR!");
            yonetici.sendMessage(ChatColor.YELLOW + "  Ev: " + ChatColor.WHITE + ev.isim + ChatColor.YELLOW + "  Kira: " + ChatColor.GREEN + "$" + ev.fiyat);
            yonetici.sendMessage(ChatColor.YELLOW + "  Sözleşme hazırlamak için: " + ChatColor.AQUA + "/kirasozlesme");
            yonetici.sendMessage(ChatColor.GOLD + "" + ChatColor.STRIKETHROUGH + "                                             ");
            yonetici.playSound(yonetici.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.2f);
        });

        p.sendMessage(ChatColor.GREEN + "[Emlak] " + ev.isim + " için başvurunuz " + kira.sahipAdi(ev) + " tarafına iletildi.");
        if (!ulasti) {
            p.sendMessage(ChatColor.GRAY + "Ev sahibi şu an çevrimiçi değil; oyuna girdiğinde başvurunuzu görecek.");
        }
        p.sendMessage(ChatColor.GRAY + "Ev sahibi sözleşmeyi imzalayınca size kitap olarak gelecek.");
    }

    // Ev silindi/yeniden kuruldu/kiralandı: o eve ait başvurular ve sözleşmeler geçersiz
    void evSozlesmeleriniIptalEt(String evId) {
        basvurular.remove(evId);
        sozlesmeler.values().removeIf(s -> s.evId.equals(evId));
    }

    // ------------------------------------------------------------------
    // KOMUTLAR: /kirasozlesme [imzala|kabul|reddet <id>]  ve  /kira [cik|cikonay <evId>]
    // ------------------------------------------------------------------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player p = (Player) sender;

        if (command.getName().equalsIgnoreCase("kirasozlesme")) {
            if (args.length == 0) { basvuruMenusuAc(p); return true; }
            if (args.length < 2) return false;
            switch (args[0].toLowerCase()) {
                case "imzala": imzala(p, args[1]); return true;
                case "kabul": kabulEt(p, args[1]); return true;
                case "reddet": reddet(p, args[1]); return true;
                default: return false;
            }
        }

        if (command.getName().equalsIgnoreCase("kira")) {
            if (args.length == 0) { aktifSozlesmeKitabiVer(p); return true; }
            if (args.length < 2) return false;
            switch (args[0].toLowerCase()) {
                case "cik": cikisOnayiSor(p, args[1]); return true;
                case "cikonay": kiradanCik(p, args[1]); return true;
                default: return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 2) EV SAHİBİ: başvuru menüsü -> sözleşme kitabı -> [İMZALA]
    // ------------------------------------------------------------------
    private void basvuruMenusuAc(Player p) {
        Inventory inv = Bukkit.createInventory(null, MENU_BOYUTU, MENU_BASLIGI);

        for (Map.Entry<String, LinkedHashSet<UUID>> giris : basvurular.entrySet()) {
            KiralikEv ev = kira.evler.get(giris.getKey());
            if (ev == null || ev.kiraci != null || !kira.evYoneticisiMi(p, ev)) continue;

            for (UUID basvuran : giris.getValue()) {
                if (inv.firstEmpty() == -1) break;
                OfflinePlayer op = Bukkit.getOfflinePlayer(basvuran);
                ItemStack kafa = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) kafa.getItemMeta();
                meta.setOwningPlayer(op);
                meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + (op.getName() != null ? op.getName() : "Bilinmiyor"));
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Ev: " + ChatColor.WHITE + ev.isim);
                lore.add(ChatColor.GRAY + "Kira (10 Saat): " + ChatColor.GREEN + "$" + ev.fiyat);
                lore.add(ChatColor.GRAY + "Durum: " + (op.isOnline() ? ChatColor.GREEN + "Çevrimiçi" : ChatColor.RED + "Çevrimdışı"));
                lore.add("");
                lore.add(ChatColor.YELLOW + "► Sözleşme hazırlamak için tıkla");
                meta.setLore(lore);
                meta.getPersistentDataContainer().set(basvuruKey, PersistentDataType.STRING, ev.id + "|" + basvuran);
                kafa.setItemMeta(meta);
                inv.addItem(kafa);
            }
        }

        if (inv.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Evleriniz için bekleyen bir kira başvurusu yok.");
            return;
        }
        p.openInventory(inv);
    }

    @EventHandler
    public void onBasvuruMenuClick(InventoryClickEvent event) {
        if (!event.getView().getTitle().equals(MENU_BASLIGI)) return;
        event.setCancelled(true);
        if (event.getRawSlot() < 0 || event.getRawSlot() >= MENU_BOYUTU) return;
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() != Material.PLAYER_HEAD) return;

        ItemMeta meta = item.getItemMeta();
        String veri = meta == null ? null : meta.getPersistentDataContainer().get(basvuruKey, PersistentDataType.STRING);
        if (veri == null) return;
        String[] parca = veri.split("\\|");
        Player p = (Player) event.getWhoClicked();
        p.closeInventory();

        KiralikEv ev = kira.evler.get(parca[0]);
        UUID kiraciId = UUID.fromString(parca[1]);
        if (ev == null || ev.kiraci != null || !kira.evYoneticisiMi(p, ev) || !basvurduMu(ev.id, kiraciId)) {
            p.sendMessage(ChatColor.RED + "Bu başvuru artık geçerli değil.");
            return;
        }

        // Aynı ev + kiracı için önceki taslak varsa yenisiyle değiştir (eski kitap geçersiz olur)
        sozlesmeler.values().removeIf(s -> s.evId.equals(ev.id) && s.kiraci.equals(kiraciId));
        String id = UUID.randomUUID().toString().substring(0, 8);
        Sozlesme s = new Sozlesme(id, ev.id, kiraciId, p.getUniqueId(), ev.fiyat);
        sozlesmeler.put(id, s);

        eleVer(p, sozlesmeKitabi(s, ev, false));
        p.sendMessage(ChatColor.GREEN + "[Emlak] Kira sözleşmesi elinize verildi. Kitabı açıp " + ChatColor.BOLD + "[İMZALA]" + ChatColor.GREEN + " butonuna basın.");
    }

    private void imzala(Player p, String id) {
        Sozlesme s = sozlesmeler.get(id);
        if (s == null) { p.sendMessage(ChatColor.RED + "Bu sözleşme artık geçerli değil."); kitaplariSil(p, "sozlesme:" + id); return; }
        if (!s.hazirlayan.equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Bu sözleşmeyi siz hazırlamadınız."); return; }
        if (s.imzali) { p.sendMessage(ChatColor.YELLOW + "Bu sözleşmeyi zaten imzaladınız."); return; }

        KiralikEv ev = kira.evler.get(s.evId);
        if (ev == null || ev.kiraci != null || !kira.evYoneticisiMi(p, ev)) {
            sozlesmeler.remove(id);
            kitaplariSil(p, "sozlesme:" + id);
            p.sendMessage(ChatColor.RED + "Ev artık kiralanabilir durumda değil, sözleşme iptal edildi.");
            return;
        }

        s.imzali = true;
        kitaplariSil(p, "sozlesme:" + id);
        OfflinePlayer kiraciOp = Bukkit.getOfflinePlayer(s.kiraci);
        String kiraciAdi = kiraciOp.getName() != null ? kiraciOp.getName() : "kiracı";
        p.sendMessage(ChatColor.GREEN + "[Emlak] Sözleşmeyi imzaladınız. " + kiraciAdi + " onaylayınca kira başlayacak.");

        Player kiraci = kiraciOp.getPlayer();
        if (kiraci != null) {
            kiraciyaTeslimEt(kiraci, s, ev);
        } else {
            p.sendMessage(ChatColor.GRAY + kiraciAdi + " şu an çevrimdışı; oyuna girince sözleşme kendisine teslim edilecek.");
        }
    }

    // ------------------------------------------------------------------
    // 3) KİRACI: [KABUL ET] / [REDDET]
    // ------------------------------------------------------------------
    private void kiraciyaTeslimEt(Player kiraci, Sozlesme s, KiralikEv ev) {
        eleVer(kiraci, sozlesmeKitabi(s, ev, true));
        kiraci.showTitle(Title.title(
                Component.text("Kira Sözleşmesi", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(ev.isim + " için imzalandı", NamedTextColor.YELLOW)));
        kiraci.sendMessage(ChatColor.GOLD + "[Emlak] " + kira.sahipAdi(ev) + " " + ev.isim
                + " için kira sözleşmesini imzaladı! Elinizdeki kitabı açıp şartları kabul edin ya da reddedin.");
        kiraci.playSound(kiraci.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0f, 1.0f);
    }

    private void kabulEt(Player p, String id) {
        Sozlesme s = sozlesmeler.get(id);
        if (s == null || !s.imzali) { p.sendMessage(ChatColor.RED + "Bu sözleşme artık geçerli değil."); kitaplariSil(p, "sozlesme:" + id); return; }
        if (!s.kiraci.equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Bu sözleşme size ait değil."); return; }

        KiralikEv ev = kira.evler.get(s.evId);
        String hata = ev == null ? "Bu ev artık kiralık listesinde değil." : kira.kiraBaslat(ev, p, s.fiyat);
        if (hata != null) {
            p.sendMessage(ChatColor.RED + "[Emlak] " + hata);
            // Para yetmiyorsa sözleşme durur, bakiye yatırıp tekrar kabul edebilir. Diğer hatalarda iptal.
            if (ev == null || ev.kiraci != null || ev.fiyat != s.fiyat) {
                sozlesmeler.remove(id);
                kitaplariSil(p, "sozlesme:" + id);
            }
            return;
        }

        kitaplariSil(p, "sozlesme:" + id);
        evSozlesmeleriniIptalEt(ev.id);

        p.sendMessage(ChatColor.GREEN + "Tebrikler! " + ChatColor.GOLD + ev.isim + ChatColor.GREEN + " adlı evi kiraladınız. ($" + s.fiyat + " ödendi)");
        p.sendMessage(ChatColor.YELLOW + "Kira süresi sadece oyundayken akar; her 10 saatte bir banka hesabınızdan otomatik çekilir.");
        p.sendMessage(ChatColor.GRAY + "Sözleşmenizi görmek veya kiradan çıkmak için: " + ChatColor.AQUA + "/kira");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);

        kira.evYoneticilerineGonder(ev, y -> y.sendMessage(ChatColor.GREEN + "[Emlak] " + p.getName() + " " + ev.isim + " kira sözleşmesini kabul etti!"));
    }

    private void reddet(Player p, String id) {
        Sozlesme s = sozlesmeler.get(id);
        if (s == null) { p.sendMessage(ChatColor.RED + "Bu sözleşme artık geçerli değil."); kitaplariSil(p, "sozlesme:" + id); return; }
        if (!s.kiraci.equals(p.getUniqueId())) { p.sendMessage(ChatColor.RED + "Bu sözleşme size ait değil."); return; }

        sozlesmeler.remove(id);
        Set<UUID> liste = basvurular.get(s.evId);
        if (liste != null) liste.remove(p.getUniqueId());
        kitaplariSil(p, "sozlesme:" + id);
        p.sendMessage(ChatColor.YELLOW + "[Emlak] Kira sözleşmesini reddettiniz.");

        KiralikEv ev = kira.evler.get(s.evId);
        if (ev != null) {
            kira.evYoneticilerineGonder(ev, y -> y.sendMessage(ChatColor.RED + "[Emlak] " + p.getName() + " " + ev.isim + " kira sözleşmesini reddetti."));
        }
    }

    // ------------------------------------------------------------------
    // 4) /kira: aktif sözleşme kitabı ve kiradan çıkma
    // ------------------------------------------------------------------
    private void aktifSozlesmeKitabiVer(Player p) {
        boolean bulundu = false;
        for (KiralikEv ev : kira.evler.values()) {
            if (!p.getUniqueId().equals(ev.kiraci)) continue;
            kitaplariSil(p, "aktif:" + ev.id); // Eski kopya varsa güncel olanla değiştir
            eleVer(p, aktifSozlesmeKitabi(ev));
            bulundu = true;
        }
        if (!bulundu) {
            p.sendMessage(ChatColor.RED + "Şu an kiracı olduğunuz bir ev yok.");
            return;
        }
        p.sendMessage(ChatColor.GREEN + "[Emlak] Kira sözleşmeniz envanterinize eklendi.");
    }

    private void cikisOnayiSor(Player p, String evId) {
        KiralikEv ev = kira.evler.get(evId);
        if (ev == null || !p.getUniqueId().equals(ev.kiraci)) {
            p.sendMessage(ChatColor.RED + "Bu evin kiracısı değilsiniz.");
            kitaplariSil(p, "aktif:" + evId);
            return;
        }
        p.sendMessage(Component.text("[Emlak] ", NamedTextColor.GOLD)
                .append(Component.text(ev.isim + " kiracılığını bırakmak istediğinize emin misiniz? Kalan süre ("
                        + KiraManager.sureMetni(ev.kalanSureMs) + ") iade edilmez. ", NamedTextColor.YELLOW))
                .append(Component.text("[EVET, ÇIK]", NamedTextColor.RED, TextDecoration.BOLD)
                        .clickEvent(ClickEvent.runCommand("/kira cikonay " + ev.id))
                        .hoverEvent(HoverEvent.showText(Component.text("Kiracılığı hemen sonlandır", NamedTextColor.RED)))));
    }

    private void kiradanCik(Player p, String evId) {
        KiralikEv ev = kira.evler.get(evId);
        if (ev == null || !p.getUniqueId().equals(ev.kiraci)) {
            p.sendMessage(ChatColor.RED + "Bu evin kiracısı değilsiniz.");
            return;
        }
        if (!kira.kiracilikBitir(ev)) {
            p.sendMessage(ChatColor.RED + "Şu an işlem yapılamıyor (evin dünyası yüklü değil). Lütfen yetkililere bildirin.");
            return;
        }
        kitaplariSil(p, "aktif:" + evId);
        p.sendMessage(ChatColor.YELLOW + "[Emlak] " + ev.isim + " kiracılığınız sona erdi. Kira artık hesabınızdan çekilmeyecek.");
        kira.evYoneticilerineGonder(ev, y -> y.sendMessage(ChatColor.YELLOW + "[Emlak] " + p.getName() + " " + ev.isim + " evinden çıktı, ev tekrar boşta."));
    }

    // ------------------------------------------------------------------
    // GİRİŞ: bekleyen başvuruları hatırlat, imzalı sözleşmeyi teslim et
    // ------------------------------------------------------------------
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Diğer giriş mesajlarının arasında kaybolmasın
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;

            for (Sozlesme s : sozlesmeler.values()) {
                if (!s.imzali || !s.kiraci.equals(p.getUniqueId()) || kitapVarMi(p, "sozlesme:" + s.id)) continue;
                KiralikEv ev = kira.evler.get(s.evId);
                if (ev != null && ev.kiraci == null) kiraciyaTeslimEt(p, s, ev);
            }

            int bekleyen = 0;
            for (Map.Entry<String, LinkedHashSet<UUID>> giris : basvurular.entrySet()) {
                KiralikEv ev = kira.evler.get(giris.getKey());
                if (ev != null && ev.kiraci == null && kira.evYoneticisiMi(p, ev)) bekleyen += giris.getValue().size();
            }
            if (bekleyen > 0) {
                p.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "[Emlak] Evleriniz için " + bekleyen + " bekleyen kira başvurusu var! "
                        + ChatColor.AQUA + "/kirasozlesme");
            }
        }, 60L);
    }

    // ------------------------------------------------------------------
    // KİTAPLAR
    // ------------------------------------------------------------------
    private ItemStack sozlesmeKitabi(Sozlesme s, KiralikEv ev, boolean kiraciNushasi) {
        OfflinePlayer kiraciOp = Bukkit.getOfflinePlayer(s.kiraci);
        String kiraciAdi = kiraciOp.getName() != null ? kiraciOp.getName() : "?";

        Component sayfa1 = Component.text()
                .append(Component.text("KİRA SÖZLEŞMESİ\n\n", NamedTextColor.DARK_BLUE, TextDecoration.BOLD))
                .append(satir("Daire", ev.isim))
                .append(satir("Ev Sahibi", kira.sahipAdi(ev)))
                .append(satir("Kiracı", kiraciAdi))
                .append(satir("Kira", "$" + s.fiyat))
                .append(satir("Dönem", "10 saat (oyunda)"))
                .append(satir("Konum", ev.dunya + " " + ev.loc.getBlockX() + ", " + ev.loc.getBlockY() + ", " + ev.loc.getBlockZ()))
                .append(Component.text("\nNo: " + s.id, NamedTextColor.GRAY))
                .build();

        Component sayfa2 = Component.text()
                .append(Component.text("ŞARTLAR\n\n", NamedTextColor.DARK_BLUE, TextDecoration.BOLD))
                .append(Component.text("1. Kira peşin, bankadan alınır.\n", NamedTextColor.BLACK))
                .append(Component.text("2. Süre sadece oyundayken işler.\n", NamedTextColor.BLACK))
                .append(Component.text("3. Süre bitince kira yenilenir; para yetmezse haciz.\n", NamedTextColor.BLACK))
                .append(Component.text("4. /kira ile çıkılır, iade yok.\n", NamedTextColor.BLACK))
                .append(Component.text("5. 7 gün girmeyenin sözleşmesi biter.", NamedTextColor.BLACK))
                .build();

        // Butonlar ayrı sayfada: sayfa 14 satırı aşarsa taşan kısım (butonlar) görünmez
        TextComponent.Builder sayfa3 = Component.text()
                .append(Component.text("ONAY\n\n", NamedTextColor.DARK_BLUE, TextDecoration.BOLD));
        if (!kiraciNushasi) {
            sayfa3.append(Component.text("Şartları okudum, sözleşmeyi imzalıyorum.\n\n", NamedTextColor.BLACK))
                    .append(buton("[İMZALA]", NamedTextColor.DARK_GREEN, "/kirasozlesme imzala " + s.id, "Sözleşmeyi imzala ve kiracıya gönder"));
        } else {
            sayfa3.append(Component.text("Ev sahibi imzaladı.\n\n", NamedTextColor.DARK_GREEN, TextDecoration.ITALIC))
                    .append(buton("[KABUL ET]", NamedTextColor.DARK_GREEN, "/kirasozlesme kabul " + s.id, "$" + s.fiyat + " ödenir ve kira başlar"))
                    .append(Component.text("\n\n"))
                    .append(buton("[REDDET]", NamedTextColor.DARK_RED, "/kirasozlesme reddet " + s.id, "Sözleşmeyi reddet"));
        }

        return kitapOlustur(kiraciNushasi ? kira.sahipAdi(ev) : kiraciAdi, "sozlesme:" + s.id, sayfa1, sayfa2, sayfa3.build());
    }

    private ItemStack aktifSozlesmeKitabi(KiralikEv ev) {
        Component sayfa1 = Component.text()
                .append(Component.text("KİRA SÖZLEŞMESİ\n", NamedTextColor.DARK_BLUE, TextDecoration.BOLD))
                .append(Component.text("(Yürürlükte)\n\n", NamedTextColor.DARK_GREEN))
                .append(satir("Daire", ev.isim))
                .append(satir("Ev Sahibi", kira.sahipAdi(ev)))
                .append(satir("Kira", "$" + ev.fiyat + " / 10 saat"))
                .append(satir("Kalan Süre", KiraManager.sureMetni(ev.kalanSureMs)))
                .append(ev.kaldirilacak
                        ? Component.text("\nEv sahibi süre bitince sözleşmeyi sonlandıracak.", NamedTextColor.DARK_RED)
                        : Component.empty())
                .build();

        Component sayfa2 = Component.text()
                .append(Component.text("KİRADAN ÇIKIŞ\n\n", NamedTextColor.DARK_BLUE, TextDecoration.BOLD))
                .append(Component.text("Kira döngüsünden çıkarsanız evdeki yetkileriniz kaldırılır ve kalan süre iade edilmez.\n\n", NamedTextColor.BLACK))
                .append(buton("[KİRADAN ÇIK]", NamedTextColor.DARK_RED, "/kira cik " + ev.id, "Kiracılığı sonlandır"))
                .build();

        return kitapOlustur(kira.sahipAdi(ev), "aktif:" + ev.id, sayfa1, sayfa2);
    }

    private Component satir(String etiket, String deger) {
        return Component.text(etiket + ": ", NamedTextColor.DARK_GRAY)
                .append(Component.text(deger + "\n", NamedTextColor.BLACK));
    }

    private Component buton(String yazi, NamedTextColor renk, String komut, String aciklama) {
        return Component.text(yazi, renk, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(komut))
                .hoverEvent(HoverEvent.showText(Component.text(aciklama)));
    }

    private ItemStack kitapOlustur(String yazar, String isaret, Component... sayfalar) {
        ItemStack kitap = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) kitap.getItemMeta();
        meta.setTitle(KITAP_ADI);
        meta.setAuthor(yazar);
        meta.addPages(sayfalar);
        meta.getPersistentDataContainer().set(kitapKey, PersistentDataType.STRING, isaret);
        kitap.setItemMeta(meta);
        return kitap;
    }

    // Eli boşsa doğrudan eline, değilse envantere; envanter doluysa ayağının dibine
    private void eleVer(Player p, ItemStack item) {
        PlayerInventory inv = p.getInventory();
        if (inv.getItemInMainHand().getType() == Material.AIR) {
            inv.setItemInMainHand(item);
            return;
        }
        for (ItemStack artan : inv.addItem(item).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), artan);
        }
    }

    private boolean kitapVarMi(Player p, String isaret) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (kitapIsareti(item, isaret)) return true;
        }
        return false;
    }

    private void kitaplariSil(Player p, String isaret) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (kitapIsareti(inv.getItem(i), isaret)) inv.setItem(i, null);
        }
    }

    private boolean kitapIsareti(ItemStack item, String isaret) {
        if (item == null || item.getType() != Material.WRITTEN_BOOK || !item.hasItemMeta()) return false;
        return isaret.equals(item.getItemMeta().getPersistentDataContainer().get(kitapKey, PersistentDataType.STRING));
    }
}
