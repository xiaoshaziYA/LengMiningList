package shaziawa.LengMiningList;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.*;
import org.bukkit.scheduler.*;
import org.bukkit.scoreboard.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import java.util.stream.Collectors;

import java.util.Collections;
import java.sql.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;

public class LengMiningList extends JavaPlugin implements Listener {

    // ========= 数据库 ==========
    private Connection miningConnection, placingConnection, pvpConnection, mobConnection,
                       daoguanConnection, voidConnection, suicideConnection, discardConnection, settingsConnection;

    // ========= 统计 ==========
    private final ConcurrentHashMap<String, Integer> miningCounts   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> placeCounts    = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> pvpKillCounts  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> mobKillCounts  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> daoguanCounts  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> voidDeathCounts= new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> suicideCounts  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> discardCounts  = new ConcurrentHashMap<>();

    // ========= 玩家状态 ==========
    private final Map<UUID, ScoreboardStatus> playerScoreboardStatus = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastToggleTime = new ConcurrentHashMap<>();
    private final Map<String, ChatColor> playerColorCache = new ConcurrentHashMap<>();
    private final Set<UUID> sneakingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastDaoguanUpdate = new ConcurrentHashMap<>();
    private final Set<UUID> pendingSuicide = ConcurrentHashMap.newKeySet();

    // ========= 设置 ==========
    private final Map<UUID, Set<ScoreboardStatus>> playerSettings = new ConcurrentHashMap<>();
    private final Set<ScoreboardStatus> enabledBoards = EnumSet.noneOf(ScoreboardStatus.class);
    private final Map<ScoreboardStatus, BoardReward> boardRewards = new EnumMap<>(ScoreboardStatus.class);

    // ========= 配置字段 ==========
    private FileConfiguration config;
    private String weiAiMuId;
    private int pointsPerSecondNearWeiAiMu, pointsPer3Seconds;

    public enum ScoreboardStatus {
        MINING("挖掘榜"), PLACING("放置榜"), PVP("壁咚榜"), MOB("怪物猎人榜"),
        DAOGUAN("道馆榜"), VOID("自走虚空榜"), SUICIDE("自我主宰榜"), DISCARD("丢弃榜"), HIDDEN("隐藏");

        private final String displayName;
        ScoreboardStatus(String displayName) { this.displayName = displayName; }
        public String getDisplayName() { return displayName; }
    }

    private static class BoardReward {
        final String permission;
        final int requiredPoints;
        BoardReward(String p, int r) { permission = p; requiredPoints = r; }
    }

    // =========================================================
    //  生命周期
    // =========================================================
    @Override
    public void onEnable() {
        loadConfig();
        initializeDatabases();
        loadAllPlayerData();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("wjb")).setExecutor(new MiningCommand(this));

