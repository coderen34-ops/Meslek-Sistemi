package me.mesleksistemi;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

public class Commands implements CommandExecutor {

    private final MeslekSistemi plugin;

    public Commands(MeslekSistemi plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        
        if (command.getName().equalsIgnoreCase("rehber")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            player.getInventory().addItem(plugin.getRehberKitabi());
            player.sendMessage(ChatColor.GREEN + "Şehir Rehberi envanterinize eklendi!");
            return true;
        }

        if (command.getName().equalsIgnoreCase("meslekata") && sender.hasPermission("meslek.admin")) {
            if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Kullanim: /meslekata <oyuncu> <meslek>"); return true; }
            Player hedef = Bukkit.getPlayer(args[0]);
            String yeniMeslek = args[1].toLowerCase();
            if (hedef != null) {
                plugin.oyuncuMeslekCache.put(hedef.getUniqueId(), yeniMeslek);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + hedef.getName() + " parent set " + yeniMeslek);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + hedef.getName() + " " + yeniMeslek);
                sender.sendMessage(ChatColor.GREEN + hedef.getName() + " meslegi " + yeniMeslek + " yapildi!");
                hedef.sendMessage(ChatColor.GREEN + "Mesleginiz " + yeniMeslek + " olarak degistirildi.");
                plugin.veriKaydet();
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("primver") && sender.hasPermission("meslek.admin")) {
            if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Kullanim: /primver <oyuncu> <miktar>"); return true; }
            Player hedef = Bukkit.getPlayer(args[0]);
            if (hedef != null) {
                try {
                    double miktar = Double.parseDouble(args[1]);
                    double bakiye = plugin.bankaHesaplari.getOrDefault(hedef.getUniqueId(), 0.0);
                    plugin.bankaHesaplari.put(hedef.getUniqueId(), bakiye + miktar);
                    hedef.sendMessage(ChatColor.GREEN + "[Banka] Hesabiniza " + miktar + "$ prim yatirildi!");
                    sender.sendMessage(ChatColor.GREEN + "Prim yatirildi.");
                } catch (Exception e) { sender.sendMessage(ChatColor.RED + "Gecersiz miktar!"); }
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("yasalar")) {
            if (!(sender instanceof Player)) return true;
            Player player = (Player) sender;
            player.sendMessage(ChatColor.YELLOW + "========================================");
            player.sendMessage(ChatColor.GOLD + "Şehrin güncel yasalarını okumak için Belediye");
            player.sendMessage(ChatColor.GOLD + "binasındaki " + ChatColor.WHITE + ChatColor.BOLD + "Yasa Kürsüsü" + ChatColor.GOLD + "'ne gitmelisiniz.");
            player.sendMessage(ChatColor.YELLOW + "========================================");
            return true;
        }

        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("kasaayarla") && player.hasPermission("meslek.admin")) {
            Block block = player.getTargetBlockExact(5);
            if (block != null && block.getState() instanceof Chest) {
                plugin.kasaKonumu = block.getLocation(); plugin.veriKaydet();
                player.sendMessage(ChatColor.GREEN + "Belediye Kasasi ayarlandi!");
            } else { player.sendMessage(ChatColor.RED + "Lutfen bir sandiga bakin."); }
            return true;
        }

        if (command.getName().equalsIgnoreCase("kursuayarla") && player.hasPermission("meslek.admin")) {
            Block block = player.getTargetBlockExact(5);
            if (block != null && block.getType() == Material.LECTERN) {
                plugin.kursuKonumu = block.getLocation(); plugin.veriKaydet();
                player.sendMessage(ChatColor.GREEN + "Başvuru Kursusu ayarlandi!");
            } else { player.sendMessage(ChatColor.RED + "Lutfen bir kursuye bakin."); }
            return true;
        }

