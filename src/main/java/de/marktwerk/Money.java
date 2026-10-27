package de.marktwerk;

import java.math.*;

public final class Money {
  public static final long MAX = 9_000_000_000_000_000L;

  private Money() {}

  public static long valid(long n) {
    if (n < 0 || n > MAX)
      throw new IllegalArgumentException("Amount outside the allowed range");
    return n;
  }

  public static long positive(long n) {
    valid(n);
    if (n == 0) throw new IllegalArgumentException("Betrag muss positiv sein");
    return n;
  }

  public static long add(long a, long b) {
    return valid(Math.addExact(valid(a), valid(b)));
  }

  public static long times(long a, long b) {
    return valid(Math.multiplyExact(valid(a), valid(b)));
  }

  public static long parse(String s) {
    return valid(
        new BigDecimal(s.replace(',', '.'))
            .setScale(2, RoundingMode.UNNECESSARY)
            .movePointRight(2)
            .longValueExact());
  }

  public static long fromVault(double d) {
    if (!Double.isFinite(d)) throw new IllegalArgumentException("Invalid Amount");
    return parse(BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP).toPlainString());
  }

  public static String format(long n) {
    return BigDecimal.valueOf(n, 2).toPlainString();
  }

  public static long ratio(long n, long numerator, long denominator) {
    if (denominator <= 0) throw new IllegalArgumentException();
    return valid(
        BigInteger.valueOf(valid(n))
            .multiply(BigInteger.valueOf(valid(numerator)))
            .divide(BigInteger.valueOf(denominator))
            .longValueExact());
  }
}
