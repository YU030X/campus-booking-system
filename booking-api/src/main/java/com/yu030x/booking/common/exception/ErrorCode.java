package com.yu030x.booking.common.exception;
public enum ErrorCode { INVALID_PARAMETER(40000,400), UNAUTHENTICATED(40100,401), FORBIDDEN(40300,403), NOT_FOUND(40400,404), USER_ERROR(41000,409), RESOURCE_ERROR(42000,409), BOOKING_ERROR(43000,409), INTERNAL_ERROR(50000,500); public final int code,httpStatus; ErrorCode(int c,int h){code=c;httpStatus=h;} }
