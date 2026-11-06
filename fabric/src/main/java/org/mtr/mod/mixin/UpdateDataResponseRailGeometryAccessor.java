package org.mtr.mod.mixin;

import org.mtr.core.data.Siding;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = UpdateDataResponse.class, remap = false)
public interface UpdateDataResponseRailGeometryAccessor {

	@Invoker("getSidings") ObjectArrayList<Siding> mtr$getSidings();
}
