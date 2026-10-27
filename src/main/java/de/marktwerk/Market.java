package de.marktwerk;

import static de.marktwerk.Store.*;

import com.google.gson.*;
import java.util.*;

/** Domain services: no Bukkit access. All public calls run on Store's worker. */
public final class Market {
  public static final String SERVER = "00000000-0000-0000-0000-000000000000";
  public static final String BONUS = "00000000-0000-0000-0000-000000000001";
  public final Store db;
  public final Settings cfg;
  public final Prices prices;

  public Market(Store db, Settings cfg, Prices prices) {
    this.db = db;
    this.cfg = cfg;
    this.prices = prices;
    db.submit(
            () -> {
              db.account(SERVER, "Server", cfg.n("currency", "serverInitialMinor"));
              db.account(BONUS, "Bonus Reserve", 0);
              return null;
            })
        .join();
  }

  public void enabled(String name) {
    if (!cfg.on(name)) throw new IllegalArgumentException("Modul deaktiviert: " + name);
  }

  public void itemAllowed(String material, String domain) {
    if (cfg.excluded(material, domain))
      throw new IllegalArgumentException("Item ist in diesem Handelsbereich ausgeschlossen");
  }

  public long stock(String material) {
    return db.number("SELECT quantity FROM stock WHERE material=?", material);
  }

  public Prices.Quote quote(String material, int quantity) {
    enabled("serverShop");
    return prices.quote(material, quantity, stock(material), voteFactor(material));
  }

  public void verify(Prices.Quote q) {
    if (!q.equals(quote(q.material(), q.quantity())))
      throw new IllegalArgumentException(cfg.message("stale"));
  }

  public void serverBuy(String buyer, Prices.Quote quote, String serializedItem, String op) {
    db.atomic(
        () -> {
          if (db.number("SELECT COUNT(*) FROM operations WHERE id=?", op) > 0) return null;
          verify(quote);
          if (quote.stock() < quote.quantity()) throw new IllegalArgumentException("Ausverkauft");
          db.change(buyer, -quote.sell(), op, "SERVER_BUY");
          var funding =
              quote
                  .bonus()
                  .add(
                      Fraction.parse(
                          s(
                              db.one(
                                  "SELECT bonus_carry FROM stock WHERE material=?",
                                  quote.material()),
                              "bonus_carry")));
          long reserved = funding.floor();
          db.change(SERVER, quote.sell() - reserved, op, "SERVER_BUY");
          db.change(BONUS, reserved, op, "BONUS_RESERVE");
          db.exec(
              "UPDATE stock SET bonus_carry=? WHERE material=?",
              funding.rest().toString(),
              quote.material());
          var suppliers = db.rows("SELECT * FROM suppliers WHERE material=?", quote.material());
          for (var supplier : suppliers) {
            var units = Fraction.parse(s(supplier, "units"));
            var carry = Fraction.parse(s(supplier, "carry"));
            var due = units.mul(quote.bonus()).mul(1, quote.stock()).add(carry);
            long payout = due.floor();
            if (payout > 0) {
              db.change(BONUS, -payout, op, "SUPPLIER");
              db.change(s(supplier, "owner"), payout, op, "SUPPLIER");
            }
            db.exec(
                "UPDATE suppliers SET units=?,carry=? WHERE material=? AND owner=?",
                units.mul(quote.stock() - quote.quantity(), quote.stock()).toString(),
                due.rest().toString(),
                quote.material(),
                s(supplier, "owner"));
          }
          db.exec(
              "UPDATE stock SET quantity=quantity-? WHERE material=?",
              quote.quantity(),
              quote.material());
          db.mail(buyer, serializedItem, op);
          db.operation(
              op,
              "SERVER_BUY",
              buyer,
              "COMMITTED",
              Settings.JSON.toJsonTree(quote).getAsJsonObject());
          return null;
        });
  }

  public String prepareSell(String owner, Prices.Quote quote, String item, JsonObject evidence) {
    return db.atomic(
        () -> {
          verify(quote);
          Math.addExact(stock(quote.material()), quote.quantity());
          if (db.pending(owner)) throw new IllegalArgumentException("Offener Inventarvorgang");
          String op = UUID.randomUUID().toString();
          // Reserve server funds before any physical removal. Minting is explicit.
          if (!cfg.section("currency").get("allowMinting").getAsBoolean())
            db.change(SERVER, -quote.buy(), op, "SELL_HOLD");
          var payload =
              obj(
                  "quote",
                  quote,
                  "item",
                  item,
                  "evidence",
                  evidence,
                  "mint",
                  cfg.section("currency").get("allowMinting").getAsBoolean());
          db.operation(op, "SERVER_SELL", owner, "PREPARED", payload);
          return op;
        });
  }

