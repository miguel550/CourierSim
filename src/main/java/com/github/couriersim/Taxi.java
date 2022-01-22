package com.github.couriersim;

import com.github.rinde.rinsim.core.model.pdp.*;
import com.github.rinde.rinsim.core.model.road.MovingRoadUser;
import com.github.rinde.rinsim.core.model.road.RoadModel;
import com.github.rinde.rinsim.core.model.road.RoadModels;
import com.github.rinde.rinsim.core.model.road.RoadUser;
import com.github.rinde.rinsim.core.model.time.TimeLapse;
import com.github.rinde.rinsim.geom.Point;
import com.google.common.base.Predicate;
import org.jetbrains.annotations.Nullable;

import javax.measure.unit.SI;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * Implementation of a very simple taxi agent. It moves to the closest customer,
 * picks it up, then delivers it, repeat.
 *
 * @author Rinde van Lon
 */
class Taxi extends Vehicle {
    private static final double SPEED = 1000d;
    private boolean shouldMoveToDepot = false;
    private String printed;
    private String role;
    private Parcel current_parcel;
    private double profit;
    private final double GAS_PRICE_PER_GALON = 270d;
    private final double KM_PER_GALON = 30d;
    private final double PERCENTAGE_KM = .01;


    Taxi(Point startPosition, int capacity) {
        super(VehicleDTO.builder()
                .capacity(capacity)
                .startPosition(startPosition)
                .speed(SPEED)
                .build());
        profit = 0;
        role = "Pickup";
    }

    public void setRole(String role) {
        this.role = role;
    }
    public double getProfit() {
        return profit;
    }

    @Override
    public void afterTick(TimeLapse timeLapse) {}

    private void printlnonce(String s){
        if (printed != s) {
            printed = s;
            System.out.println(s);
        }
    }

    protected void DialARideSolution(TimeLapse time) {
        final RoadModel rm = getRoadModel();
        final PDPModel pm = getPDPModel();

        if (!time.hasTimeLeft()) {
            return;
        }
        if (current_parcel == null) {
            current_parcel = RoadModels.findClosestObject(
                    rm.getPosition(this), rm, Parcel.class);
        }

        if (current_parcel == null) {
            return;
        }

        final boolean inCargo = pm.containerContains(this, current_parcel);
        // sanity check: if it is not in our cargo AND it is also not on the
        // RoadModel, we cannot go to curr anymore.
        if (!inCargo && !rm.containsObject(current_parcel)) {
            current_parcel = null;
        } else if (inCargo) {
            // if it is in cargo, go to its destination
            rm.moveTo(this, current_parcel.getDeliveryLocation(), time);
            if (rm.getPosition(this).equals(current_parcel.getDeliveryLocation())) {
                // deliver when we arrive
                pm.deliver(this, current_parcel, time);
            }
        } else {
            // it is still available, go there as fast as possible
            rm.moveTo(this, current_parcel, time);
            if (rm.equalPosition(this, current_parcel)) {
                // pickup customer
                pm.pickup(this, current_parcel, time);
            }
        }
    }

    enum Pred implements Predicate<RoadUser> {
        TAXIS {
            @Override
            public boolean apply(@Nullable RoadUser roadUser) {
                return roadUser instanceof Taxi;
            }
        },
        PARCELS {
            @Override
            public boolean apply(@Nullable RoadUser roadUser) {
                return roadUser instanceof Parcel;
            }
        },
        DEPOTS {
            @Override
            public boolean apply(@Nullable RoadUser roadUser) {
                return roadUser instanceof Depot;
            }
        }
    }

