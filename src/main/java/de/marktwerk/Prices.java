package de.marktwerk;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class Prices {
  public record Article(String material, long buy, long sell, int bonus) {}

  public record Quote(
      String material, int quantity, long stock, long vote, long buy, long sell, Fraction bonus) {}

  private final Settings cfg;
  private final Map<String, Article> articles = new TreeMap<>();

  public Prices(Settings cfg, Path rarity) throws Exception {
    this.cfg = cfg;
    JsonObject all = JsonParser.parseString(Files.readString(rarity)).getAsJsonObject();
    if (cfg.on("rarityPrices"))
      for (var e : all.entrySet()) {
        var a = e.getValue().getAsJsonObject();
        if (a.get("tradable").getAsBoolean())
          add(
              e.getKey(),
              a.get("buyMinor").getAsLong(),
              a.get("sellMinor").getAsLong(),
              (int) cfg.n("serverShop", "bonusBps"));
      }
    for (var e : cfg.section("serverShop").getAsJsonObject("manual").entrySet()) {
      var a = e.getValue().getAsJsonObject();
      if (a.has("excluded") && a.get("excluded").getAsBoolean()) {
        articles.remove(e.getKey());
        continue;
      }
      add(
          e.getKey(),
          a.get("buyMinor").getAsLong(),
          a.get("sellMinor").getAsLong(),
          a.get("bonusBps").getAsInt());
    }
  }

  private void add(String m, long buy, long sell, int bonus) {
    if (cfg.excluded(m, "server")) {
      articles.remove(m);
      return;
    }
    Money.valid(buy);
    Money.positive(sell);
    if (bonus < 0 || bonus > 5000)
      throw new IllegalArgumentException("Bonus außerhalb 0..5000: " + m);
    var a = new Article(m, buy, sell, bonus);
    long floor = minSell(a);
    if (buy > floor - maxBonus(a) - 1)
      throw new IllegalArgumentException(
          "Unsicherer Ankaufspreis: " + m + "; maximal " + (floor - maxBonus(a) - 1));
    articles.put(m, a);
  }

  public Collection<Article> all() {
    return List.copyOf(articles.values());
  }

  public Article get(String m) {
    var a = articles.get(m);
    if (a == null) throw new IllegalArgumentException("Item nicht handelbar");
    return a;
  }

  private long clamp(long n) {
    return Math.max(cfg.n("pricing", "minMinor"), Math.min(cfg.n("pricing", "maxMinor"), n));
  }

  private long minSell(Article a) {
    long n = a.sell;
    if (cfg.on("playerVotes")) n = Money.ratio(n, cfg.n("votes", "minFactorBps"), 10000);
    if (cfg.on("stockPrices")) n = Money.ratio(n, cfg.n("pricing", "minFactorBps"), 10000);
    return clamp(n);
  }

  private long maxBonus(Article a) {
    long n = a.sell;
    if (cfg.on("playerVotes")) n = Money.ratio(n, cfg.n("votes", "maxFactorBps"), 10000);
    if (cfg.on("stockPrices")) n = Money.ratio(n, cfg.n("pricing", "maxFactorBps"), 10000);
    var exact = Fraction.of(clamp(n)).mul(a.bonus, 10000);
    return exact.floor() + (exact.rest().equals(Fraction.ZERO) ? 0 : 1);
  }

  public long unit(Article a, long edge, long vote) {
    long n = a.sell;
    if (cfg.on("playerVotes"))
      n =
          Money.ratio(
              n,
              Math.max(
                  cfg.n("votes", "minFactorBps"), Math.min(cfg.n("votes", "maxFactorBps"), vote)),
              10000);
    if (cfg.on("stockPrices")) {
      long ref = cfg.n("pricing", "referenceStock");
      long capped = Math.min(Math.max(0, edge), ref * 100);
      long f = 10000 + cfg.n("pricing", "strengthBps") * (ref - capped) / Math.max(ref, capped);
      f = Math.max(cfg.n("pricing", "minFactorBps"), Math.min(cfg.n("pricing", "maxFactorBps"), f));
      n = Money.ratio(n, f, 10000);
    }
    return clamp(n);
  }

  public Quote quote(String material, int quantity, long stock, long vote) {
    if (quantity < 1 || quantity > cfg.n("pricing", "maxQuantity"))
      throw new IllegalArgumentException("Invalid Quantity");
    var a = get(material);
    long sell = 0, buy = 0;
    Fraction bonus = Fraction.ZERO;
    for (int i = 0; i < quantity; i++) {
      if (stock >= quantity) {
        long u = unit(a, stock - i, vote);
        sell = Money.add(sell, u);
        if (cfg.on("supplierBonus")) bonus = bonus.add(Fraction.of(u).mul(a.bonus, 10000));
      }
      long edge = unit(a, Math.addExact(stock, i + 1), vote);
      long upper = minSell(a) - maxBonus(a) - 1;
      long b = Math.min(upper, Money.ratio(edge, a.buy, a.sell));
      buy = Money.add(buy, Math.max(0, b));
    }
    return new Quote(material, quantity, stock, vote, buy, sell, bonus);
  }
}
