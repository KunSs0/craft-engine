# 家具右键命中解析接口

第三方插件可以通过 `CraftEngineFurniture.registerInteractionResolver(owner, id, priority, resolver)` 提供家具命中解析实现。CE 本身不识别任何相机 Mod，也不依赖第三方插件。

## 注册与生命周期

```java
FurnitureInteractionRegistration registration = CraftEngineFurniture.registerInteractionResolver(
        plugin,
        Key.of("example:camera-interaction"),
        100,
        context -> {
            if (!usesCustomCamera(context.player())) {
                return FurnitureInteractionResult.PASS;
            }
            Vec3d relative = context.relativeHitPosition();
            Vec3d position = context.part().pos();
            Vec3d point = position.add(relative.x, relative.y, relative.z);
            return new FurnitureInteractionResult.Hit(new EntityHitResult(Direction.NORTH, point));
        }
);
// 不再使用时主动注销；owner 插件停用时 CE 也会自动注销。
registration.close();
```

示例中的 `usesCustomCamera` 由接入插件实现；命中面也应由接入插件根据实际点位计算，不能固定使用示例中的 `NORTH`。

注册 ID 全局唯一，重复注册会抛出异常。优先级越高越先执行，同优先级保持注册顺序。`close()` 可重复调用；旧句柄不会移除同 ID 的新注册。注册时 owner 必须已启用，接入插件应声明对 CraftEngine 的依赖或软依赖，并在其启用后注册。

## 解析结果

| 结果 | 后续行为 |
| --- | --- |
| `FurnitureInteractionResult.PASS` | 继续尝试下一解析器；全部跳过或无人注册时，执行 CE 原有的玩家眼睛射线。 |
| `new FurnitureInteractionResult.Hit(hit)` | CE 验证候选命中点，通过后进入正常家具事件与行为链，不再执行其余解析器。 |
| `new FurnitureInteractionResult.Reject(reason)` | 终止本次交互。 |

非法返回、非有限坐标、越界、超距、方块遮挡，以及解析器运行异常都会终止本次交互，不会切换回默认射线。有效命中继续触发原有 `FurnitureInteractEvent`，事件取消、家具行为和后续函数的执行顺序不变。

## 上下文与验证

解析器在 CE 调度的服务器线程内同步执行，不应保存上下文、异步访问实体或自行执行家具行为。上下文包含玩家、家具、命中交互箱及部件、手、潜行标记、客户端候选坐标，以及服务端眼睛位置、方向和原生交互距离。

`relativeHitPosition` 是客户端包中的候选坐标，尚未验证，基准是 `part.pos()` 表示的被点击部件实体位置。它不是相对家具根实体的位置。

第三方命中点必须位于本次部件 AABB 内、在玩家眼睛的原生交互距离内，且玩家眼睛到命中点之间没有方块遮挡。CE 只为原版 float 包坐标转换保留 `1E-5` 的边界误差。默认眼睛射线分支保持原行为。

此接口处理原版 `INTERACT_AT` 右键请求，覆盖旧版 float 坐标包和新版 Vec3 坐标包。左键攻击不包含同等坐标，继续执行原有逻辑。
