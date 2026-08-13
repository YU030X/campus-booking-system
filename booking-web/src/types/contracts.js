export const ROLES = Object.freeze(['STUDENT','ADMIN']);
export const BOOKING_STATUS = Object.freeze(['PENDING_APPROVAL','CONFIRMED','CHECKED_IN','COMPLETED','REJECTED','CANCELLED','NO_SHOW']);
export const LEGAL_TRANSITIONS = Object.freeze({PENDING_APPROVAL:['CONFIRMED','REJECTED','CANCELLED'],CONFIRMED:['CHECKED_IN','CANCELLED','NO_SHOW'],CHECKED_IN:['COMPLETED']});
export const ERROR_RANGES = Object.freeze({general:[40000,40099],auth:[40100,40199],authorization:[40300,40399],notFound:[40400,40499],user:[41000,41099],resource:[42000,42099],booking:[43000,43099]});
export const PAGE = Object.freeze({pageNumber:1,pageSize:10,total:0,records:[]});
export const TIME = Object.freeze({format:'yyyy-MM-dd HH:mm:ss',timezone:'Asia/Shanghai',slotMinutes:30,alignment:[':00',':30'],interval:'[start,end)',crossDay:false,longSerialization:'string'});
