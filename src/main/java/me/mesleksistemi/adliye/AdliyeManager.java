package me.mesleksistemi.adliye;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;
import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

public class AdliyeManager {

    private final MeslekSistemi plugin;
    public final NamespacedKey adliyeNpcKey;
    public final NamespacedKey davaKitabiKey;

    public Location mahkemeSalonu = null; 
    public Location mahkemePos1 = null;   
    public Location mahkemePos2 = null;   
    
    public final HashMap<UUID, DavaDosyasi> aktifDavalar = new HashMap<>();
    public final HashSet<UUID> durusmadakiOyuncular = new HashSet<>();
    
    public final java.util.concurrent.ConcurrentHashMap<UUID, String> sohbetDurumu = new java.util.concurrent.ConcurrentHashMap<>();
    public final java.util.concurrent.ConcurrentHashMap<UUID, String> geciciDavaHedefi = new java.util.concurrent.ConcurrentHashMap<>();
    public final java.util.concurrent.ConcurrentHashMap<UUID, Double> geciciDavaMiktari = new java.util.concurrent.ConcurrentHashMap<>();
    
    public final java.util.concurrent.ConcurrentHashMap<UUID, UUID> avukatTeklifAsamasi = new java.util.concurrent.ConcurrentHashMap<>(); 
    public final HashMap<UUID, AvukatTeklifi> bekleyenTeklifler = new HashMap<>(); 
    public final java.util.concurrent.ConcurrentHashMap<UUID, UUID> hakimKararAsamasi = new java.util.concurrent.ConcurrentHashMap<>(); 
    public final java.util.concurrent.ConcurrentHashMap<UUID, Double> geciciHakimTazminat = new java.util.concurrent.ConcurrentHashMap<>();

