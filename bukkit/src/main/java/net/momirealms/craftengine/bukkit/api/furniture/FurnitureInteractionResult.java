package net.momirealms.craftengine.bukkit.api.furniture;

import net.momirealms.craftengine.core.world.EntityHitResult;

import java.util.Objects;

/**
 * 解析器的明确裁决：跳过、拒绝或提交命中；拒绝不会继续尝试其他实现。
 */
public sealed interface FurnitureInteractionResult {
    Pass PASS = new Pass();

    /**
     * 当前实现不负责本次请求。
     */
    record Pass() implements FurnitureInteractionResult {
    }

    /**
     * 当前实现负责本次请求，但请求不合法。
     */
    record Reject(String reason) implements FurnitureInteractionResult {
        public Reject {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * 提交候选世界命中及命中面；CE 仍会验证坐标、距离、碰撞箱和遮挡。
     */
    record Hit(EntityHitResult hitResult) implements FurnitureInteractionResult {
        public Hit {
            Objects.requireNonNull(hitResult, "hitResult");
        }
    }
}
