package io.github.saboooor.lumifollow;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Door;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WallClimberTest {
    private final World world = mock(World.class);
    private final LivingEntity entity = mock(LivingEntity.class);
    private final Player target = mock(Player.class);
    private final WallClimber climber = new WallClimber();
    private boolean ceiling;

    private void terrain(int wallHeight) {
        when(world.getMaxHeight()).thenReturn(320);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            int x = call.getArgument(0), y = call.getArgument(1), z = call.getArgument(2);
            boolean solid = y < 64 || (x == 1 && y < 64 + wallHeight)
                    || (ceiling && y == 66 && x == 0);
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(solid ? Material.STONE : Material.AIR);
            when(block.isPassable()).thenReturn(!solid);
            when(block.getLocation()).thenReturn(new Location(world, x, y, z));
            return block;
        });
        when(world.getBlockAt(any(Location.class))).thenAnswer(call -> {
            Location sample = call.getArgument(0);
            return world.getBlockAt(sample.getBlockX(), sample.getBlockY(), sample.getBlockZ());
        });
        when(entity.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        when(entity.getVelocity()).thenReturn(new Vector());
        when(entity.isValid()).thenReturn(true);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(new Location(world, 4, 67, 0.5));
        when(target.isValid()).thenReturn(true);
    }

    @Test void climbsWallAndResumesAfterReachingTheLedge() {
        terrain(3);
        assertTrue(climber.tick(entity, target, 6, 100));
        verify(entity).setVelocity(new Vector(0, 0.25, 0));
        when(entity.getLocation()).thenReturn(new Location(world, 0.5, 67.2, 0.5));
        assertTrue(climber.tick(entity, target, 6, 100));
        when(entity.getLocation()).thenReturn(new Location(world, 1.5, 67.1, 0.5));
        assertFalse(climber.tick(entity, target, 6, 100));
    }

    @Test void cancelsNavigationBeforeApplyingClimbingVelocity() {
        terrain(3);
        Runnable cancel = mock(Runnable.class);
        assertTrue(climber.tick(entity, target, 6, 100, cancel));
        var order = inOrder(cancel, entity);
        order.verify(cancel).run();
        order.verify(entity).setVelocity(new Vector(0, 0.25, 0));
    }

    @Test void failedPathStillApproachesAWallAcrossClearGround() {
        terrain(3);
        when(entity.getLocation()).thenReturn(new Location(world, -1.5, 64, 0.5));
        when(entity.isOnGround()).thenReturn(true);
        when(entity.getVelocity()).thenReturn(new Vector());
        climber.approachWhenNoPath(entity, target);
        verify(entity).setVelocity(new Vector(0.18, 0, 0));
    }

    @Test void refusesWallsAboveTheConfiguredHeight() {
        terrain(7);
        assertFalse(climber.tick(entity, target, 6, 100));
        verify(entity, never()).setVelocity(any());
    }

    @Test void refusesToClimbThroughACeiling() {
        terrain(3);
        ceiling = true;
        assertFalse(climber.tick(entity, target, 6, 100));
        verify(entity, never()).setVelocity(any());
    }

    @Test void abandonsClimbingWhenTimeRunsOut() {
        terrain(3);
        assertTrue(climber.tick(entity, target, 6, 1));
        assertFalse(climber.tick(entity, target, 6, 1));
        assertFalse(climber.tick(entity, target, 6, 1));
    }

    @Test void dropsDownToPlayerWhenOnHigherBlocks() {
        terrain(3);
        when(entity.getLocation()).thenReturn(new Location(world, 1.5, 67, 0.5));
        when(target.getLocation()).thenReturn(new Location(world, 4, 64, 0.5));
        assertTrue(climber.tick(entity, target, 6, 100));
        verify(entity).setVelocity(new Vector(0.22, -0.15, 0));
        verify(entity).setFallDistance(0);

        // While falling in air
        when(entity.getLocation()).thenReturn(new Location(world, 2.0, 65.5, 0.5));
        when(entity.isOnGround()).thenReturn(false);
        assertTrue(climber.tick(entity, target, 6, 100));

        // When landed on ground at lower level
        when(entity.getLocation()).thenReturn(new Location(world, 2.5, 64, 0.5));
        when(entity.isOnGround()).thenReturn(true);
        assertFalse(climber.tick(entity, target, 6, 100));
    }

    @Test void approachWhenNoPathMovesAcrossLedgeTowardsDrop() {
        terrain(3);
        when(entity.getLocation()).thenReturn(new Location(world, 1.5, 67, 0.5));
        when(target.getLocation()).thenReturn(new Location(world, 4, 64, 0.5));
        when(entity.isOnGround()).thenReturn(true);
        climber.approachWhenNoPath(entity, target);
        verify(entity).setVelocity(new Vector(0.18, 0, 0));
    }

    @Test void approachWhenNoPathDropsDownOffLedgeWhenTargetIsBelow() {
        terrain(3);
        when(entity.getLocation()).thenReturn(new Location(world, 1.9, 67, 0.5));
        when(target.getLocation()).thenReturn(new Location(world, 4, 64, 0.5));
        when(entity.isOnGround()).thenReturn(true);
        climber.approachWhenNoPath(entity, target);
        verify(entity).setVelocity(new Vector(0.18, -0.15, 0));
        verify(entity).setFallDistance(0);
    }

    @Test void opensDoorInWayWhenApproachingTarget() {
        terrain(3);
        when(entity.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        when(target.getLocation()).thenReturn(new Location(world, 3.5, 64, 0.5));

        Block doorBottom = mock(Block.class);
        Block doorTop = mock(Block.class);
        Door bottomData = mock(Door.class);
        Door topData = mock(Door.class);

        java.util.concurrent.atomic.AtomicBoolean bottomOpen = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean topOpen = new java.util.concurrent.atomic.AtomicBoolean(false);

        when(bottomData.isOpen()).thenAnswer(i -> bottomOpen.get());
        doAnswer(i -> { bottomOpen.set(i.getArgument(0)); return null; }).when(bottomData).setOpen(anyBoolean());
        when(bottomData.getHalf()).thenReturn(Bisected.Half.BOTTOM);
        when(doorBottom.getBlockData()).thenReturn(bottomData);
        when(doorBottom.getType()).thenReturn(Material.OAK_DOOR);
        when(doorBottom.getLocation()).thenReturn(new Location(world, 1, 64, 0));
        when(doorBottom.getRelative(BlockFace.UP)).thenReturn(doorTop);

        when(topData.isOpen()).thenAnswer(i -> topOpen.get());
        doAnswer(i -> { topOpen.set(i.getArgument(0)); return null; }).when(topData).setOpen(anyBoolean());
        when(topData.getHalf()).thenReturn(Bisected.Half.TOP);
        when(doorTop.getBlockData()).thenReturn(topData);
        when(doorTop.getType()).thenReturn(Material.OAK_DOOR);
        when(doorTop.getLocation()).thenReturn(new Location(world, 1, 65, 0));
        when(doorTop.getRelative(BlockFace.DOWN)).thenReturn(doorBottom);

        when(world.getBlockAt(eq(1), eq(64), eq(0))).thenReturn(doorBottom);
        when(world.getBlockAt(eq(1), eq(65), eq(0))).thenReturn(doorTop);

        assertTrue(climber.openDoorsInWay(entity, target));
        verify(bottomData).setOpen(true);
        verify(doorBottom).setBlockData(bottomData, true);
        verify(topData).setOpen(true);
        verify(doorTop).setBlockData(topData, true);
        verify(entity).swingMainHand();
    }

    @Test void doesNotClimbClosedDoorsAsWalls() {
        terrain(3);
        when(entity.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        when(target.getLocation()).thenReturn(new Location(world, 3.5, 64, 0.5));

        Block doorBottom = mock(Block.class);
        Door bottomData = mock(Door.class);
        when(bottomData.isOpen()).thenReturn(false);
        when(doorBottom.getBlockData()).thenReturn(bottomData);
        when(doorBottom.getType()).thenReturn(Material.IRON_DOOR);
        when(doorBottom.getLocation()).thenReturn(new Location(world, 1, 64, 0));
        when(world.getBlockAt(eq(1), eq(64), eq(0))).thenReturn(doorBottom);

        assertFalse(climber.tick(entity, target, 6, 100));
        verify(bottomData).setOpen(true);
        verify(doorBottom).setBlockData(bottomData, true);
        verify(entity).swingMainHand();
    }

    @Test void doesNotReopenAlreadyOpenDoors() {
        terrain(3);
        when(entity.getLocation()).thenReturn(new Location(world, 0.5, 64, 0.5));
        when(target.getLocation()).thenReturn(new Location(world, 3.5, 64, 0.5));

        Block doorBottom = mock(Block.class);
        Door bottomData = mock(Door.class);
        when(bottomData.isOpen()).thenReturn(true);
        when(doorBottom.getBlockData()).thenReturn(bottomData);
        when(doorBottom.getType()).thenReturn(Material.OAK_DOOR);
        when(world.getBlockAt(eq(1), eq(64), eq(0))).thenReturn(doorBottom);

        assertFalse(climber.openDoorsInWay(entity, target));
        verify(bottomData, never()).setOpen(anyBoolean());
    }
}
