package org.mtr.mod.mixin;

import org.mtr.core.data.VehicleExtraData;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.mod.data.RailGeometryRefresh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VehicleExtraData.class, remap = false)
public abstract class VehicleExtraDataRailGeometryMixin implements RailGeometryRefresh.UpdateFlag {

	@Unique private boolean mtr$geometryChanged;
	@Unique private boolean mtr$sendFullPath;

	@Override
	public void mtr$markGeometryChanged() {
		mtr$geometryChanged = true;
	}

	@Inject(method = "checkForUpdate", at = @At("RETURN"), cancellable = true, remap = false)
	private void mtr$forceGeometryUpdate(CallbackInfoReturnable<Boolean> callbackInfo) {
		mtr$sendFullPath = mtr$geometryChanged;
		if (mtr$geometryChanged) {
			mtr$geometryChanged = false;
			callbackInfo.setReturnValue(true);
		}
	}

	@Inject(method = "copy", at = @At("HEAD"), cancellable = true, remap = false)
	private void mtr$copyFullGeometry(int pathUpdateIndex, CallbackInfoReturnable<VehicleExtraData> callbackInfo) {
		if (mtr$sendFullPath) {
			callbackInfo.setReturnValue(new VehicleExtraData(new JsonReader(Utilities.getJsonObjectFromData((VehicleExtraData) (Object) this))));
		}
	}
}
