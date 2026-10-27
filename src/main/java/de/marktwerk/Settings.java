package de.marktwerk;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

public final class Settings {
  public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  public final JsonObject root;

  public Settings(Path path) throws Exception {
    root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    validate();
  }

  public boolean on(String module) {
    return root.getAsJsonObject("modules").get(module).getAsBoolean();
  }

  public JsonObject section(String s) {
    return root.getAsJsonObject(s);
  }

  public long n(String section, String key) {
    return section(section).get(key).getAsBigDecimal().longValueExact();
  }

  public String text(String section, String key) {
    return section(section).get(key).getAsString();
  }

  public String message(String key) {
    return text("messages", key);
  }

  public boolean excluded(String material, String domain) {
    for (String key : List.of("excludedMaterials", domain + "Excluded"))
      for (var e : section("items").getAsJsonArray(key))
        if (e.getAsString().equals(material)) return true;
    return false;
  }

  private void validate() {
    for (String k :
        List.of(
            "serverShop",
            "chestShops",
            "auctionHouse",
            "httpApi",
            "bedrockForms",
            "menuItem",
            "supplierBonus",
            "rarityPrices",
            "playerVotes",
            "stockPrices",
            "vault")) on(k);
    for (String k : List.of("supplierBonus", "rarityPrices", "playerVotes", "stockPrices"))
      if (on(k) && !on("serverShop"))
        throw new IllegalArgumentException(k + " requires serverShop");
    if (on("httpApi") && !on("chestShops"))
      throw new IllegalArgumentException("httpApi requires chestShops");
    Money.valid(n("currency", "startingMinor"));
    Money.valid(n("currency", "serverInitialMinor"));
    if ((n("pricing", "referenceStock") < 1 || n("pricing", "referenceStock") > 1000000000)
        || n("pricing", "maxQuantity") < 1
        || n("pricing", "maxQuantity") > 4096
        || n("pricing", "minMinor") < 1
        || n("pricing", "maxMinor") < n("pricing", "minMinor"))
      throw new IllegalArgumentException("Invalid price limits");
    Money.valid(n("pricing", "maxMinor"));
    if (n("pricing", "strengthBps") < 0
        || n("pricing", "strengthBps") > 10000
        || n("pricing", "minFactorBps") < 1
        || n("pricing", "minFactorBps") > 10000
        || n("pricing", "maxFactorBps") < 10000
        || n("pricing", "maxFactorBps") > 100000)
      throw new IllegalArgumentException("Invalid stock factors");
    if (n("votes", "minFactorBps") < 1
        || n("votes", "minFactorBps") > 10000
        || n("votes", "maxFactorBps") < 10000
        || n("votes", "maxFactorBps") > 100000
        || n("votes", "stepBps") < 1
        || n("votes", "stepBps") > 1000
        || n("votes", "minParticipants") < 1
        || (n("votes", "windowSeconds") < 60
            || n("votes", "windowSeconds") > 2592000
            || n("votes", "cooldownSeconds") > 2592000)
        || n("votes", "cooldownSeconds") < 0)
      throw new IllegalArgumentException("Invalid voting configuration");
    if (n("auctions", "limit") < 1
        || n("auctions", "durationSeconds") < 60
        || n("auctions", "durationSeconds") > n("auctions", "maxDurationSeconds")
        || n("auctions", "maxDurationSeconds") > 2592000
        || n("auctions", "saleFeeBps") < 0
        || n("auctions", "saleFeeBps") > 10000)
      throw new IllegalArgumentException("Invalid auction configuration");
    Money.valid(n("auctions", "listingFeeMinor"));
    if (n("api", "port") < 1
        || n("api", "port") > 65535
        || n("api", "codeSeconds") < 30
        || n("api", "codeSeconds") > 600
        || n("api", "tokenSeconds") < 60
        || n("api", "tokenSeconds") > 86400
        || n("api", "requestsPerMinute") < 1
        || n("api", "codeAttemptsPerMinute") < 1)
      throw new IllegalArgumentException("Invalid API configuration");
    for (var origin : section("api").getAsJsonArray("allowedOrigins")) {
      String s = origin.getAsString();
      if (!s.matches("https://[a-zA-Z0-9.:-]+") && !s.matches("http://localhost(:[0-9]+)?"))
        throw new IllegalArgumentException("Origin muss explizit und ohne Pfad angegeben werden");
    }
    if (n("menu", "preferredSlot") < 0 || n("menu", "preferredSlot") > 35)
      throw new IllegalArgumentException("Invalid Menu Slot");
  }
}
