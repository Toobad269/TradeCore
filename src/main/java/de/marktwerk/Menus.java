package de.marktwerk;

import static de.marktwerk.Store.*;

import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;

public final class Menus {
  public record Button(String label, Material icon, Runnable action, int slot, ItemStack preview) {
    public Button(String label, Material icon, Runnable action, int slot) {
      this(label, icon, action, slot, null);
    }

    public Button(String label, ItemStack preview, Runnable action) {
      this(label, preview.getType(), action, -1, preview.clone());
    }

    public Button(String label, Runnable action) {
      this(label, Material.PAPER, action, -1);
    }

    public Button(String label, Material icon, Runnable action) {
      this(label, icon, action, -1);
    }
  }

  private static final class Holder implements InventoryHolder {
    final Map<Integer, Runnable> actions = new HashMap<>();
    Inventory inventory;

    public Inventory getInventory() {
      return inventory;
    }
  }

  private final MarktWerk p;
  private final Map<UUID, UUID> sessions = new HashMap<>();

  public Menus(MarktWerk p) {
    this.p = p;
  }

  public void forget(Player player) {
    sessions.remove(player.getUniqueId());
  }

  public boolean isMenu(Inventory inventory) {
    return inventory.getHolder() instanceof Holder;
  }

  public void click(Player player, InventoryClickEvent event) {
    if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize())
      return;
    var holder = (Holder) event.getView().getTopInventory().getHolder();
    Runnable action = holder.actions.get(event.getRawSlot());
    if (action != null) p.main(() -> invoke(player, action));
  }

  public void invoke(Player player, Runnable action) {
    try {
      p.require(player, "use");
      if (p.physical.busy.contains(player.getUniqueId()))
        throw new IllegalArgumentException(p.cfg.message("busy"));
      action.run();
    } catch (Throwable error) {
      p.error(player, error);
    }
  }

  private boolean bedrock(Player player) {
    return p.bedrock != null && p.cfg.on("bedrockForms") && p.bedrock.isBedrock(player);
  }

  public void page(Player player, String title, List<Button> source, int index) {
    p.require(player, "use");
    int pages = Math.max(1, (source.size() + 44) / 45);
    index = Math.max(0, Math.min(pages - 1, index));
    int current = index;
    var buttons = new ArrayList<Button>();
    for (int i = index * 45; i < Math.min(source.size(), (index + 1) * 45); i++)
      buttons.add(source.get(i));
    if (index > 0)
      buttons.add(
          new Button(
              "Previous Page",
              Material.ARROW,
              () -> page(player, title, source, current - 1),
              45));
    if (index + 1 < pages)
      buttons.add(
          new Button(
              "Next Page", Material.ARROW, () -> page(player, title, source, current + 1), 53));
    buttons.add(new Button("Main Menu", Material.NETHER_STAR, () -> home(player), 49));
    UUID session = UUID.randomUUID();
    sessions.put(player.getUniqueId(), session);
    var guarded = new ArrayList<Button>();
    for (var b : buttons) {
      Runnable action =
          () -> {
            if (!session.equals(sessions.get(player.getUniqueId()))) return;
            sessions.remove(player.getUniqueId());
            b.action.run();
          };
      guarded.add(new Button(b.label, b.icon, action, b.slot, b.preview));
    }
    if (bedrock(player)) {
      p.bedrock.page(player, title, guarded);
      return;
    }
    var holder = new Holder();
    holder.inventory =
        Bukkit.createInventory(holder, 54, title.length() > 60 ? title.substring(0, 60) : title);
    int next = 0;
    for (var button : guarded) {
      int slot = button.slot >= 0 ? button.slot : next++;
      if (slot > 53) break;
      var icon = button.preview == null ? new ItemStack(button.icon) : button.preview.clone();
      icon.setAmount(Math.min(icon.getAmount(), icon.getMaxStackSize()));
      var meta = icon.getItemMeta();
      meta.setDisplayName("§e" + button.label);
      icon.setItemMeta(meta);
      holder.inventory.setItem(slot, icon);
      holder.actions.put(slot, button.action);
    }
    player.openInventory(holder.inventory);
  }

  public void input(Player player, String title, String initial, Consumer<String> callback) {
    p.require(player, "use");
    if (bedrock(player)) {
      UUID id = UUID.randomUUID();
      sessions.put(player.getUniqueId(), id);
      p.bedrock.input(
          player,
          title,
          initial,
          value -> {
            if (!id.equals(sessions.remove(player.getUniqueId()))) return;
            if (value.length() > 16384) throw new IllegalArgumentException("Input Too Long");
            callback.accept(value);
          });
      return;
    }
    keyboard(player, title, initial, 0, callback);
  }

  private void keyboard(
      Player player, String title, String value, int charset, Consumer<String> callback) {
    String[] sets = {
      "0123456789.,-+",
      "abcdefghijklmnopqrstuvwxyz_ ",
      "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
      "äöüßÄÖÜ€",
      "{}[]\":/!?=@#%&()"
    };
    var buttons = new ArrayList<Button>();
    buttons.add(
        new Button(
            "Apply: " + (value.length() > 35 ? value.substring(value.length() - 35) : value),
            Material.LIME_DYE,
            () -> callback.accept(value)));
    buttons.add(
        new Button(
            "Delete Last Character",
            Material.RED_DYE,
            () ->
                keyboard(
                    player,
                    title,
                    value.isEmpty() ? "" : value.substring(0, value.length() - 1),
                    charset,
                    callback)));
    buttons.add(new Button("Clear All", () -> keyboard(player, title, "", charset, callback)));
    buttons.add(
        new Button(
            "Switch Character Set",
            Material.BOOK,
            () -> keyboard(player, title, value, (charset + 1) % sets.length, callback)));
    for (char c : sets[charset].toCharArray())
      buttons.add(
          new Button(
              "Characters: " + c,
              () -> {
                if (value.length() >= 16384) throw new IllegalArgumentException("Input Too Long");
                keyboard(player, title, value + c, charset, callback);
              }));
    page(player, title, buttons, 0);
  }

  private void amount(Player player, String title, LongConsumer callback) {
    input(player, title, "", text -> callback.accept(Money.positive(Money.parse(text))));
  }

  private void quantity(Player player, IntConsumer callback) {
    input(
        player,
        "Menge (1–4096)",
        "1",
        text -> {
          int q = Integer.parseInt(text);
          if (q < 1 || q > 4096) throw new IllegalArgumentException("Quantity must be 1..4096");
          callback.accept(q);
        });
  }

  private void confirm(Player player, String description, Runnable action) {
    page(
        player,
        "Please Confirm",
        List.of(
            new Button(description, Material.LIME_DYE, action),
            new Button("Cancel", Material.RED_DYE, () -> home(player))),
        0);
  }

  private String cash(long value) {
    return Money.format(value) + " " + p.cfg.text("currency", "plural");
  }

  private String owner(Player player) {
    return player.getUniqueId().toString();
  }

  public void home(Player player) {
    p.async(
        player,
        () -> p.db.one("SELECT balance,income FROM accounts WHERE id=?", owner(player)),
        a -> {
          var b = new ArrayList<Button>();
          b.add(new Button("Balance: " + cash(n(a, "balance")), () -> home(player)));
          b.add(new Button("Transfer", Material.GOLD_INGOT, () -> transfer(player)));
          if (p.cfg.on("serverShop"))
            b.add(new Button("Server Shop", Material.EMERALD, () -> server(player)));
          if (p.cfg.on("chestShops")) {
            b.add(
                new Button(
                    "Chest shops suchen",
                    Material.CHEST,
                    () -> input(player, "Shop Search", "", q -> shops(player, q))));
            b.add(new Button("Manage My Shops", Material.CHEST, () -> manage(player)));
            b.add(
                new Button("Register Targeted Chest", Material.CHEST, () -> createShop(player)));
          }
          if (p.cfg.on("auctionHouse")) {
            b.add(
                new Button(
                    "Auction House",
                    Material.GOLDEN_APPLE,
                    () -> auctions(player, "", "ALL", "PRICE")));
            b.add(new Button("List Held Item", () -> listAuction(player)));
            b.add(new Button("My Listings", () -> myAuctions(player)));
          }
          b.add(new Button("Pickup Mailbox", Material.ENDER_CHEST, () -> mail(player)));
          b.add(new Button("Income: " + cash(n(a, "income")), () -> income(player)));
          b.add(new Button("Supplier Shares", () -> shares(player)));
          if (p.cfg.on("httpApi")) {
            b.add(
                new Button(
                    "Link Website: One-Time Code",
                    () -> {
                      p.require(player, "api");
                      p.async(
                          player,
                          () -> p.api.issue(owner(player)),
                          code ->
                              page(
                                  player,
                                  "Linking",
                                  List.of(new Button("Code: " + code, () -> home(player))),
                                  0));
                    }));
            b.add(
                new Button(
                    "Revoke All Website Access",
                    () -> {
                      p.require(player, "api");
                      p.async(
                          player,
                          () -> {
                            p.db.exec("DELETE FROM credentials WHERE owner=?", owner(player));
                            return true;
                          },
                          v -> home(player));
                    }));
          }
          page(player, "TradeCore", b, 0);
        });
  }

  private void transfer(Player player) {
    p.require(player, "transfer");
    p.async(
        player,
        () ->
            p.db.rows(
                "SELECT id,name FROM accounts WHERE id<>? AND id<>? AND id<>? ORDER BY name",
                owner(player),
                Market.SERVER,
                Market.BONUS),
        rows -> {
          var b = new ArrayList<Button>();
          for (var r : rows)
            b.add(
                new Button(
                    s(r, "name"),
                    () ->
                        amount(
                            player,
                            "Transfer Amount",
                            value ->
                                confirm(
                                    player,
                                    s(r, "name") + ": " + cash(value),
                                    () -> {
                                      p.require(player, "transfer");
                                      String op = UUID.randomUUID().toString();
                                      p.async(
                                          player,
                                          () -> {
                                            p.db.transfer(owner(player), s(r, "id"), value, op);
                                            return true;
                                          },
                                          v -> home(player));
                                    }))));
          page(player, "Select Recipient", b, 0);
        });
  }

  private void income(Player player) {
    p.async(
        player,
        () ->
            p.db.rows(
                "SELECT delta,reason,at FROM ledger WHERE account=? AND delta>0 ORDER BY id DESC"
                    + " LIMIT 200",
                owner(player)),
        rows -> {
          var b = new ArrayList<Button>();
          for (var r : rows)
            b.add(new Button(cash(n(r, "delta")) + " · " + s(r, "reason"), () -> income(player)));
          page(player, "Last 200 Income Entries", b, 0);
        });
  }

  private void shares(Player player) {
    p.async(
        player,
        () ->
            p.db.rows(
                "SELECT s.*,COALESCE(t.quantity,0) AS stock FROM suppliers s LEFT JOIN stock t ON"
                    + " s.material=t.material WHERE owner=?",
                owner(player)),
        rows -> {
          var b = new ArrayList<Button>();
          for (var r : rows) {
            var f = Fraction.parse(s(r, "units"));
            b.add(
                new Button(
                    s(r, "material")
                        + ": "
                        + f
                        + " / "
                        + n(r, "stock")
                        + "; Bonusrest "
                        + s(r, "carry"),
                    () -> shares(player)));
          }
          page(player, "Supplier Shares (Exact)", b, 0);
        });
  }

  private void server(Player player) {
    p.require(player, "server");
    var b = new ArrayList<Button>();
    for (var article : p.prices.all())
      b.add(
          new Button(
              article.material(),
              Material.valueOf(article.material()),
              () -> quantity(player, q -> serverArticle(player, article.material(), q))));
    page(player, "Server Shop", b, 0);
  }

  private void serverArticle(Player player, String material, int quantity) {
    p.require(player, "server");
    p.async(
        player,
        () -> p.market.quote(material, quantity),
        q -> {
          var b = new ArrayList<Button>();
          b.add(
              new Button(
                  "Buy "
                      + quantity
                      + "× "
                      + material
                      + ": "
                      + cash(q.sell())
                      + " · Lager "
                      + q.stock(),
                  () ->
                      confirm(
                          player,
                          "Buy " + quantity + "× " + material + " for " + cash(q.sell()),
                          () -> {
                            p.require(player, "server");
                            var item = new ItemStack(Material.valueOf(material), quantity);
                            String encoded = Items.encode(item), op = UUID.randomUUID().toString();
                            p.async(
                                player,
                                () -> {
                                  p.market.serverBuy(owner(player), q, encoded, op);
                                  return true;
                                },
                                v -> mail(player));
                          })));
          b.add(
              new Button(
                  "Sell " + quantity + "× " + material + ": " + cash(q.buy()),
                  () ->
                      confirm(
                          player,
                          "Sell " + quantity + "× " + material + " for " + cash(q.buy()),
                          () -> {
                            p.require(player, "server");
                            var template = new ItemStack(Material.valueOf(material));
                            Items.serverAllowed(template);
                            String encoded = Items.encode(template);
                            p.physical.intake(
                                player,
                                "server",
                                template,
                                quantity,
                                e -> p.market.prepareSell(owner(player), q, encoded, e),
                                p.market::finishSell);
                          })));
          if (p.cfg.on("playerVotes")) {
            b.add(new Button("Vote / Results", () -> voteMenu(player, material)));
          }
          page(player, material, b, 0);
        });
  }

  private void voteMenu(Player player, String material) {
    p.require(player, "vote");
    p.async(
        player,
        () -> p.market.voteStatus(material),
        status -> {
          var b = new ArrayList<Button>();
          b.add(
              new Button(
                  "Faktor " + n(status, "factorBps") + "/10000 · " + s(status, "result"),
                  () -> voteMenu(player, material)));
          b.add(
              new Button(
                  "Ends: " + java.time.Instant.ofEpochSecond(n(status, "votingEndsAt")),
                  () -> voteMenu(player, material)));
          b.add(
              new Button(
                  "Next Start: " + java.time.Instant.ofEpochSecond(n(status, "nextStartsAt")),
                  () -> voteMenu(player, material)));
          for (int d : new int[] {1, -1})
            b.add(
                new Button(
                    d > 0 ? "More Expensive" : "Cheaper",
                    () -> {
                      p.require(player, "vote");
                      p.async(
                          player,
                          () -> {
                            p.market.vote(owner(player), material, d);
                            return true;
                          },
                          v -> voteMenu(player, material));
                    }));
          page(player, "Price Voting", b, 0);
        });
  }

  private void mail(Player player) {
    p.require(player, "claim");
    p.async(
        player,
        () -> p.db.rows("SELECT * FROM mailbox WHERE owner=? AND state='READY'", owner(player)),
        rows -> {
          var b = new ArrayList<Button>();
          for (var r : rows) {
            ItemStack item = Items.decode(s(r, "item"));
            b.add(
                new Button(
                    "Collect: " + item.getAmount() + "× " + Items.describe(item),
                    item,
                    () ->
                        confirm(
                            player,
                            "Collect: " + item.getAmount() + "× " + Items.describe(item),
                            () -> p.physical.claim(player, r))));
          }
          page(player, "Pickup Mailbox", b, 0);
        });
  }

  private void auctions(Player player, String search, String filter, String sort) {
    p.require(player, "auction");
    p.async(
        player,
        () ->
            p.db.rows(
                "SELECT * FROM auctions WHERE state='ACTIVE' AND expires>? ORDER BY "
                    + (sort.equals("TIME") ? "expires" : "price"),
                now()),
        rows -> {
          var b = new ArrayList<Button>();
          b.add(
              new Button(
                  "Search: " + search,
                  Material.COMPASS,
                  () -> input(player, "Search term", "", q -> auctions(player, q, filter, sort))));
          b.add(
              new Button(
                  "Filter: " + filter,
                  () ->
                      page(
                          player,
                          "Filter Material",
                          List.of(
                              new Button("All", () -> auctions(player, search, "ALL", sort)),
                              new Button(
                                  "Enter material name",
                                  () ->
                                      input(
                                          player,
                                          "Material Name",
                                          "",
                                          f ->
                                              auctions(
                                                  player,
                                                  search,
                                                  f.toUpperCase(Locale.ROOT),
                                                  sort)))),
                          0)));
          b.add(
              new Button(
                  "Sort: " + sort,
                  () -> auctions(player, search, filter, sort.equals("TIME") ? "PRICE" : "TIME")));
          for (var a : rows) {
            ItemStack item = Items.decode(s(a, "item"));
            String label =
                item.getAmount()
                    + "× "
                    + Items.describe(item)
                    + " · "
                    + cash(n(a, "price"))
                    + " · "
                    + Math.max(0, n(a, "expires") - now())
                    + "s";
            String searchable = label + (item.hasItemMeta() ? item.getItemMeta().toString() : "");
            if (!searchable.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))
                || !filter.equals("ALL") && !item.getType().name().equals(filter)) continue;
            b.add(
                new Button(
                    label,
                    item,
                    () ->
                        confirm(
                            player,
                            label,
                            () -> {
                              p.require(player, "auction");
                              String op = UUID.randomUUID().toString();
                              p.async(
                                  player,
                                  () -> {
                                    p.market.buyAuction(
                                        owner(player), s(a, "id"), n(a, "price"), op);
                                    return true;
                                  },
                                  v -> mail(player));
                            })));
          }
          page(player, "Auction House", b, 0);
        });
  }

  private void listAuction(Player player) {
    p.require(player, "auction");
    var held = player.getInventory().getItemInMainHand().clone();
    if (!Items.allowed(held) || p.menuItem(held))
      throw new IllegalArgumentException("This Item Cannot Be Traded");
    quantity(
        player,
        q -> {
          if (q > held.getAmount())
            throw new IllegalArgumentException("Select at most one stack");
          held.setAmount(q);
          amount(
              player,
              "Total Price",
              price ->
                  input(
                      player,
                      "Duration in seconds",
                      Long.toString(p.cfg.n("auctions", "durationSeconds")),
                      duration -> {
                        long seconds = Long.parseLong(duration);
                        confirm(
                            player,
                            q
                                + "× "
                                + held.getType()
                                + " for "
                                + cash(price)
                                + "; Gebühr "
                                + cash(p.cfg.n("auctions", "listingFeeMinor"))
                                + "; "
                                + seconds
                                + "s",
                            () -> {
                              p.require(player, "auction");
                              String encoded = Items.encode(held);
                              p.physical.intake(
                                  player,
                                  "auction",
                                  held,
                                  q,
                                  e ->
                                      p.market.prepareAuction(
                                          owner(player),
                                          encoded,
                                          held.getType().name(),
                                          price,
                                          seconds,
                                          e),
                                  p.market::finishAuction);
                            });
                      }));
        });
  }

  private void myAuctions(Player player) {
    p.require(player, "auction");
    p.async(
        player,
        () -> p.db.rows("SELECT * FROM auctions WHERE owner=? AND state='ACTIVE'", owner(player)),
        rows -> {
          var b = new ArrayList<Button>();
          for (var a : rows)
            b.add(
                new Button(
                    "Withdraw: " + s(a, "material") + " · " + cash(n(a, "price")),
                    () ->
                        confirm(
                            player,
                            "Withdraw Listing",
                            () -> {
                              p.require(player, "auction");
                              p.async(
                                  player,
                                  () -> {
                                    p.market.cancelAuction(owner(player), s(a, "id"));
                                    return true;
                                  },
                                  v -> mail(player));
                            })));
          page(player, "My Listings", b, 0);
        });
  }

  private void shops(Player player, String query) {
    p.require(player, "chest");
    p.async(
        player,
        () -> p.db.rows("SELECT * FROM shops ORDER BY title"),
        rows -> {
          var b = new ArrayList<Button>();
          for (var shop : rows)
            if (s(shop, "title").toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))
              b.add(
                  new Button(
                      s(shop, "title"),
                      Material.CHEST,
                      () -> openShop(player, s(shop, "id"), null)));
          page(player, "Chest Shops", b, 0);
        });
  }

  public void openShop(Player player, String id, String pageId) {
    p.require(player, "chest");
    p.market.enabled("chestShops");
    p.async(
        player,
        () ->
            obj(
                "shop",
                p.market.shop(id),
                "offers",
                p.db.rows("SELECT * FROM offers WHERE shop=? AND visible=1", id)),
        result -> {
          var shop = result.getAsJsonObject("shop");
          var layout = JsonParser.parseString(s(shop, "layout")).getAsJsonObject();
          var offers = new HashMap<String, JsonObject>();
          result
              .getAsJsonArray("offers")
              .forEach(e -> offers.put(s(e.getAsJsonObject(), "id"), e.getAsJsonObject()));
          var b = new ArrayList<Button>();
          String shownTitle = s(shop, "title");
          if (s(layout, "mode").equals("CUSTOM")) {
            var pages = layout.getAsJsonArray("pages");
            JsonObject current = pages.get(0).getAsJsonObject();
            if (pageId != null)
              for (var e : pages)
                if (s(e.getAsJsonObject(), "id").equals(pageId)) current = e.getAsJsonObject();
            shownTitle = s(current, "title");
            for (var e : current.getAsJsonArray("fields")) {
              var f = e.getAsJsonObject();
              String action = s(f, "action");
              Runnable run =
                  switch (action) {
                    case "BUY_OFFER" ->
                        () -> {
                          var offer = offers.get(s(f, "offerId"));
                          if (offer == null)
                            throw new IllegalArgumentException("Listing hidden or removed");
                          chestConfirm(player, shop, offer);
                        };
                    case "OPEN_PAGE" -> () -> openShop(player, id, s(f, "targetPage"));
                    case "CLOSE" -> player::closeInventory;
                    case "HOME" -> () -> home(player);
                    default -> () -> openShop(player, id, pageId);
                  };
              b.add(
                  new Button(
                      s(f, "label"), Material.valueOf(s(f, "icon")), run, (int) n(f, "slot")));
            }
          } else
            for (var offer : offers.values()) {
              var template = Items.decode(s(offer, "item"));
              String stock;
              try {
                stock =
                    Integer.toString(
                        Items.count(p.physical.chest(shop).getBlockInventory(), template));
              } catch (IllegalArgumentException e) {
                stock = "Chunk is not loaded";
              }
              b.add(
                  new Button(
                      n(offer, "quantity")
                          + "× "
                          + Items.describe(template)
                          + " · "
                          + cash(n(offer, "price"))
                          + " · Bestand "
                          + stock,
                      template,
                      () -> chestConfirm(player, shop, offer)));
            }
          page(player, shownTitle, b, 0);
        });
  }

  private void chestConfirm(Player player, JsonObject shop, JsonObject offer) {
    confirm(
        player,
        n(offer, "quantity")
            + "× "
            + Items.describe(Items.decode(s(offer, "item")))
            + " for "
            + cash(n(offer, "price")),
        () -> p.physical.chestBuy(player, shop, offer));
  }

  private void createShop(Player player) {
    p.require(player, "chest");
    p.market.enabled("chestShops");
    var block = player.getTargetBlockExact(5);
    if (block == null
        || !Set.of(Material.CHEST, Material.TRAPPED_CHEST).contains(block.getType())
        || !(block.getState() instanceof Chest chest)
        || chest.getInventory().getHolder() instanceof org.bukkit.block.DoubleChest)
      throw new IllegalArgumentException(
          "Look at a Single Chest Within 5 Blocks");
    var location = block.getLocation();
    input(
        player,
        "Shop Name",
        "My Shop",
        title -> {
          p.require(player, "chest");
          if (title.isBlank() || title.length() > 60)
            throw new IllegalArgumentException("Name must contain 1..60 characters");
          if (!(block.getState() instanceof Chest c)
              || c.getInventory().getHolder() instanceof org.bukkit.block.DoubleChest)
            throw new IllegalArgumentException("Chest Changed");
          p.async(
              player,
              () ->
                  p.market.createShop(
                      owner(player),
                      location.getWorld().getUID().toString(),
                      location.getBlockX(),
                      location.getBlockY(),
                      location.getBlockZ(),
                      title),
              id -> p.reloadShops(() -> editShop(player, id)));
        });
  }

  private void manage(Player player) {
    p.require(player, "chest");
    p.async(
        player,
        () -> p.db.rows("SELECT * FROM shops WHERE owner=?", owner(player)),
        rows -> {
          var b = new ArrayList<Button>();
          for (var shop : rows)
            b.add(new Button(s(shop, "title"), () -> editShop(player, s(shop, "id"))));
          page(player, "My Shops", b, 0);
        });
  }

  private void editShop(Player player, String id) {
    p.require(player, "chest");
    p.async(
        player,
        () ->
            obj(
                "shop",
                p.market.ownShop(owner(player), id),
                "offers",
                p.db.rows("SELECT * FROM offers WHERE shop=?", id)),
        data -> {
          var shop = data.getAsJsonObject("shop");
          var b = new ArrayList<Button>();
          b.add(new Button("Create Listing from Held Item", () -> editOffer(player, shop, null)));
          for (var e : data.getAsJsonArray("offers")) {
            var offer = e.getAsJsonObject();
            b.add(
                new Button(
                    s(offer, "material") + " · " + cash(n(offer, "price")),
                    () ->
                        page(
                            player,
                            "Edit Listing",
                            List.of(
                                new Button(
                                    "Change Quantity and Price", () -> editOffer(player, shop, offer)),
                                new Button(
                                    n(offer, "visible") == 1 ? "Hide" : "Show",
                                    () ->
                                        saveOffer(
                                            player,
                                            shop,
                                            offer,
                                            s(offer, "item"),
                                            s(offer, "material"),
                                            (int) n(offer, "quantity"),
                                            n(offer, "price"),
                                            n(offer, "visible") != 1)),
                                new Button(
                                    "Delete",
                                    () -> {
                                      p.require(player, "chest");
                                      p.async(
                                          player,
                                          () -> {
                                            p.market.removeOffer(
                                                owner(player),
                                                id,
                                                s(offer, "id"),
                                                n(shop, "version"));
                                            return true;
                                          },
                                          v -> editShop(player, id));
                                    })),
                            0)));
          }
          b.add(
              new Button(
                  "Edit Custom Layout as JSON",
                  Material.WRITABLE_BOOK,
                  () ->
                      input(
                          player,
                          "Layout-JSON",
                          s(shop, "layout"),
                          text -> {
                            p.require(player, "chest");
                            var layout = JsonParser.parseString(text).getAsJsonObject();
                            p.validateIcons(layout);
                            p.async(
                                player,
                                () -> {
                                  p.market.layout(owner(player), id, n(shop, "version"), layout);
                                  return true;
                                },
                                v -> editShop(player, id));
                          })));
          b.add(
              new Button(
                  "Enable Simple Mode",
                  () -> {
                    p.require(player, "chest");
                    p.async(
                        player,
                        () -> {
                          p.market.layout(owner(player), id, n(shop, "version"), Layouts.empty());
                          return true;
                        },
                        v -> editShop(player, id));
                  }));
          b.add(
              new Button(
                  "Remove shop (goods remain in chest)",
                  Material.RED_DYE,
                  () ->
                      confirm(
                          player,
                          "Unregister Shop",
                          () -> {
                            p.require(player, "chest");
                            p.async(
                                player,
                                () -> {
                                  p.market.removeShop(owner(player), id);
                                  return true;
                                },
                                v -> p.reloadShops(() -> manage(player)));
                          })));
          page(player, s(shop, "title"), b, 0);
        });
  }

  private void editOffer(Player player, JsonObject shop, JsonObject offer) {
    p.require(player, "chest");
    ItemStack template =
        offer == null
            ? player.getInventory().getItemInMainHand().clone()
            : Items.decode(s(offer, "item"));
    if (!Items.allowed(template) || p.menuItem(template))
      throw new IllegalArgumentException("Non-Tradable Item");
    template.setAmount(1);
    String item = Items.encode(template);
    quantity(
        player,
        quantity ->
            amount(
                player,
                "Total Price",
                price ->
                    saveOffer(
                        player,
                        shop,
                        offer,
                        item,
                        template.getType().name(),
                        quantity,
                        price,
                        true)));
  }

  private void saveOffer(
      Player player,
      JsonObject shop,
      JsonObject offer,
      String item,
      String material,
      int quantity,
      long price,
      boolean visible) {
    p.require(player, "chest");
    String id = offer == null ? UUID.randomUUID().toString() : s(offer, "id");
    p.async(
        player,
        () -> {
          p.market.updateOffer(
              owner(player),
              s(shop, "id"),
              n(shop, "version"),
              id,
              item,
              material,
              quantity,
              price,
              visible);
          return true;
        },
        v -> editShop(player, s(shop, "id")));
  }
}
