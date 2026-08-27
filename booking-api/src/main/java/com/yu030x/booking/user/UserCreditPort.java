package com.yu030x.booking.user;

/**
 * Transactional port owned by the user module for applying credit score
 * deductions. Implementations MUST join the caller's existing transaction and
 * compute the result atomically as max(0, currentCredit + scoreChange).
 */
public interface UserCreditPort {

    int applyDeduction(long userId, Integer scoreChange);
}
