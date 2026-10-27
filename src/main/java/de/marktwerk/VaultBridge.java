package de.marktwerk;

import static de.marktwerk.Store.*;

import java.util.*;
import net.milkbowl.vault.economy.*;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

/** Vault is synchronous. JDBC still runs on the DB worker; callers wait for its atomic result. */
public final class VaultBridge implements Economy {
  private final MarktWerk p;

  public VaultBridge(MarktWerk p) {
    this.p = p;
  }

  public void register() {
    p.getServer().getServicesManager().register(Economy.class, this, p, ServicePriority.Highest);
  }

  public boolean isEnabled() {
    return p.isEnabled();
  }

  public String getName() {
    return "TradeCore";
  }

  public boolean hasBankSupport() {
    return false;
  }

  public int fractionalDigits() {
    return 2;
  }

  public String format(double amount) {
    return Money.format(Money.fromVault(amount)) + " " + currencyNamePlural();
  }

  public String currencyNamePlural() {
    return p.cfg.text("currency", "plural");
  }

  public String currencyNameSingular() {
    return p.cfg.text("currency", "singular");
  }

  private String id(String name) {
    return p.db
        .submit(
            () -> {
              var rows =
                  p.db.rows(
                      "SELECT id FROM accounts WHERE name=? COLLATE NOCASE AND id<>?",
                      name,
                      Market.SERVER);
              return rows.size() == 1 ? s(rows.getFirst(), "id") : null;
            })
        .join();
  }

  private double balance(String id) {
    return id == null ? 0 : p.db.submit(() -> p.db.balance(id)).join() / 100.0;
  }

  private boolean exists(String id) {
    return id != null
        && p.db
            .submit(() -> p.db.number("SELECT COUNT(*) FROM accounts WHERE id=?", id) > 0)
            .join();
  }

  private boolean create(String id, String name) {
    if (id == null) return false;
    return p.db
        .submit(
            () ->
                p.db.atomic(
                    () -> {
                      p.db.account(id, name, p.cfg.n("currency", "startingMinor"));
                      return true;
                    }))
        .join();
  }

  private EconomyResponse move(String id, double amount, boolean deposit) {
    try {
      if (id == null) throw new IllegalArgumentException("Unbekanntes Konto");
      long minor = Money.fromVault(amount);
      return p.db
          .submit(
              () ->
                  p.db.atomic(
                      () -> {
                        p.db.change(
                            id,
                            deposit ? minor : -minor,
                            UUID.randomUUID().toString(),
                            deposit ? "VAULT_DEPOSIT" : "VAULT_WITHDRAW");
                        return new EconomyResponse(
                            minor / 100.0,
                            p.db.balance(id) / 100.0,
                            EconomyResponse.ResponseType.SUCCESS,
                            "");
                      }))
          .join();
    } catch (RuntimeException e) {
      return new EconomyResponse(
          0,
          balance(id),
          EconomyResponse.ResponseType.FAILURE,
          "Invalid amount, unknown account, or insufficient funds");
    }
  }

  private EconomyResponse unsupported() {
    return new EconomyResponse(
        0,
        0,
        EconomyResponse.ResponseType.NOT_IMPLEMENTED,
        "Bankkonten sind nicht Teil dieses Plugins");
  }

  public boolean hasAccount(String name) {
    return exists(id(name));
  }

  public double getBalance(String name) {
    return balance(id(name));
  }

  public boolean has(String name, double amount) {
    try {
      String account = id(name);
      return account != null
          && p.db.submit(() -> p.db.balance(account) >= Money.fromVault(amount)).join();
    } catch (RuntimeException e) {
      return false;
    }
  }

  public EconomyResponse depositPlayer(String name, double amount) {
    return move(id(name), amount, true);
  }

  public EconomyResponse withdrawPlayer(String name, double amount) {
    return move(id(name), amount, false);
  }

  public boolean createPlayerAccount(String name) {
    return exists(id(name));
  }

  public boolean hasAccount(String name, String world) {
    return exists(id(name));
  }

  public double getBalance(String name, String world) {
    return balance(id(name));
  }

  public boolean has(String name, String world, double amount) {
    try {
      String account = id(name);
      return account != null
          && p.db.submit(() -> p.db.balance(account) >= Money.fromVault(amount)).join();
    } catch (RuntimeException e) {
      return false;
    }
  }

  public EconomyResponse depositPlayer(String name, String world, double amount) {
    return move(id(name), amount, true);
  }

  public EconomyResponse withdrawPlayer(String name, String world, double amount) {
    return move(id(name), amount, false);
  }

  public boolean createPlayerAccount(String name, String world) {
    return exists(id(name));
  }

  public EconomyResponse createBank(String bank, String name) {
    return unsupported();
  }

  public EconomyResponse isBankOwner(String bank, String name) {
    return unsupported();
  }

  public EconomyResponse isBankMember(String bank, String name) {
    return unsupported();
  }

  public boolean hasAccount(OfflinePlayer player) {
    return exists(player.getUniqueId().toString());
  }

  public double getBalance(OfflinePlayer player) {
    return balance(player.getUniqueId().toString());
  }

  public boolean has(OfflinePlayer player, double amount) {
    try {
      return exists(player.getUniqueId().toString())
          && p.db
              .submit(
                  () -> p.db.balance(player.getUniqueId().toString()) >= Money.fromVault(amount))
              .join();
    } catch (RuntimeException e) {
      return false;
    }
  }

  public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
    return move(player.getUniqueId().toString(), amount, true);
  }

  public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
    return move(player.getUniqueId().toString(), amount, false);
  }

  public boolean createPlayerAccount(OfflinePlayer player) {
    return create(player.getUniqueId().toString(), Objects.toString(player.getName(), ""));
  }

  public boolean hasAccount(OfflinePlayer player, String world) {
    return exists(player.getUniqueId().toString());
  }

  public double getBalance(OfflinePlayer player, String world) {
    return balance(player.getUniqueId().toString());
  }

  public boolean has(OfflinePlayer player, String world, double amount) {
    try {
      return exists(player.getUniqueId().toString())
          && p.db
              .submit(
                  () -> p.db.balance(player.getUniqueId().toString()) >= Money.fromVault(amount))
              .join();
    } catch (RuntimeException e) {
      return false;
    }
  }

  public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
    return move(player.getUniqueId().toString(), amount, true);
  }

  public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
    return move(player.getUniqueId().toString(), amount, false);
  }

  public boolean createPlayerAccount(OfflinePlayer player, String world) {
    return create(player.getUniqueId().toString(), Objects.toString(player.getName(), ""));
  }

  public EconomyResponse createBank(String bank, OfflinePlayer player) {
    return unsupported();
  }

  public EconomyResponse isBankOwner(String bank, OfflinePlayer player) {
    return unsupported();
  }

  public EconomyResponse isBankMember(String bank, OfflinePlayer player) {
    return unsupported();
  }

  public EconomyResponse deleteBank(String bank) {
    return unsupported();
  }

  public EconomyResponse bankBalance(String bank) {
    return unsupported();
  }

  public EconomyResponse bankHas(String bank, double amount) {
    return unsupported();
  }

  public EconomyResponse bankWithdraw(String bank, double amount) {
    return unsupported();
  }

  public EconomyResponse bankDeposit(String bank, double amount) {
    return unsupported();
  }

  public List<String> getBanks() {
    return List.of();
  }
}