    public AdliyeManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.adliyeNpcKey = new NamespacedKey(plugin, "adliye_npc");
        this.davaKitabiKey = new NamespacedKey(plugin, "dava_kitabi");
        veriYukleAdliye();
    }

    public ItemStack createDavaDosyasiKitabi(DavaDosyasi dava) {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle("Dava Dosyası: " + dava.id.toString().substring(0, 5));
        meta.setAuthor("Adalet Bakanlığı");

        String anaSayfa = ChatColor.DARK_RED + ChatColor.BOLD.toString() + "DAVA DOSYASI\n\n" +
                ChatColor.BLACK + "Müşteki: " + ChatColor.DARK_GRAY + dava.musteki + "\n" +
                ChatColor.BLACK + "M. Avukatı: " + ChatColor.DARK_GRAY + (dava.mustekiAvukati != null ? dava.mustekiAvukati : "Yok") + "\n\n" +
                ChatColor.BLACK + "Sanık: " + ChatColor.DARK_GRAY + dava.sanik + "\n" +
                ChatColor.BLACK + "S. Avukatı: " + ChatColor.DARK_GRAY + (dava.sanikAvukati != null ? dava.sanikAvukati : "Yok") + "\n\n" +
                ChatColor.BLACK + "Talep: " + ChatColor.DARK_GREEN + "$" + dava.talepEdilenMiktar;
        
        meta.addPage(anaSayfa);

        String sebep = dava.sebep;
        int index = 0;
        while (index < sebep.length()) {
            int end = Math.min(index + 200, sebep.length());
            if (end < sebep.length()) {
                int ls = sebep.lastIndexOf(' ', end);
                if (ls > index) end = ls;
            }
            meta.addPage(ChatColor.DARK_BLUE + ChatColor.BOLD.toString() + "OLAY DETAYI\n\n" + ChatColor.BLACK + sebep.substring(index, end).trim());
            index = end;
        }

        book.setItemMeta(meta);
        return book;
    }

    public void durusmayaCek(DavaDosyasi dava, Player hakim) {
        if (mahkemeSalonu == null) return;
        
        ItemStack davaDosyasi = createDavaDosyasiKitabi(dava);
        
        if (hakim != null) {
            hakim.getInventory().addItem(davaDosyasi);
            hakim.sendMessage(ChatColor.GREEN + "Dava Dosyası (Kitap) envanterinize eklendi! Karar vermeden önce okuyabilirsiniz.");
        }

        String[] taraflar = {dava.musteki, dava.sanik, dava.mustekiAvukati, dava.sanikAvukati};
        for (String isim : taraflar) {
            if (isim != null) {
                Player p = Bukkit.getPlayerExact(isim);
                // Arena savaşındaki taraf (sağlık muafiyetli) savaşın ortasından salona çekilmez
                if (p != null && plugin.saglikManager != null && plugin.saglikManager.muafMi(p.getUniqueId())) {
                    if (hakim != null) hakim.sendMessage(ChatColor.YELLOW + isim + " şu an bir klan savaşında, duruşma salonuna getirilemedi.");
                    p.sendMessage(ChatColor.YELLOW + "[Adliye] Davanızın duruşması başladı ancak savaşta olduğunuz için salona çekilmediniz.");
                    continue;
                }
                if (p != null && p.isOnline()) {
                    p.teleport(mahkemeSalonu);
                    durusmadakiOyuncular.add(p.getUniqueId());
                    p.sendMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.YELLOW + "Hakim duruşmayı başlattı, mahkeme salonuna çekildiniz!");
                    p.sendMessage(ChatColor.RED + "Duruşma bitene kadar salonu terk edemezsiniz!");
                    
                    if (isim.equalsIgnoreCase(dava.mustekiAvukati) || isim.equalsIgnoreCase(dava.sanikAvukati)) {
                        p.getInventory().addItem(davaDosyasi.clone());
                        p.sendMessage(ChatColor.GREEN + "Savunmanız için Dava Dosyası (Kitap) envanterinize eklendi.");
                    }
                }
            }
        }
    }
    
    public void durusmayiBitir(DavaDosyasi dava) {
        String[] taraflar = {dava.musteki, dava.sanik, dava.mustekiAvukati, dava.sanikAvukati, dava.hakim};
        for (String isim : taraflar) {
            if (isim != null) {
                @SuppressWarnings("deprecation")
                OfflinePlayer op = Bukkit.getOfflinePlayer(isim);
                durusmadakiOyuncular.remove(op.getUniqueId());
                if (op.isOnline()) {
                    Player p = op.getPlayer();
                    // Hapisteyken duruşmaya getirilen taraf hücresine geri döner
                    if (plugin.hapisteMi(p.getUniqueId())) {
                        plugin.polisManager.hucreyeGonder(p);
                        p.sendMessage(ChatColor.YELLOW + "Duruşma sona erdi, hücrenize geri götürüldünüz.");
                    } else {
                        p.sendMessage(ChatColor.GREEN + "Duruşma sona erdi, artık salondan ayrılabilirsiniz.");
                    }
                }
            }
        }
    }

    // Duruşmayı yöneten hakim oyundan çıkarsa taraflar salonda mahsur kalmasın:
    // duruşma ertelenir ve dava başka bir hakim tarafından yeniden başlatılabilir.
    public void hakimAyrildi(Player hakim) {
        hakimKararAsamasi.remove(hakim.getUniqueId());
        geciciHakimTazminat.remove(hakim.getUniqueId());
        for (DavaDosyasi dava : aktifDavalar.values()) {
            if (dava.durum == DavaDurumu.DURUSMADA && hakim.getName().equalsIgnoreCase(dava.hakim)) {
                durusmayiBitir(dava);
                dava.durum = DavaDurumu.HAKIM_BEKLIYOR;
                dava.hakim = null;
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.YELLOW + "Dava No: " + dava.id.toString().substring(0, 5)
                        + " duruşması hakimin ayrılması nedeniyle ertelendi. Yeni bir hakim bekleniyor.");
            }
        }
        veriKaydetAdliye();
    }

    public void veriKaydetAdliye() {
        if (mahkemeSalonu != null && mahkemeSalonu.getWorld() != null) {
            plugin.getConfig().set("adliye.merkez.world", mahkemeSalonu.getWorld().getName());
            plugin.getConfig().set("adliye.merkez.x", mahkemeSalonu.getX());
            plugin.getConfig().set("adliye.merkez.y", mahkemeSalonu.getY());
            plugin.getConfig().set("adliye.merkez.z", mahkemeSalonu.getZ());
            plugin.getConfig().set("adliye.merkez.yaw", mahkemeSalonu.getYaw());
            plugin.getConfig().set("adliye.merkez.pitch", mahkemeSalonu.getPitch());
        }
        if (mahkemePos1 != null && mahkemePos1.getWorld() != null) {
            plugin.getConfig().set("adliye.pos1.world", mahkemePos1.getWorld().getName());
            plugin.getConfig().set("adliye.pos1.x", mahkemePos1.getX());
            plugin.getConfig().set("adliye.pos1.y", mahkemePos1.getY());
            plugin.getConfig().set("adliye.pos1.z", mahkemePos1.getZ());
        }
        if (mahkemePos2 != null && mahkemePos2.getWorld() != null) {
            plugin.getConfig().set("adliye.pos2.world", mahkemePos2.getWorld().getName());
            plugin.getConfig().set("adliye.pos2.x", mahkemePos2.getX());
            plugin.getConfig().set("adliye.pos2.y", mahkemePos2.getY());
            plugin.getConfig().set("adliye.pos2.z", mahkemePos2.getZ());
        }

        // Davalar sunucu yeniden başlasa da kaybolmasın
        plugin.getConfig().set("adliye.davalar", null);
        for (DavaDosyasi dava : aktifDavalar.values()) {
            String yol = "adliye.davalar." + dava.id;
            plugin.getConfig().set(yol + ".musteki", dava.musteki);
            plugin.getConfig().set(yol + ".sanik", dava.sanik);
            plugin.getConfig().set(yol + ".mustekiAvukati", dava.mustekiAvukati);
            plugin.getConfig().set(yol + ".sanikAvukati", dava.sanikAvukati);
            plugin.getConfig().set(yol + ".talep", dava.talepEdilenMiktar);
            plugin.getConfig().set(yol + ".mustekiAvukatiUcreti", dava.mustekiAvukatiUcreti);
            plugin.getConfig().set(yol + ".sanikAvukatiUcreti", dava.sanikAvukatiUcreti);
            plugin.getConfig().set(yol + ".sebep", dava.sebep);
            plugin.getConfig().set(yol + ".durum", dava.durum.name());
            plugin.getConfig().set(yol + ".hakim", dava.hakim);
        }
        plugin.saveConfig();
    }

    // "mahkemeayarla sil" için: koordinatlar config'den de kaldırılır
    public void mahkemeKonumlariniSil() {
        mahkemeSalonu = null;
        mahkemePos1 = null;
        mahkemePos2 = null;
        plugin.getConfig().set("adliye.merkez", null);
        plugin.getConfig().set("adliye.pos1", null);
        plugin.getConfig().set("adliye.pos2", null);
    }

    private Location konumYukle(String yol, boolean yonlu) {
        if (!plugin.getConfig().contains(yol + ".world")) return null;
        org.bukkit.World dunya = Bukkit.getWorld(plugin.getConfig().getString(yol + ".world", ""));
        if (dunya == null) {
            plugin.getLogger().warning("[Adliye] " + yol + " konumunun dünyası yüklü değil.");
            return null;
        }
        Location loc = new Location(dunya,
                plugin.getConfig().getDouble(yol + ".x"),
                plugin.getConfig().getDouble(yol + ".y"),
                plugin.getConfig().getDouble(yol + ".z"));
        if (yonlu) {
            loc.setYaw((float) plugin.getConfig().getDouble(yol + ".yaw"));
            loc.setPitch((float) plugin.getConfig().getDouble(yol + ".pitch"));
        }
        return loc;
    }

    public void veriYukleAdliye() {
        mahkemeSalonu = konumYukle("adliye.merkez", true);
        mahkemePos1 = konumYukle("adliye.pos1", false);
        mahkemePos2 = konumYukle("adliye.pos2", false);

        ConfigurationSection davalar = plugin.getConfig().getConfigurationSection("adliye.davalar");
        if (davalar == null) return;
        for (String idStr : davalar.getKeys(false)) {
            try {
                ConfigurationSection c = davalar.getConfigurationSection(idStr);
                DavaDosyasi dava = new DavaDosyasi(UUID.fromString(idStr), c.getString("musteki"), c.getString("sanik"),
                        c.getDouble("talep"), c.getString("sebep", ""));
                dava.mustekiAvukati = c.getString("mustekiAvukati");
                dava.sanikAvukati = c.getString("sanikAvukati");
                dava.mustekiAvukatiUcreti = c.getDouble("mustekiAvukatiUcreti");
                dava.sanikAvukatiUcreti = c.getDouble("sanikAvukatiUcreti");
                dava.durum = DavaDurumu.valueOf(c.getString("durum", "MUSTEKI_AVUKATI_BEKLIYOR"));
                dava.hakim = c.getString("hakim");
                // Yeniden başlatmada yarım kalan duruşma baştan başlatılır (taraflar salona yeniden çekilir)
                if (dava.durum == DavaDurumu.DURUSMADA) {
                    dava.durum = DavaDurumu.HAKIM_BEKLIYOR;
                    dava.hakim = null;
                }
                if (dava.durum != DavaDurumu.KAPANDI) aktifDavalar.put(dava.id, dava);
            } catch (Exception e) {
                plugin.getLogger().warning("[Adliye] '" + idStr + "' davası yüklenemedi: " + e.getMessage());
            }
        }
    }
}
