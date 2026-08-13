package com.yu030x.booking.common.api;
import java.util.Set;
public enum BookingStatus { PENDING_APPROVAL, CONFIRMED, CHECKED_IN, COMPLETED, REJECTED, CANCELLED, NO_SHOW;
 public boolean canTransitionTo(BookingStatus to){ return switch(this){case PENDING_APPROVAL->Set.of(CONFIRMED,REJECTED,CANCELLED).contains(to);case CONFIRMED->Set.of(CHECKED_IN,CANCELLED,NO_SHOW).contains(to);case CHECKED_IN->to==COMPLETED;default->false;}; }
}
