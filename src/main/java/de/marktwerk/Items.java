package de.marktwerk;

import static de.marktwerk.Store.*;

import com.google.gson.*;
import java.security.MessageDigest;
import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.*;

public final class Items {
  private Items() {}

  public static String encode(ItemStack item) {
    var unit = item.clone();
    unit.setAmount(1);
    return obj(
            "data",
            Base64.getEncoder().encodeToString(unit.serializeAsBytes()),
            "amount",
            item.getAmount())
        .toString();
  }

  public static ItemStack decode(String item) {
    var data = JsonParser.parseString(item).getAsJsonObject();
    var stack = ItemStack.deserializeBytes(Base64.getDecoder().decode(s(data, "data")));
    int amount = (int) n(data, "amount");
    if (amount < 1 || amount > 4096) throw new IllegalArgumentException("Invalid Item Quantity");
    stack.setAmount(amount);
    return stack;
  }

  public static String describe(ItemStack item) {
    var meta = item.getItemMeta();
    String text = item.getType().name();
    if (meta.hasDisplayName()) text += " · " + meta.getDisplayName();
    if (!item.getEnchantments().isEmpty()) text += " · " + item.getEnchantments();
    if (meta instanceof Damageable damage && damage.hasDamage())
      text += " · Schaden " + damage.getDamage();
    return text;
  }

  public static boolean allowed(ItemStack item) {
    if (item == null || item.getType().isAir() || !item.getType().isItem() || item.getAmount() < 1)
      return false;
    String name = item.getType().name();
    if (name.contains("COMMAND")
        || name.contains("STRUCTURE")
        || name.endsWith("SPAWN_EGG")
        || Set.of(
                "BARRIER",
                "LIGHT",
                "DEBUG_STICK",
                "JIGSAW",
                "BEDROCK",
                "END_PORTAL_FRAME",
                "SPAWNER",
                "TRIAL_SPAWNER",
                "VAULT",
                "KNOWLEDGE_BOOK",
                "BUNDLE")
            .contains(name)
        || name.endsWith("_BUNDLE")) return false;
    // Containers are excluded to prevent selling hidden protected items or nested inventories.
    if (item.getItemMeta() instanceof BlockStateMeta) return false;
    return item.getItemMeta().getPersistentDataContainer().getKeys().isEmpty();
  }

  public static void serverAllowed(ItemStack item) {
    if (!allowed(item) || !item.isSimilar(new ItemStack(item.getType())))
      throw new IllegalArgumentException("Server Shop Accepts Unmodified Vanilla Items Only");
  }

  public static int count(Inventory inv, ItemStack template) {
    int n = 0;
    for (var item : inv.getStorageContents())
      if (item != null && item.isSimilar(template)) n = Math.addExact(n, item.getAmount());
    return n;
  }

  public static ItemStack[] afterRemove(Inventory inv, ItemStack template, int amount) {
    var after = copy(inv.getStorageContents());
    for (int i = 0; i < after.length && amount > 0; i++) {
      var item = after[i];
      if (item == null || !item.isSimilar(template)) continue;
      int take = Math.min(item.getAmount(), amount);
      item.setAmount(item.getAmount() - take);
      amount -= take;
      if (item.getAmount() == 0) after[i] = null;
    }
    if (amount > 0) throw new IllegalArgumentException("Not Enough Matching Stock");
    return after;
  }

  public static ItemStack[] afterAdd(Inventory inv, ItemStack template) {
    var after = copy(inv.getStorageContents());
    int remaining = template.getAmount();
    for (int i = 0; i < after.length && remaining > 0; i++) {
      var item = after[i];
      if (item != null && item.isSimilar(template)) {
        int add =
            Math.min(
                remaining,
                Math.max(
                    0, Math.min(item.getMaxStackSize(), inv.getMaxStackSize()) - item.getAmount()));
        item.setAmount(item.getAmount() + add);
        remaining -= add;
      }
    }
    for (int i = 0; i < after.length && remaining > 0; i++)
      if (after[i] == null || after[i].getType().isAir()) {
        after[i] = template.clone();
        int add = Math.min(remaining, Math.min(template.getMaxStackSize(), inv.getMaxStackSize()));
        after[i].setAmount(add);
        remaining -= add;
      }
    if (remaining > 0) throw new IllegalArgumentException("Inventar voll");
    return after;
  }

  public static ItemStack[] copy(ItemStack[] a) {
    var b = new ItemStack[a.length];
    for (int i = 0; i < a.length; i++) b[i] = a[i] == null ? null : a[i].clone();
    return b;
  }

  public static String fingerprint(ItemStack[] items) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (int i = 0; i < items.length; i++) {
        digest.update((byte) i);
        if (items[i] != null) digest.update(items[i].serializeAsBytes());
        digest.update((byte) 255);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static JsonObject evidence(Inventory inv, ItemStack[] after) {
    var before = inv.getStorageContents();
    return obj(
        "beforeHash",
        fingerprint(before),
        "afterHash",
        fingerprint(after),
        "before",
        Arrays.stream(before).map(i -> i == null ? "" : encode(i)).toList(),
        "after",
        Arrays.stream(after).map(i -> i == null ? "" : encode(i)).toList());
  }
}
