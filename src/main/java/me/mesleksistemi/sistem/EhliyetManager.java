package me.mesleksistemi.sistem;

import me.mesleksistemi.MeslekSistemi;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

public class EhliyetManager implements Listener, CommandExecutor {

    private final MeslekSistemi plugin;
    private final NamespacedKey ehliyetNpcKey;

    public EhliyetManager(MeslekSistemi plugin) {
        this.plugin = plugin;
        this.ehliyetNpcKey = new NamespacedKey(plugin, "ehliyet_npc");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;
        
        if (command.getName().equalsIgnoreCase("ehliyetnpc")) {
            if (!player.hasPermission("ehliyet.admin")) return true;
            if (args.length == 0) return true;
            
            if (args[0].equalsIgnoreCase("kur")) {
                Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
                npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
                npc.setCustomName(ChatColor.AQUA + "" + ChatColor.BOLD + "Sivil Havacılık & Uçuş Kursu");
                npc.setCustomNameVisible(true);
                npc.setProfession(Villager.Profession.CARTOGRAPHER);
                npc.getPersistentDataContainer().set(ehliyetNpcKey, PersistentDataType.BYTE, (byte) 1);
                player.sendMessage(ChatColor.GREEN + "Ehliyet NPC'si kuruldu!");
            } else if (args[0].equalsIgnoreCase("sil")) {
                int silinen = 0;
                for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                    if (entity instanceof Villager && entity.getPersistentDataContainer().has(ehliyetNpcKey, PersistentDataType.BYTE)) {
                        entity.remove(); silinen++;
                    }
                }
                player.sendMessage(ChatColor.GREEN + "" + silinen + " adet Ehliyet NPC'si silindi.");
            }
        }
        return true;
    }

    @EventHandler
    public void onNpcInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getRightClicked() instanceof Villager) {
            Villager npc = (Villager) event.getRightClicked();
            if (npc.getPersistentDataContainer().has(ehliyetNpcKey, PersistentDataType.BYTE)) {
                event.setCancelled(true);
                openEhliyetMenu(event.getPlayer());
            }
        }
    }

    private void openEhliyetMenu(Player player) {
        Inventory gui = Bukkit.createInventory(null, 27, ChatColor.DARK_AQUA + "Uçuş Kursu & Ehliyet");
        
        ItemStack ehliyet = new ItemStack(Material.ELYTRA);
        ItemMeta meta = ehliyet.getItemMeta();
        
        if (plugin.elytraEhliyetleri.contains(player.getUniqueId())) {
            meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Sivil Uçuş Ehliyetiniz Bulunuyor");
            meta.setLore(Arrays.asList(ChatColor.GRAY + "Gökyüzünün tadını çıkarın!"));
        } else {
            meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Elytra Uçuş Ehliyeti Al");
            meta.setLore(Arrays.asList(
                ChatColor.YELLOW + "Ehliyet Harcı: " + ChatColor.GREEN + "$" + String.format(java.util.Locale.US, "%.2f", plugin.ehliyetFiyati * (1.0 - plugin.kickOran(player))) + (plugin.kickOran(player) > 0 ? ChatColor.LIGHT_PURPLE + " (Kick indirimi)" : ""),
                ChatColor.GRAY + "Sadece lisanslı pilotlar süzülebilir.",
                ChatColor.GRAY + "Satın almak için tıklayın."
            ));
        }
        ehliyet.setItemMeta(meta);
        gui.setItem(13, ehliyet);
        
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();
        Player player = (Player) event.getWhoClicked();
        ItemStack clicked = event.getCurrentItem();
        
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (title.equals(ChatColor.DARK_AQUA + "Uçuş Kursu & Ehliyet")) {
            event.setCancelled(true);
            if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
            
            if (clicked.getType() == Material.ELYTRA) {
                if (plugin.elytraEhliyetleri.contains(player.getUniqueId())) {
                    player.sendMessage(ChatColor.RED + "Zaten uçuş ehliyetine sahipsiniz.");
                    return;
                }
                
                // Meslek sistemindeki aynı kasa güvenlik kontrolü
                if (plugin.getKasa() == null) {
                    player.sendMessage(ChatColor.RED + "Belediye kasası aktif değil, ehliyet harcı tahsil edilemiyor!");
                    player.closeInventory();
                    return;
                }

                // Parayı fiziksel olarak çekip Belediye Kasasına (Chest'e) aktarıyor
                if (plugin.processPaymentToKasa(player, Math.round(plugin.ehliyetFiyati * (1.0 - plugin.kickOran(player)) * 100.0) / 100.0)) {
                    plugin.elytraEhliyetleri.add(player.getUniqueId());
                    plugin.veriKaydet();
                    player.closeInventory();
                    
                    player.sendMessage(ChatColor.AQUA + "=============================");
                    player.sendMessage(ChatColor.GREEN + "Tebrikler! " + ChatColor.GOLD + "Sivil Uçuş Lisansınızı" + ChatColor.GREEN + " başarıyla aldınız.");
                    player.sendMessage(ChatColor.GRAY + "Artık Elytra ile özgürce gökyüzünde süzülebilirsiniz.");
                    player.sendMessage(ChatColor.AQUA + "=============================");
                }
            }
        }
    }

    // İŞTE BÜYÜNÜN KOPTUĞU YER: UÇUŞU İPTAL ETME
    @EventHandler
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            
            if (event.isGliding()) { // Oyuncu süzülmeye başladığında
                if (!plugin.elytraEhliyetleri.contains(player.getUniqueId())) {
                    // Adminlerin bypass (es geçme) yetkisi varsa düşmezler, RP içi herkes düşer
                    if (!player.hasPermission("ehliyet.bypass")) {
                        event.setCancelled(true);
                        player.sendMessage(ChatColor.DARK_RED + "[Sistem] " + ChatColor.RED + "Geçerli bir uçuş ehliyetiniz bulunmuyor! Süzülemezsiniz.");
                    }
                }
            }
        }
    }
}