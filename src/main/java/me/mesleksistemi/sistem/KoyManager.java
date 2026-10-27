package me.mesleksistemi.sistem;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Köy sınırı ve vatandaşlıktan çıkarma.
 *
 * Yetkili: /koyayarla pos1 | pos2 | bilgi | sil   (izin: koy.admin)
 *   Köy, pos1 ile pos2 arasındaki dikdörtgen alandır (yükseklik önemsizdir, sadece X ve Z).
 *
 * Belediye başkanı bir oyuncuyu vatandaşlıktan çıkarınca oyuncu "gocmen" olur ve köyün hemen
 * dışına, kendisine en yakın kenardan ışınlanır. Sınırlar tanımlı değilse oyuncu olduğu yerde kalır.
 *
 * Konumlar plugins/MeslekSistemi/koy.yml dosyasında tutulur.
 */
public class KoyManager implements CommandExecutor, TabCompleter, Listener {

    public static final String YETKI = "koy.admin";

    // Göçmen köye girmeye çalıştığında art arda spam mesaj/uyarı atmamak için kısa bir bekleme.
    private static final long UYARI_COOLDOWN_MS = 10_000L;

    private final MeslekSistemi plugin;
    private final File dosya;
    private Location pos1;
    private Location pos2;
    private final java.util.Map<java.util.UUID, Long> sonUyariZamani = new java.util.HashMap<>();

