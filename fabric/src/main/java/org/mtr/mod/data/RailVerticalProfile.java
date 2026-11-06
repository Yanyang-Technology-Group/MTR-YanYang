package org.mtr.mod.data;

public final class RailVerticalProfile {

	private RailVerticalProfile() {
	}

	public static double getHeight(double distance, double length, long startY, long endY, boolean sine) {
		if (startY == endY || length <= 0) {
			return startY;
		}
		final double progress = Math.max(0, Math.min(1, distance / length));
		final double profile = sine ? (1 - Math.cos(Math.PI * progress)) / 2 : progress * progress * (3 - 2 * progress);
		return startY + (endY - startY) * profile;
	}
}
