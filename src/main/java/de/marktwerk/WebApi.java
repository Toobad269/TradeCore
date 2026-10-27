package de.marktwerk;

import static de.marktwerk.Store.*;

import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Versioned management-only API. Does not expose purchases, balances writes or inventory writes.
 */
public final class WebApi implements AutoCloseable {
  private final MarktWerk p;
  private HttpServer server;
  private final ExecutorService pool =
      new ThreadPoolExecutor(
          2,
          4,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          new ThreadPoolExecutor.AbortPolicy());
  private final AccessTokens tokens;
  private final Map<String, Counter> limits = new HashMap<>();

  private record Counter(long minute, int count) {}

  private static final class ApiError extends RuntimeException {
    final int status;
    final String code;

    ApiError(int status, String code) {
      super(code);
      this.status = status;
      this.code = code;
    }
  }

  public WebApi(MarktWerk p) {
    this.p = p;
    this.tokens = new AccessTokens(p.db, p.cfg);
  }

  public void start() throws IOException {
    server =
        HttpServer.create(
            new InetSocketAddress(p.cfg.text("api", "host"), (int) p.cfg.n("api", "port")), 32);
    server.createContext("/api/v1", this::handle);
    server.setExecutor(pool);
    server.start();
  }

  public String issue(String owner) {
    return tokens.issue(owner);
  }

  private synchronized void rate(String key, long maximum) {
    long minute = now() / 60;
    limits.entrySet().removeIf(e -> e.getValue().minute < minute);
    if (limits.size() > 10000 && !limits.containsKey(key)) throw new ApiError(429, "RATE_LIMIT");
    Counter c = limits.get(key);
    int count = c == null || c.minute != minute ? 1 : c.count + 1;
    limits.put(key, new Counter(minute, count));
    if (count > maximum) throw new ApiError(429, "RATE_LIMIT");
  }

  private boolean allowedOrigin(String origin) {
    for (var o : p.cfg.section("api").getAsJsonArray("allowedOrigins"))
      if (o.getAsString().equals(origin)) return true;
    return false;
  }

  private <T> T db(java.util.function.Supplier<T> task) {
    return p.db.submit(task).join();
  }

