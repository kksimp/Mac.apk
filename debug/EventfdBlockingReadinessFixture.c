/* Valid ordinary eventfd notifications and pthread wakeups. */
typedef unsigned int u32;
typedef unsigned long u64;
typedef unsigned long thread_t;
typedef struct { unsigned int words[4]; } sem_t_;
typedef struct { long sec,nsec; } timespec_;
typedef struct { int fd; short events,revents; } pollfd_;
typedef struct { u32 events,padding; u64 data; } epoll_event_;
enum { EFD_SEMAPHORE=1, NONBLOCK=0x800, CLOEXEC=0x80000, POLL_IN=1, EPOLL_IN=1, EPOLL_ADD=1 };
extern int eventfd(u32,int);
extern int eventfd_read(int,u64*);
extern int eventfd_write(int,u64);
extern long read(int,void*,unsigned long);
extern long syscall(long,...);
extern int close(int);
extern int dup(int);
extern int poll(pollfd_*,unsigned long,int);
extern int epoll_create1(int);
extern int epoll_ctl(int,int,int,epoll_event_*);
extern int epoll_wait(int,epoll_event_*,int,int);
extern int nanosleep(const timespec_*,timespec_*);
extern int pthread_create(thread_t*,const void*,void*(*)(void*),void*);
extern int pthread_join(thread_t,void**);
extern int sem_init(sem_t_*,int,unsigned int);
extern int sem_wait(sem_t_*);
extern int sem_post(sem_t_*);
extern int sem_destroy(sem_t_*);
extern int* __errno(void);

