package org.maowtm.mc.auto_suspend;

import java.io.File;
import java.io.IOException;

import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.config.Configuration;
import net.md_5.bungee.config.ConfigurationProvider;
import net.md_5.bungee.config.YamlConfiguration;

public class AutoSuspend extends Plugin {
  private File configFile;
  private Configuration config;
  private ServerStateManager ssm;

  @Override
  public void onEnable() {
    try {
      if (!this.getDataFolder().exists()) {
        this.getDataFolder().mkdir();
      }
      configFile = new File(this.getDataFolder(), "config.yml");
      if (!configFile.exists()) {
        configFile.createNewFile();
      }
      var defaults = defaultConfig();
      config = ConfigurationProvider.getProvider(YamlConfiguration.class).load(configFile, defaults);
      for (var k : defaults.getKeys()) {
        if (!config.contains(k)) {
          config.set(k, defaults.get(k));
        }
      }
    } catch (IOException e) {
      getLogger().severe(String.format("Unable to open config file: %s", e.toString()));
      throw new RuntimeException(e);
    }
    trySaveConfig();
    ssm = new ServerStateManager(this, config.getString(ConfigKeys.SERVER),
        new GCEController(config.getSection(ConfigKeys.GOOGLE_COMPUTE_ENGINE), getLogger()));
    getProxy().getScheduler().runAsync(this, ssm);
    getProxy().getPluginManager().registerListener(this, new Events(this));
    getProxy().getPluginManager().registerCommand(this, new KeepAliveCommand(this));
  }

  private Configuration defaultConfig() {
    var d = new Configuration();
    String firstServerName = "lobby";
    for (var server : this.getProxy().getServers().keySet()) {
      firstServerName = server;
      break;
    }
    d.set(ConfigKeys.SERVER, firstServerName);
    d.set(ConfigKeys.SLEEP_DELAY_SECS, 30);
    d.set(ConfigKeys.GOOGLE_COMPUTE_ENGINE, GCEController.getDefaultConfig());
    d.set(ConfigKeys.STATUS_CHECK_INTERVAL_SECS, 30);
    return d;
  }

  private boolean trySaveConfig() {
    try {
      ConfigurationProvider.getProvider(YamlConfiguration.class).save(config, configFile);
      return true;
    } catch (IOException e) {
      getLogger().severe(String.format("Unable to save config: %s", e.toString()));
      return false;
    }
  }

  @Override
  public void onDisable() {
    // trySaveConfig();
    // No need to save - nothing changes dynamically here.
    if (this.ssm != null) {
      this.ssm.stop();
    }
  }

  public Configuration getConfig() {
    return this.config;
  }

  public ServerStateManager getStateManager() {
    return this.ssm;
  }
}