        // 计分板定时刷新
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) updateScoreboardIfVisible(p);
            }
        }.runTaskTimer(this, 20L, 20L);

        // 道馆榜定时检测
        if (enabledBoards.contains(ScoreboardStatus.DAOGUAN)) {
            new BukkitRunnable() {
                @Override public void run() {
                    long now = System.currentTimeMillis();
                    for (UUID id : sneakingPlayers) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline()) checkDaoguanProximity(p, now);
                    }
                }
            }.runTaskTimer(this, 20L, 20L);
        }
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) hideScoreboard(p);
        closeDatabase(miningConnection, "mining");
        closeDatabase(placingConnection, "placing");
        closeDatabase(pvpConnection, "pvp");
        closeDatabase(mobConnection, "mob");
        closeDatabase(daoguanConnection, "daoguan");
        closeDatabase(voidConnection, "void");
        closeDatabase(suicideConnection, "suicide");
        closeDatabase(discardConnection, "discard");
        closeDatabase(settingsConnection, "settings");
    }

    // =========================================================
    //  配置加载
    // =========================================================
    private void loadConfig() {
        saveDefaultConfig();
        config = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "config.yml"));

        enabledBoards.clear();
        if (config.getBoolean("enabled-boards.mining", true)) enabledBoards.add(ScoreboardStatus.MINING);
        if (config.getBoolean("enabled-boards.placing", true)) enabledBoards.add(ScoreboardStatus.PLACING);
        if (config.getBoolean("enabled-boards.pvp", true)) enabledBoards.add(ScoreboardStatus.PVP);
        if (config.getBoolean("enabled-boards.mob", true)) enabledBoards.add(ScoreboardStatus.MOB);
        if (config.getBoolean("enabled-boards.daoguan", true)) enabledBoards.add(ScoreboardStatus.DAOGUAN);
        if (config.getBoolean("enabled-boards.void", true)) enabledBoards.add(ScoreboardStatus.VOID);
        if (config.getBoolean("enabled-boards.suicide", true)) enabledBoards.add(ScoreboardStatus.SUICIDE);
        if (config.getBoolean("enabled-boards.discard", true)) enabledBoards.add(ScoreboardStatus.DISCARD);

        // 道馆额外配置
        weiAiMuId = config.getString("daoguan-settings.wei-ai-mu-id", "WeiAiMu");
        pointsPerSecondNearWeiAiMu = config.getInt("daoguan-settings.points-per-second-near-wei-ai-mu", 10);
        pointsPer3Seconds = config.getInt("daoguan-settings.points-per-3-seconds", 1);

        // 权限奖励配置
        boardRewards.clear();
        loadReward(ScoreboardStatus.MINING, config, "mining");
        loadReward(ScoreboardStatus.PLACING, config, "placing");
        loadReward(ScoreboardStatus.PVP, config, "pvp");
        loadReward(ScoreboardStatus.MOB, config, "mob");
        loadReward(ScoreboardStatus.DAOGUAN, config, "daoguan");
        loadReward(ScoreboardStatus.VOID, config, "void");
        loadReward(ScoreboardStatus.SUICIDE, config, "suicide");
        loadReward(ScoreboardStatus.DISCARD, config, "discard");
    }

    private void loadReward(ScoreboardStatus st, FileConfiguration cfg, String key) {
        String perm = cfg.getString("board-rewards." + key + ".permission", "cfc." + key);
        int points  = cfg.getInt("board-rewards." + key + ".required-points", 1000);
        boardRewards.put(st, new BoardReward(perm, points));
    }

    // =========================================================
    //  数据库
    // =========================================================
    private void initializeDatabases() {
        File folder = getDataFolder();
        if (!folder.exists()) folder.mkdirs();

        miningConnection   = initializeSingleDatabase(new File(folder, "mining_data.db"), "mining_stats");
        placingConnection  = initializeSingleDatabase(new File(folder, "placing_data.db"), "placing_stats");
        pvpConnection      = initializeSingleDatabase(new File(folder, "pvp_data.db"), "pvp_stats");
        mobConnection      = initializeSingleDatabase(new File(folder, "mob_data.db"), "mob_stats");
        daoguanConnection  = initializeSingleDatabase(new File(folder, "daoguan_data.db"), "daoguan_stats");
        voidConnection     = initializeSingleDatabase(new File(folder, "void_data.db"), "void_stats");
        suicideConnection  = initializeSingleDatabase(new File(folder, "suicide_data.db"), "suicide_stats");
        discardConnection  = initializeSingleDatabase(new File(folder, "discard_data.db"), "discard_stats");
        settingsConnection = initializeSingleDatabase(new File(folder, "settings.db"), "player_settings");
    }

    private Connection initializeSingleDatabase(File file, String table) {
        try {
            Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement s = c.createStatement()) {
                if (table.equals("player_settings")) {
                    s.execute("CREATE TABLE IF NOT EXISTS player_settings(uuid TEXT PRIMARY KEY, enabled TEXT)");
                } else {
                    s.execute("CREATE TABLE IF NOT EXISTS " + table + "(player_name TEXT PRIMARY KEY, count INTEGER DEFAULT 0)");
                }
            }
            return c;
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, table + " 数据库初始化失败", e);
            return null;
        }
    }

    private void closeDatabase(Connection c, String n) {
        try { if (c != null && !c.isClosed()) c.close(); } catch (SQLException e) {
            getLogger().log(Level.SEVERE, n + " 数据库关闭失败", e);
        }
    }

    private void loadAllPlayerData() {
        if (enabledBoards.contains(ScoreboardStatus.MINING))   loadPlayerData(miningConnection, "mining_stats", miningCounts);
        if (enabledBoards.contains(ScoreboardStatus.PLACING))  loadPlayerData(placingConnection, "placing_stats", placeCounts);
        if (enabledBoards.contains(ScoreboardStatus.PVP))      loadPlayerData(pvpConnection, "pvp_stats", pvpKillCounts);
        if (enabledBoards.contains(ScoreboardStatus.MOB))      loadPlayerData(mobConnection, "mob_stats", mobKillCounts);
        if (enabledBoards.contains(ScoreboardStatus.DAOGUAN))  loadPlayerData(daoguanConnection, "daoguan_stats", daoguanCounts);
        if (enabledBoards.contains(ScoreboardStatus.VOID))     loadPlayerData(voidConnection, "void_stats", voidDeathCounts);
        if (enabledBoards.contains(ScoreboardStatus.SUICIDE))  loadPlayerData(suicideConnection, "suicide_stats", suicideCounts);
        if (enabledBoards.contains(ScoreboardStatus.DISCARD))  loadPlayerData(discardConnection, "discard_stats", discardCounts);
    }

    private void loadPlayerData(Connection c, String table, Map<String, Integer> map) {
        if (c == null) return;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT player_name,count FROM " + table)) {
            while (rs.next()) map.put(rs.getString("player_name"), rs.getInt("count"));
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "加载" + table + "数据失败", e);
        }
    }

    private void updatePlayerData(Connection c, String table, String player, int count, String type) {
        if (c == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO " + table + "(player_name,count) VALUES(?,?)")) {
                ps.setString(1, player);
                ps.setInt(2, count);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "更新" + type + "数据失败: " + player, e);
            }
        });
    }

    // =========================================================
    //  事件监听
    // =========================================================
    @EventHandler public void onBlockBreak(BlockBreakEvent e) {
        if (!enabledBoards.contains(ScoreboardStatus.MINING)) return;
        updateStat(e.getPlayer().getName(), miningCounts, miningConnection, "mining_stats", "挖掘");
    }

    @EventHandler public void onBlockPlace(BlockPlaceEvent e) {
        if (!enabledBoards.contains(ScoreboardStatus.PLACING)) return;
        updateStat(e.getPlayer().getName(), placeCounts, placingConnection, "placing_stats", "放置");
    }

    @EventHandler public void onPlayerDeath(PlayerDeathEvent e) {
        Player player = e.getEntity();
        String name = player.getName();

        if (enabledBoards.contains(ScoreboardStatus.SUICIDE) && pendingSuicide.remove(player.getUniqueId())) {
            updateStat(name, suicideCounts, suicideConnection, "suicide_stats", "自我主宰");
            return;
        }
        if (enabledBoards.contains(ScoreboardStatus.VOID)) {
            EntityDamageEvent dmg = player.getLastDamageCause();
            if (dmg != null && dmg.getCause() == EntityDamageEvent.DamageCause.VOID) {
                updateStat(name, voidDeathCounts, voidConnection, "void_stats", "自走虚空");
                return;
            }
        }
        Player killer = player.getKiller();
        if (killer != null && enabledBoards.contains(ScoreboardStatus.PVP)) {
            updateStat(killer.getName(), pvpKillCounts, pvpConnection, "pvp_stats", "壁咚");
        }
    }

    @EventHandler public void onPlayerDropItem(PlayerDropItemEvent e) {
        if (!enabledBoards.contains(ScoreboardStatus.DISCARD)) return;
        String name = e.getPlayer().getName();
        int count = discardCounts.merge(name, 1, Integer::sum);
        updatePlayerData(discardConnection, "discard_stats", name, count, "丢弃");

        BoardReward reward = boardRewards.get(ScoreboardStatus.DISCARD);
        if (reward != null && count == reward.requiredPoints && !e.getPlayer().hasPermission(reward.permission)) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "lp user " + name + " permission set " + reward.permission);
            e.getPlayer().sendMessage(ChatColor.GOLD + "[丢弃榜] " + ChatColor.GREEN + "恭喜获得权限：" + reward.permission);
        }
    }

    @EventHandler public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent e) {
        if (enabledBoards.contains(ScoreboardStatus.SUICIDE) && e.getMessage().equalsIgnoreCase("/killme")) {
            pendingSuicide.add(e.getPlayer().getUniqueId());
        }
    }

    @EventHandler public void onEntityDeath(EntityDeathEvent e) {
        if (!enabledBoards.contains(ScoreboardStatus.MOB)) return;
        Player killer = e.getEntity().getKiller();
        if (killer != null && isHostileMob(e.getEntity())) {
            updateStat(killer.getName(), mobKillCounts, mobConnection, "mob_stats", "怪物猎人");
        }
    }

    @EventHandler public void onPlayerToggleSneak(PlayerToggleSneakEvent e) {
        if (!enabledBoards.contains(ScoreboardStatus.DAOGUAN)) return;
        UUID id = e.getPlayer().getUniqueId();
        if (e.isSneaking()) {
            sneakingPlayers.add(id);
            lastDaoguanUpdate.put(id, System.currentTimeMillis());
        } else {
            sneakingPlayers.remove(id);
            lastDaoguanUpdate.remove(id);
        }
    }

    // =========================================================
    //  GUI
    // =========================================================
    public void openSettingsGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, 9, "榜单自定义设置");
        Set<ScoreboardStatus> set = getSettings(player);

        for (ScoreboardStatus st : ScoreboardStatus.values()) {
            if (st == ScoreboardStatus.HIDDEN) continue;

            ItemStack item = new ItemStack(Material.PAPER);
            ItemMeta meta = item.getItemMeta();

            boolean globallyEnabled = enabledBoards.contains(st);
            boolean personallyEnabled = set.contains(st);

            if (globallyEnabled) {
                meta.setDisplayName(ChatColor.YELLOW + st.getDisplayName() + ChatColor.GRAY +
                        " [当前：" + (personallyEnabled ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭") + ChatColor.GRAY + "]");
                meta.setLore(Collections.singletonList(ChatColor.GRAY + "左键切换状态"));
            } else {
                meta.setDisplayName(ChatColor.RED + st.getDisplayName() + ChatColor.GRAY + " [强制关闭中]");
                meta.setLore(Collections.singletonList(ChatColor.RED + "管理员已全局禁用"));
                item.setType(Material.BARRIER);
            }
            item.setItemMeta(meta);
            gui.addItem(item);
        }
        player.openInventory(gui);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (!e.getView().getTitle().equals("榜单自定义设置")) return;
        e.setCancelled(true);
        Player player = (Player) e.getWhoClicked();
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.BARRIER) return;

        String display = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        for (ScoreboardStatus st : ScoreboardStatus.values()) {
            if (display.contains(st.getDisplayName())) {
                if (!enabledBoards.contains(st)) return; // 全局关闭不可切换
                togglePlayerSetting(player, st);
                openSettingsGUI(player);
                return;
            }
        }
    }

    private Set<ScoreboardStatus> getSettings(Player player) {
        return playerSettings.computeIfAbsent(player.getUniqueId(), k -> {
            Set<ScoreboardStatus> set = EnumSet.noneOf(ScoreboardStatus.class);
            set.addAll(enabledBoards);
            return set;
        });
    }

