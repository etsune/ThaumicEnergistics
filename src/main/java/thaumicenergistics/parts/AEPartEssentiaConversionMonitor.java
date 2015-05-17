package thaumicenergistics.parts;

import java.util.Collections;
import java.util.List;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;
import org.apache.commons.lang3.tuple.ImmutablePair;
import thaumcraft.api.aspects.Aspect;
import thaumicenergistics.aspect.AspectStack;
import thaumicenergistics.integration.tc.EssentiaConversionHelper;
import thaumicenergistics.integration.tc.EssentiaItemContainerHelper;
import thaumicenergistics.registries.AEPartsEnum;
import thaumicenergistics.util.EffectiveSide;
import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.security.MachineSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.client.texture.CableBusTextures;
import appeng.core.WorldSettings;
import appeng.util.InventoryAdaptor;
import appeng.util.Platform;

public class AEPartEssentiaConversionMonitor
	extends AEPartEssentiaStorageMonitor
{
	private static long DOUBLE_CLICK_TICKS = 2 * 20;

	private int lastPlayerSneakInteractionID = -1;

	private int lastPlayerSneakInteractionTick = 0;

	/**
	 * Network source representing this part.
	 */
	private MachineSource asMachineSource = new MachineSource( this );

	public AEPartEssentiaConversionMonitor()
	{
		super( AEPartsEnum.EssentiaConversionMonitor );

		this.darkCornerTexture = CableBusTextures.PartConversionMonitor_Colored;
		this.lightCornerTexture = CableBusTextures.PartConversionMonitor_Dark;
	}

	/**
	 * Drains the container at the specified index.
	 * 
	 * @param player
	 * @param slotIndex
	 * @return
	 */
	private boolean drainEssentiaContainer( final EntityPlayer player, final int slotIndex )
	{
		// Get the container
		ItemStack container = player.inventory.getStackInSlot( slotIndex );

		// Create request
		IAEFluidStack request = EssentiaConversionHelper.instance.createAEFluidStackFromItemEssentiaContainer( container );

		// Is there anything to request?
		if( request == null )
		{
			return false;
		}

		// Calculate how much to take from the container
		int drainAmount_E = (int)EssentiaConversionHelper.instance.convertFluidAmountToEssentiaAmount( request.getStackSize() );

		// Is there enough power?
		if( !this.extractPowerForEssentiaTransfer( drainAmount_E, Actionable.SIMULATE ) )
		{
			// Not enough power.
			return false;
		}

		// Inject fluid
		IAEFluidStack rejected = this.injectFluid( request, Actionable.MODULATE );

		// How much is left over?
		int rejectedAmount_E = (int)EssentiaConversionHelper.instance.convertFluidAmountToEssentiaAmount( rejected.getStackSize() );

		// Update the drain amount
		drainAmount_E = drainAmount_E - rejectedAmount_E;

		if( drainAmount_E <= 0 )
		{
			// Could not inject
			return false;
		}

		// Extract power
		this.extractPowerForEssentiaTransfer( drainAmount_E, Actionable.MODULATE );

		// Drain the container
		ImmutablePair<Integer, ItemStack> drained = EssentiaItemContainerHelper.instance.extractFromContainer( container, drainAmount_E );

		// Update the player inventory
		player.inventory.setInventorySlotContents( slotIndex, drained.right );

		return true;

	}

	/**
	 * Fills the container the player is holding.
	 * 
	 * @param player
	 * @param heldItem
	 * @return
	 */
	private boolean fillEssentiaContainer( final EntityPlayer player, final ItemStack heldItem )
	{
		// Is the item being held a label?
		if( EssentiaItemContainerHelper.instance.isLabel( heldItem ) )
		{
			// Set the label type
			EssentiaItemContainerHelper.instance.setLabelAspect( heldItem, this.trackedEssentia.getAspect() );
			return true;
		}

		// Does the player have extract permission?
		if( !this.doesPlayerHavePermission( player, SecurityPermissions.EXTRACT ) )
		{
			return false;
		}

		// Get how much is in the container
		int containerAmount = EssentiaItemContainerHelper.instance.getContainerStoredAmount( heldItem );

		// Is there existing essentia in the container?
		if( containerAmount > 0 )
		{
			// Get the container aspect
			Aspect containerAspect = EssentiaItemContainerHelper.instance.getAspectInContainer( heldItem );

			// Ensure it matches the tracker
			if( this.trackedEssentia.getAspect() != containerAspect )
			{
				return false;
			}
		}

		// Is there a jar label?
		Aspect jarLabelAspect = EssentiaItemContainerHelper.instance.getJarLabelAspect( heldItem );
		if( jarLabelAspect != null )
		{
			// Ensure it matches the tracker
			if( this.trackedEssentia.getAspect() != jarLabelAspect )
			{
				return false;
			}
		}

		// Get how much the container can hold
		int containerCapacity = EssentiaItemContainerHelper.instance.getContainerCapacity( heldItem );

		// Calculate how much to fill
		int amountToFill_E = containerCapacity - containerAmount;

		// Is the container full?
		if( amountToFill_E <= 0 )
		{
			// Container is full
			return false;
		}

		// Is there enough power?
		if( !this.extractPowerForEssentiaTransfer( amountToFill_E, Actionable.SIMULATE ) )
		{
			// Not enough power
			return false;
		}

		// Create the fluid stack
		IAEFluidStack request = EssentiaConversionHelper.instance
						.createAEFluidStackInEssentiaUnits( this.trackedEssentia.getAspect(), amountToFill_E );

		// Request the fluid
		IAEFluidStack extracted = this.extractFluid( request, Actionable.SIMULATE );

		// Was any extracted?
		if( ( extracted == null ) || ( extracted.getStackSize() <= 0 ) )
		{
			// None extracted
			return false;
		}
		// Update values based on how much was extracted
		request.setStackSize( extracted.getStackSize() );
		amountToFill_E = (int)EssentiaConversionHelper.instance.convertFluidAmountToEssentiaAmount( request.getStackSize() );

		// Fill the container
		ImmutablePair<Integer, ItemStack> filledContainer = EssentiaItemContainerHelper.instance.injectIntoContainer( heldItem, new AspectStack(
						this.trackedEssentia.getAspect(), amountToFill_E ) );

		// Could the container be filled?
		if( filledContainer == null )
		{
			return false;
		}

		// Take original container
		player.inventory.decrStackSize( player.inventory.currentItem, 1 );

		// Add filled container
		InventoryAdaptor adaptor = InventoryAdaptor.getAdaptor( player, ForgeDirection.UNKNOWN );
		ItemStack rejected = adaptor.addItems( filledContainer.right );
		if( rejected != null )
		{
			TileEntity te = this.tile;
			List<ItemStack> list = Collections.singletonList( rejected );
			Platform.spawnDrops( player.worldObj, te.xCoord + this.cableSide.offsetX, te.yCoord + this.cableSide.offsetY, te.zCoord +
							this.cableSide.offsetZ, list );
		}

		if( player.openContainer != null )
		{
			player.openContainer.detectAndSendChanges();
		}

		// Extract the fluid
		this.extractFluid( request, Actionable.MODULATE );

		// Take power
		this.extractPowerForEssentiaTransfer( amountToFill_E, Actionable.MODULATE );

		// Done
		return true;
	}

	/**
	 * Drains all containers in the players inventory.
	 * 
	 * @param player
	 */
	private void insertAllEssentiaIntoNetwork( final EntityPlayer player )
	{
		for( int slotIndex = 0; slotIndex < player.inventory.getSizeInventory(); ++slotIndex )
		{
			this.drainEssentiaContainer( player, slotIndex );
		}
	}

	/**
	 * Sets the last interaction trackers.
	 * 
	 * @param player
	 */
	private void markFirstClick( final EntityPlayer player )
	{
		// Set the ID
		this.lastPlayerSneakInteractionID = WorldSettings.getInstance().getPlayerID( player.getGameProfile() );

		// Set the time
		this.lastPlayerSneakInteractionTick = MinecraftServer.getServer().getTickCounter();
	}

	/**
	 * Returns true if double clicked.
	 * 
	 * @param player
	 * @return
	 */
	private boolean wasDoubleClick( final EntityPlayer player )
	{
		// Is this the same player that just used the monitor?
		if( ( this.lastPlayerSneakInteractionID != -1 ) &&
						( this.lastPlayerSneakInteractionID == WorldSettings.getInstance().getPlayerID( player.getGameProfile() ) ) )
		{
			// Was it a double click?
			if( MinecraftServer.getServer().getTickCounter() - this.lastPlayerSneakInteractionTick <= AEPartEssentiaConversionMonitor.DOUBLE_CLICK_TICKS )
			{
				// Reset last interaction trackers
				this.lastPlayerSneakInteractionID = -1;
				this.lastPlayerSneakInteractionTick = 0;
				return true;
			}
		}

		// Wrong player, or time between clicks to long.
		return false;
	}

	/**
	 * Extracts fluid from the ME network.
	 * 
	 * @param toExtract
	 * @param mode
	 * @return
	 */
	protected final IAEFluidStack extractFluid( final IAEFluidStack toExtract, final Actionable mode )
	{
		IMEMonitor<IAEFluidStack> monitor = this.gridBlock.getFluidMonitor();

		if( monitor == null )
		{
			return null;
		}

		return monitor.extractItems( toExtract, mode, this.asMachineSource );
	}

	// TODO: This should be generalized
	/**
	 * Injects fluid into the ME network.
	 * Returns what was not stored.
	 * 
	 * @param toInject
	 * @param mode
	 * @return
	 */
	protected final IAEFluidStack injectFluid( final IAEFluidStack toInject, final Actionable mode )
	{
		IMEMonitor<IAEFluidStack> monitor = this.gridBlock.getFluidMonitor();

		if( monitor == null )
		{
			return null;
		}

		return monitor.injectItems( toInject, mode, this.asMachineSource );
	}

	/**
	 * Attempts to fill an essentia container if monitor locked
	 */
	@Override
	protected boolean onActivatedWithEssentiaContainerOrLabel( final EntityPlayer player, final ItemStack heldItem )
	{
		// Is there nothing being tracked, or is the monitor unlocked?
		if( !this.trackedEssentia.isValid() || !this.isLocked() )
		{
			// Pass to super
			return super.onActivatedWithEssentiaContainerOrLabel( player, heldItem );
		}

		// Fill the container
		return this.fillEssentiaContainer( player, heldItem );

	}

	@Override
	public boolean onShiftActivate( final EntityPlayer player, final Vec3 position )
	{
		// Ignore client side.
		if( EffectiveSide.isClientSide() )
		{
			return true;
		}

		// Permission and activation checks
		if( !activationCheck( player ) )
		{
			return false;
		}

		// Does the player have inject permission?
		if( !this.doesPlayerHavePermission( player, SecurityPermissions.INJECT ) )
		{
			return false;
		}

		// Get the item the player is holding
		ItemStack heldItem = player.getCurrentEquippedItem();

		// Is the player holding an essentia container?
		if( !EssentiaItemContainerHelper.instance.isContainerOrLabel( heldItem ) )
		{
			// Not holding container
			return false;
		}

		// Shift-right-clicking attempts to insert the essentia into the network.
		// Shift-double-right-clicking attempts to insert all essentia in the players inventory into the network.

		// Is the item being held a label?
		if( EssentiaItemContainerHelper.instance.isLabel( heldItem ) )
		{
			// Can't do anything with a label.
			return false;
		}

		// Was it a double click?
		if( this.wasDoubleClick( player ) )
		{
			// Attempt to insert all essentia
			this.insertAllEssentiaIntoNetwork( player );
			return true;
		}

		// Drain the container
		boolean didDrain = this.drainEssentiaContainer( player, player.inventory.currentItem );
		if( didDrain )
		{
			this.markFirstClick( player );
		}

		return didDrain;
	}

}