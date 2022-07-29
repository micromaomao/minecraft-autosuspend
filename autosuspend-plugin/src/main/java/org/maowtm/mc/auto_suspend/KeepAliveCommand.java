package org.maowtm.mc.auto_suspend;

import java.time.Duration;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.plugin.Command;

public class KeepAliveCommand extends Command {
  private AutoSuspend plugin;

  public KeepAliveCommand(AutoSuspend plugin) {
    super("keepalive", "auto_suspend.keepalive");
    this.plugin = plugin;
  }

  @Override
  public void execute(CommandSender sender, String[] args) {
    if (!this.hasPermission(sender)) {
      return;
    }
    final String USAGE = "Usage: /keepalive [number of minutes]; or\n" +
        "         /keepalive indefinitely";
    if (args.length != 1) {
      sender.sendMessage(new ComponentBuilder().color(ChatColor.RED).append(USAGE).create());
      return;
    }
    ServerStateManager ssm = this.plugin.getStateManager();
    if (args[0].equalsIgnoreCase("indefinitely")) {
      ssm.keepAliveForever();
      sender.sendMessage(new ComponentBuilder().color(ChatColor.GREEN)
          .append("Server will be kept running indefinitely. Cancel with /keepalive 0").create());
      return;
    } else {
      double minutes;
      Duration dur;
      try {
        minutes = Double.parseDouble(args[0]);
      } catch (NumberFormatException e) {
        sender.sendMessage(new ComponentBuilder().color(ChatColor.RED).append(USAGE).create());
        return;
      }
      if (minutes == 0.0) {
        dur = Duration.ZERO;
      } else {
        dur = Duration.ofSeconds((long) (minutes * 60.0));
      }
      ssm.keepAliveFor(dur);
      if (dur.isZero()) {
        sender.sendMessage(new ComponentBuilder().color(ChatColor.GREEN)
            .append("Server will now suspend normally.")
            .create());
      } else {
        sender.sendMessage(new ComponentBuilder().color(ChatColor.GREEN)
            .append(String.format("Server will be kept running for %s minutes. Cancel with /keepalive 0", args[0]))
            .create());
      }
    }
  }
}
