package de.marktwerk;

import java.math.BigInteger;

public record Fraction(BigInteger n, BigInteger d) {
  public static final Fraction ZERO = of(0);

  public Fraction {
    if (d.signum() <= 0 || n.signum() < 0) throw new IllegalArgumentException("Invalid Fraction");
    var g = n.gcd(d);
    n = n.divide(g);
    d = d.divide(g);
  }

  public static Fraction of(long n) {
    return new Fraction(BigInteger.valueOf(n), BigInteger.ONE);
  }

  public static Fraction parse(String s) {
    var p = s.split("/");
    return new Fraction(new BigInteger(p[0]), new BigInteger(p[1]));
  }

  public Fraction add(Fraction f) {
    return new Fraction(n.multiply(f.d).add(f.n.multiply(d)), d.multiply(f.d));
  }

  public Fraction mul(long a, long b) {
    return new Fraction(n.multiply(BigInteger.valueOf(a)), d.multiply(BigInteger.valueOf(b)));
  }

  public Fraction mul(Fraction f) {
    return new Fraction(n.multiply(f.n), d.multiply(f.d));
  }

  public Fraction div(Fraction f) {
    return new Fraction(n.multiply(f.d), d.multiply(f.n));
  }

  public long floor() {
    return n.divide(d).longValueExact();
  }

  public Fraction rest() {
    return new Fraction(n.remainder(d), d);
  }

  public String toString() {
    return n + "/" + d;
  }
}
