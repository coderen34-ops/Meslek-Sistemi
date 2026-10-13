package me.mesleksistemi.sistem;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

/**
 * Poster sistemi: bir görsel dosyasını (png/jpg/gif/bmp) harita parçalarına bölüp duvara item frame
 * olarak dizer. Sunucu yeniden başlasa da poster kaybolmaz.
 *
 * Görseller: plugins/MeslekSistemi/posterler/ klasörüne konur.
 *   /poster liste                              -> klasördeki görselleri listeler
 *   /poster <dosya> [genislik] [yukseklik]     -> baktığın bloğu sol-alt köşe sayarak posteri kurar
 *                                                 (yükseklik verilmezse görselin oranından hesaplanır)
 *   /poster sil                                -> baktığın posteri tamamen kaldırır
 *
 * Yetki: meslek.admin. Her harita parçası 128x128 pikseldir; poster en fazla 16x16 parça olabilir.
 */
public class PosterManager implements CommandExecutor, TabCompleter, Listener {

    public static final String YETKI = "meslek.admin";

    private static final int PARCA_PIKSEL = 128;
    private static final int VARSAYILAN_GENISLIK = 4;
    private static final int MAKS_KENAR = 16;
    private static final int MAKS_PARCA = 64;
    private static final long MAKS_DOSYA_BAYT = 25L * 1024 * 1024;
    private static final List<String> UZANTILAR = List.of(".png", ".jpg", ".jpeg", ".gif", ".bmp");

    private final MeslekSistemi plugin;
    private final File posterKlasoru;
    private final File haritaKlasoru;
    private final NamespacedKey posterKey;

