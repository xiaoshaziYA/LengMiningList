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
        // 子命令：/wjb toggle
        if (args.length == 1 && args[0].equalsIgnoreCase("toggle")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家才能使用这个命令");
                return true;
            }
            plugin.openSettingsGUI((Player) sender);
            return true;
        }

        // 子命令：/wjb info
        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            plugin.showPluginInfo(sender);
            return true;
        }

        // 默认：/wjb 显示/隐藏计分板
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家才能使用这个命令");
            return true;
        }
        plugin.toggleScoreboard((Player) sender);
        return true;
    }
}