package org.mtr.render;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import org.jspecify.annotations.Nullable;
import org.mtr.core.data.Passenger;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

import java.util.Random;
import java.util.UUID;

/**
 * Caches render-side state for AI passengers so that passenger entities and deterministic
 * standing positions are not recomputed (and reallocated) every frame.
 *
 * <p>Two caches are maintained:</p>
 * <ul>
 *   <li><b>Entity cache</b> — {@link RemotePlayer} construction is expensive (entity id
 *       allocation, profile handling, game logic initialisation) and was previously done
 *       per passenger per frame in both {@link RenderPassengers} and {@link RenderVehicles}.
 *       Entities are keyed by passenger id; the whole cache is invalidated when the client
 *       level changes because entities hold a reference to their level.</li>
 *   <li><b>Vehicle standing position cache</b> — the on-board position of a passenger is
 *       derived deterministically from a {@link Random} seeded with the passenger id. That
 *       random sequence was previously replayed every frame; instead the resulting offsets
 *       are cached per passenger and invalidated when the floor list instance changes
 *       (for example after a resource reload).</li>
 * </ul>
 *
 * <p>Both caches are bounded (LRU) and only accessed from the render thread.</p>
 */
public final class PassengerRenderCache {

	private static final int ENTITY_CACHE_LIMIT = 4096;
	private static final int POSITION_CACHE_LIMIT = 4096;

	private static final Long2ObjectLinkedOpenHashMap<CachedEntity> entityCache = new Long2ObjectLinkedOpenHashMap<>();
	private static final Long2ObjectLinkedOpenHashMap<CachedVehiclePosition> vehiclePositionCache = new Long2ObjectLinkedOpenHashMap<>();

	@Nullable
	private static ClientLevel cachedLevel;

	/**
	 * Get (or create) the render entity for a passenger. Must only be called on the render
	 * thread.
	 *
	 * @param clientLevel the current client level; a different instance invalidates the cache
	 * @param passenger   the passenger to render
	 * @return a cached {@link RemotePlayer} for the passenger
	 */
	public static RemotePlayer getEntity(ClientLevel clientLevel, Passenger passenger) {
		if (clientLevel != cachedLevel) {
			entityCache.clear();
			cachedLevel = clientLevel;
		}

		final long passengerId = passenger.getId();
		final CachedEntity cachedEntity = entityCache.getAndMoveToFirst(passengerId);
		if (cachedEntity != null && cachedEntity.name.equals(passenger.getName())) {
			return cachedEntity.player;
		}

		final RemotePlayer player = new RemotePlayer(clientLevel, new GameProfile(new UUID(passengerId, 0), passenger.getName()));
		entityCache.putAndMoveToFirst(passengerId, new CachedEntity(player, passenger.getName()));
		while (entityCache.size() > ENTITY_CACHE_LIMIT) {
			entityCache.removeLast();
		}
		return player;
	}

	/**
	 * Get (or compute) the deterministic on-board standing position of a passenger. The
	 * position only depends on the passenger id and the floor layout, so it is cached until
	 * the floor list instance changes.
	 *
	 * @param passengerId the passenger id, used as the deterministic random seed
	 * @param floorsKey   an object identifying the floor layout (compared by identity)
	 * @param computer    computes the position from a fresh deterministic random; may return
	 *                    {@code null} if there is no valid position
	 * @return the cached position, or {@code null} if the computer returned {@code null}
	 */
	@Nullable
	public static VehiclePosition getVehiclePosition(long passengerId, Object floorsKey, PositionComputer computer) {
		final CachedVehiclePosition cachedPosition = vehiclePositionCache.getAndMoveToFirst(passengerId);
		if (cachedPosition != null && cachedPosition.floorsKey == floorsKey) {
			return cachedPosition.position;
		}

		final VehiclePosition position = computer.compute(new Random(passengerId));
		if (position == null) {
			return null;
		}

		vehiclePositionCache.putAndMoveToFirst(passengerId, new CachedVehiclePosition(floorsKey, position));
		while (vehiclePositionCache.size() > POSITION_CACHE_LIMIT) {
			vehiclePositionCache.removeLast();
		}
		return position;
	}

	/**
	 * Clears all cached state.
	 */
	public static void clear() {
		entityCache.clear();
		vehiclePositionCache.clear();
		cachedLevel = null;
	}

	@FunctionalInterface
	public interface PositionComputer {
		@Nullable
		VehiclePosition compute(Random random);
	}

	public record VehiclePosition(double x, double y, double z, double yawOffset) {
	}

	private record CachedEntity(RemotePlayer player, String name) {
	}

	private record CachedVehiclePosition(Object floorsKey, VehiclePosition position) {
	}
}
