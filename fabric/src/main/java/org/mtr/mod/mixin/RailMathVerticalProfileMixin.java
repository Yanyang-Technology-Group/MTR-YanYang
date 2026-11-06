package org.mtr.mod.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.mod.data.RailVerticalProfile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = RailMath.class, remap = false)
public abstract class RailMathVerticalProfileMixin {

	@Shadow @Final private Rail.Shape shape;
	@Shadow @Final private long yStart;
	@Shadow @Final private long yEnd;

	@Shadow public abstract double getLength();

	@ModifyReturnValue(method = "getPositionY", at = @At("RETURN"))
	private double mtr$verticalProfile(double original, double distance) {
		return yStart != yEnd && (shape == Rail.Shape.CUBIC_PARABOLA || shape == Rail.Shape.HALF_WAVE_SINE)
				? RailVerticalProfile.getHeight(distance, getLength(), yStart, yEnd, shape == Rail.Shape.HALF_WAVE_SINE)
				: original;
	}
}
