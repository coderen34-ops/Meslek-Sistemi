package me.mesleksistemi.sistem;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class MeslekListener implements Listener {

    private final MeslekSistemi plugin;

    public MeslekListener(MeslekSistemi plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // YENİ: Oyuna ilk kez giren kişiye Rehber Kitabı verme
        if (!player.hasPlayedBefore()) {
            player.getInventory().addItem(plugin.getRehberKitabi());
            player.sendMessage(ChatColor.GOLD + "Cebinize bir Şehir Rehberi bırakıldı!");
        }

        // OHAL Boss Bar Kontrolü
        if (plugin.ohalAktif && plugin.ohalBar != null) {
            plugin.ohalBar.addPlayer(player);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            plugin.sonHareketZamani.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Villager) {
            Villager npc = (Villager) event.getRightClicked();
            Player player = event.getPlayer();
            
            if (npc.getPersistentDataContainer().has(plugin.npcKey, PersistentDataType.BYTE) ||
                npc.getPersistentDataContainer().has(plugin.bankaNpcKey, PersistentDataType.BYTE) ||
                npc.getPersistentDataContainer().has(plugin.nufusNpcKey, PersistentDataType.BYTE) ||
                npc.getPersistentDataContainer().has(plugin.tapuNpcKey, PersistentDataType.BYTE)) {
                
                event.setCancelled(true);
            }
            
            if (npc.getPersistentDataContainer().has(plugin.npcKey, PersistentDataType.BYTE)) {
                String ms = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "gocmen");
                if(ms.equalsIgnoreCase("gocmen") || ms.equalsIgnoreCase("default")) {
                    player.sendMessage(ChatColor.RED + "Göçmenlerin başvuru hakkı yoktur! Kimlik çıkartmalısın."); return;
                }
                plugin.getMenuManager().openMeslekMenu(player); 
            } 
            else if (npc.getPersistentDataContainer().has(plugin.bankaNpcKey, PersistentDataType.BYTE)) {
                String ms = plugin.oyuncuMeslekCache.getOrDefault(player.getUniqueId(), "gocmen");
                if(ms.equalsIgnoreCase("gocmen") || ms.equalsIgnoreCase("default")) {
                    player.sendMessage(ChatColor.RED + "Göçmen durumundayken banka hesabı kullanamazsınız!"); return;
                }
                plugin.getMenuManager().openBankaMenu(player);
            }
            else if (npc.getPersistentDataContainer().has(plugin.tapuNpcKey, PersistentDataType.BYTE)) {
                plugin.getMenuManager().openTapuMenu(player);
            }
        }
    }

    @EventHandler
    public void onLecternInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            Block clickedBlock = event.getClickedBlock();
            if (clickedBlock != null && clickedBlock.getType() == Material.LECTERN) {
                
                if (plugin.kursuKonumu != null && clickedBlock.getLocation().equals(plugin.kursuKonumu)) {
                    event.setCancelled(true); 
                    Player player = event.getPlayer();
                    if (player.hasPermission("meslek.baskan")) plugin.getMenuManager().openBasvuruMenu(player);
                    else player.sendMessage(ChatColor.RED + "Sadece Belediye Baskani erisebilir!");
                }
                
                if (plugin.yasaKursuKonumu != null && clickedBlock.getLocation().equals(plugin.yasaKursuKonumu)) {
                    event.setCancelled(true);
                    if (plugin.yasaKitabi != null) {
                        event.getPlayer().openBook(plugin.yasaKitabi);
                    } else {
                        event.getPlayer().sendMessage(ChatColor.RED + "Belediye Başkanı henüz bir yasa kitabı yayınlamadı.");
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID pId = player.getUniqueId();
        plugin.sonHareketZamani.put(pId, System.currentTimeMillis());

        if (plugin.basvuruBekleyenler.containsKey(pId)) {
            event.setCancelled(true); 
            String sebep = event.getMessage();
            plugin.aktifBasvurular.put(pId, new MeslekSistemi.Basvuru(player.getName(), plugin.basvuruBekleyenler.get(pId), sebep));
            plugin.basvuruBekleyenler.remove(pId); plugin.veriKaydet();
            player.sendMessage(ChatColor.GREEN + "Basvurunuz Belediye Baskanina iletildi!");
            return;
        }

        if (plugin.tapuIslemBekleyenler.containsKey(pId)) {
            event.setCancelled(true);
            String islemTipi = plugin.tapuIslemBekleyenler.get(pId); 
            String mesaj = event.getMessage().trim();
            plugin.tapuIslemBekleyenler.remove(pId);

            if (mesaj.equalsIgnoreCase("iptal")) { player.sendMessage(ChatColor.YELLOW + "Tapu islemi iptal edildi."); return; }

            int blokMiktari;
            try {
                blokMiktari = Integer.parseInt(mesaj);
                if (blokMiktari <= 0) throw new NumberFormatException();
            } catch (Exception e) { player.sendMessage(ChatColor.RED + "Gecersiz miktar. Sadece sayi giriniz."); return; }

            double blokFiyati = islemTipi.equals("ILK") ? plugin.ilkTapuBlokFiyati : plugin.genisletmeBlokFiyati;
            double toplamTutar = blokMiktari * blokFiyati;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.processPaymentToKasa(player, toplamTutar)) {
                    if (islemTipi.equals("ILK")) plugin.tapuSahipleri.add(pId);
                    plugin.veriKaydet();
                    
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "acb " + player.getName() + " " + blokMiktari);
                    
                    player.sendMessage(ChatColor.GREEN + "--- TAPU ISLEMI BASARILI ---");
                    player.sendMessage(ChatColor.AQUA + "Alinan Blok: " + ChatColor.WHITE + blokMiktari);
                    player.sendMessage(ChatColor.AQUA + "Odenen Tutar: " + ChatColor.WHITE + "$" + toplamTutar);
                    player.sendMessage(ChatColor.YELLOW + "Altin kureginizi kullanarak arsanizi cizebilirsiniz.");
                }
            });
            return;
        }

        if (plugin.bankaIslemBekleyenler.containsKey(pId)) {
            event.setCancelled(true);
            String islem = plugin.bankaIslemBekleyenler.get(pId);
            String mesaj = event.getMessage().trim();
            plugin.bankaIslemBekleyenler.remove(pId);

            if (mesaj.equalsIgnoreCase("iptal")) { player.sendMessage(ChatColor.YELLOW + "İptal edildi."); return; }

            double miktar;
            try {
                miktar = Double.parseDouble(mesaj);
                if (miktar <= 0) throw new NumberFormatException();
            } catch (Exception e) { player.sendMessage(ChatColor.RED + "Gecersiz sayi."); return; }

            double bakiye = plugin.bankaHesaplari.getOrDefault(pId, 0.0);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (islem.equals("YATIR")) {
                    if (plugin.processBankDeposit(player, miktar)) {
                        plugin.bankaHesaplari.put(pId, bakiye + miktar);
                        player.sendMessage(ChatColor.GREEN + "[Banka] $" + miktar + " yatirildi."); plugin.veriKaydet();
                    }
                } else if (islem.equals("CEK")) {
                    if (bakiye >= miktar) {
                        if (player.getInventory().firstEmpty() != -1) {
                            plugin.bankaHesaplari.put(pId, bakiye - miktar);
                            player.getInventory().addItem(plugin.createEconomyNote(miktar));
                            player.sendMessage(ChatColor.GREEN + "[Banka] $" + miktar + " cektiniz."); plugin.veriKaydet();
                        } else { player.sendMessage(ChatColor.RED + "Envanteriniz dolu."); }
                    } else { player.sendMessage(ChatColor.RED + "Yetersiz bakiye."); }
                }
            });
        }
    }

    // YENİ: Phantom Doğmasını Tamamen Engelleyen Sistem
    @EventHandler
    public void onPhantomSpawn(CreatureSpawnEvent event) {
        if (event.getEntityType() == org.bukkit.entity.EntityType.PHANTOM) {
            event.setCancelled(true);
        }
    }
}