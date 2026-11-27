/* Ordinary Android timerfd API exercises, independent of any application. */
typedef unsigned long u64;
typedef struct { long sec, nsec; } timespec_;
typedef struct { timespec_ interval, value; } itimerspec_;
typedef struct { int fd; short events, revents; } pollfd_;
enum { REALTIME_ID=0, MONOTONIC_ID=1, NONBLOCK=0x800, CLOEXEC=0x80000,
       ABSTIME=1, POLL_IN=1, F_DUPFD_CLOEXEC_LINUX=1030 };
extern int timerfd_create(int,int);
extern int timerfd_gettime(int,itimerspec_*);
extern int timerfd_settime(int,int,const itimerspec_*,itimerspec_*);
extern int clock_gettime(int,timespec_*);
extern int nanosleep(const timespec_*,timespec_*);
extern int poll(pollfd_*,unsigned long,int);
extern long read(int,void*,unsigned long);
extern int close(int);
extern int dup(int);
extern int dup2(int,int);
extern int dup3(int,int,int);
extern long syscall(long,...);
extern int fcntl(int,int,...);
extern int* __errno(void);

static const itimerspec_ clear = {{0,0},{0,0}};
static long ns(timespec_ t) { return t.sec*1000000000L+t.nsec; }
static int bounded(timespec_ t,long max_ns) {
    return t.sec>=0 && t.sec<=5 && t.nsec>=0 && t.nsec<1000000000L && ns(t)>0 && ns(t)<=max_ns;
}
static void sleep_ms(long ms) {
    timespec_ t={ms/1000,(ms%1000)*1000000L};
    (void)nanosleep(&t,0);
}
static void done(int fd) {
    if (fd>=0) { timerfd_settime(fd,0,&clear,0); close(fd); }
}
static int ready(int fd,int ms) {
    pollfd_ p={fd,POLL_IN,0};
    int r=poll(&p,1,ms);
    if (r==1 && !(p.revents&POLL_IN)) return -1;
    return r;
}
static int relative_state(void) {
    int fd=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC), rc=0;
    if (fd<0) return 1;
    const itimerspec_ initial={{0,0},{2,0}}, next={{0,0},{3,0}};
    itimerspec_ a,b,old;
    if (timerfd_gettime(fd,&a) || ns(a.interval) || ns(a.value)) { rc=2; goto finish; }
    if (timerfd_settime(fd,0,&initial,0) || timerfd_gettime(fd,&a) ||
        !bounded(a.value,2000000000L) || ns(a.interval)) { rc=3; goto finish; }
    sleep_ms(30);
    if (timerfd_gettime(fd,&b) || !bounded(b.value,2000000000L) ||
        ns(b.value)>=ns(a.value) || ns(b.interval)) { rc=4; goto finish; }
    if (timerfd_settime(fd,0,&next,&old) || !bounded(old.value,ns(b.value)) || ns(old.interval)) { rc=5; goto finish; }
    if (timerfd_gettime(fd,&a) || !bounded(a.value,3000000000L) || ns(a.value)<=2000000000L || ns(a.interval)) { rc=6; goto finish; }
    if (timerfd_settime(fd,0,&clear,&old) || !bounded(old.value,ns(a.value)) || ns(old.interval)) { rc=7; goto finish; }
    if (timerfd_gettime(fd,&a) || ns(a.value) || ns(a.interval)) rc=8;
finish: done(fd); return rc;
}
static int periodic_state(void) {
    int fd=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC), rc=0;
    if (fd<0) return 10;
    const itimerspec_ arm={{0,40000000L},{0,30000000L}};
    itimerspec_ current,old;
    u64 count=0;
    if (timerfd_settime(fd,0,&arm,0)) { rc=11; goto finish; }
    sleep_ms(190);
    if (timerfd_gettime(fd,&current) || !bounded(current.value,40000000L) || ns(current.interval)!=40000000L) { rc=12; goto finish; }
    if (ready(fd,500)!=1 || read(fd,&count,sizeof(count))!=sizeof(count) || count==0) { rc=13; goto finish; }
    if (timerfd_settime(fd,0,&clear,&old) || !bounded(old.value,40000000L) || ns(old.interval)!=40000000L) { rc=14; goto finish; }
    if (timerfd_gettime(fd,&current) || ns(current.value) || ns(current.interval) || ready(fd,0)!=0) rc=15;
