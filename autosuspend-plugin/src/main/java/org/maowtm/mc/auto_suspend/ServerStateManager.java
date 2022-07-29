package org.maowtm.mc.auto_suspend;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;

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
    if (this.state == State.RUNNING) {
      l.unlock();
      p.connect(this.serverInfo);
    } else {
      this.queue.add(p);
      l.unlock();
      synchronized (this) {
        this.notify();
      }
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
          synchronized (this) {
            try {
              this.wait(5000);
            } catch (InterruptedException e) {
            }
          }
        }
        return;
      }
      if (this.state == State.RUNNING && !this.queue.isEmpty()) {
        for (var p : this.queue) {
          p.connect(serverInfo);
        }
        this.queue.clear();
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
        } else {
          this.plugin.getLogger().severe(String.format("Error when resuming server: %s", err.toString()));
          err.printStackTrace();
          this.broadcastErrorToQueue(
              String.format("There was an error when resuming the server:\n%s\nPlease try again later.",
                  err.getMessage()));
        }
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
}