private void togglePlayerSetting(Player player, ScoreboardStatus status) {
    if (!enabledBoards.contains(status)) return;
    Set<ScoreboardStatus> settings = getSettings(player);
    if (settings.contains(status)) {
        settings.remove(status);
    } else {
        settings.add(status);
    }
    savePlayerSettings(player);

    if (playerScoreboardStatus.getOrDefault(player.getUniqueId(), ScoreboardStatus.HIDDEN) == ScoreboardStatus.HIDDEN) {
        ScoreboardStatus first = settings.stream().findFirst().orElse(ScoreboardStatus.HIDDEN);
        if (first != ScoreboardStatus.HIDDEN) {
            playerScoreboardStatus.put(player.getUniqueId(), first);
            updatePlayerScoreboard(player, first);
        }
    } else {
        updateScoreboardIfVisible(player);
    }
}

    private void savePlayerSettings(Player player) {
        if (settingsConnection == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Set<ScoreboardStatus> settings = getSettings(player);
            String enabled = String.join(",", settings.stream().map(Enum::name).toArray(String[]::new));
            try (PreparedStatement ps = settingsConnection.prepareStatement(
                    "INSERT OR REPLACE INTO player_settings(uuid, enabled) VALUES(?,?)")) {
                ps.setString(1, player.getUniqueId().toString());
                ps.setString(2, enabled);
                ps.executeUpdate();
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "保存玩家设置失败", e);
            }
        });
    }

    // =========================================================
    //  计分板控制
    // =========================================================
    public void toggleScoreboard(Player player) {
        UUID id = player.getUniqueId();
        ScoreboardStatus current = playerScoreboardStatus.getOrDefault(id, ScoreboardStatus.HIDDEN);
        Set<ScoreboardStatus> set = getSettings(player);
        if (current == ScoreboardStatus.HIDDEN) {
            if (set.isEmpty()) {
                player.sendMessage(ChatColor.RED + "没有启用的榜单");
                return;
            }
            ScoreboardStatus first = set.iterator().next();
            playerScoreboardStatus.put(id, first);
            updatePlayerScoreboard(player, first);
            player.sendMessage(ChatColor.GREEN + "排行榜已显示");
        } else {
            playerScoreboardStatus.put(id, ScoreboardStatus.HIDDEN);
            hideScoreboard(player);
            player.sendMessage(ChatColor.RED + "排行榜已隐藏");
        }
    }

    private void updateScoreboardIfVisible(Player p) {
        UUID id = p.getUniqueId();
        ScoreboardStatus status = playerScoreboardStatus.getOrDefault(id, ScoreboardStatus.HIDDEN);
        if (status == ScoreboardStatus.HIDDEN) return;

        Set<ScoreboardStatus> set = getSettings(p);
        if (!set.contains(status)) {
            status = getNextStatus(status);
        }
        playerScoreboardStatus.put(id, status);
        updatePlayerScoreboard(p, status);
    }

    private ScoreboardStatus getNextStatus(ScoreboardStatus current) {
        Set<ScoreboardStatus> set = getSettings(Bukkit.getPlayer(playerScoreboardStatus.keySet().iterator().next()));
        List<ScoreboardStatus> list = new ArrayList<>(set);
        if (list.isEmpty()) return ScoreboardStatus.HIDDEN;
        int idx = list.indexOf(current);
        if (idx == -1) idx = 0;
        return list.get((idx + 1) % list.size());
    }

    private void updatePlayerScoreboard(Player p, ScoreboardStatus st) {
        Bukkit.getScheduler().runTask(this, () -> {
            try {
                Scoreboard sb = p.getScoreboard();
                Objective old = sb.getObjective("mining_stats");
                if (old != null) old.unregister();

                List<Map.Entry<String, Integer>> top = getTopList(st, 10);
                Objective obj = sb.registerNewObjective("mining_stats", Criteria.DUMMY, ChatColor.GOLD + st.getDisplayName());
                obj.setDisplaySlot(DisplaySlot.SIDEBAR);

                if (top.isEmpty()) {
                    obj.getScore(ChatColor.GRAY + "暂无数据").setScore(0);
                } else {
                    for (int i = 0; i < top.size(); i++) {
                        Map.Entry<String, Integer> e = top.get(i);
                        String name = e.getKey();
                        ChatColor color = getPlayerColor(name);
                        String crown = i == 0 ? ChatColor.GOLD + " 👑" : "";
                        String line = ChatColor.YELLOW + "No." + (i + 1) + " " + color + name + crown +
                                      ChatColor.WHITE + ": " + ChatColor.RED + e.getValue();
                        obj.getScore(line).setScore(top.size() - i);
                    }
                }
            } catch (Exception ex) {
                getLogger().warning("更新计分板出错: " + ex.getMessage());
            }
        });
    }

