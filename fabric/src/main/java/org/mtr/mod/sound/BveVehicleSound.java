package org.mtr.mod.sound;

import org.mtr.libraries.it.unimi.dsi.fastutil.ints.Int2ObjectAVLTreeMap;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.mtr.mapping.holder.BlockPos;
import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.SoundEvent;

import javax.annotation.Nullable;
import java.util.Random;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

public class BveVehicleSound extends VehicleSoundBase {

	private final BveVehicleSoundConfig config;
	private final DoubleSupplier secondsElapsedSupplier;

	private float oldSpeedChange;
	private boolean oldOnRoute = false;
	private double oldYaw;
	private long oldMotionMillis = -1;
	private double yawRate;

	private float motorCurrentOutput = 0;
	private float motorBreakerTimer = -1;

	private float mrPress;
	private boolean isCompressorActive;
	private boolean isCompressorActiveLastElapsed;

	private final VehicleLoopingSoundHolder vehicleLoopingSoundHolder;

	public BveVehicleSound(BveVehicleSoundConfig config) {
		this(config, VehicleLoopingSoundInstance::new, () -> MinecraftClient.getInstance().getLastFrameDuration() / 20);
	}

	BveVehicleSound(BveVehicleSoundConfig config, Function<SoundEvent, VehicleLoopingSoundInstance> soundFactory, DoubleSupplier secondsElapsedSupplier) {
		this.config = config;
		this.secondsElapsedSupplier = secondsElapsedSupplier;

		mrPress = randomInt(config.config.mrPressMin, config.config.mrPressMax + 1);
		isCompressorActive = randomInt(0, 20) == 0; // Currently set to 1/20 at client-side load
		isCompressorActiveLastElapsed = isCompressorActive;

		final VehicleLoopingSoundInstance[] soundLoopMotor = new VehicleLoopingSoundInstance[config.config.motor.length];
		for (int i = 0; i < Math.min(config.config.motor.length, config.motorData.getSoundCount()); i++) {
			if (config.config.motor[i] != null) {
				soundLoopMotor[i] = soundFactory.apply(config.config.motor[i]);
			}
		}
		vehicleLoopingSoundHolder = new VehicleLoopingSoundHolder(
				soundLoopMotor,
				createIndexedSounds(config.config.runSounds, soundFactory),
				createIndexedSounds(config.config.flangeSounds, soundFactory),
				config.config.noise == null ? null : soundFactory.apply(config.config.noise),
				config.config.shoe == null ? null : soundFactory.apply(config.config.shoe),
				config.config.compressorLoop == null ? null : soundFactory.apply(config.config.compressorLoop)
		);
	}

	@Override
	public void playMotorSound(BlockPos blockPos, float speed, float speedChange, float acceleration, boolean isOnRoute) {
		playMotorSound(blockPos, speed, speedChange, acceleration, isOnRoute, Double.NaN, 0, 0);
	}