    protected void pickupAndDeliverySolutionNN(TimeLapse time) {
        final RoadModel rm = getRoadModel();
        final PDPModel pm = getPDPModel();

        if (!time.hasTimeLeft()) {
            return;
        }

        if (current_parcel != null) {
            if (pm.containerContains(this, current_parcel)) {
                rm.moveTo(this, current_parcel.getDeliveryLocation(), time);
                if (rm.getPosition(this).equals(current_parcel.getDeliveryLocation())) {
                    // deliver when we arrive
                    pm.deliver(this, current_parcel, time);
                    current_parcel = null;
                }
            } else {
                if (isParcelAlreadyTaken(current_parcel)) {
                    printlnonce("It's already taken!");
                    profit -= calculateParcelProfit(current_parcel);
                    current_parcel = pickClosestNonTakenParcel();
                    profit += calculateParcelProfit(current_parcel);
                    return;
                }
                printlnonce("Moving");
                rm.moveTo(this, current_parcel.getPickupLocation(), time);
                if (rm.getPosition(this).equals(current_parcel.getPickupLocation())) {
                    // pickup when we arrive
                    pm.pickup(this, current_parcel, time);
                    current_parcel = null;
                }
            }
            return;
        }

        Parcel closest_parcel = null;
        long dur = 0;
        for (Parcel parcel: pm.getContents(this)) {
            if (closest_parcel == null) {
                closest_parcel = parcel;
                dur = parcel.getDeliveryDuration();
            } else if (dur > parcel.getDeliveryDuration()){
                closest_parcel = parcel;
                dur = parcel.getDeliveryDuration();
            }
        }

        Parcel p = RoadModels.findClosestObject(rm.getPosition(this), rm, Parcel.class);
        if (p == null && closest_parcel != null) {
            printlnonce("Last parcel to be delivered?");
            current_parcel = closest_parcel;
            profit -= calculateMovingCost(current_parcel);
        } else if (p != null && closest_parcel != null) {
            double available_capacity = getAvailableCapacity();
            boolean is_closest_already_taken = isParcelAlreadyTaken(p);
            if (is_closest_already_taken || (p.getNeededCapacity() > available_capacity
                    || p.getPickupDuration() > closest_parcel.getDeliveryDuration())) {
                printlnonce("Trying to deliver the closest parcel");
                current_parcel = closest_parcel;
                profit -= calculateMovingCost(current_parcel);
            } else if (p.getNeededCapacity() <= available_capacity
                    && p.getPickupDuration() <= closest_parcel.getDeliveryDuration()) {
                printlnonce("Trying to pickup the closest parcel");
                current_parcel = p;
                profit += calculateParcelProfit(current_parcel);
            }
        } else if (p != null) {
            printlnonce("Trying to pickup the closest parcel (only option)");
            current_parcel = pickClosestNonTakenParcel();
            profit += calculateParcelProfit(current_parcel);
        }
    }

    protected void pickupAndDeliverySolutionMultiRegion(TimeLapse time) {
        if (Objects.equals(role, "Pickup")) {
            pickupRole(time);
        } else if (Objects.equals(role, "Delivery")) {
            deliveryRole(time);
        }
    }

    protected void deliveryRole(TimeLapse time) {
        final RoadModel rm = getRoadModel();
        final PDPModel pm = getPDPModel();
        Set<RoadUser> setdepots = rm.getObjects(Pred.DEPOTS);
        Depot depot = (Depot) setdepots.toArray()[0];
        if (!time.hasTimeLeft()) {
            return;
        }
        if (current_parcel != null) {
            rm.moveTo(this, current_parcel.getDeliveryLocation(), time);
            if (rm.getPosition(this).equals(current_parcel.getDeliveryLocation())) {
                // pickup when we arrive
                pm.deliver(this, current_parcel, time);
                current_parcel = null;
            }
            return;

        }
        if (pm.getContentsSize(this) > 0) {
            for(Parcel parcel: pm.getContents(this)) {
                current_parcel = parcel;
                profit -= calculateCostByDistance(current_parcel.getDeliveryLocation());
                return;
            }
        }

        if (pm.getContentsSize(depot) > 0) {
            rm.moveTo(this, depot, time);
            if (rm.getPosition(this).equals(rm.getPosition(depot))) {
                for (Parcel parcel: pm.getContents(depot)) {
                    if (getAvailableCapacity() < parcel.getNeededCapacity()) {
                        continue;
                    }
                    pm.addParcelIn(this, parcel);
                }
            }
        }
    }

