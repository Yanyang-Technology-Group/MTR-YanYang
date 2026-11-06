package org.mtr.mod.mixin;

import org.mtr.core.data.Data;
import org.mtr.mod.data.RailGeometryRefresh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Data.class, remap = false)
public abstract class DataRailGeometryMixin {

	@Inject(method = "sync()V", at = @At("RETURN"), remap = false)
	private void mtr$repairSidingLengths(CallbackInfo callbackInfo) {
		RailGeometryRefresh.refreshSidingLengths((Data) (Object) this);
	}
}