	@Override
	public void playMotorSound(BlockPos blockPos, float speed, float speedChange, float acceleration, boolean isOnRoute, double yaw, int runSoundIndex, long gameMillis) {
		final float secondsElapsed = (float) secondsElapsedSupplier.getAsDouble();
		final float speedKilometersPerHour = speed * 3600;
		final float speedMetersPerSecond = speed * 1000;

		updateMotionSounds(blockPos, speed, yaw, runSoundIndex, gameMillis);

		// Simulation of circuit breaker in traction controller
		float motorTarget = Math.signum(speedChange);
		if (motorTarget == 0 && speedMetersPerSecond != 0) {
			motorTarget = config.config.motorOutputAtCoast;
		}
		if (motorTarget < 0 && speedMetersPerSecond < config.config.regenerationLimit) {
			motorCurrentOutput = 0; // Regeneration brake cut off below limit speed
			motorBreakerTimer = -1;
		} else if (motorTarget > 0 && speedMetersPerSecond < 1) {
			motorCurrentOutput = 1; // Disable delay at startup
			motorBreakerTimer = -1;
		} else if (motorTarget != motorCurrentOutput && motorBreakerTimer < 0) {
			motorBreakerTimer = 0;
			if (motorTarget != 0 && motorCurrentOutput != 0) {
				motorCurrentOutput = 0; // Loose behavior but sounds OK
			}
		}
		if (motorBreakerTimer >= 0) {
			motorBreakerTimer += secondsElapsed;
			if (motorBreakerTimer > config.config.breakerDelay) {
				motorBreakerTimer = -1;
				motorCurrentOutput = motorTarget;
			}
		}

		// Clamp to a minimum volume whenever the inverter/motor is active
		if (motorCurrentOutput != 0) {
			motorCurrentOutput = Math.signum(motorCurrentOutput) * (0.3f + Math.abs(motorCurrentOutput) * (1 - 0.3f));
		}

		// Simulation of main reservoir air compressor
		if (mrPress <= config.config.mrPressMin) {
			isCompressorActive = true;
			mrPress = config.config.mrPressMin;
		} else if (mrPress >= config.config.mrPressMax) {
			isCompressorActive = false;
			mrPress = config.config.mrPressMax;
		}
		if (isCompressorActive) {
			mrPress += (int) (secondsElapsed * config.config.mrCompressorSpeed);
		}
		if (vehicleLoopingSoundHolder.soundLoopCompressor != null) {
			// NOTE: Attack sound playback is not to BVE specification.
			vehicleLoopingSoundHolder.soundLoopCompressor.setData(isCompressorActive ? 1 : 0, 1, blockPos);
		}
		if (isCompressorActive && !isCompressorActiveLastElapsed) {
			playSoundInWorld(config.config.compressorAttack, blockPos);
		} else if (!isCompressorActive && isCompressorActiveLastElapsed) {
			playSoundInWorld(config.config.compressorRelease, blockPos);
		}

		// Motor noise
		for (int i = 0; i < config.motorData.getSoundCount(); i++) {
			if (vehicleLoopingSoundHolder.soundLoopMotor[i] != null) {
				vehicleLoopingSoundHolder.soundLoopMotor[i].setData(config.motorData.getVolume(i, speedKilometersPerHour, motorCurrentOutput) * config.config.motorVolumeMultiply, config.motorData.getPitch(i, speedKilometersPerHour, motorCurrentOutput), blockPos);
			}
		}

		// Brake shoe rubbing noise (below regeneration brake cutoff limit)
		if (vehicleLoopingSoundHolder.soundLoopShoe != null) {
			final float shoePitch = 1 / (speedMetersPerSecond + 1) + 1;
			float shoeGain = speedMetersPerSecond < config.config.regenerationLimit && speedChange < 0 ? 1 : 0;
			if (speedMetersPerSecond < 1.39) {
				final float t = speedMetersPerSecond * speedMetersPerSecond;
				shoeGain *= 1.5552F * t - 0.746496F * speedMetersPerSecond * t;
			} else if (speedMetersPerSecond > 12.5) {
				final float t = speedMetersPerSecond - 12.5F;
				shoeGain *= 1 / (0.1F * t * t + 1);
			}
			vehicleLoopingSoundHolder.soundLoopShoe.setData(shoeGain, shoePitch, blockPos);
		}

		// Constant loop noise
		if (vehicleLoopingSoundHolder.soundLoopNoise != null) {
			vehicleLoopingSoundHolder.soundLoopNoise.setData(isOnRoute ? 1 : 0, 1, blockPos);
		}

		// Air brake application and release noise
		if (oldSpeedChange < 0 && speedChange >= 0) {
			playSoundInWorld(config.config.brakeHandleRelease, blockPos);
			if (speedMetersPerSecond < config.config.regenerationLimit) {
				playSoundInWorld(config.config.airZero, blockPos);
			}
		} else if (oldSpeedChange <= 0 && speedChange > 0 && speedMetersPerSecond < 0.3) {
			playSoundInWorld(config.config.airHigh, blockPos);
		} else if (oldSpeedChange >= 0 && speedChange < 0) {
			mrPress -= (int) config.config.mrServiceBrakeReduce;
			playSoundInWorld(config.config.brakeHandleApply, blockPos);
		}

		// Emergency brake application after returning to depot
		if (oldOnRoute && !isOnRoute) {
			playSoundInWorld(config.config.brakeEmergency, blockPos);
		}

		oldSpeedChange = speedChange;
		oldOnRoute = isOnRoute;
		isCompressorActiveLastElapsed = isCompressorActive;
	}

	@Override
	public void updateMotionSounds(BlockPos blockPos, float speed, double yaw, int runSoundIndex, long gameMillis) {
		final float speedMetersPerSecond = Math.abs(speed) * 1000;
		updateIndexedSound(vehicleLoopingSoundHolder.soundLoopRun, runSoundIndex, Math.min(1, speedMetersPerSecond * 0.04F), speedMetersPerSecond * 0.04F, blockPos);
		updateIndexedSound(vehicleLoopingSoundHolder.soundLoopFlange, runSoundIndex, getFlangeGain(yaw, gameMillis, speedMetersPerSecond), 0.5F + speedMetersPerSecond * 0.04F, blockPos);
	}

	@Override
	protected void playDoorSound(BlockPos blockPos, boolean isOpen) {
		ScheduledSound.schedule(blockPos, isOpen ? config.config.doorOpen : config.config.doorClose, 2, 1);
	}

	@Override
	public void dispose() {
		vehicleLoopingSoundHolder.dispose();
	}

	@Override
	protected double getDoorCloseSoundTime() {
		return config.config.doorCloseSoundLength;
	}

