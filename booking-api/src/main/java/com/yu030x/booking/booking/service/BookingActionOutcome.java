package com.yu030x.booking.booking.service;

import com.yu030x.booking.booking.vo.BookingView;

public record BookingActionOutcome(Result result, BookingView booking) {
    public enum Result { WINNER, ALREADY_COMPLETED, ILLEGAL_TRANSITION, NOT_FOUND }

    public static BookingActionOutcome winner(BookingView booking) {
        return new BookingActionOutcome(Result.WINNER, booking);
    }

    public static BookingActionOutcome alreadyCompleted(BookingView booking) {
        return new BookingActionOutcome(Result.ALREADY_COMPLETED, booking);
    }

    public static BookingActionOutcome illegalTransition(BookingView booking) {
        return new BookingActionOutcome(Result.ILLEGAL_TRANSITION, booking);
    }

    public static BookingActionOutcome notFound() {
        return new BookingActionOutcome(Result.NOT_FOUND, null);
    }
}
