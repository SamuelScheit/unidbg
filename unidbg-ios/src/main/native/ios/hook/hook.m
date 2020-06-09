#import <CydiaSubstrate/CydiaSubstrate.h>
#import <Fishhook/Fishhook.h>
#import "objc.h"

NSInteger (*old_integerForKey)(id self, SEL _cmd, NSString *defaultName) = NULL;

NSInteger new_integerForKey(id self, SEL _cmd, NSString *defaultName) {
  NSInteger ret = old_integerForKey(self, _cmd, defaultName);
  NSLog(@"NSUserDefaults integerForKey defaultName=%@, ret=%ld", defaultName, (long) ret);
  return ret;
}

NSString *(*old_pathForResource)(id self, SEL _cmd, NSString *name, NSString *ext) = NULL;

NSString *new_pathForResource(id self, SEL _cmd, NSString *name, NSString *ext) {
  NSString *ret = old_pathForResource(self, _cmd, name, ext);
  NSLog(@"NSBundle pathForResource name=%@, ext=%@, ret=%@", name, ext, ret);
  return ret;
}

extern objc_msg_function old_objc_msgSend;
extern objc_msgSend_callback callback;

void hook_objc_msgSend(objc_msgSend_callback _callback) {
  callback = _callback;
  rebind_symbols((struct rebinding[1]){{"objc_msgSend", (void *)new_objc_msgSend, (void **)&old_objc_msgSend}}, 1);
}

__attribute__((constructor))
void init() {
  NSLog(@"Initializing libhook");

  MSHookMessageEx([NSUserDefaults class], @selector(integerForKey:), (IMP) &new_integerForKey, (IMP *) &old_integerForKey);
  MSHookMessageEx([NSBundle class], @selector(pathForResource:ofType:), (IMP) &new_pathForResource, (IMP *) &old_pathForResource);

  NSLog(@"Initialized libhook");
}