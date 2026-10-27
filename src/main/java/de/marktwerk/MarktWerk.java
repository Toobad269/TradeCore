package de.marktwerk;

import static de.marktwerk.Store.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class MarktWerk extends JavaPlugin {
  Settings cfg;
  Store db;
  Prices prices;
  Market market;
  Menus ui;
  Physical physical;
  Protection protection;
  Bedrock bedrock;
  WebApi api;
  private NamespacedKey menuKey;
  private final Set<UUID> activated = new HashSet<>();
  final Map<String, String> standardItems = new HashMap<>();

  @Override
  public void onEnable() {
    try {
      if (!Bukkit.getMinecraftVersion().equals("1.21.11"))
        throw new IllegalArgumentException(
            "This Plugin Supports Paper 1.21.11 Only");
      getDataFolder().mkdirs();
      for (String file : List.of("config.json", "prices.json"))
        if (!Files.exists(getDataFolder().toPath().resolve(file))) saveResource(file, false);
      cfg = new Settings(getDataFolder().toPath().resolve("config.json"));
      menuKey = new NamespacedKey(this, "menu");
      Material menu = Material.matchMaterial(cfg.text("menu", "material"));
      if (menu == null || !menu.isItem() || menu.isAir())
        throw new IllegalArgumentException("Invalid Menu Material");
      var catalogue =
          JsonParser.parseString(Files.readString(getDataFolder().toPath().resolve("prices.json")))
              .getAsJsonObject();
      var missing = new ArrayList<String>();
      for (Material material : Material.values())
        if (!material.isLegacy() && material.isItem() && !material.isAir()) {
          if (!catalogue.has(material.name())) missing.add(material.name());
          var item = new ItemStack(material);
          if (Items.allowed(item)) standardItems.put(material.name(), Items.encode(item));
        }
      for (String domain :
          List.of("excludedMaterials", "serverExcluded", "chestExcluded", "auctionExcluded"))
        for (var e : cfg.section("items").getAsJsonArray(domain))
          if (!catalogue.has(e.getAsString()))
            throw new IllegalArgumentException(
                "Unbekannter Item-Typ in items." + domain + ": " + e.getAsString());
      if (!missing.isEmpty())
        throw new IllegalArgumentException("Incomplete price catalogue: " + missing);
      prices = new Prices(cfg, getDataFolder().toPath().resolve("prices.json"));
      for (var a : prices.all())
        if (!standardItems.containsKey(a.material()))
          throw new IllegalArgumentException("Disallowed server shop item: " + a.material());
      db = new Store(getDataFolder().toPath().resolve("marktwerk.db"));
      market = new Market(db, cfg, prices);
      physical = new Physical(this);
      protection = new Protection(this);
      ui = new Menus(this);
      var shops = db.submit(() -> db.rows("SELECT * FROM shops")).join();
      protection.load(shops);
      var pending =
          db.submit(() -> db.rows("SELECT * FROM operations WHERE state='PREPARED'")).join();
      for (var row : pending) {
        physical.busy.add(UUID.fromString(s(row, "owner")));
        if (s(row, "kind").equals("CHEST_BUY")) {
          var payload = JsonParser.parseString(s(row, "payload")).getAsJsonObject();
          var shop =
              shops.stream().filter(sh -> s(sh, "id").equals(s(payload, "shop"))).findFirst();
          shop.ifPresent(
              sh ->
                  physical.lockedChests.add(
                      s(sh, "world") + ":" + n(sh, "x") + ":" + n(sh, "y") + ":" + n(sh, "z")));
        }
      }
      if (!pending.isEmpty())
        getLogger()
            .warning(pending.size() + " inventory operations require review: /markt recovery");
      if (cfg.on("bedrockForms") && getServer().getPluginManager().isPluginEnabled("floodgate"))
        bedrock = new Bedrock(this);
      if (cfg.on("vault") && getServer().getPluginManager().isPluginEnabled("Vault"))
        new VaultBridge(this).register();
      if (cfg.on("httpApi")) {
        api = new WebApi(this);
        api.start();
      }
      getServer().getPluginManager().registerEvents(protection, this);
      Objects.requireNonNull(getCommand("markt")).setExecutor(this);
      getServer()
          .getScheduler()
          .runTaskTimer(
              this,
              () -> {
                db.submit(
                        () -> {
                          market.expire();
                          return null;
                        })
                    .exceptionally(
                        e -> {
                          getLogger()
                              .severe("Wartung fehlgeschlagen: " + e.getClass().getSimpleName());
                          return null;
                        });
                for (var player : Bukkit.getOnlinePlayers())
                  if (!physical.busy.contains(player.getUniqueId())) ensureMenu(player);
              },
              20,
              100);
      Bukkit.getOnlinePlayers().forEach(this::join);
    } catch (Throwable e) {
      getLogger()
          .log(java.util.logging.Level.SEVERE, "MarktWerk konnte nicht sicher gestartet werden", e);
      getServer().getPluginManager().disablePlugin(this);
    }
  }

  public void main(Runnable task) {
    if (isEnabled()) getServer().getScheduler().runTask(this, task);
  }

  public <T> CompletableFuture<T> onMain(Supplier<T> task) {
    var future = new CompletableFuture<T>();
    main(
        () -> {
          try {
            future.complete(task.get());
          } catch (Throwable e) {
            future.completeExceptionally(e);
          }
        });
    return future;
  }

  public <T> void async(Player player, Supplier<T> task, Consumer<T> result) {
    db.submit(task)
        .whenComplete(
            (value, error) ->
                main(
                    () -> {
                      if (error != null) {
                        error(player, error);
                        return;
                      }
                      if (player.isOnline())
                        try {
                          result.accept(value);
                        } catch (Throwable e) {
                          error(player, e);
                        }
                    }));
  }

  public void error(CommandSender sender, Throwable error) {
    while (error instanceof CompletionException || error instanceof ExecutionException)
      error = error.getCause();
    if (error instanceof IllegalArgumentException || error instanceof ArithmeticException)
      sender.sendMessage(
          cfg.message("error") + Objects.toString(error.getMessage(), "Invalid Value"));
    else {
      sender.sendMessage(cfg.message("error") + "Internal Error; Please Have This Operation Reviewed.");
      getLogger().log(java.util.logging.Level.SEVERE, "Interner Vorgangsfehler", error);
    }
  }

  public void require(Player player, String permission) {
    if (!player.isOnline() || player.isDead() || !player.hasPermission("marktwerk." + permission))
      throw new IllegalArgumentException(cfg.message("unavailable"));
    if (cfg.on("menuItem")
        && !activated.contains(player.getUniqueId())
        && !player.hasPermission("marktwerk.admin"))
      throw new IllegalArgumentException(cfg.message("disabled"));
  }

  public boolean apiAllowed(String id) {
    var player = Bukkit.getPlayer(UUID.fromString(id));
    if (player == null) return false;
    try {
      require(player, "api");
      return cfg.on("httpApi") && cfg.on("chestShops") && player.hasPermission("marktwerk.chest");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public void join(Player player) {
    String id = player.getUniqueId().toString(), name = player.getName();
    async(
        player,
        () -> {
          db.atomic(
              () -> {
                db.account(id, name, cfg.n("currency", "startingMinor"));
                return null;
              });
          return db.menu(id);
        },
        enabled -> {
          if (enabled) activated.add(player.getUniqueId());
          else activated.remove(player.getUniqueId());
          ensureMenu(player);
        });
  }

  public boolean menuItem(ItemStack item) {
    return item != null
        && item.hasItemMeta()
        && item.getItemMeta().getPersistentDataContainer().has(menuKey, PersistentDataType.BYTE);
  }

  public void ensureMenu(Player player) {
    if (player.isDead() || physical.busy.contains(player.getUniqueId())) return;
    // Canonicalize to exactly one copy; never overwrite occupied slots.
    var inv = player.getInventory();
    boolean active =
        cfg.on("menuItem")
            && activated.contains(player.getUniqueId())
            && player.hasPermission("marktwerk.use");
    int existing = -1;
    for (int i = 0; i < inv.getSize(); i++)
      if (menuItem(inv.getItem(i))) {
        if (active && existing < 0) {
          existing = i;
          inv.getItem(i).setAmount(1);
        } else inv.setItem(i, null);
      }
    if (menuItem(player.getItemOnCursor())) player.setItemOnCursor(null);
    if (!active || existing >= 0) return;
    int preferred = (int) cfg.n("menu", "preferredSlot"),
        slot = inv.getItem(preferred) == null ? preferred : inv.firstEmpty();
    if (slot < 0 || slot > 35) return;
    var item =
        new ItemStack(Objects.requireNonNull(Material.matchMaterial(cfg.text("menu", "material"))));
    var meta = item.getItemMeta();
    meta.setDisplayName(cfg.text("menu", "name"));
    var lore = new ArrayList<String>();
    cfg.section("menu").getAsJsonArray("lore").forEach(e -> lore.add(e.getAsString()));
    meta.setLore(lore);
    meta.getPersistentDataContainer().set(menuKey, PersistentDataType.BYTE, (byte) 1);
    item.setItemMeta(meta);
    inv.setItem(slot, item);
  }

  public void reloadShops(Runnable after) {
    db.submit(() -> db.rows("SELECT * FROM shops"))
        .whenComplete(
            (shops, error) ->
                main(
                    () -> {
                      if (error != null) {
                        getLogger().severe("Shop-Schutz konnte nicht aktualisiert werden");
                        return;
                      }
                      protection.load(shops);
                      after.run();
                    }));
  }

  public void validateIcons(JsonObject layout) {
    for (var page : layout.getAsJsonArray("pages"))
      for (var field : page.getAsJsonObject().getAsJsonArray("fields")) {
        String icon = s(field.getAsJsonObject(), "icon");
        var material = Material.matchMaterial(icon);
        if (material == null || material.isAir() || !material.isItem())
          throw new IllegalArgumentException("Invalid layout icon: " + icon);
      }
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    try {
      if (args.length == 0) {
        if (!(sender instanceof Player player))
          throw new IllegalArgumentException("Only Players Can Open This Menu");
        ui.invoke(player, () -> ui.home(player));
        return true;
      }
      if (!sender.hasPermission("marktwerk.admin"))
        throw new IllegalArgumentException("Keine Administratorberechtigung");
      switch (args[0].toLowerCase(Locale.ROOT)) {
        case "menu" -> {
          if (args.length != 3) throw new IllegalArgumentException("/markt menu <UUID> <on|off>");
          String id = UUID.fromString(args[1]).toString();
          if (!Set.of("on", "off").contains(args[2]))
            throw new IllegalArgumentException("on oder off angeben");
          boolean enable = args[2].equals("on");
          admin(
              sender,
              () -> {
                db.one("SELECT id FROM accounts WHERE id=?", id);
                db.setMenu(id, enable);
                return "Menu State Saved";
              },
              () -> {
                UUID uuid = UUID.fromString(id);
                if (enable) activated.add(uuid);
                else activated.remove(uuid);
                Player target = Bukkit.getPlayer(uuid);
                if (target != null) ensureMenu(target);
              });
        }
        case "money" -> {
          if (args.length != 4)
            throw new IllegalArgumentException(
                "/markt money <UUID|server> <set|give|take> <Betrag>");
          String id =
              args[1].equals("server") ? Market.SERVER : UUID.fromString(args[1]).toString();
          long value = Money.parse(args[3]);
          String mode = args[2];
          if (!Set.of("set", "give", "take").contains(mode))
            throw new IllegalArgumentException("set, give oder take angeben");
          admin(
              sender,
              () ->
                  db.atomic(
                      () -> {
                        long delta =
                            switch (mode) {
                              case "set" -> value - db.balance(id);
                              case "take" -> -value;
                              default -> value;
                            };
                        db.change(
                            id,
                            delta,
                            UUID.randomUUID().toString(),
                            "ADMIN_" + mode.toUpperCase(Locale.ROOT));
                        return "Guthaben angepasst";
                      }),
              () -> {});
        }
        case "recovery" -> {
          if (args.length == 1) {
            admin(
                sender,
                () ->
                    Settings.JSON.toJson(
                        db.rows("SELECT id,kind,owner,at FROM operations WHERE state='PREPARED'")),
                () -> {});
          } else if (args.length == 3 && args[1].equals("inspect")) {
            String id = UUID.fromString(args[2]).toString();
            admin(
                sender,
                () -> Settings.JSON.toJson(db.one("SELECT * FROM operations WHERE id=?", id)),
                () -> {});
          } else if (args.length >= 5 && args[1].equals("resolve")) {
            if (!physical.inFlight.isEmpty())
              throw new IllegalArgumentException(
                  "Please finish pending inventory operations first");
            String id = UUID.fromString(args[2]).toString();
            boolean applied = args[3].equals("applied");
            if (!applied && !args[3].equals("not-applied"))
              throw new IllegalArgumentException("applied oder not-applied angeben");
            String note =
                sender.getName()
                    + ": "
                    + String.join(" ", Arrays.copyOfRange(args, 4, args.length));
            admin(
                sender,
                () -> {
                  market.resolve(id, applied, note);
                  return "Vorgang abgeglichen";
                },
                () -> refreshRecovery());
          } else
            throw new IllegalArgumentException(
                "/markt recovery [inspect <ID> | resolve <ID> <applied|not-applied>"
                    + " <Prüfvermerk>]");
        }
        case "backup" ->
            admin(
                sender,
                () -> {
                  Path backup =
                      getDataFolder()
                          .toPath()
                          .resolve("backup-" + System.currentTimeMillis() + ".db");
                  db.backup(backup);
                  return "Sicherung: " + backup.getFileName();
                },
                () -> {});
        case "votes" -> {
          if (args.length != 3 || !args[1].equals("reset"))
            throw new IllegalArgumentException("/markt votes reset <MATERIAL>");
          String material = args[2];
          admin(
              sender,
              () ->
                  db.atomic(
                      () -> {
                        db.exec("DELETE FROM votes WHERE material=?", material);
                        db.exec("DELETE FROM vote_results WHERE material=?", material);
                        return "Votes Reset";
                      }),
              () -> {});
        }
        case "revoke" -> {
          if (args.length != 2) throw new IllegalArgumentException("/markt revoke <UUID>");
          String id = UUID.fromString(args[1]).toString();
          admin(
              sender,
              () -> {
                db.exec("DELETE FROM credentials WHERE owner=?", id);
                return "Website Access Revoked";
              },
              () -> {});
        }
        default ->
            throw new IllegalArgumentException(
                "Befehle: menu, money, recovery, backup, votes, revoke");
      }
    } catch (Throwable e) {
      error(sender, e);
    }
    return true;
  }

  private void admin(CommandSender sender, Supplier<String> task, Runnable after) {
    db.submit(task)
        .whenComplete(
            (result, error) ->
                main(
                    () -> {
                      if (error != null) error(sender, error);
                      else {
                        sender.sendMessage(result);
                        after.run();
                      }
                    }));
  }

  private void refreshRecovery() {
    db.submit(() -> db.rows("SELECT * FROM operations WHERE state='PREPARED'"))
        .thenAccept(
            rows ->
                main(
                    () -> {
                      physical.busy.clear();
                      physical.busy.addAll(physical.inFlight);
                      physical.lockedChests.clear();
                      physical.lockedChests.addAll(physical.inFlightChests);
                      for (var row : rows) {
                        physical.busy.add(UUID.fromString(s(row, "owner")));
                        if (s(row, "kind").equals("CHEST_BUY")) {
                          var payload = JsonParser.parseString(s(row, "payload")).getAsJsonObject();
                          protection.shops.values().stream()
                              .filter(shop -> s(shop, "id").equals(s(payload, "shop")))
                              .findFirst()
                              .ifPresent(
                                  shop ->
                                      physical.lockedChests.add(
                                          s(shop, "world")
                                              + ":"
                                              + n(shop, "x")
                                              + ":"
                                              + n(shop, "y")
                                              + ":"
                                              + n(shop, "z")));
                        }
                      }
                    }));
  }

  @Override
  public void onDisable() {
    if (api != null) api.close();
    if (db != null) db.close();
  }
}