static int poll_ready(int fd,int expected) {
    pollfd_ p={fd,POLL_IN,0};
    int r=poll(&p,1,0);
    return (r==expected && (!expected || (p.revents&POLL_IN))) ? 0 : 1;
}
static int epoll_ready(int fd,int expected) {
    int ep=epoll_create1(CLOEXEC), rc=0;
    if (ep<0) return 2;
    epoll_event_ interest={EPOLL_IN,0,123}, result;
    if (epoll_ctl(ep,EPOLL_ADD,fd,&interest)) rc=3;
    else {
        int n=epoll_wait(ep,&result,1,0);
        if (n!=expected || (expected && (!(result.events&EPOLL_IN) || result.data!=123))) rc=4;
    }
    close(ep);
    return rc;
}
struct Reader {
    int fd,wrapper,done,result;
    u64 value;
    sem_t_ ready;
};
static void* reader(void* opaque) {
    struct Reader* r=opaque;
    sem_post(&r->ready);
    if (r->wrapper==2) r->result=syscall(63L,(long)r->fd,&r->value,8L,0L,0L,0L)==sizeof(r->value)?0:-1;
    else if (r->wrapper) r->result=eventfd_read(r->fd,&r->value);
    else r->result=read(r->fd,&r->value,sizeof(r->value))==sizeof(r->value)?0:-1;
    __atomic_store_n(&r->done,1,__ATOMIC_RELEASE);
    return 0;
}
static int blocking_read(int readfd,int writefd,int wrapper,u64 written,u64 expected) {
    struct Reader r={.fd=readfd,.wrapper=wrapper,.done=0,.result=-1,.value=0};
    thread_t t;
    if (sem_init(&r.ready,0,0)) return 1;
    if (pthread_create(&t,0,reader,&r)) { sem_destroy(&r.ready); return 2; }
    int waited=sem_wait(&r.ready);
    // Give the zero-counter read time to enter its blocking wait. The main
    // thread supplies the notification only after the worker announces entry.
    const timespec_ delay={0,30000000L};
    nanosleep(&delay,0);
    int early=__atomic_load_n(&r.done,__ATOMIC_ACQUIRE);
    int wrote=eventfd_write(writefd,written);
    int joined=pthread_join(t,0);
    sem_destroy(&r.ready);
    if (waited || early || wrote || joined) return 3;
    if (r.result || r.value!=expected) return 4;
    return 0;
}
static int blocking_empty(int wrapper,int semaphore,int check_epoll) {
    int fd=eventfd(0,CLOEXEC|(semaphore?EFD_SEMAPHORE:0)), rc=0;
    if (fd<0) return 10;
    if (poll_ready(fd,0)) { rc=11; goto finish; }
    if (blocking_read(fd,fd,wrapper,semaphore?1:7,semaphore?1:7)) { rc=12; goto finish; }
    if (check_epoll ? epoll_ready(fd,0) : poll_ready(fd,0)) rc=13;
finish: close(fd); return rc;
}
static int semaphore_remaining(void) {
    int fd=eventfd(0,CLOEXEC|EFD_SEMAPHORE), rc=0;
    if (fd<0) return 20;
    if (blocking_read(fd,fd,1,3,1)) { rc=21; goto finish; }
    if (poll_ready(fd,1) || epoll_ready(fd,1)) { rc=22; goto finish; }
    u64 n;
    for (int i=0;i<2;++i) {
        if (eventfd_read(fd,&n) || n!=1) { rc=23; goto finish; }
        if (poll_ready(fd,i==0?1:0)) { rc=24; goto finish; }
    }
    if (epoll_ready(fd,0)) rc=25;
finish: close(fd); return rc;
}
static int nonblocking_control(void) {
    int fd=eventfd(0,CLOEXEC|NONBLOCK), rc=0;
    if (fd<0) return 30;
    u64 n;
    if (eventfd_write(fd,2) || eventfd_write(fd,3) || poll_ready(fd,1) || epoll_ready(fd,1)) { rc=31; goto finish; }
    if (eventfd_read(fd,&n) || n!=5 || poll_ready(fd,0) || epoll_ready(fd,0)) { rc=32; goto finish; }
    if (eventfd_read(fd,&n)!=-1 || *__errno()!=11) rc=33;
finish: close(fd); return rc;
}
static int repeated_wakeups(void) {
    int fd=eventfd(0,CLOEXEC), rc=0;
    if (fd<0) return 40;
    for (int i=1;i<=3;++i) {
        if (blocking_read(fd,fd,i%2,i,i) || poll_ready(fd,0) || epoll_ready(fd,0)) { rc=41; break; }
    }
    close(fd); return rc;
}
static int alias_wakeup(void) {
    int fd=eventfd(0,CLOEXEC), other=-1, rc=0;
    if (fd<0) return 50;
    other=dup(fd);
    if (other<0) { rc=51; goto finish; }
    if (blocking_read(other,fd,1,9,9)) { rc=52; goto finish; }
    if (poll_ready(fd,0) || poll_ready(other,0) || epoll_ready(other,0)) rc=53;
finish: if (other>=0) close(other); close(fd); return rc;
}
static int two_waiters(void) {
    int fd=eventfd(0,CLOEXEC|EFD_SEMAPHORE), rc=0, created=0, initialized=0;
    thread_t threads[2];
    struct Reader readers[2]={0};
    if (fd<0) return 60;
    for (int i=0;i<2;++i) {
        readers[i].fd=fd; readers[i].wrapper=i; readers[i].result=-1;
        if (sem_init(&readers[i].ready,0,0)) { rc=61; break; }
        ++initialized;
        if (pthread_create(&threads[i],0,reader,&readers[i])) { rc=62; break; }
        ++created;
    }
    for (int i=0;i<created;++i) if (sem_wait(&readers[i].ready)) rc=63;
    const timespec_ delay={0,30000000L};
    nanosleep(&delay,0);
    for (int i=0;i<created;++i) if (__atomic_load_n(&readers[i].done,__ATOMIC_ACQUIRE)) rc=64;
    if (created && eventfd_write(fd,(u64)created)) rc=65;
    for (int i=0;i<created;++i) {
        if (pthread_join(threads[i],0) || readers[i].result || readers[i].value!=1) rc=66;
    }
    for (int i=0;i<initialized;++i) sem_destroy(&readers[i].ready);
    if (!rc && (poll_ready(fd,0) || epoll_ready(fd,0))) rc=67;
    close(fd); return rc;
}
__attribute__((visibility("default"))) int fixture_eventfd_blocking(int kind) {
    switch(kind) {
        case 0: return blocking_empty(0,0,0);
        case 1: return blocking_empty(1,0,1);
        case 2: return blocking_empty(1,1,0);
        case 3: return semaphore_remaining();
        case 4: return nonblocking_control();
        case 5: return repeated_wakeups();
        case 6: return alias_wakeup();
        case 7: return two_waiters();
        case 8: return blocking_empty(2,0,0);
        default: return 99;
    }
}
