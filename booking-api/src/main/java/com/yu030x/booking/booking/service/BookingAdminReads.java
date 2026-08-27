package com.yu030x.booking.booking.service;

import com.yu030x.booking.booking.vo.BookingView;
import com.yu030x.booking.common.api.PageResult;

/**
 * T09 handoff port: read-only pending-approval paging for the admin approval
 * list. Callers receive view objects only and never touch booking persistence.
 */
public interface BookingAdminReads {
    PageResult<BookingView> pagePendingApprovals(int pageNumber, int pageSize);
}
