package org.mtr.mod.resource;

import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mapping.annotation.MappedMethod;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.mapper.ModelPartExtension;
import org.mtr.mapping.mapper.OptimizedModel;
import org.mtr.mapping.mapper.OptimizedRenderer;
import org.mtr.mod.Init;
import org.mtr.mod.resource.nativeobj.NativeObjModel;
import org.mtr.mod.resource.nativeobj.NativeObjModelAdapter;
import org.mtr.mod.resource.nativeobj.NativeObjModelBuilder;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class OptimizedModelWrapper {
	/**
	 * Used as a fallback texture if a face does not have a texture
	 */
	public static final Identifier WHITE_TEXTURE = new Identifier(Init.MOD_ID, "textures/block/white.png");

	@Nullable
	final OptimizedModel optimizedModel;

	public static OptimizedModelWrapper fromMaterialGroups(@Nullable ObjectArrayList<MaterialGroupWrapper> materialGroupList) {
		return new OptimizedModelWrapper(OptimizedRenderer.hasOptimizedRendering() && materialGroupList != null ? OptimizedModel.fromMaterialGroups(materialGroupList.stream().map(materialGroup -> materialGroup.materialGroup).filter(Objects::nonNull).collect(Collectors.toList())) : null);
	}

	/**
	 * Builds the GPU model for one vehicle part condition, from whichever backend produced the
	 * geometry.
	 *
	 * <p>Both backends can appear in the same list, because a vehicle may declare several model
	 * groups and only some of them may be loadable natively. Each wrapper reports which it is,
	 * and the two sets are assembled separately before being concatenated — the same thing
	 * {@code new OptimizedModel(a, b)} does for the Java path.</p>
	 *
	 * <p>A native wrapper builds itself here rather than being built by the caller, because
	 * building is the point at which the native buffers are uploaded and released. Doing it
	 * once, at the moment the part condition is assembled, is what keeps the native memory
	 * alive for exactly as long as the upload needs it.</p>
	 */
	@Nullable
	public static OptimizedModelWrapper fromObjModelWrappers(@Nullable ObjectArrayList<ObjModelWrapper> objModels) {
		if (!OptimizedRenderer.hasOptimizedRendering() || objModels == null) {
			return null;
		}

		final List<OptimizedModel> combined = new ArrayList<>();
		final List<OptimizedModel.ObjModel> javaModels = new ArrayList<>();

		for (final ObjModelWrapper wrapper : objModels) {
			final NativeObjModelBuilder nativeBuilder = wrapper.getNativeBuilder();
			if (nativeBuilder != null) {
				final OptimizedModelWrapper built = nativeBuilder.build();
				if (built != null && built.optimizedModel != null) {
					combined.add(built.optimizedModel);
				}
			} else {
				final OptimizedModel.ObjModel javaModel = JavaObjModel.unwrap(wrapper.getObjModel());
				if (javaModel != null) {
					javaModels.add(javaModel);
				}
			}
		}

		if (javaModels.isEmpty() && combined.isEmpty()) {
			return null;
		}

		if (!javaModels.isEmpty()) {
			combined.add(OptimizedModel.fromObjModels(javaModels));
		}
		return new OptimizedModelWrapper(combined.size() == 1
				? combined.get(0)
				: new OptimizedModel(combined.toArray(new OptimizedModel[0])));
	}

	/**
	 * Wraps an already-built optimized model.
	 *
	 * <p>Package-private so the native builder can hand back its result without exposing a
	 * public constructor that would let callers bypass the optimized-rendering check. The
	 * native builder lives in a different package, so it goes through
	 * {@link #fromNativeModel} instead, which is public but still applies the check.</p>
	 */
	static OptimizedModelWrapper wrapNative(OptimizedModel optimizedModel) {
		return new OptimizedModelWrapper(OptimizedRenderer.hasOptimizedRendering() ? optimizedModel : null);
	}

	/**
	 * Wraps a model the native pipeline already built and uploaded.
	 *
	 * <p>Unlike {@link #wrapNative} this is public, because
	 * {@code org.mtr.mod.resource.nativeobj} is a separate package. The optimized-rendering
	 * check is still applied, so a caller cannot get a wrapper whose renderer would ignore it.</p>
	 */
	public static OptimizedModelWrapper fromNativeModel(OptimizedModel optimizedModel) {
		return wrapNative(optimizedModel);
	}

	private OptimizedModelWrapper(@Nullable OptimizedModel optimizedModel) {
		this.optimizedModel = optimizedModel;
	}

	public OptimizedModelWrapper(@Nullable OptimizedModelWrapper optimizedModel1, @Nullable OptimizedModelWrapper optimizedModel2) {
		final boolean nonNull1 = optimizedModel1 != null && optimizedModel1.optimizedModel != null;
		final boolean nonNull2 = optimizedModel2 != null && optimizedModel2.optimizedModel != null;
		if (OptimizedRenderer.hasOptimizedRendering()) {
			if (nonNull1 && nonNull2) {
				optimizedModel = new OptimizedModel(optimizedModel1.optimizedModel, optimizedModel2.optimizedModel);
			} else if (nonNull1) {
				optimizedModel = optimizedModel1.optimizedModel;
			} else if (nonNull2) {
				optimizedModel = optimizedModel2.optimizedModel;
			} else {
				optimizedModel = null;
			}
		} else {
			optimizedModel = null;
		}
	}

	public static final class MaterialGroupWrapper {

		@Nullable
		private final OptimizedModel.MaterialGroup materialGroup;

		@MappedMethod
		public MaterialGroupWrapper(OptimizedModel.ShaderType shaderType, Identifier texture) {
			materialGroup = OptimizedRenderer.hasOptimizedRendering() ? new OptimizedModel.MaterialGroup(shaderType, texture) : null;
		}

		@MappedMethod
		public void addCube(ModelPartExtension modelPart, double x, double y, double z, boolean flipped, int light) {
			if (materialGroup != null) {
				materialGroup.addCube(modelPart, x, y, z, flipped, light);
			}
		}
	}

	/**
	 * One model instance as a vehicle part sees it.
	 *
	 * <p>Exactly one of the two backends is populated. The Java model is present when the
	 * native pipeline is unavailable, which keeps the original path fully functional; the
	 * native model is present when it is. {@link #copy()} from {@code ModelPropertiesPart}
	 * picks the same backend for the copy, so a model never migrates between them.</p>
	 */
	public static final class ObjModelWrapper {

		/**
		 * The model as {@code ModelPropertiesPart} sees it.
		 *
		 * <p>Both backends present the same surface through {@link OptimizedObjModel}, so the
		 * part code never branches on which one is in use. The concrete type still matters for
		 * two things: {@link #getNativeBuilder()} and {@link JavaObjModel#unwrap}, which are how
		 * the assembled GPU model is built.</p>
		 */
		@Nullable
		private final OptimizedObjModel objModel;

		/**
		 * The staging builder for this instance, or null when the Java backend is in use.
		 *
		 * <p>One builder per instance is what makes the per-part isolation real: a shared builder
		 * would accumulate every part's placements into a single buffer and reproduce the
		 * cross-condition rendering bug this branch already fixed once.</p>
		 */
		@Nullable
		private NativeObjModelBuilder nativeBuilder;

		public ObjModelWrapper(OptimizedModel.ObjModel objModel) {
			this.objModel = new JavaObjModel(objModel);
			this.nativeBuilder = null;
		}

		/**
		 * Wraps a model from whichever backend produced it.
		 *
		 * <p>A native model is already a per-part copy — {@code NativeObjModel.copy()} returns a
		 * genuinely independent instance — so no second copy is made here. A Java model arrives
		 * already copied by the caller, matching the existing contract.</p>
		 */
		public ObjModelWrapper(OptimizedObjModel objModel) {
			if (objModel instanceof NativeObjModelAdapter) {
				final NativeObjModel nativeModel = ((NativeObjModelAdapter) objModel).getModel();
				this.objModel = objModel;
				this.nativeBuilder = nativeModel.createBuilder();
			} else {
				this.objModel = objModel;
				this.nativeBuilder = null;
			}
		}

		/** True when this instance's geometry comes from the native pipeline. */
		public boolean isNative() {
			return nativeBuilder != null;
		}

		/** The model, for the assembler. */
		@Nullable
		public OptimizedObjModel getObjModel() {
			return objModel;
		}

		/** The staging builder, or null for a Java-backed instance. */
		@Nullable
		public NativeObjModelBuilder getNativeBuilder() {
			return nativeBuilder;
		}

		public void addTransformation(OptimizedModel.ShaderType shaderType, double x, double y, double z, boolean flipped) {
			if (nativeBuilder != null) {
				nativeBuilder.addPlacement(shaderType.ordinal(), x, y, z, flipped);
			} else if (objModel != null) {
				objModel.addTransformation(shaderType, x, y, z, flipped);
			}
		}
	}
}