    public KoyManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.dosya = new File(plugin.getDataFolder(), "koy.yml");
        yukle();
    }

    // ------------------------------------------------------------------ kayıt
    private void yukle() {
        pos1 = null;
        pos2 = null;
        if (!dosya.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dosya);
        pos1 = oku(y, "pos1");
        pos2 = oku(y, "pos2");
    }

    private Location oku(YamlConfiguration y, String yol) {
        String dunya = y.getString(yol + ".dunya");
        if (dunya == null) return null;
        World w = Bukkit.getWorld(dunya);
        if (w == null) return null;
        return new Location(w, y.getDouble(yol + ".x"), y.getDouble(yol + ".y"), y.getDouble(yol + ".z"));
    }

    private void yaz(YamlConfiguration y, String yol, Location l) {
        if (l == null || l.getWorld() == null) return;
        y.set(yol + ".dunya", l.getWorld().getName());
        y.set(yol + ".x", l.getX());
        y.set(yol + ".y", l.getY());
        y.set(yol + ".z", l.getZ());
    }

    private void kaydet() {
        YamlConfiguration y = new YamlConfiguration();
        yaz(y, "pos1", pos1);
        yaz(y, "pos2", pos2);
        try {
            plugin.getDataFolder().mkdirs();
            y.save(dosya);
        } catch (IOException e) {
            plugin.getLogger().warning("koy.yml yazılamadı: " + e.getMessage());
        }
    }

    public boolean sinirHazirMi() {
        return pos1 != null && pos2 != null && pos1.getWorld() != null && pos1.getWorld().equals(pos2.getWorld());
    }

    // ------------------------------------------------------------------ komut
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(YETKI)) {
            sender.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkin yok.");
            return true;
        }
        String alt = args.length == 0 ? "bilgi" : args[0].toLowerCase(Locale.ROOT);

        if (alt.equals("pos1") || alt.equals("pos2")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("Bu komut oyun içinden kullanılır.");
                return true;
            }
            Player p = (Player) sender;
            if (alt.equals("pos1")) pos1 = p.getLocation().clone();
            else pos2 = p.getLocation().clone();
            kaydet();
            p.sendMessage(ChatColor.GREEN + "Köy " + (alt.equals("pos1") ? "1." : "2.") + " köşesi (" + alt + ") kaydedildi: "
                    + ChatColor.YELLOW + p.getLocation().getBlockX() + ", " + p.getLocation().getBlockZ());
            if (pos1 != null && pos2 != null && !sinirHazirMi()) {
                p.sendMessage(ChatColor.RED + "Dikkat: pos1 ve pos2 farklı dünyalarda, köy sınırı çalışmaz.");
            }
            return true;
        }
        if (alt.equals("sil")) {
            pos1 = null;
            pos2 = null;
            kaydet();
            sender.sendMessage(ChatColor.GREEN + "Köy sınırı silindi.");
            return true;
        }
        // bilgi
        if (pos1 == null && pos2 == null) {
            sender.sendMessage(ChatColor.YELLOW + "Köy sınırı henüz ayarlanmadı. /koyayarla pos1 ve /koyayarla pos2 kullanın.");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "Köy sınırı:");
        sender.sendMessage(ChatColor.GRAY + "pos1: " + yazi(pos1));
        sender.sendMessage(ChatColor.GRAY + "pos2: " + yazi(pos2));
        sender.sendMessage(sinirHazirMi() ? ChatColor.GREEN + "Sınır hazır." : ChatColor.RED + "Sınır eksik ya da farklı dünyalarda.");
        return true;
    }

    private String yazi(Location l) {
        if (l == null) return "yok";
        return (l.getWorld() == null ? "?" : l.getWorld().getName()) + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> sonuc = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission(YETKI)) {
            for (String s : new String[]{"pos1", "pos2", "bilgi", "sil"}) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) sonuc.add(s);
            }
        }
        return sonuc;
    }

    // ------------------------------------------------------------------ vatandaşlıktan çıkarma
    /** Hedefin vatandaşlıktan çıkarılabilir olup olmadığını söyler; olamıyorsa nedenini döndürür, olabiliyorsa null. */
    public String cikarilamazNedeni(Player target) {
        String meslek = plugin.oyuncuMeslekCache.getOrDefault(target.getUniqueId(), "vatandas");
        if (meslek.equalsIgnoreCase("belediyebaskani")) return "Belediye Başkanı vatandaşlıktan çıkarılamaz!";
        if (meslek.equalsIgnoreCase("gocmen") || meslek.equalsIgnoreCase("default")) return target.getName() + " zaten göçmen!";
        if (meslek.equalsIgnoreCase("mahkum")) return target.getName() + " şu an hapiste, önce cezası bitmeli!";
        return null;
    }

    public void vatandasliktanCikar(Player baskan, Player target) {
        String meslek = plugin.oyuncuMeslekCache.getOrDefault(baskan.getUniqueId(), "vatandas");
        if (!meslek.equalsIgnoreCase("belediyebaskani")) {
            baskan.sendMessage(ChatColor.RED + "Bunu sadece Belediye Başkanı yapabilir!");
            return;
        }
        if (baskan.getUniqueId().equals(target.getUniqueId())) return;
        String neden = cikarilamazNedeni(target);
        if (neden != null) {
            baskan.sendMessage(ChatColor.RED + neden);
            return;
        }

        plugin.oyuncuMeslekCache.put(target.getUniqueId(), "gocmen");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + target.getName() + " parent set gocmen");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + target.getName() + " gocmen");
        plugin.veriKaydet();

        Bukkit.broadcastMessage(ChatColor.GOLD + "[BELEDİYE] " + ChatColor.YELLOW + target.getName() + ChatColor.RED
                + ", Belediye Başkanı " + baskan.getName() + " tarafından vatandaşlıktan çıkarıldı!");
        target.sendMessage(ChatColor.RED + "Vatandaşlığınız kaldırıldı, göçmen statüsüne geçtiniz.");

        if (sinirHazirMi()) {
            disariIsinla(target);
        } else {
            baskan.sendMessage(ChatColor.GRAY + "(Köy sınırı ayarlı olmadığı için oyuncu ışınlanmadı. Yetkili /koyayarla pos1 ve pos2 ile sınırı belirlemeli.)");
        }
    }

    /** Verilen konum köy sınırlarının içinde mi? Sınır ayarlı değilse ya da farklı dünyadaysa false döner. */
    public boolean icerideMi(Location konum) {
        if (!sinirHazirMi() || konum == null || konum.getWorld() == null) return false;
        World w = pos1.getWorld();
        if (!konum.getWorld().equals(w)) return false;
        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ());
        double x = konum.getX();
        double z = konum.getZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** Oyuncuyu köy dikdörtgeninin kendisine en yakın kenarının hemen dışına ışınlar. Zaten dışarıdaysa dokunmaz. */
    public void disariIsinla(Player p) {
        if (!sinirHazirMi()) return;
        World w = pos1.getWorld();
        double minX = Math.min(pos1.getX(), pos2.getX());
        double maxX = Math.max(pos1.getX(), pos2.getX());
        double minZ = Math.min(pos1.getZ(), pos2.getZ());
        double maxZ = Math.max(pos1.getZ(), pos2.getZ());
        Location su = p.getLocation();
        if (!icerideMi(su)) return;

        double x = su.getX();
        double z = su.getZ();
        final double pay = 4.0;
        double solaMesafe = x - minX;
        double sagaMesafe = maxX - x;
        double kuzeyeMesafe = z - minZ;
        double guneyeMesafe = maxZ - z;
        double enKucuk = Math.min(Math.min(solaMesafe, sagaMesafe), Math.min(kuzeyeMesafe, guneyeMesafe));

        double hx = x;
        double hz = z;
        if (enKucuk == solaMesafe) hx = minX - pay;
        else if (enKucuk == sagaMesafe) hx = maxX + pay;
        else if (enKucuk == kuzeyeMesafe) hz = minZ - pay;
        else hz = maxZ + pay;

        final int bx = (int) Math.floor(hx);
        final int bz = (int) Math.floor(hz);
        final float yaw = su.getYaw();
        final float pitch = su.getPitch();
        w.getChunkAtAsync(bx >> 4, bz >> 4).thenAccept(chunk -> {
            int y = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            Location hedef = new Location(w, bx + 0.5, y, bz + 0.5, yaw, pitch);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    p.teleportAsync(hedef);
                    p.sendMessage(ChatColor.GRAY + "Köyün dışına çıkarıldınız.");
                }
            });
        });
    }

    // ------------------------------------------------------------------ göçmen girişi engeli
    /** Vatandaşlıktan çıkarılmış ("göçmen") bir oyuncu köy sınırının içine girmeye çalışırsa dışarı atılır ve polise haber verilir. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!sinirHazirMi()) return;
        // Performans: sadece blok değiştiren hareketlerde kontrol et (sağa sola bakmak tetiklemesin)
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;

        Player p = event.getPlayer();
        String meslek = plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas");
        if (meslek.equalsIgnoreCase("doktor")) {
            // Doktor köy sınırından dışarı çıkamaz (zaten dışarıdaysa geri girebilir)
            if (icerideMi(from) && !icerideMi(to) && !p.hasPermission(YETKI)) {
                event.setTo(from.clone().setDirection(to.getDirection()));
                doktorUyar(p);
            }
            return;
        }
        if (!meslek.equalsIgnoreCase("gocmen")) return;
        if (!icerideMi(to)) return;

        disariIsinla(p);

        long simdi = System.currentTimeMillis();
        Long sonUyari = sonUyariZamani.get(p.getUniqueId());
        if (sonUyari != null && simdi - sonUyari < UYARI_COOLDOWN_MS) return;
        sonUyariZamani.put(p.getUniqueId(), simdi);

        p.sendMessage(ChatColor.RED + "Göçmen olduğunuz için köye giremezsiniz!");
        polisleriUyar(p);
    }

    /** Işınlanma (ender pearl, komut vb.) ile de köy dışına çıkış engellenir. */
    @EventHandler(ignoreCancelled = true)
    public void onDoktorTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (!sinirHazirMi()) return;
        Player p = event.getPlayer();
        if (!plugin.oyuncuMeslekCache.getOrDefault(p.getUniqueId(), "vatandas").equalsIgnoreCase("doktor")) return;
        if (p.hasPermission(YETKI)) return;
        Location to = event.getTo();
        if (to != null && icerideMi(event.getFrom()) && !icerideMi(to)) {
            event.setCancelled(true);
            doktorUyar(p);
        }
    }

    private void doktorUyar(Player p) {
        long simdi = System.currentTimeMillis();
        Long son = sonUyariZamani.get(p.getUniqueId());
        if (son != null && simdi - son < 3000L) return;
        sonUyariZamani.put(p.getUniqueId(), simdi);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Doktor olarak köy sınırının dışına çıkamazsın!", net.kyori.adventure.text.format.NamedTextColor.RED));
    }

    private void polisleriUyar(Player gocmen) {
        String uyari = ChatColor.DARK_RED + "[MERKEZ] " + ChatColor.YELLOW + "DİKKAT: " + ChatColor.RED
                + "Göçmen " + gocmen.getName() + ChatColor.YELLOW + " köye izinsiz girmeye çalıştı!";
        for (Player online : Bukkit.getOnlinePlayers()) {
            String mOnline = plugin.oyuncuMeslekCache.getOrDefault(online.getUniqueId(), "vatandas");
            if (mOnline.equalsIgnoreCase("polis") || mOnline.equalsIgnoreCase("amir")) {
                online.sendMessage(uyari);
            }
        }
    }
}
