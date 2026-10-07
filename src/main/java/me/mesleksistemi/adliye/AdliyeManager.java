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
    
    public final HashMap<UUID, String> sohbetDurumu = new HashMap<>();
    public final HashMap<UUID, String> geciciDavaHedefi = new HashMap<>();
    public final HashMap<UUID, Double> geciciDavaMiktari = new HashMap<>();
    
    public final HashMap<UUID, UUID> avukatTeklifAsamasi = new HashMap<>(); 
    public final HashMap<UUID, AvukatTeklifi> bekleyenTeklifler = new HashMap<>(); 
    public final HashMap<UUID, UUID> hakimKararAsamasi = new HashMap<>(); 
    public final HashMap<UUID, Double> geciciHakimTazminat = new HashMap<>();

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
        String[] taraflar = {dava.musteki, dava.sanik, dava.mustekiAvukati, dava.sanikAvukati};
        for (String isim : taraflar) {
            if (isim != null) {
                @SuppressWarnings("deprecation")
                OfflinePlayer op = Bukkit.getOfflinePlayer(isim);
                durusmadakiOyuncular.remove(op.getUniqueId());
                if (op.isOnline()) {
                    op.getPlayer().sendMessage(ChatColor.GREEN + "Duruşma sona erdi, artık salondan ayrılabilirsiniz.");
                }
            }
        }
    }

    public void veriKaydetAdliye() {
        if (mahkemeSalonu != null) {
            plugin.getConfig().set("adliye.merkez.world", mahkemeSalonu.getWorld().getName());
            plugin.getConfig().set("adliye.merkez.x", mahkemeSalonu.getX());
            plugin.getConfig().set("adliye.merkez.y", mahkemeSalonu.getY());
            plugin.getConfig().set("adliye.merkez.z", mahkemeSalonu.getZ());
            plugin.getConfig().set("adliye.merkez.yaw", mahkemeSalonu.getYaw());
            plugin.getConfig().set("adliye.merkez.pitch", mahkemeSalonu.getPitch());
        }
        if (mahkemePos1 != null) {
            plugin.getConfig().set("adliye.pos1.world", mahkemePos1.getWorld().getName());
            plugin.getConfig().set("adliye.pos1.x", mahkemePos1.getX());
            plugin.getConfig().set("adliye.pos1.y", mahkemePos1.getY());
            plugin.getConfig().set("adliye.pos1.z", mahkemePos1.getZ());
        }
        if (mahkemePos2 != null) {
            plugin.getConfig().set("adliye.pos2.world", mahkemePos2.getWorld().getName());
            plugin.getConfig().set("adliye.pos2.x", mahkemePos2.getX());
            plugin.getConfig().set("adliye.pos2.y", mahkemePos2.getY());
            plugin.getConfig().set("adliye.pos2.z", mahkemePos2.getZ());
        }
        plugin.saveConfig();
    }

    public void veriYukleAdliye() {
        if (plugin.getConfig().contains("adliye.merkez.world")) {
            mahkemeSalonu = new Location(
                Bukkit.getWorld(plugin.getConfig().getString("adliye.merkez.world")),
                plugin.getConfig().getDouble("adliye.merkez.x"),
                plugin.getConfig().getDouble("adliye.merkez.y"),
                plugin.getConfig().getDouble("adliye.merkez.z"),
                (float) plugin.getConfig().getDouble("adliye.merkez.yaw"),
                (float) plugin.getConfig().getDouble("adliye.merkez.pitch")
            );
        }
        if (plugin.getConfig().contains("adliye.pos1.world")) {
            mahkemePos1 = new Location(
                Bukkit.getWorld(plugin.getConfig().getString("adliye.pos1.world")),
                plugin.getConfig().getDouble("adliye.pos1.x"),
                plugin.getConfig().getDouble("adliye.pos1.y"),
                plugin.getConfig().getDouble("adliye.pos1.z")
            );
        }
        if (plugin.getConfig().contains("adliye.pos2.world")) {
            mahkemePos2 = new Location(
                Bukkit.getWorld(plugin.getConfig().getString("adliye.pos2.world")),
                plugin.getConfig().getDouble("adliye.pos2.x"),
                plugin.getConfig().getDouble("adliye.pos2.y"),
                plugin.getConfig().getDouble("adliye.pos2.z")
            );
        }
    }
}