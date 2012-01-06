/*
 * Title: CloudSim Toolkit Description: CloudSim (Cloud Simulation) Toolkit for Modeling and
 * Simulation of Clouds Licence: GPL - http://www.gnu.org/copyleft/gpl.html
 * 
 * Copyright (c) 2009-2010, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

import org.cloudbus.cloudsim.lists.PeList;
import org.cloudbus.cloudsim.util.MathUtil;

/**
 * This is a Time-Shared VM Scheduler, which allows over-subscription. In other words, the scheduler
 * still allows the allocation of VMs that require more CPU capacity that is available.
 * Oversubscription results in performance degradation. Each virtual PE cannot be allocated more CPU
 * capacity than MIPS of a single PE.
 */
public class VmSchedulerTimeSharedOverSubscription extends VmSchedulerTimeShared {

	/**
	 * Instantiates a new vm scheduler time shared over subscription.
	 * 
	 * @param pelist the pelist
	 */
	public VmSchedulerTimeSharedOverSubscription(List<? extends Pe> pelist) {
		super(pelist);
	}

	/**
	 * Allocate pes for vm. The policy allows over-subscription. In other words, the policy still
	 * allows the allocation of VMs that require more CPU capacity that is available.
	 * Oversubscription results in performance degradation. Each virtual PE cannot be allocated more
	 * CPU capacity than MIPS of a single PE.
	 * 
	 * @param vmUid the vm uid
	 * @param mipsShareRequested the mips share requested
	 * 
	 * @return true, if successful
	 */
	@Override
	protected boolean allocatePesForVm(String vmUid, List<Double> mipsShareRequested) {
		double totalRequestedMips = 0;
		double peMips = getPeCapacity();
		for (Double mips : mipsShareRequested) {
			// each virtual PE of a VM must require not more than the capacity of a physical PE
			if (mips > peMips) {
				return false;
			}
			totalRequestedMips += mips;
		}

		getMipsMapRequested().put(vmUid, mipsShareRequested);
		setPesInUse(getPesInUse() + mipsShareRequested.size());

		if (getVmsMigratingIn().contains(vmUid)) {
			// the destination host only experience 10% of the migrating VM's MIPS
			totalRequestedMips *= 0.1;
		}

		if (getAvailableMips() >= totalRequestedMips) {
			List<Double> mipsShareAllocated = new ArrayList<Double>();
			for (Double mipsRequested : mipsShareRequested) {
				if (getVmsMigratingOut().contains(vmUid)) {
					// performance degradation due to migration = 10% MIPS
					mipsRequested *= 0.9;
				} else if (getVmsMigratingIn().contains(vmUid)) {
					// the destination host only experience 10% of the migrating VM's MIPS
					mipsRequested *= 0.1;
				}
				mipsShareAllocated.add(mipsRequested);
			}

			getMipsMap().put(vmUid, mipsShareAllocated);
			setAvailableMips(getAvailableMips() - totalRequestedMips);
		} else {
			redistributeMipsDueToOverSubscription();
		}

		return true;
	}

	/**
	 * This method recalculates distribution of MIPs among VMs considering eventual shortage of MIPS
	 * compared to the amount requested by VMs.
	 */
	protected void redistributeMipsDueToOverSubscription() {
		// First, we calculate the scaling factor - the MIPS allocation for all VMs will be scaled
		// proportionally
		double totalRequiredMipsByAllVms = 0;

		for (Entry<String, List<Double>> entry : getMipsMapRequested().entrySet()) {
			double requiredMipsByThisVm = MathUtil.sum(entry.getValue());
			if (getVmsMigratingIn().contains(entry.getKey())) {
				// the destination host only experience 10% of the migrating VM's MIPS
				requiredMipsByThisVm *= 0.1;
			}
			totalRequiredMipsByAllVms += requiredMipsByThisVm;
		}

		double totalAvailableMips = PeList.getTotalMips(getPeList());
		double scalingFactor = totalAvailableMips / totalRequiredMipsByAllVms;

		// Clear the old MIPS allocation
		getMipsMap().clear();

		// Update the actual MIPS allocated to the VMs
		for (Entry<String, List<Double>> entry : getMipsMapRequested().entrySet()) {
			String vmUid = entry.getKey();
			List<Double> requestedMips = entry.getValue();

			List<Double> updatedMipsAllocation = new ArrayList<Double>();
			for (Double mips : requestedMips) {
				if (getVmsMigratingOut().contains(vmUid)) {
					// the original amount is scaled
					mips *= scalingFactor;
					// performance degradation due to migration = 10% MIPS
					mips *= 0.9;
				} else if (getVmsMigratingIn().contains(vmUid)) {
					// the destination host only experiences 10% of the migrating VM's MIPS
					mips *= 0.1;
					// the final 10% are scaled
					mips *= scalingFactor;
				} else {
					mips *= scalingFactor;
				}

				updatedMipsAllocation.add(Math.floor(mips));
			}

			// add in the new map
			getMipsMap().put(vmUid, updatedMipsAllocation);

		}

		// As the host is oversubscribed, there no more available MIPS
		setAvailableMips(0);
	}

}
