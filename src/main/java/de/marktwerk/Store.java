package de.marktwerk;

import com.google.gson.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** All JDBC access is serialized on this dedicated executor, including Vault calls. */
public final class Store implements AutoCloseable {
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(r -> new Thread(r, "MarktWerk-SQLite"));
  private Connection connection;

  public <T> CompletableFuture<T> submit(Supplier<T> task) {
    return CompletableFuture.supplyAsync(task, worker);
  }

  public Store(Path path) {
    try {
      submit(
              () -> {
                try {
                  Class.forName("org.sqlite.JDBC");
                  connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                  try (var s = connection.createStatement()) {
                    s.execute("PRAGMA journal_mode=WAL");
                    s.execute("PRAGMA synchronous=FULL");
                    s.execute("PRAGMA foreign_keys=ON");
                    s.execute("PRAGMA busy_timeout=10000");
                  }
                  migrate();
                  return null;
                } catch (Exception e) {
                  if (connection != null)
                    try {
                      connection.close();
                    } catch (SQLException close) {
                      e.addSuppressed(close);
                    }
                  throw new IllegalStateException(e);
                }
              })
          .join();
    } catch (RuntimeException e) {
      worker.shutdownNow();
      throw e;
    }
  }

  private void migrate() {
    long version = number("PRAGMA user_version");
    if (version > 1) throw new IllegalStateException("Datenbank ist neuer als dieses Plugin");
    if (version == 1) return;
    atomic(
        () -> {
          for (String ddl :
              List.of(
                  "CREATE TABLE accounts(id TEXT PRIMARY KEY,name TEXT NOT NULL,balance INTEGER NOT"
                      + " NULL CHECK(balance>=0 AND balance<=9000000000000000),menu INTEGER NOT"
                      + " NULL DEFAULT 0,income INTEGER NOT NULL DEFAULT 0)",
                  "CREATE TABLE ledger(id INTEGER PRIMARY KEY AUTOINCREMENT,operation TEXT NOT"
                      + " NULL,account TEXT NOT NULL,delta INTEGER NOT NULL,reason TEXT NOT NULL,at"
                      + " INTEGER NOT NULL)",
                  "CREATE TABLE operations(id TEXT PRIMARY KEY,kind TEXT NOT NULL,owner TEXT NOT"
                      + " NULL,state TEXT NOT NULL,payload TEXT NOT NULL,at INTEGER NOT"
                      + " NULL,resolution TEXT)",
                  "CREATE TABLE stock(material TEXT PRIMARY KEY,quantity INTEGER NOT NULL"
                      + " CHECK(quantity>=0),bonus_carry TEXT NOT NULL DEFAULT '0/1')",
                  "CREATE TABLE suppliers(material TEXT NOT NULL,owner TEXT NOT NULL,units TEXT NOT"
                      + " NULL,carry TEXT NOT NULL,PRIMARY KEY(material,owner))",
                  "CREATE TABLE shops(id TEXT PRIMARY KEY,owner TEXT NOT NULL,world TEXT NOT NULL,x"
                      + " INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,title TEXT NOT"
                      + " NULL,version INTEGER NOT NULL DEFAULT 1,layout TEXT NOT"
                      + " NULL,UNIQUE(world,x,y,z))",
                  "CREATE TABLE offers(id TEXT PRIMARY KEY,shop TEXT NOT NULL REFERENCES shops(id)"
                      + " ON DELETE CASCADE,item TEXT NOT NULL,material TEXT NOT NULL,quantity"
                      + " INTEGER NOT NULL,price INTEGER NOT NULL CHECK(price>0),visible INTEGER"
                      + " NOT NULL)",
                  "CREATE TABLE auctions(id TEXT PRIMARY KEY,owner TEXT NOT NULL,item TEXT NOT"
                      + " NULL,material TEXT NOT NULL,price INTEGER NOT NULL,expires INTEGER NOT"
                      + " NULL,state TEXT NOT NULL)",
                  "CREATE TABLE mailbox(id TEXT PRIMARY KEY,owner TEXT NOT NULL,item TEXT NOT"
                      + " NULL,state TEXT NOT NULL DEFAULT 'READY',source TEXT NOT NULL UNIQUE)",
                  "CREATE TABLE votes(material TEXT NOT NULL,owner TEXT NOT NULL,round INTEGER NOT"
                      + " NULL,direction INTEGER NOT NULL,PRIMARY KEY(material,owner,round))",
                  "CREATE TABLE vote_results(material TEXT PRIMARY KEY,factor INTEGER NOT NULL"
                      + " DEFAULT 10000,round INTEGER NOT NULL DEFAULT -1,summary TEXT NOT NULL"
                      + " DEFAULT '')",
                  "CREATE TABLE credentials(hash TEXT PRIMARY KEY,owner TEXT NOT NULL,kind TEXT NOT"
                      + " NULL,expires INTEGER NOT NULL)",
                  "CREATE INDEX mailbox_owner ON mailbox(owner,state)",
                  "CREATE INDEX operation_state ON operations(state)",
                  "PRAGMA user_version=1")) exec(ddl);
          return null;
        });
  }