  private <T> T main(java.util.function.Supplier<T> task) {
    try {
      return p.onMain(task).get(10, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw new CompletionException(e.getCause());
    } catch (Exception e) {
      throw new ApiError(503, "SERVER_BUSY");
    }
  }

  private String authenticate(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("Authorization");
    if (header == null || !header.startsWith("Bearer ") || header.length() > 200)
      throw new ApiError(401, "TOKEN_REQUIRED");
    String owner;
    try {
      owner = db(() -> tokens.owner(header.substring(7)));
    } catch (CompletionException e) {
      if (e.getCause() instanceof IllegalArgumentException)
        throw new ApiError(401, "TOKEN_INVALID");
      throw e;
    }
    if (!main(() -> p.apiAllowed(owner)))
      throw new ApiError(403, "PLAYER_OFFLINE_OR_PERMISSION_DENIED");
    return owner;
  }

  private void handle(HttpExchange exchange) {
    try {
      String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
      rate("ip:" + ip, p.cfg.n("api", "requestsPerMinute"));
      String origin = exchange.getRequestHeaders().getFirst("Origin");
      if (origin != null) {
        if (!allowedOrigin(origin)) throw new ApiError(403, "ORIGIN_DENIED");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Vary", "Origin");
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "ETag");
      }
      exchange.getResponseHeaders().set("Cache-Control", "no-store");
      exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
      String method = exchange.getRequestMethod();
      if (method.equals("OPTIONS")) {
        exchange
            .getResponseHeaders()
            .set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        exchange
            .getResponseHeaders()
            .set("Access-Control-Allow-Headers", "Authorization, Content-Type, If-Match");
        send(exchange, 204, null);
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (exchange.getRequestURI().getRawQuery() != null)
        throw new ApiError(400, "QUERY_NOT_SUPPORTED");
      if (!path.equals("/api/v1") && !path.startsWith("/api/v1/"))
        throw new ApiError(404, "NOT_FOUND");
      if (path.equals("/api/v1/link") && method.equals("POST")) {
        rate("codes:" + ip, p.cfg.n("api", "codeAttemptsPerMinute"));
        JsonObject body = body(exchange, Set.of("code"));
        String code = s(body, "code");
        if (code.length() > 80) throw new ApiError(401, "CODE_INVALID");
        String owner;
        try {
          owner = db(() -> tokens.codeOwner(code));
        } catch (CompletionException e) {
          if (e.getCause() instanceof IllegalArgumentException)
            throw new ApiError(401, "CODE_INVALID");
          throw e;
        }
        if (!main(() -> p.apiAllowed(owner)))
          throw new ApiError(403, "PLAYER_OFFLINE_OR_PERMISSION_DENIED");
        AccessTokens.Issued token;
        try {
          token = db(() -> tokens.exchange(code, owner));
        } catch (CompletionException e) {
          if (e.getCause() instanceof IllegalArgumentException)
            throw new ApiError(401, "CODE_INVALID");
          throw e;
        }
        send(exchange, 201, token);
        return;
      }
      String owner = authenticate(exchange);
      rate("owner:" + owner, p.cfg.n("api", "requestsPerMinute"));
      String[] parts = path.substring("/api/v1".length()).split("/");
      if (path.equals("/api/v1/profile") && method.equals("GET")) {
        send(
            exchange,
            200,
            db(() -> p.db.one("SELECT id,name,balance,income FROM accounts WHERE id=?", owner)));
        return;
      }
      if (path.equals("/api/v1/shops") && method.equals("GET")) {
        send(exchange, 200, db(() -> p.db.rows("SELECT * FROM shops WHERE owner=?", owner)));
        return;
      }
      if (path.equals("/api/v1/income") && method.equals("GET")) {
        send(
            exchange,
            200,
            db(
                () ->
                    p.db.rows(
                        "SELECT delta,reason,at,operation FROM ledger WHERE account=? AND delta>0"
                            + " ORDER BY id DESC LIMIT 200",
                        owner)));
        return;
      }
      if ((path.equals("/api/v1/tokens") || path.equals("/api/v1/tokens/current"))
          && method.equals("DELETE")) {
        String digest =
            AccessTokens.hash(exchange.getRequestHeaders().getFirst("Authorization").substring(7));
        db(
            () -> {
              if (path.endsWith("/current"))
                p.db.exec("DELETE FROM credentials WHERE hash=? AND owner=?", digest, owner);
              else p.db.exec("DELETE FROM credentials WHERE owner=?", owner);
              return null;
            });
        send(exchange, 204, null);
        return;
      }
      if (parts.length >= 3 && parts[1].equals("shops")) {
        String id = uuid(parts[2]);
        JsonObject snapshot =
            db(
                () ->
                    p.db.atomic(
                        () ->
                            obj(
                                "shop",
                                p.market.ownShop(owner, id),
                                "offers",
                                p.db.rows("SELECT * FROM offers WHERE shop=?", id))));
        JsonObject shop = snapshot.getAsJsonObject("shop");
        exchange.getResponseHeaders().set("ETag", "\"" + n(shop, "version") + "\"");
        if (parts.length == 3 && method.equals("GET")) {
          send(exchange, 200, snapshot);
          return;
        }
        if (parts.length == 3 && method.equals("PUT")) {
          long version = version(exchange);
          var body = body(exchange, Set.of("title"));
          String title = s(body, "title");
          if (title.isBlank() || title.length() > 60) throw new ApiError(422, "INVALID_TITLE");
          db(
              () ->
                  p.db.atomic(
                      () -> {
                        p.market.ownShop(owner, id);
                        p.market.checkShopIdle(id);
                        p.market.version(id, version);
                        p.db.exec("UPDATE shops SET title=? WHERE id=?", title, id);
                        return null;
                      }));
          updated(exchange, version + 1);
          return;
        }
        if (parts.length == 4 && parts[3].equals("layout")) {
          if (method.equals("GET")) {
            send(exchange, 200, JsonParser.parseString(s(shop, "layout")));
            return;
          }
          if (method.equals("PUT")) {
            long version = version(exchange);
            var layout = body(exchange, Set.of("mode", "pages"));
            main(
                () -> {
                  p.validateIcons(layout);
                  return true;
                });
            db(
                () -> {
                  p.market.layout(owner, id, version, layout);
                  return null;
                });
            updated(exchange, version + 1);
            return;
          }
        }
        if (parts.length == 4 && parts[3].equals("stock") && method.equals("GET")) {
          var offers = db(() -> p.db.rows("SELECT * FROM offers WHERE shop=?", id));
          var stock =
              main(
                  () -> {
                    var result = new JsonArray();
                    try {
                      var inventory = p.physical.chest(shop).getBlockInventory();
                      for (var offer : offers)
                        result.add(
                            obj(
                                "offerId",
                                s(offer, "id"),
                                "quantity",
                                Items.count(inventory, Items.decode(s(offer, "item")))));
                      return obj("loaded", true, "offers", result, "observedAt", now());
                    } catch (IllegalArgumentException e) {
                      return obj("loaded", false, "offers", result, "observedAt", now());
                    }
                  });
          send(exchange, 200, stock);
          return;
        }
        if (parts.length == 4 && parts[3].equals("income") && method.equals("GET")) {
          send(
              exchange,
              200,
              db(
                  () ->
                      p.db.rows(
                          "SELECT l.delta,l.at,l.operation FROM ledger l JOIN operations o ON"
                              + " o.id=l.operation WHERE l.account=? AND l.reason='CHEST_SALE' AND"
                              + " json_extract(o.payload,'$.shop')=? ORDER BY l.id DESC LIMIT 200",
                          owner,
                          id)));
          return;
        }
        if (parts.length == 4 && parts[3].equals("offers") && method.equals("POST")
            || parts.length == 5 && parts[3].equals("offers") && method.equals("PUT")) {
          long version = version(exchange);
          JsonObject body =
              body(
                  exchange,
                  Set.of("material", "templateOfferId", "quantity", "priceMinor", "visible"));
          String offer = parts.length == 5 ? uuid(parts[4]) : UUID.randomUUID().toString();
          String item, material;
          if (body.has("templateOfferId")) {
            String template = uuid(s(body, "templateOfferId"));
            var row =
                db(
                    () ->
                        p.db.one(
                            "SELECT o.item,o.material FROM offers o JOIN shops s ON s.id=o.shop"
                                + " WHERE o.id=? AND s.owner=?",
                            template,
                            owner));
            item = s(row, "item");
            material = s(row, "material");
          } else {
            material = s(body, "material");
            item = p.standardItems.get(material);
            if (item == null) throw new ApiError(422, "ITEM_NOT_TRADABLE");
          }
          int quantity = Math.toIntExact(integer(body, "quantity"));
          long price = integer(body, "priceMinor");
          if (!body.has("visible")
              || !body.get("visible").isJsonPrimitive()
              || !body.getAsJsonPrimitive("visible").isBoolean())
            throw new ApiError(422, "INVALID_VISIBLE");
          if (body.has("material") == body.has("templateOfferId"))
            throw new ApiError(422, "EXACTLY_ONE_TEMPLATE_REQUIRED");
          boolean visible = body.get("visible").getAsBoolean();
          String finalItem = item, finalMaterial = material;
          db(
              () -> {
                p.market.updateOffer(
                    owner, id, version, offer, finalItem, finalMaterial, quantity, price, visible);
                return null;
              });
          updated(exchange, version + 1);
          return;
        }
        if (parts.length == 5 && parts[3].equals("offers") && method.equals("DELETE")) {
          long version = version(exchange);
          String offer = uuid(parts[4]);
          db(
              () -> {
                p.market.removeOffer(owner, id, offer, version);
                return null;
              });
          updated(exchange, version + 1);
          return;
        }
      }
      throw new ApiError(404, "NOT_FOUND");
    } catch (Throwable error) {
      while (error instanceof CompletionException || error instanceof ExecutionException)
        error = error.getCause();
      int status = 500;
      String code = "INTERNAL_ERROR", message = "Interner Fehler";
      if (error instanceof ApiError e) {
        status = e.status;
        code = e.code;
        message = e.code;
      } else if (error instanceof IllegalArgumentException
          || error instanceof ArithmeticException
          || error instanceof NullPointerException
          || error instanceof ClassCastException
          || error instanceof UnsupportedOperationException
          || error instanceof IllegalStateException
              && error.getCause() instanceof com.google.gson.JsonParseException) {
        status = "VERSION_CONFLICT".equals(error.getMessage()) ? 409 : 422;
        code = status == 409 ? "VERSION_CONFLICT" : "VALIDATION_ERROR";
        message = Objects.toString(error.getMessage(), "Invalid Request");
      } else
        p.getLogger()
            .warning("HTTP-API: interner Fehler vom Typ " + error.getClass().getSimpleName());
      try {
        if (status == 429) exchange.getResponseHeaders().set("Retry-After", "60");
        send(exchange, status, obj("error", code, "message", message));
      } catch (IOException ignored) {
      }
    } finally {
      exchange.close();
    }
  }

  private void updated(HttpExchange exchange, long version) throws IOException {
    exchange.getResponseHeaders().set("ETag", "\"" + version + "\"");
    send(exchange, 200, obj("version", version));
  }

  private static String uuid(String value) {
    return UUID.fromString(value).toString();
  }

  private static long integer(JsonObject object, String key) {
    if (!object.has(key)
        || !object.get(key).isJsonPrimitive()
        || !object.getAsJsonPrimitive(key).isNumber()) throw new ApiError(422, "INTEGER_REQUIRED");
    return object.get(key).getAsBigDecimal().longValueExact();
  }

  private static long version(HttpExchange exchange) {
    String header = exchange.getRequestHeaders().getFirst("If-Match");
    if (header == null) throw new ApiError(428, "IF_MATCH_REQUIRED");
    if (!header.matches("\"[1-9][0-9]{0,17}\"")) throw new ApiError(400, "IF_MATCH_INVALID");
    return Long.parseLong(header.substring(1, header.length() - 1));
  }

  private static JsonObject body(HttpExchange exchange, Set<String> allowed) throws IOException {
    String type = exchange.getRequestHeaders().getFirst("Content-Type");
    if (type == null || !type.toLowerCase(Locale.ROOT).matches("application/json(\\s*;.*)?"))
      throw new ApiError(415, "JSON_REQUIRED");
    byte[] bytes = exchange.getRequestBody().readNBytes(32769);
    if (bytes.length > 32768) throw new ApiError(413, "BODY_TOO_LARGE");
    JsonObject body;
    try {
      body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (RuntimeException e) {
      throw new ApiError(400, "INVALID_JSON");
    }
    for (String key : body.keySet())
      if (!allowed.contains(key)) throw new ApiError(422, "UNKNOWN_FIELD");
    return body;
  }

  private static void send(HttpExchange exchange, int status, Object value) throws IOException {
    if (status == 204) {
      exchange.sendResponseHeaders(status, -1);
      return;
    }
    byte[] data = Settings.JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, data.length);
    exchange.getResponseBody().write(data);
  }

  @Override
  public void close() {
    if (server != null) server.stop(1);
    pool.shutdownNow();
  }
}
