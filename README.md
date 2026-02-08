# LengMiningList
适用于高版本服务器的优秀榜单插件 1.21.x
- 获得称号奖励的原理是通过luckperms给特定权限，权限在代码里面嵌套了
- **使用本插件，请遵守许可证！尊重作者！**
```专属纪念称号获得：
挖掘榜达到：100000
放置榜：10000
怪物猎人榜：2000
道馆榜：1000
壁咚榜：100
自走虚空榜：50000
自我主宰榜：3000
丢弃榜：100000
---
获得专属称号奖励（达到以上分数）

挖掘榜（顾名思义）
放置榜（顾名思义）
怪物猎人榜（击杀怪物，不包括末影人）
壁咚榜（击杀玩家）
道馆榜（蹲着靠近玩家，每3秒加1，靠近vm每三秒加10）
自走虚空榜（顾名思义）
```

## config
```# 控制哪些榜单启用 (true/false)
enabled-boards:
  mining: true
  placing: true
  pvp: true
  mob: true
  daoguan: true

# 道馆榜特殊设置
daoguan-settings:
  wei-ai-mu-id: "WeiAiMu" #靠近指定玩家触发特殊操作。这里填写其ID
  points-per-second-near-wei-ai-mu: 10 #蹲着靠近每3秒加10 （上面指定的玩家）
  points-per-3-seconds: 1 #蹲着靠近每三秒加多少（普通玩家）
  permission-to-grant: "cfc.daoguan" #你可以搭配一些luckperms或者称号插件来实现发放指定称号
  required-points-for-permission: 1000 #分数多少发放上面的权限
  
  #关于排行榜上ID颜色
  #color.admin 红色（管理）
  #color.liteadmin 金色（协管）
  #color.mod 绿色（客服）
  #color.pro 蓝色（类似于高级玩家）
  #---
  #本插件初次授权于 ColorFulCraft Network，可能存在水印
  #使用/wjb 显示/隐藏 榜单
  #使用/wjb reload 重载
  #使用/wjb info 查看相关信息
  #---
  #本插件采取ID记录的方法，防止某些特定操作导致uuid变动而数据变动
```
