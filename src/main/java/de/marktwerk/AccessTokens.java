package de.marktwerk;

import static de.marktwerk.Store.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** High-entropy, one-time linking and revocable access tokens; runs solely on Store's worker. */
public final class AccessTokens {
  public record Issued(String accessToken, String tokenType, long expiresAt) {}

  private final Store db;
  private final Settings cfg;
  private final SecureRandom random = new SecureRandom();

  public AccessTokens(Store db, Settings cfg) {
    this.db = db;
    this.cfg = cfg;
  }

  private String secret(int bytes) {
    byte[] value = new byte[bytes];
    random.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public String issue(String owner) {
    return db.atomic(
        () -> {
          String code = secret(10);
          db.exec("DELETE FROM credentials WHERE owner=? AND kind='CODE'", owner);
          db.exec(
              "INSERT INTO credentials VALUES(?,?,?,?)",
              hash(code),
              owner,
              "CODE",
              now() + cfg.n("api", "codeSeconds"));
          return code;
        });
  }

  public String codeOwner(String code) {
    return credentialOwner(code, "CODE");
  }

  public String owner(String token) {
    return credentialOwner(token, "TOKEN");
  }

  private String credentialOwner(String value, String kind) {
    var rows =
        db.rows(
            "SELECT owner FROM credentials WHERE hash=? AND kind=? AND expires>?",
            hash(value),
            kind,
            now());
    if (rows.isEmpty())
      throw new IllegalArgumentException(kind.equals("CODE") ? "CODE_INVALID" : "TOKEN_INVALID");
    return s(rows.getFirst(), "owner");
  }

  /** Permission must have been checked for expectedOwner immediately before this call. */
  public Issued exchange(String code, String expectedOwner) {
    return db.atomic(
        () -> {
          if (!codeOwner(code).equals(expectedOwner))
            throw new IllegalArgumentException("CODE_INVALID");
          if (db.exec(
                  "DELETE FROM credentials WHERE hash=? AND kind='CODE' AND owner=? AND expires>?",
                  hash(code),
                  expectedOwner,
                  now())
              != 1) throw new IllegalArgumentException("CODE_INVALID");
          String token = secret(32);
          long expires = now() + cfg.n("api", "tokenSeconds");
          db.exec(
              "INSERT INTO credentials VALUES(?,?,?,?)",
              hash(token),
              expectedOwner,
              "TOKEN",
              expires);
          return new Issued(token, "Bearer", expires);
        });
  }

  public void revokeAll(String owner) {
    db.exec("DELETE FROM credentials WHERE owner=?", owner);
  }

  public void revoke(String owner, String token) {
    db.exec("DELETE FROM credentials WHERE owner=? AND hash=?", owner, hash(token));
  }
}
