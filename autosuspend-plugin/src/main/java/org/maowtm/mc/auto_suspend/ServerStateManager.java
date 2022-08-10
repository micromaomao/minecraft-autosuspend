package org.maowtm.mc.auto_suspend;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ServerConnectRequest;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.ServerConnectEvent.Reason;

public class ServerStateManager implements Runnable {
  private final Lock l = new ReentrantLock();
  private final AutoSuspend plugin;
  private final String targetServer;
  private final ServerInfo serverInfo;
  private volatile boolean stopped = false;
  private ArrayList<ProxiedPlayer> queue = new ArrayList<>();
  private Instant lastPlayerActive = Instant.now();
  private int lastPlayerCount = 0;
  private final ServerController controller;
  private Duration statusCheckInterval;
  private Instant lastStatusCheck = Instant.now();
  private Instant keepAliveUntil = null;
  private HttpClient webhookClient;

  public static enum State {
    NOT_READY,
    RUNNING,
    SUSPENDED
  }

  private State state = State.NOT_READY;

  public ServerStateManager(AutoSuspend plugin, String targetServer, ServerController controller) {
    this.plugin = plugin;
    this.targetServer = targetServer;
    this.serverInfo = plugin.getProxy().getServerInfo(targetServer);
    this.controller = controller;
    this.statusCheckInterval = Duration.ofSeconds(plugin.getConfig().getInt(ConfigKeys.STATUS_CHECK_INTERVAL_SECS));
    this.webhookClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(10000)).build();
  }

  public void stop() {
    stopped = true;
    synchronized (this) {
      this.notify();
    }
  }

  public void updatePlayerCount(int count) {
    l.lock();
    this.lastPlayerCount = count;
    if (count > 0) {
      this.lastPlayerActive = Instant.now();
    }
    l.unlock();
  }

  public synchronized State getState() {
    l.lock();
    var s = this.state;
    l.unlock();
    return s;
  }

  public String getTargetServer() {
    // final
    return targetServer;
  }

  public void enqueue(ProxiedPlayer p) {
    l.lock();
    this.queue.add(p);
    l.unlock();
    synchronized (this) {
      this.notify();
    }
  }

  public void keepAliveFor(Duration duration) {
    l.lock();
    try {
      this.keepAliveUntil = Instant.now().plus(duration);
      if (duration.isZero()) {
        this.keepAliveUntil = null;
      }
    } finally {
      l.unlock();
    }
    synchronized (this) {
      this.notify();
    }
  }

  public void keepAliveForever() {
    l.lock();
    try {
      this.keepAliveUntil = Instant.MAX;
    } finally {
      l.unlock();
    }
    synchronized (this) {
      this.notify();
    }
  }

  /**
   * Must already own lock
   */
  private boolean isKeepAliveEffective() {
    return this.keepAliveUntil != null && this.keepAliveUntil.isAfter(Instant.now());
  }

  /**
   * Must hold lock already. Clears the queue.
   */
  private void broadcastErrorToQueue(String msg) {
    var chat = new ComponentBuilder()
        .color(ChatColor.RED)
        .append(msg)
        .create();
    for (var p : queue) {
      p.disconnect(chat);
    }
    queue.clear();
  }

  /**
   * Must hold lock already. Clears the queue.
   */
  private void connectAllInQueue() {
    for (var p : this.queue) {
      p.connect(ServerConnectRequest.builder().target(serverInfo).reason(Reason.PLUGIN).build());
    }
    this.queue.clear();
  }

  private void update() {
    l.lock();
    this.updatePlayerCount(this.plugin.getProxy().getOnlineCount());
    try {
      if (this.state == State.NOT_READY) {
        this.lastStatusCheck = Instant.now();
        l.unlock();
        State new_state;
        try {
          new_state = controller.checkState();
        } finally {
          l.lock();
        }
        this.state = new_state;
        if (new_state == State.NOT_READY) {
          l.unlock();
          try {
            synchronized (this) {
              this.wait(5000);
            }
          } catch (InterruptedException e) {
          } finally {
            l.lock();
          }
        }
        return;
      }
      if (this.state == State.RUNNING && !this.queue.isEmpty()) {
        // left over from last resume
        this.connectAllInQueue();
        return;
      }
      if (this.state == State.SUSPENDED && (!this.queue.isEmpty() || isKeepAliveEffective())) {
        l.unlock();
        State new_state = State.SUSPENDED;
        Exception err = null;
        try {
          try {
            this.controller.resume();
            while (new_state == State.SUSPENDED) {
              try {
                Thread.sleep(500);
              } catch (InterruptedException e) {
              }
              new_state = this.controller.checkState();
            }
          } catch (Exception e) {
            err = e;
          }
        } finally {
          l.lock();
        }
        this.lastStatusCheck = Instant.now();
        this.state = new_state;
        if (err == null) {
          this.plugin.getLogger().info("Resumed server " + this.targetServer);
          if (!this.isKeepAliveEffective()) {
            this.webhookNotify(WebhookEvent.RESUMED, this.queue.get(0));
            if (this.queue.size() > 1) {
              for (int i = 1; i < this.queue.size(); i++) {
                this.webhookNotify(WebhookEvent.JOINED_WHILE_RUNNING, this.queue.get(i));
              }
            }
          } else {
            this.webhookNotify(WebhookEvent.KEEPALIVE, null);
          }
        } else {
          this.plugin.getLogger().severe(String.format("Error when resuming server: %s", err.toString()));
          err.printStackTrace();
          this.broadcastErrorToQueue(
              String.format("There was an error when resuming the server:\n%s\nPlease try again later.",
                  err.getMessage()));
        }
        // next loop iteration will connect the players.
        return;
      }
      if (this.state == State.RUNNING && !isKeepAliveEffective() && this.lastPlayerCount == 0
          && this.lastPlayerActive.isBefore(
              Instant.now().minus(Duration.ofSeconds(plugin.getConfig().getInt(ConfigKeys.SLEEP_DELAY_SECS))))) {
        // Set state to suspended first to stop new joins
        this.state = State.SUSPENDED;
        l.unlock();
        boolean succeed = false;
        try {
          controller.suspend();
          succeed = true;
          this.webhookNotify(WebhookEvent.SUSPENDED, null);
        } catch (Exception e) {
          this.plugin.getLogger().severe(String.format("Error suspending machine: %s", e.toString()));
          e.printStackTrace();
        } finally {
          l.lock();
        }
        if (succeed) {
          this.plugin.getLogger().info("Suspended server " + this.targetServer);
          this.state = State.SUSPENDED;
        } else {
          this.state = controller.checkState();
        }
        this.lastStatusCheck = Instant.now();
        return;
      }

      if (this.lastStatusCheck.isBefore(Instant.now().minus(statusCheckInterval))) {
        this.lastStatusCheck = Instant.now();
        l.unlock();
        State check_state;
        try {
          check_state = controller.checkState();
        } finally {
          l.lock();
        }
        this.state = check_state;
      }
    } finally {
      l.unlock();
    }
    synchronized (this) {
      try {
        this.wait(1000);
      } catch (InterruptedException e) {
      }
    }
  }

  @Override
  public void run() {
    while (true) {
      if (stopped) {
        return;
      }

      update();
    }
  }

  public enum WebhookEvent {
    RESUMED, SUSPENDED, JOINED_WHILE_RUNNING, LEFT, KEEPALIVE
  }

  public void webhookNotify(final WebhookEvent event, ProxiedPlayer actor) {
    final var plugin = this.plugin;
    final var config = plugin.getConfig();
    if (!config.contains(ConfigKeys.WEBHOOK)) {
      return;
    }
    final var webhook_config = config.getSection(ConfigKeys.WEBHOOK);
    final String player_name;
    if (webhook_config.getBoolean(ConfigKeys.WEBHOOK_INCLUDE_USER, true) && actor != null) {
      player_name = actor.getName();
    } else {
      player_name = null;
    }
    final var client = this.webhookClient;
    final int nb_players = this.lastPlayerCount; // A more reliable number than proxy.getOnlineCount when player are
                                                 // leaving. See Events.onDisconnect
    plugin.getProxy().getScheduler().runAsync(plugin, () -> {
      String msg = generateWebhookMessage(event, player_name, nb_players);
      var json = new JsonObject();
      json.addProperty(webhook_config.getString(ConfigKeys.WEBHOOK_JSON_KEY, "content"), msg);
      try {
        var req = HttpRequest.newBuilder()
            .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(json)))
            .uri(new URI(webhook_config.getString(ConfigKeys.WEBHOOK_URL)))
            .setHeader("User-Agent", "Minecraft-AutoSuspend")
            .setHeader("Content-Type", "application/json")
            .build();
        var res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
          throw new Exception(String.format("%d: %s", res.statusCode(), res.body()));
        }
      } catch (Exception e) {
        plugin.getLogger().severe(String.format("Unable to send webhook: ", e.toString()));
        e.printStackTrace();
      }
    });
  }

  private String generateWebhookMessage(WebhookEvent event, String player_name, int nb_players) {
    switch (event) {
      case RESUMED:
        if (player_name != null) {
          return String.format("Server resumed: %s joined the game.", player_name);
        } else {
          return "Server resumed: someone joined.";
        }
      case SUSPENDED:
        return "Server suspended.";
      case JOINED_WHILE_RUNNING:
        if (player_name != null) {
          return String.format("%s joined the game. (%d players online)", player_name, nb_players);
        } else {
          return String.format("Someone just joined. (%d players online)", nb_players);
        }
      case LEFT:
        if (player_name != null) {
          return String.format("%s left the game. (%d players now online)", player_name, nb_players);
        } else {
          return String.format("Someone just left. (%d players now online)", nb_players);
        }
      case KEEPALIVE:
        return "Server keepalive enabled.";
      default:
        throw new RuntimeException("unreachable");
    }
  }
}