    public PosterManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.posterKlasoru = new File(plugin.getDataFolder(), "posterler");
        this.haritaKlasoru = new File(posterKlasoru, "harita");
        this.posterKey = new NamespacedKey(plugin, "poster_id");
        if (!haritaKlasoru.exists() && !haritaKlasoru.mkdirs()) {
            plugin.getLogger().warning("Poster klasörü oluşturulamadı: " + haritaKlasoru);
        }
    }

    // ------------------------------------------------------------------ komut

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Bu komut sadece oyun içinden kullanılabilir.");
            return true;
        }
        Player p = (Player) sender;
        if (!p.hasPermission(YETKI)) {
            p.sendMessage(ChatColor.RED + "Bu komutu kullanma yetkin yok.");
            return true;
        }
        if (args.length == 0) {
            yardim(p);
            return true;
        }

        String ilk = args[0].toLowerCase(Locale.ROOT);
        if (ilk.equals("liste")) {
            listele(p);
            return true;
        }
        if (ilk.equals("sil")) {
            posteriSil(p);
            return true;
        }
        posteriKur(p, args);
        return true;
    }

    private void yardim(Player p) {
        p.sendMessage(ChatColor.GOLD + "/poster liste" + ChatColor.GRAY + " - posterler klasöründeki görselleri listeler");
        p.sendMessage(ChatColor.GOLD + "/poster <dosya> [genislik] [yukseklik]" + ChatColor.GRAY
                + " - baktığın bloğu sol-alt köşe sayarak duvara poster kurar");
        p.sendMessage(ChatColor.GOLD + "/poster sil" + ChatColor.GRAY + " - baktığın posteri kaldırır");
        p.sendMessage(ChatColor.GRAY + "Görselleri plugins/MeslekSistemi/posterler/ klasörüne koy.");
    }

    private void listele(Player p) {
        List<String> dosyalar = gorselDosyalari();
        if (dosyalar.isEmpty()) {
            p.sendMessage(ChatColor.YELLOW + "Poster klasöründe görsel yok (plugins/MeslekSistemi/posterler/).");
            return;
        }
        p.sendMessage(ChatColor.GOLD + "Posterler: " + ChatColor.WHITE + String.join(", ", dosyalar));
    }

    // ------------------------------------------------------------------ kurulum

    private void posteriKur(Player p, String[] args) {
        String dosyaAdi = args[0];
        File dosya = guvenliDosya(dosyaAdi);
        if (dosya == null || !dosya.isFile()) {
            p.sendMessage(ChatColor.RED + "Böyle bir görsel yok: " + dosyaAdi + ChatColor.GRAY + " (/poster liste)");
            return;
        }
        if (dosya.length() > MAKS_DOSYA_BAYT) {
            p.sendMessage(ChatColor.RED + "Görsel çok büyük (en fazla 25 MB).");
            return;
        }

        Integer istenenGenislik = null;
        Integer istenenYukseklik = null;
        try {
            if (args.length >= 2) istenenGenislik = Integer.parseInt(args[1]);
            if (args.length >= 3) istenenYukseklik = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            p.sendMessage(ChatColor.RED + "Genişlik/yükseklik sayı olmalı. Örn: /poster " + dosyaAdi + " 4 4");
            return;
        }
        if ((istenenGenislik != null && istenenGenislik < 1) || (istenenYukseklik != null && istenenYukseklik < 1)) {
            p.sendMessage(ChatColor.RED + "Genişlik ve yükseklik en az 1 olmalı.");
            return;
        }

        RayTraceResult sonuc = p.rayTraceBlocks(6.0);
        Block bakilan = sonuc == null ? null : sonuc.getHitBlock();
        BlockFace yuz = sonuc == null ? null : sonuc.getHitBlockFace();
        if (bakilan == null || yuz == null || !yatayYuzMu(yuz)) {
            p.sendMessage(ChatColor.RED + "Bir duvarın yan yüzüne bak (6 blok içinde). Baktığın blok posterin sol-alt köşesi olur.");
            return;
        }

        final World dunya = bakilan.getWorld();
        final int bx = bakilan.getX(), by = bakilan.getY(), bz = bakilan.getZ();
        final BlockFace yuzF = yuz;
        final UUID oyuncuId = p.getUniqueId();
        final Integer gen = istenenGenislik;
        final Integer yuk = istenenYukseklik;

        p.sendMessage(ChatColor.GRAY + "Görsel hazırlanıyor...");

        // Görsel okuma/ölçekleme ana thread'i yormasın diye arka planda yapılır.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final List<BufferedImage> parcalar;
            final int genislik;
            final int yukseklik;
            try {
                BufferedImage kaynak = ImageIO.read(dosya);
                if (kaynak == null) {
                    mesajGonder(oyuncuId, ChatColor.RED + "Bu dosya okunabilir bir görsel değil.");
                    return;
                }
                int g = gen != null ? gen : VARSAYILAN_GENISLIK;
                int y;
                if (yuk != null) {
                    y = yuk;
                } else {
                    y = (int) Math.round(g * (double) kaynak.getHeight() / (double) kaynak.getWidth());
                    if (y < 1) y = 1;
                }
                if (g > MAKS_KENAR || y > MAKS_KENAR || g * y > MAKS_PARCA) {
                    mesajGonder(oyuncuId, ChatColor.RED + "Poster çok büyük: " + g + "x" + y + " parça. En fazla "
                            + MAKS_KENAR + "x" + MAKS_KENAR + " ve toplam " + MAKS_PARCA + " parça olabilir.");
                    return;
                }
                genislik = g;
                yukseklik = y;
                parcalar = parcala(kaynak, g, y);
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("Poster görseli okunamadı (" + dosya.getName() + "): " + e.getMessage());
                mesajGonder(oyuncuId, ChatColor.RED + "Görsel okunamadı: " + e.getMessage());
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () ->
                    posteriYerlestir(oyuncuId, dunya, bx, by, bz, yuzF, genislik, yukseklik, parcalar));
        });
    }

    /** Görseli (genislik*128) x (yukseklik*128) boyuta getirip 128x128'lik parçalara böler (soldan sağa, yukarıdan aşağıya). */
    private List<BufferedImage> parcala(BufferedImage kaynak, int genislik, int yukseklik) {
        int hedefG = genislik * PARCA_PIKSEL;
        int hedefY = yukseklik * PARCA_PIKSEL;
        BufferedImage olcekli = new BufferedImage(hedefG, hedefY, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = olcekli.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Saydam alanlar siyah zemin üzerine çizilir
            g2.setColor(java.awt.Color.BLACK);
            g2.fillRect(0, 0, hedefG, hedefY);
            g2.drawImage(kaynak, 0, 0, hedefG, hedefY, null);
        } finally {
            g2.dispose();
        }

        List<BufferedImage> parcalar = new ArrayList<>(genislik * yukseklik);
        for (int satir = 0; satir < yukseklik; satir++) {
            for (int sutun = 0; sutun < genislik; sutun++) {
                BufferedImage parca = new BufferedImage(PARCA_PIKSEL, PARCA_PIKSEL, BufferedImage.TYPE_INT_RGB);
                Graphics2D pg = parca.createGraphics();
                try {
                    pg.drawImage(olcekli.getSubimage(sutun * PARCA_PIKSEL, satir * PARCA_PIKSEL, PARCA_PIKSEL, PARCA_PIKSEL), 0, 0, null);
                } finally {
                    pg.dispose();
                }
                parcalar.add(parca);
            }
        }
        return parcalar;
    }

    /** Ana thread: duvarın uygun olup olmadığını kontrol eder, haritaları ve item frame'leri oluşturur. */
    private void posteriYerlestir(UUID oyuncuId, World dunya, int bx, int by, int bz, BlockFace yuz,
                                  int genislik, int yukseklik, List<BufferedImage> parcalar) {
        Player p = Bukkit.getPlayer(oyuncuId);

        // Sağ vektör: duvarın karşısında duran oyuncunun sağ eli. n = duvar yüzünün normali.
        int nx = yuz.getModX();
        int nz = yuz.getModZ();
        int sagX = nz;
        int sagZ = -nx;

        // Önce tüm konumlar uygun mu bak; biri bile uygun değilse hiçbir şey kurma.
        int engelli = 0;
        for (int satir = 0; satir < yukseklik; satir++) {
            for (int sutun = 0; sutun < genislik; sutun++) {
                Block cerceve = dunya.getBlockAt(
                        bx + nx + sagX * sutun,
                        by + (yukseklik - 1 - satir),
                        bz + nz + sagZ * sutun);
                Block duvar = cerceve.getRelative(yuz.getOppositeFace());
                if (!cerceve.isPassable() || cerceve.isLiquid() || !duvar.getType().isSolid()) engelli++;
            }
        }
        if (engelli > 0) {
            if (p != null) {
                p.sendMessage(ChatColor.RED + "Poster sığmıyor: " + genislik + "x" + yukseklik + " alanda " + engelli
                        + " noktada duvar eksik ya da önü dolu. Daha düz/geniş bir duvar seç ya da boyutu küçült.");
            }
            return;
        }

        String posterId = UUID.randomUUID().toString();
        for (int satir = 0; satir < yukseklik; satir++) {
            for (int sutun = 0; sutun < genislik; sutun++) {
                BufferedImage parca = parcalar.get(satir * genislik + sutun);
                ItemStack harita = haritaOlustur(dunya, parca);
                if (harita == null) {
                    if (p != null) p.sendMessage(ChatColor.RED + "Harita oluşturulamadı, poster yarım kalmış olabilir (/poster sil ile temizle).");
                    return;
                }
                Block cerceveBlok = dunya.getBlockAt(
                        bx + nx + sagX * sutun,
                        by + (yukseklik - 1 - satir),
                        bz + nz + sagZ * sutun);
                dunya.spawn(cerceveBlok.getLocation(), ItemFrame.class, f -> {
                    f.setFacingDirection(yuz, true);
                    f.setItem(harita, false);
                    f.setVisible(false);
                    f.setFixed(true);
                    f.setInvulnerable(true);
                    f.setItemDropChance(0f);
                    f.getPersistentDataContainer().set(posterKey, PersistentDataType.STRING, posterId);
                });
            }
        }
        if (p != null) {
            p.sendMessage(ChatColor.GREEN + "Poster kuruldu: " + genislik + "x" + yukseklik + " parça ("
                    + (genislik * yukseklik) + " harita). Kaldırmak için posterin üstüne bakıp /poster sil yaz.");
        }
    }

    private ItemStack haritaOlustur(World dunya, BufferedImage parca) {
        try {
            MapView view = Bukkit.createMap(dunya);
            for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            view.addRenderer(new ParcaRenderer(parca));

            File kayit = new File(haritaKlasoru, "harita_" + view.getId() + ".png");
            ImageIO.write(parca, "png", kayit);

            ItemStack item = new ItemStack(Material.FILLED_MAP);
            MapMeta meta = (MapMeta) item.getItemMeta();
            meta.setMapView(view);
            item.setItemMeta(meta);
            return item;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Poster haritası oluşturulamadı: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ silme

    private void posteriSil(Player p) {
        Entity hedef = p.getTargetEntity(6);
        if (!(hedef instanceof ItemFrame)) {
            p.sendMessage(ChatColor.RED + "Silmek istediğin posterin üstüne bak (6 blok içinde).");
            return;
        }
        String id = hedef.getPersistentDataContainer().get(posterKey, PersistentDataType.STRING);
        if (id == null) {
            p.sendMessage(ChatColor.RED + "Bu item frame bir poster parçası değil.");
            return;
        }

        int silinen = 0;
        for (ItemFrame f : hedef.getWorld().getEntitiesByClass(ItemFrame.class)) {
            String fid = f.getPersistentDataContainer().get(posterKey, PersistentDataType.STRING);
            if (!id.equals(fid)) continue;

            ItemStack item = f.getItem();
            if (item.getItemMeta() instanceof MapMeta) {
                MapView v = ((MapMeta) item.getItemMeta()).getMapView();
                if (v != null) {
                    File kayit = new File(haritaKlasoru, "harita_" + v.getId() + ".png");
                    if (kayit.isFile() && !kayit.delete()) {
                        plugin.getLogger().warning("Poster harita dosyası silinemedi: " + kayit.getName());
                    }
                }
            }
            f.remove();
            silinen++;
        }
        p.sendMessage(ChatColor.GREEN + "Poster kaldırıldı (" + silinen + " parça).");
    }

    // ------------------------------------------------------------------ kalıcılık

    /** Restart sonrası harita yüklenince, kayıtlı poster parçası varsa görselini yeniden bağlar. */
    @EventHandler
    public void onMapInit(MapInitializeEvent event) {
        MapView view = event.getMap();
        File kayit = new File(haritaKlasoru, "harita_" + view.getId() + ".png");
        if (!kayit.isFile()) return;
        try {
            BufferedImage img = ImageIO.read(kayit);
            if (img == null) return;
            for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
            view.setTrackingPosition(false);
            view.setUnlimitedTracking(false);
            view.addRenderer(new ParcaRenderer(img));
        } catch (IOException e) {
            plugin.getLogger().warning("Poster parçası yüklenemedi (" + kayit.getName() + "): " + e.getMessage());
        }
    }

    /** Haritaya sabit bir görseli bir kez çizer. */
    private static class ParcaRenderer extends MapRenderer {
        private final BufferedImage img;
        private boolean cizildi = false;

        ParcaRenderer(BufferedImage img) {
            super(false);
            this.img = img;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            if (cizildi) return;
            canvas.drawImage(0, 0, img);
            cizildi = true;
        }
    }

    // ------------------------------------------------------------------ yardımcılar

    private static boolean yatayYuzMu(BlockFace f) {
        return f == BlockFace.NORTH || f == BlockFace.SOUTH || f == BlockFace.EAST || f == BlockFace.WEST;
    }

    private void mesajGonder(UUID oyuncuId, String mesaj) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(oyuncuId);
            if (p != null) p.sendMessage(mesaj);
        });
    }

    /** Klasörün dışına çıkan yolları (../) engeller ve sadece desteklenen uzantılara izin verir. */
    private File guvenliDosya(String ad) {
        if (ad == null || ad.isEmpty()) return null;
        String kucuk = ad.toLowerCase(Locale.ROOT);
        boolean uzantiTamam = false;
        for (String u : UZANTILAR) {
            if (kucuk.endsWith(u)) { uzantiTamam = true; break; }
        }
        if (!uzantiTamam) return null;
        try {
            File klasor = posterKlasoru.getCanonicalFile();
            File dosya = new File(klasor, ad).getCanonicalFile();
            if (!klasor.equals(dosya.getParentFile())) return null;
            return dosya;
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> gorselDosyalari() {
        List<String> sonuc = new ArrayList<>();
        File[] liste = posterKlasoru.listFiles();
        if (liste == null) return sonuc;
        for (File f : liste) {
            if (!f.isFile()) continue;
            String kucuk = f.getName().toLowerCase(Locale.ROOT);
            for (String u : UZANTILAR) {
                if (kucuk.endsWith(u)) { sonuc.add(f.getName()); break; }
            }
        }
        java.util.Collections.sort(sonuc);
        return sonuc;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> sonuc = new ArrayList<>();
        if (!sender.hasPermission(YETKI)) return sonuc;
        if (args.length == 1) {
            String on = args[0].toLowerCase(Locale.ROOT);
            List<String> adaylar = new ArrayList<>(List.of("liste", "sil"));
            adaylar.addAll(gorselDosyalari());
            for (String a : adaylar) {
                if (a.toLowerCase(Locale.ROOT).startsWith(on)) sonuc.add(a);
            }
        } else if (args.length == 2 || args.length == 3) {
            for (String s : List.of("2", "3", "4", "6", "8")) {
                if (s.startsWith(args[args.length - 1])) sonuc.add(s);
            }
        }
        return sonuc;
    }
}
