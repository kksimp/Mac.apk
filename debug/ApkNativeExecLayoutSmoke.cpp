#include "macapk/apk_native_exec.h"
#include <filesystem>
#include <fstream>
#include <vector>
#include <cassert>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <unistd.h>
namespace fs=std::filesystem;
static void write(const fs::path& p,const std::vector<unsigned char>& b){std::ofstream f(p,std::ios::binary);f.write((const char*)b.data(),b.size());}
int main(){
    char temp[]="/tmp/macapk-exec-layout-XXXXXX"; assert(mkdtemp(temp));fs::path root=temp,lib=root/"lib",abi=lib/"arm64",bin=root/"bin";
    fs::create_directories(abi);fs::create_directories(bin);
    fs::copy_file("/usr/bin/true",bin/"macapk-elfexec");std::ofstream(bin/"libmacapk-bionic-shim.dylib").put('\0');
    std::vector<unsigned char> elf(64);std::memcpy(elf.data(),"\177ELF",4);elf[4]=2;elf[5]=1;elf[16]=2;elf[24]=0x80;
    write(abi/"libhelper.so",elf);setenv("MACAPK_ELFEXEC","1",1);
    auto run=[&]{return macapk::ReconcileApkNativeExecutables(lib.string(),bin.string(),"","");};
    assert(run()==1);fs::path backup=lib/".macapk-elfexec"/"arm64";
    assert(fs::exists(backup/"libhelper.so"));
    for(auto& entry:fs::directory_iterator(abi))assert(!entry.is_directory());
    assert(run()==1); // idempotent
    fs::rename(backup,abi/".macapk-elfexec");assert(run()==1); // migrate old installs
    assert(!fs::exists(abi/".macapk-elfexec"));assert(fs::exists(backup/"libhelper.so"));
    setenv("MACAPK_ELFEXEC","0",1);assert(run()==0);
    std::ifstream f(abi/"libhelper.so",std::ios::binary);std::vector<unsigned char> restored((std::istreambuf_iterator<char>(f)),{});assert(restored==elf);
    fs::remove_all(root);std::cout<<"elfexec-layout: PASS (no ABI subdirectory, idempotence, legacy migration, byte-exact restore)\n";
}
