/*
 * This file is part of adventure-platform, licensed under the MIT License.
 *
 * Copyright (c) 2018-2020 KyoriPowered
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.kyori.adventure.platform.bungeecord;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.platform.AudienceIdentity;
import net.kyori.adventure.platform.facet.FacetAudienceProvider;
import net.kyori.adventure.platform.facet.Knob;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.renderer.ComponentRenderer;
import net.kyori.adventure.text.serializer.bungeecord.BungeeComponentSerializer;
import net.kyori.adventure.translation.GlobalTranslator;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;

import static java.util.Objects.requireNonNull;
import static net.kyori.adventure.platform.facet.Knob.logError;

final class BungeeAudiencesImpl extends FacetAudienceProvider<CommandSender, BungeeAudience> implements BungeeAudiences {

  static {
    Knob.OUT = message -> ProxyServer.getInstance().getLogger().log(Level.INFO, message);
    Knob.ERR = (message, error) -> ProxyServer.getInstance().getLogger().log(Level.WARNING, message, error);

    // Inject our adapter component into Bungee's Gson instance
    // (this is separate from the ComponentSerializer Gson instance)
    // Please, just use Velocity!
    try {
      final Field gsonField = ProxyServer.getInstance().getClass().getDeclaredField("gson");
      gsonField.setAccessible(true);
      final Gson gson = (Gson) gsonField.get(ProxyServer.getInstance());
      BungeeComponentSerializer.inject(gson);
    } catch(final Throwable error) {
      logError(error, "Failed to inject ProxyServer gson");
    }
  }

  private static final Map<String, BungeeAudiences> INSTANCES = Collections.synchronizedMap(new HashMap<>(4));

  static @NonNull BungeeAudiences instanceFor(final @NonNull Plugin plugin) {
    return builder(plugin).build();
  }

  static @NonNull Builder builder(final @NonNull Plugin plugin) {
    return new Builder(plugin);
  }

  private final Plugin plugin;
  private final Listener listener;

  BungeeAudiencesImpl(final Plugin plugin, final @NonNull ComponentRenderer<AudienceIdentity> componentRenderer, final @NonNull ToIntFunction<AudienceIdentity> partitionFunction) {
    super(componentRenderer, partitionFunction);
    this.plugin = requireNonNull(plugin, "plugin");
    this.listener = new Listener();
    this.plugin.getProxy().getPluginManager().registerListener(this.plugin, this.listener);

    final CommandSender console = this.plugin.getProxy().getConsole();
    this.addViewer(console);

    for(final ProxiedPlayer player : this.plugin.getProxy().getPlayers()) {
      this.addViewer(player);
    }
  }

  @NonNull
  @Override
  public Audience sender(final @NonNull CommandSender sender) {
    if(sender instanceof ProxiedPlayer) {
      return this.player((ProxiedPlayer) sender);
    } else if(ProxyServer.getInstance().getConsole().equals(sender)) {
      return this.console();
    }
    return this.createAudience(Collections.singletonList(sender));
  }

  @NonNull
  @Override
  public Audience player(final @NonNull ProxiedPlayer player) {
    return this.player(player.getUniqueId());
  }

  @Override
  protected @NonNull AudienceIdentity createIdentity(final @NonNull CommandSender viewer) {
    return new BungeeIdentity(viewer);
  }

  @NonNull
  @Override
  protected BungeeAudience createAudience(final @NonNull Collection<CommandSender> viewers) {
    return new BungeeAudience(this, viewers);
  }

  @Override
  public void close() {
    this.plugin.getProxy().getPluginManager().unregisterListener(this.listener);
    super.close();
  }

  static final class Builder implements BungeeAudiences.Builder {
    private final @NonNull Plugin plugin;
    private @MonotonicNonNull ComponentRenderer<AudienceIdentity> componentRenderer;
    private @MonotonicNonNull ToIntFunction<AudienceIdentity> partitionFunction;

    Builder(final @NonNull Plugin plugin) {
      this.plugin = requireNonNull(plugin, "plugin");
      this.componentRenderer(new ComponentRenderer<AudienceIdentity>() {
        @Override
        public @NonNull Component render(final @NonNull Component component, final @NonNull AudienceIdentity context) {
          return GlobalTranslator.render(component, context.locale());
        }
      });
      this.partitionBy(context -> context.locale().hashCode());
    }

    @Override
    public @NonNull Builder componentRenderer(final @NonNull ComponentRenderer<AudienceIdentity> componentRenderer) {
      this.componentRenderer = requireNonNull(componentRenderer, "component renderer");
      return this;
    }

    @Override
    public @NonNull Builder partitionBy(final @NonNull ToIntFunction<AudienceIdentity> partitionFunction) {
      this.partitionFunction = requireNonNull(partitionFunction, "partition function");
      return this;
    }

    @Override
    public @NonNull BungeeAudiences build() {
      return INSTANCES.computeIfAbsent(this.plugin.getDescription().getName(), name -> new BungeeAudiencesImpl(this.plugin, this.componentRenderer, this.partitionFunction));
    }
  }

  public final class Listener implements net.md_5.bungee.api.plugin.Listener {
    @EventHandler(priority = Byte.MIN_VALUE /* before EventPriority.LOWEST */)
    public void onLogin(final PostLoginEvent event) {
      BungeeAudiencesImpl.this.addViewer(event.getPlayer());
    }

    @EventHandler(priority = Byte.MAX_VALUE /* after EventPriority.HIGHEST */)
    public void onDisconnect(final PlayerDisconnectEvent event) {
      BungeeAudiencesImpl.this.removeViewer(event.getPlayer());
    }
  }
}
