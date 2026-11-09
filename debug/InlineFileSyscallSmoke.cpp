#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstdio>
#include <cstdlib>
#include <cerrno>
#include <cstdint>
#include <string>
#include <signal.h>
#include <sys/ucontext.h>
static struct sigaction prior;static volatile uint64_t seen[6];
static void observer(int sig,siginfo_t*info,void*context){auto*u=(ucontext_t*)context;for(int i=0;i<4;i++)seen[i]=u->uc_mcontext->__ss.__x[i];seen[4]=u->uc_mcontext->__ss.__x[16];seen[5]=u->uc_mcontext->__ss.__pc;prior.sa_sigaction(sig,info,context);}
using Access=long(*)(int,const char*,int);using OpenAt=long(*)(int,const char*,int,unsigned);
using Copy=void*(*)(void*,const void*,size_t);
using Protect=int(*)(void*,size_t,int);
static void check(bool ok,const char* what,long value=0){if(!ok){fprintf(stderr,"FAIL %s actual=%ld errno=%d\n",what,value,errno);exit(1);}}
int main(int argc,char**argv){if(argc!=2)return 2;void*h=dlopen(argv[1],RTLD_NOW|RTLD_LOCAL);if(!h){puts(dlerror());return 2;}auto base=(unsigned char*(*)())dlsym(h,"fixture_base");auto copy=(Copy)dlsym(h,"fixture_copy");auto protect=(Protect)dlsym(h,"fixture_protect");auto access=(Access)dlsym(h,"fixture_access");auto bsd=(Access)dlsym(h,"fixture_access_bsd");auto guest_open=(OpenAt)dlsym(h,"fixture_openat");auto bsd_open=(OpenAt)dlsym(h,"fixture_openat_bsd");check(base&&copy&&protect&&access&&bsd&&guest_open&&bsd_open,"exports");unsigned char*b=base();check(!protect(b,4096,PROT_READ|PROT_WRITE|PROT_EXEC),"authorize 4K code");uint32_t svc=0xd4000001;check(copy(b+0x498,&svc,4)==b+0x498,"publish real late svc");check(copy(b+0x4a8,&svc,4)==b+0x4a8,"publish BSD-selector svc");check(copy(b+0x4b8,&svc,4)==b+0x4b8&&copy(b+0x4c8,&svc,4)==b+0x4c8,"publish late openat svcs");
char temp[]="/private/tmp/macapk-inline-access.XXXXXX";char*dir=mkdtemp(temp);check(dir,"temporary fixture directory");std::string path=std::string(dir)+"/exists";int fd=open(path.c_str(),O_CREAT|O_WRONLY,0600);check(fd>=0,"create empty fixture file");close(fd);struct sigaction obs{};obs.sa_flags=SA_SIGINFO;obs.sa_sigaction=observer;sigemptyset(&obs.sa_mask);sigaction(SIGSYS,&obs,&prior);long result=access(-100,path.c_str(),F_OK);if(result)fprintf(stderr,"fixture path=%p observed x0=%llx x1=%llx x2=%llx x3=%llx x16=%llx pc=%llx\n",path.c_str(),seen[0],seen[1],seen[2],seen[3],seen[4],seen[5]);check(result==0,"existing absolute path",result);check((int)seen[0]==-100&&seen[1]==(uintptr_t)path.c_str()&&(int32_t)seen[4]<0,"Mach-selector arguments preserved");
std::string missing=std::string(dir)+"/missing";result=access(-100,missing.c_str(),F_OK);check(result==-ENOENT,"missing absolute path",result);
check(!chmod(path.c_str(),0000),"deny fixture read");errno=0;int expected=faccessat(AT_FDCWD,path.c_str(),R_OK,0);int error=errno;result=access(-100,path.c_str(),R_OK);check(result==(expected? -error:0),"actual permission result",result);chmod(path.c_str(),0600);
result=access(-9876,path.c_str(),F_OK);check(result==0,"absolute ignores dirfd",result);
result=access(-100,path.c_str(),0x1000);check(result==-EINVAL,"invalid mode",result);
result=access(-100,nullptr,F_OK);check(result==-EFAULT,"null path",result);result=access(-100,(const char*)1,F_OK);check(result==-EFAULT,"unreadable path",result);std::string longpath(4096,'x');longpath[0]='/';result=access(-100,longpath.c_str(),F_OK);check(result==-36,"Linux ENAMETOOLONG",result);
int dfd=open(dir,O_RDONLY);check(dfd>=0,"open dirfd fixture");result=access(dfd,"exists",F_OK);check(result==0,"real relative dirfd preserved",result);result=access(-9876,"exists",F_OK);check(result==-EBADF,"invalid relative dirfd",result);close(dfd);result=bsd(-100,path.c_str(),F_OK);check(result==-38,"BSD clobbered arguments remain Linux ENOSYS",result);check(seen[0]==78&&seen[1]==0,"BSD error return loses arguments");result=guest_open(-100,path.c_str(),0,0);check(result>=0,"openat existing absolute",result);struct stat info{};check(!fstat((int)result,&info)&&S_ISREG(info.st_mode)&&info.st_size==0,"openat returns real empty file");close((int)result);result=guest_open(-100,missing.c_str(),0,0);check(result==-ENOENT,"openat missing",result);chmod(path.c_str(),0000);errno=0;int hostfd=open(path.c_str(),O_RDONLY);error=errno;result=guest_open(-100,path.c_str(),0,0);if(hostfd<0)check(result==-error,"openat real permissions",result);else {close(hostfd);check(result>=0,"openat root permissions",result);}if(result>=0)close((int)result);chmod(path.c_str(),0600);result=guest_open(-9876,path.c_str(),0,0);check(result>=0,"openat absolute ignores dirfd",result);close((int)result);dfd=(int)guest_open(-100,dir,0x10000,0);check(dfd>=0,"openat real directory",dfd);result=guest_open(dfd,"exists",0,0);check(result>=0,"openat relative dirfd",result);close((int)result);close(dfd);result=guest_open(-9876,"exists",0,0);check(result==-EBADF,"openat invalid relative dirfd",result);result=guest_open(-100,nullptr,0,0);check(result==-EFAULT,"openat null path",result);result=bsd_open(-100,path.c_str(),0,0);check(result==-38,"BSD openat lost arguments unsupported",result);using Router=long(*)(long,long,long,long,long,long,long);
auto router=(Router)dlsym(h,"__macapk_arm64_syscall_impl");
std::string dangling=std::string(dir)+"/dangling";
check(router&&symlink("absent-target",dangling.c_str())==0,"dangling symlink fixture");
errno=0;result=router(48,-100,(long)dangling.c_str(),F_OK,0x100,0,0);
check(result==-1&&errno==ENOENT,"raw faccessat ignores fourth register",result);
using Access4=long(*)(int,const char*,int,int);
result=((Access4)access)(-100,dangling.c_str(),F_OK,0x100);
check(result==-ENOENT,"inline faccessat ignores fourth register",result);
unlink(dangling.c_str());unlink(path.c_str());rmdir(dir);puts("PASS late inline faccessat/openat: real exists/missing/permissions/invalid-mode/absolute-dirfd; real dirfd; BSD loss preserved");}
