# Chest Loader

Chest Loader 是一个纯服务端 Fabric 模组。它用原版物品把木箱或木桶变成区块加载器，原版客户端无需安装模组即可加入服务器。

本文档也有 [English](README.md) 版本。

## Minecraft 版本

- 26.2

## 安装

在服务器安装 Fabric Loader，再把 Fabric API 和 Chest Loader JAR 放入服务器的 `mods` 目录。客户端无需安装 Chest Loader 或 Fabric API。

单人游戏需要把这些文件安装到本地游戏实例。

## 使用方法

在木箱或木桶里摆出配置规定的图案，然后打开容器。默认图案至少需要 10 块黑曜石、同一格内至少 4 节动力铁轨，以及 1 辆矿车：

```text
O O O O
O R M O
O O O O
```

`O` 是黑曜石，`R` 是动力铁轨，`M` 是矿车。图案以外的格子必须为空。图案可以从任意能放下它的列开始，动力铁轨和矿车也可以交换位置。

加载器会在容器所在的维度加载 7×7 个区块，其中中心 3×3 个区块会处理实体。服务器重启后，加载状态会恢复。

从图案中取走物品并关闭容器后，加载器会停用。漏斗造成的变化最多需要等待一个扫描周期。

## 配置

Chest Loader 首次启动时会创建 `config/chestloader.json`：

```json
{
  "patterns": [
    {
      "name": "obsidian-frame",
      "dimensions": ["minecraft:overworld", "minecraft:the_nether"],
      "shape": [
        "OOOO",
        "ORMO",
        "OOOO"
      ],
      "keys": {
        "O": { "items": ["minecraft:obsidian"], "min": 1 },
        "R": { "items": ["minecraft:powered_rail"], "min": 4 },
        "M": {
          "items": [
            "minecraft:minecart",
            "minecraft:chest_minecart",
            "minecraft:hopper_minecart",
            "minecraft:furnace_minecart"
          ],
          "min": 1,
          "max": 1
        }
      },
      "slide": true,
      "mirror": true
    }
  ],
  "ticketLevel": 30,
  "scanIntervalTicks": 200,
  "maxLoadersPerDimension": 32,
  "maxLoadersTotal": 128,
  "notifyOnActivate": true,
  "particleOnActive": true
}
```

`patterns` 中的每一项定义一种有效图案。

- `shape` 按行画出图案。`.` 或空格表示该格必须为空，其他字符对应 `keys` 中的项目。
- `keys` 指定每格接受的物品，以及最少和最多数量。
- `slide` 允许图案从容器内任意能放下它的位置开始。图案外的格子必须为空。
- `mirror` 同时接受图案的左右镜像。
- `dimensions` 指定图案可用的维度。加入 `minecraft:the_end` 可在末地使用。

你可以在 `patterns` 中增加项目，为其他摆法或维度设置图案。

`ticketLevel` 控制加载范围。默认值 30 会加载 7×7 个区块，中心 3×3 个区块会处理实体。设为 32 或 33 后，中心区块也不会处理实体。

`scanIntervalTicks` 控制模组检查漏斗变化的间隔。`maxLoadersPerDimension` 和 `maxLoadersTotal` 限制正在工作的加载器数量。最后两个选项控制激活消息和传送门粒子。

## 指令

```text
/chestloader list
/chestloader disable <x> <y> <z> [维度]
/chestloader enable <x> <y> <z> [维度]
/chestloader check <x> <y> <z>
```

`list` 会列出全部加载器，并在聊天中提供可点击的启用或停用按钮。`disable` 会停止加载区块，但保留记录。`enable` 可以从任何位置恢复加载。以上指令需要 1 级权限。

`check` 会检查指定位置的容器，需要 2 级权限。

## 限制

- 激活的加载器会占用整个容器，不能同时用来储物。
- 区块加载不会改变原版的生物生成和消失距离。附近没有玩家时，加载器不能让刷怪塔工作。
- 需要处理实体的机器应完整放在中心 3×3 个区块内。
- 如果加载器停用后在区块未加载时被破坏，它可能继续出现在 `list` 中，直到区块再次加载或有人尝试启用它。

## 构建和测试

安装 JDK 25 后运行：

```bash
./gradlew test
./gradlew runGameTest
./gradlew build
```

构建产物位于 `build/libs/`。

## 许可证

[Apache License 2.0](LICENSE)
