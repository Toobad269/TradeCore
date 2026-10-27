package de.marktwerk;

import static de.marktwerk.Store.*;

import com.google.gson.JsonObject;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;

public final class Protection implements Listener {
  private final MarktWerk p;
  public final Map<String, JsonObject> shops = new HashMap<>();

  public Protection(MarktWerk p) {
    this.p = p;
  }

  public String key(Location l) {
    return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
  }

  public void load(List<JsonObject> all) {
    shops.clear();
    all.forEach(
        s -> shops.put(s(s, "world") + ":" + n(s, "x") + ":" + n(s, "y") + ":" + n(s, "z"), s));
  }

  public boolean protectedBlock(Block b) {
    return shops.containsKey(key(b.getLocation()));
  }

  public boolean locked(Player player) {
    return p.physical.busy.contains(player.getUniqueId());
  }

  private List<Block> blocks(Inventory inventory) {
    var holder = inventory.getHolder();
    if (holder instanceof DoubleChest dc)
      return List.of(((Chest) dc.getLeftSide()).getBlock(), ((Chest) dc.getRightSide()).getBlock());
    if (holder instanceof Chest c) return List.of(c.getBlock());
    return List.of();
  }

  private boolean protectedInventory(Inventory inv) {
    return blocks(inv).stream().anyMatch(this::protectedBlock);
  }

  private boolean lockedInventory(Inventory inv) {
    return blocks(inv).stream()
        .anyMatch(b -> p.physical.lockedChests.contains(key(b.getLocation())));
  }

