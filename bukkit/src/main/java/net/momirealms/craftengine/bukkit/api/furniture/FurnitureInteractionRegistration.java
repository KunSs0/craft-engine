package net.momirealms.craftengine.bukkit.api.furniture;

/**
 * 命中解析器的注销句柄；重复关闭无副作用，所属插件禁用时自动关闭。
 */
@FunctionalInterface
public interface FurnitureInteractionRegistration extends AutoCloseable {
    @Override
    void close();
}
