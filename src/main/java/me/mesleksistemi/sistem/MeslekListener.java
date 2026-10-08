package me.mesleksistemi.sistem;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
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
import org.bukkit.inventory.EquipmentSlot;
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

    private static final int BANKA_ACILIS = 8, BANKA_KAPANIS = 17;

    @EventHandler
    public void onNpcClick(PlayerInteractEntityEvent event) {
        // Sağ tık her iki el için ayrı tetiklenir; menü iki kez açılmasın
        if (event.getHand() != EquipmentSlot.HAND) return;
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
                // Banka mesaisi: oyun saatiyle 08:00 - 17:00 (Minecraft'ta 0 tick = 06:00)
                long tick = npc.getWorld().getTime();
                int saat = (int) ((tick / 1000 + 6) % 24), dakika = (int) (tick % 1000 * 60 / 1000);
                if (saat < BANKA_ACILIS || saat >= BANKA_KAPANIS) {
                    player.sendMessage(ChatColor.RED + "Banka şu an kapalı! Mesai saatleri: " + String.format("%02d:00 - %02d:00", BANKA_ACILIS, BANKA_KAPANIS)
                            + ChatColor.GRAY + " (Şu an " + String.format("%02d:%02d", saat, dakika) + ")");
                    return;
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
        if (event.getHand() != EquipmentSlot.HAND) return;
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

    // Chat olayı ana thread dışında gelir. Bekleyen bir işlem varsa mesaj chat'e düşmez,
    // asıl işlem (banka, başvuru, tapu) ana thread'de yapılır ki veriler güvenle değişsin.
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID pId = player.getUniqueId();
        plugin.sonHareketZamani.put(pId, System.currentTimeMillis());

        if (!plugin.basvuruBekleyenler.containsKey(pId) && !plugin.tapuIslemBekleyenler.containsKey(pId)
                && !plugin.bankaIslemBekleyenler.containsKey(pId)) return;

        event.setCancelled(true);
        String mesaj = event.getMessage().trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) sohbetIsle(player, mesaj);
        });
    }

    private void sohbetIsle(Player player, String mesaj) {
        UUID pId = player.getUniqueId();

        if (plugin.basvuruBekleyenler.containsKey(pId)) {
            plugin.aktifBasvurular.put(pId, new MeslekSistemi.Basvuru(player.getName(), plugin.basvuruBekleyenler.get(pId), mesaj));
            plugin.basvuruBekleyenler.remove(pId); plugin.veriKaydet();
            player.sendMessage(ChatColor.GREEN + "Basvurunuz Belediye Baskanina iletildi!");
            // Çevrimiçi başkana bildirim ve zil sesi
            String meslekAdi = plugin.meslekGorunumAdlari.getOrDefault(plugin.aktifBasvurular.get(pId).meslek, plugin.aktifBasvurular.get(pId).meslek);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getUniqueId().equals(pId)) continue;
                if (!"belediyebaskani".equalsIgnoreCase(plugin.oyuncuMeslekCache.get(p.getUniqueId())) && !p.hasPermission("meslek.baskan")) continue;
                p.sendMessage(ChatColor.GOLD + "[Belediye] " + ChatColor.YELLOW + player.getName() + ChatColor.WHITE + " adlı vatandaş "
                        + ChatColor.AQUA + meslekAdi + ChatColor.WHITE + " mesleği için başvurdu. " + ChatColor.GRAY + "(Başvuru Kürsüsü'nden inceleyin)");
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 1.2f);
            }
            return;
        }

        if (plugin.tapuIslemBekleyenler.containsKey(pId)) {
            String islemTipi = plugin.tapuIslemBekleyenler.remove(pId);

            if (mesaj.equalsIgnoreCase("iptal")) { player.sendMessage(ChatColor.YELLOW + "Tapu islemi iptal edildi."); return; }

            int blokMiktari;
            try {
                blokMiktari = Integer.parseInt(mesaj);
                if (blokMiktari <= 0) throw new NumberFormatException();
            } catch (Exception e) { player.sendMessage(ChatColor.RED + "Gecersiz miktar. Sadece sayi giriniz."); return; }

            double blokFiyati = islemTipi.equals("ILK") ? plugin.ilkTapuBlokFiyati : plugin.genisletmeBlokFiyati;
            double toplamTutar = blokMiktari * blokFiyati;

            if (plugin.processPaymentToKasa(player, toplamTutar)) {
                if (islemTipi.equals("ILK")) plugin.tapuSahipleri.add(pId);
                plugin.veriKaydet();

                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "acb " + player.getName() + " " + blokMiktari);

                player.sendMessage(ChatColor.GREEN + "--- TAPU ISLEMI BASARILI ---");
                player.sendMessage(ChatColor.AQUA + "Alinan Blok: " + ChatColor.WHITE + blokMiktari);
                player.sendMessage(ChatColor.AQUA + "Odenen Tutar: " + ChatColor.WHITE + "$" + toplamTutar);
                player.sendMessage(ChatColor.YELLOW + "Altin kureginizi kullanarak arsanizi cizebilirsiniz.");
            }
            return;
        }

        if (plugin.bankaIslemBekleyenler.containsKey(pId)) {
            String islem = plugin.bankaIslemBekleyenler.remove(pId);

            if (mesaj.equalsIgnoreCase("iptal")) { player.sendMessage(ChatColor.YELLOW + "İptal edildi."); return; }

            double miktar;
            try {
                miktar = MeslekSistemi.parsePara(mesaj);
                if (miktar <= 0) throw new NumberFormatException();
            } catch (Exception e) { player.sendMessage(ChatColor.RED + "Gecersiz sayi."); return; }

            // Bakiye işlem anında okunur: arada maaş/kira gibi değişiklikler ezilmesin
            double bakiye = plugin.bankaHesaplari.getOrDefault(pId, 0.0);
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