package org.mtr.screen;

import gg.essential.elementa.components.UIWrappedText;
import gg.essential.elementa.constraints.*;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;
import org.mtr.client.MinecraftClientData;
import org.mtr.generated.lang.TranslationProvider;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectObjectImmutablePair;
import org.mtr.packet.PacketDeleteRailAction;
import org.mtr.registry.RegistryClient;
import org.mtr.tool.GuiHelper;
import org.mtr.widget.BackgroundComponent;
import org.mtr.widget.ListComponent;
import org.mtr.widget.ListItem;
import org.mtr.widget.SlotBackgroundComponent;

import java.awt.*;

/**
 * Restores the legacy rail actions screen that was dropped in the 4.1 dashboard redesign.
 *
 * <p>The server broadcasts pending rail actions via {@link org.mtr.packet.PacketBroadcastRailActions};
 * this screen lists them and lets the player undo (delete) each action individually by sending
 * {@link PacketDeleteRailAction}, exactly like the pre-4.1 {@code RailActionsScreen} did.</p>
 */
public final class RailActionsScreen extends WindowBase {

	private final ListComponent<DashboardListItem> listComponent;

	public RailActionsScreen(@Nullable WindowBase previousScreen) {
		super(previousScreen);

		final BackgroundComponent backgroundComponent = new BackgroundComponent(getWindow(), ObjectImmutableList.of());
		new UIWrappedText(TranslationProvider.GUI_MTR_RAIL_ACTIONS.getString(), false)
			.setChildOf(backgroundComponent)
			.setWidth(new RelativeConstraint())
			.setColor(new Color(GuiHelper.MINECRAFT_GUI_TITLE_TEXT_COLOR));

		final SlotBackgroundComponent slotBackgroundComponent = (SlotBackgroundComponent) new SlotBackgroundComponent()
			.setChildOf(backgroundComponent)
			.setY(new SiblingConstraint(GuiHelper.DEFAULT_PADDING))
			.setWidth(new RelativeConstraint())
			.setHeight(new SubtractiveConstraint(new FillConstraint(), new PixelConstraint(GuiHelper.DEFAULT_PADDING)));

		listComponent = GuiHelper.createListComponent(slotBackgroundComponent);
	}

	@Override
	public void onTick() {
		super.onTick();

		ListComponent.setGeneric(
			listComponent,
			MinecraftClientData.getInstance().railActions,
			dashboardListItem -> dashboardListItem.getName(true),
			dashboardListItem -> dashboardListItem.getColor(true),
			ObjectArrayList.of(new ObjectObjectImmutablePair<>(GuiHelper.DELETE_TEXTURE_ID, (indexList, dashboardListItem) -> RegistryClient.sendPacketToServer(new PacketDeleteRailAction(dashboardListItem.id))))
		);
	}
}
