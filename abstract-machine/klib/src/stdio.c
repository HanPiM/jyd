#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)

// return false to end format, also caller cnt will not +1
typedef bool(*putch_func)(int c,void* exinfo);

static int meta_printf(putch_func f_putch,void* exinfo,const char *fmt, va_list ap){

#define _putch(c) do{\
	if(!f_putch(c,exinfo))return cnt;\
	cnt++;\
}while(0)

    int cnt=0;
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
						 // When d=INT_MIN
						 // -d is overflow
                     }
                     char buf[20];
                     char* end=buf+sizeof(buf);
                     char* p=end;
					 int tmp;
                     do{
                         p--;
						 tmp=d%10;
						 // avoid -INT_MIN overflow
						 if(tmp<0)tmp=-tmp;
                         *p='0'+tmp;
                         d/=10;
                     }while(d);
                     while(p!=end){
                         _putch(*p);
                         p++;
                     }
                     break;
                         }
                default: panic("unimpl fmt");
            }
        }
        else _putch(*fmt);
        fmt++;
    }
    return cnt;
}

static bool printf_putch(int ch,void* v){
	putch(ch);
	return true;
}
int printf(const char *fmt, ...) {
	va_list ap;
	va_start(ap,fmt);
	int cnt=meta_printf(printf_putch,NULL,fmt,ap);
	va_end(ap);
	return cnt;
}

static bool sprintf_putch_to_str(int c,void* p){
	char** pout=(char**)p;
	char* out=*pout;
	*out=c;
	*pout=*pout+1;
	return true;
}

int vsprintf(char *out, const char *fmt, va_list ap) {
	char* cur=out;
	int cnt=meta_printf(sprintf_putch_to_str, &cur, fmt, ap);
	sprintf_putch_to_str(0, &cur);
	return cnt;
}
int sprintf(char *out, const char *fmt, ...) {
	va_list ap;
	va_start(ap,fmt);
	int cnt=vsprintf(out, fmt, ap);
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
