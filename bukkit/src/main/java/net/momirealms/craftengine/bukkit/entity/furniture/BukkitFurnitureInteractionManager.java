package net.momirealms.craftengine.bukkit.entity.furniture;

import net.momirealms.craftengine.bukkit.api.furniture.FurnitureInteractionContext;
import net.momirealms.craftengine.bukkit.api.furniture.FurnitureInteractionRegistration;
import net.momirealms.craftengine.bukkit.api.furniture.FurnitureInteractionResolver;
import net.momirealms.craftengine.bukkit.api.furniture.FurnitureInteractionResult;
import net.momirealms.craftengine.core.plugin.logger.PluginLogger;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.EntityHitResult;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.collision.AABB;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 管理第三方家具命中解析器及默认眼睛射线，使用快照遍历兼容区域线程。
 */
public final class BukkitFurnitureInteractionManager {
    // 原版交互包使用 float 坐标，仅容忍转换时的碰撞箱边界误差。
    private static final double HIT_EPSILON = 1.0E-5;
    private volatile List<Entry> entries = List.of();
    private final PluginLogger logger;

    public BukkitFurnitureInteractionManager(PluginLogger logger) {
        this.logger = logger;
    }

    /**
     * 按优先级从高到低注册；同优先级保持注册顺序，ID 全局唯一。
     */
    public synchronized FurnitureInteractionRegistration register(Plugin owner, Key id, int priority,
                                                                 FurnitureInteractionResolver resolver) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resolver, "resolver");
        if (!owner.isEnabled()) {
            throw new IllegalStateException("Cannot register a furniture interaction resolver for a disabled plugin");
        }
        for (Entry entry : this.entries) {
            if (entry.id.equals(id)) {
                throw new IllegalArgumentException("Duplicate furniture interaction resolver: " + id);
            }
        }
        Entry entry = new Entry(owner, id, priority, resolver);
        List<Entry> next = new ArrayList<>(this.entries);
        next.add(entry);
        next.sort(Comparator.comparingInt((Entry value) -> value.priority).reversed());
        this.entries = List.copyOf(next);
        return entry;
    }

    /**
     * 移除指定插件拥有的全部注册。
     */
    public synchronized void unregister(Plugin owner) {
        for (Entry entry : this.entries) {
            if (entry.owner == owner) {
                entry.close();
            }
        }
    }

    /**
     * CE 停用时释放全部第三方引用。
     */
    public synchronized void clear() {
        for (Entry entry : this.entries) {
            entry.close();
        }
    }

    /**
     * 原子发布注销后的注册快照。
     */
    private synchronized void unregister(Entry entry) {
        entry.closed = true;
        List<Entry> next = new ArrayList<>(this.entries);
        next.remove(entry);
        this.entries = List.copyOf(next);
    }

    /**
     * 只有全部解析器明确跳过时才执行默认射线；拒绝、非法返回及异常均终止本次请求。
     */
    public Optional<EntityHitResult> resolve(FurnitureInteractionContext context) {
        for (Entry entry : this.entries) {
            if (entry.closed || !entry.owner.isEnabled()) {
                continue;
            }
            try {
                FurnitureInteractionResult result = Objects.requireNonNull(entry.resolver.resolve(context), "resolver result");
                if (result instanceof FurnitureInteractionResult.Pass) {
                    continue;
                }
                if (result instanceof FurnitureInteractionResult.Reject reject) {
                    this.logger.warn("Furniture interaction rejected by " + entry.id + ": " + reject.reason());
                    return Optional.empty();
                }
                EntityHitResult hit = ((FurnitureInteractionResult.Hit) result).hitResult();
                if (hit.direction() == null || !validPoint(context, hit.hitLocation())) {
                    this.logger.warn("Invalid furniture interaction hit from " + entry.id);
                    return Optional.empty();
                }
                return Optional.of(hit);
            } catch (RuntimeException | LinkageError exception) {
                this.logger.warn("Furniture interaction resolver failed: " + entry.id, exception);
                return Optional.empty();
            }
        }
        Vec3d eye = context.eyePosition();
        Vec3d direction = context.eyeDirection();
        double range = context.interactionRange();
        Vec3d end = eye.add(direction.x * range, direction.y * range, direction.z * range);
        return context.part().aabb().clip(eye, end);
    }

    /**
     * 第三方命中点必须属于本次部件，在玩家交互距离内且与玩家眼睛之间没有方块遮挡。
     */
    private boolean validPoint(FurnitureInteractionContext context, Vec3d point) {
        if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) {
            return false;
        }
        AABB box = context.part().aabb();
        if (point.x < box.minX - HIT_EPSILON || point.x > box.maxX + HIT_EPSILON
                || point.y < box.minY - HIT_EPSILON || point.y > box.maxY + HIT_EPSILON
                || point.z < box.minZ - HIT_EPSILON || point.z > box.maxZ + HIT_EPSILON) {
            return false;
        }
        Vec3d eye = context.eyePosition();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double range = context.interactionRange();
        if (!Double.isFinite(distance) || !Double.isFinite(range) || range <= 0 || distance > range + HIT_EPSILON) {
            return false;
        }
        if (distance <= HIT_EPSILON) {
            return true;
        }
        Location start = new Location(context.player().getWorld(), eye.x, eye.y, eye.z);
        return start.getWorld().rayTraceBlocks(start, new Vector(dx, dy, dz), distance,
                FluidCollisionMode.NEVER, true) == null;
    }

    /**
     * 一个注册项同时作为注销句柄，避免旧句柄误删同 ID 的新注册。
     */
    private final class Entry implements FurnitureInteractionRegistration {
        private final Plugin owner;
        private final Key id;
        private final int priority;
        private final FurnitureInteractionResolver resolver;
        private volatile boolean closed;

        private Entry(Plugin owner, Key id, int priority, FurnitureInteractionResolver resolver) {
            this.owner = owner;
            this.id = id;
            this.priority = priority;
            this.resolver = resolver;
        }

        @Override
        public void close() {
            BukkitFurnitureInteractionManager.this.unregister(this);
        }
    }
}
