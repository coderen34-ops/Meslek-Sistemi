package me.mesleksistemi.guvenlik;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import me.mesleksistemi.MeslekSistemi;

/**
 * Giriş sözleşme onay duvarı. Güncel sürümü kabul etmemiş oyuncu: hareket edemez, sohbet/komut/etkileşim yapamaz,
 * dokunulmazdır ve onay menüsü kapatılsa bile yeniden açılır. "Kabul Etmiyorum" veya zaman aşımı = oyundan atılır.
 * Kabul kaydı: plugins/MeslekSistemi/sozlesme-kabul.yml (UUID, ad, sürüm, tarih; IP kaydı YAPILMAZ).
 * Sürüm config'te "sozlesme.surum" (varsayılan 1.0); artırılınca herkesten yeniden onay istenir.
 * Metindeki yer tutucular config'ten gelir: sozlesme.sunucu-adi / tarih / iletisim / kvkk / saklama-suresi.
 */
public class GirisSozlesmeManager implements Listener, CommandExecutor {

    private static final int SLOT_OKU = 11, SLOT_KABUL = 13, SLOT_RED = 15;
    private static final long ZAMAN_ASIMI_TICK = 20L * 240;

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final MeslekSistemi plugin;
    private final File kayitDosyasi;
    private final YamlConfiguration kayit;
    private final Set<UUID> bekleyen = new HashSet<>();
    private final Set<UUID> okuyor = new HashSet<>();

