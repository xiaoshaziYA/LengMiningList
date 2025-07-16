package shaziawa.LengMiningList;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.*;

import java.io.File;
import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class LengMiningList extends JavaPlugin implements Listener {

    // 数据库连接
    private Connection miningConnection;
    private Connection placingConnection;
    private Connection pvpConnection;
    private Connection mobConnection;
    private Connection daoguanConnection;

    // 数据统计
    private final ConcurrentHashMap<String, Integer> miningCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> placeCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pvpKillCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> mobKillCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> daoguanCounts = new ConcurrentHashMap<>();

    // 玩家状态
    private final Map<UUID, ScoreboardStatus> playerScoreboardStatus = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastToggleTime = new ConcurrentHashMap<>();
    private final Map<String, ChatColor> playerColorCache = new ConcurrentHashMap<>();
    private final Set<UUID> sneakingPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<UUID, Long> lastDaoguanUpdate = new ConcurrentHashMap<>();

    // 配置相关
    private FileConfiguration config;
    private final Set<ScoreboardStatus> enabledBoards = EnumSet.noneOf(ScoreboardStatus.class);
    private String weiAiMuId;
    private int pointsPerSecondNearWeiAiMu;
    private int pointsPer3Seconds;
    private String permissionToGrant;
    private int requiredPointsForPermission;

    // 计分板状态枚举
    public enum ScoreboardStatus {
        MINING("挖掘榜"),
        PLACING("放置榜"),
        PVP("壁咚榜"),
        MOB("怪物猎人榜"),
        DAOGUAN("道馆榜"),
        HIDDEN("隐藏");

        private final String displayName;

        ScoreboardStatus(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    @Override
    public void onEnable() {
        loadConfig();
        getLogger().info(ChatColor.GOLD + "[LengMiningList] " +
                ChatColor.AQUA + "插件已加载，作者" +
                ChatColor.LIGHT_PURPLE + "shazi_awa" +
                ChatColor.GREEN + " 版本号: " +
                ChatColor.YELLOW + getDescription().getVersion());

        initializeDatabases();
        loadAllPlayerData();
        getServer().getPluginManager().registerEvents(this, this);

        try {
            Objects.requireNonNull(getCommand("wjb")).setExecutor(new MiningCommand(this));
        } catch (Exception e) {
            getLogger().severe("命令注册失败: " + e.getMessage());
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    updateScoreboardIfVisible(player);
                }
            }
        }.runTaskTimer(this, 20L, 20L);

        if (enabledBoards.contains(ScoreboardStatus.DAOGUAN)) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    long now = System.currentTimeMillis();
                    for (UUID playerId : sneakingPlayers) {
                        Player player = Bukkit.getPlayer(playerId);
                        if (player != null && player.isOnline()) {
                            checkDaoguanProximity(player, now);
                        }
                    }
                }
            }.runTaskTimer(this, 20L, 20L);
        }
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            hideScoreboard(player);
        }
        closeDatabase(miningConnection, "mining");
        closeDatabase(placingConnection, "placing");
        closeDatabase(pvpConnection, "pvp");
        closeDatabase(mobConnection, "mob");
        closeDatabase(daoguanConnection, "daoguan");
    }

    private void loadConfig() {
        saveDefaultConfig();
        config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
        enabledBoards.clear();
        if (config.getBoolean("enabled-boards.mining", true)) enabledBoards.add(ScoreboardStatus.MINING);
        if (config.getBoolean("enabled-boards.placing", true)) enabledBoards.add(ScoreboardStatus.PLACING);
        if (config.getBoolean("enabled-boards.pvp", true)) enabledBoards.add(ScoreboardStatus.PVP);
        if (config.getBoolean("enabled-boards.mob", true)) enabledBoards.add(ScoreboardStatus.MOB);
        if (config.getBoolean("enabled-boards.daoguan", true)) enabledBoards.add(ScoreboardStatus.DAOGUAN);

        weiAiMuId = config.getString("daoguan-settings.wei-ai-mu-id", "WeiAiMu");
        pointsPerSecondNearWeiAiMu = config.getInt("daoguan-settings.points-per-second-near-wei-ai-mu", 10);
        pointsPer3Seconds = config.getInt("daoguan-settings.points-per-3-seconds", 1);
        permissionToGrant = config.getString("daoguan-settings.permission-to-grant", "cfc.daoguan");
        requiredPointsForPermission = config.getInt("daoguan-settings.required-points-for-permission", 1000);
    }

    // ✅ 修复：改名为 reloadPluginConfig()
    public boolean reloadPluginConfig() {
        try {
            config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));
            loadConfig();
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "重载配置失败", e);
            return false;
        }
    }

    public Set<ScoreboardStatus> getEnabledBoards() {
        return Collections.unmodifiableSet(enabledBoards);
    }

    private void initializeDatabases() {
        File dataFolder = getDataFolder();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        miningConnection = initializeSingleDatabase(new File(dataFolder, "mining_data.db"), "mining_stats");
        placingConnection = initializeSingleDatabase(new File(dataFolder, "placing_data.db"), "placing_stats");
        pvpConnection = initializeSingleDatabase(new File(dataFolder, "pvp_data.db"), "pvp_stats");
        mobConnection = initializeSingleDatabase(new File(dataFolder, "mob_data.db"), "mob_stats");
        daoguanConnection = initializeSingleDatabase(new File(dataFolder, "daoguan_data.db"), "daoguan_stats");
    }

    private Connection initializeSingleDatabase(File dbFile, String tableName) {
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (player_name TEXT PRIMARY KEY, count INTEGER DEFAULT 0)");
            }
            return conn;
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, tableName + "数据库初始化失败", e);
            return null;
        }
    }

    private void loadAllPlayerData() {
        if (enabledBoards.contains(ScoreboardStatus.MINING)) loadPlayerData(miningConnection, "mining_stats", miningCounts, "挖掘");
        if (enabledBoards.contains(ScoreboardStatus.PLACING)) loadPlayerData(placingConnection, "placing_stats", placeCounts, "放置");
        if (enabledBoards.contains(ScoreboardStatus.PVP)) loadPlayerData(pvpConnection, "pvp_stats", pvpKillCounts, "壁咚");
        if (enabledBoards.contains(ScoreboardStatus.MOB)) loadPlayerData(mobConnection, "mob_stats", mobKillCounts, "怪物猎人");
        if (enabledBoards.contains(ScoreboardStatus.DAOGUAN)) loadPlayerData(daoguanConnection, "daoguan_stats", daoguanCounts, "道馆");
    }

    private void loadPlayerData(Connection conn, String tableName, ConcurrentHashMap<String, Integer> counts, String type) {
        if (conn == null) return;
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT player_name, count FROM " + tableName)) {
            while (rs.next()) counts.put(rs.getString("player_name"), rs.getInt("count"));
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "加载" + type + "玩家数据时出错", e);
        }
    }

    private void updatePlayerData(Connection conn, String tableName, String playerName, int count, String type) {
        if (conn == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO " + tableName + " (player_name, count) VALUES (?, ?)")) {
                ps.setString(1, playerName);
                ps.setInt(2, count);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "更新" + type + "玩家数据失败: " + playerName, e);
            }
        });
    }

    private void updateScoreboardIfVisible(Player player) {
        UUID id = player.getUniqueId();
        ScoreboardStatus status = playerScoreboardStatus.getOrDefault(id, ScoreboardStatus.HIDDEN);
        if (status != ScoreboardStatus.HIDDEN) {
            long now = System.currentTimeMillis();
            Long lastToggle = lastToggleTime.get(id);
            if (lastToggle == null || now - lastToggle >= 5000) {
                status = getNextStatus(status);
                playerScoreboardStatus.put(id, status);
                lastToggleTime.put(id, now);
            }
            updatePlayerScoreboard(player, status);
        }
    }

    private ScoreboardStatus getNextStatus(ScoreboardStatus current) {
        if (enabledBoards.size() <= 1) return current;
        List<ScoreboardStatus> enabledList = new ArrayList<>(enabledBoards);
        int currentIndex = enabledList.indexOf(current);
        if (currentIndex == -1) currentIndex = 0;
        int nextIndex = (currentIndex + 1) % enabledList.size();
        return enabledList.get(nextIndex);
    }

    private void checkDaoguanProximity(Player player, long currentTime) {
        String playerName = player.getName();
        boolean nearWeiAiMu = isNearWeiAiMu(player);
        Long lastUpdate = lastDaoguanUpdate.get(player.getUniqueId());
        if (lastUpdate == null) {
            lastUpdate = currentTime;
            lastDaoguanUpdate.put(player.getUniqueId(), lastUpdate);
        }
        long timeDiff = currentTime - lastUpdate;
        if (nearWeiAiMu && timeDiff >= 1000) {
            updateDaoguanCount(player, playerName, pointsPerSecondNearWeiAiMu, currentTime);
        } else if (timeDiff >= 3000) {
            updateDaoguanCount(player, playerName, pointsPer3Seconds, currentTime);
        }
    }

    private boolean isNearWeiAiMu(Player player) {
        Player weiAiMu = Bukkit.getPlayerExact(weiAiMuId);
        return weiAiMu != null && weiAiMu.isOnline() &&
               player.getWorld().equals(weiAiMu.getWorld()) &&
               player.getLocation().distanceSquared(weiAiMu.getLocation()) <= 1.0;
    }

    private void updateDaoguanCount(Player player, String playerName, int increment, long currentTime) {
        int newCount = daoguanCounts.merge(playerName, increment, Integer::sum);
        updatePlayerData(daoguanConnection, "daoguan_stats", playerName, newCount, "道馆");
        lastDaoguanUpdate.put(player.getUniqueId(), currentTime);
        if (newCount >= requiredPointsForPermission && !player.hasPermission(permissionToGrant)) {
            grantDaoguanPermission(player);
        }
    }

    private void grantDaoguanPermission(Player player) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "lp user " + player.getName() + " permission set " + permissionToGrant);
        player.sendMessage(ChatColor.GOLD + "[道馆] " + ChatColor.GREEN + "恭喜你获得了道馆权限!");
    }

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        if (!enabledBoards.contains(ScoreboardStatus.DAOGUAN)) return;
        Player player = event.getPlayer();
        if (event.isSneaking()) {
            sneakingPlayers.add(player.getUniqueId());
            lastDaoguanUpdate.put(player.getUniqueId(), System.currentTimeMillis());
        } else {
            sneakingPlayers.remove(player.getUniqueId());
            lastDaoguanUpdate.remove(player.getUniqueId());
        }
    }

    private boolean isHostileMob(Entity entity) {
        if (!(entity instanceof Monster)) return false;
        if (entity instanceof Enderman) return false;
        return entity instanceof Zombie || entity instanceof Skeleton ||
               entity instanceof Creeper || entity instanceof Spider ||
               entity instanceof Witch || entity instanceof Blaze ||
               entity instanceof Ghast || entity instanceof Slime ||
               entity instanceof MagmaCube || entity instanceof Phantom ||
               entity instanceof Drowned || entity instanceof Husk ||
               entity instanceof Stray || entity instanceof WitherSkeleton ||
               entity instanceof Guardian || entity instanceof ElderGuardian ||
               entity instanceof Shulker || entity instanceof Vex ||
               entity instanceof Vindicator || entity instanceof Evoker ||
               entity instanceof Pillager || entity instanceof Ravager ||
               entity instanceof Warden || entity instanceof Hoglin ||
               entity instanceof Zoglin || entity instanceof PiglinBrute;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!enabledBoards.contains(ScoreboardStatus.MINING)) return;
        Player player = event.getPlayer();
        String name = player.getName();
        int count = miningCounts.merge(name, 1, Integer::sum);
        updatePlayerData(miningConnection, "mining_stats", name, count, "挖掘");
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!enabledBoards.contains(ScoreboardStatus.PLACING)) return;
        Player player = event.getPlayer();
        String name = player.getName();
        int count = placeCounts.merge(name, 1, Integer::sum);
        updatePlayerData(placingConnection, "placing_stats", name, count, "放置");
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!enabledBoards.contains(ScoreboardStatus.PVP)) return;
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer != null) {
            String killerName = killer.getName();
            int count = pvpKillCounts.merge(killerName, 1, Integer::sum);
            updatePlayerData(pvpConnection, "pvp_stats", killerName, count, "壁咚");
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!enabledBoards.contains(ScoreboardStatus.MOB)) return;
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer != null && isHostileMob(entity)) {
            String killerName = killer.getName();
            int count = mobKillCounts.merge(killerName, 1, Integer::sum);
            updatePlayerData(mobConnection, "mob_stats", killerName, count, "怪物猎人");
        }
    }

    public void toggleScoreboard(Player player) {
        UUID id = player.getUniqueId();
        ScoreboardStatus currentStatus = playerScoreboardStatus.getOrDefault(id, ScoreboardStatus.HIDDEN);
        if (currentStatus == ScoreboardStatus.HIDDEN) {
            if (enabledBoards.isEmpty()) {
                player.sendMessage(ChatColor.RED + "没有启用的榜单");
                return;
            }
            playerScoreboardStatus.put(id, enabledBoards.iterator().next());
            lastToggleTime.put(id, System.currentTimeMillis());
            showScoreboard(player);
            updatePlayerScoreboard(player, playerScoreboardStatus.get(id));
        } else {
            playerScoreboardStatus.put(id, ScoreboardStatus.HIDDEN);
            hideScoreboard(player);
        }
    }

    private void showScoreboard(Player player) {
        player.sendMessage(ChatColor.YELLOW + "[" + ChatColor.GOLD + "LengMiningList" + ChatColor.YELLOW + "] " +
                ChatColor.GREEN + "排行榜已显示");
    }

    private void hideScoreboard(Player player) {
        try {
            Scoreboard sb = player.getScoreboard();
            Objective obj = sb.getObjective("mining_stats");
            if (obj != null) obj.unregister();
            sb.clearSlot(DisplaySlot.SIDEBAR);
            player.sendMessage(ChatColor.YELLOW + "[" + ChatColor.GOLD + "LengMiningList" + ChatColor.YELLOW + "] " +
                    ChatColor.RED + "排行榜已隐藏");
        } catch (Exception e) {
            getLogger().warning("隐藏计分板时出错: " + e.getMessage());
        }
    }

    private void updatePlayerScoreboard(Player player, ScoreboardStatus status) {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                Scoreboard sb = player.getScoreboard();
                Objective old = sb.getObjective("mining_stats");
                if (old != null) old.unregister();

                String title = status.getDisplayName();
                List<Map.Entry<String, Integer>> top = getTopList(status, 10);

                Objective obj = sb.registerNewObjective("mining_stats", Criteria.DUMMY, ChatColor.GOLD + title);
                obj.setDisplaySlot(DisplaySlot.SIDEBAR);

                if (top.isEmpty()) {
                    obj.getScore(ChatColor.GRAY + "暂无数据").setScore(0);
                } else {
                    for (int i = 0; i < top.size(); i++) {
                        Map.Entry<String, Integer> e = top.get(i);
                        String name = e.getKey();
                        ChatColor color = getPlayerColor(name);
                        String crown = (i == 0) ? ChatColor.GOLD + " 👑" : "";
                        String line = ChatColor.YELLOW + "No." + (i + 1) + " " +
                                color + name + crown +
                                ChatColor.WHITE + ": " + ChatColor.RED + e.getValue();
                        obj.getScore(line).setScore(top.size() - i);
                    }
                }
            } catch (Exception e) {
                getLogger().warning("更新计分板时出错: " + e.getMessage());
            }
        });
    }

    private List<Map.Entry<String, Integer>> getTopList(ScoreboardStatus status, int limit) {
        switch (status) {
            case MINING: return getTopMiners(limit);
            case PLACING: return getTopPlacers(limit);
            case PVP: return getTopPvpKills(limit);
            case MOB: return getTopMobKills(limit);
            case DAOGUAN: return getTopDaoguan(limit);
            default: return Collections.emptyList();
        }
    }

    private List<Map.Entry<String, Integer>> getTopMiners(int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(miningCounts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list.subList(0, Math.min(limit, list.size()));
    }

    private List<Map.Entry<String, Integer>> getTopPlacers(int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(placeCounts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list.subList(0, Math.min(limit, list.size()));
    }

    private List<Map.Entry<String, Integer>> getTopPvpKills(int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(pvpKillCounts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list.subList(0, Math.min(limit, list.size()));
    }

    private List<Map.Entry<String, Integer>> getTopMobKills(int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(mobKillCounts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list.subList(0, Math.min(limit, list.size()));
    }

    private List<Map.Entry<String, Integer>> getTopDaoguan(int limit) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(daoguanCounts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list.subList(0, Math.min(limit, list.size()));
    }

    private ChatColor getPlayerColor(String playerName) {
        if (playerColorCache.containsKey(playerName)) return playerColorCache.get(playerName);
        Player player = Bukkit.getPlayerExact(playerName);
        ChatColor color = ChatColor.WHITE;
        if (player != null) {
            if (player.hasPermission("color.admin")) color = ChatColor.RED;
            else if (player.hasPermission("color.liteadmin")) color = ChatColor.GOLD;
            else if (player.hasPermission("color.mod")) color = ChatColor.GREEN;
            else if (player.hasPermission("color.pro")) color = ChatColor.AQUA;
            playerColorCache.put(playerName, color);
        }
        return color;
    }

    public void showPluginInfo(Player player) {
        player.sendMessage(ChatColor.YELLOW + "=== " + ChatColor.GOLD + "LengMiningList" + ChatColor.YELLOW + " ===");
        player.sendMessage(ChatColor.YELLOW + "作者: " + ChatColor.LIGHT_PURPLE + "shazi_awa");
        player.sendMessage(ChatColor.YELLOW + "版本号: " + ChatColor.GREEN + getDescription().getVersion());
        StringBuilder boards = new StringBuilder();
        for (ScoreboardStatus status : enabledBoards) {
            if (boards.length() > 0) boards.append("/");
            boards.append(status.getDisplayName());
        }
        player.sendMessage(ChatColor.YELLOW + "当前启用的榜单: " + ChatColor.GREEN + boards.toString());
        player.sendMessage(ChatColor.YELLOW + "授权于: " + ChatColor.GREEN + "ColorFul" + ChatColor.WHITE + "Craft Network");
        player.sendMessage(ChatColor.YELLOW + "===================");
    }

    private void closeDatabase(Connection conn, String dbName) {
        try {
            if (conn != null && !conn.isClosed()) conn.close();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, dbName + "数据库连接关闭失败", e);
        }
    }
}