  public void finishSell(String op) {
    db.atomic(
        () -> {
          var row = prepared(op, "SERVER_SELL");
          var p = JsonParser.parseString(s(row, "payload")).getAsJsonObject();
          var q = Settings.JSON.fromJson(p.get("quote"), Prices.Quote.class);
          String owner = s(row, "owner");
          db.change(
              owner,
              q.buy(),
              op,
              p.get("mint").getAsBoolean() ? "MINT_SERVER_SELL" : "SERVER_SELL");
          long current = stock(q.material());
          db.exec(
              "INSERT INTO stock(material,quantity) VALUES(?,?) ON CONFLICT(material) DO UPDATE SET"
                  + " quantity=excluded.quantity",
              q.material(),
              Math.addExact(current, q.quantity()));
          var suppliers =
              db.rows("SELECT * FROM suppliers WHERE material=? AND owner=?", q.material(), owner);
          Fraction units =
              suppliers.isEmpty()
                  ? Fraction.ZERO
                  : Fraction.parse(s(suppliers.getFirst(), "units"));
          String carry = suppliers.isEmpty() ? "0/1" : s(suppliers.getFirst(), "carry");
          db.exec(
              "INSERT INTO suppliers VALUES(?,?,?,?) ON CONFLICT(material,owner) DO UPDATE SET"
                  + " units=excluded.units",
              q.material(),
              owner,
              units.add(Fraction.of(q.quantity())).toString(),
              carry);
          committed(op);
          return null;
        });
  }

  private JsonObject prepared(String op, String kind) {
    var r = db.one("SELECT * FROM operations WHERE id=? AND state='PREPARED'", op);
    if (!s(r, "kind").equals(kind)) throw new IllegalArgumentException("Falscher Vorgangstyp");
    return r;
  }

  private void committed(String op) {
    db.exec("UPDATE operations SET state='COMMITTED' WHERE id=?", op);
  }

  public String prepareAuction(
      String owner, String item, String material, long price, long duration, JsonObject evidence) {
    enabled("auctionHouse");
    itemAllowed(material, "auction");
    return db.atomic(
        () -> {
          Money.positive(price);
          if (duration < 60 || duration > cfg.n("auctions", "maxDurationSeconds"))
            throw new IllegalArgumentException("Invalid Duration");
          if (db.pending(owner)) throw new IllegalArgumentException("Offener Vorgang");
          if (db.number("SELECT COUNT(*) FROM auctions WHERE owner=? AND state='ACTIVE'", owner)
              >= cfg.n("auctions", "limit"))
            throw new IllegalArgumentException("Angebotslimit erreicht");
          String op = UUID.randomUUID().toString();
          long fee = cfg.n("auctions", "listingFeeMinor");
          db.change(owner, -fee, op, "AUCTION_LIST_HOLD");
          db.operation(
              op,
              "AUCTION_LIST",
              owner,
              "PREPARED",
              obj(
                  "item",
                  item,
                  "material",
                  material,
                  "price",
                  price,
                  "expires",
                  now() + duration,
                  "fee",
                  fee,
                  "evidence",
                  evidence));
          return op;
        });
  }

  public void finishAuction(String op) {
    db.atomic(
        () -> {
          var row = prepared(op, "AUCTION_LIST");
          var p = JsonParser.parseString(s(row, "payload")).getAsJsonObject();
          db.exec(
              "INSERT INTO auctions VALUES(?,?,?,?,?,?,'ACTIVE')",
              op,
              s(row, "owner"),
              s(p, "item"),
              s(p, "material"),
              n(p, "price"),
              n(p, "expires"));
          db.change(SERVER, n(p, "fee"), op, "AUCTION_FEE");
          committed(op);
          return null;
        });
  }

  public void expire() {
    db.atomic(
        () -> {
          for (var a :
              db.rows("SELECT * FROM auctions WHERE state='ACTIVE' AND expires<=?", now())) {
            db.exec("UPDATE auctions SET state='EXPIRED' WHERE id=?", s(a, "id"));
            db.mail(s(a, "owner"), s(a, "item"), "expire:" + s(a, "id"));
          }
          db.exec("DELETE FROM credentials WHERE expires<?", now());
          return null;
        });
  }

