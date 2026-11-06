package org.mtr.mod.mixin;

import org.mtr.core.data.Vehicle;
import org.mtr.core.data.VehicleExtraData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = Vehicle.class, remap = false)
public interface VehicleRailGeometryAccessor {

	@Mutable
	@Accessor("vehicleExtraData") void mtr$setExtraData(VehicleExtraData data);
	@Accessor("stoppingCooldown") void mtr$setStoppingCooldown(long cooldown);
	@Accessor("deviationSpeedAdjustment") void mtr$setDeviationSpeedAdjustment(double adjustment);
	@Invoker("updateDeviation") void mtr$updateDeviation();
}
