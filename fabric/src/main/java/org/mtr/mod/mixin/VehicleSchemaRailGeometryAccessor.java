package org.mtr.mod.mixin;

import org.mtr.core.generated.data.VehicleSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VehicleSchema.class, remap = false)
public interface VehicleSchemaRailGeometryAccessor {

	@Accessor("railProgress") double mtr$getRailProgress();
	@Accessor("railProgress") void mtr$setRailProgress(double progress);
	@Accessor("departureIndex") void mtr$setDepartureIndex(long index);
}