	private static void playSoundInWorld(@Nullable SoundEvent soundEvent, BlockPos blockPos) {
		ScheduledSound.schedule(blockPos, soundEvent, 1, 1);
	}

	private static int randomInt(int minInclusive, int maxExclusive) {
		return new Random().nextInt(maxExclusive - minInclusive) + minInclusive;
	}

	private float getFlangeGain(double yaw, long gameMillis, float speedMetersPerSecond) {
		if (!Double.isFinite(yaw)) {
			oldMotionMillis = -1;
			yawRate = 0;
			return 0;
		}
		if (gameMillis != oldMotionMillis) {
			final long millisElapsed = gameMillis - oldMotionMillis;
			// Reseed after a visibility gap; the unseen rotation is not this frame's turn.
			if (oldMotionMillis >= 0 && millisElapsed > 0 && millisElapsed <= 1000) {
				final double deltaYaw = Math.atan2(Math.sin(yaw - oldYaw), Math.cos(yaw - oldYaw));
				yawRate = Math.abs(deltaYaw) * 1000 / millisElapsed;
			} else {
				yawRate = 0;
			}
			oldYaw = yaw;
			oldMotionMillis = gameMillis;
		}
		// Yaw rate (rad/s) times speed (m/s) gives lateral acceleration.
		return (float) Math.min(0.75, yawRate * Math.abs(speedMetersPerSecond) * 0.5);
	}

	private static Int2ObjectAVLTreeMap<VehicleLoopingSoundInstance> createIndexedSounds(Int2ObjectAVLTreeMap<SoundEvent> sounds, Function<SoundEvent, VehicleLoopingSoundInstance> soundFactory) {
		final Int2ObjectAVLTreeMap<VehicleLoopingSoundInstance> instances = new Int2ObjectAVLTreeMap<>();
		for (final Int2ObjectMap.Entry<SoundEvent> entry : sounds.int2ObjectEntrySet()) {
			instances.put(entry.getIntKey(), soundFactory.apply(entry.getValue()));
		}
		return instances;
	}

	private static void updateIndexedSound(Int2ObjectAVLTreeMap<VehicleLoopingSoundInstance> sounds, int index, float gain, float pitch, BlockPos blockPos) {
		if (!sounds.isEmpty()) {
			final int selectedIndex = sounds.containsKey(index) ? index : sounds.firstIntKey();
			for (final Int2ObjectMap.Entry<VehicleLoopingSoundInstance> entry : sounds.int2ObjectEntrySet()) {
				entry.getValue().setData(entry.getIntKey() == selectedIndex ? gain : 0, pitch, blockPos);
			}
		}
	}

	private static class VehicleLoopingSoundHolder {

		private final VehicleLoopingSoundInstance[] soundLoopMotor;
		private final Int2ObjectAVLTreeMap<VehicleLoopingSoundInstance> soundLoopRun;
		private final Int2ObjectAVLTreeMap<VehicleLoopingSoundInstance> soundLoopFlange;
		@Nullable
		private final VehicleLoopingSoundInstance soundLoopNoise;
		@Nullable
		private final VehicleLoopingSoundInstance soundLoopShoe;
		@Nullable
		private final VehicleLoopingSoundInstance soundLoopCompressor;

		private VehicleLoopingSoundHolder(
				VehicleLoopingSoundInstance[] soundLoopMotor,
				Int2ObjectAVLTreeMap<VehicleLoopingSoundInstance> soundLoopRun,
				Int2ObjectAVLTreeMap<VehicleLoopingSoundInstance> soundLoopFlange,
				@Nullable VehicleLoopingSoundInstance soundLoopNoise,
				@Nullable VehicleLoopingSoundInstance soundLoopShoe,
				@Nullable VehicleLoopingSoundInstance soundLoopCompressor
		) {
			this.soundLoopMotor = soundLoopMotor;
			this.soundLoopRun = soundLoopRun;
			this.soundLoopFlange = soundLoopFlange;
			this.soundLoopNoise = soundLoopNoise;
			this.soundLoopShoe = soundLoopShoe;
			this.soundLoopCompressor = soundLoopCompressor;
		}

		public void dispose() {
			for (VehicleLoopingSoundInstance instance : soundLoopMotor) {
				if (instance != null) {
					instance.dispose();
				}
			}
			soundLoopRun.values().forEach(VehicleLoopingSoundInstance::dispose);
			soundLoopFlange.values().forEach(VehicleLoopingSoundInstance::dispose);
			if (soundLoopNoise != null) {
				soundLoopNoise.dispose();
			}
			if (soundLoopShoe != null) {
				soundLoopShoe.dispose();
			}
			if (soundLoopCompressor != null) {
				soundLoopCompressor.dispose();
			}
		}
	}
}
