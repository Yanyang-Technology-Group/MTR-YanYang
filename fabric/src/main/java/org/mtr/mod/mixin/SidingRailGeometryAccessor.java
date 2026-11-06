package org.mtr.mod.mixin;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = Siding.class, remap = false)
public interface SidingRailGeometryAccessor {

	@Accessor("pathSidingToMainRoute") ObjectArrayList<PathData> mtr$getOutboundPath();
	@Accessor("pathMainRoute") ObjectArrayList<PathData> mtr$getMainPath();
	@Accessor("pathMainRouteToSiding") ObjectArrayList<PathData> mtr$getInboundPath();
	@Accessor("defaultPathData") PathData mtr$getDefaultPath();
	@Accessor("defaultPathData") void mtr$setDefaultPath(PathData path);
	@Accessor("vehicles") ObjectArraySet<Vehicle> mtr$getVehicles();
	@Accessor("departures") LongArrayList mtr$getDepartures();
	@Invoker("generatePathDistancesAndTimeSegments") void mtr$generateDistancesAndTimeSegments();
}
