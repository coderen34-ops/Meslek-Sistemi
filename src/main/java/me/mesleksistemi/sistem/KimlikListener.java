package me.mesleksistemi.sistem;

import me.mesleksistemi.MeslekSistemi;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class KimlikListener implements Listener {

    private final MeslekSistemi plugin;

    public KimlikListener(MeslekSistemi plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "gocmen");
        if (meslek.equalsIgnoreCase("gocmen") || meslek.equalsIgnoreCase("default")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.DARK_RED + "[!] " + ChatColor.RED + "Göçmen statüsünde çevreyle etkileşime giremezsiniz. Önce Nüfus Müdürlüğüne gidip kimlik çıkartın!");
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        String meslek = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "gocmen");
        if (meslek.equalsIgnoreCase("gocmen") || meslek.equalsIgnoreCase("default")) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.DARK_RED + "[!] " + ChatColor.RED + "Göçmen statüsünde çevreyle etkileşime giremezsiniz. Önce kimlik çıkartın!");
        }
    }

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Villager) {
            Villager npc = (Villager) event.getRightClicked();
            Player player = event.getPlayer();
            
            if (npc.getPersistentDataContainer().has(plugin.nufusNpcKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                String ms = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "gocmen");
                if (!ms.equalsIgnoreCase("gocmen") && !ms.equalsIgnoreCase("default")) {
                    player.sendMessage(ChatColor.YELLOW + "Memur: " + ChatColor.WHITE + "Zaten sistemimizde kayıtlı bir vatandaşsın.");
                    return;
                }
                plugin.kimlikAsama.put(player.getUniqueId(), 1);
                plugin.geciciKimlikler.put(player.getUniqueId(), new MeslekSistemi.GeciciKimlik());
                player.sendMessage(ChatColor.YELLOW + "--- NÜFUS MÜDÜRLÜĞÜ ---");
                player.sendMessage(ChatColor.AQUA + "Memur: " + ChatColor.WHITE + "Kaydınız için lütfen sadece " + ChatColor.YELLOW + "İSMİNİZİ" + ChatColor.WHITE + " sohbete yazın.");
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID pId = player.getUniqueId();

        if (plugin.kimlikAsama.containsKey(pId)) {
            event.setCancelled(true);
            int asama = plugin.kimlikAsama.get(pId);
            String mesaj = event.getMessage().trim();
            MeslekSistemi.GeciciKimlik veri = plugin.geciciKimlikler.get(pId);

            if (mesaj.equalsIgnoreCase("iptal")) {
                plugin.kimlikAsama.remove(pId); plugin.geciciKimlikler.remove(pId);
                player.sendMessage(ChatColor.RED + "İşlem iptal edildi."); return;
            }

            switch (asama) {
                case 1:
                    veri.isim = mesaj; plugin.kimlikAsama.put(pId, 2);
                    player.sendMessage(ChatColor.AQUA + "Memur: " + ChatColor.WHITE + "Şimdi " + ChatColor.YELLOW + "SOYİSMİNİZİ" + ChatColor.WHITE + " yazın.");
                    break;
                case 2:
                    veri.soyisim = mesaj; plugin.kimlikAsama.put(pId, 3);
                    player.sendMessage(ChatColor.AQUA + "Memur: " + ChatColor.WHITE + "Lütfen RP " + ChatColor.YELLOW + "YAŞINIZI" + ChatColor.WHITE + " rakamla yazın.");
                    break;
                case 3:
                    try {
                        int yas = Integer.parseInt(mesaj);
                        if (yas < 18 || yas > 90) { player.sendMessage(ChatColor.RED + "18-90 arası mantıklı bir yaş girin."); return; }
                        veri.yas = yas; plugin.kimlikAsama.put(pId, 4);
                        biyomSecenekleriniGoster(player);
                    } catch (NumberFormatException e) { player.sendMessage(ChatColor.RED + "Lütfen sadece rakam kullanın."); }
                    break;
                case 4:
                    player.sendMessage(ChatColor.RED + "Sohbete yazmak yerine yukarıdaki kütük butonlarından birine tıklayın.");
                    break;
            }
        }
    }

    private void biyomSecenekleriniGoster(Player player) {
        player.sendMessage(ChatColor.AQUA + "Memur: " + ChatColor.WHITE + "Son olarak kütüğünüzün bulunduğu biyomu seçin:");
        String[] biyomlar = {"Orman", "Çöl", "Dağlar", "Ovalar", "Buzul", "Bataklık"};
        for (String b : biyomlar) {
            TextComponent btn = new TextComponent(ChatColor.DARK_AQUA + "➤ [" + b + "] ");
            btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/kimlikbiyomsec " + b));
            player.spigot().sendMessage(btn);
        }
    }
}