/* Independent valid local descriptor readiness tests for the Android epoll ABI. */
typedef unsigned int u32;
typedef unsigned long u64;
typedef struct { u32 events; u32 padding; u64 data; } linux_epoll_event;
enum { EPOLL_IN=1, EPOLL_OUT=4, EPOLL_ADD=1, EPOLL_CLOEXEC=0x80000 };
static const u32 EPOLL_EDGE=1u<<31, EPOLL_ONESHOT=1u<<30;
extern int epoll_create1(int);
extern int epoll_ctl(int,int,int,linux_epoll_event*);
extern int epoll_wait(int,linux_epoll_event*,int,int);
extern long read(int,void*,unsigned long);
extern long write(int,const void*,unsigned long);
extern int socketpair(int,int,int,int[2]);
extern int close(int);

static int two_ready(u64 first, u64 second, u32 flags) {
    int a[2]={-1,-1}, b[2]={-1,-1};
    int ep=-1, rc=0;
    if (socketpair(1,1,0,a) || socketpair(1,1,0,b)) { rc=1; goto done; }
    ep=epoll_create1(EPOLL_CLOEXEC);
    if (ep<0) { rc=2; goto done; }
    linux_epoll_event left={EPOLL_IN|flags,0,first}, right={EPOLL_IN|flags,0,second}, found[4];
    if (epoll_ctl(ep,EPOLL_ADD,a[0],&left) || epoll_ctl(ep,EPOLL_ADD,b[0],&right)) {
        rc=3; goto done;
    }
    char value='a';
    if (write(a[1],&value,1)!=1 || write(b[1],&value,1)!=1) { rc=4; goto done; }
    int n=epoll_wait(ep,found,4,500);
    if (n!=2) { rc=5; goto done; }
    int first_count=0, second_count=0;
    for (int i=0;i<n;++i) {
        if (!(found[i].events&EPOLL_IN)) { rc=6; goto done; }
        first_count += found[i].data==first;
        second_count += found[i].data==second;
    }
    if (first==second ? first_count!=2 : (first_count!=1 || second_count!=1)) {
        rc=7; goto done;
    }
    if (read(a[0],&value,1)!=1 || read(b[0],&value,1)!=1 ||
        epoll_wait(ep,found,4,0)!=0) rc=8;
done:
    if (ep>=0) close(ep);
    for (int i=0;i<2;++i) {
        if (a[i]>=0) close(a[i]);
        if (b[i]>=0) close(b[i]);
    }
    return rc;
}
static int combined_filters(void) {
    int pair[2]={-1,-1}, rc=0;
    if (socketpair(1,1,0,pair)) return 10;
    int ep=epoll_create1(EPOLL_CLOEXEC);
    if (ep<0) { rc=11; goto done; }
    linux_epoll_event interest={EPOLL_IN|EPOLL_OUT,0,55}, found[4];
    if (epoll_ctl(ep,EPOLL_ADD,pair[0],&interest)) { rc=12; goto done; }
    char value='b';
    if (write(pair[1],&value,1)!=1) { rc=13; goto done; }
    int n=epoll_wait(ep,found,4,500);
    if (n!=1 || found[0].data!=55 ||
        (found[0].events&(EPOLL_IN|EPOLL_OUT))!=(EPOLL_IN|EPOLL_OUT)) rc=14;
done:
    if (ep>=0) close(ep);
    close(pair[0]); close(pair[1]);
    return rc;
}

__attribute__((visibility("default"))) int fixture_epoll_identity(int mode) {
    switch (mode) {
        case 0: return two_ready(0,0,0);
        case 1: return two_ready(73,73,0);
        case 2: return two_ready(0,73,0);
        case 3: return combined_filters();
        case 4: return two_ready(73,73,EPOLL_EDGE);
        case 5: return two_ready(73,73,EPOLL_ONESHOT);
        default: return 99;
    }
}
