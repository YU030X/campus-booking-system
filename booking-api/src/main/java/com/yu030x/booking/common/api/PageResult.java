package com.yu030x.booking.common.api;
import java.util.List;
public record PageResult<T>(int pageNumber,int pageSize,long total,List<T> records){ public PageResult { if(pageSize<1||pageSize>100) throw new IllegalArgumentException("pageSize must be <= 100"); } }