        if (command.getName().equalsIgnoreCase("yasakursuayarla") && player.hasPermission("meslek.admin")) {
            Block block = player.getTargetBlockExact(5);
            if (block != null && block.getType() == Material.LECTERN) {
                plugin.yasaKursuKonumu = block.getLocation(); plugin.veriKaydet();
                player.sendMessage(ChatColor.GREEN + "Anayasa Kursusu ayarlandi! Artik yasalar buradan okunacak.");
            } else { player.sendMessage(ChatColor.RED + "Lutfen bir kursuye bakin."); }
            return true;
        }

        // GÜNCELLENEN KISIM: OHAL Bekleme Süresi Kontrolü
        if (command.getName().equalsIgnoreCase("ohal") && player.hasPermission("meslek.baskan")) {
            if (plugin.ohalAktif) {
                plugin.ohalAktif = false;
                plugin.sonOhalBitisZamani = System.currentTimeMillis();
                plugin.veriKaydet();
                Bukkit.broadcastMessage(ChatColor.GREEN + "Olağanüstü hal (Sokağa çıkma yasağı) kaldırıldı.");
                plugin.ohalBar.removeAll();
            } else {
                long beklemeSuresiMillis = 30 * 60 * 1000L; // 30 Dakika
                long gecenZaman = System.currentTimeMillis() - plugin.sonOhalBitisZamani;
                
                if (gecenZaman < beklemeSuresiMillis) {
                    long kalanDakika = (beklemeSuresiMillis - gecenZaman) / 60000L;
                    long kalanSaniye = ((beklemeSuresiMillis - gecenZaman) / 1000L) % 60L;
                    player.sendMessage(ChatColor.RED + "OHAL ilan edebilmek için " + kalanDakika + " dakika " + kalanSaniye + " saniye daha beklemelisiniz.");
                    return true;
                }

                plugin.ohalAktif = true;
                plugin.veriKaydet();
                Bukkit.broadcastMessage("");
                Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "★★★ OLAĞANÜSTÜ HAL İLAN EDİLDİ ★★★");
                Bukkit.broadcastMessage(ChatColor.RED + "Belediye Başkanı kararıyla sokağa çıkma yasağı başlamıştır!");
                Bukkit.broadcastMessage(ChatColor.RED + "Güvenliğiniz için derhal evlerinize dönün!");
                Bukkit.broadcastMessage("");
                
                for (Player p : Bukkit.getOnlinePlayers()) {
                    plugin.ohalBar.addPlayer(p);
                    p.sendTitle(ChatColor.DARK_RED + "SOKAĞA ÇIKMA YASAĞI", ChatColor.RED + "Lütfen derhal evinize dönün!", 10, 70, 20);
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.0f);
                }
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("maaslar") && player.hasPermission("meslek.baskan")) {
            if (args.length == 0) { player.sendMessage(ChatColor.RED + "Kullanım: /maaslar <ac|kapat>"); return true; }
            if (args[0].equalsIgnoreCase("kapat")) {
                plugin.maaslarAcik = false;
                plugin.veriKaydet();
                player.sendMessage(ChatColor.GREEN + "Maaşlar başarıyla DURDURULDU. (Tasarruf Tedbirleri devrede)");
            } else if (args[0].equalsIgnoreCase("ac")) {
                plugin.maaslarAcik = true;
                plugin.veriKaydet();
                player.sendMessage(ChatColor.GREEN + "Maaşlar tekrar AÇILDI. Memurlar bir sonraki döngüde ödeme alacak.");
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("yasayayinla") && player.hasPermission("meslek.baskan")) {
            ItemStack item = player.getInventory().getItemInMainHand();
            if (item != null && item.getType() == Material.WRITTEN_BOOK) {
                plugin.yasaKitabi = item.clone();
                plugin.veriKaydet();
                Bukkit.broadcastMessage("");
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "📜 YENİ KANUN YAYINLANDI!");
                Bukkit.broadcastMessage(ChatColor.YELLOW + "Belediye Başkanı anayasaya yeni maddeler ekledi.");
                Bukkit.broadcastMessage(ChatColor.YELLOW + "Belediye binasındaki Yasa Kürsüsü'nden okuyabilirsiniz.");
                Bukkit.broadcastMessage("");
            } else { 
                player.sendMessage(ChatColor.RED + "Elinizde imzalanmış bir kitap (Written Book) tutmalısınız."); 
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("mesleknpc") && player.hasPermission("meslek.admin")) {
            handleNpcCommand(player, args, plugin.npcKey, "Meslek Basvurusu", Villager.Profession.LIBRARIAN);
            return true;
        }
        if (command.getName().equalsIgnoreCase("bankanpc") && player.hasPermission("meslek.admin")) {
            handleNpcCommand(player, args, plugin.bankaNpcKey, "Banka Gorevlisi", Villager.Profession.CARTOGRAPHER);
            return true;
        }
        if (command.getName().equalsIgnoreCase("nufusnpc") && player.hasPermission("meslek.admin")) {
            handleNpcCommand(player, args, plugin.nufusNpcKey, "Nüfus Müdürlüğü", Villager.Profession.CLERIC);
            return true;
        }
        if (command.getName().equalsIgnoreCase("tapunpc") && player.hasPermission("meslek.admin")) {
            handleNpcCommand(player, args, plugin.tapuNpcKey, "Tapu Mudurlugu", Villager.Profession.MASON);
            return true;
        }

        if (command.getName().equalsIgnoreCase("kimlikbiyomsec")) {
            if (args.length == 0) return true;
            UUID pId = player.getUniqueId();
            if (plugin.kimlikAsama.containsKey(pId) && plugin.kimlikAsama.get(pId) == 4) {
                plugin.kimlikAsama.remove(pId);
                MeslekSistemi.GeciciKimlik veri = plugin.geciciKimlikler.get(pId);
                veri.kutuk = args[0];
                generateKimlik(player, veri);
                plugin.geciciKimlikler.remove(pId);
            }
            return true;
        }

        if (command.getName().equalsIgnoreCase("meslekler")) {
            if (!player.hasPermission("meslek.admin")) { player.sendMessage(ChatColor.RED + "Sadece NPC'den acilir."); return true; }
            plugin.getMenuManager().openMeslekMenu(player); return true;
        }
        if (command.getName().equalsIgnoreCase("basvurular")) {
            if (!player.hasPermission("meslek.admin")) { player.sendMessage(ChatColor.RED + "Sadece kursuden acilir."); return true; }
            plugin.getMenuManager().openBasvuruMenu(player); return true;
        }
        if (command.getName().equalsIgnoreCase("tapumenusu")) {
            plugin.getMenuManager().openTapuMenu(player); return true;
        }

        if (command.getName().equalsIgnoreCase("basvurucevapla") && player.hasPermission("meslek.baskan") && args.length >= 2) {
            UUID basvuranUUID = UUID.fromString(args[0]);
            String islem = args[1];
            if (!plugin.aktifBasvurular.containsKey(basvuranUUID)) { player.sendMessage(ChatColor.RED + "Gecersiz basvuru."); return true; }
            
            MeslekSistemi.Basvuru basvuru = plugin.aktifBasvurular.get(basvuranUUID);
            Player basvuranPlayer = Bukkit.getPlayer(basvuranUUID);

            if (islem.equalsIgnoreCase("kabulet")) {
                plugin.aktifBasvurular.remove(basvuranUUID);
                player.sendMessage(ChatColor.GREEN + "Basvuru kabul edildi!");
                if (basvuranPlayer != null) basvuranPlayer.sendMessage(ChatColor.GREEN + "Basvurunuz kabul edildi.");
                
                String pName = Bukkit.getOfflinePlayer(basvuranUUID).getName();
                plugin.oyuncuMeslekCache.put(basvuranUUID, basvuru.meslek);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + pName + " parent set " + basvuru.meslek);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + pName + " " + basvuru.meslek);
            } else if (islem.equalsIgnoreCase("reddet")) {
                plugin.aktifBasvurular.remove(basvuranUUID);
                player.sendMessage(ChatColor.RED + "Basvuru reddedildi!");
                if (basvuranPlayer != null) basvuranPlayer.sendMessage(ChatColor.RED + "Basvurunuz reddedildi.");
            }
            player.closeInventory(); plugin.veriKaydet(); return true;
        }
        return false;
    }

    private void handleNpcCommand(Player player, String[] args, NamespacedKey key, String name, Villager.Profession prof) {
        if (args.length == 0) { player.sendMessage(ChatColor.RED + "Kullanım: /<komut> <kur|sil>"); return; }
        if (args[0].equalsIgnoreCase("kur")) {
            Villager npc = (Villager) player.getWorld().spawnEntity(player.getLocation(), EntityType.VILLAGER);
            npc.setAI(false); npc.setInvulnerable(true); npc.setCollidable(false);
            npc.setCustomName(ChatColor.GOLD + "" + ChatColor.BOLD + name);
            npc.setCustomNameVisible(true); npc.setProfession(prof);
            npc.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
            player.sendMessage(ChatColor.GREEN + name + " NPC'si kuruldu!");
        } else if (args[0].equalsIgnoreCase("sil")) {
            int silinen = 0;
            for (Entity entity : player.getNearbyEntities(5, 5, 5)) {
                if (entity instanceof Villager && entity.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                    entity.remove(); silinen++;
                }
            }
            player.sendMessage(ChatColor.GREEN + "" + silinen + " adet NPC silindi.");
        }
    }

    private void generateKimlik(Player player, MeslekSistemi.GeciciKimlik veri) {
        org.bukkit.inventory.ItemStack kimlik = new org.bukkit.inventory.ItemStack(Material.PAPER);
        org.bukkit.inventory.meta.ItemMeta meta = kimlik.getItemMeta();
        String kimlikNo = "TR-" + (100000 + new java.util.Random().nextInt(900000));
        meta.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "Pasaport & Kimlik Kartı");
        java.util.List<String> lore = new java.util.ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "Resmi Belge"); lore.add("");
        lore.add(ChatColor.GRAY + "İsim: " + ChatColor.WHITE + veri.isim);
        lore.add(ChatColor.GRAY + "Soyisim: " + ChatColor.WHITE + veri.soyisim);
        lore.add(ChatColor.GRAY + "Yaş: " + ChatColor.WHITE + veri.yas);
        lore.add(ChatColor.GRAY + "Kütük: " + ChatColor.WHITE + veri.kutuk);
        lore.add(""); lore.add(ChatColor.DARK_RED + "Kimlik No: " + ChatColor.RED + kimlikNo);
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(plugin.kimlikIdKey, PersistentDataType.STRING, kimlikNo);
        kimlik.setItemMeta(meta);

        if (player.getInventory().firstEmpty() != -1) player.getInventory().addItem(kimlik);
        else player.getWorld().dropItem(player.getLocation(), kimlik);

        plugin.oyuncuMeslekCache.put(player.getUniqueId(), "vatandas");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + player.getName() + " parent set vatandas");
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tag set " + player.getName() + " vatandas");
        
        double mevcutBakiye = plugin.bankaHesaplari.getOrDefault(player.getUniqueId(), 0.0);
        plugin.bankaHesaplari.put(player.getUniqueId(), mevcutBakiye + 1000.0);
        
        plugin.veriKaydet();

        player.sendMessage(ChatColor.GREEN + "--- İŞLEM TAMAMLANDI ---");
        player.sendMessage(ChatColor.AQUA + "Memur: " + ChatColor.WHITE + "Artık ülkenin yasal bir Vatandaşısın!");
        player.sendMessage(ChatColor.GOLD + "Memur: " + ChatColor.WHITE + "Devletimiz yeni vatandaşlara destek olmak amacıyla banka hesabınıza " + ChatColor.GREEN + "$1000" + ChatColor.WHITE + " yatırdı.");
    }
}