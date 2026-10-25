package org.mtr.mod.resource.nativeobj;

import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mod.resource.OptimizedObjModel;

/**
 * {@link OptimizedObjModel} over native geometry.
 *
 * <p>The native model already implements the whole surface, so this is a thin adapter rather
 * than a wrapper with logic of its own. Keeping the two types separate means the native
 * package does not have to depend on MTR's resource package beyond this one file, which keeps
 * the JNI-facing types testable on their own.</p>
 */
public final class NativeObjModelAdapter implements OptimizedObjModel {

	private final NativeObjModel model;

	public NativeObjModelAdapter(NativeObjModel model) {
		this.model = model;
	}

	/** The wrapped native model, for callers that need the texture table or key list. */
	public NativeObjModel getModel() {
		return model;
	}

	@Override
	public OptimizedObjModel copy() {
		return new NativeObjModelAdapter(model.copy());
	}

	@Override
	public void addTransformation(OptimizedModel.ShaderType shaderType, double x, double y, double z, boolean flipped) {
		model.addTransformation(shaderType, x, y, z, flipped);
	}

	@Override
	public float getMinX() {
		return model.getMinX();
	}

	@Override
	public float getMinY() {
		return model.getMinY();
	}

	@Override
	public float getMinZ() {
		return model.getMinZ();
	}

	@Override
	public float getMaxX() {
		return model.getMaxX();
	}

	@Override
	public float getMaxY() {
		return model.getMaxY();
	}

	@Override
	public float getMaxZ() {
		return model.getMaxZ();
	}

	@Override
	public void applyTranslation(double x, double y, double z) {
		model.applyTranslation(x, y, z);
	}

	@Override
	public void applyRotation(double x, double y, double z) {
		model.applyRotation(x, y, z);
	}

	@Override
	public void applyScale(double x, double y, double z) {
		model.applyScale(x, y, z);
	}

	@Override
	public void applyMirror(boolean x, boolean y, boolean z) {
		model.applyMirror(x, y, z);
	}
}