  public void buyAuction(String buyer, String id, long expected, String operation) {
    enabled("auctionHouse");
    db.atomic(
        () -> {
          if (db.number("SELECT COUNT(*) FROM operations WHERE id=?", operation) > 0) return null;
          var a =
              db.one(
                  "SELECT * FROM auctions WHERE id=? AND state='ACTIVE' AND expires>?", id, now());
          itemAllowed(s(a, "material"), "auction");
          if (n(a, "price") != expected) throw new IllegalArgumentException(cfg.message("stale"));
          if (s(a, "owner").equals(buyer)) throw new IllegalArgumentException("Eigenes Angebot");
          long fee = Money.ratio(expected, cfg.n("auctions", "saleFeeBps"), 10000);
          db.change(buyer, -expected, operation, "AUCTION_BUY");
          db.change(s(a, "owner"), expected - fee, operation, "AUCTION_SALE");
          db.change(SERVER, fee, operation, "AUCTION_FEE");
          db.exec("UPDATE auctions SET state='SOLD' WHERE id=? AND state='ACTIVE'", id);
          db.mail(buyer, s(a, "item"), operation);
          db.operation(
              operation, "AUCTION_BUY", buyer, "COMMITTED", obj("auction", id, "price", expected));
          return null;
        });
  }

  public void cancelAuction(String owner, String id) {
    db.atomic(
        () -> {
          var a =
              db.one("SELECT * FROM auctions WHERE id=? AND owner=? AND state='ACTIVE'", id, owner);
          db.exec("UPDATE auctions SET state='CANCELLED' WHERE id=?", id);
          db.mail(owner, s(a, "item"), "cancel:" + id);
          return null;
        });
  }

  public String prepareClaim(String owner, String id, JsonObject evidence) {
    return db.atomic(
        () -> {
          if (db.pending(owner)) throw new IllegalArgumentException("Offener Inventarvorgang");
          var mail =
              db.one("SELECT * FROM mailbox WHERE id=? AND owner=? AND state='READY'", id, owner);
          String op = UUID.randomUUID().toString();
          db.exec("UPDATE mailbox SET state='HELD' WHERE id=?", id);
          db.operation(
              op,
              "CLAIM",
              owner,
              "PREPARED",
              obj("mail", id, "item", s(mail, "item"), "evidence", evidence));
          return op;
        });
  }

  public void finishClaim(String op) {
    db.atomic(
        () -> {
          var row = prepared(op, "CLAIM");
          var p = JsonParser.parseString(s(row, "payload")).getAsJsonObject();
          db.exec("UPDATE mailbox SET state='DELIVERED' WHERE id=?", s(p, "mail"));
          committed(op);
          return null;
        });
  }

  public String createShop(String owner, String world, int x, int y, int z, String title) {
    enabled("chestShops");
    String id = UUID.randomUUID().toString();
    db.exec(
        "INSERT INTO shops(id,owner,world,x,y,z,title,layout) VALUES(?,?,?,?,?,?,?,?)",
        id,
        owner,
        world,
        x,
        y,
        z,
        title,
        Layouts.empty().toString());
    return id;
  }

  public JsonObject shop(String id) {
    return db.one("SELECT * FROM shops WHERE id=?", id);
  }

  public JsonObject ownShop(String owner, String id) {
    return db.one("SELECT * FROM shops WHERE id=? AND owner=?", id, owner);
  }

  public void updateOffer(
      String owner,
      String shop,
      long version,
      String id,
      String item,
      String material,
      int quantity,
      long price,
      boolean visible) {
    enabled("chestShops");
    db.atomic(
        () -> {
          ownShop(owner, shop);
          itemAllowed(material, "chest");
          Money.positive(price);
          if (quantity < 1 || quantity > 4096)
            throw new IllegalArgumentException("Quantity must be 1..4096");
          checkShopIdle(shop);
          version(shop, version);
          if (db.number("SELECT COUNT(*) FROM offers WHERE shop=?", shop) >= 200
              && db.number("SELECT COUNT(*) FROM offers WHERE shop=? AND id=?", shop, id) == 0)
            throw new IllegalArgumentException("Maximal 200 Angebote");
          if (db.number("SELECT COUNT(*) FROM offers WHERE id=? AND shop<>?", id, shop) > 0)
            throw new IllegalArgumentException("Angebots-ID bereits vergeben");
          db.exec(
              "INSERT INTO offers VALUES(?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET"
                  + " item=excluded.item,material=excluded.material,quantity=excluded.quantity,price=excluded.price,visible=excluded.visible",
              id,
              shop,
              item,
              material,
              quantity,
              price,
              visible ? 1 : 0);
          return null;
        });
  }

