package org.maowtm.mc.auto_suspend;

public interface ServerController {
  public ServerStateManager.State checkState();
  public void resume() throws Exception;
  public void suspend() throws Exception;
}