    private boolean isTruckFull() {
        final PDPModel pm = getPDPModel();
        return pm.getContainerCapacity(this) == pm.getContentsSize(this);
    }
    protected void pickupRole(TimeLapse time) {
        final RoadModel rm = getRoadModel();
        final PDPModel pm = getPDPModel();
        Set<RoadUser> setdepots = rm.getObjects(Pred.DEPOTS);
        Depot depot = (Depot) setdepots.toArray()[0];

        if (!time.hasTimeLeft()) {
            return;
        }

        if (isTruckFull() || shouldMoveToDepot) {
            rm.moveTo(this, depot, time);
            if (rm.getPosition(this).equals(rm.getPosition(depot))) {
                // deliver when we arrive
                for (Parcel parcel: pm.getContents(this)) {
                    pm.drop(this, parcel, time);
                }
                shouldMoveToDepot = false;
            }
            return;
        }

        if (current_parcel != null) {
            if (isParcelAlreadyTaken(current_parcel)) {
                printlnonce("It's already taken!");
                profit -= calculateParcelProfit(current_parcel);
                current_parcel = pickClosestNonTakenParcel();
                profit += calculateParcelProfit(current_parcel);
                return;
            }
            printlnonce("Moving");
            rm.moveTo(this, current_parcel.getPickupLocation(), time);
            if (rm.getPosition(this).equals(current_parcel.getPickupLocation())) {
                // pickup when we arrive
                pm.pickup(this, current_parcel, time);
                if (isTruckFull()) {
                    profit -= calculateCostByDistance(rm.getPosition(depot));
                }
                current_parcel = null;
            }
            return;
        }


        Parcel p = RoadModels.findClosestObject(rm.getPosition(this), rm, Parcel.class);
        if (p != null) {
            double available_capacity = getAvailableCapacity();
            if (p.getNeededCapacity() <= available_capacity) {
                printlnonce("Trying to pickup the closest parcel");
                current_parcel = p;
                profit += calculateParcelProfit(current_parcel);
            } else {
                shouldMoveToDepot = true;
            }
        }
    }

    private double calculateParcelProfit(Parcel p) {
        printlnonce(String.valueOf(calculateMovingCost(p)));
        return calculateParcelCharge(p) - calculateMovingCost(p);
    }
    private double calculateParcelCharge(Parcel p) {
        if (p == null) return 0d;
        return (p.getNeededCapacity() - 1) * 30 + 100;
    }
    private double calculateMovingCost(Parcel p) {
        final PDPModel pm = getPDPModel();
        if (p == null) return 0d;
        if (pm.containerContains(this, p)) {
            return calculateCostByDistance(p.getDeliveryLocation());
        }
        return calculateCostByDistance(p.getPickupLocation());
    }
    private double calculateCostByDistance(Point point) {
        final RoadModel rm = getRoadModel();
        return rm.getDistanceOfPath(
                rm.getShortestPathTo(this, point)
        ).doubleValue(SI.KILOMETER) * PERCENTAGE_KM / KM_PER_GALON * GAS_PRICE_PER_GALON;
    }
    private boolean isParcelAlreadyTaken(Parcel p) {
        final RoadModel rm = getRoadModel();
        Set<RoadUser> settaxis = rm.getObjects(Pred.TAXIS);
        boolean is_closest_already_taken = false;
        for (RoadUser ru: settaxis) {
            if (ru == this) {
                continue;
            }
            Point point = rm.getDestination((MovingRoadUser) ru);
            if (point == null) continue;
            if(point.equals(p.getPickupLocation())) {
                is_closest_already_taken = true;
            }
        }
        return is_closest_already_taken;
    }

    private Parcel pickClosestNonTakenParcel() {
        for (Parcel parc: getAvailableParcels()){
            if (!isParcelAlreadyTaken(parc)) {
                return parc;
            }
        }
        return null;
    }
    private ArrayList<Parcel> getAvailableParcels() {
        final RoadModel rm = getRoadModel();
        Set<RoadUser> setparcels = rm.getObjects(Pred.PARCELS);
        ArrayList<Parcel> pl = new ArrayList<>();
        for (RoadUser parc: setparcels) {
            pl.add((Parcel) parc);
        }
        pl.sort(new PickupDurationComparator());
        return pl;
    }
    private double getAvailableCapacity() {
        final PDPModel pm = getPDPModel();
        return pm.getContainerCapacity(this) - pm.getContentsSize(this);
    }
    @Override
    protected void tickImpl(TimeLapse time) {
//        pickupAndDeliverySolutionNN(time);
        pickupAndDeliverySolutionMultiRegion(time);
    }
}
