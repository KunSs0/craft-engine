package net.momirealms.craftengine.bukkit.api.furniture;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitboxPart;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.world.Vec3d;
import org.bukkit.entity.Player;

/**
 * 家具右键命中解析上下文，在 CE 现有的家具交互调度线程内创建并调用。
 * relativeHitPosition 是相对于被点击部件实体位置的客户端坐标，尚未经过服务端验证。
 * eyePosition、eyeDirection 和 interactionRange 均来自当前服务端玩家状态。
 */
public record FurnitureInteractionContext(
        Player player,
        BukkitFurniture furniture,
        FurnitureHitBox hitBox,
        FurnitureHitboxPart part,
        InteractionHand hand,
        boolean secondaryAction,
        Vec3d relativeHitPosition,
        Vec3d eyePosition,
        Vec3d eyeDirection,
        double interactionRange
) {
}
