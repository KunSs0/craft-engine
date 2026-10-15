package net.momirealms.craftengine.bukkit.entity.furniture;

import net.momirealms.craftengine.bukkit.api.furniture.FurnitureInteractionContext;
import net.momirealms.craftengine.bukkit.api.furniture.FurnitureInteractionRegistration;
import net.momirealms.craftengine.bukkit.api.furniture.FurnitureInteractionResult;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitboxPart;
import net.momirealms.craftengine.core.entity.player.InteractionHand;
import net.momirealms.craftengine.core.plugin.logger.PluginLogger;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.EntityHitResult;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.core.world.collision.AABB;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证默认射线、第三方裁决边界和注册生命周期，不启动服务器。
 */
class BukkitFurnitureInteractionManagerTest {
    @Test
    void preservesDefaultRayWithoutProvidersOrWhenAllPass() {
        Fixture fixture = new Fixture();
        assertEquals(2.5, fixture.manager.resolve(fixture.context(0)).orElseThrow().hitLocation().z);
        fixture.manager.register(fixture.owner, Key.of("test:pass"), 0, context -> FurnitureInteractionResult.PASS);
        assertEquals(2.5, fixture.manager.resolve(fixture.context(0)).orElseThrow().hitLocation().z);
        assertTrue(fixture.manager.resolve(fixture.context(1)).isEmpty());
    }

    @Test
    void acceptsOffAxisHitAndRespectsPriorityAndRegistrationOrder() {
        Fixture fixture = new Fixture();
        List<String> calls = new ArrayList<>();
        fixture.manager.register(fixture.owner, Key.of("test:low"), 0, context -> {
            fail("A resolved request must not reach a lower priority resolver");
            return FurnitureInteractionResult.PASS;
        });
        fixture.manager.register(fixture.owner, Key.of("test:first"), 10, context -> {
            calls.add("first");
            return FurnitureInteractionResult.PASS;
        });
        fixture.manager.register(fixture.owner, Key.of("test:second"), 10, context -> {
            calls.add("second");
            return new FurnitureInteractionResult.Hit(fixture.hit(1, 0.5, 2.5));
        });
        assertEquals(1, fixture.manager.resolve(fixture.context(1)).orElseThrow().hitLocation().x);
        assertEquals(List.of("first", "second"), calls);
    }

    @Test
    void rejectionExceptionAndNullNeverInvokeDefaultOrNextResolver() {
        Fixture fixture = new Fixture();
        FurnitureInteractionRegistration registration = fixture.manager.register(fixture.owner, Key.of("test:reject"), 1,
                context -> new FurnitureInteractionResult.Reject("denied"));
        fixture.manager.register(fixture.owner, Key.of("test:next"), 0, context -> {
            fail("Rejected requests must stop");
            return FurnitureInteractionResult.PASS;
        });
        assertTrue(fixture.manager.resolve(fixture.context(0)).isEmpty());
        registration.close();
        registration = fixture.manager.register(fixture.owner, Key.of("test:error"), 1, context -> {
            throw new IllegalStateException("broken provider");
        });
        assertTrue(fixture.manager.resolve(fixture.context(0)).isEmpty());
        registration.close();
        fixture.manager.register(fixture.owner, Key.of("test:null"), 1, context -> null);
        assertTrue(fixture.manager.resolve(fixture.context(0)).isEmpty());
        assertEquals(3, fixture.warnings.size());
    }

