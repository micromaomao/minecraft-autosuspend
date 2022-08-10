package org.maowtm.mc.auto_suspend;

import org.maowtm.mc.auto_suspend.ServerStateManager.WebhookEvent;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.ProxyPingEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerConnectEvent.Reason;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

public class Events implements Listener {

  // I heard that BungeeCord is thread-safe
  // https://www.spigotmc.org/threads/scheduling-runnable-in-main-thread.254168/

  private AutoSuspend plugin;

  public Events(AutoSuspend plugin) {
    this.plugin = plugin;
  }

  private ServerStateManager getServerState() {
    return this.plugin.getStateManager();
  }

  @EventHandler
  public void onServerConnect(ServerConnectEvent evt) {
    final var player = evt.getPlayer();
    final var target = evt.getTarget();
    final var serverState = getServerState();
    if (!target.getName().equals(serverState.getTargetServer())) {
      return;
    }
    if (evt.getReason() != Reason.JOIN_PROXY) {
      // Don't handle event caused by our own .connects
      return;
    }
    serverState.updatePlayerCount(this.plugin.getProxy().getOnlineCount());
    switch (serverState.getState()) {
      case NOT_READY:
        evt.setCancelled(true);
        player.disconnect(new ComponentBuilder().color(ChatColor.RED).append("Server is not ready.").create());
        break;
      case SUSPENDED:
        evt.setCancelled(true);
        serverState.enqueue(player);
        break;
      case RUNNING:
        serverState.webhookNotify(WebhookEvent.JOINED_WHILE_RUNNING, player);
        break;
    }
  }

  @EventHandler
  public void onDisconnect(PlayerDisconnectEvent evt) {
    this.getServerState().updatePlayerCount(this.plugin.getProxy().getOnlineCount() - 1);
    this.getServerState().webhookNotify(WebhookEvent.LEFT, evt.getPlayer());
  }

  @EventHandler
  public void onPing(ProxyPingEvent evt) {
    var res = evt.getResponse();
    switch (this.getServerState().getState()) {
      case NOT_READY:
        res.setDescriptionComponent(
            new TextComponent(new ComponentBuilder().color(ChatColor.RED).append("Server not ready :(").create()));
        res.getPlayers().setMax(0);
        break;
      case SUSPENDED:
        var current_desc = res.getDescriptionComponent();
        var n = new ComponentBuilder().append("(sleeping) ").append(current_desc).create();
        res.setDescriptionComponent(new TextComponent(n));
        break;
      case RUNNING:
        break;
    }
    evt.setResponse(res);
  }
}
