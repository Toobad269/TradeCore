package de.marktwerk;

import java.util.*;
import java.util.function.Consumer;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.*;
import org.geysermc.floodgate.api.FloodgateApi;

/** Loaded only if Floodgate is installed; all response callbacks return to the Bukkit thread. */
public final class Bedrock {
  private final MarktWerk plugin;

  public Bedrock(MarktWerk plugin) {
    this.plugin = plugin;
  }

  public boolean isBedrock(Player player) {
    return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
  }

  public void page(Player p, String title, List<Menus.Button> buttons) {
    var builder = SimpleForm.builder().title(title);
    buttons.forEach(b -> builder.button(b.label()));
    builder.validResultHandler(
        r ->
            plugin.main(
                () -> {
                  int i = r.clickedButtonId();
                  if (i >= 0 && i < buttons.size()) plugin.ui.invoke(p, buttons.get(i).action());
                }));
    FloodgateApi.getInstance().sendForm(p.getUniqueId(), builder.build());
  }

  public void input(Player p, String title, String initial, Consumer<String> callback) {
    var form =
        CustomForm.builder()
            .title(title)
            .input(title, "", initial)
            .validResultHandler(
                r -> plugin.main(() -> plugin.ui.invoke(p, () -> callback.accept(r.asInput()))))
            .build();
    FloodgateApi.getInstance().sendForm(p.getUniqueId(), form);
  }
}
