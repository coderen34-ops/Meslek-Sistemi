package me.mesleksistemi.adliye;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

public class AdliyeCommands implements CommandExecutor {

    private final MeslekSistemi plugin;
    private final AdliyeManager am;

    public AdliyeCommands(MeslekSistemi plugin, AdliyeManager am) {
        this.plugin = plugin;
        this.am = am;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("adliyenpc")) {
            if (!player.hasPermission("adliye.admin")) return true;
            if (args.length == 0) return true;

            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
                npc.setCustomName(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Adalet Sarayı & Dava Merkezi");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.LIBRARIAN);
                npc.getPersistentDataContainer().set(am.adliyeNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Adliye NPC'si kuruldu!");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(am.adliyeNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(ChatColor.GREEN + "" + silinen + " adet Adliye NPC'si silindi.");
            }
            return true;
        }

        if (cmd.equals("mahkemeayarla")) {
            if (!player.hasPermission("adliye.admin")) return true;
            
            if (args.length == 0) {
                am.mahkemeSalonu = player.getLocation();
                player.sendMessage(ChatColor.GREEN + "Mahkeme salonu (merkez ışınlanma noktası) başarıyla kaydedildi!");
            } else if (args[0].equalsIgnoreCase("pos1")) {
                am.mahkemePos1 = player.getLocation();
                player.sendMessage(ChatColor.GREEN + "Mahkeme salonu 1. Köşesi (pos1) kaydedildi!");
            } else if (args[0].equalsIgnoreCase("pos2")) {
                am.mahkemePos2 = player.getLocation();
                player.sendMessage(ChatColor.GREEN + "Mahkeme salonu 2. Köşesi (pos2) kaydedildi!");
            } 
            else if (args[0].equalsIgnoreCase("sil")) {
                am.mahkemeSalonu = null;
                am.mahkemePos1 = null;
                am.mahkemePos2 = null;
                player.sendMessage(ChatColor.GREEN + "Tüm mahkeme koordinatları ve kilitleri başarıyla silindi! Artık herkes özgürce çıkabilir.");
                am.durusmadakiOyuncular.clear();
            }
            am.veriKaydetAdliye();
            return true;
        }

        if (cmd.equals("avukatkabul")) {
            if (!am.bekleyenTeklifler.containsKey(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "Size gönderilmiş bekleyen bir avukatlık sözleşmesi yok.");
                return true;
            }
            
            AvukatTeklifi teklif = am.bekleyenTeklifler.get(player.getUniqueId());
            DavaDosyasi dava = am.aktifDavalar.get(teklif.davaId);
            
            if (dava == null) {
                player.sendMessage(ChatColor.RED + "Bu dava dosyası artık geçerli değil.");
                am.bekleyenTeklifler.remove(player.getUniqueId());
                return true;
            }
            
            double musteriPara = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
            plugin.bankaHesaplari.put(player.getUniqueId(), musteriPara - teklif.ucret);
            
            double avukatPara = plugin.bankaHesaplari.getOrDefault(teklif.avukatId, 0.0);
            plugin.bankaHesaplari.put(teklif.avukatId, avukatPara + teklif.ucret);
            plugin.veriKaydet();
            
            if (teklif.isMusteki) {
                dava.mustekiAvukati = teklif.avukatAdi;
                dava.mustekiAvukatiUcreti = teklif.ucret;
                dava.durum = DavaDurumu.SANIK_AVUKATI_BEKLIYOR;
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "[Adliye] " + ChatColor.YELLOW + "Dava No: " + dava.id.toString().substring(0, 5) + " için Şikayetçi Avukatı atanmıştır. Sıra Sanık Avukatında!");
            }
            
            player.sendMessage(ChatColor.GREEN + "Sözleşmeyi kabul ettiniz! $" + teklif.ucret + " banka hesabınızdan avukata aktarıldı.");
            Player avukat = Bukkit.getPlayer(teklif.avukatId);
            if (avukat != null) {
                avukat.sendMessage(ChatColor.GREEN + "Müşteri teklifinizi kabul etti! $" + teklif.ucret + " banka hesabınıza yattı.");
            }
            
            am.bekleyenTeklifler.remove(player.getUniqueId());
            return true;
        }

        if (cmd.equals("avukatred")) {
            if (am.bekleyenTeklifler.containsKey(player.getUniqueId())) {
                AvukatTeklifi teklif = am.bekleyenTeklifler.remove(player.getUniqueId());
                player.sendMessage(ChatColor.RED + "Avukatlık teklifini reddettiniz.");
                Player avukat = Bukkit.getPlayer(teklif.avukatId);
                if (avukat != null) {
                    avukat.sendMessage(ChatColor.RED + player.getName() + " sunduğunuz avukatlık teklifini reddetti.");
                }
            } else {
                player.sendMessage(ChatColor.RED + "Bekleyen bir teklifiniz yok.");
            }
            return true;
        }
        return false;
    }
}