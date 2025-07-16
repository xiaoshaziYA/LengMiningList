# LengMiningList
适用于高版本服务器的优秀榜单插件 1.21.x
**使用本插件，请遵守许可证！尊重作者！**
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
  points-per-3-seconds: 1 #蹲着靠近每三秒加多少
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
