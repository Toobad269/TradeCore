package de.marktwerk;

import static de.marktwerk.Store.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;

/**
 * Explicit prepare -> physical mutation/save -> DB commit. Ambiguous failures remain quarantined.
 */
public final class Physical {
  private final MarktWerk p;
  public final Set<UUID> busy = new HashSet<>();
  public final Set<UUID> inFlight = new HashSet<>();
  public final Set<String> inFlightChests = new HashSet<>();
  public final Set<String> lockedChests = new HashSet<>();

  public Physical(MarktWerk p) {
    this.p = p;
  }

  public void intake(
      Player player,
      String permission,
      ItemStack template,
      int quantity,
      Function<JsonObject, String> prepare,
      Consumer<String> finish) {
    p.require(player, permission);
    if (!busy.add(player.getUniqueId())) throw new IllegalArgumentException(p.cfg.message("busy"));
    inFlight.add(player.getUniqueId());
    try {
      player.closeInventory();
      var after = Items.afterRemove(player.getInventory(), template, quantity);
      var evidence = Items.evidence(player.getInventory(), after);
      String before = s(evidence, "beforeHash");
      p.db
          .submit(() -> prepare.apply(evidence))
          .whenComplete(
              (op, error) ->
                  p.main(
                      () -> {
                        if (error != null) {
                          busy.remove(player.getUniqueId());
                          inFlight.remove(player.getUniqueId());
                          p.error(player, error);
                          return;
                        }
                        if (!player.isOnline()
                            || player.isDead()
                            || !before.equals(
                                Items.fingerprint(player.getInventory().getStorageContents()))) {
                          cancel(player, op, null);
                          return;
                        }
                        try {
                          p.require(player, permission);
                        } catch (Throwable e) {
                          cancel(player, op, null);
                          return;
                        }
                        try {
                          player.getInventory().setStorageContents(after);
                          player.saveData();
                          p.db
                              .submit(
                                  () -> {
                                    finish.accept(op);
                                    return null;
                                  })
                              .whenComplete((v, e) -> p.main(() -> end(player, e, null)));
                        } catch (Throwable e) {
                          end(player, e, null);
                        }
                      }));
    } catch (Throwable e) {
      busy.remove(player.getUniqueId());
      inFlight.remove(player.getUniqueId());
      throw e;
    }
  }

  public void claim(Player player, JsonObject mail) {
    p.require(player, "claim");
    if (!busy.add(player.getUniqueId())) throw new IllegalArgumentException(p.cfg.message("busy"));
    inFlight.add(player.getUniqueId());
    try {
      player.closeInventory();
      var item = Items.decode(s(mail, "item"));
      var after = Items.afterAdd(player.getInventory(), item);
      var evidence = Items.evidence(player.getInventory(), after);
      String before = s(evidence, "beforeHash");
      p.db
          .submit(
              () -> p.market.prepareClaim(player.getUniqueId().toString(), s(mail, "id"), evidence))
          .whenComplete(
              (op, error) ->
                  p.main(
                      () -> {
                        if (error != null) {
                          busy.remove(player.getUniqueId());
                          inFlight.remove(player.getUniqueId());
                          p.error(player, error);
                          return;
                        }
                        if (!player.isOnline()
                            || player.isDead()
                            || !before.equals(
                                Items.fingerprint(player.getInventory().getStorageContents()))) {
                          cancel(player, op, null);
                          return;
                        }
                        try {
                          p.require(player, "claim");
                        } catch (Throwable e) {
                          cancel(player, op, null);
                          return;
                        }
                        try {
                          player.getInventory().setStorageContents(after);
                          player.saveData();
                          p.db
                              .submit(
                                  () -> {
                                    p.market.finishClaim(op);
                                    return null;
                                  })
                              .whenComplete((v, e) -> p.main(() -> end(player, e, null)));
                        } catch (Throwable e) {
                          end(player, e, null);
                        }
                      }));
    } catch (Throwable e) {
      busy.remove(player.getUniqueId());
      inFlight.remove(player.getUniqueId());
      throw e;
    }
  }

