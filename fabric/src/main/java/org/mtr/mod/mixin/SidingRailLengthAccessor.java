package org.mtr.mod.mixin;

import org.mtr.core.generated.data.SidingSchema;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SidingSchema.class, remap = false)
public interface SidingRailLengthAccessor {

	@Mutable
	@Accessor("railLength")
	void mtr$setRailLength(double length);
}
