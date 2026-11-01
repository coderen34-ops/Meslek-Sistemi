package me.mesleksistemi.kacak;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import me.mesleksistemi.MeslekSistemi;

/**
 * Kaçak mallar Ender sandığına konamaz (buharlaşır), Nether'de bir sandığa/fıçıya/shulker'a
 * konursa konteyner yatak gibi patlar.
 */
public class KacakDepoKoruma implements Listener {
    private final MeslekSistemi plugin;

    public KacakDepoKoruma(MeslekSistemi plugin) { this.plugin = plugin; }

    private boolean kacak(ItemStack it) {
        return it != null && KacakMal.kacakMalMi(plugin, it);
    }

    /** 0 = tehlikesiz, 1 = ender sandığı, 2 = nether konteyneri */
    private int tur(Inventory inv) {
        if (inv == null) return 0;
        InventoryType t = inv.getType();
        if (t == InventoryType.ENDER_CHEST) return 1;
        if (t == InventoryType.CHEST || t == InventoryType.BARREL || t == InventoryType.SHULKER_BOX) {
            Location l = inv.getLocation();
            if (l != null && l.getWorld() != null && l.getWorld().getEnvironment() == World.Environment.NETHER) return 2;
        }
        return 0;
    }

    private void uygula(Player p, Inventory hedef, int tur) {
        if (tur == 1) {
            Location l = p.getLocation().add(0, 1, 0);
            p.getWorld().spawnParticle(Particle.CLOUD, l, 25, 0.3, 0.4, 0.3, 0.02);
            p.getWorld().playSound(l, Sound.BLOCK_FIRE_EXTINGUISH, 1f, 0.8f);
            p.sendMessage(ChatColor.RED + "Kaçak mal Ender sandığına girer girmez buharlaştı!");
            plugin.getServer().getScheduler().runTask(plugin, p::updateInventory);
        } else if (tur == 2) {
            Location l = hedef.getLocation();
            if (l == null) return;
            p.sendMessage(ChatColor.RED + "Kaçak mal Nether'de depolanamaz, sandık patladı!");
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                p.closeInventory();
                l.getWorld().createExplosion(l.clone().add(0.5, 0.5, 0.5), 5f, true, true);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTikla(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        int tur = tur(top);
        if (tur == 0) return;
        boolean ustte = e.getClickedInventory() != null && e.getClickedInventory().equals(top);
        boolean tetik = false;
        switch (e.getAction()) {
            case PLACE_ALL, PLACE_SOME, PLACE_ONE, SWAP_WITH_CURSOR:
                tetik = ustte && kacak(e.getCursor());
                if (tetik) p.setItemOnCursor(null);
                break;
            case MOVE_TO_OTHER_INVENTORY:
                tetik = !ustte && kacak(e.getCurrentItem());
                if (tetik) e.setCurrentItem(null);
                break;
            case HOTBAR_SWAP: {
                if (!ustte) break;
                int b = e.getHotbarButton();
                ItemStack it = b >= 0 ? p.getInventory().getItem(b) : p.getInventory().getItemInOffHand();
                tetik = kacak(it);
                if (tetik) {
                    if (b >= 0) p.getInventory().setItem(b, null); else p.getInventory().setItemInOffHand(null);
                }
                break;
            }
            default:
                break;
        }
        if (!tetik) return;
        e.setCancelled(true);
        uygula(p, top, tur);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSurukle(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Inventory top = e.getView().getTopInventory();
        int tur = tur(top);
        if (tur == 0 || !kacak(e.getOldCursor())) return;
        int boy = top.getSize();
        for (int s : e.getRawSlots()) {
            if (s < boy) {
                e.setCancelled(true);
                p.setItemOnCursor(null);
                uygula(p, top, tur);
                return;
            }
        }
    }

    /** Hopper vb. ile nether konteynerine kaçak mal sokulması. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryMoveItemEvent e) {
        if (!kacak(e.getItem())) return;
        if (tur(e.getDestination()) != 2) return;
        e.setCancelled(true);
        Location l = e.getDestination().getLocation();
        if (l == null) return;
        plugin.getServer().getScheduler().runTask(plugin,
                () -> l.getWorld().createExplosion(l.clone().add(0.5, 0.5, 0.5), 5f, true, true));
    }
}
