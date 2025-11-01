#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <stdarg.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

int printf(const char *fmt, ...) {
  panic("Not implemented");
}

int vsprintf(char *out, const char *fmt, va_list ap) {
  panic("Not implemented");
}

int sprintf(char *out, const char *fmt, ...) {
#define _putch(ch) do{\
    *out=(ch);out++;\
    cnt++;\
}while(0)

    int cnt=0;
    va_list ap;
    va_start(ap,fmt);
    while(*fmt){
        if(*fmt=='%'){
            fmt++;
            switch(*fmt){
                case 's':{
                     const char* str=va_arg(ap,const char*);
                     while(*str){
                         _putch(*str);
                         str++;
                     }
                     break;
                         }
                case 'd':{
                     int d=va_arg(ap,int);
                     if(d<0){
                         _putch('-');
                         d=-d;
                     }
                     char buf[20];
                     char* end=buf+sizeof(buf);
                     char* p=end;
                     do{
                         p--;
                         *p='0'+d%10;
                         d/=10;
                     }while(d);
                     while(p!=end){
                         _putch(*p);
                         p++;
                     }
                     break;
                         }
                default:                     panic("s");
            }
        }
        else _putch(*fmt);
        fmt++;
    }
    *out=0;
    va_end(ap);
    return cnt;
}

int snprintf(char *out, size_t n, const char *fmt, ...) {
  panic("Not implemented");
}

int vsnprintf(char *out, size_t n, const char *fmt, va_list ap) {
  panic("Not implemented");
}

#endif
