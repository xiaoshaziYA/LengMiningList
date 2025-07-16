package shaziawa.LengMiningList;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class MiningCommand implements CommandExecutor {
    private final LengMiningList plugin;
    
    public MiningCommand(LengMiningList plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("info")) {
                showPluginInfo(sender);
                return true;
            } else if (args[0].equalsIgnoreCase("reload")) {
                return handleReload(sender);
            }
        }
        
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家才能使用这个命令");
            return true;
        }
        
        return togglePlayerScoreboard((Player) sender);
    }

private boolean handleReload(CommandSender sender) {
    if (!sender.hasPermission("LengMiningList.reload")) {
        sender.sendMessage(ChatColor.RED + "你没有权限执行此命令");
        return true;
    }

    if (plugin.reloadPluginConfig()) {
        sender.sendMessage(ChatColor.GREEN + "配置已重载");
        return true;
    } else {
        sender.sendMessage(ChatColor.RED + "配置重载失败，请查看控制台日志");
        return false;
    }
}

    private void showPluginInfo(CommandSender sender) {
        if (sender instanceof Player) {
            plugin.showPluginInfo((Player) sender);
        } else {
            sender.sendMessage(ChatColor.GOLD + "=== LengMiningList ===");
            sender.sendMessage(ChatColor.YELLOW + "作者: " + ChatColor.LIGHT_PURPLE + "shazi_awa");
            sender.sendMessage(ChatColor.YELLOW + "版本号: " + ChatColor.GREEN + plugin.getDescription().getVersion());
            
            StringBuilder boards = new StringBuilder();
            for (LengMiningList.ScoreboardStatus status : plugin.getEnabledBoards()) {
                if (boards.length() > 0) boards.append("/");
                boards.append(status.getDisplayName());
            }
            
            sender.sendMessage(ChatColor.YELLOW + "当前启用的榜单: " + ChatColor.GREEN + boards.toString());
        }
    }

    private boolean togglePlayerScoreboard(Player player) {
        try {
            plugin.toggleScoreboard(player);
            return true;
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "命令执行出错，请重试");
            plugin.getLogger().warning("命令执行出错: " + e.getMessage());
            return false;
        }
    }
}