  public Chest chest(JsonObject shop) {
    World world = Bukkit.getWorld(UUID.fromString(s(shop, "world")));
    int x = (int) n(shop, "x"), y = (int) n(shop, "y"), z = (int) n(shop, "z");
    if (world == null || !world.isChunkLoaded(x >> 4, z >> 4))
      throw new IllegalArgumentException("Shop-Chunk ist nicht geladen");
    if (!(world.getBlockAt(x, y, z).getState() instanceof Chest chest)
        || chest.getInventory().getHolder() instanceof DoubleChest)
      throw new IllegalArgumentException("Shop Chest Missing or Modified");
    return chest;
  }

  public void chestBuy(Player buyer, JsonObject shop, JsonObject offer) {
    p.require(buyer, "chest");
    p.market.enabled("chestShops");
    Chest chest = chest(shop);
    String key = p.protection.key(chest.getLocation());
    if (lockedChests.contains(key) || !busy.add(buyer.getUniqueId()))
      throw new IllegalArgumentException(p.cfg.message("busy"));
    lockedChests.add(key);
    inFlight.add(buyer.getUniqueId());
    inFlightChests.add(key);
    var chunk = chest.getChunk();
    chunk.addPluginChunkTicket(p);
    try {
      buyer.closeInventory();
      for (var viewer : List.copyOf(chest.getBlockInventory().getViewers()))
        viewer.closeInventory();
      ItemStack template = Items.decode(s(offer, "item"));
      var after =
          Items.afterRemove(chest.getBlockInventory(), template, (int) n(offer, "quantity"));
      var evidence = Items.evidence(chest.getBlockInventory(), after);
      template.setAmount((int) n(offer, "quantity"));
      String delivery = Items.encode(template);
      evidence.addProperty("delivery", delivery);
      String before = s(evidence, "beforeHash");
      p.db
          .submit(
              () -> {
                return p.market.prepareChest(
                    buyer.getUniqueId().toString(),
                    s(offer, "id"),
                    n(shop, "version"),
                    n(offer, "price"),
                    evidence);
              })
          .whenComplete(
              (op, error) ->
                  p.main(
                      () -> {
                        if (error != null) {
                          unlock(buyer, key);
                          chunk.removePluginChunkTicket(p);
                          p.error(buyer, error);
                          return;
                        }
                        Chest current;
                        try {
                          p.require(buyer, "chest");
                          current = chest(shop);
                          if (!before.equals(
                              Items.fingerprint(current.getBlockInventory().getStorageContents())))
                            throw new IllegalArgumentException("Chest Contents Changed");
                        } catch (Throwable e) {
                          cancel(buyer, op, key);
                          chunk.removePluginChunkTicket(p);
                          return;
                        }
                        try {
                          current.getBlockInventory().setStorageContents(after);
                          current.getWorld().save();
                          p.db
                              .submit(
                                  () -> {
                                    p.market.finishChest(op, delivery);
                                    return null;
                                  })
                              .whenComplete(
                                  (v, e) ->
                                      p.main(
                                          () -> {
                                            chunk.removePluginChunkTicket(p);
                                            end(buyer, e, key);
                                          }));
                        } catch (Throwable e) {
                          chunk.removePluginChunkTicket(p);
                          end(buyer, e, key);
                        }
                      }));
    } catch (Throwable e) {
      unlock(buyer, key);
      chunk.removePluginChunkTicket(p);
      throw e;
    }
  }

  private void cancel(Player player, String op, String key) {
    p.db
        .submit(
            () -> {
              p.market.resolve(
                  op, false, "Cancelled Before Any Physical Change");
              return null;
            })
        .whenComplete((v, e) -> p.main(() -> end(player, e, key)));
  }

  private void unlock(Player player, String key) {
    busy.remove(player.getUniqueId());
    inFlight.remove(player.getUniqueId());
    if (key != null) {
      lockedChests.remove(key);
      inFlightChests.remove(key);
    }
  }

  private void end(Player player, Throwable error, String key) {
    if (error == null) {
      unlock(player, key);
      player.sendMessage(p.cfg.message("success"));
    } else {
      inFlight.remove(player.getUniqueId());
      if (key != null) inFlightChests.remove(key);
      p.getLogger()
          .severe(
              "Inventory operation remains locked; inspect /markt recovery. "
                  + error.getClass().getSimpleName());
      player.sendMessage(p.cfg.message("recovery"));
    }
  }
}