finish: done(fd); return rc;
}
static int one_shot_wakeup(void) {
    int fd=timerfd_create(MONOTONIC_ID,CLOEXEC), rc=0;
    if (fd<0) return 20;
    const itimerspec_ arm={{0,0},{0,40000000L}};
    itimerspec_ current;
    u64 count=0;
    if (timerfd_settime(fd,0,&arm,0)) { rc=21; goto finish; }
    if (read(fd,&count,sizeof(count))!=sizeof(count) || count!=1) { rc=22; goto finish; }
    if (timerfd_gettime(fd,&current) || ns(current.interval) || ns(current.value) || ready(fd,0)!=0) rc=23;
finish: done(fd); return rc;
}
static int rearm_pending(void) {
    int fd=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC), rc=0;
    if (fd<0) return 30;
    const itimerspec_ first={{0,0},{0,30000000L}}, next={{0,0},{2,0}};
    itimerspec_ old, current;
    u64 count;
    if (timerfd_settime(fd,0,&first,0) || ready(fd,1000)!=1) { rc=31; goto finish; }
    if (timerfd_settime(fd,0,&next,&old) || ns(old.value) || ns(old.interval)) { rc=32; goto finish; }
    if (ready(fd,0)!=0 || read(fd,&count,sizeof(count))!=-1 || *__errno()!=11) { rc=33; goto finish; }
    if (timerfd_gettime(fd,&current) || !bounded(current.value,2000000000L)) rc=34;
finish: done(fd); return rc;
}
static int absolute_state(int clock_id) {
    int fd=timerfd_create(clock_id,NONBLOCK|CLOEXEC), rc=0;
    if (fd<0) return 40;
    itimerspec_ arm={{0,0},{0,0}}, current,old;
    if (clock_gettime(clock_id,&arm.value)) { rc=41; goto finish; }
    arm.value.nsec+=300000000L;
    if (arm.value.nsec>=1000000000L) { arm.value.nsec-=1000000000L; ++arm.value.sec; }
    if (timerfd_settime(fd,ABSTIME,&arm,0) || timerfd_gettime(fd,&current) ||
        !bounded(current.value,300000000L) || ns(current.interval)) { rc=42; goto finish; }
    u64 count=0;
    if (ready(fd,1500)!=1 || read(fd,&count,sizeof(count))!=sizeof(count) || count!=1) { rc=43; goto finish; }
    if (timerfd_settime(fd,0,&clear,&old) || ns(old.value) || ns(old.interval)) rc=44;
finish: done(fd); return rc;
}
static int alias_state(int mode) {
    int fd=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC), copy=-1, rc=0;
    if (fd<0) return 50;
    const itimerspec_ first={{0,0},{2,0}}, second={{0,100000000L},{0,300000000L}};
    itimerspec_ current,old;
    if (mode<2) copy=mode ? fcntl(fd,F_DUPFD_CLOEXEC_LINUX,0) : dup(fd);
    else {
        // Duplicate into a separate, initially disarmed descriptor.
        copy=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC);
        if (copy<0 || (mode==2 ? dup2(fd,copy) : dup3(fd,copy,CLOEXEC))!=copy) { rc=51; goto finish; }
    }
    if (copy<0) { rc=51; goto finish; }
    if (timerfd_settime(fd,0,&first,0) || timerfd_gettime(copy,&current) ||
        !bounded(current.value,2000000000L)) { rc=52; goto finish; }
    if (timerfd_settime(copy,0,&second,&old) || !bounded(old.value,2000000000L)) { rc=53; goto finish; }
    if (timerfd_gettime(fd,&current) || !bounded(current.value,300000000L) || ns(current.interval)!=100000000L) { rc=54; goto finish; }
    if (close(fd)) { rc=55; goto finish; }
    fd=-1;
    if (ready(copy,1500)!=1) { rc=56; goto finish; }
    u64 count=0;
    if (read(copy,&count,sizeof(count))!=sizeof(count) || count==0) { rc=57; goto finish; }
    if (timerfd_settime(copy,0,&clear,&old) || ns(old.interval)!=100000000L ||
        !bounded(old.value,100000000L)) rc=58;
finish: if (fd>=0) done(fd); if (copy>=0) done(copy); return rc;
}
static int old_value_alias(void) {
    int fd=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC), rc=0;
    if (fd<0) return 70;
    const itimerspec_ first={{0,0},{2,0}};
    itimerspec_ next={{0,0},{3,0}}, current;
    if (timerfd_settime(fd,0,&first,0) || timerfd_settime(fd,0,&next,&next) ||
        !bounded(next.value,2000000000L) || ns(next.interval)) { rc=71; goto finish; }
    if (timerfd_gettime(fd,&current) || !bounded(current.value,3000000000L) || ns(current.value)<=2000000000L) rc=72;