private List<Map.Entry<String, Integer>> getTopList(ScoreboardStatus st, int lim) {
    Map<String, Integer> map;
    switch (st) {
        case MINING:   map = miningCounts;   break;
        case PLACING:  map = placeCounts;    break;
        case PVP:      map = pvpKillCounts;  break;
        case MOB:      map = mobKillCounts;  break;
        case DAOGUAN:  map = daoguanCounts;  break;
        case VOID:     map = voidDeathCounts;break;
        case SUICIDE:  map = suicideCounts;  break;
        case DISCARD:  map = discardCounts;  break;
        default:       map = Collections.emptyMap();
    }
    return map.entrySet().stream()
              .sorted((a, b) -> b.getValue() - a.getValue())
              .limit(lim)
              .collect(Collectors.toList());
}

    public void hideScoreboard(Player player) {
        try {
            Scoreboard sb = player.getScoreboard();
            Objective obj = sb.getObjective("mining_stats");
            if (obj != null) obj.unregister();
            sb.clearSlot(DisplaySlot.SIDEBAR);
        } catch (Exception ignored) {}

    }

    public void showPluginInfo(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== LengMiningList ===");
        sender.sendMessage(ChatColor.YELLOW + "作者: shazi_awa");
        sender.sendMessage(ChatColor.YELLOW + "版本号: " + getDescription().getVersion());
        StringBuilder sb = new StringBuilder();
        for (ScoreboardStatus s : enabledBoards) {
            if (sb.length() > 0) sb.append("/");
            sb.append(s.getDisplayName());
        }
        sender.sendMessage(ChatColor.YELLOW + "当前启用的榜单: " + ChatColor.GREEN + sb.toString());
    }

    // ========= 辅助方法 ==========
    private void checkDaoguanProximity(Player p, long now) {
        if (!p.isSneaking()) return;
        boolean near = isNearWeiAiMu(p);
        UUID id = p.getUniqueId();
        Long last = lastDaoguanUpdate.get(id);
        if (last == null) last = now;
        long diff = now - last;
        if (near && diff >= 1000) {
            updateDaoguan(p, pointsPerSecondNearWeiAiMu, now);
        } else if (diff >= 3000) {
            updateDaoguan(p, pointsPer3Seconds, now);
        }
    }

    private boolean isNearWeiAiMu(Player p) {
        Player target = Bukkit.getPlayerExact(weiAiMuId);
        return target != null && target.isOnline() &&
               p.getWorld() == target.getWorld() &&
               p.getLocation().distanceSquared(target.getLocation()) <= 1.0;
    }

    private void updateDaoguan(Player p, int inc, long now) {
        String name = p.getName();
        int count = daoguanCounts.merge(name, inc, Integer::sum);
        updatePlayerData(daoguanConnection, "daoguan_stats", name, count, "道馆");
        lastDaoguanUpdate.put(p.getUniqueId(), now);
    }

    private boolean isHostileMob(Entity e) {
        return e instanceof org.bukkit.entity.Monster && !(e instanceof org.bukkit.entity.Enderman);
    }

private void updateStat(String playerName, ConcurrentHashMap<String, Integer> map,
                        Connection conn, String table, String type) {
    int count = map.merge(playerName, 1, Integer::sum);
    updatePlayerData(conn, table, playerName, count, type);
}

    private ChatColor getPlayerColor(String name) {
        if (playerColorCache.containsKey(name)) return playerColorCache.get(name);
        Player p = Bukkit.getPlayerExact(name);
        ChatColor color = ChatColor.WHITE;
        if (p != null) {
            if (p.hasPermission("color.admin")) color = ChatColor.RED;
            else if (p.hasPermission("color.liteadmin")) color = ChatColor.GOLD;
            else if (p.hasPermission("color.mod")) color = ChatColor.GREEN;
            else if (p.hasPermission("color.pro")) color = ChatColor.AQUA;
            playerColorCache.put(name, color);
        }
        return color;
    }
}