  private boolean menu(ItemStack item) {
    return p.menuItem(item);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void interact(PlayerInteractEvent e) {
    if (menu(e.getItem())) {
      e.setCancelled(true);
      if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND)
        p.ui.invoke(e.getPlayer(), () -> p.ui.home(e.getPlayer()));
      return;
    }
    if (locked(e.getPlayer())) {
      e.setCancelled(true);
      return;
    }
    Block b = e.getClickedBlock();
    if (b != null && protectedBlock(b)) {
      var shop = shops.get(key(b.getLocation()));
      if (!s(shop, "owner").equals(e.getPlayer().getUniqueId().toString())
          || p.physical.lockedChests.contains(key(b.getLocation()))) {
        e.setCancelled(true);
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK)
          p.ui.invoke(e.getPlayer(), () -> p.ui.openShop(e.getPlayer(), s(shop, "id"), null));
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void open(InventoryOpenEvent e) {
    if (e.getPlayer() instanceof Player player) {
      for (Block b : blocks(e.getInventory()))
        if (protectedBlock(b)) {
          var shop = shops.get(key(b.getLocation()));
          if (lockedInventory(e.getInventory())
              || !s(shop, "owner").equals(player.getUniqueId().toString())) e.setCancelled(true);
        }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent e) {
    if (!(e.getWhoClicked() instanceof Player player)) return;
    if (p.ui.isMenu(e.getView().getTopInventory())) {
      e.setCancelled(true);
      p.ui.click(player, e);
      return;
    }
    if (locked(player)
        || lockedInventory(e.getView().getTopInventory())
        || menu(e.getCurrentItem())
        || menu(e.getCursor())
        || e.getHotbarButton() >= 0 && menu(player.getInventory().getItem(e.getHotbarButton()))
        || e.getClick() == ClickType.SWAP_OFFHAND && menu(player.getInventory().getItemInOffHand()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent e) {
    if (e.getWhoClicked() instanceof Player player
        && (locked(player)
            || p.ui.isMenu(e.getView().getTopInventory())
            || lockedInventory(e.getView().getTopInventory())
            || menu(e.getOldCursor()))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void drop(PlayerDropItemEvent e) {
    if (locked(e.getPlayer()) || menu(e.getItemDrop().getItemStack())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void pickup(EntityPickupItemEvent e) {
    if (menu(e.getItem().getItemStack())
        || e.getEntity() instanceof Player player && locked(player)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void hopper(InventoryMoveItemEvent e) {
    if (menu(e.getItem())
        || protectedInventory(e.getSource())
        || protectedInventory(e.getDestination())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void hopperPickup(InventoryPickupItemEvent e) {
    if (menu(e.getItem().getItemStack()) || protectedInventory(e.getInventory()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void swap(PlayerSwapHandItemsEvent e) {
    if (locked(e.getPlayer()) || menu(e.getMainHandItem()) || menu(e.getOffHandItem()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void consume(PlayerItemConsumeEvent e) {
    if (locked(e.getPlayer()) || menu(e.getItem())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void damage(EntityDamageEvent e) {
    if (e.getEntity() instanceof Player player && locked(player)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void breakBlock(BlockBreakEvent e) {
    if (protectedBlock(e.getBlock()) || locked(e.getPlayer())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void place(BlockPlaceEvent e) {
    if (locked(e.getPlayer()) || menu(e.getItemInHand())) {
      e.setCancelled(true);
      return;
    }
    if (e.getBlock().getType() == Material.CHEST
        || e.getBlock().getType() == Material.TRAPPED_CHEST)
      for (var face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST))
        if (protectedBlock(e.getBlock().getRelative(face))) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explode(EntityExplodeEvent e) {
    e.blockList().removeIf(this::protectedBlock);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void explode(BlockExplodeEvent e) {
    e.blockList().removeIf(this::protectedBlock);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void piston(BlockPistonExtendEvent e) {
    if (e.getBlocks().stream().anyMatch(this::protectedBlock)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void piston(BlockPistonRetractEvent e) {
    if (e.getBlocks().stream().anyMatch(this::protectedBlock)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void burn(BlockBurnEvent e) {
    if (protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void change(EntityChangeBlockEvent e) {
    if (protectedBlock(e.getBlock())) e.setCancelled(true);
  }

  @EventHandler
  public void death(PlayerDeathEvent e) {
    e.getDrops().removeIf(this::menu);
    if (e.getKeepInventory())
      for (int i = 0; i < e.getEntity().getInventory().getSize(); i++)
        if (menu(e.getEntity().getInventory().getItem(i)))
          e.getEntity().getInventory().setItem(i, null);
  }

  @EventHandler
  public void respawn(PlayerRespawnEvent e) {
    p.getServer().getScheduler().runTaskLater(p, () -> p.ensureMenu(e.getPlayer()), 2);
  }

  @EventHandler
  public void join(PlayerJoinEvent e) {
    p.join(e.getPlayer());
  }

  @EventHandler
  public void quit(PlayerQuitEvent e) {
    p.ui.forget(e.getPlayer());
  }

  @EventHandler
  public void close(InventoryCloseEvent e) {
    if (e.getPlayer() instanceof Player player)
      p.getServer()
          .getScheduler()
          .runTask(
              p,
              () -> {
                if (player.isOnline() && !locked(player)) p.ensureMenu(player);
              });
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void creative(InventoryCreativeEvent e) {
    if (menu(e.getCursor()) || menu(e.getCurrentItem())) e.setCancelled(true);
  }

  @EventHandler
  public void craft(PrepareItemCraftEvent e) {
    for (var item : e.getInventory().getMatrix()) if (menu(item)) e.getInventory().setResult(null);
  }

  @EventHandler
  public void smith(PrepareSmithingEvent e) {
    for (var item : e.getInventory().getContents()) if (menu(item)) e.setResult(null);
  }

  @EventHandler
  public void anvil(PrepareAnvilEvent e) {
    for (var item : e.getInventory().getStorageContents()) if (menu(item)) e.setResult(null);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void interactEntity(PlayerInteractEntityEvent e) {
    if (locked(e.getPlayer()) || menu(e.getPlayer().getInventory().getItem(e.getHand()))) {
      e.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void interactAtEntity(PlayerInteractAtEntityEvent e) {
    if (locked(e.getPlayer()) || menu(e.getPlayer().getInventory().getItem(e.getHand())))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void armorStand(PlayerArmorStandManipulateEvent e) {
    if (locked(e.getPlayer()) || menu(e.getPlayerItem()) || menu(e.getArmorStandItem()))
      e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void shoot(EntityShootBowEvent e) {
    if (menu(e.getBow())
        || menu(e.getConsumable())
        || e.getEntity() instanceof Player player && locked(player)) e.setCancelled(true);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void loadCrossbow(io.papermc.paper.event.entity.EntityLoadCrossbowEvent e) {
    if (e.getEntity() instanceof Player player) {
      if (locked(player)) {
        e.setCancelled(true);
        return;
      }
      for (var item : player.getInventory().getContents())
        if (menu(item)
            && (item.getType() == Material.ARROW
                || item.getType() == Material.SPECTRAL_ARROW
                || item.getType() == Material.TIPPED_ARROW
                || item.getType() == Material.FIREWORK_ROCKET)) {
          e.setCancelled(true);
          return;
        }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void resurrect(EntityResurrectEvent e) {
    if (e.getEntity() instanceof Player player
        && e.getHand() != null
        && menu(player.getInventory().getItem(e.getHand()))) e.setCancelled(true);
  }
}
