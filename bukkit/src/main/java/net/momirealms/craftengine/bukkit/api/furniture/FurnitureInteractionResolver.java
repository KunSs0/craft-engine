package net.momirealms.craftengine.bukkit.api.furniture;

import org.jetbrains.annotations.NotNull;

/**
 * 第三方家具右键命中解析器；不得保留本次上下文或自行执行家具行为。
 */
@FunctionalInterface
public interface FurnitureInteractionResolver {
    @NotNull
    FurnitureInteractionResult resolve(@NotNull FurnitureInteractionContext context);
}
