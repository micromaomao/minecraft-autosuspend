package org.maowtm.mc.auto_suspend;

import java.io.IOException;
import java.util.logging.Logger;

import org.maowtm.mc.auto_suspend.ServerStateManager.State;

import com.google.api.gax.rpc.ApiException;
import com.google.cloud.compute.v1.GetInstanceRequest;
import com.google.cloud.compute.v1.Instance;
import com.google.cloud.compute.v1.InstancesClient;

import net.md_5.bungee.config.Configuration;

public class GCEController implements ServerController {
  private final Configuration config;
  private final Logger logger;
  private final InstancesClient instancesClient;
  private Instance lastInstanceData = null;

  public static Configuration getDefaultConfig() {
    var cfg = new Configuration();
    cfg.set(ConfigKeys.GCE_PROJECT, "my-project");
    cfg.set(ConfigKeys.GCE_ZONE, "europe-west2-c");
    cfg.set(ConfigKeys.GCE_INSTANCE, "minecraft-vm");
    return cfg;
  }

  private GetInstanceRequest buildGetRequest() {
    return GetInstanceRequest.newBuilder()
        .setProject(config.getString(ConfigKeys.GCE_PROJECT))
        .setZone(config.getString(ConfigKeys.GCE_ZONE))
        .setInstance(config.getString(ConfigKeys.GCE_INSTANCE)).build();
  }

  private Instance tryFetchInstanceData() {
    try {
      lastInstanceData = instancesClient.get(buildGetRequest());
    } catch (ApiException e) {
      logger.severe(String.format("Unable to fetch compute instance: ", e.toString()));
      if (lastInstanceData == null) {
        throw new RuntimeException(e);
      }
    }
    return lastInstanceData;
  }

  public GCEController(Configuration config, Logger logger) {
    this.config = config;
    this.logger = logger;
    try {
      this.instancesClient = InstancesClient.create();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    logger.info(tryFetchInstanceData().getStatus());
  }

  @Override
  public State checkState() {
    var inst = tryFetchInstanceData();
    var state = inst.getStatus();
    logger.info(String.format("GCP checkState %s: %s", inst.getName(), state));
    switch (state) {
      case "RUNNING":
        return State.RUNNING;
      case "SUSPENDING":
      case "SUSPENDED":
      case "STAGING":
        return State.SUSPENDED;
      default:
        return State.NOT_READY;
    }
  }

  @Override
  public void resume() throws Exception {
    var f = instancesClient.resumeAsync(config.getString(ConfigKeys.GCE_PROJECT), config.getString(ConfigKeys.GCE_ZONE),
        config.getString(ConfigKeys.GCE_INSTANCE));
    var res = f.get();
    if (res.hasError()) {
      throw new RuntimeException(res.getError().toString());
    }
  }

  @Override
  public void suspend() throws Exception {
    var f = instancesClient.suspendAsync(config.getString(ConfigKeys.GCE_PROJECT),
        config.getString(ConfigKeys.GCE_ZONE),
        config.getString(ConfigKeys.GCE_INSTANCE));
    var res = f.get();
    if (res.hasError()) {
      throw new RuntimeException(res.getError().toString());
    }
  }
}
