package org.mtr.mod.mixin;

import org.mtr.core.data.*;
import org.mtr.core.path.SidingPathFinder;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.data.ServerRailPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

@Mixin(value = SidingPathFinder.class, remap = false)
public abstract class SidingPathFinderMixin implements ServerRailPaths.FinderAccess {
	@Unique private Data mtr$data;
	@Unique private ServerRailPaths.Request mtr$request;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void mtr$captureData(Data data, SavedRailBase<?, ?> start, SavedRailBase<?, ?> end, int stopIndex, CallbackInfo ci) {
		mtr$data = data;
	}

	@Inject(method = "findPathTick", at = @At("HEAD"), cancellable = true)
	private static <T extends AreaBase<T, U>, U extends SavedRailBase<U, T>, V extends AreaBase<V, W>, W extends SavedRailBase<W, V>> void mtr$batchPaths(
		ObjectArrayList<PathData> path, ObjectArrayList<SidingPathFinder<T, U, V, W>> finders, long altitude, Runnable success, BiConsumer<U, W> fail, CallbackInfo ci) {
		if (ServerRailPaths.process(path, finders, success, fail)) ci.cancel();
	}

	@Override public Data mtr$getData() { return mtr$data; }
	@Override public ServerRailPaths.Request mtr$getRequest() { return mtr$request; }
	@Override public void mtr$setRequest(ServerRailPaths.Request request) { mtr$request = request; }
}
