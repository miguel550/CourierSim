package com.github.couriersim;

import com.github.rinde.rinsim.core.model.pdp.Parcel;

import java.util.Comparator;

public class PickupDurationComparator implements Comparator<Parcel> {
    @Override
    public int compare(Parcel o1, Parcel o2) {
        return (int) (o1.getPickupDuration() - o2.getPickupDuration());
    }
}
