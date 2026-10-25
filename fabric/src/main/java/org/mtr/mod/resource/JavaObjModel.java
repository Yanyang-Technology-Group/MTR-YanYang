package org.mtr.mod.resource;

import org.mtr.mapping.mapper.OptimizedModel;

/**
 * {@link OptimizedObjModel} over the original Java-built geometry.
 *
 * <p>A pure delegation: every call lands on {@code OptimizedModel.ObjModel} unchanged. It
 * exists so that the native backend can be selected without the rest of the model pipeline
 * needing to know which one it is talking to, and so that falling back is a matter of
 * constructing one of these instead.</p>
 *
 * <p>This is also the reference the native backend is verified against, so nothing here
 * should ever gain a behaviour the Java class does not have.</p>
 */
public final class JavaObjModel implements OptimizedObjModel {

	private final OptimizedModel.ObjModel objModel;

	public JavaObjModel(OptimizedModel.ObjModel objModel) {
		this.objModel = objModel;
	}

	/** The wrapped model, for callers that need the original type. */
	public OptimizedModel.ObjModel getObjModel() {
		return objModel;
	}

	/** Unwraps a model back to the Java type, or null when it is native-backed. */
	@javax.annotation.Nullable
	public static OptimizedModel.ObjModel unwrap(@javax.annotation.Nullable OptimizedObjModel model) {
		return model instanceof JavaObjModel ? ((JavaObjModel) model).objModel : null;
	}

	@Override
	public OptimizedObjModel copy() {
		return new JavaObjModel(objModel.copy());
	}

	@Override
	public void addTransformation(OptimizedModel.ShaderType shaderType, double x, double y, double z, boolean flipped) {
		objModel.addTransformation(shaderType, x, y, z, flipped);
	}

	@Override
	public float getMinX() {
		return objModel.getMinX();
	}

	@Override
	public float getMinY() {
		return objModel.getMinY();
	}

	@Override
	public float getMinZ() {
		return objModel.getMinZ();
	}

	@Override
	public float getMaxX() {
		return objModel.getMaxX();
	}

	@Override
	public float getMaxY() {
		return objModel.getMaxY();
	}

	@Override
	public float getMaxZ() {
		return objModel.getMaxZ();
	}

	@Override
	public void applyTranslation(double x, double y, double z) {
		objModel.applyTranslation(x, y, z);
	}

	@Override
	public void applyRotation(double x, double y, double z) {
		objModel.applyRotation(x, y, z);
	}

	@Override
	public void applyScale(double x, double y, double z) {
		objModel.applyScale(x, y, z);
	}

	@Override
	public void applyMirror(boolean x, boolean y, boolean z) {
		objModel.applyMirror(x, y, z);
	}
}
