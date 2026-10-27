package de.marktwerk;

import static de.marktwerk.Store.*;

import com.google.gson.*;
import java.util.*;

/** Server-defined action whitelist; no command or executable content. */
public final class Layouts {
  private Layouts() {}

  public static JsonObject empty() {
    return obj("mode", "SIMPLE", "pages", List.of());
  }

  public static void validate(JsonObject layout, List<JsonObject> offers) {
    only(layout, Set.of("mode", "pages"));
    String mode = s(layout, "mode");
    if (!Set.of("SIMPLE", "CUSTOM").contains(mode))
      throw new IllegalArgumentException("mode muss SIMPLE oder CUSTOM sein");
    var pages = layout.getAsJsonArray("pages");
    if (pages == null || pages.size() > 20 || mode.equals("CUSTOM") && pages.isEmpty())
      throw new IllegalArgumentException("CUSTOM requires 1..20 pages");
    var ids = new HashSet<String>();
    var offerIds = new HashSet<String>();
    offers.forEach(o -> offerIds.add(s(o, "id")));
    for (var p : pages) {
      var page = p.getAsJsonObject();
      only(page, Set.of("id", "title", "fields"));
      String id = s(page, "id");
      if (!id.matches("[a-zA-Z0-9_-]{1,40}") || !ids.add(id))
        throw new IllegalArgumentException("Invalid or duplicate page ID");
      label(page, "title");
      var fields = page.getAsJsonArray("fields");
      if (fields.size() > 45) throw new IllegalArgumentException("Maximal 45 Felder pro Seite");
      var slots = new HashSet<Long>();
      for (var f : fields) {
        var field = f.getAsJsonObject();
        only(field, Set.of("slot", "icon", "label", "action", "offerId", "targetPage"));
        long slot = field.get("slot").getAsBigDecimal().longValueExact();
        if (slot < 0 || slot > 44 || !slots.add(slot))
          throw new IllegalArgumentException("Invalid or duplicate field slot");
        label(field, "label");
        if (!s(field, "icon").matches("[A-Z0-9_]{1,80}"))
          throw new IllegalArgumentException("Invalid Icon");
        String action = s(field, "action");
        if (!Set.of("DECORATION", "BUY_OFFER", "OPEN_PAGE", "CLOSE", "HOME").contains(action))
          throw new IllegalArgumentException("Aktion nicht erlaubt");
        if (action.equals("BUY_OFFER") && !offerIds.contains(s(field, "offerId")))
          throw new IllegalArgumentException("Offer does not belong to this shop");
      }
    }
    for (var p : pages)
      for (var f : p.getAsJsonObject().getAsJsonArray("fields")) {
        var field = f.getAsJsonObject();
        if (s(field, "action").equals("OPEN_PAGE") && !ids.contains(s(field, "targetPage")))
          throw new IllegalArgumentException("Zielseite existiert nicht");
      }
  }

  private static void only(JsonObject o, Set<String> keys) {
    for (String k : o.keySet())
      if (!keys.contains(k)) throw new IllegalArgumentException("Unbekanntes Layout-Feld: " + k);
  }

  private static void label(JsonObject o, String k) {
    String v = s(o, k);
    if (v.isBlank() || v.length() > 80 || v.chars().anyMatch(c -> c < 32))
      throw new IllegalArgumentException("Invalid label: " + k);
  }
}