    @Test
    void validatesFiniteCoordinatesPartBoundsRangeAndObstruction() {
        Fixture fixture = new Fixture();
        double[][] invalid = {{Double.NaN, 0.5, 2.5}, {0, 0.5, Double.POSITIVE_INFINITY}, {2, 0.5, 2.5}};
        for (double[] point : invalid) {
            try (FurnitureInteractionRegistration ignored = fixture.manager.register(fixture.owner, Key.of("test:hit"), 0,
                    context -> new FurnitureInteractionResult.Hit(fixture.hit(point[0], point[1], point[2])))) {
                assertTrue(fixture.manager.resolve(fixture.context(0)).isEmpty());
            }
        }
        fixture.manager.register(fixture.owner, Key.of("test:hit"), 0,
                context -> new FurnitureInteractionResult.Hit(fixture.hit(0, 0.5, 2.5)));
        fixture.range = 2;
        assertTrue(fixture.manager.resolve(fixture.context(0)).isEmpty());
        fixture.range = 4.5;
        fixture.obstructed = true;
        assertTrue(fixture.manager.resolve(fixture.context(0)).isEmpty());
        fixture.obstructed = false;
        assertTrue(fixture.manager.resolve(fixture.context(0)).isPresent());
    }

    @Test
    void unregistersByOwnerAndOldHandleCannotRemoveReplacement() {
        Fixture fixture = new Fixture();
        Key id = Key.of("test:resolver");
        FurnitureInteractionRegistration old = fixture.manager.register(fixture.owner, id, 0,
                context -> new FurnitureInteractionResult.Reject("old"));
        assertThrows(IllegalArgumentException.class, () -> fixture.manager.register(fixture.owner, id, 0,
                context -> FurnitureInteractionResult.PASS));
        old.close();
        fixture.manager.register(fixture.owner, id, 0, context -> new FurnitureInteractionResult.Reject("new"));
        old.close();
        assertTrue(fixture.manager.resolve(fixture.context(0)).isEmpty());
        fixture.manager.unregister(fixture.owner);
        assertTrue(fixture.manager.resolve(fixture.context(0)).isPresent());
        fixture.manager.register(fixture.owner, id, 0, context -> new FurnitureInteractionResult.Reject("new"));
        fixture.manager.clear();
        assertTrue(fixture.manager.resolve(fixture.context(0)).isPresent());
        fixture.enabled = false;
        assertThrows(IllegalStateException.class, () -> fixture.manager.register(fixture.owner, id, 0,
                context -> FurnitureInteractionResult.PASS));
    }

    private static final class Fixture {
        private final List<String> warnings = new ArrayList<>();
        private boolean enabled = true;
        private boolean obstructed;
        private double range = 4.5;
        private final Plugin owner = proxy(Plugin.class, (object, method, arguments) -> {
            if (method.getName().equals("isEnabled")) {
                return this.enabled;
            }
            throw new UnsupportedOperationException(method.toString());
        });
        private final World world = proxy(World.class, (object, method, arguments) -> {
            if (method.getName().equals("rayTraceBlocks")) {
                return this.obstructed ? new RayTraceResult(new Vector(0, 0.5, 1)) : null;
            }
            throw new UnsupportedOperationException(method.toString());
        });
        private final Player player = proxy(Player.class, (object, method, arguments) -> {
            if (method.getName().equals("getWorld")) {
                return this.world;
            }
            throw new UnsupportedOperationException(method.toString());
        });
        private final BukkitFurnitureInteractionManager manager = new BukkitFurnitureInteractionManager(new PluginLogger() {
            public void info(String message) {}
            public void warn(String message) { warnings.add(message); }
            public void warn(String message, Throwable exception) { warnings.add(message); }
            public void error(String message) { fail(message); }
            public void error(String message, Throwable exception) { fail(message, exception); }
        });

        private FurnitureInteractionContext context(double x) {
            FurnitureHitboxPart part = new FurnitureHitboxPart(1, new AABB(x - 0.25, 0, 2.5, x + 0.25, 1, 3.5),
                    new Vec3d(x, 0, 3), true);
            return new FurnitureInteractionContext(this.player, null, null, part, InteractionHand.MAIN_HAND, false,
                    new Vec3d(0, 0.5, -0.5), new Vec3d(0, 0.5, 0), new Vec3d(0, 0, 1), this.range);
        }

        private EntityHitResult hit(double x, double y, double z) {
            return new EntityHitResult(Direction.NORTH, new Vec3d(x, y, z));
        }

        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
        }
    }
}