  public void checkShopIdle(String shop) {
    if (db.number(
            "SELECT COUNT(*) FROM operations WHERE state='PREPARED' AND kind='CHEST_BUY' AND"
                + " json_extract(payload,'$.shop')=?",
            shop)
        > 0) throw new IllegalArgumentException("Shop hat einen reservierten oder unklaren Kauf");
  }

  public void version(String shop, long expected) {
    if (db.exec("UPDATE shops SET version=version+1 WHERE id=? AND version=?", shop, expected) != 1)
      throw new IllegalArgumentException("VERSION_CONFLICT");
  }

  public void layout(String owner, String shop, long version, JsonObject layout) {
    enabled("chestShops");
    db.atomic(
        () -> {
          ownShop(owner, shop);
          Layouts.validate(layout, db.rows("SELECT id FROM offers WHERE shop=?", shop));
          checkShopIdle(shop);
          version(shop, version);
          db.exec("UPDATE shops SET layout=? WHERE id=?", layout.toString(), shop);
          return null;
        });
  }

  public void removeOffer(String owner, String shop, String offer, long version) {
    db.atomic(
        () -> {
          ownShop(owner, shop);
          checkShopIdle(shop);
          version(shop, version);
          db.exec("DELETE FROM offers WHERE id=? AND shop=?", offer, shop);
          db.exec("UPDATE shops SET layout=? WHERE id=?", Layouts.empty().toString(), shop);
          return null;
        });
  }

  public void removeShop(String owner, String id) {
    db.atomic(
        () -> {
          ownShop(owner, id);
          checkShopIdle(id);
          db.exec("DELETE FROM shops WHERE id=?", id);
          return null;
        });
  }

  public String prepareChest(
      String buyer, String offer, long expectedVersion, long expectedPrice, JsonObject evidence) {
    enabled("chestShops");
    return db.atomic(
        () -> {
          if (db.pending(buyer)) throw new IllegalArgumentException("Offener Inventarvorgang");
          var o =
              db.one(
                  "SELECT o.*,s.owner,s.version FROM offers o JOIN shops s ON s.id=o.shop WHERE"
                      + " o.id=? AND o.visible=1",
                  offer);
          itemAllowed(s(o, "material"), "chest");
          if (n(o, "version") != expectedVersion || n(o, "price") != expectedPrice)
            throw new IllegalArgumentException(cfg.message("stale"));
          if (s(o, "owner").equals(buyer)) throw new IllegalArgumentException("Eigener Shop");
          checkShopIdle(s(o, "shop"));
          String op = UUID.randomUUID().toString();
          db.change(buyer, -expectedPrice, op, "CHEST_HOLD");
          o.add("evidence", evidence);
          if (evidence.has("delivery")) o.add("delivery", evidence.get("delivery"));
          db.operation(op, "CHEST_BUY", buyer, "PREPARED", o);
          return op;
        });
  }

  public void finishChest(String op, String deliveredItem) {
    db.atomic(
        () -> {
          var row = prepared(op, "CHEST_BUY");
          var p = JsonParser.parseString(s(row, "payload")).getAsJsonObject();
          db.change(s(p, "owner"), n(p, "price"), op, "CHEST_SALE");
          db.mail(s(row, "owner"), deliveredItem, op);
          committed(op);
          return null;
        });
  }

