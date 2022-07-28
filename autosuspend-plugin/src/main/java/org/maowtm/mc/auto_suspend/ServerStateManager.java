package org.maowtm.mc.auto_suspend;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

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

  public static enum State {
    NOT_READY,
    RUNNING,
    SUSPENDED
  }

  private State state = State.RUNNING;

  public ServerStateManager(AutoSuspend plugin, String targetServer) {
    this.plugin = plugin;
    this.targetServer = targetServer;
    this.serverInfo = plugin.getProxy().getServerInfo(targetServer);
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

  public synchronized void enqueue(ProxiedPlayer p) {
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

  private void update() {
    l.lock();
    this.updatePlayerCount(this.plugin.getProxy().getOnlineCount());
    try {
      if (this.state == State.RUNNING && !this.queue.isEmpty()) {
        for (var p : this.queue) {
          p.connect(serverInfo);
        }
        this.queue.clear();
        return;
      }
      if (this.state == State.SUSPENDED && !this.queue.isEmpty()) {
        l.unlock();
        State new_state;
        try {
          // TODO resume
          // TODO loop check until server is running
          try {
            Thread.sleep(5000);
          } catch (InterruptedException e) {}
          this.plugin.getLogger().info("Resumed server " + this.targetServer);
          new_state = State.RUNNING;
        } finally {
          l.lock();
        }
        // TODO
        this.state = new_state;
        return;
      }
      if (this.state == State.RUNNING && this.lastPlayerCount == 0 && this.lastPlayerActive.isBefore(
          Instant.now().minus(Duration.ofSeconds(plugin.getConfig().getInt(ConfigKeys.SLEEP_DELAY_SECS))))) {
        this.state = State.SUSPENDED;
        l.unlock();
        State new_state = State.SUSPENDED;
        try {
          // TODO: suspend server
          try {
            Thread.sleep(2000);
          } catch (InterruptedException e) {}
          this.plugin.getLogger().info("Suspended server " + this.targetServer);
        } finally {
          l.lock();
        }
        // this.state = new_state;
        return;
      }
    } finally {
      l.unlock();
    }
  }

  @Override
  public void run() {
    while (true) {
      if (stopped) {
        return;
      }

      update();

      synchronized (this) {
        try {
          this.wait(1000);
        } catch (InterruptedException e) {
        }
      }
    }
  }
}
