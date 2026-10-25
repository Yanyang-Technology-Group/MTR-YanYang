package org.mtr.mod.data;

import org.mtr.core.data.PathData;
import org.mtr.core.data.Data;
import org.mtr.core.data.Rail;
import org.mtr.core.data.VehicleCar;
import org.mtr.core.tool.Utilities;
import org.mtr.mod.client.CustomResourceLoader;
import org.mtr.mod.client.MinecraftClientData;

import java.util.List;

/** Resolves each car's rail sound using the same progress and coupling layout as the core renderer. */
final class VehicleTrackSoundIndex {

	private final int[] indexes;

	VehicleTrackSoundIndex(int carCount) {
		indexes = new int[carCount];
	}

	void update(List<VehicleCar> cars, List<PathData> path, double railProgress, double totalVehicleLength, boolean reversed) {
		final int direction = reversed ? 1 : -1;
		double carProgress = railProgress - (reversed ? totalVehicleLength : 0);
		for (int i = 0; i < cars.size(); i++) {
			final VehicleCar car = cars.get(i);
			carProgress += direction * car.getTotalLength(i == 0, true);
			final double centerProgress = carProgress - direction * car.getLength() / 2;
			final PathData pathData = Utilities.getElement(path, Utilities.getIndexFromConditionalList(path, centerProgress));
			// Network vehicle paths cache geometry on a default rail without styles.
			// Resolve sound metadata from the current client rail, leaving that geometry intact.
			final Rail rail = pathData == null ? null : Data.tryGet(MinecraftClientData.getInstance().positionsToRail, pathData.getOrderedPosition1(), pathData.getOrderedPosition2(), pathData.getRail());
			indexes[i] = rail == null ? 0 : CustomResourceLoader.getRailRunSoundIndex(rail);
			carProgress += direction * (car.getTotalLength(true, false) - car.getLength());
		}
	}

	int get(int carNumber) {
		return indexes[carNumber];
	}
}
