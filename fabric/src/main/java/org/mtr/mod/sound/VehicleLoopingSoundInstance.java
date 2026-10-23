package org.mtr.mod.sound;

import net.minecraft.client.sound.WeightedSoundSet;
import org.mtr.mapping.holder.*;
import org.mtr.mapping.mapper.MovingSoundInstanceExtension;

import java.util.function.Supplier;

public class VehicleLoopingSoundInstance extends MovingSoundInstanceExtension {

	private final Supplier<SoundManager> soundManagerSupplier;
	private final Supplier<Vector3d> listenerPositionSupplier;
	private WeightedSoundSet soundSet;
	private double playbackDistance = -1;
	private boolean capturePlaybackDistance;
	private boolean stopping;

	public VehicleLoopingSoundInstance(SoundEvent event) {
		this(event, () -> MinecraftClient.getInstance().getSoundManager(), () -> MinecraftClient.getInstance().getGameRendererMapped().getCamera().getPos());
	}

	VehicleLoopingSoundInstance(SoundEvent event, Supplier<SoundManager> soundManagerSupplier, Supplier<Vector3d> listenerPositionSupplier) {
		super(event, SoundCategory.getBlocksMapped());
		this.soundManagerSupplier = soundManagerSupplier;
		this.listenerPositionSupplier = listenerPositionSupplier;
		setIsRepeatableMapped(true);
		setRepeatDelay(0);
		setVolume(0);
		setPitch(1);
	}

	public void setData(float volume, float pitch, BlockPos blockPos) {
		if (isDone()) {
			return;
		}
		setPitch(pitch == 0 ? 1 : pitch);
		setVolume(volume);
		setX(blockPos.getX());
		setY(blockPos.getY());
		setZ(blockPos.getZ());

		final SoundManager soundManager = soundManagerSupplier.get();
		final SoundInstance instance = new SoundInstance(this);
		final boolean playing = soundManager.isPlaying(instance);
		if (!playing && (stopping || playbackDistance >= 0)) {
			// Minecraft removes a stopped source asynchronously. Keep its sample and
			// range until removal, then resolve a fresh sample for the next playback.
			stopping = false;
			soundSet = null;
			playbackDistance = -1;
		}
		if (stopping) {
			stop(soundManager);
			return;
		}
		if (volume <= 0 || !isAudible(soundManager, playing)) {
			if (playing) {
				stop(soundManager);
			}
		} else if (!playing) {
			setIsRepeatableMapped(true);
			capturePlaybackDistance = true;
			try {
				soundManager.play(instance);
			} finally {
				capturePlaybackDistance = false;
			}
			if (!soundManager.isPlaying(instance)) {
				playbackDistance = -1;
			}
		}
	}

	@Override
	public void tick2() {
		final SoundManager soundManager = soundManagerSupplier.get();
		if (stopping) {
			stop(soundManager);
			return;
		}
		// A visually occluded car can still be audible. Do not expire it merely
		// because rendering stopped calling setData; check the listener's distance.
		if (!isAudible(soundManager, soundManager.isPlaying(new SoundInstance(this)))) {
			stop(soundManager);
		}
	}

	@Override
	public float getVolume2() {
		final float volume = super.getVolume2();
		if (capturePlaybackDistance) {
			// SoundSystem reads this gain synchronously during play, before allocating
			// the source. Later ticks change gain but never change its attenuation range.
			playbackDistance = Math.max(1, volume) * getSound().getAttenuation();
			capturePlaybackDistance = false;
		}
		return volume;
	}

	@Override
	public boolean shouldAlwaysPlay2() {
		return false;
	}

	@Override
	public boolean canPlay2() {
		return !isDone();
	}

	public void dispose() {
		setDone2();
		stop(soundManagerSupplier.get());
	}

	@Override
	public WeightedSoundSet getSoundSet(net.minecraft.client.sound.SoundManager soundManager) {
		// Resolve once for the range check and playback: choosing a second random
		// sample could give the actual source a different attenuation distance.
		// Resource reloads replace the set, so inactive loops must check its identity too.
		if (soundSet == null || soundSet != soundManager.get(getId())) {
			soundSet = super.getSoundSet(soundManager);
		}
		return soundSet;
	}

	private boolean isAudible(SoundManager soundManager, boolean playing) {
		if (getSoundSet(soundManager.data) == null) {
			return false;
		}
		final float volume = getVolume();
		if (volume <= 0) {
			return false;
		}
		if (isRelative() || getAttenuationType() == net.minecraft.client.sound.SoundInstance.AttenuationType.NONE) {
			return true;
		}
		final Vector3d listener = listenerPositionSupplier.get();
		final double dx = getX() - listener.getXMapped();
		final double dy = getY() - listener.getYMapped();
		final double dz = getZ() - listener.getZMapped();
		// Match Minecraft's source range, including sounds.json volume and attenuation.
		final double distance = playing && playbackDistance >= 0 ? playbackDistance : Math.max(1, volume) * getSound().getAttenuation();
		return dx * dx + dy * dy + dz * dz < distance * distance;
	}

	private void stop(SoundManager soundManager) {
		stopping = true;
		// A stop on an AL_INITIAL source has no effect. Retry while registered:
		// Minecraft's asynchronous buffer load may still start it after the first stop.
		soundManager.stop(new SoundInstance(this));
	}
}
