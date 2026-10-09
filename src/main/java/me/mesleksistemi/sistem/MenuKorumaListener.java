package me.mesleksistemi.sistem;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;

/**
 * Eklentinin sahipsiz (holder'ı olmayan) sandık menülerine eşya sürükleyerek koymayı engeller.
 * Menü tıklamaları ilgili yöneticilerde iptal ediliyor ama sürükleme (drag) ayrı bir olaydır;
 * engellenmezse menüye bırakılan eşya menü kapanınca kaybolur.
 */
public class MenuKorumaListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory ust = event.getView().getTopInventory();
        if (ust.getType() != InventoryType.CHEST || ust.getHolder() != null) return;
        int boyut = ust.getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < boyut) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