    public GirisSozlesmeManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        plugin.getConfig().addDefault("sozlesme.aktif", true);
        plugin.getConfig().addDefault("sozlesme.surum", "1.0");
        plugin.getConfig().addDefault("sozlesme.sunucu-adi", "Koninski SMP");
        plugin.getConfig().addDefault("sozlesme.tarih", "30.10.2026");
        plugin.getConfig().addDefault("sozlesme.iletisim", "sunucu Discord'u");
        plugin.getConfig().addDefault("sozlesme.kvkk", "yöneticilere sorulabilir");
        plugin.getConfig().addDefault("sozlesme.saklama-suresi", "1 yıl");
        plugin.getConfig().options().copyDefaults(true);
        plugin.saveConfig();
        this.kayitDosyasi = new File(plugin.getDataFolder(), "sozlesme-kabul.yml");
        this.kayit = YamlConfiguration.loadConfiguration(kayitDosyasi);
    }

    private boolean aktif() { return plugin.getConfig().getBoolean("sozlesme.aktif", true); }
    private String surum() { return plugin.getConfig().getString("sozlesme.surum", "1.0"); }

    private boolean kabulEtmis(UUID id) {
        return surum().equals(kayit.getString(id + ".surum"));
    }

    // ------------------------------------------------------------------ metin
    private String c(String k) { return plugin.getConfig().getString("sozlesme." + k, ""); }

    private List<String> sayfalar() {
        String[] m = {
            "OYUNCU SÖZLEŞMESİ\nSürüm " + surum() + " - " + c("tarih") + "\n\n1. Taraflar ve Kapsam\nBu sözleşme " + c("sunucu-adi") + " ile sunucuya giren oyuncu arasındadır. \"Kabul Ediyorum\" düğmesine basarak bu metni okuduğunu, anladığını ve kabul ettiğini beyan edersin. Kabul etmeyen oyuncu sunucuya giremez.",
            "2. Sunucunun Niteliği\nSunucu, eğlence ve rol yapma amacıyla işletilen gönüllü bir topluluktur. Gerçek para karşılığı satılan bir para birimi ya da şans oyunu yoktur.\n\n3. Oyun Varlıklarının Değeri Yoktur\nOyun içi para, eşya, mülk, kimlik, meslek, klan ve tüm sanal varlıkların maddi değeri yoktur; gerçek paraya çevrilemez, iade edilemez. Sunucu bunları silebilir, sıfırlayabilir veya değiştirebilir; tazminat talep edilemez.",
            "4. Gerçek Para Ticareti (RMT) Kesinlikle Yasaktır\nOyun parasını, eşyayı, hesabı, mülkü veya mesleği gerçek para, kripto para, hediye kartı veya başka bir oyunun/hizmetin varlıkları karşılığında satmak, almak, takas etmek; bu işlemlere aracılık etmek, teklif vermek, reklam yapmak, sohbette ya da Discord'da duyurmak; başkası adına yaptırmak yasaktır.",
            "5. Bahis ve Şans Oyunları\nBahis sistemleri yalnızca oyun parasıyla oynanan sanal eğlencedir. Gerçek para karşılığı oyun parası edinip bahis oynamak veya oynatmak, bahis organize etmek yasaktır. Limitleri sunucu belirler ve değiştirebilir.\n\n6. Transfer Kuralları\nOyuncular arası para transferleri kayıt altına alınır ve kurallara tabidir. Limitleri aşmak için hesap bölmek, çoklu hesap kullanmak veya başkasına transfer yaptırmak yasaktır.",
            "7. Tespit, Kayıt ve Yaptırım\nEkonomik hareketler, transferler ve bahisler güvenlik amacıyla kayıt altına alınır ve yöneticilerce incelenebilir. İhlalde sunucu süresiz uzaklaştırma (ban) uygulayabilir, sanal varlıkları silebilir, haksız kazancı geri alabilir, ilgili diğer hesapları da cezalandırabilir. Şüphede geçici kısıtlama uygulanabilir. İtirazlar için: " + c("iletisim") + ". Nihai karar sunucu yönetimine aittir.",
            "8. Sorumluluk\nBu kuralları ihlalinden doğan her türlü hukuki ve cezai sorumluluk sana aittir. Sunucu ve yöneticileri, oyuncular arasında sunucu dışında yapılan anlaşmalardan, dolandırıcılıktan veya doğan zararlardan sorumlu değildir. Sunucu RMT'yi yasakladığını ve engellemek için önlem aldığını beyan eder. Minecraft EULA ve Mojang/Microsoft kurallarına uyacağını kabul edersin.",
            "9. Kişisel Veriler\nKabul kaydı ve güvenlik kayıtları için şunlar saklanabilir: oyuncu kimliği (UUID), kullanıcı adı, tarih/saat, ekonomi ve işlem kayıtları. Yalnızca güvenlik, kural denetimi ve ihlal incelemesi için " + c("saklama-suresi") + " boyunca saklanır. Aydınlatma metni: " + c("kvkk") + ".",
            "10. Değişiklikler ve Yürürlük\nSunucu bu sözleşmeyi güncelleyebilir. Güncellemede sürüm artar ve yeniden onay istenir; onay vermeyen oyuncu sunucuya giremez. Uyuşmazlıklarda Türk hukuku uygulanır."
        };
        List<String> l = new ArrayList<>();
        for (String s : m) l.add(s);
        return l;
    }

    private ItemStack kitap() {
        ItemStack b = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta bm = (BookMeta) b.getItemMeta();
        bm.setTitle("Sözleşme");
        bm.setAuthor(c("sunucu-adi"));
        for (String s : sayfalar()) bm.addPage(s);
        b.setItemMeta(bm);
        return b;
    }

    // ------------------------------------------------------------------ menü
    private static ItemStack esya(Material m, String ad, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ad);
        if (lore.length > 0) { List<String> l = new ArrayList<>(); for (String s : lore) l.add(s); meta.setLore(l); }
        it.setItemMeta(meta);
        return it;
    }

    private void menuAc(Player p) {
        Menu h = new Menu();
        Inventory inv = Bukkit.createInventory(h, 27, ChatColor.DARK_RED + "Oyuncu Sözleşmesi (v" + surum() + ")");
        h.inv = inv;
        inv.setItem(SLOT_OKU, esya(Material.WRITABLE_BOOK, ChatColor.YELLOW + "" + ChatColor.BOLD + "Sözleşmeyi Oku",
                ChatColor.GRAY + "Önce mutlaka oku.", ChatColor.GRAY + "RMT (gerçek para ticareti) yasaktır."));
        inv.setItem(SLOT_KABUL, esya(Material.LIME_CONCRETE, ChatColor.GREEN + "" + ChatColor.BOLD + "Kuralları Okudum, Kabul Ediyorum",
                ChatColor.GRAY + "Kabulün kaydedilir."));
        inv.setItem(SLOT_RED, esya(Material.RED_CONCRETE, ChatColor.RED + "" + ChatColor.BOLD + "Kabul Etmiyorum",
                ChatColor.GRAY + "Sunucudan atılırsın."));
        p.openInventory(inv);
    }

    private void duvarKur(Player p) {
        if (!aktif() || p.hasPermission("meslek.sozlesme.muaf")) return;
        if (kabulEtmis(p.getUniqueId())) return;
        UUID id = p.getUniqueId();
        bekleyen.add(id);
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, Integer.MAX_VALUE, 0, false, false));
        p.sendMessage(ChatColor.GOLD + "Sunucuya girmek için oyuncu sözleşmesini kabul etmelisin.");
        menuAc(p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q != null && bekleyen.contains(id)) {
                q.kickPlayer(ChatColor.RED + "Sözleşme süresi içinde onaylanmadı.\n" + ChatColor.GRAY + "Tekrar girip sözleşmeyi kabul edebilirsin.");
            }
        }, ZAMAN_ASIMI_TICK);
    }

    private void duvarKaldir(Player p) {
        bekleyen.remove(p.getUniqueId());
        p.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    private void kabulEt(Player p) {
        kayit.set(p.getUniqueId() + ".ad", p.getName());
        kayit.set(p.getUniqueId() + ".surum", surum());
        kayit.set(p.getUniqueId() + ".tarih", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        try { kayit.save(kayitDosyasi); } catch (Exception e) { plugin.getLogger().warning("Sözleşme kaydı yazılamadı: " + e.getMessage()); }
        duvarKaldir(p);
        p.closeInventory();
        p.sendMessage(ChatColor.GREEN + "Sözleşmeyi kabul ettin. İyi oyunlar!");
        plugin.ekonomiLog("sozlesme-kabul", p.getName(), "v" + surum());
    }

    // ------------------------------------------------------------------ olaylar
    @EventHandler
    public void onGiris(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) duvarKur(p); }, 20L);
    }

    @EventHandler
    public void onCikis(PlayerQuitEvent e) { bekleyen.remove(e.getPlayer().getUniqueId()); okuyor.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onTikla(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int s = e.getRawSlot();
        if (s == SLOT_OKU) {
            okuyor.add(p.getUniqueId());
            p.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                p.openBook(kitap());
                p.sendMessage(net.kyori.adventure.text.Component.text("Okuduktan sonra: ", net.kyori.adventure.text.format.NamedTextColor.GRAY)
                    .append(net.kyori.adventure.text.Component.text("[KABUL EDİYORUM] ", net.kyori.adventure.text.format.NamedTextColor.GREEN)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/kurallar kabul")))
                    .append(net.kyori.adventure.text.Component.text("[KABUL ETMİYORUM] ", net.kyori.adventure.text.format.NamedTextColor.RED)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/kurallar red")))
                    .append(net.kyori.adventure.text.Component.text("[MENÜ] ", net.kyori.adventure.text.format.NamedTextColor.YELLOW)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/kurallar"))));
            });
        } else if (s == SLOT_KABUL) {
            kabulEt(p);
        } else if (s == SLOT_RED) {
            bekleyen.remove(p.getUniqueId());
            p.kickPlayer(ChatColor.RED + "Sözleşmeyi kabul etmeyen oyuncu sunucuya giremez.");
        }
    }

    @EventHandler
    public void onSurukle(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    @EventHandler
    public void onKapat(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p) || !bekleyen.contains(p.getUniqueId())) return;
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        if (okuyor.remove(p.getUniqueId())) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && bekleyen.contains(p.getUniqueId()) && !(p.getOpenInventory().getTopInventory().getHolder() instanceof Menu)) {
                menuAc(p);
            }
        }, 20L);
    }

    @EventHandler
    public void onKomut(PlayerCommandPreprocessEvent e) {
        if (!bekleyen.contains(e.getPlayer().getUniqueId())) return;
        String m = e.getMessage().toLowerCase().split("\\s+")[0];
        if (m.equals("/kurallar") || m.equals("/oyuncusozlesmesi")) return;
        e.setCancelled(true);
        e.getPlayer().sendMessage(ChatColor.RED + "Önce sözleşmeyi kabul etmelisin: /kurallar");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSohbet(AsyncPlayerChatEvent e) {
        if (bekleyen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onHareket(PlayerMoveEvent e) {
        if (!bekleyen.contains(e.getPlayer().getUniqueId())) return;
        if (e.getTo() != null && (e.getFrom().getBlockX() != e.getTo().getBlockX() || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ())) e.setTo(e.getFrom());
    }

    @EventHandler
    public void onEtkilesim(PlayerInteractEvent e) { if (bekleyen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler
    public void onEtkilesimVarlik(PlayerInteractEntityEvent e) { if (bekleyen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler
    public void onAt(PlayerDropItemEvent e) { if (bekleyen.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }

    @EventHandler
    public void onAl(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && bekleyen.contains(p.getUniqueId())) e.setCancelled(true); }

    @EventHandler
    public void onHasar(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && bekleyen.contains(p.getUniqueId())) e.setCancelled(true); }

    // ------------------------------------------------------------------ komut
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("durum") && s.hasPermission("meslek.admin")) {
            s.sendMessage(ChatColor.YELLOW + "Sözleşme sürümü: " + surum() + " | kabul kaydı: " + kayit.getKeys(false).size()
                    + " | bekleyen: " + bekleyen.size());
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("sifirla") && s.hasPermission("meslek.admin")) {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(a[1]);
            kayit.set(op.getUniqueId().toString(), null);
            try { kayit.save(kayitDosyasi); } catch (Exception ignored) { }
            s.sendMessage(ChatColor.GREEN + a[1] + " için sözleşme kabulü silindi; girişte yeniden sorulur.");
            return true;
        }
        if (!(s instanceof Player p)) return true;
        if (a.length >= 1 && bekleyen.contains(p.getUniqueId())) {
            if (a[0].equalsIgnoreCase("kabul")) { kabulEt(p); return true; }
            if (a[0].equalsIgnoreCase("red")) { bekleyen.remove(p.getUniqueId()); p.kickPlayer(ChatColor.RED + "Sözleşmeyi kabul etmeyen oyuncu sunucuya giremez."); return true; }
        }
        if (bekleyen.contains(p.getUniqueId())) menuAc(p);
        else {
            p.sendMessage(ChatColor.GREEN + "Sözleşmeyi kabul etmişsin (v" + surum() + "). İstersen tekrar okuyabilirsin:");
            p.openBook(kitap());
        }
        return true;
    }
}