finish: done(fd); return rc;
}
static int independent_timers(void) {
    int a=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC), b=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC), rc=0;
    const itimerspec_ first={{0,0},{2,0}}, second={{0,100000000L},{3,0}};
    itimerspec_ current,old;
    if (a<0 || b<0) { rc=90; goto finish; }
    if (timerfd_settime(a,0,&first,0) || timerfd_settime(b,0,&second,0)) { rc=91; goto finish; }
    if (timerfd_settime(a,0,&clear,&old) || !bounded(old.value,2000000000L) || ns(old.interval)) { rc=92; goto finish; }
    if (timerfd_gettime(b,&current) || !bounded(current.value,3000000000L) ||
        ns(current.value)<=2000000000L || ns(current.interval)!=100000000L) rc=93;
finish: done(a); done(b); return rc;
}
static int periodic_repeated_wakeup(void) {
    int fd=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC), rc=0;
    if (fd<0) return 100;
    const itimerspec_ arm={{0,80000000L},{0,30000000L}};
    u64 count=0;
    if (timerfd_settime(fd,0,&arm,0)) { rc=101; goto finish; }
    for (int i=0;i<3;++i) {
        if (ready(fd,1000)!=1 || read(fd,&count,sizeof(count))!=sizeof(count) || !count) { rc=102; goto finish; }
    }
finish: done(fd); return rc;
}
static int syscall_state(void) {
    // The standard libc syscall() entry and named functions share one object.
    int fd=(int)syscall(85L,(long)MONOTONIC_ID,(long)(NONBLOCK|CLOEXEC),0L,0L,0L,0L), rc=0;
    if (fd<0) return 110;
    const itimerspec_ arm={{0,0},{2,0}};
    itimerspec_ current,old;
    if (syscall(86L,(long)fd,0L,&arm,0L,0L,0L) || timerfd_gettime(fd,&current) ||
        !bounded(current.value,2000000000L)) { rc=111; goto finish; }
    if (syscall(87L,(long)fd,&current,0L,0L,0L,0L) || !bounded(current.value,2000000000L)) { rc=112; goto finish; }
    if (timerfd_settime(fd,0,&clear,&old) || !bounded(old.value,2000000000L) || ns(old.interval)) rc=113;
finish:
    timerfd_settime(fd,0,&clear,0);
    if (syscall(57L,(long)fd,0L,0L,0L,0L,0L) && !rc) rc=114;
    return rc;
}
static int close_long_timer(void) {
    int fd=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC);
    if (fd<0) return 80;
    const itimerspec_ arm={{0,0},{120,0}};
    if (timerfd_settime(fd,0,&arm,0)) { done(fd); return 81; }
    if (close(fd)) return 82;
    return 0;
}
static int disarmed_interval(void) {
    int fd=timerfd_create(MONOTONIC_ID,NONBLOCK|CLOEXEC), rc=0;
    if (fd<0) return 60;
    const itimerspec_ disarmed={{0,200000000L},{0,0}};
    itimerspec_ current,old;
    if (timerfd_settime(fd,0,&disarmed,0) || timerfd_gettime(fd,&current) ||
        ns(current.value) || ns(current.interval)!=200000000L) { rc=61; goto finish; }
    if (timerfd_settime(fd,0,&clear,&old) || ns(old.value) || ns(old.interval)!=200000000L || ready(fd,0)!=0) rc=62;
finish: done(fd); return rc;
}
__attribute__((visibility("default"))) int fixture_timer(int kind) {
    switch(kind) {
        case 0: return relative_state();
        case 1: return periodic_state();
        case 2: return one_shot_wakeup();
        case 3: return rearm_pending();
        case 4: return absolute_state(MONOTONIC_ID);
        case 5: return absolute_state(REALTIME_ID);
        case 6: return alias_state(0);
        case 7: return alias_state(1);
        case 8: return disarmed_interval();
        case 9: return old_value_alias();
        case 10: return alias_state(2);
        case 11: return alias_state(3);
        case 12: return independent_timers();
        case 13: return periodic_repeated_wakeup();
        case 14: return syscall_state();
        case 15: return close_long_timer();
        default: return 99;
    }
}
