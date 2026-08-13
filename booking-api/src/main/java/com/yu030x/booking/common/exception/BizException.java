package com.yu030x.booking.common.exception; public class BizException extends RuntimeException { public final ErrorCode errorCode; public BizException(ErrorCode c,String m){super(m);errorCode=c;} }
