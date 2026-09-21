package org.mtr.mod.mixin;

import org.mtr.core.data.Data;
import org.mtr.mod.data.ServerRailPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Data.class, remap = false)
public abstract class DataPathEpochMixin {
	@Inject(method = "sync", at = @At("HEAD"))
	private void mtr$invalidatePaths(CallbackInfo ci) {
		ServerRailPaths.invalidate((Data) (Object) this);
	}
}
