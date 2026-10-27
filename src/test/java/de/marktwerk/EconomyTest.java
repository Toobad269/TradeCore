package de.marktwerk;

import static de.marktwerk.Store.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class EconomyTest {
  @TempDir Path dir;
  Store db;
  Market market;
  Settings cfg;
  Prices prices;
  static final String A = "11111111-1111-1111-1111-111111111111",
      B = "22222222-2222-2222-2222-222222222222",
      C = "33333333-3333-3333-3333-333333333333";

  <T> T run(Supplier<T> s) {
    return db.submit(s).join();
  }

  @BeforeEach
  void setup() throws Exception {
    Files.copy(Path.of("src/main/resources/config.json"), dir.resolve("config.json"));
    cfg = new Settings(dir.resolve("config.json"));
    prices = new Prices(cfg, Path.of("src/main/resources/prices.json"));
    db = new Store(dir.resolve("data.db"));
    market = new Market(db, cfg, prices);
    run(
        () -> {
          db.account(A, "Alice", 100000);
          db.account(B, "Bob", 100000);
          db.account(C, "Charlie", 100000);
          return null;
        });
  }

  @AfterEach
  void close() {
    if (db != null) db.close();
  }

  void deposit(String owner, String material, int quantity) {
    run(
        () -> {
          var q = market.quote(material, quantity);
          String op =
              market.prepareSell(
                  owner, q, "item-data", obj("beforeHash", "before", "afterHash", "after"));
          market.finishSell(op);
          return null;
        });
  }

  @Test
  void exactMoneyAndOverflow() {
    assertEquals(1234, Money.parse("12,34"));
    assertEquals(101, Money.fromVault(1.005));
    assertEquals("0.01", Money.format(1));
    assertThrows(ArithmeticException.class, () -> Money.parse("0.001"));
    assertThrows(IllegalArgumentException.class, () -> Money.parse("-1"));
    assertThrows(IllegalArgumentException.class, () -> Money.fromVault(Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Money.add(Money.MAX, 1));
    assertThrows(ArithmeticException.class, () -> Money.times(Money.MAX, Money.MAX));
  }

  @Test
  void fractionConservation() {
    Fraction a = Fraction.of(60), b = Fraction.of(140);
    for (int i = 0; i < 199; i++) {
      long stock = 200 - i;
      a = a.mul(stock - 1, stock);
      b = b.mul(stock - 1, stock);
      assertEquals(Fraction.of(stock - 1), a.add(b));
    }
    assertEquals(new Fraction(java.math.BigInteger.valueOf(3), java.math.BigInteger.TEN), a);
  }

  @Test
  void transferIsAtomicAndIdempotent() {
    run(
        () -> {
          db.transfer(A, B, 500, "same-operation");
          db.transfer(A, B, 500, "same-operation");
          assertEquals(99500, db.balance(A));
          assertEquals(100500, db.balance(B));
          assertThrows(
              IllegalArgumentException.class, () -> db.transfer(A, B, Money.MAX, "too-big"));
          assertEquals(99500, db.balance(A));
          assertEquals(100500, db.balance(B));
          return null;
        });
  }

  @Test
  void supplierExampleAndOfflineCredit() {
    deposit(A, "DIRT", 60);
    deposit(B, "DIRT", 140);
    long before = run(() -> db.balance(A));
    run(
        () -> {
          var q = market.quote("DIRT", 20);
          market.serverBuy(C, q, "20-dirt", "purchase");
          var row = db.one("SELECT units FROM suppliers WHERE owner=? AND material='DIRT'", A);
          assertEquals(Fraction.of(54), Fraction.parse(s(row, "units")));
          assertEquals(180, market.stock("DIRT"));
          return null;
        });
    assertEquals(before + 3, run(() -> db.balance(A)));
  }

  @Test
  void bonusRemaindersAreConservedAndNeverPaidTwice() {
    deposit(A, "DIAMOND", 1);
    deposit(B, "DIAMOND", 2);
    long oldA = run(() -> db.balance(A)), oldB = run(() -> db.balance(B));
    run(
        () -> {
          var q = market.quote("DIAMOND", 1);
          market.serverBuy(C, q, "one", "buy1");
          market.serverBuy(C, q, "one", "buy1");
          assertEquals(2, market.stock("DIAMOND"));
          market.serverBuy(C, market.quote("DIAMOND", 1), "two", "buy2");
          market.serverBuy(C, market.quote("DIAMOND", 1), "three", "buy3");
          assertEquals(oldA + 50, db.balance(A));
          assertEquals(oldB + 100, db.balance(B));
          for (var row : db.rows("SELECT * FROM suppliers WHERE material='DIAMOND'")) {
            assertEquals(Fraction.ZERO, Fraction.parse(s(row, "units")));
            assertEquals(Fraction.ZERO, Fraction.parse(s(row, "carry")));
          }
          return null;
        });
  }

  @Test
  void saleFundsReservedAndRecoveryAfterRestart() {
    String op =
        run(
            () ->
                market.prepareSell(
                    A,
                    market.quote("DIRT", 5),
                    "five-items",
                    obj("beforeHash", "before", "afterHash", "after")));
    long funds = run(() -> db.balance(Market.SERVER));
    db.close();
    db = new Store(dir.resolve("data.db"));
    market = new Market(db, cfg, prices);
    run(
        () -> {
          assertTrue(db.pending(A));
          assertEquals(0, market.stock("DIRT"));
          assertEquals(100000, db.balance(A));
          assertEquals(funds, db.balance(Market.SERVER));
          market.resolve(op, true, "Test: nachgewiesene Inventarentnahme");
          assertEquals(5, market.stock("DIRT"));
          assertEquals(100005, db.balance(A));
          assertThrows(
              IllegalArgumentException.class,
              () -> market.resolve(op, true, "Test: keine doppelte Ausführung"));
          return null;
        });
  }

  @Test
  void abortBeforePhysicalChangeRefundsExactlyOnce() {
    run(
        () -> {
          long before = db.balance(Market.SERVER);
          String op = market.prepareSell(A, market.quote("DIRT", 4), "item", obj());
          market.resolve(op, false, "Test: Inventar wurde nicht verändert");
          assertEquals(before, db.balance(Market.SERVER));
          assertEquals(0, market.stock("DIRT"));
          assertFalse(db.pending(A));
          assertThrows(
              IllegalArgumentException.class,
              () -> market.resolve(op, false, "Test: keine doppelte Rückzahlung"));
          return null;
        });
  }

  @Test
  void twoBuyersCannotBuyOneAuction() throws Exception {
    String id =
        run(
            () -> {
              String op =
                  market.prepareAuction(
                      A, "enchanted-full-item", "DIAMOND_SWORD", 500, 3600, obj());
              market.finishAuction(op);
              return op;
            });
    ExecutorService clients = Executors.newFixedThreadPool(2);
    try {
      var calls = new ArrayList<Future<Boolean>>();
      for (String buyer : List.of(B, C))
        calls.add(
            clients.submit(
                () -> {
                  try {
                    run(
                        () -> {
                          market.buyAuction(buyer, id, 500, UUID.randomUUID().toString());
                          return null;
                        });
                    return true;
                  } catch (CompletionException e) {
                    return false;
                  }
                }));
      int successes = 0;
      for (var f : calls) if (f.get()) successes++;
      assertEquals(1, successes);
      run(
          () -> {
            assertEquals(
                1, db.number("SELECT COUNT(*) FROM mailbox WHERE item='enchanted-full-item'"));
            assertEquals(1, db.number("SELECT COUNT(*) FROM auctions WHERE state='SOLD'"));
            assertEquals(100480, db.balance(A));
            return null;
          });
    } finally {
      clients.shutdownNow();
    }
  }

  @Test
  void expiredAndCancelledAuctionsPersistInMailbox() {
    run(
        () -> {
          String op = market.prepareAuction(A, "one", "DIAMOND", 100, 60, obj());
          market.finishAuction(op);
          db.exec("UPDATE auctions SET expires=0 WHERE id=?", op);
          market.expire();
          market.expire();
          assertEquals(1, db.number("SELECT COUNT(*) FROM mailbox"));
          String op2 = market.prepareAuction(A, "two", "DIAMOND", 100, 60, obj());
          market.finishAuction(op2);
          market.cancelAuction(A, op2);
          assertThrows(IllegalArgumentException.class, () -> market.cancelAuction(A, op2));
          assertEquals(2, db.number("SELECT COUNT(*) FROM mailbox"));
          return null;
        });
  }

  @Test
  void pendingClaimIsNeverAutomaticallyReissued() {
    run(
        () -> {
          db.mail(A, "named-enchanted-item", "source");
          String id = s(db.one("SELECT id FROM mailbox"), "id");
          String op = market.prepareClaim(A, id, obj("beforeHash", "x", "afterHash", "y"));
          assertEquals("HELD", s(db.one("SELECT state FROM mailbox WHERE id=?", id), "state"));
          assertThrows(IllegalArgumentException.class, () -> market.prepareClaim(A, id, obj()));
          market.resolve(op, true, "Test: Ausgabe in Spielerdatei bestätigt");
          assertEquals("DELIVERED", s(db.one("SELECT state FROM mailbox WHERE id=?", id), "state"));
          assertEquals(
              "named-enchanted-item", s(db.one("SELECT item FROM mailbox WHERE id=?", id), "item"));
          return null;
        });
  }

  @Test
  void serverConcurrentStockReservation() {
    deposit(A, "DIRT", 1);
    var quote = run(() -> market.quote("DIRT", 1));
    CompletableFuture<Boolean>
        first =
            db.submit(
                () -> {
                  market.serverBuy(B, quote, "item", "first");
                  return true;
                }),
        second =
            db.submit(
                () -> {
                  market.serverBuy(C, quote, "item", "second");
                  return true;
                });
    assertTrue(first.join());
    assertThrows(CompletionException.class, second::join);
    assertEquals(0, run(() -> market.stock("DIRT")));
  }

  @Test
  void optimisticLayoutOwnershipAndSafeActions() {
    run(
        () -> {
          String shop = market.createShop(A, "world", 1, 2, 3, "Test");
          assertThrows(
              IllegalArgumentException.class, () -> market.layout(B, shop, 1, Layouts.empty()));
          market.layout(A, shop, 1, Layouts.empty());
          assertThrows(
              IllegalArgumentException.class, () -> market.layout(A, shop, 1, Layouts.empty()));
          var layout =
              obj(
                  "mode",
                  "CUSTOM",
                  "pages",
                  List.of(
                      obj(
                          "id",
                          "home",
                          "title",
                          "Test",
                          "fields",
                          List.of(
                              obj(
                                  "slot",
                                  0,
                                  "icon",
                                  "PAPER",
                                  "label",
                                  "Hack",
                                  "action",
                                  "RUN_COMMAND")))));
          assertThrows(IllegalArgumentException.class, () -> market.layout(A, shop, 2, layout));
          assertEquals(2, n(market.shop(shop), "version"));
          return null;
        });
  }

  @Test
  void chestPurchaseReservesAndQuarantines() {
    run(
        () -> {
          String shop = market.createShop(A, "world", 1, 2, 3, "Chest"),
              offer = UUID.randomUUID().toString();
          market.updateOffer(A, shop, 1, offer, "exact-meta", "DIAMOND", 2, 300, true);
          String op = market.prepareChest(B, offer, 2, 300, obj());
          assertEquals(99700, db.balance(B));
          assertEquals(100000, db.balance(A));
          assertThrows(
              IllegalArgumentException.class, () -> market.prepareChest(C, offer, 2, 300, obj()));
          assertThrows(IllegalArgumentException.class, () -> market.removeShop(A, shop));
          db.exec(
              "UPDATE operations SET payload=json_set(payload,'$.delivery','full-stack') WHERE"
                  + " id=?",
              op);
          market.resolve(op, true, "Test: Chestnentnahme bestätigt und gesichert");
          assertEquals(100300, db.balance(A));
          assertEquals(
              "full-stack", s(db.one("SELECT item FROM mailbox WHERE owner=?", B), "item"));
          return null;
        });
  }

  @Test
  void moduleDisableRetainsMailAndBalances() {
    run(
        () -> {
          db.mail(A, "item", "module-test");
          cfg.section("modules").addProperty("auctionHouse", false);
          assertThrows(
              IllegalArgumentException.class,
              () -> market.prepareAuction(A, "x", "DIRT", 1, 60, obj()));
          assertEquals(1, db.number("SELECT COUNT(*) FROM mailbox"));
          assertEquals(100000, db.balance(A));
          return null;
        });
  }

  @Test
  void priceCombinationsAndSplitInvariance() throws Exception {
    for (int mask = 0; mask < 8; mask++) {
      var root =
          JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject();
      root.getAsJsonObject("modules").addProperty("rarityPrices", (mask & 1) != 0);
      root.getAsJsonObject("modules").addProperty("playerVotes", (mask & 2) != 0);
      root.getAsJsonObject("modules").addProperty("stockPrices", (mask & 4) != 0);
      Path file = dir.resolve("combination.json");
      Files.writeString(file, root.toString());
      var settings = new Settings(file);
      var pricing = new Prices(settings, Path.of("src/main/resources/prices.json"));
      for (String material : List.of("DIRT", "DIAMOND"))
        for (long factor : new long[] {8000, 10000, 12000}) {
          var combined = pricing.quote(material, 64, 2000, factor);
          long splitSell = 0, splitBuy = 0;
          Fraction bonus = Fraction.ZERO;
          for (int i = 0; i < 64; i++) {
            var sell = pricing.quote(material, 1, 2000 - i, factor);
            splitSell = Money.add(splitSell, sell.sell());
            bonus = bonus.add(sell.bonus());
            splitBuy = Money.add(splitBuy, pricing.quote(material, 1, 2000 + i, factor).buy());
          }
          assertEquals(combined.sell(), splitSell);
          assertEquals(combined.buy(), splitBuy);
          assertEquals(combined.bonus(), bonus);
          assertTrue(combined.sell() > combined.buy() + combined.bonus().floor());
        }
    }
  }

  @Test
  void votesPersistRejectDuplicateAndRespectCooldown() {
    run(
        () -> {
          cfg.section("modules").addProperty("playerVotes", true);
          cfg.section("votes").addProperty("windowSeconds", 60);
          cfg.section("votes").addProperty("cooldownSeconds", 0);
          market.vote(A, "DIRT", 1);
          assertThrows(IllegalArgumentException.class, () -> market.vote(A, "DIRT", -1));
          assertEquals(1, db.number("SELECT COUNT(*) FROM votes WHERE owner=?", A));
          return null;
        });
  }

  @Test
  void backupIsConsistent() {
    run(
        () -> {
          db.transfer(A, B, 12, "backup-test");
          db.backup(dir.resolve("backup.db"));
          return null;
        });
    try (var other = new Store(dir.resolve("backup.db"))) {
      assertEquals(99988, other.submit(() -> other.balance(A)).join());
    }
  }

  @Test
  void rejectsFutureBonusArbitrageEvenIfImmediateSpreadLooksSafe() throws Exception {
    var root =
        JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject();
    root.getAsJsonObject("modules").addProperty("stockPrices", true);
    root.getAsJsonObject("modules").addProperty("playerVotes", true);
    root.getAsJsonObject("serverShop")
        .getAsJsonObject("manual")
        .getAsJsonObject("DIAMOND")
        .addProperty("buyMinor", 350);
    Path file = dir.resolve("unsafe.json");
    Files.writeString(file, root.toString());
    var settings = new Settings(file);
    assertThrows(
        IllegalArgumentException.class,
        () -> new Prices(settings, Path.of("src/main/resources/prices.json")));
  }

  @Test
  void closedVotingRoundsAreNotLostWhileServerIsOffline() {
    run(
        () -> {
          cfg.section("modules").addProperty("playerVotes", true);
          cfg.section("votes").addProperty("windowSeconds", 60);
          cfg.section("votes").addProperty("cooldownSeconds", 0);
          cfg.section("votes").addProperty("minParticipants", 2);
          long round = now() / 60;
          for (long r : new long[] {round - 3, round - 2}) {
            db.exec("INSERT INTO votes VALUES(?,?,?,1)", "DIAMOND", A, r);
            db.exec("INSERT INTO votes VALUES(?,?,?,1)", "DIAMOND", B, r);
          }
          db.exec(
              "INSERT INTO vote_results(material,factor,round) VALUES('DIAMOND',10000,?)",
              round - 4);
          assertEquals(10400, market.voteFactor("DIAMOND"));
          assertEquals(10400, market.voteFactor("DIAMOND"));
          return null;
        });
  }

  @Test
  void insufficientServerFundsPreventPhysicalPreparation() {
    run(
        () -> {
          db.change(Market.SERVER, -db.balance(Market.SERVER), "empty-server", "ADMIN");
          assertThrows(
              IllegalArgumentException.class,
              () -> market.prepareSell(A, market.quote("DIAMOND", 1), "item", obj()));
          assertFalse(db.pending(A));
          assertEquals(0, market.stock("DIAMOND"));
          assertEquals(100000, db.balance(A));
          return null;
        });
  }

  @Test
  void catalogueIsCompleteAndHasExplicitDecisions() throws Exception {
    var catalogue =
        JsonParser.parseString(Files.readString(Path.of("src/main/resources/prices.json")))
            .getAsJsonObject();
    var registry = Files.readAllLines(Path.of("docs/registry-1.21.11.txt"));
    assertEquals(1505, registry.size());
    assertEquals(new HashSet<>(registry), catalogue.keySet());
    for (var e : catalogue.entrySet()) {
      var a = e.getValue().getAsJsonObject();
      assertTrue(a.has("tradable"));
      assertFalse(s(a, "reason").isBlank());
      assertTrue(n(a, "sellMinor") >= 10);
      assertTrue(n(a, "rarityScore") >= 1);
    }
    assertFalse(catalogue.getAsJsonObject("COMMAND_BLOCK").get("tradable").getAsBoolean());
    assertFalse(catalogue.getAsJsonObject("OAK_SIGN").get("tradable").getAsBoolean());
  }

  @Test
  void largeQuotesAndPriceLimits() {
    var q = prices.quote("DIAMOND", 4096, 1000000, 10000);
    assertEquals(4096000, q.sell());
    assertEquals(409600, q.buy());
    assertThrows(IllegalArgumentException.class, () -> prices.quote("DIAMOND", 0, 0, 10000));
    assertThrows(IllegalArgumentException.class, () -> prices.quote("DIAMOND", 4097, 10000, 10000));
    assertEquals(0, prices.quote("DIAMOND", 1, 0, 10000).sell());
  }

  @Test
  void disablingBonusDoesNotRetroactivelyPayAndRetainsClaims() {
    deposit(A, "DIAMOND", 1);
    deposit(B, "DIAMOND", 2);
    run(
        () -> {
          market.serverBuy(C, market.quote("DIAMOND", 1), "one", "bonus-one");
          long before = db.balance(A), reserve = db.balance(Market.BONUS);
          String carry =
              s(
                  db.one("SELECT carry FROM suppliers WHERE owner=? AND material='DIAMOND'", A),
                  "carry");
          assertNotEquals("0/1", carry);
          cfg.section("modules").addProperty("supplierBonus", false);
          market.serverBuy(C, market.quote("DIAMOND", 1), "two", "bonus-two");
          assertEquals(before, db.balance(A));
          assertEquals(reserve, db.balance(Market.BONUS));
          assertEquals(
              carry,
              s(
                  db.one("SELECT carry FROM suppliers WHERE owner=? AND material='DIAMOND'", A),
                  "carry"));
          return null;
        });
  }

  @Test
  void linkingUsesHashedExpiringOneTimeCodesAndRevocableTokens() {
    run(
        () -> {
          var tokens = new AccessTokens(db, cfg);
          String old = tokens.issue(A), code = tokens.issue(A);
          assertThrows(IllegalArgumentException.class, () -> tokens.codeOwner(old));
          assertEquals(A, tokens.codeOwner(code));
          assertEquals(0, db.number("SELECT COUNT(*) FROM credentials WHERE hash=?", code));
          var result = tokens.exchange(code, A);
          assertEquals(A, tokens.owner(result.accessToken()));
          assertEquals(43, result.accessToken().length());
          assertThrows(IllegalArgumentException.class, () -> tokens.exchange(code, A));
          assertEquals(
              0, db.number("SELECT COUNT(*) FROM credentials WHERE hash=?", result.accessToken()));
          tokens.revoke(A, result.accessToken());
          assertThrows(IllegalArgumentException.class, () -> tokens.owner(result.accessToken()));
          String expired = tokens.issue(A);
          db.exec("UPDATE credentials SET expires=0");
          assertThrows(IllegalArgumentException.class, () -> tokens.codeOwner(expired));
          String active = tokens.issue(A);
          tokens.exchange(active, A);
          tokens.revokeAll(A);
          assertEquals(0, db.number("SELECT COUNT(*) FROM credentials WHERE owner=?", A));
          return null;
        });
  }

  @Test
  void oneTimeCodeCannotBeReusedForAnotherOwner() {
    run(
        () -> {
          var tokens = new AccessTokens(db, cfg);
          String code = tokens.issue(A);
          tokens.exchange(code, A);
          assertThrows(IllegalArgumentException.class, () -> tokens.exchange(code, B));
          assertEquals(1, db.number("SELECT COUNT(*) FROM credentials WHERE kind='TOKEN'"));
          return null;
        });
  }

  @Test
  void globalAndPerModuleItemExclusionsAreEnforced() {
    run(
        () -> {
          cfg.section("items").getAsJsonArray("auctionExcluded").add("DIAMOND");
          assertThrows(
              IllegalArgumentException.class,
              () -> market.prepareAuction(A, "item", "DIAMOND", 100, 60, obj()));
          assertFalse(db.pending(A));
          assertEquals(100000, db.balance(A));
          cfg.section("items").getAsJsonArray("excludedMaterials").add("DIRT");
          assertThrows(IllegalArgumentException.class, () -> market.itemAllowed("DIRT", "chest"));
          return null;
        });
  }

  @Test
  void subCentBonusesAccumulateAcrossSeparatePurchasesWithoutLoss() {
    deposit(A, "DIRT", 2);
    run(
        () -> {
          long before = db.balance(A), sum = db.number("SELECT SUM(balance) FROM accounts");
          assertEquals(Fraction.of(1), market.quote("DIRT", 2).bonus());
          market.serverBuy(C, market.quote("DIRT", 1), "first", "fraction-first");
          assertEquals(before, db.balance(A));
          assertEquals(
              "1/2",
              s(db.one("SELECT bonus_carry FROM stock WHERE material='DIRT'"), "bonus_carry"));
          assertEquals(
              "1/2",
              s(
                  db.one("SELECT carry FROM suppliers WHERE material='DIRT' AND owner=?", A),
                  "carry"));
          market.serverBuy(C, market.quote("DIRT", 1), "second", "fraction-second");
          assertEquals(before + 1, db.balance(A));
          assertEquals(0, db.balance(Market.BONUS));
          assertEquals(
              "0/1",
              s(db.one("SELECT bonus_carry FROM stock WHERE material='DIRT'"), "bonus_carry"));
          assertEquals(sum, db.number("SELECT SUM(balance) FROM accounts"));
          return null;
        });
  }
}