  /**
   * Administrative resolution is explicit evidence-based; never automatically replay physical
   * changes.
   */
  public void resolve(String op, boolean applied, String note) {
    if (note.length() < 12)
      throw new IllegalArgumentException(
          "Provide a clear audit note (at least 12 characters)");
    db.atomic(
        () -> {
          var row = db.one("SELECT * FROM operations WHERE id=? AND state='PREPARED'", op);
          var p = JsonParser.parseString(s(row, "payload")).getAsJsonObject();
          String owner = s(row, "owner");
          String kind = s(row, "kind");
          if (applied) {
            switch (kind) {
              case "SERVER_SELL" -> finishSell(op);
              case "AUCTION_LIST" -> finishAuction(op);
              case "CLAIM" -> finishClaim(op);
              case "CHEST_BUY" -> finishChest(op, s(p, "delivery"));
              default -> throw new IllegalArgumentException("Nicht physischer Vorgang");
            }
          } else {
            switch (kind) {
              case "SERVER_SELL" -> {
                if (!p.get("mint").getAsBoolean())
                  db.change(SERVER, n(p.getAsJsonObject("quote"), "buy"), op, "REFUND");
              }
              case "AUCTION_LIST" -> db.change(owner, n(p, "fee"), op, "REFUND");
              case "CLAIM" -> db.exec("UPDATE mailbox SET state='READY' WHERE id=?", s(p, "mail"));
              case "CHEST_BUY" -> db.change(owner, n(p, "price"), op, "REFUND");
              default -> throw new IllegalArgumentException("Nicht physischer Vorgang");
            }
            db.exec("UPDATE operations SET state='CANCELLED' WHERE id=?", op);
          }
          db.exec("UPDATE operations SET resolution=? WHERE id=?", note, op);
          return null;
        });
  }

  public long voteFactor(String material) {
    if (!cfg.on("playerVotes")) return 10000;
    long window = cfg.n("votes", "windowSeconds"),
        cool = cfg.n("votes", "cooldownSeconds"),
        round = now() / (window + cool);
    var r = db.rows("SELECT * FROM vote_results WHERE material=?", material);
    if (r.isEmpty()) {
      db.exec("INSERT INTO vote_results(material) VALUES(?)", material);
      return 10000;
    }
    var result = r.getFirst();
    long last = n(result, "round");
    long completed = now() % (window + cool) >= window ? round : round - 1;
    if (completed > last) {
      long factor = n(result, "factor");
      String summary = "Keine abgeschlossenen Stimmen";
      for (var votes :
          db.rows(
              "SELECT round,COUNT(*) AS count,COALESCE(SUM(direction),0) AS total FROM votes WHERE"
                  + " material=? AND round>? AND round<=? GROUP BY round ORDER BY round",
              material,
              last,
              completed)) {
        long count = n(votes, "count"), total = n(votes, "total");
        if (count >= cfg.n("votes", "minParticipants")) {
          factor += Long.signum(total) * cfg.n("votes", "stepBps");
          factor =
              Math.max(
                  cfg.n("votes", "minFactorBps"), Math.min(cfg.n("votes", "maxFactorBps"), factor));
        }
        summary = "Runde " + n(votes, "round") + ": Teilnehmer " + count + ", Saldo " + total;
      }
      db.exec(
          "UPDATE vote_results SET factor=?,round=?,summary=? WHERE material=?",
          factor,
          completed,
          summary,
          material);
      return factor;
    }
    return n(result, "factor");
  }

  public void vote(String owner, String material, int direction) {
    enabled("playerVotes");
    prices.get(material);
    if (direction != 1 && direction != -1) throw new IllegalArgumentException("Invalid Vote");
    for (var e : cfg.section("votes").getAsJsonArray("excluded"))
      if (e.getAsString().equals(material))
        throw new IllegalArgumentException("Item von Abstimmungen ausgeschlossen");
    long window = cfg.n("votes", "windowSeconds"),
        cycle = window + cfg.n("votes", "cooldownSeconds");
    if (now() % cycle >= window) throw new IllegalArgumentException("Voting Is on Cooldown");
    voteFactor(material);
    if (db.exec(
            "INSERT OR IGNORE INTO votes VALUES(?,?,?,?)",
            material,
            owner,
            now() / cycle,
            direction)
        == 0) throw new IllegalArgumentException("You Already Voted for This Item");
  }

  public JsonObject voteStatus(String material) {
    long factor = voteFactor(material),
        cycle = cfg.n("votes", "windowSeconds") + cfg.n("votes", "cooldownSeconds");
    return obj(
        "factorBps",
        factor,
        "round",
        now() / cycle,
        "votingEndsAt",
        now() / cycle * cycle + cfg.n("votes", "windowSeconds"),
        "nextStartsAt",
        (now() / cycle + 1) * cycle,
        "result",
        db.one("SELECT summary FROM vote_results WHERE material=?", material).get("summary"));
  }
}
