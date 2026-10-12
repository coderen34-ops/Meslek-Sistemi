package me.mesleksistemi.sistem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import me.mesleksistemi.MeslekSistemi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

/**
 * Oyuncuların yetkilileri çağırdığı sistem.
 *
 * Oyuncu: /calladmin <sebep>  (takma adlar: /admincagir, /yetkili)
 * Yetkili (admincagri.yonet): /cagrilar  -> bekleyen çağrı menüsü
 *   Menüde sol tık = oyuncuya ışınlan, sağ tık = çözüldü olarak kapat.
 *   /cagrilar isinlan <id>, /cagrilar coz <id>  (sohbetteki butonlar bunları kullanır)
 *
 * Çağrılar plugins/MeslekSistemi/cagrilar.yml dosyasında tutulur; yetkili çevrimdışıyken de kaybolmaz,
 * girişte "N bekleyen çağrı var" uyarısı çıkar.
 *
 * Ayarlar (config.yml, hepsi isteğe bağlı):
 *   admin-cagri.bekleme-saniye: 120     aynı oyuncunun iki çağrısı arası
 *   admin-cagri.en-fazla-acik: 3        bir oyuncunun aynı anda açık çağrı sayısı
 *   admin-cagri.min-uzunluk: 5          sebep en az kaç karakter
 *   admin-cagri.max-uzunluk: 150        sebep en fazla kaç karakter
 *   admin-cagri.saklama-saat: 72        çözülmeyen çağrı kaç saat sonra otomatik silinsin
 */
public class AdminCagriManager implements Listener, CommandExecutor, TabCompleter {

    public static final String YETKI = "admincagri.yonet";

    private static class Cagri {
        final int id;
        final UUID oyuncu;
        final String ad;
        final String sebep;
        final String dunya;
        final double x, y, z;
        final float yaw, pitch;
        final long zaman;

        Cagri(int id, UUID oyuncu, String ad, String sebep, String dunya, double x, double y, double z, float yaw, float pitch, long zaman) {
            this.id = id;
            this.oyuncu = oyuncu;
            this.ad = ad;
            this.sebep = sebep;
            this.dunya = dunya;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
            this.zaman = zaman;
        }

        Location konum() {
            org.bukkit.World w = Bukkit.getWorld(dunya);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }
    }

    /** Menüyü başlık yazısıyla değil kendi tipiyle tanımak için (başka sandıkla karışmasın). */
    private static class CagriMenu implements InventoryHolder {
        final Map<Integer, Integer> slotCagri = new HashMap<>(); // slot -> çağrı id
        Inventory envanter;

        @Override
        public Inventory getInventory() { return envanter; }
    }

    private final MeslekSistemi plugin;
    private final File dosya;
    private final Map<Integer, Cagri> acik = new LinkedHashMap<>();
    private final Map<UUID, Long> sonCagri = new HashMap<>();
    private int sonId = 0;

