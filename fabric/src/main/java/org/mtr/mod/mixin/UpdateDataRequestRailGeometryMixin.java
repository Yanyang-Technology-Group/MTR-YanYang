package org.mtr.mod.mixin;

import org.mtr.core.data.Data;
import org.mtr.core.generated.operation.UpdateDataRequestSchema;
import org.mtr.core.operation.UpdateDataRequest;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.mod.data.RailGeometryRefresh;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = UpdateDataRequest.class, remap = false)
public abstract class UpdateDataRequestRailGeometryMixin extends UpdateDataRequestSchema {

	@Shadow @Final private Data data;
	@Unique private RailGeometryRefresh mtr$geometryRefresh;

	@Inject(method = "update", at = @At("HEAD"), remap = false)
	private void mtr$captureGeometry(CallbackInfoReturnable<UpdateDataResponse> callbackInfo) {
		mtr$geometryRefresh = RailGeometryRefresh.capture(data, rails);
	}

	@Inject(method = "update", at = @At("RETURN"), remap = false)
	private void mtr$refreshGeometry(CallbackInfoReturnable<UpdateDataResponse> callbackInfo) {
		if (mtr$geometryRefresh != null) {
			mtr$geometryRefresh.apply(callbackInfo.getReturnValue());
			mtr$geometryRefresh = null;
		}
	}
}