  public <T> T atomic(Supplier<T> task) {
    try {
      if (!connection.getAutoCommit()) return task.get();
      connection.setAutoCommit(false);
      try {
        T result = task.get();
        connection.commit();
        return result;
      } catch (Throwable e) {
        connection.rollback();
        throw e;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public int exec(String sql, Object... args) {
    try (var p = prepare(sql, args)) {
      return p.executeUpdate();
    } catch (SQLException e) {
      throw new IllegalStateException("Datenbankoperation fehlgeschlagen", e);
    }
  }

  private PreparedStatement prepare(String sql, Object... args) throws SQLException {
    var p = connection.prepareStatement(sql);
    for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
    return p;
  }

  public List<JsonObject> rows(String sql, Object... args) {
    try (var p = prepare(sql, args);
        var r = p.executeQuery()) {
      var list = new ArrayList<JsonObject>();
      var meta = r.getMetaData();
      while (r.next()) {
        var row = new JsonObject();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
          Object v = r.getObject(i);
          if (v == null) row.add(meta.getColumnLabel(i), JsonNull.INSTANCE);
          else if (v instanceof Number n) row.addProperty(meta.getColumnLabel(i), n);
          else row.addProperty(meta.getColumnLabel(i), v.toString());
        }
        list.add(row);
      }
      return list;
    } catch (SQLException e) {
      throw new IllegalStateException("Datenbankabfrage fehlgeschlagen", e);
    }
  }

  public JsonObject one(String sql, Object... args) {
    var rows = rows(sql, args);
    if (rows.isEmpty()) throw new IllegalArgumentException("Eintrag nicht gefunden");
    return rows.getFirst();
  }

  public long number(String sql, Object... args) {
    var rows = rows(sql, args);
    return rows.isEmpty() ? 0 : rows.getFirst().entrySet().iterator().next().getValue().getAsLong();
  }

  public static String s(JsonObject o, String k) {
    return o.get(k).getAsString();
  }

  public static long n(JsonObject o, String k) {
    return o.get(k).getAsLong();
  }

  public static JsonObject obj(Object... pairs) {
    var o = new JsonObject();
    for (int i = 0; i < pairs.length; i += 2)
      o.add(pairs[i].toString(), Settings.JSON.toJsonTree(pairs[i + 1]));
    return o;
  }

  public static long now() {
    return System.currentTimeMillis() / 1000;
  }

  public void account(String id, String name, long start) {
    atomic(
        () -> {
          Money.valid(start);
          if (exec("INSERT OR IGNORE INTO accounts(id,name,balance) VALUES(?,?,?)", id, name, start)
                  == 1
              && start > 0)
            exec(
                "INSERT INTO ledger(operation,account,delta,reason,at) VALUES(?,?,?,?,?)",
                UUID.randomUUID().toString(),
                id,
                start,
                "START",
                now());
          if (!name.isBlank()) exec("UPDATE accounts SET name=? WHERE id=?", name, id);
          return null;
        });
  }

  public long balance(String id) {
    return number("SELECT balance FROM accounts WHERE id=?", id);
  }

  public void change(String id, long delta, String operation, String reason) {
    var a = one("SELECT balance,income FROM accounts WHERE id=?", id);
    long value = Money.valid(Math.addExact(n(a, "balance"), delta));
    long income = Math.addExact(n(a, "income"), delta > 0 && !reason.equals("REFUND") ? delta : 0);
    exec("UPDATE accounts SET balance=?,income=? WHERE id=?", value, income, id);
    exec(
        "INSERT INTO ledger(operation,account,delta,reason,at) VALUES(?,?,?,?,?)",
        operation,
        id,
        delta,
        reason,
        now());
  }

  public boolean menu(String id) {
    return number("SELECT menu FROM accounts WHERE id=?", id) == 1;
  }

  public void setMenu(String id, boolean value) {
    exec("UPDATE accounts SET menu=? WHERE id=?", value ? 1 : 0, id);
    if (!value) exec("DELETE FROM credentials WHERE owner=?", id);
  }

  public void transfer(String from, String to, long amount, String operation) {
    atomic(
        () -> {
          Money.positive(amount);
          if (from.equals(to))
            throw new IllegalArgumentException("You Cannot Transfer Money to Yourself");
          if (number("SELECT COUNT(*) FROM operations WHERE id=?", operation) > 0) return null;
          change(from, -amount, operation, "TRANSFER");
          change(to, amount, operation, "TRANSFER");
          operation(operation, "TRANSFER", from, "COMMITTED", obj("to", to, "amount", amount));
          return null;
        });
  }

  public void operation(String id, String kind, String owner, String state, JsonObject data) {
    exec(
        "INSERT INTO operations(id,kind,owner,state,payload,at) VALUES(?,?,?,?,?,?)",
        id,
        kind,
        owner,
        state,
        Settings.JSON.toJson(data),
        now());
  }

  public boolean pending(String owner) {
    return number("SELECT COUNT(*) FROM operations WHERE owner=? AND state='PREPARED'", owner) > 0;
  }

  public void mail(String owner, String item, String source) {
    exec(
        "INSERT INTO mailbox(id,owner,item,source) VALUES(?,?,?,?)",
        UUID.randomUUID().toString(),
        owner,
        item,
        source);
  }

  public void backup(Path destination) {
    exec("VACUUM INTO ?", destination.toAbsolutePath().toString());
  }

  @Override
  public void close() {
    submit(
            () -> {
              try {
                connection.close();
                return null;
              } catch (SQLException e) {
                throw new IllegalStateException(e);
              }
            })
        .join();
    worker.shutdown();
  }
}