    public AdminCagriManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "cagrilar.yml");
        yukle();
    }

    // ------------------------------------------------------------------ ayarlar
    private int bekleme() { return Math.max(0, plugin.getConfig().getInt("admin-cagri.bekleme-saniye", 120)); }
    private int enFazlaAcik() { return Math.max(1, plugin.getConfig().getInt("admin-cagri.en-fazla-acik", 3)); }
    private int minUzunluk() { return Math.max(1, plugin.getConfig().getInt("admin-cagri.min-uzunluk", 5)); }
    private int maxUzunluk() { return Math.max(minUzunluk(), plugin.getConfig().getInt("admin-cagri.max-uzunluk", 150)); }
    private long saklamaMs() { return Math.max(1, plugin.getConfig().getInt("admin-cagri.saklama-saat", 72)) * 3600_000L; }

    // ------------------------------------------------------------------ kayıt
    private void yukle() {
        acik.clear();
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        sonId = y.getInt("son-id", 0);
        ConfigurationSection kok = y.getConfigurationSection("cagrilar");
        if (kok == null) return;
        for (String anahtar : kok.getKeys(false)) {
            try {
                ConfigurationSection c = kok.getConfigurationSection(anahtar);
                if (c == null) continue;
                int id = Integer.parseInt(anahtar);
                acik.put(id, new Cagri(id, UUID.fromString(c.getString("oyuncu", "")), c.getString("ad", "?"),
                        c.getString("sebep", ""), c.getString("dunya", ""), c.getDouble("x"), c.getDouble("y"), c.getDouble("z"),
                        (float) c.getDouble("yaw"), (float) c.getDouble("pitch"), c.getLong("zaman")));
                sonId = Math.max(sonId, id);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("cagrilar.yml içinde bozuk kayıt atlandı: " + anahtar);
            }
        }
        eskileriTemizle();
    }

    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("son-id", sonId);
        for (Cagri c : acik.values()) {
            String p = "cagrilar." + c.id + ".";
            y.set(p + "oyuncu", c.oyuncu.toString());
            y.set(p + "ad", c.ad);
            y.set(p + "sebep", c.sebep);
            y.set(p + "dunya", c.dunya);
            y.set(p + "x", c.x);
            y.set(p + "y", c.y);
            y.set(p + "z", c.z);
            y.set(p + "yaw", c.yaw);
            y.set(p + "pitch", c.pitch);
            y.set(p + "zaman", c.zaman);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(dosya);
        } catch (IOException e) {
            plugin.getLogger().warning("cagrilar.yml yazılamadı: " + e.getMessage());
        }
    }

    private void eskileriTemizle() {
        long sinir = System.currentTimeMillis() - saklamaMs();
        if (acik.values().removeIf(c -> c.zaman < sinir)) kaydet();
    }

    // ------------------------------------------------------------------ komutlar
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String ad = command.getName().toLowerCase(Locale.ROOT);
        if (ad.equals("calladmin")) return cagriYap(sender, args);
        if (ad.equals("cagrilar")) return yoneticiKomutu(sender, args);
        return false;
    }

    private boolean cagriYap(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(ChatColor.RED + "Bu komut sadece oyun içinden kullanılabilir.");
            return true;
        }
        String sebep = String.join(" ", args).replace('§', ' ').trim();
        if (sebep.length() < minUzunluk()) {
            p.sendMessage(ChatColor.RED + "Kullanım: /calladmin <sebep>  " + ChatColor.GRAY + "(Neden yetkili çağırdığını kısaca yaz.)");
            return true;
        }
        if (sebep.length() > maxUzunluk()) {
            p.sendMessage(ChatColor.RED + "Sebep çok uzun! En fazla " + maxUzunluk() + " karakter yazabilirsin.");
            return true;
        }

        long simdi = System.currentTimeMillis();
        Long son = sonCagri.get(p.getUniqueId());
        if (son != null && simdi - son < bekleme() * 1000L) {
            long kalan = (bekleme() * 1000L - (simdi - son) + 999) / 1000;
            p.sendMessage(ChatColor.RED + "Çok sık çağrı yapıyorsun. " + kalan + " saniye sonra tekrar deneyebilirsin.");
            return true;
        }
        long acikSayisi = acik.values().stream().filter(c -> c.oyuncu.equals(p.getUniqueId())).count();
        if (acikSayisi >= enFazlaAcik()) {
            p.sendMessage(ChatColor.RED + "Zaten cevap bekleyen " + acikSayisi + " çağrın var. Yetkililerin ilgilenmesini bekle.");
            return true;
        }

        Location l = p.getLocation();
        Cagri c = new Cagri(++sonId, p.getUniqueId(), p.getName(), sebep, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch(), simdi);
        acik.put(c.id, c);
        sonCagri.put(p.getUniqueId(), simdi);
        kaydet();

        int yetkili = yetkililereBildir(c);
        plugin.getLogger().info("[AdminCagri] #" + c.id + " " + c.ad + ": " + c.sebep);
        if (yetkili > 0) {
            p.sendMessage(ChatColor.GREEN + "Çağrın yetkililere iletildi (#" + c.id + "). Çevrimiçi yetkili: " + yetkili);
        } else {
            p.sendMessage(ChatColor.GREEN + "Çağrın kaydedildi (#" + c.id + "). " + ChatColor.YELLOW + "Şu an çevrimiçi yetkili yok, girince çağrını görecekler.");
        }
        return true;
    }

    /** Çevrimiçi yetkililere sohbette tıklanabilir bildirim gönderir; bildirilen yetkili sayısını döner. */
    private int yetkililereBildir(Cagri c) {
        int sayi = 0;
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (!a.hasPermission(YETKI)) continue;
            sayi++;
            a.sendMessage(Component.text("[ADMİN ÇAĞRISI #" + c.id + "] ", NamedTextColor.RED, TextDecoration.BOLD)
                    .append(Component.text(c.ad, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))
                    .append(Component.text(": " + c.sebep, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false)));
            a.sendMessage(Component.text("  [IŞINLAN]", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/cagrilar isinlan " + c.id))
                    .hoverEvent(HoverEvent.showText(Component.text("Oyuncunun yanına ışınlan")))
                    .append(Component.text("  [ÇÖZÜLDÜ]", NamedTextColor.AQUA, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/cagrilar coz " + c.id))
                            .hoverEvent(HoverEvent.showText(Component.text("Çağrıyı kapat ve oyuncuya bildir"))))
                    .append(Component.text("  [LİSTE]", NamedTextColor.GOLD, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/cagrilar"))
                            .hoverEvent(HoverEvent.showText(Component.text("Tüm bekleyen çağrılar")))));
            a.playSound(a.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
        }
        return sayi;
    }

    private boolean yoneticiKomutu(CommandSender sender, String[] args) {
        if (!sender.hasPermission(YETKI)) {
            sender.sendMessage(ChatColor.RED + "Bu komutu kullanmak için yetkin yok.");
            return true;
        }
        if (!(sender instanceof Player a)) {
            sender.sendMessage(ChatColor.YELLOW + "Bekleyen çağrı sayısı: " + acik.size());
            for (Cagri c : acik.values()) sender.sendMessage("#" + c.id + " " + c.ad + ": " + c.sebep);
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("liste")) {
            menuAc(a);
            return true;
        }
        String alt = args[0].toLowerCase(Locale.ROOT);
        if ((alt.equals("isinlan") || alt.equals("coz")) && args.length >= 2) {
            int id;
            try {
                id = Integer.parseInt(args[1].replace("#", ""));
            } catch (NumberFormatException e) {
                a.sendMessage(ChatColor.RED + "Geçerli bir çağrı numarası yaz.");
                return true;
            }
            Cagri c = acik.get(id);
            if (c == null) {
                a.sendMessage(ChatColor.RED + "#" + id + " numaralı çağrı yok ya da başkası ilgilenip kapatmış.");
                return true;
            }
            if (alt.equals("isinlan")) isinlan(a, c); else coz(a, c);
            return true;
        }
        a.sendMessage(ChatColor.YELLOW + "Kullanım: /cagrilar [liste | isinlan <id> | coz <id>]");
        return true;
    }

    private void isinlan(Player a, Cagri c) {
        Player hedef = Bukkit.getPlayer(c.oyuncu);
        Location yer = hedef != null ? hedef.getLocation() : c.konum();
        if (yer == null) {
            a.sendMessage(ChatColor.RED + "Çağrının yapıldığı dünya şu an yüklü değil.");
            return;
        }
        a.teleport(yer);
        a.sendMessage(ChatColor.GREEN + c.ad + (hedef != null ? " oyuncusunun yanına" : " oyuncusunun çağrı yaptığı yere") + " ışınlandın. "
                + ChatColor.GRAY + "İşin bitince /cagrilar coz " + c.id);
    }

    private void coz(Player a, Cagri c) {
        acik.remove(c.id);
        kaydet();
        a.sendMessage(ChatColor.GREEN + "#" + c.id + " numaralı çağrı çözüldü olarak kapatıldı.");
        Player hedef = Bukkit.getPlayer(c.oyuncu);
        if (hedef != null) {
            hedef.sendMessage(ChatColor.GREEN + "Yetkili " + a.getName() + " çağrınla ilgilendi (#" + c.id + ").");
            hedef.playSound(hedef.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
        }
    }

    // ------------------------------------------------------------------ menü
    private void menuAc(Player a) {
        eskileriTemizle();
        CagriMenu sahip = new CagriMenu();
        Inventory inv = Bukkit.createInventory(sahip, 54, ChatColor.DARK_RED + "Admin Çağrıları (" + acik.size() + ")");
        sahip.envanter = inv;
        long simdi = System.currentTimeMillis();
        int slot = 0;
        for (Cagri c : acik.values()) {
            if (slot >= 45) break;
            ItemStack kafa = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) kafa.getItemMeta();
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(c.oyuncu));
            boolean cevrimici = Bukkit.getPlayer(c.oyuncu) != null;
            meta.setDisplayName(ChatColor.YELLOW + "" + ChatColor.BOLD + "#" + c.id + " " + c.ad + (cevrimici ? ChatColor.GREEN + " ●" : ChatColor.DARK_GRAY + " ○"));
            List<String> lore = new ArrayList<>();
            for (String satir : sar(c.sebep, 34)) lore.add(ChatColor.WHITE + satir);
            lore.add("");
            lore.add(ChatColor.GRAY + "Ne zaman: " + ChatColor.WHITE + sureYaz(simdi - c.zaman) + " önce");
            lore.add(ChatColor.GRAY + "Konum: " + ChatColor.WHITE + c.dunya + " " + (int) c.x + " " + (int) c.y + " " + (int) c.z);
            lore.add("");
            lore.add(ChatColor.GREEN + "► Sol tık: oyuncuya ışınlan");
            lore.add(ChatColor.AQUA + "► Sağ tık: çözüldü, kapat");
            meta.setLore(lore);
            kafa.setItemMeta(meta);
            inv.setItem(slot, kafa);
            sahip.slotCagri.put(slot, c.id);
            slot++;
        }
        if (acik.isEmpty()) {
            ItemStack bos = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
            ItemMeta m = bos.getItemMeta();
            m.setDisplayName(ChatColor.GREEN + "Bekleyen çağrı yok");
            bos.setItemMeta(m);
            inv.setItem(22, bos);
        }
        a.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CagriMenu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player a)) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        if (!a.hasPermission(YETKI)) { a.closeInventory(); return; }

        Integer id = menu.slotCagri.get(event.getRawSlot());
        if (id == null) return;
        Cagri c = acik.get(id);
        if (c == null) {
            a.sendMessage(ChatColor.RED + "Bu çağrı artık yok, liste yenileniyor.");
            menuAc(a);
            return;
        }
        ClickType t = event.getClick();
        if (t.isRightClick()) {
            coz(a, c);
            menuAc(a);
        } else if (t.isLeftClick()) {
            a.closeInventory();
            isinlan(a, c);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof CagriMenu) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ giriş
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission(YETKI)) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            eskileriTemizle();
            if (acik.isEmpty()) return;
            p.sendMessage(Component.text("[ADMİN ÇAĞRISI] ", NamedTextColor.RED, TextDecoration.BOLD)
                    .append(Component.text(acik.size() + " bekleyen çağrı var. ", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))
                    .append(Component.text("[LİSTEYİ AÇ]", NamedTextColor.GOLD, TextDecoration.BOLD)
                            .clickEvent(ClickEvent.runCommand("/cagrilar"))));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
        }, 60L);
    }

    // ------------------------------------------------------------------ tab tamamlama
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> oneri = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("cagrilar") && sender.hasPermission(YETKI)) {
            if (args.length == 1) {
                oneri.addAll(List.of("liste", "isinlan", "coz"));
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("isinlan") || args[0].equalsIgnoreCase("coz"))) {
                for (Cagri c : acik.values()) oneri.add(String.valueOf(c.id));
            }
        }
        String yazilan = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        oneri.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(yazilan));
        return oneri;
    }

    // ------------------------------------------------------------------ yardımcılar
    private static List<String> sar(String metin, int genislik) {
        List<String> satirlar = new ArrayList<>();
        StringBuilder satir = new StringBuilder();
        for (String kelime : metin.split(" ")) {
            if (satir.length() > 0 && satir.length() + 1 + kelime.length() > genislik) {
                satirlar.add(satir.toString());
                satir.setLength(0);
            }
            if (satir.length() > 0) satir.append(' ');
            satir.append(kelime);
        }
        if (satir.length() > 0) satirlar.add(satir.toString());
        return satirlar;
    }

    private static String sureYaz(long ms) {
        long dk = Math.max(0, ms / 60000);
        if (dk < 1) return "az";
        if (dk < 60) return dk + " dk";
        long saat = dk / 60;
        if (saat < 48) return saat + " sa " + (dk % 60) + " dk";
        return (saat / 24) + " gün";
    }
}
